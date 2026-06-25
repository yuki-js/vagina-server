package app.vagina.server;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.*;

import app.vagina.server.realtime.FakeRealtimeAdapter;
import app.vagina.server.realtime.FakeRealtimeAdapterFactory;
import app.vagina.server.realtime.VhrpTestClient;
import app.vagina.server.support.HarigataOidcMockServerResource;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Composite E2E test: layers 2–4 of the VHRP/1 hosted-realtime stack.
 *
 * <p>This test starts the full Quarkus server (PostgreSQL via Dev Services) and exercises the real
 * WebSocket endpoint ({@code VhrpEndpoint}), real JWT authentication ({@code AuthService}), and the
 * {@code RealtimeAdapter} dispatch path — with the production OAI provider replaced by the
 * test-only {@link FakeRealtimeAdapterFactory} via CDI {@code @Alternative @Priority(1)}.
 *
 * <h2>Layer 2 — Real WebSocket handshake</h2>
 *
 * <ul>
 *   <li>Subprotocol {@code vhrp.cbor.v1} negotiate succeeds.
 *   <li>Connection without (or with wrong) subprotocol is closed by the server.
 * </ul>
 *
 * <h2>Layer 3 — Real JWT authentication</h2>
 *
 * <ul>
 *   <li>Valid JWT (obtained via the Harigata OIDC mock flow) + {@code session.open} → {@code
 *       session.ready}.
 *   <li>Invalid JWT → {@code error(auth.invalid_jwt)} + connection closed.
 * </ul>
 *
 * <h2>Layer 4 — Conversation flow via fake provider</h2>
 *
 * <ul>
 *   <li>{@code turn.text.submit} → {@code ack} → fake adapter pushes {@code thread.patch} → client
 *       receives patch with correct content.
 *   <li>{@code thread.sync.request} → {@code thread.snapshot} (additional scenario).
 * </ul>
 *
 * <h2>No production code was changed</h2>
 *
 * <p>{@link FakeRealtimeAdapterFactory} lives entirely in {@code src/test/java} and overrides
 * {@link app.vagina.server.realtime.ConfigRealtimeAdapterFactory} via CDI
 * {@code @Alternative @Priority(1)} — the injection point in {@code VhrpSessionRegistry} is the
 * interface {@code RealtimeAdapterFactory}, so the swap is transparent to all production classes.
 */
@QuarkusTest
@QuarkusTestResource(HarigataOidcMockServerResource.class)
class VhrpCompositeE2ETest {

  /** Fake factory injected by Quarkus CDI (wins over production factory via @Priority). */
  @Inject FakeRealtimeAdapterFactory fakeFactory;

  /**
   * HTTP base URL of the running Quarkus test server. Port is random ({@code test-port=0}). We
   * extract the port from this URL and reuse it for WebSocket connections.
   */
  @TestHTTPResource("/")
  URL testServerUrl;

  /** Vert.x instance for the test WebSocket clients. */
  @Inject io.vertx.mutiny.core.Vertx mutinyVertx;

  /**
   * JWT obtained via Harigata OIDC mock flow.
   *
   * <p>Intentionally an instance field (not {@code static}). Each test method acquires a fresh
   * token in {@link #setUp()}, so the token is always minted against the same Quarkus instance and
   * Dev-Services DB that will validate it. A static cache would break whenever Quarkus is restarted
   * between test runs (new DB → old uid no longer exists) and would introduce hidden
   * order-dependency between test methods.
   */
  private String validJwt;

  private VhrpTestClient client;

  @BeforeEach
  void setUp() {
    fakeFactory.reset();
    client = new VhrpTestClient(mutinyVertx.getDelegate());
    // Mint a fresh JWT for every test so no order-dependency can form and the token is always
    // backed by a user row in the current Dev-Services DB.
    validJwt = null; // reset before each obtain call
    obtainValidJwt();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (client != null) {
      client.close();
    }
  }

  // =========================================================================
  // Layer 2: WebSocket handshake
  // =========================================================================

  /**
   * Contract: A WebSocket client that presents {@code vhrp.cbor.v1} as the subprotocol must have
   * the handshake accepted and the same subprotocol echoed back. Without this the CBOR binary
   * framing contract is unenforceable and the client cannot send any application message.
   */
  @Test
  void layer2_vhrpSubProtocolNegotiationSucceeds() throws Exception {
    // Act — connect with the correct VHRP subprotocol
    client.connect(testPort(), "vhrp.cbor.v1");

    // Assert — server echoed the subprotocol
    assertEquals(
        "vhrp.cbor.v1",
        client.negotiatedSubProtocol(),
        "Server must echo vhrp.cbor.v1 in the WebSocket handshake");
  }

  /**
   * Contract: A WebSocket client that connects without any subprotocol (or with a different one)
   * must be rejected. The server closes the connection with a 4406 application close code so the
   * client knows to update its protocol version rather than retrying silently.
   */
  @Test
  void layer2_connectionWithoutSubProtocolIsRejected() throws Exception {
    // Act — connect with a nonsense subprotocol; the server should close it
    try {
      // Vert.x will either reject the handshake outright or connect and immediately receive close.
      client.connect(testPort(), "wrong.protocol.v0");
      // If the connect succeeded (HTTP upgrade accepted with wrong subprotocol), the server's
      // @OnOpen guard must close the socket shortly after.
      int code = client.waitForClose(5, TimeUnit.SECONDS);
      // 4406 is the application-level close code VhrpEndpoint uses for bad subprotocol.
      // Accept any non-normal close (code != 1000) as "rejected".
      assertNotEquals(1000, code, "Server must close a connection with wrong subprotocol");
    } catch (RuntimeException ex) {
      // Handshake failed at HTTP level — also acceptable (the server may reject during upgrade).
      assertTrue(
          ex.getMessage() != null && !ex.getMessage().isEmpty(),
          "Connection without correct subprotocol must fail at transport level");
    }
  }

  // =========================================================================
  // Layer 3: JWT authentication
  // =========================================================================

  /**
   * Contract: A valid JWT (from the real auth flow) + correct session.open must elicit
   * session.ready. Without this, authenticated users cannot start a hosted-realtime session.
   */
  @Test
  void layer3_validJwtSessionOpenGivesSessionReady() throws Exception {
    // Arrange — obtain a real JWT from the Harigata OIDC mock flow
    String jwt = obtainValidJwt();

    // Act — connect and send session.open
    client.connect(testPort(), "vhrp.cbor.v1");
    String openMsgId = client.sendSessionOpen(jwt, "voice-agent-fake");

    // Assert — server replies with session.ready correlated to our messageId
    JsonNode ready = client.waitForMessage("session.ready", 10, TimeUnit.SECONDS);
    assertNotNull(ready.get("body"), "session.ready must have a body");
    assertNotNull(
        ready.get("body").get("sessionId"), "session.ready.body.sessionId must be present");
    assertNotNull(ready.get("body").get("threadId"), "session.ready.body.threadId must be present");
    assertEquals(
        openMsgId,
        ready.get("replyTo") != null ? ready.get("replyTo").asText() : null,
        "session.ready.replyTo must match session.open messageId");
  }

  /**
   * Contract: An invalid JWT must cause the server to respond with an error frame carrying {@code
   * auth.invalid_jwt} and then close the connection. Without this, an attacker with a malformed
   * token could trick the server into allocating a session.
   */
  @Test
  void layer3_invalidJwtSessionOpenGivesErrorAndClose() throws Exception {
    // Arrange — deliberately broken token
    String badJwt = "this.is.not.a.valid.jwt";

    // Act
    client.connect(testPort(), "vhrp.cbor.v1");
    client.sendSessionOpen(badJwt, "voice-agent-fake");

    // Assert — error frame with auth.invalid_jwt (or any auth error)
    JsonNode error = client.waitForMessage("error", 10, TimeUnit.SECONDS);
    assertNotNull(error.get("body"), "error frame must have a body");
    String code = error.get("body").get("code").asText();
    assertTrue(
        code.contains("auth") || code.contains("invalid"),
        "Error code must indicate authentication failure, got: " + code);

    // Assert — connection is closed after the error (recoverable=false → server closes)
    int closeCode = client.waitForClose(5, TimeUnit.SECONDS);
    // Server uses 4400 for bootstrap failures
    assertNotEquals(-1, closeCode, "Server must close the connection after auth failure");
  }

  /**
   * Contract: A session.open that omits the JWT field must also cause auth failure. The token field
   * being absent must not cause a NullPointerException — it must be handled gracefully.
   */
  @Test
  void layer3_missingJwtFieldCausesAuthError() throws Exception {
    // Act — send session.open with empty token
    client.connect(testPort(), "vhrp.cbor.v1");
    client.send(
        "session.open",
        Map.of("modelId", "voice-agent-fake"), // no "token" key
        Map.of("messageId", UUID.randomUUID().toString()));

    // Assert — error frame
    JsonNode error = client.waitForMessage("error", 10, TimeUnit.SECONDS);
    assertNotNull(error.get("body"));
    String code = error.get("body").get("code").asText();
    assertTrue(
        code.contains("auth") || code.contains("invalid") || code.contains("bad"),
        "Missing JWT must produce an auth or protocol error, got: " + code);
    client.waitForClose(5, TimeUnit.SECONDS);
  }

  // =========================================================================
  // Layer 4: Conversation flow via fake provider
  // =========================================================================

  /**
   * Contract: After session.ready, a turn.text.submit must yield an ack from the server AND the
   * fake provider's thread.patch (simulated assistant response) must reach the client in order.
   * Without this the core turn-taking loop is broken and the user sees no AI response.
   */
  @Test
  void layer4_turnTextSubmitProducesAckThenAssistantThreadPatch() throws Exception {
    // Arrange
    String jwt = obtainValidJwt();
    client.connect(testPort(), "vhrp.cbor.v1");
    client.sendSessionOpen(jwt, "voice-agent-fake");
    client.waitForMessage("session.ready", 10, TimeUnit.SECONDS);

    // Allow time for VhrpSession subscriptions to be wired up
    Thread.sleep(100);
    FakeRealtimeAdapter adapter = fakeFactory.lastCreated();
    assertNotNull(adapter, "FakeRealtimeAdapterFactory must have created an adapter");

    // Act — client sends a text turn
    String clientItemId = "ci_" + UUID.randomUUID();
    String msgId = client.sendTurnTextSubmit(clientItemId, "Hello from test");

    // Assert — server acks the turn
    JsonNode ack = client.waitForMessage("ack", 10, TimeUnit.SECONDS);
    assertEquals(
        msgId, ack.get("replyTo").asText(), "ack.replyTo must match turn.text.submit messageId");
    assertTrue(ack.get("body").get("accepted").asBoolean(), "ack.body.accepted must be true");
    assertEquals(
        clientItemId,
        ack.get("body").get("clientItemId").asText(),
        "ack.body.clientItemId must echo our clientItemId");

    // Assert — fake adapter received the text
    assertFalse(adapter.sentTexts().isEmpty(), "Fake adapter must have received the text");
    assertEquals("Hello from test", adapter.sentTexts().get(0));

    // Act — fake adapter emits a thread.patch (simulates assistant response)
    String assistantItemId = "ai_" + UUID.randomUUID();
    adapter.emitTextResponse(assistantItemId, "Hi there from the fake AI!");

    // Assert — client receives the thread.patch with correct ops
    JsonNode patch = client.waitForMessage("thread.patch", 10, TimeUnit.SECONDS);
    JsonNode ops = patch.get("body").get("ops");
    assertNotNull(ops, "thread.patch.body.ops must be present");
    assertTrue(ops.isArray(), "ops must be an array");
    assertTrue(ops.size() >= 2, "ops must contain at least add_item and append_text");

    // Find add_item op
    JsonNode addItemOp = findOp(ops, "add_item");
    assertNotNull(addItemOp, "thread.patch must contain add_item op");
    assertEquals(
        assistantItemId,
        addItemOp.get("item").get("id").asText(),
        "add_item.item.id must match the assistant item id");
    assertEquals("assistant", addItemOp.get("item").get("role").asText());

    // Find append_text op
    JsonNode appendTextOp = findOp(ops, "append_text");
    assertNotNull(appendTextOp, "thread.patch must contain append_text op");
    assertEquals(
        "Hi there from the fake AI!",
        appendTextOp.get("delta").asText(),
        "append_text.delta must carry the assistant response text");

    // Find set_status completed op
    JsonNode setStatusOp = findOp(ops, "set_status");
    assertNotNull(setStatusOp, "thread.patch must contain set_status op");
    assertEquals("completed", setStatusOp.get("status").asText());
  }

  /**
   * Contract: thread.sync.request must cause the server to respond with a thread.snapshot
   * containing the current canonical state of the thread. This is the only recovery mechanism when
   * a client's local projection diverges — e.g. after reconnect or a dropped patch.
   */
  @Test
  void layer4_threadSyncRequestReturnsThreadSnapshot() throws Exception {
    // Arrange — authenticate and open session
    String jwt = obtainValidJwt();
    client.connect(testPort(), "vhrp.cbor.v1");
    client.sendSessionOpen(jwt, "voice-agent-fake");
    client.waitForMessage("session.ready", 10, TimeUnit.SECONDS);
    Thread.sleep(100); // let subscriptions settle

    // Act — request a full snapshot
    client.sendThreadSyncRequest();

    // Assert — server responds with thread.snapshot
    JsonNode snapshot = client.waitForMessage("thread.snapshot", 10, TimeUnit.SECONDS);
    assertNotNull(snapshot.get("body"), "thread.snapshot must have a body");
    assertNotNull(
        snapshot.get("body").get("threadId"), "thread.snapshot.body.threadId must be present");
    assertTrue(
        snapshot.get("body").get("items").isArray(),
        "thread.snapshot.body.items must be an array (empty for a fresh session)");
  }

  // =========================================================================
  // Layer 2 (robustness): bootstrap-before-close must not cause log floods
  // =========================================================================

  /**
   * Regression: before the fix, a wrong-subprotocol connection (or any connection whose transport
   * was closed before bootstrap completed) triggered a self-recursive {@code @OnError} flood:
   *
   * <ol>
   *   <li>{@code @OnOpen} calls {@code connection.close(CLOSE_BAD_SUBPROTOCOL)}.
   *   <li>The resulting "WebSocket is closed" exception reached {@code @OnError}.
   *   <li>{@code onError} converted it to {@code protocol.bad_message} and tried to send an error
   *       frame → send failed → re-entered {@code @OnError} → infinite loop.
   * </ol>
   *
   * <p>This test connects {@value #WRONG_SUBPROTOCOL_REPEAT} times with a wrong subprotocol and
   * verifies that:
   *
   * <ul>
   *   <li>Each connection is closed by the server (no hang = no infinite loop blocking the event
   *       loop).
   *   <li>No {@code error} application frame is received (the server must not send an error frame
   *       for a transport-level close; it must just close).
   *   <li>The entire batch completes well within the generous timeout, proving no recursive storm
   *       is causing per-connection overhead.
   * </ul>
   */
  @Test
  void layer2_wrongSubprotocolDoesNotCauseRecursiveErrorFlood() throws Exception {
    int WRONG_SUBPROTOCOL_REPEAT = 5;
    long startMs = System.currentTimeMillis();

    for (int i = 0; i < WRONG_SUBPROTOCOL_REPEAT; i++) {
      VhrpTestClient bad = new VhrpTestClient(mutinyVertx.getDelegate());
      try {
        // Connect with clearly wrong subprotocol
        try {
          bad.connect(testPort(), "wrong.protocol.v0");
        } catch (RuntimeException handshakeRejected) {
          // Some Vert.x versions reject the upgrade at HTTP level — that is also acceptable.
          continue;
        }

        // Server must close the connection (via @OnOpen CLOSE_BAD_SUBPROTOCOL).
        // If the recursive loop were active this wait would time out or take many seconds.
        int code = bad.waitForClose(5, TimeUnit.SECONDS);
        assertNotEquals(1000, code, "Server must close wrong-subprotocol connection");

        // No application-level error frame should have been sent: the transport close is NOT a
        // protocol violation and the fixed onError must absorb it silently.
        List<com.fasterxml.jackson.databind.JsonNode> frames = bad.allReceived();
        long errorFrames =
            frames.stream()
                .filter(
                    f -> {
                      com.fasterxml.jackson.databind.JsonNode t = f.get("type");
                      return t != null && "error".equals(t.asText());
                    })
                .count();
        assertEquals(
            0,
            errorFrames,
            "No error application frame must be sent for a transport-close event (iteration "
                + i
                + ")");
      } finally {
        bad.close();
      }
    }

    long elapsedMs = System.currentTimeMillis() - startMs;
    // 5 connections × 5 s timeout each = 25 s theoretical max. The recursive loop used to produce
    // thousands of log lines and visible processing overhead; a clean run finishes in < 5 s total.
    assertTrue(
        elapsedMs < 20_000,
        "All "
            + WRONG_SUBPROTOCOL_REPEAT
            + " wrong-subprotocol connections must be handled quickly"
            + " (elapsed "
            + elapsedMs
            + " ms). A recursive loop would cause this to stall.");
  }

  /**
   * Contract: A second turn in the same session must also produce an ack and allow the fake adapter
   * to push multiple patches independently. Session state must survive across turns.
   */
  @Test
  void layer4_multipleTextTurnsInOneSesstion() throws Exception {
    // Arrange
    String jwt = obtainValidJwt();
    client.connect(testPort(), "vhrp.cbor.v1");
    client.sendSessionOpen(jwt, "voice-agent-fake");
    client.waitForMessage("session.ready", 10, TimeUnit.SECONDS);
    Thread.sleep(100);

    FakeRealtimeAdapter adapter = fakeFactory.lastCreated();
    assertNotNull(adapter);

    // Act — first turn
    client.sendTurnTextSubmit("ci_1", "First message");
    client.waitForMessage("ack", 10, TimeUnit.SECONDS);
    adapter.emitTextResponse("ai_1", "Response to first message");
    client.waitForMessage("thread.patch", 10, TimeUnit.SECONDS);

    // Act — second turn
    String msgId2 = client.sendTurnTextSubmit("ci_2", "Second message");
    // Wait for second ack (by replyTo matching)
    JsonNode ack2 = waitForAckReplyingTo(msgId2, 10, TimeUnit.SECONDS);
    assertEquals(msgId2, ack2.get("replyTo").asText());

    // Fake adapter pushes another patch
    adapter.emitTextResponse("ai_2", "Response to second");
    // Wait for second thread.patch (total >= 2 in received list)
    waitForNthPatch(2, 10, TimeUnit.SECONDS);

    // Verify both texts arrived at the fake adapter
    assertEquals(2, adapter.sentTexts().size(), "Adapter must have received 2 texts");
    assertEquals("First message", adapter.sentTexts().get(0));
    assertEquals("Second message", adapter.sentTexts().get(1));
  }

  // =========================================================================
  // Layer 4: Tool call round-trip (function call → tool.result.submit)
  // =========================================================================

  /**
   * Contract: After session.ready the fake adapter can emit a {@code thread.patch} carrying a
   * {@code functionCall} item (add_item + set_field × 3 + set_status). The client must receive the
   * patch, be able to read the callId/name/arguments, and then send {@code tool.result.submit}
   * (CBOR, callId as primary key). The server must route the tool result to the adapter via {@link
   * app.vagina.server.realtime.RealtimeAdapter#sendFunctionOutput}. The adapter records the call so
   * the test can verify end-to-end delivery.
   *
   * <p>After step 6 (tool result arrives at adapter), the test additionally drives the adapter to
   * emit a follow-up assistant text response (step 7) to verify the session remains operational
   * after a tool round-trip.
   */
  @Test
  void layer4_toolCallRoundTrip() throws Exception {
    // ── Arrange: open session ─────────────────────────────────────────────
    String jwt = obtainValidJwt();
    client.connect(testPort(), "vhrp.cbor.v1");
    client.sendSessionOpen(jwt, "voice-agent-fake");
    client.waitForMessage("session.ready", 10, TimeUnit.SECONDS);
    Thread.sleep(100); // let VhrpSession subscriptions settle

    FakeRealtimeAdapter adapter = fakeFactory.lastCreated();
    assertNotNull(adapter, "FakeRealtimeAdapterFactory must have created an adapter");

    // ── Step 2: client sends a text turn (triggers ack) ──────────────────
    String clientItemId = "ci_tool_" + UUID.randomUUID();
    String turnMsgId = client.sendTurnTextSubmit(clientItemId, "what is the weather?");

    JsonNode ack = client.waitForMessage("ack", 10, TimeUnit.SECONDS);
    assertEquals(turnMsgId, ack.get("replyTo").asText(), "ack.replyTo must match turn msgId");
    assertTrue(ack.get("body").get("accepted").asBoolean(), "ack.body.accepted must be true");

    // ── Step 3: fake emits a function-call thread.patch ───────────────────
    // callId is the primary key linking this function call to the tool result.
    String fcItemId = "fc_item_" + UUID.randomUUID();
    String callId = "call_" + UUID.randomUUID();
    String toolName = "get_weather";
    String toolArgs = "{\"location\":\"Tokyo\"}";
    adapter.emitFunctionCall(fcItemId, callId, toolName, toolArgs);

    // ── Step 4: client receives the thread.patch and validates it ─────────
    JsonNode patch = client.waitForMessage("thread.patch", 10, TimeUnit.SECONDS);
    JsonNode ops = patch.get("body").get("ops");
    assertNotNull(ops, "thread.patch.body.ops must be present");
    assertTrue(ops.isArray(), "ops must be an array");

    // add_item op must carry type=functionCall and the item id
    JsonNode addItemOp = findOp(ops, "add_item");
    assertNotNull(addItemOp, "thread.patch must contain add_item op");
    assertEquals(
        fcItemId,
        addItemOp.get("item").get("id").asText(),
        "add_item.item.id must match the function-call item id");
    assertEquals(
        "functionCall",
        addItemOp.get("item").get("type").asText(),
        "add_item.item.type must be functionCall");

    // set_field ops must carry callId, name, and arguments with matching values
    JsonNode callIdFieldOp = findSetFieldOp(ops, "callId");
    assertNotNull(callIdFieldOp, "thread.patch must contain set_field(callId)");
    assertEquals(callId, callIdFieldOp.get("value").asText(), "set_field callId value must match");

    JsonNode nameFieldOp = findSetFieldOp(ops, "name");
    assertNotNull(nameFieldOp, "thread.patch must contain set_field(name)");
    assertEquals(toolName, nameFieldOp.get("value").asText(), "set_field name value must match");

    JsonNode argsFieldOp = findSetFieldOp(ops, "arguments");
    assertNotNull(argsFieldOp, "thread.patch must contain set_field(arguments)");
    assertEquals(
        toolArgs, argsFieldOp.get("value").asText(), "set_field arguments value must match");

    // set_status op must indicate requires_action
    JsonNode setStatusOp = findOp(ops, "set_status");
    assertNotNull(setStatusOp, "thread.patch must contain set_status op");
    assertEquals("requires_action", setStatusOp.get("status").asText());

    // ── Step 5: client sends tool.result.submit (CBOR, callId as PK) ─────
    String resultClientItemId = "ri_" + UUID.randomUUID();
    String toolOutput = "{\"temperature\":\"22°C\",\"condition\":\"sunny\"}";
    String resultMsgId =
        client.sendToolResultSubmit(resultClientItemId, callId, toolOutput, "success");

    // Server must ack the tool result submission
    JsonNode resultAck = waitForAckReplyingTo(resultMsgId, 10, TimeUnit.SECONDS);
    assertEquals(
        resultMsgId,
        resultAck.get("replyTo").asText(),
        "ack.replyTo must match tool.result.submit messageId");
    assertTrue(
        resultAck.get("body").get("accepted").asBoolean(),
        "tool.result.submit ack.body.accepted must be true");

    // ── Step 6: verify tool result reached the fake adapter ──────────────
    // Poll briefly so the async Uni completes and sendFunctionOutput is called.
    long deadline = System.currentTimeMillis() + 5_000;
    while (adapter.sentToolResults().isEmpty() && System.currentTimeMillis() < deadline) {
      Thread.sleep(50);
    }
    assertFalse(
        adapter.sentToolResults().isEmpty(),
        "sendFunctionOutput must have been called on the adapter (tool result not delivered)");

    FakeRealtimeAdapter.ToolResult delivered = adapter.sentToolResults().get(0);
    assertEquals(
        callId,
        delivered.callId(),
        "Delivered tool result callId must match the function-call callId (primary key)");
    assertEquals(toolOutput, delivered.output(), "Delivered tool output must match");
    assertNotNull(delivered.disposition(), "Delivered disposition must not be null");

    // ── Step 7: adapter emits follow-up assistant response ────────────────
    // This verifies the session remains operational after the tool round-trip.
    String followUpItemId = "ai_followup_" + UUID.randomUUID();
    adapter.emitTextResponse(followUpItemId, "The weather in Tokyo is 22°C and sunny.");

    JsonNode followUpPatch = waitForNthPatch(2, 10, TimeUnit.SECONDS); // second patch in session
    assertNotNull(followUpPatch, "A second thread.patch must arrive after the tool result");
  }

  // =========================================================================
  // Helpers
  // =========================================================================

  private int testPort() {
    return testServerUrl.getPort();
  }

  /** Finds the first op in the {@code ops} array that has the given {@code op} field value. */
  private static JsonNode findOp(JsonNode ops, String opName) {
    for (JsonNode op : ops) {
      JsonNode opField = op.get("op");
      if (opField != null && opName.equals(opField.asText())) {
        return op;
      }
    }
    return null;
  }

  /**
   * Finds the first {@code set_field} op in {@code ops} whose {@code field} value equals {@code
   * fieldName}.
   */
  private static JsonNode findSetFieldOp(JsonNode ops, String fieldName) {
    for (JsonNode op : ops) {
      JsonNode opField = op.get("op");
      JsonNode field = op.get("field");
      if (opField != null
          && "set_field".equals(opField.asText())
          && field != null
          && fieldName.equals(field.asText())) {
        return op;
      }
    }
    return null;
  }

  /** Waits for an ack whose replyTo matches {@code msgId}. */
  private JsonNode waitForAckReplyingTo(String msgId, long timeout, TimeUnit unit)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + unit.toMillis(timeout);
    while (true) {
      for (JsonNode node : client.allReceived()) {
        JsonNode t = node.get("type");
        JsonNode r = node.get("replyTo");
        if (t != null && "ack".equals(t.asText()) && r != null && msgId.equals(r.asText())) {
          return node;
        }
      }
      long remaining = deadline - System.currentTimeMillis();
      if (remaining <= 0) {
        throw new AssertionError("Timed out waiting for ack replyTo=" + msgId);
      }
      Thread.sleep(Math.min(remaining, 50));
    }
  }

  /**
   * Waits until at least {@code n} thread.patch frames have been received and returns the n-th one.
   */
  private JsonNode waitForNthPatch(int n, long timeout, TimeUnit unit) throws InterruptedException {
    long deadline = System.currentTimeMillis() + unit.toMillis(timeout);
    while (true) {
      List<JsonNode> patches =
          client.allReceived().stream()
              .filter(
                  f -> {
                    JsonNode t = f.get("type");
                    return t != null && "thread.patch".equals(t.asText());
                  })
              .toList();
      if (patches.size() >= n) {
        return patches.get(n - 1);
      }
      long remaining = deadline - System.currentTimeMillis();
      if (remaining <= 0) {
        throw new AssertionError(
            "Timed out waiting for " + n + " thread.patch frames; only got " + patches.size());
      }
      Thread.sleep(Math.min(remaining, 50));
    }
  }

  // =========================================================================
  // JWT mint via Harigata OIDC mock (same flow as AuthenticationIntegrationTest)
  // =========================================================================

  /**
   * Runs the Harigata OIDC mock authorization code flow against the running Quarkus server and
   * returns a real, DB-backed access JWT. This is the same flow used by {@code
   * AuthenticationIntegrationTest} and produces a token that {@code AuthService} will accept
   * because the corresponding user record exists in the Dev Services PostgreSQL database.
   *
   * <p>The static cache has been removed: {@link #setUp()} calls this before each test so each test
   * method gets a freshly minted token that is guaranteed to match the current DB state.
   */
  private String obtainValidJwt() {
    if (validJwt != null) {
      return validJwt; // already set for this test instance (idempotent within one test)
    }
    PkcePair pkce = issuePkcePair();

    // Start OIDC flow
    Response startResp =
        given()
            .contentType(ContentType.JSON)
            .body(
                Map.of(
                    "clientType", "web",
                    "codeChallenge", pkce.codeChallenge(),
                    "codeChallengeMethod", "S256"))
            .when()
            .post("/api/auth/oidc/harigata/start")
            .then()
            .statusCode(200)
            .body("authorizationUrl", notNullValue())
            .extract()
            .response();

    String authorizationUrl = startResp.jsonPath().getString("authorizationUrl");
    String state = parseQueryParams(URI.create(authorizationUrl).getRawQuery()).get("state");

    // Fake authorize (mock server redirects back with code)
    Response authorizeResp =
        given()
            .redirects()
            .follow(false)
            .when()
            .get(authorizationUrl)
            .then()
            .statusCode(302)
            .extract()
            .response();

    String location =
        URLDecoder.decode(authorizeResp.getHeader("Location"), StandardCharsets.UTF_8);
    Map<String, String> locationParams = parseQueryParams(URI.create(location).getRawQuery());
    String code = locationParams.get("code");

    // Exchange code for tokens
    Response exchangeResp =
        given()
            .contentType(ContentType.JSON)
            .body(
                Map.of(
                    "code", code,
                    "state", state,
                    "codeVerifier", pkce.codeVerifier()))
            .when()
            .post("/api/auth/oidc/harigata/exchange")
            .then()
            .statusCode(200)
            .body("accessToken", notNullValue())
            .extract()
            .response();

    validJwt = exchangeResp.jsonPath().getString("accessToken");
    assertNotNull(validJwt, "Access token must not be null after OIDC exchange");
    return validJwt;
  }

  // =========================================================================
  // PKCE helpers (copied from AuthenticationIntegrationTest to stay self-contained)
  // =========================================================================

  private PkcePair issuePkcePair() {
    String verifier = "test-verifier-" + UUID.randomUUID();
    return new PkcePair(verifier, s256(verifier));
  }

  private String s256(String verifier) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(verifier.getBytes(StandardCharsets.US_ASCII));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  private Map<String, String> parseQueryParams(String rawQuery) {
    Map<String, String> result = new LinkedHashMap<>();
    if (rawQuery == null || rawQuery.isBlank()) {
      return result;
    }
    for (String pair : rawQuery.split("&")) {
      String[] parts = pair.split("=", 2);
      String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
      String value = parts.length > 1 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
      result.put(key, value);
    }
    return result;
  }

  private record PkcePair(String codeVerifier, String codeChallenge) {}
}
