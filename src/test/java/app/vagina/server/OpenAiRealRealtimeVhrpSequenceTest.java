package app.vagina.server;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import app.vagina.server.realtime.VhrpTestClient;
import app.vagina.server.realtime.oai_cc.OaiCcWavEncoder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkiverse.wiremock.devservice.ConnectWireMock;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.vertx.core.Vertx;
import java.io.ByteArrayOutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Opt-in real-provider regression tests for OAI Realtime response coordination through VHRP.
 *
 * <p>Unlike {@link OpenAiRealVhrpSequenceTest}, which exercises the Chat Completions provider, this
 * class deliberately selects the OAI Realtime registry entry. The scenarios protect bugs that fake
 * providers cannot settle: create-pending cancellation, the provider's single-active-response rule,
 * and staggered multi-tool completion. Normal CI skips the class; callers must provide explicit
 * Realtime credentials and enable the shared real-OpenAI flag.
 */
@QuarkusTest
@ConnectWireMock
class OpenAiRealRealtimeVhrpSequenceTest {
  private static final String MODEL_ID = "voice-agent-prod-realtime";
  private static final String OPT_IN_PROPERTY = "vagina.test.openai.real.enabled";
  private static final String TRANSCRIPT_DIR_PROPERTY = "vagina.test.openai.real.transcript.dir";
  private static final String BASE_URL_PROPERTY =
      "vagina.realtime.models.voice-agent-prod-realtime.base-url";
  private static final String API_KEY_PROPERTY =
      "vagina.realtime.models.voice-agent-prod-realtime.api-key";
  private static final ObjectMapper JSON = new ObjectMapper();

  @TestHTTPResource("/")
  URL testServerUrl;

  private Vertx vertx;
  private VhrpTestClient client;

  @BeforeEach
  void setUp() {
    assumeTrue(realTestEnabled(), "set -Dvagina.test.openai.real.enabled=true to run");
    assumeTrue(hasRealRealtimeConfig(), "set OAI_REALTIME_BASE_URL and OAI_REALTIME_API_KEY");
    vertx = Vertx.vertx();
    client = new VhrpTestClient(vertx);
  }

  @AfterEach
  void tearDown() throws Exception {
    if (client != null) {
      client.close();
    }
    if (vertx != null) {
      vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }
  }

  /** Active output interruption remains the ordinary, non-error barge-in path. */
  @Test
  void activeInterruptThenImmediateTurnCompletesWithoutProviderConflict() throws Exception {
    openSession(false, Map.of());
    assertAccepted(
        client.sendSessionInstructionsSet(
            "For the first request count slowly. For the recovery request reply with exactly "
                + "REALTIME_INTERRUPT_OK."));

    assertAccepted(
        client.sendTurnTextSubmit(clientItemId(), "Count slowly from one to one hundred."));
    waitForPatchText("one", 60);
    int boundary = client.allReceived().size();
    client.sendAssistantInterrupt("real_realtime_active_interrupt");
    assertAccepted(
        client.sendTurnTextSubmit(
            clientItemId(), "Recovery request: reply REALTIME_INTERRUPT_OK."));

    waitForPatchTextAfter("REALTIME_INTERRUPT_OK", boundary, 60);
    assertNoErrorFrames();
    writeArtifacts("realtimeActiveInterrupt");
  }

  /**
   * A second turn arriving while the first create is pending supersedes the first generation. Both
   * user items stay in history, but only the latest turn owns the one provider response.
   */
  @Test
  void rapidTurnsUseLatestTurnWithoutDroppingItsResponse() throws Exception {
    openSession(false, Map.of());
    assertAccepted(
        client.sendSessionInstructionsSet(
            "If the latest request says B, reply with exactly REALTIME_LATEST_B_OK. Do not answer A."));

    assertAccepted(client.sendTurnTextSubmit(clientItemId(), "Rapid turn A. Do not delay."));
    client.sendAssistantInterrupt("supersede_pending_A");
    int boundary = client.allReceived().size();
    assertAccepted(
        client.sendTurnTextSubmit(
            clientItemId(), "Rapid turn B. Reply with exactly REALTIME_LATEST_B_OK."));

    waitForPatchTextAfter("REALTIME_LATEST_B_OK", boundary, 60);
    assertNoErrorFrames();
    JsonNode snapshot = requestSnapshot();
    assertTrue(containsText(snapshot, "Rapid turn A"), "superseded A must remain in history");
    assertTrue(containsText(snapshot, "Rapid turn B"), "authoritative B must remain in history");
    writeArtifacts("realtimeRapidLatestWins", snapshot);
  }

  /**
   * Tool results intentionally arrive at different times. The first output must not start
   * inference; after the second arrives, exactly one continuation must consume both markers.
   */
  @Test
  void staggeredToolOutputsProduceOneContinuationUsingBothResults() throws Exception {
    openSession(true, Map.of("realtime_fast", true, "realtime_slow", true));
    assertAccepted(client.sendToolsSet(List.of(tool("realtime_fast"), tool("realtime_slow"))));
    assertAccepted(
        client.sendSessionInstructionsSet(
            "Call realtime_fast and realtime_slow exactly once each in the same response. After both "
                + "outputs arrive, reply with exactly REALTIME_BOTH_TOOLS_OK."));

    int toolBoundary = client.allReceived().size();
    assertAccepted(
        client.sendTurnTextSubmit(clientItemId(), "Call both registered tools now, exactly once."));
    List<FunctionCall> calls = waitForFunctionCallsAfter(toolBoundary, 2, 60);
    FunctionCall fast = callNamed(calls, "realtime_fast");
    FunctionCall slow = callNamed(calls, "realtime_slow");
    // Required mode is needed to deterministically obtain both calls, but it must be relaxed before
    // the output batch completes; otherwise the provider is required to call another tool instead
    // of
    // producing the direct final marker that proves one continuation consumed both outputs.
    assertAccepted(
        client.sendSessionExtensionApply(
            "session.tool_choice_required", Map.of("required", false)));

    int continuationBoundary = client.allReceived().size();
    assertAccepted(
        client.sendToolResultSubmit(clientItemId(), fast.callId(), "FAST_RESULT_OK", "success"));
    // This delay models a real slow tool. No assistant continuation may start from the partial
    // batch.
    Thread.sleep(750);
    assertFalse(
        containsTextAfter(continuationBoundary, "REALTIME_BOTH_TOOLS_OK"),
        "partial tool batch must not start final continuation");
    assertAccepted(
        client.sendToolResultSubmit(clientItemId(), slow.callId(), "SLOW_RESULT_OK", "success"));

    waitForPatchTextAfter("REALTIME_BOTH_TOOLS_OK", continuationBoundary, 60);
    assertNoErrorFrames();
    writeArtifacts("realtimeStaggeredTools");
  }

  private void openSession(boolean toolChoiceRequired, Map<String, Boolean> enabledTools)
      throws Exception {
    String jwt = VhrpAuthTestSupport.obtainValidJwt();
    String speedDialId = saveSpeedDial(jwt, toolChoiceRequired, enabledTools);
    client.connect(testServerUrl.getPort(), "vhrp.cbor.v1");
    String messageId = client.sendSessionOpen(jwt, speedDialId);
    JsonNode ready = client.waitForMessage("session.ready", 20, TimeUnit.SECONDS);
    assertEquals(messageId, text(ready, "replyTo"));
  }

  private String saveSpeedDial(
      String jwt, boolean toolChoiceRequired, Map<String, Boolean> enabledTools) {
    return given()
        .auth()
        .oauth2(jwt)
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "name", "Real OAI Realtime Regression",
                "systemPrompt", "You are a deterministic Realtime integration-test agent.",
                "voice", "alloy",
                "voiceAgentId", MODEL_ID,
                "enabledTools", enabledTools,
                "reasoningEffort", "off",
                "toolChoiceRequired", toolChoiceRequired))
        .when()
        .post("/api/speed-dials")
        .then()
        .statusCode(201)
        .body("id", matchesPattern("sd_[0-9a-f]{32}"))
        .body("voiceAgentId", equalTo(MODEL_ID))
        .extract()
        .jsonPath()
        .getString("id");
  }

  private static Map<String, Object> tool(String name) {
    return Map.of(
        "name",
        name,
        "description",
        "Deterministic integration-test tool " + name,
        "parameters",
        Map.of("type", "object", "properties", Map.of()));
  }

  private void assertAccepted(String messageId) throws InterruptedException {
    JsonNode ack = client.waitForAckReplyingTo(messageId, 20, TimeUnit.SECONDS);
    assertTrue(ack.path("body").path("accepted").asBoolean(), "ack rejected " + messageId);
  }

  private void waitForPatchText(String expected, int seconds) throws InterruptedException {
    waitForPatchTextAfter(expected, 0, seconds);
  }

  private void waitForPatchTextAfter(String expected, int after, int seconds)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
    while (System.nanoTime() < deadline) {
      List<JsonNode> frames = client.allReceived();
      for (int index = Math.min(after, frames.size()); index < frames.size(); index++) {
        JsonNode frame = frames.get(index);
        if ("thread.patch".equals(text(frame, "type")) && containsText(frame, expected)) {
          return;
        }
      }
      Thread.sleep(50);
    }
    throw new AssertionError("Timed out waiting for thread text " + expected);
  }

  private List<FunctionCall> waitForFunctionCallsAfter(int after, int count, int seconds)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
    while (System.nanoTime() < deadline) {
      Map<String, FunctionCall> calls = new LinkedHashMap<>();
      List<JsonNode> frames = client.allReceived();
      for (int index = Math.min(after, frames.size()); index < frames.size(); index++) {
        JsonNode ops = frames.get(index).path("body").path("ops");
        if (!ops.isArray()) {
          continue;
        }
        for (JsonNode op : ops) {
          if ("add_item".equals(text(op, "op"))
              && "functionCall".equals(text(op.path("item"), "type"))) {
            JsonNode item = op.path("item");
            String itemId = text(item, "id");
            calls.putIfAbsent(itemId, new FunctionCall(itemId, null, text(item, "name")));
          } else if ("set_field".equals(text(op, "op")) && "callId".equals(text(op, "field"))) {
            String itemId = text(op, "itemId");
            FunctionCall previous = calls.get(itemId);
            if (previous != null) {
              calls.put(itemId, new FunctionCall(itemId, text(op, "value"), previous.name()));
            }
          } else if ("set_field".equals(text(op, "op")) && "name".equals(text(op, "field"))) {
            String itemId = text(op, "itemId");
            FunctionCall previous = calls.get(itemId);
            if (previous != null) {
              calls.put(itemId, new FunctionCall(itemId, previous.callId(), text(op, "value")));
            }
          }
        }
      }
      List<FunctionCall> complete =
          calls.values().stream()
              .filter(call -> call.callId() != null && call.name() != null)
              .toList();
      if (complete.size() >= count) {
        return complete;
      }
      Thread.sleep(50);
    }
    throw new AssertionError("Timed out waiting for " + count + " function calls");
  }

  private static FunctionCall callNamed(List<FunctionCall> calls, String name) {
    return calls.stream().filter(call -> name.equals(call.name())).findFirst().orElseThrow();
  }

  private boolean containsTextAfter(int after, String expected) {
    List<JsonNode> frames = client.allReceived();
    for (int index = Math.min(after, frames.size()); index < frames.size(); index++) {
      if (containsText(frames.get(index), expected)) {
        return true;
      }
    }
    return false;
  }

  private void assertNoErrorFrames() {
    List<JsonNode> errors =
        client.allReceived().stream().filter(frame -> "error".equals(text(frame, "type"))).toList();
    assertTrue(errors.isEmpty(), "unexpected VHRP errors: " + sanitizedLifecycle(errors));
  }

  private JsonNode requestSnapshot() throws InterruptedException {
    int before = client.allReceived().size();
    client.sendThreadSyncRequest();
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (System.nanoTime() < deadline) {
      List<JsonNode> frames = client.allReceived();
      for (int index = Math.min(before, frames.size()); index < frames.size(); index++) {
        if ("thread.snapshot".equals(text(frames.get(index), "type"))) {
          return frames.get(index);
        }
      }
      Thread.sleep(50);
    }
    throw new AssertionError("Timed out waiting for thread.snapshot");
  }

  private void writeArtifacts(String name) throws Exception {
    writeArtifacts(name, requestSnapshot());
  }

  /**
   * Writes the same user-facing evidence as the existing real sequence tests plus a sanitized
   * lifecycle file. The lifecycle intentionally contains only frame type and VHRP error code; JWTs,
   * provider credentials, PCM bytes, and unrestricted provider payloads are never serialized.
   */
  private void writeArtifacts(String name, JsonNode snapshot) throws Exception {
    Optional<Path> maybeDir = transcriptDir();
    if (maybeDir.isEmpty()) {
      return;
    }
    Path dir = maybeDir.get();
    Files.createDirectories(dir);
    Files.writeString(
        dir.resolve(name + ".snapshot.json"),
        JSON.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot) + System.lineSeparator(),
        StandardCharsets.UTF_8);
    Files.writeString(
        dir.resolve(name + ".md"), renderMarkdown(name, snapshot), StandardCharsets.UTF_8);
    Files.writeString(
        dir.resolve(name + ".lifecycle.json"),
        JSON.writerWithDefaultPrettyPrinter()
                .writeValueAsString(sanitizedLifecycle(client.allReceived()))
            + System.lineSeparator(),
        StandardCharsets.UTF_8);
    writeAudio(name, dir);
  }

  private void writeAudio(String name, Path dir) throws Exception {
    Map<String, ByteArrayOutputStream> pcmByPart = new LinkedHashMap<>();
    for (JsonNode frame : client.allReceived()) {
      if (!"assistant.audio.chunk".equals(text(frame, "type"))) {
        continue;
      }
      JsonNode body = frame.path("body");
      JsonNode pcm = body.path("pcm");
      if (!pcm.isBinary()) {
        continue;
      }
      String key = safe(text(body, "itemId")) + ".part" + safe(text(body, "contentIndex"));
      pcmByPart
          .computeIfAbsent(key, ignored -> new ByteArrayOutputStream())
          .write(pcm.binaryValue());
    }
    for (Map.Entry<String, ByteArrayOutputStream> entry : pcmByPart.entrySet()) {
      if (entry.getValue().size() > 0) {
        Files.write(
            dir.resolve(name + "." + entry.getKey() + ".wav"),
            OaiCcWavEncoder.encode(entry.getValue().toByteArray()));
      }
    }
  }

  private static List<Map<String, String>> sanitizedLifecycle(List<JsonNode> frames) {
    List<Map<String, String>> lifecycle = new ArrayList<>();
    for (JsonNode frame : frames) {
      String type = text(frame, "type");
      String code = "error".equals(type) ? text(frame.path("body"), "code") : null;
      Map<String, String> entry = new LinkedHashMap<>();
      entry.put("type", type == null ? "unknown" : type);
      if (code != null) {
        entry.put("errorCode", code);
      }
      lifecycle.add(entry);
    }
    return lifecycle;
  }

  private static String renderMarkdown(String name, JsonNode snapshot) {
    StringBuilder markdown = new StringBuilder("# ").append(name).append(" transcript\n\n");
    for (JsonNode item : snapshot.path("body").path("items")) {
      markdown
          .append("## ")
          .append(text(item, "role"))
          .append(' ')
          .append(text(item, "type"))
          .append("\n\n");
      for (JsonNode part : item.path("content")) {
        String value =
            "audio".equals(text(part, "type")) ? text(part, "transcript") : text(part, "text");
        if (value != null && !value.isBlank()) {
          markdown.append(value).append("\n");
        }
      }
      markdown.append('\n');
    }
    return markdown.toString();
  }

  private static boolean realTestEnabled() {
    return ConfigProvider.getConfig()
        .getOptionalValue(OPT_IN_PROPERTY, String.class)
        .map(Boolean::parseBoolean)
        .orElse(false);
  }

  private static boolean hasRealRealtimeConfig() {
    String baseUrl =
        ConfigProvider.getConfig().getOptionalValue(BASE_URL_PROPERTY, String.class).orElse("");
    String apiKey =
        ConfigProvider.getConfig().getOptionalValue(API_KEY_PROPERTY, String.class).orElse("");
    return !baseUrl.isBlank()
        && !baseUrl.contains("realtime.invalid")
        && !apiKey.isBlank()
        && !"__NO_AUTH__".equals(apiKey);
  }

  private static Optional<Path> transcriptDir() {
    return ConfigProvider.getConfig()
        .getOptionalValue(TRANSCRIPT_DIR_PROPERTY, String.class)
        .map(String::trim)
        .filter(value -> !value.isBlank())
        .map(Paths::get);
  }

  private static String clientItemId() {
    return "ci_" + UUID.randomUUID();
  }

  private static String safe(String value) {
    return value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9._-]", "_");
  }

  private static boolean containsText(JsonNode node, String expected) {
    if (node == null) {
      return false;
    }
    if (node.isTextual()) {
      return node.asText().contains(expected);
    }
    if (node.isContainerNode()) {
      for (JsonNode child : node) {
        if (containsText(child, expected)) {
          return true;
        }
      }
    }
    return false;
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node == null ? null : node.get(field);
    return value == null || value.isNull() ? null : value.asText();
  }

  private record FunctionCall(String itemId, String callId, String name) {}
}
