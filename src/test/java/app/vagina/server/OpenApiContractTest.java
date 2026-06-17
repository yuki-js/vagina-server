package app.vagina.server;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import app.vagina.server.support.HarigataOidcMockServerResource;
import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.restassured.OpenApiValidationFilter;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

@QuarkusTest
@QuarkusTestResource(HarigataOidcMockServerResource.class)
@TestMethodOrder(OrderAnnotation.class)
public class OpenApiContractTest {

  private static OpenApiValidationFilter validationFilter;
  private static String accessToken;
  private static String refreshToken;
  private static String state;
  private static String codeVerifier;

  @BeforeAll
  public static void setupValidationFilter() throws IOException {
    URL specUrl = OpenApiContractTest.class.getClassLoader().getResource("META-INF/openapi.yaml");
    if (specUrl == null) {
      throw new IllegalStateException("OpenAPI spec file not found");
    }

    String specContent = new String(specUrl.openStream().readAllBytes(), StandardCharsets.UTF_8);
    OpenApiInteractionValidator validator =
        OpenApiInteractionValidator.createForInlineApiSpecification(specContent).build();
    validationFilter = new OpenApiValidationFilter(validator);
  }

  @Test
  @Order(1)
  public void testStartOidcLoginContract() {
    PkcePair pkce = issuePkcePair();
    codeVerifier = pkce.codeVerifier();
    Response response =
        given()
            .filter(validationFilter)
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

    String authorizationUrl = response.jsonPath().getString("authorizationUrl");
    state = parseQueryParams(URI.create(authorizationUrl).getRawQuery()).get("state");
  }

  @Test
  @Order(2)
  public void testExchangeOidcLoginContract() {
    Response response =
        given()
            .filter(validationFilter)
            .contentType(ContentType.JSON)
            .body(
                Map.of(
                    "code", HarigataOidcMockServerResource.DEFAULT_AUTHORIZATION_CODE,
                    "state", state,
                    "codeVerifier", codeVerifier))
            .when()
            .post("/api/auth/oidc/harigata/exchange")
            .then()
            .statusCode(200)
            .extract()
            .response();

    accessToken = response.jsonPath().getString("accessToken");
    refreshToken = response.jsonPath().getString("refreshToken");
  }

  @Test
  @Order(3)
  public void testGetCurrentUserContract() {
    given()
        .filter(validationFilter)
        .header("Authorization", "Bearer " + accessToken)
        .when()
        .get("/api/me")
        .then()
        .statusCode(200);
  }

  @Test
  @Order(4)
  public void testRefreshSessionContract() {
    Response response =
        given()
            .filter(validationFilter)
            .contentType(ContentType.JSON)
            .body(Map.of("refreshToken", refreshToken))
            .when()
            .post("/api/auth/refresh")
            .then()
            .statusCode(200)
            .extract()
            .response();

    accessToken = response.jsonPath().getString("accessToken");
    refreshToken = response.jsonPath().getString("refreshToken");
  }

  @Test
  @Order(5)
  public void testLogoutContract() {
    given()
        .filter(validationFilter)
        .contentType(ContentType.JSON)
        .body(Map.of("refreshToken", refreshToken))
        .when()
        .post("/api/auth/logout")
        .then()
        .statusCode(204);
  }

  // Unauthenticated access to protected endpoints must return 401.
  // This test ensures the security declared in OpenAPI spec is enforced at runtime,
  // and that the contract validator does not silently ignore missing Authorization headers.

  @Test
  @Order(6)
  public void testUnauthenticatedGetCurrentUserReturns401() {
    given()
        .when()
        .get("/api/me")
        .then()
        .statusCode(401)
        .body("message", notNullValue());
  }

  @Test
  @Order(7)
  public void testUnauthenticatedListSpeedDialsReturns401() {
    given()
        .when()
        .get("/api/speed-dials")
        .then()
        .statusCode(401)
        .body("message", notNullValue());
  }

  @Test
  @Order(8)
  public void testUnauthenticatedVfsRpcReturns401() {
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("jsonrpc", "2.0", "method", "vfs.list", "params", Map.of("path", "/"), "id", 1))
        .when()
        .post("/api/vfs/rpc")
        .then()
        .statusCode(401)
        .body("message", equalTo("No JWT token found"));
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
