package app.vagina.server;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import app.vagina.server.realtime.VhrpTestClient;
import app.vagina.server.support.HarigataOidcMockServerResource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.vertx.core.Vertx;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
 * Opt-in real OpenAI sequence tests for the hosted Text Agent REST user path.
 *
 * <p>These scenarios deliberately keep the Text Agent query path end-to-end through the REST API:
 * Harigata OIDC, DB-backed JWT, hosted VHRP session ownership, Text Agent persistence, and real
 * OpenAI Chat Completions execution through the configured {@code text-agent-prod} provider. The
 * VHRP realtime adapter is mocked only to keep this class focused on Text Agent behavior and avoid
 * a second provider credential requirement unrelated to {@code /api/text-agents/{id}/query}.
 *
 * <p>Tool-call coverage is not included here yet. The current Text Agent REST product path persists
 * {@code enabledTools}, but the OpenAI Text Agent adapters do not send a client tool catalog in
 * provider requests. Without an exposed REST-path tool catalog, asserting {@code requires_tool}
 * from real OpenAI would test prompt luck instead of a stable product contract.
 */
@QuarkusTest
@QuarkusTestResource(HarigataOidcMockServerResource.class)
class OpenAiRealTextAgentSequenceTest {

  private static final String MODEL_ID = "text-agent-prod";
  private static final String REQUIRED_API_KEY_PROPERTY =
      "vagina.text-agent.models.text-agent-prod.api-key";
  private static final String REQUIRED_BASE_URL_PROPERTY =
      "vagina.text-agent.models.text-agent-prod.base-url";
  private static final String OPT_IN_PROPERTY = "vagina.test.openai.real.enabled";
  private static final String TRANSCRIPT_DIR_PROPERTY = "vagina.test.openai.real.transcript.dir";
  private static final String DEFAULT_AGENT_PROMPT =
      "You are a deterministic Text Agent integration test assistant. "
          + "Follow exact-output instructions. Maintain memory only within the current active VHRP "
          + "session and this Text Agent.";
  private static final ObjectMapper JSON = new ObjectMapper();

  @TestHTTPResource("/")
  URL testServerUrl;

  private Vertx vertx;
  private VhrpTestClient client;

  @BeforeEach
  void setUp() {
    assumeTrue(realOpenAiTestEnabled(), "set -Dvagina.test.openai.real.enabled=true to run");
    assumeTrue(hasRequiredOpenAiConfig(), "Text Agent real OpenAI provider config must be set");
    VhrpLifecycleTestSupport.installSuccessfulAdapterFactory();
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
   * textAgentSeq01: meeting note follow-up uses session-scoped Text Agent memory.
   *
   * <ol>
   *   <li>User starts authentication.
   *   <li>User authenticates with Harigata and proxies the redirection back to the app.
   *   <li>Server creates or finds the user entity and issues a JWT.
   *   <li>User starts a hosted VHRP session and records the server-issued session id.
   *   <li>User creates a meeting-notes Text Agent backed by the real OpenAI model registry entry.
   *   <li>User pastes meeting notes that include a launch checklist owner and due date.
   *   <li>Real OpenAI acknowledges the notes through the REST query path.
   *   <li>User asks a natural follow-up without restating the owner or due date.
   *   <li>Real OpenAI answers from the same session's Text Agent memory.
   *   <li>User starts a second VHRP session and asks for the same meeting detail without pasting
   *       the notes.
   *   <li>Real OpenAI follows the no-prior-note instruction and does not leak the first session's
   *       meeting details.
   *   <li>User explicitly ends the hosted sessions.
   * </ol>
   */
  @Test
  void textAgentSeq01() throws Exception {
    String jwt = VhrpAuthTestSupport.obtainValidJwt();
    SessionIds firstSession = openAuthenticatedSession(jwt);
    String textAgentId =
        createRealOpenAiTextAgent(
            jwt,
            DEFAULT_AGENT_PROMPT
                + " You help a product manager keep meeting notes. For follow-up questions, use "
                + "only details the user already provided in this Text Agent conversation.");
    String meetingNotesPrompt =
        "Meeting notes: Project Kestrel launch review. Mina owns the launch checklist. "
            + "The checklist is due Friday. Omar will prepare the rollout dashboard. "
            + "Acknowledge with exactly TEXT_AGENT_MEETING_NOTES_STORED_OK and no other text.";
    String followUpPrompt =
        "Who owns the launch checklist, and when is it due? Reply with exactly "
            + "OWNER=<owner>;DUE=<due date> and no other text.";
    String isolatedPrompt =
        "Who owns the launch checklist from the meeting notes? If I have not pasted meeting notes "
            + "in this session, reply exactly TEXT_AGENT_MEETING_NO_PRIOR_MEMO_OK and no other text.";

    Response firstTurn =
        queryTextAgent(jwt, textAgentId, firstSession.sessionId(), requestId(), meetingNotesPrompt);
    assertCompletedWithMarker(firstTurn, "TEXT_AGENT_MEETING_NOTES_STORED_OK");

    Response followUp =
        queryTextAgent(jwt, textAgentId, firstSession.sessionId(), requestId(), followUpPrompt);
    assertCompletedWithMarkers(followUp, "OWNER=Mina", "DUE=Friday");

    closeSession();
    client = new VhrpTestClient(vertx);
    SessionIds secondSession = openAuthenticatedSession(jwt);

    Response isolatedSession =
        queryTextAgent(jwt, textAgentId, secondSession.sessionId(), requestId(), isolatedPrompt);
    assertCompletedWithMarker(isolatedSession, "TEXT_AGENT_MEETING_NO_PRIOR_MEMO_OK");
    assertResponseTextDoesNotContain(isolatedSession, "Mina");
    assertResponseTextDoesNotContain(isolatedSession, "Friday");

    writeTranscriptArtifact(
        "textAgentSeq01",
        List.of(
            artifactEntry(
                "meeting-notes-stored",
                firstSession,
                textAgentId,
                meetingNotesPrompt,
                firstTurn,
                "TEXT_AGENT_MEETING_NOTES_STORED_OK"),
            artifactEntry(
                "same-session-meeting-recall",
                firstSession,
                textAgentId,
                followUpPrompt,
                followUp,
                "OWNER=Mina;DUE=Friday"),
            artifactEntry(
                "new-session-meeting-isolation",
                secondSession,
                textAgentId,
                isolatedPrompt,
                isolatedSession,
                "TEXT_AGENT_MEETING_NO_PRIOR_MEMO_OK")));
    closeSession();
  }

  /**
   * textAgentSeq02: same VHRP session keeps different Text Agents' workspaces separate.
   *
   * <ol>
   *   <li>User starts authentication and receives an app JWT.
   *   <li>User starts one hosted VHRP session.
   *   <li>User creates two distinct Text Agents: a meeting analyst and a risk reviewer.
   *   <li>User gives the meeting analyst a budget-owner note.
   *   <li>User asks the risk reviewer for a budget owner without giving it that note.
   *   <li>Real OpenAI answers through the risk reviewer without leaking the meeting analyst's
   *       owner.
   *   <li>User returns to the meeting analyst and asks a follow-up without restating the owner.
   *   <li>Real OpenAI recalls the owner from the meeting analyst's own state, proving state is
   *       keyed by Text Agent id inside the same VHRP session.
   *   <li>User explicitly ends the hosted session.
   * </ol>
   */
  @Test
  void textAgentSeq02() throws Exception {
    String jwt = VhrpAuthTestSupport.obtainValidJwt();
    SessionIds session = openAuthenticatedSession(jwt);
    String meetingAnalyst =
        createRealOpenAiTextAgent(
            jwt,
            DEFAULT_AGENT_PROMPT
                + " You are the user's meeting analyst. Use only notes given to this Text Agent.");
    String riskReviewer =
        createRealOpenAiTextAgent(
            jwt,
            DEFAULT_AGENT_PROMPT
                + " You are the user's release risk reviewer. Use only notes given to this Text "
                + "Agent, not other assistants.");
    String meetingAnalystPrompt =
        "Meeting note for the analyst: Riku owns the release budget review. "
            + "The vendor-latency risk remains open. Acknowledge with exactly "
            + "TEXT_AGENT_MEETING_ANALYST_STORED_OK and no other text.";
    String riskReviewerPrompt =
        "Based only on notes I gave this risk-review Text Agent, who owns the release budget "
            + "review? If I have not given this Text Agent that owner, reply exactly "
            + "TEXT_AGENT_RISK_REVIEWER_NO_BUDGET_OWNER_OK and no other text.";
    String analystRecallPrompt =
        "Who owns the release budget review from the meeting note? Reply with exactly "
            + "BUDGET_OWNER=<owner> and no other text.";

    Response analystFirst =
        queryTextAgent(jwt, meetingAnalyst, session.sessionId(), requestId(), meetingAnalystPrompt);
    assertCompletedWithMarker(analystFirst, "TEXT_AGENT_MEETING_ANALYST_STORED_OK");

    Response reviewerIsolation =
        queryTextAgent(jwt, riskReviewer, session.sessionId(), requestId(), riskReviewerPrompt);
    assertCompletedWithMarker(reviewerIsolation, "TEXT_AGENT_RISK_REVIEWER_NO_BUDGET_OWNER_OK");
    assertResponseTextDoesNotContain(reviewerIsolation, "Riku");

    Response analystRecall =
        queryTextAgent(jwt, meetingAnalyst, session.sessionId(), requestId(), analystRecallPrompt);
    assertCompletedWithMarker(analystRecall, "BUDGET_OWNER=Riku");

    writeTranscriptArtifact(
        "textAgentSeq02",
        List.of(
            artifactEntry(
                "meeting-analyst-note-stored",
                session,
                meetingAnalyst,
                meetingAnalystPrompt,
                analystFirst,
                "TEXT_AGENT_MEETING_ANALYST_STORED_OK"),
            artifactEntry(
                "risk-reviewer-isolation",
                session,
                riskReviewer,
                riskReviewerPrompt,
                reviewerIsolation,
                "TEXT_AGENT_RISK_REVIEWER_NO_BUDGET_OWNER_OK"),
            artifactEntry(
                "meeting-analyst-recall",
                session,
                meetingAnalyst,
                analystRecallPrompt,
                analystRecall,
                "BUDGET_OWNER=Riku")));
    closeSession();
  }

  /**
   * textAgentSeq03: updating a persisted Text Agent changes a real support-triage workflow.
   *
   * <ol>
   *   <li>User starts authentication and receives an app JWT.
   *   <li>User creates a support-triage Text Agent whose prompt uses the v1 classification policy.
   *   <li>User starts a hosted VHRP session and submits a duplicate-invoice refund case.
   *   <li>Real OpenAI applies the v1 persisted policy through the Text Agent query path.
   *   <li>User ends the first hosted session.
   *   <li>User updates the same Text Agent through REST to the v2 triage policy.
   *   <li>User starts a fresh hosted VHRP session and submits the same kind of support case.
   *   <li>Real OpenAI applies the updated v2 policy and does not return stale v1 behavior.
   *   <li>User explicitly ends the hosted session.
   * </ol>
   */
  @Test
  void textAgentSeq03() throws Exception {
    String jwt = VhrpAuthTestSupport.obtainValidJwt();
    String textAgentId =
        createRealOpenAiTextAgent(
            jwt,
            "You are a support triage Text Agent. For duplicate-invoice refund cases, reply in "
                + "one line containing TRIAGE_V1 and category=billing_refund.");
    String supportCasePrompt =
        "A customer says their invoice was charged twice and asks for a refund. Classify this "
            + "support case according to your current triage policy.";

    SessionIds firstSession = openAuthenticatedSession(jwt);
    Response firstPolicy =
        queryTextAgent(jwt, textAgentId, firstSession.sessionId(), requestId(), supportCasePrompt);
    assertCompletedWithMarkers(firstPolicy, "TRIAGE_V1", "category=billing_refund");
    closeSession();

    updateRealOpenAiTextAgent(
        jwt,
        textAgentId,
        "You are an updated support triage Text Agent. For duplicate-invoice refund cases, reply "
            + "in one line containing TRIAGE_V2, category=billing_refund, and escalation=finance.");
    client = new VhrpTestClient(vertx);
    SessionIds secondSession = openAuthenticatedSession(jwt);
    Response secondPolicy =
        queryTextAgent(jwt, textAgentId, secondSession.sessionId(), requestId(), supportCasePrompt);
    assertCompletedWithMarkers(
        secondPolicy, "TRIAGE_V2", "category=billing_refund", "escalation=finance");
    assertResponseTextDoesNotContain(secondPolicy, "TRIAGE_V1");

    writeTranscriptArtifact(
        "textAgentSeq03",
        List.of(
            artifactEntry(
                "support-triage-v1",
                firstSession,
                textAgentId,
                supportCasePrompt,
                firstPolicy,
                "TRIAGE_V1 category=billing_refund"),
            artifactEntry(
                "support-triage-v2-after-update",
                secondSession,
                textAgentId,
                supportCasePrompt,
                secondPolicy,
                "TRIAGE_V2 category=billing_refund escalation=finance")));
    closeSession();
  }

  private SessionIds openAuthenticatedSession(String jwt) throws Exception {
    client.connect(testPort(), "vhrp.cbor.v1");
    String openMsgId = client.sendSessionOpen(jwt, "default");

    JsonNode ready = client.waitForMessage("session.ready", 20, TimeUnit.SECONDS);
    assertTrue(
        ready.get("replyTo") != null && openMsgId.equals(ready.get("replyTo").asText()),
        "session.ready must reply to session.open");
    assertNotNull(ready.get("body").get("sessionId"), "session.ready must expose sessionId");
    assertNotNull(ready.get("body").get("threadId"), "session.ready must expose threadId");
    return new SessionIds(
        ready.get("body").get("sessionId").asText(), ready.get("body").get("threadId").asText());
  }

  private String createRealOpenAiTextAgent(String token, String prompt) {
    Response response =
        given()
            .auth()
            .oauth2(token)
            .contentType(ContentType.JSON)
            .accept(ContentType.JSON)
            .body(textAgentWriteBody("Real OpenAI Text Agent Sequence", prompt))
            .when()
            .post("/api/text-agents")
            .then()
            .statusCode(201)
            .body("id", matchesPattern("ta_[0-9a-f]{32}"))
            .body("textModelId", equalTo(MODEL_ID))
            .extract()
            .response();
    return response.jsonPath().getString("id");
  }

  private void updateRealOpenAiTextAgent(String token, String textAgentId, String prompt) {
    given()
        .auth()
        .oauth2(token)
        .contentType(ContentType.JSON)
        .accept(ContentType.JSON)
        .body(textAgentWriteBody("Updated Real OpenAI Text Agent Sequence", prompt))
        .when()
        .put("/api/text-agents/{textAgentId}", textAgentId)
        .then()
        .statusCode(200)
        .body("id", equalTo(textAgentId))
        .body("textModelId", equalTo(MODEL_ID));
  }

  private static Map<String, Object> textAgentWriteBody(String name, String prompt) {
    return Map.of(
        "name",
        name,
        "prompt",
        prompt,
        "description",
        "Opt-in real OpenAI Text Agent sequence test fixture.",
        "textModelId",
        MODEL_ID,
        "enabledTools",
        Map.of());
  }

  private Response queryTextAgent(
      String token, String textAgentId, String voiceSessionId, String requestId, String prompt) {
    return given()
        .auth()
        .oauth2(token)
        .contentType(ContentType.JSON)
        .accept(ContentType.JSON)
        .body(
            Map.of(
                "voiceSessionId", voiceSessionId,
                "requestId", requestId,
                "prompt", prompt,
                "toolSchemas", List.of()))
        .when()
        .post("/api/text-agents/{textAgentId}/query", textAgentId)
        .then()
        .statusCode(200)
        .extract()
        .response();
  }

  private static void assertCompletedWithMarker(Response response, String marker) {
    assertCompletedWithMarkers(response, marker);
  }

  private static void assertCompletedWithMarkers(Response response, String... markers) {
    response.then().body("status", equalTo("completed"));
    String text = response.jsonPath().getString("text");
    assertNotNull(text, "completed Text Agent response must include text");
    for (String marker : markers) {
      assertTrue(text.contains(marker), "completed Text Agent response must contain " + marker);
    }
  }

  private static void assertResponseTextDoesNotContain(Response response, String forbidden) {
    String text = response.jsonPath().getString("text");
    assertNotNull(text, "completed Text Agent response must include text");
    assertTrue(
        !text.contains(forbidden), "Text Agent response must not expose marker " + forbidden);
  }

  private void closeSession() throws InterruptedException {
    client.send("session.end", Map.of());
    assertTrue(
        client.waitForClose(10, TimeUnit.SECONDS) != -1, "session.end must close the socket");
  }

  private int testPort() {
    return testServerUrl.getPort();
  }

  private static String requestId() {
    return "req_" + UUID.randomUUID();
  }

  private static boolean realOpenAiTestEnabled() {
    return ConfigProvider.getConfig()
        .getOptionalValue(OPT_IN_PROPERTY, String.class)
        .map(Boolean::parseBoolean)
        .orElse(false);
  }

  private static boolean hasRequiredOpenAiConfig() {
    return hasNonBlankConfig(REQUIRED_API_KEY_PROPERTY)
        && hasNonBlankConfig(REQUIRED_BASE_URL_PROPERTY);
  }

  private static boolean hasNonBlankConfig(String propertyName) {
    return ConfigProvider.getConfig()
        .getOptionalValue(propertyName, String.class)
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

  private static Map<String, Object> artifactEntry(
      String step,
      SessionIds session,
      String textAgentId,
      String requestPrompt,
      Response response,
      String expectedMarker) {
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("step", step);
    entry.put("voiceSessionId", session.sessionId());
    entry.put("threadId", session.threadId());
    entry.put("textAgentId", textAgentId);
    entry.put("requestPrompt", requestPrompt);
    entry.put("expectedMarker", expectedMarker);
    entry.put("status", response.jsonPath().getString("status"));
    entry.put("responseText", response.jsonPath().getString("text"));
    entry.put("toolCalls", response.jsonPath().getList("toolCalls"));
    entry.put("error", response.jsonPath().getMap("error"));
    return entry;
  }

  private static void writeTranscriptArtifact(
      String sequenceName, List<Map<String, Object>> entries) throws IOException {
    Optional<Path> maybeDir = transcriptDir();
    if (maybeDir.isEmpty()) {
      return;
    }

    Path dir = maybeDir.get();
    Files.createDirectories(dir);
    Files.writeString(
        dir.resolve(sequenceName + ".text-agent.json"),
        JSON.writerWithDefaultPrettyPrinter().writeValueAsString(entries) + System.lineSeparator(),
        StandardCharsets.UTF_8);
    Files.writeString(
        dir.resolve(sequenceName + ".text-agent.md"),
        renderTranscriptMarkdown(sequenceName, entries),
        StandardCharsets.UTF_8);
  }

  private static String renderTranscriptMarkdown(
      String sequenceName, List<Map<String, Object>> entries) {
    StringBuilder markdown = new StringBuilder();
    markdown
        .append("# ")
        .append(sequenceName)
        .append(" Text Agent transcript")
        .append(System.lineSeparator());
    markdown.append(System.lineSeparator());
    int index = 1;
    for (Map<String, Object> entry : entries) {
      markdown
          .append("## ")
          .append(index++)
          .append(". ")
          .append(entry.get("step"))
          .append(System.lineSeparator())
          .append(System.lineSeparator());
      appendMarkdownField(markdown, "voiceSessionId", entry.get("voiceSessionId"));
      appendMarkdownField(markdown, "threadId", entry.get("threadId"));
      appendMarkdownField(markdown, "textAgentId", entry.get("textAgentId"));
      appendMarkdownField(markdown, "expectedMarker", entry.get("expectedMarker"));
      appendMarkdownField(markdown, "status", entry.get("status"));
      markdown.append(System.lineSeparator());
      markdown.append("### Request").append(System.lineSeparator()).append(System.lineSeparator());
      markdown
          .append(entry.get("requestPrompt"))
          .append(System.lineSeparator())
          .append(System.lineSeparator());
      markdown.append("### Response").append(System.lineSeparator()).append(System.lineSeparator());
      markdown
          .append(entry.get("responseText"))
          .append(System.lineSeparator())
          .append(System.lineSeparator());
    }
    return markdown.toString();
  }

  private static void appendMarkdownField(StringBuilder markdown, String key, Object value) {
    if (value != null) {
      markdown
          .append("- ")
          .append(key)
          .append(": `")
          .append(value)
          .append('`')
          .append(System.lineSeparator());
    }
  }

  private record SessionIds(String sessionId, String threadId) {}
}
