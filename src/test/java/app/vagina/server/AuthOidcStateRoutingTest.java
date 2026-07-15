package app.vagina.server;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.vagina.server.support.HarigataOidcTestConstants;
import app.vagina.server.support.Util;
import io.quarkiverse.wiremock.devservice.ConnectWireMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
@ConnectWireMock
class AuthOidcStateRoutingTest {

  @Test
  void startPrefixesStateForEachClientTypeWithoutChangingRedirectConfigurationShape() {
    assertStatePrefixAndRedirect("web", "w.", "https://example.test/callback");
    assertStatePrefixAndRedirect("mobile", "m.", "https://example.test/callback");
    assertStatePrefixAndRedirect("desktop", "d.", "https://example.test/callback");
  }

  @Test
  void omittedClientTypeDefaultsToWebStatePrefix() {
    PkcePair pkce = issuePkcePair();
    String authorizationUrl =
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("codeChallenge", pkce.codeChallenge(), "codeChallengeMethod", "S256"))
            .when()
            .post("/api/auth/oidc/harigata/start")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getString("authorizationUrl");

    String state = queryParameters(URI.create(authorizationUrl)).get("state");
    assertTrue(state.startsWith("w."));
  }

  @Test
  void exchangeRejectsMissingAndUnknownStatePrefixes() {
    PkcePair pkce = issuePkcePair();

    assertRejectedState("opaque-state-without-prefix", pkce.codeVerifier());
    assertRejectedState("x.unknown-client-type", pkce.codeVerifier());
    assertRejectedState("w.", pkce.codeVerifier());
  }

  @Test
  void changingAValidStatePrefixCannotResolveThePersistedAttempt() {
    PkcePair pkce = issuePkcePair();
    String authorizationUrl = startLogin("mobile", pkce);
    String state = queryParameters(URI.create(authorizationUrl)).get("state");
    assertTrue(state.startsWith("m."));

    String tamperedState = "d." + state.substring(2);
    assertNotEquals(state, tamperedState);
    assertRejectedState(tamperedState, pkce.codeVerifier());
  }

  private void assertStatePrefixAndRedirect(
      String clientType, String expectedPrefix, String expectedRedirectUri) {
    PkcePair pkce = issuePkcePair();
    String authorizationUrl = startLogin(clientType, pkce);
    Map<String, String> query = queryParameters(URI.create(authorizationUrl));

    assertTrue(query.get("state").startsWith(expectedPrefix));
    assertEquals(expectedRedirectUri, query.get("redirect_uri"));
  }

  private String startLogin(String clientType, PkcePair pkce) {
    return given()
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "clientType",
                clientType,
                "codeChallenge",
                pkce.codeChallenge(),
                "codeChallengeMethod",
                "S256"))
        .when()
        .post("/api/auth/oidc/harigata/start")
        .then()
        .statusCode(200)
        .extract()
        .jsonPath()
        .getString("authorizationUrl");
  }

  private void assertRejectedState(String state, String codeVerifier) {
    Response response =
        given()
            .contentType(ContentType.JSON)
            .body(
                Map.of(
                    "code", HarigataOidcTestConstants.AUTHORIZATION_CODE,
                    "state", state,
                    "codeVerifier", codeVerifier))
            .when()
            .post("/api/auth/oidc/harigata/exchange");

    assertEquals(401, response.statusCode());
  }

  private static PkcePair issuePkcePair() {
    String verifier = "routing-test-verifier-" + UUID.randomUUID();
    return new PkcePair(verifier, s256(verifier));
  }

  private static String s256(String verifier) {
    byte[] hash = Util.sha256(verifier, StandardCharsets.US_ASCII);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
  }

  private static Map<String, String> queryParameters(URI uri) {
    Map<String, String> result = new LinkedHashMap<>();
    String rawQuery = uri.getRawQuery();
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
