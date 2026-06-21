package app.vagina.server.realtime.oai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.http.WebSocketConnectOptions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the auth-header helpers in {@link OaiWebSocketTransport}.
 *
 * <p>Both helpers are package-private static methods so they can be tested without a live Vert.x
 * instance or Quarkus CDI context.
 */
class OaiWebSocketTransportHeaderTest {

  // ─── applyAuthHeaders ────────────────────────────────────────────────────

  @Test
  void applyAuthHeaders_withValidToken_addsAuthorizationAndApiKey() {
    WebSocketConnectOptions opts = new WebSocketConnectOptions();
    OaiWebSocketTransport.applyAuthHeaders(opts, "sk-test-key");

    assertEquals("Bearer sk-test-key", opts.getHeaders().get("Authorization"));
    assertEquals("sk-test-key", opts.getHeaders().get("api-key"));
  }

  @Test
  void applyAuthHeaders_trimsWhitespace() {
    WebSocketConnectOptions opts = new WebSocketConnectOptions();
    OaiWebSocketTransport.applyAuthHeaders(opts, "  sk-trimmed  ");

    assertEquals("Bearer sk-trimmed", opts.getHeaders().get("Authorization"));
    assertEquals("sk-trimmed", opts.getHeaders().get("api-key"));
  }

  @Test
  void applyAuthHeaders_withNullToken_addsNoHeaders() {
    WebSocketConnectOptions opts = new WebSocketConnectOptions();
    OaiWebSocketTransport.applyAuthHeaders(opts, null);

    // No headers should be set; getHeaders() returns null when none were added.
    var headers = opts.getHeaders();
    assertTrue(headers == null || !headers.contains("Authorization"),
        "Authorization header must not be present when token is null");
    assertTrue(headers == null || !headers.contains("api-key"),
        "api-key header must not be present when token is null");
  }

  @Test
  void applyAuthHeaders_withBlankToken_addsNoHeaders() {
    WebSocketConnectOptions opts = new WebSocketConnectOptions();
    OaiWebSocketTransport.applyAuthHeaders(opts, "   ");

    var headers = opts.getHeaders();
    assertTrue(headers == null || !headers.contains("Authorization"),
        "Authorization header must not be present when token is blank");
    assertTrue(headers == null || !headers.contains("api-key"),
        "api-key header must not be present when token is blank");
  }

  // ─── maskHeaderValue ─────────────────────────────────────────────────────

  @Test
  void maskHeaderValue_authorization_isRedacted() {
    assertEquals("[REDACTED]",
        OaiWebSocketTransport.maskHeaderValue("Authorization", "Bearer secret"));
  }

  @Test
  void maskHeaderValue_authorizationCaseInsensitive_isRedacted() {
    assertEquals("[REDACTED]",
        OaiWebSocketTransport.maskHeaderValue("AUTHORIZATION", "Bearer secret"));
  }

  @Test
  void maskHeaderValue_apiKey_isRedacted() {
    assertEquals("[REDACTED]",
        OaiWebSocketTransport.maskHeaderValue("api-key", "sk-azure-key"));
  }

  @Test
  void maskHeaderValue_apiKeyCaseInsensitive_isRedacted() {
    assertEquals("[REDACTED]",
        OaiWebSocketTransport.maskHeaderValue("Api-Key", "sk-azure-key"));
  }

  @Test
  void maskHeaderValue_locationWithApiKey_masksKeyValueButKeepsStructure() {
    String location =
        "wss://myaccount.openai.azure.com/openai/realtime"
            + "?api-version=2025-01-01-preview&api-key=sk-secret123&model=gpt-4o-realtime";
    String masked = OaiWebSocketTransport.maskHeaderValue("Location", location);

    assertTrue(masked.contains("api-key=[REDACTED]"),
        "api-key query param must be masked");
    assertTrue(masked.contains("api-version=2025-01-01-preview"),
        "api-version must remain visible");
    assertTrue(masked.contains("model=gpt-4o-realtime"),
        "model param must remain visible");
    assertTrue(!masked.contains("sk-secret123"),
        "secret key value must not appear in masked string");
  }

  @Test
  void maskHeaderValue_locationWithoutApiKey_isUnchanged() {
    String location = "wss://example.com/realtime?api-version=2025-01-01-preview";
    assertEquals(location, OaiWebSocketTransport.maskHeaderValue("Location", location));
  }

  @Test
  void maskHeaderValue_otherHeader_isUnchanged() {
    assertEquals("application/json",
        OaiWebSocketTransport.maskHeaderValue("Content-Type", "application/json"));
  }

  @Test
  void maskHeaderValue_nullHeaderName_returnsValue() {
    assertEquals("some-value",
        OaiWebSocketTransport.maskHeaderValue(null, "some-value"));
  }

  @Test
  void maskHeaderValue_nullValue_returnsNull() {
    assertNull(OaiWebSocketTransport.maskHeaderValue("Authorization", null));
  }
}
