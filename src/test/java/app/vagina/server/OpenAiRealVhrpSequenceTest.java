package app.vagina.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import app.vagina.server.realtime.VhrpTestClient;
import app.vagina.server.realtime.oai_cc.OaiCcWavEncoder;
import app.vagina.server.support.HarigataOidcMockServerResource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Opt-in real OpenAI sequence tests for the hosted VHRP user path.
 *
 * <p>These scenarios deliberately use the production provider registry and the real OpenAI Chat
 * Completions backend instead of a fake adapter. Each test starts with Harigata OIDC, obtains a DB
 * backed user JWT, opens a hosted VHRP session, and then drives a user-visible conversation path.
 */
@QuarkusTest
@QuarkusTestResource(HarigataOidcMockServerResource.class)
class OpenAiRealVhrpSequenceTest {

  private static final String MODEL_ID = "voice-agent-prod-cc";
  private static final String REQUIRED_PROPERTY = "vagina.realtime.models.voice-agent-prod-cc.api-key";
  private static final String OPT_IN_PROPERTY = "vagina.test.openai.real.enabled";
  private static final String TRANSCRIPT_DIR_PROPERTY = "vagina.test.openai.real.transcript.dir";
  private static final ObjectMapper JSON = new ObjectMapper();

  @TestHTTPResource("/")
  URL testServerUrl;

  @Inject io.vertx.mutiny.core.Vertx mutinyVertx;

  private VhrpTestClient client;

  @BeforeEach
  void setUp() {
    assumeTrue(realOpenAiTestEnabled(), "set -Dvagina.test.openai.real.enabled=true to run");
    assumeTrue(hasRequiredOpenAiConfig(), REQUIRED_PROPERTY + " must be configured");
    client = new VhrpTestClient(mutinyVertx.getDelegate());
  }

  @AfterEach
  void tearDown() {
    if (client != null) {
      client.close();
    }
  }

  /**
   * worksSeq01: tool-assisted hosted conversation.
   *
   * <ol>
   *   <li>User starts authentication.
   *   <li>User authenticates with Harigata and proxies the redirection back to the app.
   *   <li>Server creates or finds the user entity and issues a JWT.
   *   <li>User starts a hosted call backed by the server model registry.
   *   <li>User enables an app tool for the agent.
   *   <li>User asks the agent to use that tool.
   *   <li>Real OpenAI emits a function call.
   *   <li>App submits the tool result to the server.
   *   <li>Real OpenAI continues the same conversation using the tool result.
   *   <li>User sends a follow-up message in the same hosted session.
   *   <li>Real OpenAI answers from retained conversation context.
   *   <li>User explicitly ends the call.
   * </ol>
   */
  @Test
  void worksSeq01() throws Exception {
    openAuthenticatedSession();

    String toolsMsgId = client.sendToolsSet(List.of(sequenceProbeTool()));
    assertAcceptedAck(toolsMsgId);

    String instructionsMsgId =
        client.sendSessionInstructionsSet(
            "You are in an integration test. "
                + "When the user asks for the probe, call vhrp_sequence_probe exactly once. "
                + "After the tool result, answer with the exact token VHRP_REAL_SEQUENCE_OK.");
    assertAcceptedAck(instructionsMsgId);

    String turnMsgId =
        client.sendTurnTextSubmit("ci_" + UUID.randomUUID(), "Run the VHRP probe now.");
    assertAcceptedAck(turnMsgId);

    JsonNode toolCallPatch = waitForPatchContainingItemType("functionCall", 60, TimeUnit.SECONDS);
    JsonNode toolCallItem = findItemInPatch(toolCallPatch, "functionCall");
    assertNotNull(toolCallItem, "real API sequence must produce a functionCall item");
    String callId = fieldValueFromPatch(toolCallPatch, toolCallItem.get("id").asText(), "callId");
    String toolName = fieldValueFromPatch(toolCallPatch, toolCallItem.get("id").asText(), "name");
    assertNotNull(callId, "functionCall must include callId");
    assertEquals("vhrp_sequence_probe", toolName, "functionCall must target the registered tool");

    String resultMsgId =
        client.sendToolResultSubmit(
            "ri_" + UUID.randomUUID(), callId, "VHRP_REAL_SEQUENCE_OK", "success");
    assertAcceptedAck(resultMsgId);

    JsonNode assistantPatch = waitForPatchContainingText("VHRP_REAL_SEQUENCE_OK", 60, TimeUnit.SECONDS);
    assertNotNull(assistantPatch, "assistant final patch must include the deterministic marker");
    waitForAssistantAudioDoneAfterCount(0, 20, TimeUnit.SECONDS);

    int beforeFollowUpFrameCount = client.allReceived().size();
    String followUpMsgId =
        client.sendTurnTextSubmit(
            "ci_" + UUID.randomUUID(),
            "What exact token did the probe return? Reply with only RECALL_VHRP_REAL_SEQUENCE_OK.");
    assertAcceptedAck(followUpMsgId);
    JsonNode followUpPatch =
        waitForPatchContainingTextAfterCount(
            "RECALL_VHRP_REAL_SEQUENCE_OK", beforeFollowUpFrameCount, 60, TimeUnit.SECONDS);
    assertNotNull(followUpPatch, "same session must continue after the tool-result turn");
    waitForAssistantAudioDoneAfterCount(beforeFollowUpFrameCount, 20, TimeUnit.SECONDS);

    assertSnapshotContains("VHRP_REAL_SEQUENCE_OK");
    assertSnapshotContains("RECALL_VHRP_REAL_SEQUENCE_OK");
    assertNoErrorFramesSoFar();
    writeTranscriptArtifact("worksSeq01");
    closeSession();
  }

  /**
   * worksSeq02: dynamic instruction update during one hosted call.
   *
   * <ol>
   *   <li>User starts authentication.
   *   <li>User authenticates with Harigata and proxies the redirection back to the app.
   *   <li>Server creates or finds the user entity and issues a JWT.
   *   <li>User starts a hosted call backed by the server model registry.
   *   <li>User sets initial agent instructions.
   *   <li>User sends the first message.
   *   <li>Real OpenAI answers according to the initial instructions.
   *   <li>User changes the agent instructions without reopening the call.
   *   <li>User sends the second message in the same hosted session.
   *   <li>Real OpenAI answers according to the updated instructions.
   *   <li>Server confirms the conversation history contains both instructed turns.
   *   <li>User explicitly ends the call.
   * </ol>
   */
  @Test
  void worksSeq02() throws Exception {
    openAuthenticatedSession();

    String firstInstructionsMsgId =
        client.sendSessionInstructionsSet(
            "You are in an integration test. For the next user message, reply with exactly "
                + "FIRST_REAL_TURN_OK and no other text.");
    assertAcceptedAck(firstInstructionsMsgId);

    String firstTurnMsgId =
        client.sendTurnTextSubmit("ci_" + UUID.randomUUID(), "Please answer the first marker.");
    assertAcceptedAck(firstTurnMsgId);
    JsonNode firstPatch = waitForPatchContainingText("FIRST_REAL_TURN_OK", 60, TimeUnit.SECONDS);
    assertNotNull(firstPatch, "first real OpenAI turn must follow initial instructions");
    waitForAssistantAudioDoneAfterCount(0, 20, TimeUnit.SECONDS);

    int beforeSecondTurnFrameCount = client.allReceived().size();
    String secondInstructionsMsgId =
        client.sendSessionInstructionsSet(
            "Instructions changed. For the next user message, reply with exactly "
                + "SECOND_REAL_TURN_OK and no other text.");
    assertAcceptedAck(secondInstructionsMsgId);

    String secondTurnMsgId =
        client.sendTurnTextSubmit("ci_" + UUID.randomUUID(), "Please answer the second marker.");
    assertAcceptedAck(secondTurnMsgId);
    JsonNode secondPatch =
        waitForPatchContainingTextAfterCount(
            "SECOND_REAL_TURN_OK", beforeSecondTurnFrameCount, 60, TimeUnit.SECONDS);
    assertNotNull(secondPatch, "second real OpenAI turn must follow updated instructions");
    waitForAssistantAudioDoneAfterCount(beforeSecondTurnFrameCount, 20, TimeUnit.SECONDS);

    assertSnapshotContains("FIRST_REAL_TURN_OK");
    assertSnapshotContains("SECOND_REAL_TURN_OK");
    assertNoErrorFramesSoFar();
    writeTranscriptArtifact("worksSeq02");
    closeSession();
  }

  /**
   * worksSeq03: direct hosted text conversation without tools.
   *
   * <ol>
   *   <li>User starts authentication.
   *   <li>User authenticates with Harigata and proxies the redirection back to the app.
   *   <li>Server creates or finds the user entity and issues a JWT.
   *   <li>User starts a hosted call backed by the server model registry.
   *   <li>User sets direct-response agent instructions.
   *   <li>User moves mouth and utters aloud: "VAGINA is not a vulgar word but a good app".
   *   <li>Server receives the utterance as a user turn for the same hosted session.
   *   <li>Real OpenAI answers directly without an app tool round-trip.
   *   <li>Server confirms the conversation history contains the direct assistant answer.
   *   <li>User explicitly ends the call.
   * </ol>
   */
  @Test
  void worksSeq03() throws Exception {
    openAuthenticatedSession();

    String instructionsMsgId =
        client.sendSessionInstructionsSet(
            "You are in an integration test. When the user says the sentence about VAGINA, "
                + "reply with exactly VAGINA_APP_WORD_OK and no other text.");
    assertAcceptedAck(instructionsMsgId);

    String turnMsgId =
        client.sendTurnTextSubmit(
            "ci_" + UUID.randomUUID(), "VAGINA is not a vulgar word but a good app");
    assertAcceptedAck(turnMsgId);

    JsonNode directPatch = waitForPatchContainingText("VAGINA_APP_WORD_OK", 60, TimeUnit.SECONDS);
    assertNotNull(directPatch, "direct real OpenAI turn must answer without tool round-trip");
    waitForAssistantAudioDoneAfterCount(0, 20, TimeUnit.SECONDS);

    assertSnapshotContains("VAGINA_APP_WORD_OK");
    assertNoErrorFramesSoFar();
    writeTranscriptArtifact("worksSeq03");
    closeSession();
  }

  /**
   * worksSeq04: tool-required mode cannot escape to direct answers.
   *
   * <ol>
   *   <li>User starts authentication.
   *   <li>User authenticates with Harigata and proxies the redirection back to the app.
   *   <li>Server creates or finds the user entity and issues a JWT.
   *   <li>User starts a hosted call backed by the server model registry.
   *   <li>User enables an app tool for the agent.
   *   <li>User switches tool choice to required mode.
   *   <li>User explicitly says not to call tools and asks for a direct answer.
   *   <li>Real OpenAI emits a function call anyway because tools are required.
   *   <li>App submits a tool result that again asks the model not to call tools.
   *   <li>Real OpenAI still emits another function call instead of escaping to a direct answer.
   *   <li>The app repeats the tool-result continuation ten times.
   *   <li>Server confirms the session can remain trapped in required-tool continuation without VHRP
   *       errors.
   *   <li>User explicitly ends the call.
   * </ol>
   */
  @Test
  void worksSeq04() throws Exception {
    openAuthenticatedSession();

    String toolsMsgId = client.sendToolsSet(List.of(sequenceProbeTool()));
    assertAcceptedAck(toolsMsgId);

    String requiredMsgId =
        client.sendSessionExtensionApply("session.tool_choice_required", Map.of("required", true));
    assertAcceptedAck(requiredMsgId);

    String instructionsMsgId =
        client.sendSessionInstructionsSet(
            "You are in an integration test. The user asks you not to call tools. So you must not call tools. "
                + "Even if you cannot avoid calling tools, you must still try to avoid calling tools and answer directly. ");
    assertAcceptedAck(instructionsMsgId);

    int beforeTurnFrameCount = client.allReceived().size();
    String turnMsgId =
        client.sendTurnTextSubmit(
            "ci_" + UUID.randomUUID(),
            "Do not call any tool. Answer directly with REQUIRED_TOOL_MODE_OK.");
    assertAcceptedAck(turnMsgId);

    for (int index = 0; index < 10; index++) {
      JsonNode toolCallPatch =
          waitForPatchContainingItemTypeAfterCount(
              "functionCall", beforeTurnFrameCount, 60, TimeUnit.SECONDS);
      JsonNode toolCallItem = findItemInPatch(toolCallPatch, "functionCall");
      assertNotNull(toolCallItem, "required tool mode must produce functionCall #" + (index + 1));
      String itemId = toolCallItem.get("id").asText();
      String callId = fieldValueFromPatch(toolCallPatch, itemId, "callId");
      String toolName = fieldValueFromPatch(toolCallPatch, itemId, "name");
      assertNotNull(callId, "required functionCall must include callId");
      assertEquals(
          "vhrp_sequence_probe", toolName, "required functionCall must target the registered tool");
      beforeTurnFrameCount = client.allReceived().size();

      String resultMsgId =
          client.sendToolResultSubmit(
              "ri_" + UUID.randomUUID(),
              callId,
              "Tool result #"
                  + (index + 1)
                  + ": do not call tools anymore; answer directly with REQUIRED_TOOL_MODE_OK.",
              "success");
      assertAcceptedAck(resultMsgId);
    }

    assertTrue(
        countItemsInPatches("functionCall") >= 10,
        "required tool mode must keep producing function calls across continuations");
    assertNoErrorFramesSoFar();
    writeTranscriptArtifact("worksSeq04");
    closeSession();
  }

  /**
   * worksSeq05: user interrupt cancels an in-flight real OpenAI response.
   *
   * <ol>
   *   <li>User starts authentication.
   *   <li>User authenticates with Harigata and proxies the redirection back to the app.
   *   <li>Server creates or finds the user entity and issues a JWT.
   *   <li>User starts a hosted call backed by the server model registry.
   *   <li>User asks for a slow response that would eventually include an old marker.
   *   <li>Real OpenAI starts responding on the hosted session.
   *   <li>User interrupts the assistant before completion.
   *   <li>User immediately submits a new turn asking for a new marker.
   *   <li>Server confirms the new marker appears after the interrupt boundary.
   *   <li>Server confirms the old marker does not leak after the interrupt boundary.
   *   <li>User explicitly ends the call.
   * </ol>
   */
  @Test
  void worksSeq05() throws Exception {
    openAuthenticatedSession();

    String instructionsMsgId =
        client.sendSessionInstructionsSet(
            "You are in an integration test. For the first user message, write a long response "
                + "and include OLD_INTERRUPTED_MARKER only near the very end. For any later user "
                + "message asking for recovery, reply with exactly NEW_AFTER_INTERRUPT_OK and no "
                + "other text.");
    assertAcceptedAck(instructionsMsgId);

    String slowTurnMsgId =
        client.sendTurnTextSubmit(
            "ci_" + UUID.randomUUID(),
            "Start a long response now. Do not mention OLD_INTERRUPTED_MARKER until the final "
                + "sentence after several paragraphs.");
    assertAcceptedAck(slowTurnMsgId);

    waitForPatchContainingItemType("message", 60, TimeUnit.SECONDS);
    int interruptBoundaryFrameCount = client.allReceived().size();
    client.sendAssistantInterrupt("user_interrupted_real_openai_response");

    String newTurnMsgId =
        client.sendTurnTextSubmit(
            "ci_" + UUID.randomUUID(),
            "Recovery turn: reply with exactly NEW_AFTER_INTERRUPT_OK and no other text.");
    assertAcceptedAck(newTurnMsgId);

    JsonNode newPatch =
        waitForPatchContainingTextAfterCount(
            "NEW_AFTER_INTERRUPT_OK", interruptBoundaryFrameCount, 60, TimeUnit.SECONDS);
    assertNotNull(newPatch, "new turn must complete after interrupt");
    waitForAssistantAudioDoneAfterCount(interruptBoundaryFrameCount, 20, TimeUnit.SECONDS);

    assertNoTextAfterCount("OLD_INTERRUPTED_MARKER", interruptBoundaryFrameCount);
    assertNoErrorFramesSoFar();
    writeTranscriptArtifact("worksSeq05");
    closeSession();
  }

  /**
   * worksSeq06: hosted session recovery after microwave-grade network chaos.
   *
   * <ol>
   *   <li>User starts authentication.
   *   <li>User authenticates with Harigata and proxies the redirection back to the app.
   *   <li>Server creates or finds the user entity and issues a JWT.
   *   <li>User starts a hosted call backed by the server model registry.
   *   <li>User records the sessionId and threadId from the hosted call.
   *   <li>User says a deterministic before-microwave marker.
   *   <li>Real OpenAI answers and the marker is present in thread state.
   *   <li>A microwave destroys the 2.4GHz connection; the socket drops without session.end.
   *   <li>User reconnects with session.open resume.sessionId using the same JWT.
   *   <li>Server emits session.resumed for the same session and thread.
   *   <li>User asks for thread.sync.request.
   *   <li>Server returns thread.snapshot containing the before-microwave marker.
   *   <li>User says an after-microwave marker.
   *   <li>Real OpenAI continues on the recovered session, not a fresh one.
   *   <li>User explicitly ends the call.
   * </ol>
   */
  @Test
  void worksSeq06() throws Exception {
    String jwt = VhrpAuthTestSupport.obtainValidJwt();
    SessionIds sessionIds = openAuthenticatedSession(jwt);

    String instructionsMsgId =
        client.sendSessionInstructionsSet(
            "You are in an integration test. When the user asks for the before-microwave marker, "
                + "reply with exactly BEFORE_MICROWAVE_OK and no other text. When the user asks "
                + "for the after-microwave marker, reply with exactly AFTER_MICROWAVE_OK and no "
                + "other text.");
    assertAcceptedAck(instructionsMsgId);

    String beforeTurnMsgId =
        client.sendTurnTextSubmit(
            "ci_" + UUID.randomUUID(), "Please answer the before-microwave marker.");
    assertAcceptedAck(beforeTurnMsgId);
    JsonNode beforePatch = waitForPatchContainingText("BEFORE_MICROWAVE_OK", 60, TimeUnit.SECONDS);
    assertNotNull(beforePatch, "before-microwave marker must exist before socket drop");
    waitForAssistantAudioDoneAfterCount(0, 20, TimeUnit.SECONDS);

    client.close();
    client.waitForClose(10, TimeUnit.SECONDS);

    client = new VhrpTestClient(mutinyVertx.getDelegate());
    client.connect(testPort(), "vhrp.cbor.v1");
    String resumeMsgId = client.sendSessionOpenResume(jwt, MODEL_ID, sessionIds.sessionId());
    JsonNode resumed = client.waitForMessage("session.resumed", 20, TimeUnit.SECONDS);
    assertEquals(resumeMsgId, text(resumed, "replyTo"));
    assertEquals(sessionIds.sessionId(), text(resumed.get("body"), "sessionId"));
    assertEquals(sessionIds.threadId(), text(resumed.get("body"), "threadId"));
    assertFalse(hasFrameType("session.ready"), "resume must not fall back to a fresh session");

    JsonNode snapshot = requestSnapshot();
    assertEquals(sessionIds.threadId(), text(snapshot.get("body"), "threadId"));
    assertTrue(
        containsText(snapshot.get("body").get("items"), "BEFORE_MICROWAVE_OK"),
        "resumed snapshot must contain pre-drop conversation history");

    int beforeAfterTurnFrameCount = client.allReceived().size();
    String afterTurnMsgId =
        client.sendTurnTextSubmit(
            "ci_" + UUID.randomUUID(), "Please answer the after-microwave marker.");
    assertAcceptedAck(afterTurnMsgId);
    JsonNode afterPatch =
        waitForPatchContainingTextAfterCount(
            "AFTER_MICROWAVE_OK", beforeAfterTurnFrameCount, 60, TimeUnit.SECONDS);
    assertNotNull(afterPatch, "after-microwave turn must continue on the resumed session");
    waitForAssistantAudioDoneAfterCount(beforeAfterTurnFrameCount, 20, TimeUnit.SECONDS);

    assertSnapshotContains("BEFORE_MICROWAVE_OK");
    assertSnapshotContains("AFTER_MICROWAVE_OK");
    assertNoErrorFramesSoFar();
    writeTranscriptArtifact("worksSeq06");
    closeSession();
  }

  /**
   * worksSeq07: tool-required mode can escape when an answer tool exists.
   *
   * <ol>
   *   <li>User starts authentication.
   *   <li>User authenticates with Harigata and proxies the redirection back to the app.
   *   <li>Server creates or finds the user entity and issues a JWT.
   *   <li>User starts a hosted call backed by the server model registry.
   *   <li>User enables two app tools: a normal probe tool and an answer-submission tool.
   *   <li>User switches tool choice to required mode.
   *   <li>User instructs the agent to call the normal probe tool exactly ten times first.
   *   <li>App submits one continuation result for each probe call.
   *   <li>Server confirms function calls #1 through #10 target only the normal probe tool.
   *   <li>Server confirms function call #11 targets the answer tool.
   *   <li>Server confirms the answer marker is carried in the answer tool arguments.
   *   <li>User explicitly ends the call without triggering a twelfth required-tool continuation.
   * </ol>
   */
  @Test
  void worksSeq07() throws Exception {
    openAuthenticatedSession();

    String toolsMsgId = client.sendToolsSet(List.of(sequenceProbeTool(), sequenceAnswerTool()));
    assertAcceptedAck(toolsMsgId);

    String requiredMsgId =
        client.sendSessionExtensionApply("session.tool_choice_required", Map.of("required", true));
    assertAcceptedAck(requiredMsgId);

    try {
      String instructionsMsgId =
          client.sendSessionInstructionsSet(
              "You are in an integration test. Tool choice is required, so every assistant step must "
                  + "be exactly one function call and no direct answer. The registered tools are "
                  + "vhrp_sequence_probe and vhrp_required_answer. For the next user turn, call "
                  + "vhrp_sequence_probe exactly ten times first. After tool result #10, call "
                  + "vhrp_required_answer as function call #11 and set answer exactly "
                  + "ANSWER_TOOL_MODE_OK. Never call vhrp_required_answer before function call #11. "
                  + "Never produce a normal assistant message for this task.");
      assertAcceptedAck(instructionsMsgId);

      int beforeTurnFrameCount = client.allReceived().size();
      String turnMsgId =
          client.sendTurnTextSubmit(
              "ci_" + UUID.randomUUID(),
              "Execute the required-tool counting protocol now. Function calls #1 through #10 must "
                  + "be vhrp_sequence_probe. Function call #11 must be vhrp_required_answer with "
                  + "ANSWER_TOOL_MODE_OK. Do not answer directly.");
      assertAcceptedAck(turnMsgId);

      Set<String> seenFunctionCallItemIds = new HashSet<>();
      for (int callNumber = 1; callNumber <= 10; callNumber++) {
        FunctionCallFrame probeCall =
            waitForNextFunctionCall(beforeTurnFrameCount, seenFunctionCallItemIds, 90, TimeUnit.SECONDS);
        assertEquals(
            "vhrp_sequence_probe",
            probeCall.name(),
            "function call #" + callNumber + " must be a probe call");
        String resultMsgId =
            client.sendToolResultSubmit(
                "ri_" + UUID.randomUUID(),
                probeCall.callId(),
                "Probe accepted.",
                "success");
        assertAcceptedAck(resultMsgId);
      }

      FunctionCallFrame answerCall =
          waitForNextFunctionCall(beforeTurnFrameCount, seenFunctionCallItemIds, 90, TimeUnit.SECONDS);
      assertEquals(
          "vhrp_required_answer", answerCall.name(), "function call #11 must be the answer tool");
      String arguments =
          waitForFieldContaining(
              answerCall.itemId(), "arguments", "ANSWER_TOOL_MODE_OK", 90, TimeUnit.SECONDS);
      assertTrue(
          arguments.contains("ANSWER_TOOL_MODE_OK"),
          "answer tool arguments must carry ANSWER_TOOL_MODE_OK: " + arguments);
      waitForItemStatus(answerCall.itemId(), "completed", 90, TimeUnit.SECONDS);
      assertEquals(11, countFunctionCallItems(), "worksSeq07 must stop after exactly eleven tool calls");

      assertNoErrorFramesSoFar();
    } finally {
      writeTranscriptArtifact("worksSeq07");
      closeSession();
    }
  }

  private void openAuthenticatedSession() throws Exception {
    openAuthenticatedSession(VhrpAuthTestSupport.obtainValidJwt());
  }

  private SessionIds openAuthenticatedSession(String jwt) throws Exception {
    client.connect(testPort(), "vhrp.cbor.v1");
    String openMsgId = client.sendSessionOpen(jwt, MODEL_ID);

    JsonNode ready = client.waitForMessage("session.ready", 20, TimeUnit.SECONDS);
    assertEquals(openMsgId, text(ready, "replyTo"));
    assertNotNull(ready.get("body").get("sessionId"), "session.ready must expose sessionId");
    assertNotNull(ready.get("body").get("threadId"), "session.ready must expose threadId");
    return new SessionIds(
        ready.get("body").get("sessionId").asText(), ready.get("body").get("threadId").asText());
  }

  private int testPort() {
    return testServerUrl.getPort();
  }

  private static boolean realOpenAiTestEnabled() {
    return ConfigProvider.getConfig()
        .getOptionalValue(OPT_IN_PROPERTY, String.class)
        .map(Boolean::parseBoolean)
        .orElse(false);
  }

  private static boolean hasRequiredOpenAiConfig() {
    return ConfigProvider.getConfig()
        .getOptionalValue(REQUIRED_PROPERTY, String.class)
        .map(String::trim)
        .filter(value -> !value.isBlank())
        .isPresent();
  }

  private static Optional<Path> transcriptDir() {
    return ConfigProvider.getConfig()
        .getOptionalValue(TRANSCRIPT_DIR_PROPERTY, String.class)
        .map(String::trim)
        .filter(value -> !value.isBlank())
        .map(Paths::get);
  }

  private void writeTranscriptArtifact(String sequenceName) throws IOException, InterruptedException {
    Optional<Path> maybeDir = transcriptDir();
    if (maybeDir.isEmpty()) {
      return;
    }

    JsonNode snapshot = requestSnapshot();
    Path dir = maybeDir.get();
    Files.createDirectories(dir);
    Files.writeString(
        dir.resolve(sequenceName + ".snapshot.json"),
        JSON.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot) + System.lineSeparator(),
        StandardCharsets.UTF_8);
    Files.writeString(
        dir.resolve(sequenceName + ".md"), renderTranscriptMarkdown(sequenceName, snapshot), StandardCharsets.UTF_8);
    writeAudioArtifacts(sequenceName, dir);
  }

  private void writeAudioArtifacts(String sequenceName, Path dir) throws IOException {
    Map<String, ByteArrayOutputStream> pcmByPart = new LinkedHashMap<>();
    for (JsonNode frame : client.allReceived()) {
      if (!"assistant.audio.chunk".equals(text(frame, "type"))) {
        continue;
      }
      JsonNode body = frame.get("body");
      if (body == null) {
        continue;
      }
      JsonNode pcm = body.get("pcm");
      if (pcm == null || pcm.isNull()) {
        continue;
      }
      String itemId = text(body, "itemId");
      String contentIndex = text(body, "contentIndex");
      String key = safeFileToken(itemId) + ".part" + safeFileToken(contentIndex);
      pcmByPart.computeIfAbsent(key, ignored -> new ByteArrayOutputStream()).write(pcm.binaryValue());
    }

    for (Map.Entry<String, ByteArrayOutputStream> entry : pcmByPart.entrySet()) {
      byte[] pcm = entry.getValue().toByteArray();
      if (pcm.length == 0) {
        continue;
      }
      Files.write(dir.resolve(sequenceName + "." + entry.getKey() + ".wav"), OaiCcWavEncoder.encode(pcm));
    }
  }

  private static String safeFileToken(String value) {
    if (value == null || value.isBlank()) {
      return "unknown";
    }
    return value.replaceAll("[^A-Za-z0-9._-]", "_");
  }

  private static String renderTranscriptMarkdown(String sequenceName, JsonNode snapshot) {
    StringBuilder markdown = new StringBuilder();
    JsonNode body = snapshot.get("body");
    markdown.append("# ").append(sequenceName).append(" transcript").append(System.lineSeparator());
    markdown.append(System.lineSeparator());
    markdown.append("- threadId: `").append(text(body, "threadId")).append('`').append(System.lineSeparator());
    markdown
        .append("- conversationId: `")
        .append(text(body, "conversationId"))
        .append('`')
        .append(System.lineSeparator());
    markdown.append(System.lineSeparator());

    JsonNode items = body.get("items");
    if (items == null || !items.isArray() || items.isEmpty()) {
      markdown.append("_No conversation items._").append(System.lineSeparator());
      return markdown.toString();
    }

    int index = 1;
    for (JsonNode item : items) {
      markdown
          .append("## ")
          .append(index++)
          .append(". ")
          .append(itemTitle(item))
          .append(System.lineSeparator())
          .append(System.lineSeparator());
      appendItemMetadata(markdown, item);
      appendItemContent(markdown, item);
      markdown.append(System.lineSeparator());
    }
    return markdown.toString();
  }

  private static String itemTitle(JsonNode item) {
    String type = text(item, "type");
    String role = text(item, "role");
    if (role != null && !role.isBlank()) {
      return role + " " + type;
    }
    return type == null ? "item" : type;
  }

  private static void appendItemMetadata(StringBuilder markdown, JsonNode item) {
    appendMetadata(markdown, "id", text(item, "id"));
    appendMetadata(markdown, "status", text(item, "status"));
    appendMetadata(markdown, "displayState", text(item, "displayState"));
    appendMetadata(markdown, "callId", text(item, "callId"));
    appendMetadata(markdown, "name", text(item, "name"));
    appendMetadata(markdown, "toolOutputDisposition", text(item, "toolOutputDisposition"));
    appendMetadata(markdown, "toolErrorMessage", text(item, "toolErrorMessage"));
    appendMetadata(markdown, "arguments", text(item, "arguments"));
    appendMetadata(markdown, "output", text(item, "output"));
    markdown.append(System.lineSeparator());
  }

  private static void appendMetadata(StringBuilder markdown, String key, String value) {
    if (value != null && !value.isBlank()) {
      markdown.append("- ").append(key).append(": `").append(value).append('`').append(System.lineSeparator());
    }
  }

  private static void appendItemContent(StringBuilder markdown, JsonNode item) {
    JsonNode content = item.get("content");
    if (content == null || !content.isArray() || content.isEmpty()) {
      return;
    }
    for (JsonNode part : content) {
      String type = text(part, "type");
      if ("text".equals(type)) {
        markdown.append(text(part, "text")).append(System.lineSeparator());
      } else if ("audio".equals(type)) {
        String transcript = text(part, "transcript");
        if (transcript != null && !transcript.isBlank()) {
          markdown.append("[audio transcript] ").append(transcript).append(System.lineSeparator());
        } else {
          markdown.append("[audio]").append(System.lineSeparator());
        }
      } else if ("image".equals(type)) {
        markdown
            .append("[image] ")
            .append(text(part, "imageUrl"))
            .append(" detail=")
            .append(text(part, "detail"))
            .append(System.lineSeparator());
      }
    }
  }

  private void assertAcceptedAck(String msgId) throws InterruptedException {
    JsonNode ack = client.waitForAckReplyingTo(msgId, 20, TimeUnit.SECONDS);
    assertTrue(ack.get("body").get("accepted").asBoolean(), "ack must be accepted for " + msgId);
  }

  private void assertSnapshotContains(String expectedText) throws InterruptedException {
    JsonNode snapshot = requestSnapshot();
    assertTrue(
        containsText(snapshot.get("body").get("items"), expectedText),
        "thread.snapshot must contain " + expectedText);
  }

  private JsonNode requestSnapshot() throws InterruptedException {
    int beforeSyncFrameCount = client.allReceived().size();
    client.sendThreadSyncRequest();
    JsonNode snapshot = client.waitForNextMessageAfterCount(beforeSyncFrameCount, 20, TimeUnit.SECONDS);
    assertEquals("thread.snapshot", text(snapshot, "type"));
    assertNotNull(snapshot.get("body").get("threadId"));
    assertTrue(snapshot.get("body").get("items").isArray(), "snapshot items must be an array");
    return snapshot;
  }

  private void assertNoErrorFramesSoFar() {
    List<JsonNode> errorFrames =
        client.allReceived().stream().filter(frame -> "error".equals(text(frame, "type"))).toList();
    assertTrue(errorFrames.isEmpty(), "real API sequence must not emit VHRP error frames: " + errorFrames);
  }

  private void assertNoTextAfterCount(String forbiddenText, int previousFrameCount) {
    List<JsonNode> frames = client.allReceived();
    for (int index = Math.min(previousFrameCount, frames.size()); index < frames.size(); index++) {
      assertFalse(
          containsText(frames.get(index), forbiddenText),
          "frames after boundary must not contain stale text " + forbiddenText);
    }
  }

  private void closeSession() throws InterruptedException {
    client.send("session.end", Map.of());
    assertTrue(client.waitForClose(10, TimeUnit.SECONDS) != -1, "session.end must close the socket");
  }

  private static Map<String, Object> sequenceProbeTool() {
    return Map.of(
        "name",
        "vhrp_sequence_probe",
        "description",
        "Returns a deterministic marker for the real API VHRP sequence test.",
        "parameters",
        Map.of(
            "type",
            "object",
            "properties",
            Map.of(),
            "additionalProperties",
            false));
  }

  private static Map<String, Object> sequenceAnswerTool() {
    return Map.of(
        "name",
        "vhrp_required_answer",
        "description",
        "Submits the final user-visible answer when direct answers are blocked by required tool mode.",
        "parameters",
        Map.of(
            "type",
            "object",
            "properties",
            Map.of(
                "answer",
                Map.of(
                    "type",
                    "string",
                    "description",
                    "The exact final answer that should be shown to the user.")),
            "required",
            List.of("answer"),
            "additionalProperties",
            false));
  }

  private FunctionCallFrame waitForNextFunctionCall(
      int previousFrameCount, Set<String> seenItemIds, long timeout, TimeUnit unit)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + unit.toMillis(timeout);
    while (System.currentTimeMillis() < deadline) {
      List<JsonNode> frames = client.allReceived();
      for (int index = Math.min(previousFrameCount, frames.size()); index < frames.size(); index++) {
        JsonNode frame = frames.get(index);
        if (!"thread.patch".equals(text(frame, "type"))) {
          continue;
        }
        for (JsonNode item : findItemsInPatch(frame, "functionCall")) {
          String itemId = text(item, "id");
          if (itemId == null || !seenItemIds.add(itemId)) {
            continue;
          }
          String callId = waitForField(itemId, "callId", timeout, unit);
          String name = waitForField(itemId, "name", timeout, unit);
          assertNotNull(callId, "functionCall must include callId");
          assertNotNull(name, "functionCall must include name");
          return new FunctionCallFrame(itemId, callId, name);
        }
      }
      Thread.sleep(100);
    }
    throw new AssertionError(
        "Timed out waiting for next unseen functionCall; seen="
            + seenItemIds
            + "; frames="
            + client.allReceived());
  }

  private JsonNode waitForPatchContainingItemType(String itemType, long timeout, TimeUnit unit)
      throws InterruptedException {
    return waitForPatchContainingItemTypeAfterCount(itemType, 0, timeout, unit);
  }

  private JsonNode waitForPatchContainingItemTypeAfterCount(
      String itemType, int previousFrameCount, long timeout, TimeUnit unit) throws InterruptedException {
    long deadline = System.currentTimeMillis() + unit.toMillis(timeout);
    while (System.currentTimeMillis() < deadline) {
      List<JsonNode> frames = client.allReceived();
      for (int index = Math.min(previousFrameCount, frames.size()); index < frames.size(); index++) {
        JsonNode frame = frames.get(index);
        if ("thread.patch".equals(text(frame, "type"))
            && findItemInPatch(frame, itemType) != null) {
          return frame;
        }
      }
      Thread.sleep(100);
    }
    throw new AssertionError(
        "Timed out waiting for thread.patch with item type "
            + itemType
            + "; frames="
            + client.allReceived());
  }

  private JsonNode waitForPatchContainingText(String expected, long timeout, TimeUnit unit)
      throws InterruptedException {
    return waitForPatchContainingTextAfterCount(expected, 0, timeout, unit);
  }

  private JsonNode waitForPatchContainingTextAfterCount(
      String expected, int previousFrameCount, long timeout, TimeUnit unit) throws InterruptedException {
    long deadline = System.currentTimeMillis() + unit.toMillis(timeout);
    while (System.currentTimeMillis() < deadline) {
      List<JsonNode> frames = client.allReceived();
      for (int index = Math.min(previousFrameCount, frames.size()); index < frames.size(); index++) {
        JsonNode frame = frames.get(index);
        if ("thread.patch".equals(text(frame, "type")) && containsText(frame, expected)) {
          return frame;
        }
      }
      Thread.sleep(100);
    }
    throw new AssertionError("Timed out waiting for thread.patch containing " + expected);
  }

  private String waitForField(String itemId, String fieldName, long timeout, TimeUnit unit)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + unit.toMillis(timeout);
    String latest = null;
    while (System.currentTimeMillis() < deadline) {
      for (JsonNode frame : client.allReceived()) {
        if (!"thread.patch".equals(text(frame, "type"))) {
          continue;
        }
        String value = fieldValueFromPatch(frame, itemId, fieldName);
        if (value != null) {
          latest = value;
          if (!value.isBlank()) {
            return value;
          }
        }
      }
      Thread.sleep(100);
    }
    throw new AssertionError(
        "Timed out waiting for "
            + fieldName
            + " of "
            + itemId
            + " to be present; latest="
            + latest);
  }

  private String waitForFieldContaining(
      String itemId, String fieldName, String expected, long timeout, TimeUnit unit)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + unit.toMillis(timeout);
    String latest = null;
    while (System.currentTimeMillis() < deadline) {
      for (JsonNode frame : client.allReceived()) {
        if (!"thread.patch".equals(text(frame, "type"))) {
          continue;
        }
        String value = fieldValueFromPatch(frame, itemId, fieldName);
        if (value != null) {
          latest = value;
          if (value.contains(expected)) {
            return value;
          }
        }
      }
      Thread.sleep(100);
    }
    throw new AssertionError(
        "Timed out waiting for "
            + fieldName
            + " of "
            + itemId
            + " to contain "
            + expected
            + "; latest="
            + latest);
  }

  private void waitForItemStatus(String itemId, String expectedStatus, long timeout, TimeUnit unit)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + unit.toMillis(timeout);
    String latest = null;
    while (System.currentTimeMillis() < deadline) {
      for (JsonNode frame : client.allReceived()) {
        if (!"thread.patch".equals(text(frame, "type"))) {
          continue;
        }
        String value = itemStatusFromPatch(frame, itemId);
        if (value != null) {
          latest = value;
          if (expectedStatus.equals(value)) {
            return;
          }
        }
      }
      Thread.sleep(100);
    }
    throw new AssertionError(
        "Timed out waiting for status of "
            + itemId
            + " to become "
            + expectedStatus
            + "; latest="
            + latest);
  }

  private JsonNode waitForAssistantAudioDoneAfterCount(int previousFrameCount, long timeout, TimeUnit unit)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + unit.toMillis(timeout);
    while (System.currentTimeMillis() < deadline) {
      List<JsonNode> frames = client.allReceived();
      for (int index = Math.min(previousFrameCount, frames.size()); index < frames.size(); index++) {
        JsonNode frame = frames.get(index);
        if ("assistant.audio.done".equals(text(frame, "type"))) {
          return frame;
        }
      }
      Thread.sleep(100);
    }
    throw new AssertionError("Timed out waiting for assistant.audio.done");
  }

  private long countItemsInPatches(String itemType) {
    return client.allReceived().stream()
        .filter(frame -> "thread.patch".equals(text(frame, "type")))
        .filter(frame -> findItemInPatch(frame, itemType) != null)
        .count();
  }

  private long countFunctionCallItems() {
    return client.allReceived().stream()
        .filter(frame -> "thread.patch".equals(text(frame, "type")))
        .flatMap(frame -> findItemsInPatch(frame, "functionCall").stream())
        .map(item -> text(item, "id"))
        .distinct()
        .count();
  }

  private static JsonNode findItemInPatch(JsonNode patch, String itemType) {
    List<JsonNode> items = findItemsInPatch(patch, itemType);
    return items.isEmpty() ? null : items.getFirst();
  }

  private static List<JsonNode> findItemsInPatch(JsonNode patch, String itemType) {
    JsonNode ops = patch.get("body").get("ops");
    if (ops == null || !ops.isArray()) {
      return List.of();
    }
    List<JsonNode> items = new java.util.ArrayList<>();
    for (JsonNode op : ops) {
      if (!"add_item".equals(text(op, "op"))) {
        continue;
      }
      JsonNode item = op.get("item");
      if (item != null && itemType.equals(text(item, "type"))) {
        items.add(item);
      }
    }
    return items;
  }

  private static String itemStatusFromPatch(JsonNode patch, String itemId) {
    JsonNode ops = patch.get("body").get("ops");
    if (ops == null || !ops.isArray()) {
      return null;
    }
    for (JsonNode op : ops) {
      if ("set_status".equals(text(op, "op")) && itemId.equals(text(op, "itemId"))) {
        return text(op, "status");
      }
    }
    return null;
  }

  private static String fieldValueFromPatch(JsonNode patch, String itemId, String fieldName) {
    JsonNode ops = patch.get("body").get("ops");
    if (ops == null || !ops.isArray()) {
      return null;
    }
    for (JsonNode op : ops) {
      if ("set_field".equals(text(op, "op"))
          && itemId.equals(text(op, "itemId"))
          && fieldName.equals(text(op, "field"))) {
        JsonNode value = op.get("value");
        return value == null ? null : value.asText();
      }
    }
    return null;
  }

  private boolean hasFrameType(String type) {
    return client.allReceived().stream().anyMatch(frame -> type.equals(text(frame, "type")));
  }

  private record FunctionCallFrame(String itemId, String callId, String name) {}

  private record SessionIds(String sessionId, String threadId) {}

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

  private static String text(JsonNode node, String fieldName) {
    JsonNode value = node == null ? null : node.get(fieldName);
    return value == null ? null : value.asText();
  }
}
