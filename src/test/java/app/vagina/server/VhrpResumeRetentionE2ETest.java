package app.vagina.server;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import app.vagina.server.realtime.VhrpTestClient;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkiverse.wiremock.devservice.ConnectWireMock;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.vertx.core.Vertx;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** VHRP resume-retention abnormal-path tests that need a short retention profile. */
@QuarkusTest
@ConnectWireMock
@TestProfile(VhrpResumeRetentionE2ETest.ShortRetentionProfile.class)
class VhrpResumeRetentionE2ETest {

  @TestHTTPResource("/")
  URL testServerUrl;

  private Vertx vertx;
  private VhrpTestClient client;

  @BeforeEach
  void setUp() {
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
   * Scenario: a valid owner drops the socket and waits beyond the configured retention window
   * before trying to resume.
   *
   * <p>The profile shortens retention so the test proves the expiry path without sleeping for the
   * production default. After expiry, the registry must treat the id as unavailable, close during
   * bootstrap, and avoid emitting {@code session.resumed}; otherwise a stale client could resurrect
   * a terminally-closed session.
   */
  @Test
  void resumeExpired() throws Exception {
    String token = VhrpAuthTestSupport.obtainValidJwt();

    client.connect(testPort(), "vhrp.cbor.v1");
    client.sendSessionOpen(token, "default");
    JsonNode ready = client.waitForMessage("session.ready", 10, TimeUnit.SECONDS);
    String sessionId = VhrpCompositeE2ETest.text(ready.get("body"), "sessionId");
    assertNotNull(sessionId, "fresh open must expose a resumable session id");

    client.close();
    client.waitForClose(5, TimeUnit.SECONDS);
    Thread.sleep(300);
    client = new VhrpTestClient(vertx);

    client.connect(testPort(), "vhrp.cbor.v1");
    client.sendSessionOpenResume(token, "default", sessionId);

    VhrpCompositeE2ETest.assertErrorCode(
        client.waitForMessage("error", 10, TimeUnit.SECONDS), "resume.not_available");
    client.waitForClose(5, TimeUnit.SECONDS);
    VhrpCompositeE2ETest.assertNoFrameType(client, "session.resumed");
  }

  private int testPort() {
    return testServerUrl.getPort();
  }

  public static final class ShortRetentionProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("vagina.realtime.resume-retention", "PT0.2S");
    }
  }
}
