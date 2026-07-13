package app.vagina.server;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import app.vagina.server.realtime.VhrpTestClient;
import app.vagina.server.realtime.oai_cc.OaiCcWavEncoder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.client.WireMock;
import io.quarkiverse.wiremock.devservice.ConnectWireMock;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.vertx.core.Vertx;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
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
 * Opt-in real OpenAI sequence tests for the VHRP session-history lifecycle.
 *
 * <p>This companion keeps session-history assertions out of {@link OpenAiRealVhrpSequenceTest}
 * while preserving the same real-provider gate and sequence-shaped user path: Harigata OIDC,
 * DB-backed JWT, hosted VHRP, real OpenAI turn, explicit terminal close, then REST history
 * visibility.
 */
@QuarkusTest
@ConnectWireMock
class OpenAiRealVhrpSessionHistoryTest {

  private static final String MODEL_ID = "voice-agent-prod-cc";
  private static final String REQUIRED_PROPERTY =
      "vagina.realtime.models.voice-agent-prod-cc.api-key";
  private static final String OPT_IN_PROPERTY = "vagina.test.openai.real.enabled";
  private static final String TRANSCRIPT_DIR_PROPERTY = "vagina.test.openai.real.transcript.dir";
  private static final String SPEED_DIAL_HISTORY_PREFIX = "SPEED_DIAL_HISTORY_POLICY_OK:";
  private static final ObjectMapper JSON = new ObjectMapper();

  @TestHTTPResource("/")
  URL testServerUrl;

  private Vertx vertx;
  private VhrpTestClient client;
  WireMock wireMock;

  @BeforeEach
  void setUp() {
    assumeTrue(realOpenAiTestEnabled(), "set -Dvagina.test.openai.real.enabled=true to run");
    assumeTrue(hasRequiredOpenAiConfig(), REQUIRED_PROPERTY + " must be configured");
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

  /**
   * worksSeq08: terminal session history after a hosted real OpenAI conversation.
   *
   * <ol>
   *   <li>User starts authentication.
   *   <li>User authenticates with Harigata and proxies the redirection back to the app.
   *   <li>Server creates or finds the user entity and issues a JWT.
   *   <li>User sees the empty session-history page shape before any hosted call.
   *   <li>User saves a Speed Dial backed by the real OpenAI Chat Completions model.
   *   <li>User starts a hosted call from that Speed Dial.
   *   <li>User conducts a multi-turn real OpenAI conversation with deterministic markers.
   *   <li>The Speed Dial system instruction forces every answer to carry the history prefix.
   *   <li>Real OpenAI answers each turn and every prefixed marker is present in thread state.
   *   <li>User explicitly ends the call.
   *   <li>Server exposes the terminal-saved multi-turn call through session-history list and
   *       detail.
   *   <li>User deletes the history item.
   *   <li>Server hides the deleted session from detail and list and treats a second delete as not
   *       found.
   * </ol>
   */
  @Test
  void worksSeq08() throws Exception {
    String jwt = VhrpAuthTestSupport.obtainValidJwt();
    assertEmptySessionList(jwt);
    assertMissingSessionDetailIsNotFound(jwt);
    assertDeleteMissingSessionIsNotFound(jwt);
    assertBulkDeleteDeduplicatesAndIgnoresMissing(jwt);
    assertListRejectsInvalidLimit(jwt);

    RealHistorySession session =
        saveRealOpenAiTerminalSession(
            jwt,
            "real-history-",
            "worksSeq08.lifecycle",
            List.of(
                new HistoryTurn(
                    "Please answer the first session history marker.",
                    "SESSION_HISTORY_REAL_FIRST_OK"),
                new HistoryTurn(
                    "Now answer the second marker so the saved history proves retained conversation context.",
                    "SESSION_HISTORY_REAL_SECOND_OK"),
                new HistoryTurn(
                    "Finally answer the deletion marker before this call is saved and deleted.",
                    "SESSION_HISTORY_REAL_DELETE_READY_OK")));

    assertSessionHistoryDetail(session);
    assertSessionListContainsId(jwt, session.callSessionId());
    deleteSessionHistory(jwt, session.callSessionId());
    assertSessionHistoryHidden(jwt, session.callSessionId());
    assertDeleteMissingSessionIsNotFound(jwt, session.callSessionId());
  }

  /**
   * worksSeq09: bulk-delete hides terminal history saved from a hosted real OpenAI conversation.
   *
   * <ol>
   *   <li>User starts authentication.
   *   <li>User authenticates with Harigata and proxies the redirection back to the app.
   *   <li>Server creates or finds the user entity and issues a JWT.
   *   <li>User saves a Speed Dial backed by the real OpenAI Chat Completions model.
   *   <li>User starts a hosted call from that Speed Dial.
   *   <li>User conducts a multi-turn real OpenAI conversation with deterministic markers.
   *   <li>The Speed Dial system instruction forces every answer to carry the history prefix.
   *   <li>Real OpenAI answers each turn and every prefixed marker is present in thread state.
   *   <li>User explicitly ends the call.
   *   <li>Server exposes the terminal-saved multi-turn call through session-history list and
   *       detail.
   *   <li>User bulk-deletes the history item together with a duplicate missing id.
   *   <li>Server reports one deleted item and hides the deleted session from detail and list.
   * </ol>
   */
  @Test
  void worksSeq09() throws Exception {
    String jwt = VhrpAuthTestSupport.obtainValidJwt();
    RealHistorySession session =
        saveRealOpenAiTerminalSession(
            jwt,
            "real-history-bulk-",
            "worksSeq09.bulkDelete",
            List.of(
                new HistoryTurn(
                    "Please answer the first bulk-delete history marker.",
                    "SESSION_HISTORY_BULK_FIRST_OK"),
                new HistoryTurn(
                    "Please answer the second bulk-delete history marker before bulk delete.",
                    "SESSION_HISTORY_BULK_SECOND_OK")));

    assertSessionHistoryDetail(session);
    assertSessionListContainsId(jwt, session.callSessionId());
    bulkDeleteSessionHistory(
        jwt,
        List.of(
            session.callSessionId(), UUID.randomUUID().toString(), UUID.randomUUID().toString()),
        1);
    assertSessionHistoryHidden(jwt, session.callSessionId());
  }

  /**
   * worksSeq10: session-history list ordering and cursor after real OpenAI terminal saves.
   *
   * <ol>
   *   <li>User starts authentication.
   *   <li>User authenticates with Harigata and proxies the redirection back to the app.
   *   <li>Server creates or finds the user entity and issues a JWT.
   *   <li>User completes three hosted multi-turn calls backed by real OpenAI.
   *   <li>Each saved call proves the Speed Dial system instruction shaped every assistant answer.
   *   <li>User lists session history with a two-item page size.
   *   <li>Server returns newest terminal-saved call first and emits a next cursor.
   *   <li>User opens details for paged sessions and sees each call's distinct multi-turn
   *       transcript.
   *   <li>User follows the next cursor.
   *   <li>Server returns the oldest terminal-saved call and no further cursor.
   * </ol>
   */
  @Test
  void worksSeq10() throws Exception {
    String jwt = VhrpAuthTestSupport.obtainValidJwt();
    RealHistorySession oldest =
        saveRealOpenAiTerminalSession(
            jwt,
            "real-history-cursor-oldest-",
            "worksSeq10.oldest",
            "SPEED_DIAL_ARCHIVE_HISTORY_OK:",
            List.of(
                new HistoryTurn(
                    "Please answer the oldest first marker.", "SESSION_HISTORY_OLDEST_FIRST_OK"),
                new HistoryTurn(
                    "Please answer the oldest second marker.",
                    "SESSION_HISTORY_OLDEST_SECOND_OK")));
    RealHistorySession middle =
        saveRealOpenAiTerminalSession(
            jwt,
            "real-history-cursor-middle-",
            "worksSeq10.middle",
            "SPEED_DIAL_COACH_HISTORY_OK:",
            List.of(
                new HistoryTurn(
                    "Please answer the middle first marker.", "SESSION_HISTORY_MIDDLE_FIRST_OK"),
                new HistoryTurn(
                    "Please answer the middle second marker.",
                    "SESSION_HISTORY_MIDDLE_SECOND_OK")));
    RealHistorySession newest =
        saveRealOpenAiTerminalSession(
            jwt,
            "real-history-cursor-newest-",
            "worksSeq10.newest",
            "SPEED_DIAL_SUPPORT_HISTORY_OK:",
            List.of(
                new HistoryTurn(
                    "Please answer the newest first marker.", "SESSION_HISTORY_NEWEST_FIRST_OK"),
                new HistoryTurn(
                    "Please answer the newest second marker.",
                    "SESSION_HISTORY_NEWEST_SECOND_OK")));

    Response firstPage =
        given()
            .auth()
            .oauth2(jwt)
            .accept(ContentType.JSON)
            .queryParam("limit", 2)
            .when()
            .get("/api/sessions")
            .then()
            .statusCode(200)
            .body("items.size()", equalTo(2))
            .body("items[0].id", equalTo(newest.callSessionId()))
            .body("items[1].id", equalTo(middle.callSessionId()))
            .body("nextCursor", notNullValue())
            .extract()
            .response();

    assertSessionHistoryDetail(newest);
    assertSessionHistoryDetail(middle);

    given()
        .auth()
        .oauth2(jwt)
        .accept(ContentType.JSON)
        .queryParam("limit", 2)
        .queryParam("cursor", firstPage.jsonPath().getString("nextCursor"))
        .when()
        .get("/api/sessions")
        .then()
        .statusCode(200)
        .body("items.size()", equalTo(1))
        .body("items[0].id", equalTo(oldest.callSessionId()))
        .body("nextCursor", nullValue());

    assertSessionHistoryDetail(oldest);
  }

  /**
   * worksSeq11: session-history ownership isolation after real OpenAI terminal saves.
   *
   * <ol>
   *   <li>Owner starts authentication and receives an app JWT.
   *   <li>Owner completes a hosted multi-turn call backed by real OpenAI whose answers carry the
   *       Speed Dial history prefix.
   *   <li>Second user starts authentication and receives a distinct app JWT.
   *   <li>Second user completes a hosted multi-turn call backed by real OpenAI whose answers carry
   *       the Speed Dial history prefix.
   *   <li>Owner cannot see second user's session detail or transcript markers.
   *   <li>Owner bulk-deletes own session together with second user's id.
   *   <li>Server reports only one deleted item and hides both ids from owner's list.
   *   <li>Second user can still see their own session detail.
   * </ol>
   */
  @Test
  void worksSeq11() throws Exception {
    stubHarigataUser("history-owner-subject", "history-owner", "history-owner@example.test");
    String ownerJwt = VhrpAuthTestSupport.obtainValidJwt();
    RealHistorySession ownerSession =
        saveRealOpenAiTerminalSession(
            ownerJwt,
            "real-history-owner-",
            "worksSeq11.owner",
            "SPEED_DIAL_OWNER_PRIVATE_HISTORY_OK:",
            List.of(
                new HistoryTurn(
                    "Owner asks for the first private marker.", "SESSION_HISTORY_OWNER_FIRST_OK"),
                new HistoryTurn(
                    "Owner asks for the second private marker.",
                    "SESSION_HISTORY_OWNER_SECOND_OK")));

    stubHarigataUser("history-other-subject", "history-other", "history-other@example.test");
    String otherJwt = VhrpAuthTestSupport.obtainValidJwt();
    RealHistorySession otherSession =
        saveRealOpenAiTerminalSession(
            otherJwt,
            "real-history-other-",
            "worksSeq11.other",
            "SPEED_DIAL_OTHER_PRIVATE_HISTORY_OK:",
            List.of(
                new HistoryTurn(
                    "Other user asks for the first private marker.",
                    "SESSION_HISTORY_OTHER_FIRST_OK"),
                new HistoryTurn(
                    "Other user asks for the second private marker.",
                    "SESSION_HISTORY_OTHER_SECOND_OK")));

    assertMissingSessionDetailIsNotFound(ownerJwt, otherSession.callSessionId());
    bulkDeleteSessionHistory(
        ownerJwt, List.of(ownerSession.callSessionId(), otherSession.callSessionId()), 1);

    given()
        .auth()
        .oauth2(ownerJwt)
        .accept(ContentType.JSON)
        .when()
        .get("/api/sessions")
        .then()
        .statusCode(200)
        .body("items.find { it.id == '" + ownerSession.callSessionId() + "' }", nullValue())
        .body("items.find { it.id == '" + otherSession.callSessionId() + "' }", nullValue());

    given()
        .auth()
        .oauth2(otherJwt)
        .accept(ContentType.JSON)
        .when()
        .get("/api/sessions/{sessionId}", otherSession.callSessionId())
        .then()
        .statusCode(200)
        .body("id", equalTo(otherSession.callSessionId()));
    assertSessionHistoryDetail(otherSession);
  }

  /**
   * worksSeq12: resumed hosted conversation is saved as one multi-turn terminal history item.
   *
   * <ol>
   *   <li>User starts authentication.
   *   <li>User starts a hosted call backed by real OpenAI and records the sessionId/threadId.
   *   <li>User completes a first real OpenAI turn shaped by the Speed Dial history prefix.
   *   <li>The socket drops without session.end.
   *   <li>User resumes the same sessionId with the same JWT.
   *   <li>User completes a second real OpenAI turn after resume with the same Speed Dial prefix.
   *   <li>User explicitly ends the call.
   *   <li>Server saves one session-history item containing both pre-drop and post-resume turns.
   * </ol>
   */
  @Test
  void worksSeq12() throws Exception {
    String jwt = VhrpAuthTestSupport.obtainValidJwt();
    RealHistorySession session =
        saveResumedRealOpenAiTerminalSession(
            jwt,
            "real-history-resume-",
            "worksSeq12.resume",
            new HistoryTurn(
                "Please answer the pre-resume history marker.", "SESSION_HISTORY_RESUME_BEFORE_OK"),
            new HistoryTurn(
                "Please answer the post-resume history marker.",
                "SESSION_HISTORY_RESUME_AFTER_OK"));

    assertSessionHistoryDetail(session);
    assertSessionListContainsId(jwt, session.callSessionId());
  }

  /**
   * worksSeq13: speed-dial tool-required policy drives repeated real OpenAI tool use that remains
   * usable in saved history.
   *
   * <ol>
   *   <li>User saves a Speed Dial with tool_choice_required enabled.
   *   <li>User starts a hosted call from that Speed Dial and registers the app tool catalog.
   *   <li>User asks real OpenAI to call the app tool twice.
   *   <li>App submits two tool results.
   *   <li>Speed Dial's required-tool policy keeps the turn in tool-call space instead of requiring
   *       a direct assistant transcript.
   *   <li>User explicitly ends the call.
   *   <li>Server saves a history detail whose thread contains user text, multiple function calls,
   *       and tool outputs in a product-usable shape.
   * </ol>
   */
  @Test
  void worksSeq13() throws Exception {
    String jwt = VhrpAuthTestSupport.obtainValidJwt();
    RealHistorySession session =
        saveToolRequiredRealOpenAiTerminalSession(
            jwt,
            "real-history-tools-",
            "worksSeq13.tools",
            "Call vhrp_history_probe exactly twice. Do not use any other tool.",
            List.of("SESSION_HISTORY_TOOL_RESULT_ONE", "SESSION_HISTORY_TOOL_RESULT_TWO"));

    assertSessionHistoryDetail(session);
    assertSessionListContainsId(jwt, session.callSessionId());
  }

  private RealHistorySession saveRealOpenAiTerminalSession(
      String jwt, String speedDialPrefix, String artifactName, List<HistoryTurn> turns)
      throws Exception {
    return saveRealOpenAiTerminalSession(
        jwt, speedDialPrefix, artifactName, SPEED_DIAL_HISTORY_PREFIX, turns);
  }

  private RealHistorySession saveRealOpenAiTerminalSession(
      String jwt,
      String speedDialPrefix,
      String artifactName,
      String instructionPrefix,
      List<HistoryTurn> turns)
      throws Exception {
    String speedDialId = saveRealOpenAiSpeedDial(jwt, instructionPrefix);
    SessionIds sessionIds = openAuthenticatedSession(jwt, speedDialId);

    conductRealOpenAiTurns(instructionPrefix, turns);
    assertNoErrorFramesSoFar();
    writeTranscriptArtifact(artifactName);
    closeSession();

    Response detail = waitForSessionHistoryDetailBySpeedDial(jwt, speedDialId);
    String callSessionId = detail.jsonPath().getString("id");
    RealHistorySession session =
        new RealHistorySession(
            jwt, callSessionId, speedDialId, sessionIds.threadId(), instructionPrefix, turns);
    assertSessionHistoryDetail(session, detail);
    replaceClient();
    return session;
  }

  private RealHistorySession saveResumedRealOpenAiTerminalSession(
      String jwt,
      String speedDialPrefix,
      String artifactName,
      HistoryTurn beforeResume,
      HistoryTurn afterResume)
      throws Exception {
    String speedDialId = saveRealOpenAiSpeedDial(jwt);
    SessionIds sessionIds = openAuthenticatedSession(jwt, speedDialId);

    conductRealOpenAiTurns(SPEED_DIAL_HISTORY_PREFIX, List.of(beforeResume));
    client.close();
    assertTrue(
        client.waitForClose(10, TimeUnit.SECONDS) != -1, "socket drop must close the first client");

    client = new VhrpTestClient(vertx);
    client.connect(testPort(), "vhrp.cbor.v1");
    String resumeMsgId = client.sendSessionOpenResume(jwt, speedDialId, sessionIds.sessionId());
    JsonNode resumed = client.waitForMessage("session.resumed", 20, TimeUnit.SECONDS);
    assertEquals(resumeMsgId, text(resumed, "replyTo"));
    assertEquals(sessionIds.sessionId(), text(resumed.get("body"), "sessionId"));
    assertEquals(sessionIds.threadId(), text(resumed.get("body"), "threadId"));
    assertSnapshotContains(beforeResume.assistantMarker());

    conductRealOpenAiTurns(SPEED_DIAL_HISTORY_PREFIX, List.of(afterResume));
    assertNoErrorFramesSoFar();
    writeTranscriptArtifact(artifactName);
    closeSession();

    Response detail = waitForSessionHistoryDetailBySpeedDial(jwt, speedDialId);
    String callSessionId = detail.jsonPath().getString("id");
    RealHistorySession session =
        new RealHistorySession(
            jwt,
            callSessionId,
            speedDialId,
            sessionIds.threadId(),
            List.of(beforeResume, afterResume));
    assertSessionHistoryDetail(session, detail);
    replaceClient();
    return session;
  }

  private void conductRealOpenAiTurns(String instructionPrefix, List<HistoryTurn> turns)
      throws InterruptedException {
    for (HistoryTurn turn : turns) {
      String expectedAssistantText =
          speedDialExpectedAssistantText(instructionPrefix, turn.assistantMarker());
      String turnMsgId =
          client.sendTurnTextSubmit(
              "ci_" + UUID.randomUUID(),
              turn.submittedText()
                  + " Reply with exactly "
                  + turn.assistantMarker()
                  + " after the mandatory Speed Dial prefix, and no other content.");
      assertAcceptedAck(turnMsgId);
      JsonNode assistantPatch =
          waitForPatchContainingText(expectedAssistantText, 60, TimeUnit.SECONDS);
      assertNotNull(
          assistantPatch,
          "real OpenAI turn must answer with the Speed Dial-shaped marker before terminal history save");
      assertSnapshotContains(turn.submittedText());
      assertSnapshotContains(expectedAssistantText);
    }
  }

  private RealHistorySession saveToolRequiredRealOpenAiTerminalSession(
      String jwt,
      String speedDialPrefix,
      String artifactName,
      String submittedText,
      List<String> toolResults)
      throws Exception {
    String speedDialId = saveRealOpenAiToolSpeedDial(jwt);
    SessionIds sessionIds = openAuthenticatedSession(jwt, speedDialId);

    String toolsMsgId = client.sendToolsSet(List.of(historyProbeTool()));
    assertAcceptedAck(toolsMsgId);

    int beforeTurnFrameCount = client.allReceived().size();
    String turnMsgId = client.sendTurnTextSubmit("ci_" + UUID.randomUUID(), submittedText);
    assertAcceptedAck(turnMsgId);

    Set<String> seenFunctionCallItemIds = new HashSet<>();
    for (int index = 0; index < toolResults.size(); index++) {
      FunctionCallFrame toolCall =
          waitForNextFunctionCall(
              beforeTurnFrameCount, seenFunctionCallItemIds, 90, TimeUnit.SECONDS);
      assertEquals(
          "vhrp_history_probe",
          toolCall.name(),
          "history tool call must use the Speed Dial tool catalog");
      String resultMsgId =
          client.sendToolResultSubmit(
              "ri_" + UUID.randomUUID(), toolCall.callId(), toolResults.get(index), "success");
      assertAcceptedAck(resultMsgId);
    }

    assertSnapshotContains(submittedText);
    for (String toolResult : toolResults) {
      assertSnapshotContains(toolResult);
    }
    assertNoErrorFramesSoFar();
    writeTranscriptArtifact(artifactName);
    closeSession();

    Response detail = waitForSessionHistoryDetailBySpeedDial(jwt, speedDialId);
    String callSessionId = detail.jsonPath().getString("id");
    RealHistorySession session =
        new RealHistorySession(
            jwt,
            callSessionId,
            speedDialId,
            sessionIds.threadId(),
            List.of(new HistoryTurn(submittedText, null)),
            List.of(
                new HistoryToolExpectation("vhrp_history_probe", toolResults.size(), toolResults)));
    assertSessionHistoryDetail(session, detail);
    replaceClient();
    return session;
  }

  private void replaceClient() {
    if (client != null) {
      client.close();
    }
    client = new VhrpTestClient(vertx);
  }

  private SessionIds openAuthenticatedSession(String jwt, String speedDialId) throws Exception {
    client.connect(testPort(), "vhrp.cbor.v1");
    String openMsgId = client.sendSessionOpen(jwt, speedDialId);

    JsonNode ready = client.waitForMessage("session.ready", 20, TimeUnit.SECONDS);
    assertEquals(openMsgId, text(ready, "replyTo"));
    assertNotNull(ready.get("body").get("sessionId"), "session.ready must expose sessionId");
    assertNotNull(ready.get("body").get("threadId"), "session.ready must expose threadId");
    return new SessionIds(
        ready.get("body").get("sessionId").asText(), ready.get("body").get("threadId").asText());
  }

  private String saveRealOpenAiSpeedDial(String token) {
    return saveRealOpenAiSpeedDial(token, SPEED_DIAL_HISTORY_PREFIX);
  }

  private String saveRealOpenAiSpeedDial(String token, String instructionPrefix) {
    return saveRealOpenAiSpeedDial(token, false, Map.of(), instructionPrefix);
  }

  private String saveRealOpenAiToolSpeedDial(String token) {
    return saveRealOpenAiSpeedDial(
        token, true, Map.of("vhrp_history_probe", true), SPEED_DIAL_HISTORY_PREFIX);
  }

  private String saveRealOpenAiSpeedDial(
      String token,
      boolean toolChoiceRequired,
      Map<String, Boolean> enabledTools,
      String instructionPrefix) {
    Response response =
        given()
            .auth()
            .oauth2(token)
            .contentType(ContentType.JSON)
            .accept(ContentType.JSON)
            .body(
                Map.of(
                    "name",
                    "Real OpenAI History Sequence",
                    "systemPrompt",
                    speedDialHistorySystemPrompt(toolChoiceRequired, instructionPrefix),
                    "voice",
                    "alloy",
                    "voiceAgentId",
                    MODEL_ID,
                    "enabledTools",
                    enabledTools,
                    "toolChoiceRequired",
                    toolChoiceRequired))
            .when()
            .post("/api/speed-dials")
            .then()
            .statusCode(201)
            .body("id", matchesPattern("sd_[0-9a-f]{32}"))
            .body("voiceAgentId", equalTo(MODEL_ID))
            .extract()
            .response();
    return response.jsonPath().getString("id");
  }

  private Response waitForSessionHistoryDetailBySpeedDial(String token, String speedDialId)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    Response lastListResponse = null;
    Response lastDetailResponse = null;
    while (System.nanoTime() < deadline) {
      lastListResponse =
          given().auth().oauth2(token).accept(ContentType.JSON).when().get("/api/sessions");
      if (lastListResponse.statusCode() == 200) {
        List<String> sessionIds = lastListResponse.jsonPath().getList("items.id", String.class);
        for (String sessionId : sessionIds) {
          lastDetailResponse =
              given()
                  .auth()
                  .oauth2(token)
                  .accept(ContentType.JSON)
                  .when()
                  .get("/api/sessions/{sessionId}", sessionId);
          if (lastDetailResponse.statusCode() == 200
              && speedDialId.equals(lastDetailResponse.jsonPath().getString("speedDialId"))) {
            return lastDetailResponse;
          }
        }
      }
      Thread.sleep(100);
    }
    throw new AssertionError(
        "session history detail with speedDialId "
            + speedDialId
            + " was not found; last list response was "
            + (lastListResponse == null ? "<none>" : lastListResponse.asString())
            + "; last detail response was "
            + (lastDetailResponse == null ? "<none>" : lastDetailResponse.asString()));
  }

  private void assertEmptySessionList(String token) {
    given()
        .auth()
        .oauth2(token)
        .accept(ContentType.JSON)
        .when()
        .get("/api/sessions")
        .then()
        .statusCode(200)
        .body("items.size()", equalTo(0))
        .body("nextCursor", nullValue());
  }

  private void assertMissingSessionDetailIsNotFound(String token) {
    assertMissingSessionDetailIsNotFound(token, UUID.randomUUID().toString());
  }

  private void assertMissingSessionDetailIsNotFound(String token, String callSessionId) {
    given()
        .auth()
        .oauth2(token)
        .accept(ContentType.JSON)
        .when()
        .get("/api/sessions/{sessionId}", callSessionId)
        .then()
        .statusCode(404)
        .body("message", notNullValue());
  }

  private void assertDeleteMissingSessionIsNotFound(String token) {
    assertDeleteMissingSessionIsNotFound(token, UUID.randomUUID().toString());
  }

  private void assertDeleteMissingSessionIsNotFound(String token, String callSessionId) {
    given()
        .auth()
        .oauth2(token)
        .accept(ContentType.JSON)
        .when()
        .delete("/api/sessions/{sessionId}", callSessionId)
        .then()
        .statusCode(404)
        .body("message", notNullValue());
  }

  private void assertBulkDeleteDeduplicatesAndIgnoresMissing(String token) {
    String missingId = UUID.randomUUID().toString();
    bulkDeleteSessionHistory(token, List.of(missingId, missingId), 0);
  }

  private void assertListRejectsInvalidLimit(String token) {
    given()
        .auth()
        .oauth2(token)
        .accept(ContentType.JSON)
        .queryParam("limit", 51)
        .when()
        .get("/api/sessions")
        .then()
        .statusCode(400);
  }

  private void assertSessionHistoryDetail(RealHistorySession session) {
    Response detail =
        given()
            .auth()
            .oauth2(session.token())
            .accept(ContentType.JSON)
            .when()
            .get("/api/sessions/{sessionId}", session.callSessionId())
            .then()
            .statusCode(200)
            .body("id", equalTo(session.callSessionId()))
            .extract()
            .response();
    assertSessionHistoryDetail(session, detail);
  }

  private void assertSessionHistoryDetail(RealHistorySession session, Response detail) {
    String detailJson = detail.asString();
    detail
        .then()
        .statusCode(200)
        .body("id", equalTo(session.callSessionId()))
        .body("speedDialId", equalTo(session.speedDialId()))
        .body("voiceAgentId", equalTo(MODEL_ID))
        .body("thread.id", equalTo(session.threadId()))
        .body("thread.conversationId", notNullValue())
        .body("thread.items.size()", notNullValue());

    JsonNode thread = readJson(detailJson).get("thread");
    assertNotNull(thread, "history detail must include a thread object");
    assertEquals(
        session.threadId(), text(thread, "id"), "history thread id must match VHRP thread id");
    assertNotNull(
        text(thread, "conversationId"), "history thread must retain provider conversation id");

    JsonNode items = thread.get("items");
    assertNotNull(items, "history thread must include items");
    assertTrue(items.isArray(), "history thread items must be an array");
    assertTrue(
        items.size() >= 2, "history thread must retain at least user and assistant messages");

    for (HistoryTurn turn : session.turns()) {
      JsonNode userItem = findVisibleMessageWithText(items, "user", turn.submittedText());
      assertNotNull(
          userItem,
          "history detail must contain the exact saved user text item: " + turn.submittedText());
      assertEquals("message", text(userItem, "type"));
      assertEquals("completed", text(userItem, "status"));

      if (turn.assistantMarker() != null) {
        String expectedAssistantText =
            speedDialExpectedAssistantText(
                session.speedDialInstructionPrefix(), turn.assistantMarker());
        JsonNode assistantItem =
            findVisibleMessageWithText(items, "assistant", expectedAssistantText);
        assertNotNull(
            assistantItem,
            "history detail must contain the Speed Dial-shaped real OpenAI assistant marker item: "
                + expectedAssistantText);
        assertEquals("message", text(assistantItem, "type"));
        assertEquals("completed", text(assistantItem, "status"));
      }
    }

    assertSpeedDialInstructionShapedHistory(session, items);
    assertProductUsableThread(
        items, session.turns().stream().anyMatch(turn -> turn.assistantMarker() != null));
    assertToolHistory(session, items);
    assertFalse(
        detailJson.contains("audioChunks"), "history projection must omit raw audio chunks");
  }

  private void assertSpeedDialInstructionShapedHistory(RealHistorySession session, JsonNode items) {
    long expectedAssistantMessages =
        session.turns().stream().filter(turn -> turn.assistantMarker() != null).count();
    if (expectedAssistantMessages == 0) {
      return;
    }

    int shapedAssistantMessages = 0;
    for (JsonNode item : items) {
      if ("message".equals(text(item, "type"))
          && "assistant".equals(text(item, "role"))
          && "completed".equals(text(item, "status"))
          && messageContainsText(item, session.speedDialInstructionPrefix())) {
        shapedAssistantMessages++;
      }
    }

    assertEquals(
        expectedAssistantMessages,
        shapedAssistantMessages,
        "every completed assistant answer in saved history must visibly obey the Speed Dial instruction prefix");
  }

  private void assertProductUsableThread(JsonNode items, boolean expectAssistantTranscript) {
    int renderableMessages = 0;
    for (JsonNode item : items) {
      String type = text(item, "type");
      String displayState = text(item, "displayState");
      if ("message".equals(type)) {
        assertEquals(
            "visible", displayState, "saved messages must be visible to the history renderer");
        assertNotNull(text(item, "role"), "saved message must have a product-renderable role");
        if ("completed".equals(text(item, "status"))) {
          assertTrue(
              hasRenderableContent(item),
              "saved completed message must have text or audio transcript content: " + item);
          renderableMessages++;
        } else {
          assertEquals(
              "assistant",
              text(item, "role"),
              "only assistant placeholders may remain non-completed in history");
        }
      } else if ("functionCall".equals(type)) {
        assertNotNull(
            text(item, "name"), "saved function calls must keep tool name for renderer badges");
        assertNotNull(
            text(item, "callId"), "saved function calls must keep callId for tool output matching");
        if (expectAssistantTranscript) {
          assertEquals(
              "completed",
              text(item, "status"),
              "saved function calls must be terminal for read-only history");
        } else {
          assertNotNull(
              text(item, "status"),
              "saved function calls must keep provider status for renderer badges");
        }
      } else if ("functionCallOutput".equals(type)) {
        assertNotNull(
            text(item, "callId"), "saved tool outputs must keep callId for tool result matching");
        assertNotNull(
            text(item, "output"), "saved tool outputs must keep output for details sheet");
        assertEquals("success", text(item, "toolOutputDisposition"));
      }
    }
    assertTrue(
        renderableMessages >= 1, "saved history must have at least one renderable user message");
    if (expectAssistantTranscript) {
      assertTrue(
          renderableMessages >= 2,
          "saved history must have renderable user and assistant messages");
    }
  }

  private boolean messageContainsText(JsonNode item, String expectedText) {
    JsonNode content = item.get("content");
    if (content == null || !content.isArray()) {
      return false;
    }
    for (JsonNode part : content) {
      String contentText = text(part, "text");
      if (contentText != null && contentText.contains(expectedText)) {
        return true;
      }
      String transcript = text(part, "transcript");
      if (transcript != null && transcript.contains(expectedText)) {
        return true;
      }
    }
    return false;
  }

  private boolean hasRenderableContent(JsonNode item) {
    JsonNode content = item.get("content");
    if (content == null || !content.isArray()) {
      return false;
    }
    for (JsonNode part : content) {
      String type = text(part, "type");
      if ("text".equals(type) && text(part, "text") != null && !text(part, "text").isBlank()) {
        return true;
      }
      if ("audio".equals(type)
          && text(part, "transcript") != null
          && !text(part, "transcript").isBlank()) {
        return true;
      }
    }
    return false;
  }

  private void assertToolHistory(RealHistorySession session, JsonNode items) {
    for (HistoryToolExpectation expectation : session.toolExpectations()) {
      int callCount = 0;
      for (JsonNode item : items) {
        if ("functionCall".equals(text(item, "type"))
            && expectation.name().equals(text(item, "name"))) {
          callCount++;
        }
      }
      assertEquals(
          expectation.expectedCallCount(),
          callCount,
          "saved history must preserve repeated tool calls");
      for (String output : expectation.outputs()) {
        assertNotNull(
            findToolOutputWithText(items, output),
            "saved history must preserve tool output: " + output);
      }
    }
  }

  private JsonNode findToolOutputWithText(JsonNode items, String expectedText) {
    for (JsonNode item : items) {
      if ("functionCallOutput".equals(text(item, "type"))
          && text(item, "output") != null
          && text(item, "output").contains(expectedText)) {
        return item;
      }
    }
    return null;
  }

  private JsonNode findVisibleMessageWithText(JsonNode items, String role, String expectedText) {
    for (JsonNode item : items) {
      if (!"message".equals(text(item, "type")) || !role.equals(text(item, "role"))) {
        continue;
      }
      JsonNode content = item.get("content");
      if (content == null || !content.isArray()) {
        continue;
      }
      for (JsonNode part : content) {
        String contentText = text(part, "text");
        String audioTranscript = text(part, "transcript");
        if ("text".equals(text(part, "type"))
            && contentText != null
            && contentText.contains(expectedText)) {
          return item;
        }
        if ("audio".equals(text(part, "type"))
            && audioTranscript != null
            && audioTranscript.contains(expectedText)) {
          return item;
        }
      }
    }
    return null;
  }

  private static JsonNode readJson(String json) {
    try {
      return JSON.readTree(json);
    } catch (Exception e) {
      throw new AssertionError("history detail must be valid JSON", e);
    }
  }

  private void assertSessionListContainsId(String token, String callSessionId) {
    given()
        .auth()
        .oauth2(token)
        .accept(ContentType.JSON)
        .when()
        .get("/api/sessions")
        .then()
        .statusCode(200)
        .body("items.find { it.id == '" + callSessionId + "' }.id", equalTo(callSessionId));
  }

  private void bulkDeleteSessionHistory(
      String token, List<String> callSessionIds, int expectedDeletedCount) {
    given()
        .auth()
        .oauth2(token)
        .contentType(ContentType.JSON)
        .accept(ContentType.JSON)
        .body(Map.of("ids", callSessionIds))
        .when()
        .post("/api/sessions/bulk-delete")
        .then()
        .statusCode(200)
        .body("deletedCount", equalTo(expectedDeletedCount));
  }

  private void deleteSessionHistory(String token, String callSessionId) {
    given()
        .auth()
        .oauth2(token)
        .accept(ContentType.JSON)
        .when()
        .delete("/api/sessions/{sessionId}", callSessionId)
        .then()
        .statusCode(204);
  }

  private void assertSessionHistoryHidden(String token, String callSessionId) {
    given()
        .auth()
        .oauth2(token)
        .accept(ContentType.JSON)
        .when()
        .get("/api/sessions/{sessionId}", callSessionId)
        .then()
        .statusCode(404);

    given()
        .auth()
        .oauth2(token)
        .accept(ContentType.JSON)
        .when()
        .get("/api/sessions")
        .then()
        .statusCode(200)
        .body("items.find { it.id == '" + callSessionId + "' }", nullValue());
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
    JsonNode snapshot =
        client.waitForNextMessageAfterCount(beforeSyncFrameCount, 20, TimeUnit.SECONDS);
    assertEquals("thread.snapshot", text(snapshot, "type"));
    assertNotNull(snapshot.get("body").get("threadId"));
    assertTrue(snapshot.get("body").get("items").isArray(), "snapshot items must be an array");
    return snapshot;
  }

  private void assertNoErrorFramesSoFar() {
    List<JsonNode> errorFrames =
        client.allReceived().stream().filter(frame -> "error".equals(text(frame, "type"))).toList();
    assertTrue(
        errorFrames.isEmpty(), "real API sequence must not emit VHRP error frames: " + errorFrames);
  }

  private void closeSession() throws InterruptedException {
    client.send("session.end", Map.of());
    assertTrue(
        client.waitForClose(10, TimeUnit.SECONDS) != -1, "session.end must close the socket");
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

  private void writeTranscriptArtifact(String sequenceName)
      throws IOException, InterruptedException {
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
        dir.resolve(sequenceName + ".md"),
        renderTranscriptMarkdown(sequenceName, snapshot),
        StandardCharsets.UTF_8);
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
      pcmByPart
          .computeIfAbsent(key, ignored -> new ByteArrayOutputStream())
          .write(pcm.binaryValue());
    }

    for (Map.Entry<String, ByteArrayOutputStream> entry : pcmByPart.entrySet()) {
      byte[] pcm = entry.getValue().toByteArray();
      if (pcm.length == 0) {
        continue;
      }
      Files.write(
          dir.resolve(sequenceName + "." + entry.getKey() + ".wav"), OaiCcWavEncoder.encode(pcm));
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
    markdown
        .append("- threadId: `")
        .append(text(body, "threadId"))
        .append('`')
        .append(System.lineSeparator());
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
      markdown
          .append("- ")
          .append(key)
          .append(": `")
          .append(value)
          .append('`')
          .append(System.lineSeparator());
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

  private static String speedDialHistorySystemPrompt(
      boolean toolChoiceRequired, String instructionPrefix) {
    String basePrompt =
        "You are the hosted VHRP real OpenAI session-history sequence agent. "
            + "For every direct assistant message, start the response with exactly "
            + instructionPrefix
            + " followed by one space. This prefix is a Speed Dial instruction and must be preserved "
            + "even when a later user message names a test marker.";
    if (!toolChoiceRequired) {
      return basePrompt;
    }
    return basePrompt
        + " When tool choice is required, obey the required tool policy and produce function calls instead of a direct answer.";
  }

  private static String speedDialExpectedAssistantText(
      String instructionPrefix, String assistantMarker) {
    return instructionPrefix + " " + assistantMarker;
  }

  private void stubHarigataUser(String subject, String login, String email) {
    wireMock.register(
        get(urlPathEqualTo("/userinfo"))
            .atPriority(1)
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {
                          "sub": "%s",
                          "preferred_username": "%s",
                          "name": "%s",
                          "picture": "https://harigata.example.test/%s.png",
                          "email": "%s",
                          "email_verified": true
                        }
                        """
                            .formatted(subject, login, login, login, email))));
  }

  private static Map<String, Object> historyProbeTool() {
    return Map.of(
        "name",
        "vhrp_history_probe",
        "description",
        "Returns deterministic session-history markers supplied by the app.",
        "parameters",
        Map.of("type", "object", "properties", Map.of(), "additionalProperties", false));
  }

  private FunctionCallFrame waitForNextFunctionCall(
      int previousFrameCount, Set<String> seenItemIds, long timeout, TimeUnit unit)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + unit.toMillis(timeout);
    while (System.currentTimeMillis() < deadline) {
      List<JsonNode> frames = client.allReceived();
      for (int index = Math.min(previousFrameCount, frames.size());
          index < frames.size();
          index++) {
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
    throw new AssertionError("Timed out waiting for next unseen functionCall; seen=" + seenItemIds);
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
        "Timed out waiting for " + fieldName + " of " + itemId + "; latest=" + latest);
  }

  private static List<JsonNode> findItemsInPatch(JsonNode patch, String itemType) {
    JsonNode body = patch.get("body");
    JsonNode ops = body == null ? null : body.get("ops");
    if (ops == null || !ops.isArray()) {
      return List.of();
    }
    List<JsonNode> items = new ArrayList<>();
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

  private void waitForItemStatus(String itemId, String expectedStatus, long timeout, TimeUnit unit)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + unit.toMillis(timeout);
    String latest = null;
    while (System.currentTimeMillis() < deadline) {
      for (JsonNode frame : client.allReceived()) {
        if (!"thread.patch".equals(text(frame, "type"))) {
          continue;
        }
        String status = itemStatusFromPatch(frame, itemId);
        if (status != null) {
          latest = status;
          if (expectedStatus.equals(status)) {
            return;
          }
        }
      }
      Thread.sleep(100);
    }
    throw new AssertionError(
        "Timed out waiting for " + itemId + " status " + expectedStatus + "; latest=" + latest);
  }

  private static String itemStatusFromPatch(JsonNode patch, String itemId) {
    JsonNode body = patch.get("body");
    JsonNode ops = body == null ? null : body.get("ops");
    if (ops == null || !ops.isArray()) {
      return null;
    }
    for (JsonNode op : ops) {
      if ("add_item".equals(text(op, "op"))) {
        JsonNode item = op.get("item");
        if (item != null && itemId.equals(text(item, "id"))) {
          return text(item, "status");
        }
      }
      if ("set_field".equals(text(op, "op"))
          && itemId.equals(text(op, "itemId"))
          && "status".equals(text(op, "field"))) {
        JsonNode value = op.get("value");
        return value == null ? null : value.asText();
      }
    }
    return null;
  }

  private static String fieldValueFromPatch(JsonNode patch, String itemId, String fieldName) {
    JsonNode body = patch.get("body");
    JsonNode ops = body == null ? null : body.get("ops");
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

  private JsonNode waitForPatchContainingText(String expected, long timeout, TimeUnit unit)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + unit.toMillis(timeout);
    while (System.currentTimeMillis() < deadline) {
      for (JsonNode frame : client.allReceived()) {
        if ("thread.patch".equals(text(frame, "type")) && containsText(frame, expected)) {
          return frame;
        }
      }
      Thread.sleep(100);
    }
    throw new AssertionError("Timed out waiting for thread.patch containing " + expected);
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

  private static String text(JsonNode node, String fieldName) {
    JsonNode value = node == null ? null : node.get(fieldName);
    return value == null ? null : value.asText();
  }

  private record SessionIds(String sessionId, String threadId) {}

  private record HistoryTurn(String submittedText, String assistantMarker) {}

  private record HistoryToolExpectation(String name, int expectedCallCount, List<String> outputs) {}

  private record FunctionCallFrame(String itemId, String callId, String name) {}

  private record RealHistorySession(
      String token,
      String callSessionId,
      String speedDialId,
      String threadId,
      String speedDialInstructionPrefix,
      List<HistoryTurn> turns,
      List<HistoryToolExpectation> toolExpectations) {
    RealHistorySession(
        String token,
        String callSessionId,
        String speedDialId,
        String threadId,
        List<HistoryTurn> turns) {
      this(
          token, callSessionId, speedDialId, threadId, SPEED_DIAL_HISTORY_PREFIX, turns, List.of());
    }

    RealHistorySession(
        String token,
        String callSessionId,
        String speedDialId,
        String threadId,
        String speedDialInstructionPrefix,
        List<HistoryTurn> turns) {
      this(
          token,
          callSessionId,
          speedDialId,
          threadId,
          speedDialInstructionPrefix,
          turns,
          List.of());
    }

    RealHistorySession(
        String token,
        String callSessionId,
        String speedDialId,
        String threadId,
        List<HistoryTurn> turns,
        List<HistoryToolExpectation> toolExpectations) {
      this(
          token,
          callSessionId,
          speedDialId,
          threadId,
          SPEED_DIAL_HISTORY_PREFIX,
          turns,
          toolExpectations);
    }
  }
}
