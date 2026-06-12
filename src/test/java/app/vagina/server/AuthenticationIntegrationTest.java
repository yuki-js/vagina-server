package app.vagina.server;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.util.Map;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

@QuarkusTest
@TestMethodOrder(OrderAnnotation.class)
public class AuthenticationIntegrationTest {

  private static String accessToken;
  private static String refreshToken;
  private static String rotatedAccessToken;
  private static String rotatedRefreshToken;
  private static String userId;

  @Test
  @Order(1)
  public void testCreateGuestSession() {
    Response response =
        given()
            .contentType(ContentType.JSON)
            .when()
            .post("/api/auth/guest")
            .then()
            .statusCode(200)
            .body("accessToken", notNullValue())
            .body("refreshToken", notNullValue())
            .body("tokenType", equalTo("Bearer"))
            .body("expiresIn", notNullValue())
            .body("user.id", notNullValue())
            .body("user.createdAt", notNullValue())
            .body("user.accountLifecycle", anyOf(is("created"), is("active")))
            .extract()
            .response();

    accessToken = response.jsonPath().getString("accessToken");
    refreshToken = response.jsonPath().getString("refreshToken");
    userId = response.jsonPath().getString("user.id");

    assertNotNull(accessToken, "Access token should be present");
    assertNotNull(refreshToken, "Refresh token should be present");
    assertNotNull(userId, "User id should be present");
    assertTrue(!accessToken.isBlank(), "Access token should not be blank");
    assertTrue(!refreshToken.isBlank(), "Refresh token should not be blank");
  }

  @Test
  @Order(2)
  public void testGetCurrentUserWithValidAccessToken() {
    given()
        .header("Authorization", "Bearer " + accessToken)
        .when()
        .get("/api/me")
        .then()
        .statusCode(200)
        .body("id", equalTo(userId))
        .body("createdAt", notNullValue())
        .body("accountLifecycle", anyOf(is("created"), is("active")));
  }

  @Test
  @Order(3)
  public void testGetCurrentUserWithoutToken() {
    given()
        .when()
        .get("/api/me")
        .then()
        .statusCode(401)
        .body("message", equalTo("No JWT token found"));
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
            .body("tokenType", equalTo("Bearer"))
            .body("user.id", equalTo(userId))
            .extract()
            .response();

    rotatedAccessToken = response.jsonPath().getString("accessToken");
    rotatedRefreshToken = response.jsonPath().getString("refreshToken");

    assertNotEquals(accessToken, rotatedAccessToken, "Access token should rotate on refresh");
    assertNotEquals(refreshToken, rotatedRefreshToken, "Refresh token should rotate on refresh");
  }

  @Test
  @Order(6)
  public void testRotatedRefreshTokenCannotReuseOldToken() {
    Response guestResponse =
        given()
            .contentType(ContentType.JSON)
            .when()
            .post("/api/auth/guest")
            .then()
            .statusCode(200)
            .extract()
            .response();

    String originalRefreshToken = guestResponse.jsonPath().getString("refreshToken");

    given()
        .contentType(ContentType.JSON)
        .body(Map.of("refreshToken", originalRefreshToken))
        .when()
        .post("/api/auth/refresh")
        .then()
        .statusCode(200)
        .body("refreshToken", notNullValue());

    given()
        .contentType(ContentType.JSON)
        .body(Map.of("refreshToken", originalRefreshToken))
        .when()
        .post("/api/auth/refresh")
        .then()
        .statusCode(401)
        .body("message", equalTo("Invalid refresh token"));
  }

  @Test
  @Order(7)
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
  @Order(8)
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
}
