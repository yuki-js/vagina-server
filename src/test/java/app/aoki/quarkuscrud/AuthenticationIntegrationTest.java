package app.aoki.quarkuscrud;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Integration tests for authentication functionality. Tests the complete authentication flow
 * including guest user creation and JWT token validation.
 */
@QuarkusTest
@TestMethodOrder(OrderAnnotation.class)
public class AuthenticationIntegrationTest {

  private static String jwtToken;

  @Test
  @Order(1)
  public void testCreateGuestUser() {
    Response response =
        given()
            .contentType(ContentType.JSON)
            .when()
            .post("/api/auth/guest")
            .then()
            .statusCode(anyOf(is(200), is(201)))
            .body("id", notNullValue())
            .body("createdAt", notNullValue())
            .header("Authorization", notNullValue())
            .extract()
            .response();

    // Extract JWT token from Authorization header
    String authHeader = response.getHeader("Authorization");
    assertNotNull(authHeader, "Authorization header should be present");
    assertTrue(
        authHeader.startsWith("Bearer "), "Authorization header should start with 'Bearer '");
    jwtToken = authHeader.substring(7); // Remove "Bearer " prefix
    assertFalse(jwtToken.isEmpty(), "JWT token should not be empty");
  }

  @Test
  @Order(2)
  public void testGetCurrentUserWithValidToken() {
    given()
        .header("Authorization", "Bearer " + jwtToken)
        .when()
        .get("/api/me")
        .then()
        .statusCode(200)
        .body("id", notNullValue())
        .body("createdAt", notNullValue());
  }

  @Test
  @Order(3)
  public void testGetCurrentUserWithoutToken() {
    given()
        .when()
        .get("/api/me")
        .then()
        .statusCode(401)
        .body("error", equalTo("No JWT token found"));
  }

  @Test
  @Order(4)
  public void testGetCurrentUserWithInvalidToken() {
    given()
        .header("Authorization", "Bearer invalid-token-12345")
        .when()
        .get("/api/me")
        .then()
        .statusCode(401);
  }

  @Test
  @Order(5)
  public void testCreateMultipleGuestUsers() {
    // Create first guest user
    Response response1 =
        given()
            .contentType(ContentType.JSON)
            .when()
            .post("/api/auth/guest")
            .then()
            .statusCode(anyOf(is(200), is(201)))
            .body("id", notNullValue())
            .header("Authorization", notNullValue())
            .extract()
            .response();

    String token1 = response1.getHeader("Authorization").substring(7);
    Long userId1 = response1.jsonPath().getLong("id");

    // Create second guest user
    Response response2 =
        given()
            .contentType(ContentType.JSON)
            .when()
            .post("/api/auth/guest")
            .then()
            .statusCode(anyOf(is(200), is(201)))
            .body("id", notNullValue())
            .header("Authorization", notNullValue())
            .extract()
            .response();

    String token2 = response2.getHeader("Authorization").substring(7);
    Long userId2 = response2.jsonPath().getLong("id");

    // Verify tokens are different
    assertNotEquals(token1, token2, "JWT tokens should be unique");

    // Verify user IDs are different
    assertNotEquals(userId1, userId2, "User IDs should be unique");
  }
}
