package app.vagina.server;

import static io.restassured.RestAssured.given;

import app.vagina.server.support.HarigataOidcMockServerResource;
import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.ValidationReport;
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
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

@QuarkusTest
@QuarkusTestResource(HarigataOidcMockServerResource.class)
@TestMethodOrder(OrderAnnotation.class)
public class OpenApiContractTest {

  private static final String CODE_VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";

  private static OpenApiValidationFilter validationFilter;
  private static String accessToken;
  private static String refreshToken;
  private static String state;

  @BeforeAll
  public static void setupValidationFilter() throws IOException {
    URL specUrl = OpenApiContractTest.class.getClassLoader().getResource("META-INF/openapi.yaml");
    if (specUrl == null) {
      throw new IllegalStateException("OpenAPI spec file not found");
    }

    String specContent = new String(specUrl.openStream().readAllBytes(), StandardCharsets.UTF_8);
    OpenApiInteractionValidator validator =
        OpenApiInteractionValidator.createForInlineApiSpecification(specContent)
            .withLevelResolver(
                LevelResolver.create()
                    .withLevel("validation.request.security.missing", ValidationReport.Level.IGNORE)
                    .build())
            .build();
    validationFilter = new OpenApiValidationFilter(validator);
  }

  @Test
  @Order(1)
  public void testStartOidcLoginContract() {
    Response response =
        given()
            .filter(validationFilter)
            .contentType(ContentType.JSON)
            .body(Map.of("clientType", "web"))
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
                    "codeVerifier", CODE_VERIFIER))
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
}
