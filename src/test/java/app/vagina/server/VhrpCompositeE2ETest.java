package app.vagina.server;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.vagina.server.realtime.VhrpTestClient;
import app.vagina.server.support.HarigataOidcMockServerResource;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.mutiny.core.Vertx;
import jakarta.inject.Inject;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * VHRP transport/authentication composite tests.
 *
 * <p>Provider conversation tests intentionally live in {@link OpenAiRealVhrpSequenceTest}; this
 * class keeps only cases that do not need a downstream realtime provider. Mock-provider
 * conversation tests were removed because the real API sequence covers the user-visible path they
 * approximated.
 */
@QuarkusTest
@QuarkusTestResource(HarigataOidcMockServerResource.class)
class VhrpCompositeE2ETest implements HarigataOidcMockServerResource.HarigataOidcMockServerAware {

  @TestHTTPResource("/")
  URL testServerUrl;

  @Inject Vertx mutinyVertx;

  private VhrpTestClient client;
  private WireMockServer existingHarigata;

  @Override
  public void setWireMockServer(WireMockServer wireMockServer) {
    this.existingHarigata = wireMockServer;
  }

  @BeforeEach
  void setUp() {
    client = new VhrpTestClient(mutinyVertx.getDelegate());
  }

  @AfterEach
  void tearDown() {
    if (client != null) {
      client.close();
    }
  }

  /**
   * Scenario: a client opens the hosted realtime WebSocket with the supported VHRP subprotocol.
   *
   * <p>The transport contract is negotiated before any application frame exists. A successful
   * handshake must echo {@code vhrp.cbor.v1}; otherwise a conforming client cannot know that binary
   * frames will be interpreted as VHRP CBOR.
   */
  @Test
  void subprotocolAccepted() throws Exception {
    client.connect(testPort(), "vhrp.cbor.v1");

    assertEquals(
        "vhrp.cbor.v1",
        client.negotiatedSubProtocol(),
        "Server must echo vhrp.cbor.v1 in the WebSocket handshake");
  }

  /**
   * Scenario: a client attempts the hosted realtime WebSocket handshake with an unsupported
   * subprotocol.
   *
   * <p>This is a transport-layer rejection, not an application-level VHRP error. The server must
   * reject or close the socket without treating the failed handshake as a normal established
   * session.
   */
  @Test
  void subprotocolRejected() throws Exception {
    try {
      client.connect(testPort(), "wrong.protocol.v0");
      int code = client.waitForClose(5, TimeUnit.SECONDS);
      assertNotEquals(1000, code, "Server must close a connection with wrong subprotocol");
    } catch (RuntimeException ex) {
      assertTrue(
          ex.getMessage() != null && !ex.getMessage().isEmpty(),
          "Connection without correct subprotocol must fail at transport level");
    }
  }

  /**
   * Scenario: the first application frame is {@code session.open}, but its JWT is malformed.
   *
   * <p>Authentication happens during bootstrap before a session is bound. The endpoint must send an
   * authentication-shaped error frame and close the socket; keeping the connection open would allow
   * unauthenticated clients to continue sending VHRP messages.
   */
  @Test
  void invalidJwt() throws Exception {
    client.connect(testPort(), "vhrp.cbor.v1");
    client.sendSessionOpen("this.is.not.a.valid.jwt", "default");

    JsonNode error = client.waitForMessage("error", 10, TimeUnit.SECONDS);
    assertNotNull(error.get("body"), "error frame must have a body");
    String code = error.get("body").get("code").asText();
    assertTrue(
        code.contains("auth") || code.contains("invalid"),
        "Error code must indicate authentication failure, got: " + code);

    int closeCode = client.waitForClose(5, TimeUnit.SECONDS);
    assertNotEquals(-1, closeCode, "Server must close the connection after auth failure");
  }

  /**
   * Scenario: {@code session.open} omits the in-band JWT field entirely.
   *
   * <p>The missing token is also a bootstrap authentication failure. It must report an
   * auth/protocol error and close before any registry session is attached to the socket.
   */
  @Test
  void missingJwt() throws Exception {
    client.connect(testPort(), "vhrp.cbor.v1");
    client.send(
        "session.open",
        Map.of("speedDialId", "default"),
        Map.of("messageId", UUID.randomUUID().toString()));

    JsonNode error = client.waitForMessage("error", 10, TimeUnit.SECONDS);
    assertNotNull(error.get("body"));
    String code = error.get("body").get("code").asText();
    assertTrue(
        code.contains("auth") || code.contains("invalid") || code.contains("bad"),
        "Missing JWT must produce an auth or protocol error, got: " + code);
    client.waitForClose(5, TimeUnit.SECONDS);
  }

  /**
   * Scenario: fresh {@code session.open} reaches the realtime adapter, but adapter setup/connect
   * fails before the session can be attached.
   *
   * <p>This is the leak-prone bootstrap boundary: the registry has already allocated a {@code
   * VhrpSession}, but the client must never receive {@code session.ready}, because that would
   * expose a resumable session id for a session whose downstream adapter is not connected. The
   * partially-created adapter must also be disposed so failed first-open does not retain provider
   * resources.
   */
  @Test
  void freshOpenConnectFailure() throws Exception {
    String token = VhrpAuthTestSupport.obtainValidJwt();
    VhrpLifecycleTestSupport.FailingConnectAdapter adapter =
        VhrpLifecycleTestSupport.installFailingConnectAdapterFactory();

    client.connect(testPort(), "vhrp.cbor.v1");
    client.sendSessionOpen(token, "default");

    assertErrorCode(client.waitForMessage("error", 10, TimeUnit.SECONDS), "protocol.bad_message");
    client.waitForClose(5, TimeUnit.SECONDS);

    assertNoFrameType(client, "session.ready");
    assertEquals(1, adapter.connectCalls.get(), "fresh open must attempt adapter connect once");
    assertEquals(1, adapter.disposeCalls.get(), "failed fresh open must dispose adapter resources");
  }

  /**
   * Scenario: client asks to resume a session id that is not retained by the registry.
   *
   * <p>The observable contract is intentionally narrow: bootstrap fails with {@code
   * resume.not_available}, the socket closes, and no {@code session.resumed} frame is emitted. That
   * keeps unknown, evicted, and unauthorized resume attempts from becoming distinguishable through
   * success-shaped frames.
   */
  @Test
  void resumeUnknown() throws Exception {
    String token = VhrpAuthTestSupport.obtainValidJwt();

    client.connect(testPort(), "vhrp.cbor.v1");
    client.sendSessionOpenResume(token, "default", "s_missing_test_session");

    assertErrorCode(client.waitForMessage("error", 10, TimeUnit.SECONDS), "resume.not_available");
    client.waitForClose(5, TimeUnit.SECONDS);
    assertNoFrameType(client, "session.resumed");
  }

  /**
   * Scenario: user A creates and detaches a resumable session; user B then tries to resume user A's
   * retained session id.
   *
   * <p>The session id is not an authority by itself. A resume must be bound to the same
   * authenticated owner that created the session, and a wrong-owner probe must look exactly like an
   * unavailable retained session: {@code resume.not_available}, close during bootstrap, and no
   * {@code session.resumed} frame.
   */
  @Test
  void resumeWrongOwner() throws Exception {
    VhrpLifecycleTestSupport.installSuccessfulAdapterFactory();
    stubExistingHarigataUser("vhrp-owner-subject", "vhrp-owner", "vhrp-owner@example.test");
    String ownerToken = VhrpAuthTestSupport.obtainValidJwt();

    client.connect(testPort(), "vhrp.cbor.v1");
    String openMsgId = client.sendSessionOpen(ownerToken, "default");
    JsonNode ready = client.waitForMessage("session.ready", 10, TimeUnit.SECONDS);
    assertEquals(openMsgId, text(ready, "replyTo"));
    String sessionId = text(ready.get("body"), "sessionId");
    assertNotNull(sessionId, "owner fresh open must expose a resumable session id");

    client.close();
    client.waitForClose(5, TimeUnit.SECONDS);
    client = new VhrpTestClient(mutinyVertx.getDelegate());

    stubExistingHarigataUser("vhrp-other-subject", "vhrp-other", "vhrp-other@example.test");
    String otherToken = VhrpAuthTestSupport.obtainValidJwt();

    client.connect(testPort(), "vhrp.cbor.v1");
    client.sendSessionOpenResume(otherToken, "default", sessionId);

    assertErrorCode(client.waitForMessage("error", 10, TimeUnit.SECONDS), "resume.not_available");
    client.waitForClose(5, TimeUnit.SECONDS);
    assertNoFrameType(client, "session.resumed");
  }

  /**
   * Scenario: repeated wrong-subprotocol handshakes are rejected at the WebSocket transport layer.
   *
   * <p>These closes are not VHRP application failures. The endpoint must not try to send
   * application {@code error} frames on already-closing sockets, because that can recursively
   * re-enter the error handler and flood logs or hang the test process.
   */
  @Test
  void wrongSubprotocolNoErrorFlood() throws Exception {
    int wrongSubprotocolRepeat = 5;
    long startMs = System.currentTimeMillis();

    for (int i = 0; i < wrongSubprotocolRepeat; i++) {
      VhrpTestClient bad = new VhrpTestClient(mutinyVertx.getDelegate());
      try {
        try {
          bad.connect(testPort(), "wrong.protocol.v0");
        } catch (RuntimeException handshakeRejected) {
          continue;
        }

        int code = bad.waitForClose(5, TimeUnit.SECONDS);
        assertNotEquals(1000, code, "Server must close wrong-subprotocol connection");

        List<JsonNode> frames = bad.allReceived();
        long errorFrames =
            frames.stream()
                .filter(
                    f -> {
                      JsonNode type = f.get("type");
                      return type != null && "error".equals(type.asText());
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
    assertTrue(
        elapsedMs < 20_000,
        "All wrong-subprotocol connections must be handled quickly (elapsed " + elapsedMs + " ms)");
  }

  private int testPort() {
    return testServerUrl.getPort();
  }

  static String text(JsonNode node, String field) {
    JsonNode value = node == null ? null : node.get(field);
    return value == null || value.isNull() ? null : value.asText();
  }

  static void assertErrorCode(JsonNode error, String expectedCode) {
    assertNotNull(error.get("body"), "error frame must have a body");
    assertEquals(expectedCode, text(error.get("body"), "code"));
  }

  static void assertNoFrameType(VhrpTestClient client, String frameType) {
    assertFalse(
        client.allReceived().stream().anyMatch(frame -> frameType.equals(text(frame, "type"))),
        "must not receive " + frameType + ": " + client.allReceived());
  }

  private void stubExistingHarigataUser(String subject, String login, String email) {
    existingHarigata.stubFor(
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
}
