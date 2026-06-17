package app.vagina.server;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.vagina.server.support.HarigataOidcMockServerResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

@QuarkusTest
@QuarkusTestResource(HarigataOidcMockServerResource.class)
@TestMethodOrder(OrderAnnotation.class)
public class AuthenticationIntegrationTest {

  private static final String REDIRECT_URI = "https://example.com/callback";

  private static String accessToken;
  private static String refreshToken;
  private static String rotatedAccessToken;
  private static String rotatedRefreshToken;
  private static String userId;

  @Test
  @Order(1)
  public void testHarigataOidcAuthorizationCodeFlow() {
    PkcePair pkce = issuePkcePair();
    Response startResponse =
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

    String authorizationUrl = startResponse.jsonPath().getString("authorizationUrl");
    String initialState = parseQueryParams(URI.create(authorizationUrl).getRawQuery()).get("state");
    assertTrue(initialState != null && !initialState.isBlank(), "Authorization URL should include state");

    Response authorizeResponse =
        given()
            .redirects()
            .follow(false)
            .when()
            .get(authorizationUrl)
            .then()
            .statusCode(302)
            .extract()
            .response();

    RedirectPayload redirectPayload = parseRedirectPayload(authorizeResponse.getHeader("Location"));

    assertTrue(
        redirectPayload.location().startsWith(REDIRECT_URI),
        "Redirect should target provided redirect URI");
    assertTrue(
        HarigataOidcMockServerResource.DEFAULT_AUTHORIZATION_CODE.equals(redirectPayload.code()),
        "Authorization code should come from Harigata redirect");
    assertEquals(
        initialState,
        redirectPayload.state(),
        "Redirected state should match start response state");

    Response exchangeResponse =
        given()
            .contentType(ContentType.JSON)
            .body(
                Map.of(
                    "code",
                    redirectPayload.code(),
                    "state",
                    redirectPayload.state(),
                    "codeVerifier",
                    pkce.codeVerifier()))
            .when()
            .post("/api/auth/oidc/harigata/exchange")
            .then()
            .statusCode(200)
            .body("accessToken", notNullValue())
            .body("refreshToken", notNullValue())
            .body("tokenType", equalTo("Bearer"))
            .body("user.id", notNullValue())
            .body("user.displayName", equalTo(HarigataOidcMockServerResource.DEFAULT_DISPLAY_NAME))
            .extract()
            .response();

    accessToken = exchangeResponse.jsonPath().getString("accessToken");
    refreshToken = exchangeResponse.jsonPath().getString("refreshToken");
    userId = exchangeResponse.jsonPath().getString("user.id");
  }

  @Test
  @Order(2)
  public void testConcurrentHarigataFirstLoginConvergesOnSameUser()
      throws ExecutionException, InterruptedException {
    CompletableFuture<String> login1 =
        CompletableFuture.supplyAsync(this::runOidcLoginAndReturnUserId);
    CompletableFuture<String> login2 =
        CompletableFuture.supplyAsync(this::runOidcLoginAndReturnUserId);

    String userId1 = login1.get();
    String userId2 = login2.get();

    assertEquals(userId1, userId2, "Concurrent first logins should converge on the same user");
  }

  @Test
  @Order(3)
  public void testStartUnsupportedOidcProvider() {
    PkcePair pkce = issuePkcePair();
    given()
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "clientType", "web",
                "codeChallenge", pkce.codeChallenge(),
                "codeChallengeMethod", "S256"))
        .when()
        .post("/api/auth/oidc/unsupported/start")
        .then()
        .statusCode(400)
        .body("message", equalTo("Unsupported OIDC provider: unsupported"));
  }

  @Test
  @Order(4)
  public void testStartKnownButNotImplementedOidcProvider() {
    PkcePair pkce = issuePkcePair();
    given()
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "clientType", "web",
                "codeChallenge", pkce.codeChallenge(),
                "codeChallengeMethod", "S256"))
        .when()
        .post("/api/auth/oidc/google/start")
        .then()
        .statusCode(501)
        .body("message", equalTo("OIDC provider not implemented: google"));
  }

  @Test
  @Order(5)
  public void testExchangeHarigataOidcLoginWithBadStateFails() {
    PkcePair pkce = issuePkcePair();
    Response startResponse =
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
            .extract()
            .response();

    String authorizationUrl = startResponse.jsonPath().getString("authorizationUrl");
    String badState = parseQueryParams(URI.create(authorizationUrl).getRawQuery()).get("state") + "-tampered";

    given()
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "code",
                HarigataOidcMockServerResource.DEFAULT_AUTHORIZATION_CODE,
                "state",
                badState,
                "codeVerifier",
                pkce.codeVerifier()))
        .when()
        .post("/api/auth/oidc/harigata/exchange")
        .then()
        .statusCode(401)
        .body("message", equalTo("Unknown OIDC state"));
  }

  @Test
  @Order(6)
  public void testGetCurrentUserWithValidAccessToken() {
    given()
        .header("Authorization", "Bearer " + accessToken)
        .when()
        .get("/api/me")
        .then()
        .statusCode(200)
        .body("id", equalTo(userId))
        .body("displayName", equalTo(HarigataOidcMockServerResource.DEFAULT_DISPLAY_NAME));
  }

  @Test
  @Order(7)
  public void testRefreshSession() {
    Response response =
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("refreshToken", refreshToken))
            .when()
            .post("/api/auth/refresh")
            .then()
            .statusCode(200)
            .body("accessToken", notNullValue())
            .body("refreshToken", notNullValue())
            .body("user.id", equalTo(userId))
            .extract()
            .response();

    rotatedAccessToken = response.jsonPath().getString("accessToken");
    rotatedRefreshToken = response.jsonPath().getString("refreshToken");

    assertNotEquals(accessToken, rotatedAccessToken);
    assertNotEquals(refreshToken, rotatedRefreshToken);
  }

  @Test
  @Order(8)
  public void testLogoutRevokesRefreshToken() {
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("refreshToken", rotatedRefreshToken))
        .when()
        .post("/api/auth/logout")
        .then()
        .statusCode(204);
  }

  @Test
  @Order(9)
  public void testRevokedRefreshTokenCannotBeUsed() {
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("refreshToken", rotatedRefreshToken))
        .when()
        .post("/api/auth/refresh")
        .then()
        .statusCode(401)
        .body("message", equalTo("Invalid refresh token"));
  }

  private String runOidcLoginAndReturnUserId() {
    PkcePair pkce = issuePkcePair();
    Response startResponse =
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
            .extract()
            .response();

    String authorizationUrl = startResponse.jsonPath().getString("authorizationUrl");

    Response authorizeResponse =
        given()
            .redirects()
            .follow(false)
            .when()
            .get(authorizationUrl)
            .then()
            .statusCode(302)
            .extract()
            .response();

    RedirectPayload redirectPayload = parseRedirectPayload(authorizeResponse.getHeader("Location"));

    Response exchangeResponse =
        given()
            .contentType(ContentType.JSON)
            .body(
                Map.of(
                    "code",
                    redirectPayload.code(),
                    "state",
                    redirectPayload.state(),
                    "codeVerifier",
                    pkce.codeVerifier()))
            .when()
            .post("/api/auth/oidc/harigata/exchange")
            .then()
            .statusCode(200)
            .extract()
            .response();

    return exchangeResponse.jsonPath().getString("user.id");
  }

  private RedirectPayload parseRedirectPayload(String locationHeader) {
    String decodedLocation = URLDecoder.decode(locationHeader, StandardCharsets.UTF_8);
    URI redirectedUri = URI.create(decodedLocation);
    Map<String, String> queryParams = parseQueryParams(redirectedUri.getRawQuery());
    return new RedirectPayload(decodedLocation, queryParams.get("code"), queryParams.get("state"));
  }

  private Map<String, String> parseQueryParams(String rawQuery) {
    Map<String, String> values = new LinkedHashMap<>();
    if (rawQuery == null || rawQuery.isBlank()) {
      return values;
    }

    for (String pair : rawQuery.split("&")) {
      String[] parts = pair.split("=", 2);
      String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
      String value = parts.length > 1 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
      values.put(key, value);
    }
    return values;
  }

  private record RedirectPayload(String location, String code, String state) {}

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
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  private record PkcePair(String codeVerifier, String codeChallenge) {}
}
