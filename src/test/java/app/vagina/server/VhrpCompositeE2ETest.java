package app.vagina.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.vagina.server.realtime.VhrpTestClient;
import app.vagina.server.support.HarigataOidcMockServerResource;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
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
 * class keeps only cases that do not need a downstream realtime provider. Mock-provider conversation
 * tests were removed because the real API sequence covers the user-visible path they approximated.
 */
@QuarkusTest
@QuarkusTestResource(HarigataOidcMockServerResource.class)
class VhrpCompositeE2ETest {

  @TestHTTPResource("/")
  URL testServerUrl;

  @Inject io.vertx.mutiny.core.Vertx mutinyVertx;

  private VhrpTestClient client;

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

  @Test
  void vhrpSubProtocolNegotiationSucceeds() throws Exception {
    client.connect(testPort(), "vhrp.cbor.v1");

    assertEquals(
        "vhrp.cbor.v1",
        client.negotiatedSubProtocol(),
        "Server must echo vhrp.cbor.v1 in the WebSocket handshake");
  }

  @Test
  void connectionWithoutSubProtocolIsRejected() throws Exception {
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

  @Test
  void invalidJwtSessionOpenGivesErrorAndClose() throws Exception {
    client.connect(testPort(), "vhrp.cbor.v1");
    client.sendSessionOpen("this.is.not.a.valid.jwt", "voice-agent-prod-cc");

    JsonNode error = client.waitForMessage("error", 10, TimeUnit.SECONDS);
    assertNotNull(error.get("body"), "error frame must have a body");
    String code = error.get("body").get("code").asText();
    assertTrue(
        code.contains("auth") || code.contains("invalid"),
        "Error code must indicate authentication failure, got: " + code);

    int closeCode = client.waitForClose(5, TimeUnit.SECONDS);
    assertNotEquals(-1, closeCode, "Server must close the connection after auth failure");
  }

  @Test
  void missingJwtFieldCausesAuthError() throws Exception {
    client.connect(testPort(), "vhrp.cbor.v1");
    client.send(
        "session.open",
        Map.of("modelId", "voice-agent-prod-cc"),
        Map.of("messageId", UUID.randomUUID().toString()));

    JsonNode error = client.waitForMessage("error", 10, TimeUnit.SECONDS);
    assertNotNull(error.get("body"));
    String code = error.get("body").get("code").asText();
    assertTrue(
        code.contains("auth") || code.contains("invalid") || code.contains("bad"),
        "Missing JWT must produce an auth or protocol error, got: " + code);
    client.waitForClose(5, TimeUnit.SECONDS);
  }

  @Test
  void wrongSubprotocolDoesNotCauseRecursiveErrorFlood() throws Exception {
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
}
