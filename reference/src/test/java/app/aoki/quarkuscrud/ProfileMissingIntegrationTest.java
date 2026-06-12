package app.aoki.quarkuscrud;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Integration tests for ProfileMissing semi-normal response.
 *
 * <p>These tests verify that when a user exists but has not created a profile yet, the API returns
 * a 404 status with a ProfileMissing response body (not ErrorResponse), allowing clients to
 * distinguish between "profile not created yet" (semi-normal) and actual error conditions.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ProfileMissingIntegrationTest {

  private static String jwtToken;
  private static Long userId;
  private static String jwtToken2;
  private static Long userId2;

  @Test
  @Order(0)
  public void setup() {
    // Create a fresh guest user for testing
    Response response = given().contentType(ContentType.JSON).post("/api/auth/guest");
    jwtToken = response.getHeader("Authorization").substring(7);

    // Get user ID
    Response userResponse =
        given().header("Authorization", "Bearer " + jwtToken).when().get("/api/me");
    userId = userResponse.jsonPath().getLong("id");

    // Create a second user without a profile for testing getUserProfile
    Response response2 = given().contentType(ContentType.JSON).post("/api/auth/guest");
    jwtToken2 = response2.getHeader("Authorization").substring(7);

    Response userResponse2 =
        given().header("Authorization", "Bearer " + jwtToken2).when().get("/api/me");
    userId2 = userResponse2.jsonPath().getLong("id");
  }

  @Test
  @Order(1)
  public void testGetMyProfile_WhenProfileNotCreated_ReturnsProfileMissing() {
    // When: fetching profile for a user who hasn't created one
    // Then: should return 404 with ProfileMissing body
    given()
        .header("Authorization", "Bearer " + jwtToken)
        .when()
        .get("/api/me/profile")
        .then()
        .statusCode(404)
        .contentType(ContentType.JSON)
        .body("type", equalTo("about:blank"))
        .body("title", equalTo("Profile Not Created"))
        .body("status", equalTo(404))
        .body("code", equalTo("PROFILE_MISSING"))
        .body("detail", equalTo("The user has not created a profile yet."));
  }

  @Test
  @Order(2)
  public void testGetUserProfile_WhenProfileNotCreated_ReturnsProfileMissing() {
    // When: fetching another user's profile that hasn't been created
    // Then: should return 404 with ProfileMissing body
    given()
        .header("Authorization", "Bearer " + jwtToken)
        .when()
        .get("/api/users/" + userId2 + "/profile")
        .then()
        .statusCode(404)
        .contentType(ContentType.JSON)
        .body("type", equalTo("about:blank"))
        .body("title", equalTo("Profile Not Created"))
        .body("status", equalTo(404))
        .body("code", equalTo("PROFILE_MISSING"))
        .body("detail", equalTo("The user has not created a profile yet."));
  }

  @Test
  @Order(3)
  public void testProfileMissing_AfterCreatingProfile_Returns200() {
    // Given: a user creates a profile
    given()
        .header("Authorization", "Bearer " + jwtToken)
        .contentType(ContentType.JSON)
        .body("{\"profileData\":{\"displayName\":\"Test User\"}}")
        .when()
        .put("/api/me/profile")
        .then()
        .statusCode(200);

    // When: fetching the profile
    // Then: should return 200 with the profile data
    given()
        .header("Authorization", "Bearer " + jwtToken)
        .when()
        .get("/api/me/profile")
        .then()
        .statusCode(200)
        .body("profileData.displayName", equalTo("Test User"));
  }
}
