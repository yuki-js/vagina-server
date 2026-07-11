package app.vagina.server;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.vagina.server.realtime.VhrpTestClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;
import io.quarkiverse.wiremock.devservice.ConnectWireMock;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.Vertx;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** User-visible VHRP coverage for Chat Completions context-window recovery. */
@QuarkusTest
@ConnectWireMock
class VhrpContextWindowRetryE2ETest {
  private static final String SYSTEM = "VHRP_CONTEXT_SYSTEM";
  private static final String OLDEST = "VHRP_CONTEXT_OLDEST_1234567890";
  private static final String FIRST_ANSWER = "VHRP_CONTEXT_FIRST_ANSWER";
  private static final String CURRENT = "VHRP_CONTEXT_CURRENT_1234567890";
  private static final String FINAL_ANSWER = "VHRP_CONTEXT_RETRY_OK";
  private static final String CONTEXT_ERROR =
      "{\"error\":{\"message\":\"context budget exceeded\",\"type\":\"invalid_request_error\",\"param\":\"messages\",\"code\":\"context_length_exceeded\"}}";

  @TestHTTPResource("/")
  URL testServerUrl;

  WireMock wireMock;
  private Vertx vertx;
  private VhrpTestClient client;

  @BeforeEach
  void setUp() {
    wireMock.resetToDefaultMappings();
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

  @Test
  void contextRejectionTrimsOnlyProviderProjectionAndStillCompletesTheVhrpTurn()
      throws Exception {
    stubSemanticContextBudget();
    String token = VhrpAuthTestSupport.obtainValidJwt();
    client.connect(testServerUrl.getPort(), "vhrp.cbor.v1");
    String openId = client.sendSessionOpen(token, "default");
    JsonNode ready = client.waitForMessage("session.ready", 20, TimeUnit.SECONDS);
    assertEquals(openId, text(ready, "replyTo"));

    assertAccepted(client.sendSessionInstructionsSet(SYSTEM));
    assertAccepted(client.sendTurnTextSubmit("ci_" + UUID.randomUUID(), OLDEST));
    waitForPatchText(FIRST_ANSWER, 20, TimeUnit.SECONDS);

    int retryBoundary = client.allReceived().size();
    assertAccepted(client.sendTurnTextSubmit("ci_" + UUID.randomUUID(), CURRENT));
    waitForPatchTextAfter(FINAL_ANSWER, retryBoundary, 20, TimeUnit.SECONDS);

    List<JsonNode> contextErrors =
        client.allReceived().stream()
            .filter(frame -> "error".equals(text(frame, "type")))
            .filter(frame -> "chat_completions_error".equals(text(frame.path("body"), "code")))
            .toList();
    assertTrue(contextErrors.isEmpty(), "successful internal retry must not leak a VHRP error");

    JsonNode snapshot = requestSnapshot();
    JsonNode items = snapshot.path("body").path("items");
    assertTrue(containsText(items, OLDEST), "canonical snapshot must retain trimmed oldest input");
    assertTrue(containsText(items, FIRST_ANSWER), "canonical snapshot must retain its answer");
    assertTrue(containsText(items, CURRENT), "canonical snapshot must retain current input");
    assertTrue(containsText(items, FINAL_ANSWER), "canonical snapshot must contain retry answer");
    assertEquals(1, countItemsContaining(items, FINAL_ANSWER), "retry must create one visible answer");

    List<JsonNode> requests = chatCompletionRequests();
    assertEquals(3, requests.size(), "first turn plus rejected and successful current-turn attempts");
    assertEquals(
        List.of("system:" + combinedSystemInstruction(), "user:" + OLDEST),
        messageSignatures(requests.get(0)));
    assertEquals(
        List.of(
            "system:" + combinedSystemInstruction(),
            "user:" + OLDEST,
            "assistant:" + FIRST_ANSWER,
            "user:" + CURRENT),
        messageSignatures(requests.get(1)));
    assertEquals(
        List.of(
            "system:" + combinedSystemInstruction(),
            "assistant:" + FIRST_ANSWER,
            "user:" + CURRENT),
        messageSignatures(requests.get(2)));
  }

  private void stubSemanticContextBudget() {
    wireMock.register(
        post(urlPathEqualTo("/v1/chat/completions"))
            .atPriority(1)
            .withRequestBody(matchingJsonPath("$.messages[?(@.content == '" + OLDEST + "')]"))
            .withRequestBody(matchingJsonPath("$.messages[?(@.content == '" + CURRENT + "')]"))
            .willReturn(
                aResponse()
                    .withStatus(400)
                    .withHeader("Content-Type", "application/json")
                    .withBody(CONTEXT_ERROR)));
    wireMock.register(
        post(urlPathEqualTo("/v1/chat/completions"))
            .atPriority(2)
            .withRequestBody(matchingJsonPath("$.messages[?(@.content == '" + CURRENT + "')]"))
            .willReturn(sse(FINAL_ANSWER)));
    wireMock.register(
        post(urlPathEqualTo("/v1/chat/completions"))
            .atPriority(3)
            .withRequestBody(matchingJsonPath("$.messages[?(@.content == '" + OLDEST + "')]"))
            .willReturn(sse(FIRST_ANSWER)));
  }

  private static ResponseDefinitionBuilder sse(String text) {
    return aResponse()
        .withStatus(200)
        .withHeader("Content-Type", "text/event-stream")
        .withBody(
            "data: {\"choices\":[{\"delta\":{\"content\":\""
                + text
                + "\"}}]}\n\n"
                + "data: {\"choices\":[{\"finish_reason\":\"stop\",\"delta\":{}}]}\n\n"
                + "data: [DONE]\n\n");
  }

  private List<JsonNode> chatCompletionRequests() throws Exception {
    List<JsonNode> requests = new ArrayList<>();
    for (var event : wireMock.getServeEvents()) {
      if ("/v1/chat/completions".equals(event.getRequest().getUrl())) {
        requests.add(new ObjectMapper().readTree(event.getRequest().getBody()));
      }
    }
    Collections.reverse(requests);
    return requests;
  }

  private static List<String> messageSignatures(JsonNode request) {
    List<String> signatures = new ArrayList<>();
    for (JsonNode message : request.path("messages")) {
      signatures.add(message.path("role").asText() + ":" + message.path("content").asText());
    }
    return signatures;
  }

  private static String combinedSystemInstruction() {
    return "You are a helpful AI assistant.\n\n" + SYSTEM;
  }

  private void assertAccepted(String messageId) throws InterruptedException {
    JsonNode ack = client.waitForAckReplyingTo(messageId, 20, TimeUnit.SECONDS);
    assertTrue(ack.path("body").path("accepted").asBoolean());
  }

  private JsonNode requestSnapshot() throws InterruptedException {
    int boundary = client.allReceived().size();
    client.sendThreadSyncRequest();
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (System.nanoTime() < deadline) {
      List<JsonNode> frames = client.allReceived();
      for (int index = Math.min(boundary, frames.size()); index < frames.size(); index++) {
        if ("thread.snapshot".equals(text(frames.get(index), "type"))) {
          return frames.get(index);
        }
      }
      Thread.sleep(20);
    }
    throw new AssertionError("Timed out waiting for thread.snapshot");
  }

  private void waitForPatchText(String expected, long timeout, TimeUnit unit)
      throws InterruptedException {
    waitForPatchTextAfter(expected, 0, timeout, unit);
  }

  private void waitForPatchTextAfter(
      String expected, int boundary, long timeout, TimeUnit unit) throws InterruptedException {
    long deadline = System.nanoTime() + unit.toNanos(timeout);
    while (System.nanoTime() < deadline) {
      List<JsonNode> frames = client.allReceived();
      for (int index = Math.min(boundary, frames.size()); index < frames.size(); index++) {
        JsonNode frame = frames.get(index);
        if ("thread.patch".equals(text(frame, "type")) && containsText(frame, expected)) {
          return;
        }
      }
      Thread.sleep(20);
    }
    throw new AssertionError("Timed out waiting for thread.patch containing " + expected);
  }

  private static boolean containsText(JsonNode node, String expected) {
    if (node == null || node.isMissingNode()) {
      return false;
    }
    if (node.isTextual() && node.asText().contains(expected)) {
      return true;
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

  private static int countItemsContaining(JsonNode items, String expected) {
    int count = 0;
    for (JsonNode item : items) {
      if (containsText(item, expected)) {
        count++;
      }
    }
    return count;
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node == null ? null : node.get(field);
    return value == null || value.isNull() ? null : value.asText();
  }
}
