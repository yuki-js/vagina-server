package app.vagina.server;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import app.vagina.server.support.Util;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

final class VhrpAuthTestSupport {

  private VhrpAuthTestSupport() {}

  static String obtainValidJwt() {
    return obtainValidAuthSession().jsonPath().getString("accessToken");
  }

  static Response obtainValidAuthSession() {
    PkcePair pkce = issuePkcePair();

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

    String accessToken = exchangeResp.jsonPath().getString("accessToken");
    assertNotNull(accessToken, "Access token must not be null after OIDC exchange");
    return exchangeResp;
  }

  private static PkcePair issuePkcePair() {
    String verifier = "test-verifier-" + UUID.randomUUID();
    return new PkcePair(verifier, s256(verifier));
  }

  private static String s256(String verifier) {
    byte[] hash = Util.sha256(verifier, StandardCharsets.US_ASCII);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
  }

  private static Map<String, String> parseQueryParams(String rawQuery) {
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
