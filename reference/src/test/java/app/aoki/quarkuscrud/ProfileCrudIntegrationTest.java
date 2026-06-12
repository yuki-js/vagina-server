package app.aoki.quarkuscrud;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Integration tests for Profile operations. Tests profile retrieval, updates, and access control.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ProfileCrudIntegrationTest {

  private static String jwtToken;
  private static Long userId;

  @Test
  @Order(0)
  public void setup() {
    // Create a guest user for testing
    Response response = given().contentType(ContentType.JSON).post("/api/auth/guest");
    jwtToken = response.getHeader("Authorization").substring(7);

    // Get user ID
    Response userResponse =
        given().header("Authorization", "Bearer " + jwtToken).when().get("/api/me");
    userId = userResponse.jsonPath().getLong("id");
  }

  @Test
  @Order(1)
  public void testGetMyProfileInitially() {
    // Initially profile should not exist - returns 404
    given()
        .header("Authorization", "Bearer " + jwtToken)
        .when()
        .get("/api/me/profile")
        .then()
        .statusCode(404); // Profile doesn't exist yet
  }

  @Test
  @Order(2)
  public void testUpdateMyProfile() {
    Response response =
        given()
            .header("Authorization", "Bearer " + jwtToken)
            .contentType(ContentType.JSON)
            .body("{\"profileData\":{\"displayName\":\"Test User\",\"bio\":\"Test bio\"}}")
            .when()
            .put("/api/me/profile")
            .then()
            .statusCode(200)
            .body("profileData.displayName", equalTo("Test User"))
            .body("profileData.bio", equalTo("Test bio"))
            .extract()
            .response();
  }

  @Test
  @Order(3)
  public void testGetMyProfileAfterUpdate() {
    given()
        .header("Authorization", "Bearer " + jwtToken)
        .when()
        .get("/api/me/profile")
        .then()
        .statusCode(200)
        .body("profileData.displayName", equalTo("Test User"))
        .body("profileData.bio", equalTo("Test bio"));
  }

  @Test
  @Order(4)
  public void testGetAnotherUserProfile() {
    given()
        .header("Authorization", "Bearer " + jwtToken)
        .when()
        .get("/api/users/" + userId + "/profile")
        .then()
        .statusCode(200)
        .body("profileData.displayName", equalTo("Test User"));
  }

  @Test
  @Order(5)
  public void testUpdateProfileWithSpecialCharacters() {
    given()
        .header("Authorization", "Bearer " + jwtToken)
        .contentType(ContentType.JSON)
        .body(
            "{\"profileData\":{\"displayName\":\"Test 日本語 @#$%\",\"bio\":\"Bio with émojis 🎉 and ünïçödé\"}}")
        .when()
        .put("/api/me/profile")
        .then()
        .statusCode(200)
        .body("profileData.displayName", equalTo("Test 日本語 @#$%"))
        .body("profileData.bio", equalTo("Bio with émojis 🎉 and ünïçödé"));
  }

  @Test
  @Order(6)
  public void testUpdateProfileWithNullBio() {
    given()
        .header("Authorization", "Bearer " + jwtToken)
        .contentType(ContentType.JSON)
        .body("{\"profileData\":{\"displayName\":\"Name Only\",\"bio\":null}}")
        .when()
        .put("/api/me/profile")
        .then()
        .statusCode(200)
        .body("profileData.displayName", equalTo("Name Only"))
        .body("profileData.bio", nullValue());
  }

  @Test
  @Order(7)
  public void testGetNonExistentUserProfile() {
    // Try to get profile for non-existent user
    given()
        .header("Authorization", "Bearer " + jwtToken)
        .when()
        .get("/api/users/999999/profile")
        .then()
        .statusCode(404);
  }

  @Test
  @Order(8)
  public void testUpdateProfileWithoutAuthentication() {
    // Try to update profile without auth token
    given()
        .contentType(ContentType.JSON)
        .body("{\"profileData\":{\"displayName\":\"Hacker\"}}")
        .when()
        .put("/api/me/profile")
        .then()
        .statusCode(401);
  }
}
