package app.vagina.server;

import static io.restassured.RestAssured.given;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.ValidationReport;
import com.atlassian.oai.validator.restassured.OpenApiValidationFilter;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

@QuarkusTest
@TestMethodOrder(OrderAnnotation.class)
public class OpenApiContractTest {

  private static OpenApiValidationFilter validationFilter;
  private static String accessToken;
  private static String refreshToken;
  private static String userId;

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
  public void testCreateGuestSessionContract() {
    Response response =
        given()
            .filter(validationFilter)
            .contentType(ContentType.JSON)
            .when()
            .post("/api/auth/guest")
            .then()
            .statusCode(200)
            .extract()
            .response();

    accessToken = response.jsonPath().getString("accessToken");
    refreshToken = response.jsonPath().getString("refreshToken");
    userId = response.jsonPath().getString("user.id");
  }

  @Test
  @Order(2)
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
  @Order(3)
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
  @Order(4)
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
}
