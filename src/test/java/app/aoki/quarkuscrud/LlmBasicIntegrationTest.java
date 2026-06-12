package app.aoki.quarkuscrud;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Basic integration tests for LLM API endpoints that don't require custom WireMock configuration.
 *
 * <p>These tests validate authentication and request validation. They work in both JVM and native
 * modes since they don't need custom WireMock stubs.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class LlmBasicIntegrationTest {

  private static String jwtToken;

  @Test
  @Order(0)
  public void setup() {
    // Create a guest user for testing
    Response response = given().contentType(ContentType.JSON).post("/api/auth/guest");
    jwtToken = response.getHeader("Authorization").substring(7);
    assertNotNull(jwtToken);
  }

  @Test
  @Order(1)
  public void testGenerateFakeNamesWithoutAuthentication() {
    given()
        .contentType(ContentType.JSON)
        .body("{\"inputName\":\"青木 勇樹\",\"variance\":0.1}")
        .when()
        .post("/api/llm/fake-names")
        .then()
        .statusCode(401);
  }

  @Test
  @Order(4)
  public void testGenerateFakeNamesMissingInputName() {
    given()
        .header("Authorization", "Bearer " + jwtToken)
        .contentType(ContentType.JSON)
        .body("{\"variance\":\"とても良く似ている名前\"}")
        .when()
        .post("/api/llm/fake-names")
        .then()
        .statusCode(400); // Bean Validation will catch this
  }

  @Test
  @Order(5)
  public void testGenerateFakeNamesEmptyInputName() {
    given()
        .header("Authorization", "Bearer " + jwtToken)
        .contentType(ContentType.JSON)
        .body("{\"inputName\":\"\",\"variance\":\"とても良く似ている名前\"}")
        .when()
        .post("/api/llm/fake-names")
        .then()
        .statusCode(400); // Size validation will catch this
  }

  @Test
  @Order(6)
  public void testGenerateFakeNamesInvalidVariance() {
    given()
        .header("Authorization", "Bearer " + jwtToken)
        .contentType(ContentType.JSON)
        .body("{\"inputName\":\"青木 勇樹\",\"variance\":\"invalid level\"}")
        .when()
        .post("/api/llm/fake-names")
        .then()
        .statusCode(400); // Use case validation will catch this
  }

  @Test
  @Order(7)
  public void testGenerateFakeNamesMissingVariance() {
    given()
        .header("Authorization", "Bearer " + jwtToken)
        .contentType(ContentType.JSON)
        .body("{\"inputName\":\"青木 勇樹\"}")
        .when()
        .post("/api/llm/fake-names")
        .then()
        .statusCode(400); // NotNull validation will catch this
  }
}
