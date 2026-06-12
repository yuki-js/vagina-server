package app.aoki.quarkuscrud;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Integration tests for Friendship operations. Tests friendship creation (profile card exchange)
 * and retrieval.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class FriendshipIntegrationTest {

  private static String user1Token;
  private static String user2Token;
  private static Long user1Id;
  private static Long user2Id;

  @Test
  @Order(0)
  public void setup() {
    // Create two guest users for testing friendships
    Response user1Response = given().contentType(ContentType.JSON).post("/api/auth/guest");
    user1Token = user1Response.getHeader("Authorization").substring(7);
    user1Id = user1Response.jsonPath().getLong("id");

    Response user2Response = given().contentType(ContentType.JSON).post("/api/auth/guest");
    user2Token = user2Response.getHeader("Authorization").substring(7);
    user2Id = user2Response.jsonPath().getLong("id");

    // Set up profiles for both users
    given()
        .header("Authorization", "Bearer " + user1Token)
        .contentType(ContentType.JSON)
        .body("{\"profileData\":{\"displayName\":\"User One\",\"bio\":\"First user\"}}")
        .put("/api/me/profile");

    given()
        .header("Authorization", "Bearer " + user2Token)
        .contentType(ContentType.JSON)
        .body("{\"profileData\":{\"displayName\":\"User Two\",\"bio\":\"Second user\"}}")
        .put("/api/me/profile");
  }

  @Test
  @Order(1)
  public void testCreateFriendshipWithoutAuthentication() {
    given()
        .contentType(ContentType.JSON)
        .body("{}")
        .when()
        .post("/api/users/" + user2Id + "/friendship")
        .then()
        .statusCode(401);
  }

  @Test
  @Order(2)
  public void testReceiveFriendship() {
    // User 1 sends their profile card to User 2
    given()
        .header("Authorization", "Bearer " + user1Token)
        .contentType(ContentType.JSON)
        .body("{}")
        .when()
        .post("/api/users/" + user2Id + "/friendship")
        .then()
        .statusCode(anyOf(is(200), is(201)))
        .body("id", notNullValue())
        .body("senderUserId", equalTo(user1Id.intValue()))
        .body("recipientUserId", equalTo(user2Id.intValue()));
  }

  @Test
  @Order(3)
  public void testListReceivedFriendships() {
    // User 2 lists their received friendships
    given()
        .header("Authorization", "Bearer " + user2Token)
        .when()
        .get("/api/me/friendships/received")
        .then()
        .statusCode(200)
        .body("size()", greaterThanOrEqualTo(1))
        .body("[0].senderUserId", equalTo(user1Id.intValue()));
  }

  @Test
  @Order(4)
  public void testReceiveFriendshipTwice() {
    // User 1 tries to send profile card again to User 2
    given()
        .header("Authorization", "Bearer " + user1Token)
        .contentType(ContentType.JSON)
        .body("{}")
        .when()
        .post("/api/users/" + user2Id + "/friendship")
        .then()
        .statusCode(anyOf(is(200), is(201), is(409))); // May return 409 Conflict
  }

  @Test
  @Order(5)
  public void testMutualFriendshipAlreadyExists() {
    // With mutual friendships, when user 1 sent profile card to user 2 in test order 2,
    // both directions were created automatically. So user 2 trying to send to user 1
    // should now be idempotent and return 200 (not 409).
    given()
        .header("Authorization", "Bearer " + user2Token)
        .contentType(ContentType.JSON)
        .body("{}")
        .when()
        .post("/api/users/" + user1Id + "/friendship")
        .then()
        .statusCode(200);

    // User 1 should have a received friendship from User 2
    given()
        .header("Authorization", "Bearer " + user1Token)
        .when()
        .get("/api/me/friendships/received")
        .then()
        .statusCode(200)
        .body("size()", greaterThanOrEqualTo(1));
  }

  @Test
  @Order(6)
  public void testReceiveFriendshipToNonExistentUser() {
    given()
        .header("Authorization", "Bearer " + user1Token)
        .contentType(ContentType.JSON)
        .body("{}")
        .when()
        .post("/api/users/999999/friendship")
        .then()
        .statusCode(404);
  }

  @Test
  @Order(7)
  public void testReceivedFriendshipsWithProfileData() {
    // Create two new users without profiles
    Response userAResponse = given().contentType(ContentType.JSON).post("/api/auth/guest");
    String userAToken = userAResponse.getHeader("Authorization").substring(7);
    Long userAId = userAResponse.jsonPath().getLong("id");

    Response userBResponse = given().contentType(ContentType.JSON).post("/api/auth/guest");
    String userBToken = userBResponse.getHeader("Authorization").substring(7);
    Long userBId = userBResponse.jsonPath().getLong("id");

    // User A sends profile card to User B (no profiles yet)
    given()
        .header("Authorization", "Bearer " + userAToken)
        .contentType(ContentType.JSON)
        .body("{}")
        .when()
        .post("/api/users/" + userBId + "/friendship")
        .then()
        .statusCode(anyOf(is(200), is(201)));

    // User B should see the received friendship with null senderProfile
    given()
        .header("Authorization", "Bearer " + userBToken)
        .when()
        .get("/api/me/friendships/received")
        .then()
        .statusCode(200)
        .body(
            "find { it.senderUserId == " + userAId.intValue() + " }.senderProfile", equalTo(null));

    // Getting User A's profile should return 404
    given()
        .header("Authorization", "Bearer " + userBToken)
        .when()
        .get("/api/users/" + userAId + "/profile")
        .then()
        .statusCode(404);

    // User A creates a profile
    given()
        .header("Authorization", "Bearer " + userAToken)
        .contentType(ContentType.JSON)
        .body(
            "{\"profileData\":{\"displayName\":\"User A\","
                + "\"bio\":\"This is User A's bio\",\"favoriteColor\":\"blue\"}}")
        .put("/api/me/profile")
        .then()
        .statusCode(200);

    // Now User B should see the received friendship with senderProfile populated
    String senderProfileBasePath =
        "find { it.senderUserId == " + userAId.intValue() + " }.senderProfile";
    given()
        .header("Authorization", "Bearer " + userBToken)
        .when()
        .get("/api/me/friendships/received")
        .then()
        .statusCode(200)
        .body(senderProfileBasePath, notNullValue())
        .body(senderProfileBasePath + ".userId", equalTo(userAId.intValue()))
        .body(senderProfileBasePath + ".profileData.displayName", equalTo("User A"))
        .body(senderProfileBasePath + ".profileData.bio", equalTo("This is User A's bio"))
        .body(senderProfileBasePath + ".profileData.favoriteColor", equalTo("blue"));

    // User A's profile should now be accessible
    given()
        .header("Authorization", "Bearer " + userBToken)
        .when()
        .get("/api/users/" + userAId + "/profile")
        .then()
        .statusCode(200)
        .body("userId", equalTo(userAId.intValue()))
        .body("profileData.displayName", equalTo("User A"));
  }

  @Test
  @Order(8)
  public void testGetFriendshipByOtherUserSuccess() {
    // User 1 gets friendship with User 2 (they are friends from test order 2)
    given()
        .header("Authorization", "Bearer " + user1Token)
        .when()
        .get("/api/friendships/" + user2Id)
        .then()
        .statusCode(200)
        .body("id", notNullValue())
        .body(
            "senderUserId",
            anyOf(equalTo(user1Id.intValue()), equalTo(user2Id.intValue()))) // Either direction
        .body(
            "recipientUserId",
            anyOf(equalTo(user1Id.intValue()), equalTo(user2Id.intValue()))); // Either direction
  }

  @Test
  @Order(9)
  public void testGetFriendshipByOtherUserReverseDirection() {
    // User 2 gets friendship with User 1 (same friendship, reverse perspective)
    given()
        .header("Authorization", "Bearer " + user2Token)
        .when()
        .get("/api/friendships/" + user1Id)
        .then()
        .statusCode(200)
        .body("id", notNullValue())
        .body(
            "senderUserId",
            anyOf(equalTo(user1Id.intValue()), equalTo(user2Id.intValue()))) // Either direction
        .body(
            "recipientUserId",
            anyOf(equalTo(user1Id.intValue()), equalTo(user2Id.intValue()))); // Either direction
  }

  @Test
  @Order(10)
  public void testGetFriendshipByOtherUserNotFound() {
    // Create a third user who has no friendship with user 1
    Response user3Response = given().contentType(ContentType.JSON).post("/api/auth/guest");
    String user3Token = user3Response.getHeader("Authorization").substring(7);
    Long user3Id = user3Response.jsonPath().getLong("id");

    // User 1 tries to get friendship with User 3 (no friendship exists)
    given()
        .header("Authorization", "Bearer " + user1Token)
        .when()
        .get("/api/friendships/" + user3Id)
        .then()
        .statusCode(404);

    // User 3 tries to get friendship with User 1 (no friendship exists)
    given()
        .header("Authorization", "Bearer " + user3Token)
        .when()
        .get("/api/friendships/" + user1Id)
        .then()
        .statusCode(404);
  }

  @Test
  @Order(11)
  public void testGetFriendshipByOtherUserUnauthenticated() {
    // Request without authentication should return 401
    given().when().get("/api/friendships/" + user2Id).then().statusCode(401);
  }

  @Test
  @Order(12)
  public void testFriendshipMetaWriteReadUpdateRead() {
    // Create two new users for meta testing
    Response userXResponse = given().contentType(ContentType.JSON).post("/api/auth/guest");
    String userXToken = userXResponse.getHeader("Authorization").substring(7);
    Long userXId = userXResponse.jsonPath().getLong("id");

    Response userYResponse = given().contentType(ContentType.JSON).post("/api/auth/guest");
    String userYToken = userYResponse.getHeader("Authorization").substring(7);
    Long userYId = userYResponse.jsonPath().getLong("id");

    // Step 1: Create friendship with initial meta
    given()
        .header("Authorization", "Bearer " + userXToken)
        .contentType(ContentType.JSON)
        .body("{\"meta\":{\"location\":\"Tokyo\",\"event\":\"Conference2024\"}}")
        .when()
        .post("/api/users/" + userYId + "/friendship")
        .then()
        .statusCode(anyOf(is(200), is(201)))
        .body("senderUserId", equalTo(userXId.intValue()))
        .body("recipientUserId", equalTo(userYId.intValue()))
        .body("meta.location", equalTo("Tokyo"))
        .body("meta.event", equalTo("Conference2024"));

    // Step 2: Read friendship and verify initial meta
    given()
        .header("Authorization", "Bearer " + userXToken)
        .when()
        .get("/api/friendships/" + userYId)
        .then()
        .statusCode(200)
        .body("meta.location", equalTo("Tokyo"))
        .body("meta.event", equalTo("Conference2024"));

    // Also verify from the other user's perspective
    given()
        .header("Authorization", "Bearer " + userYToken)
        .when()
        .get("/api/friendships/" + userXId)
        .then()
        .statusCode(200)
        .body("meta.location", equalTo("Tokyo"))
        .body("meta.event", equalTo("Conference2024"));

    // Verify meta in received friendships list
    given()
        .header("Authorization", "Bearer " + userYToken)
        .when()
        .get("/api/me/friendships/received")
        .then()
        .statusCode(200)
        .body(
            "find { it.senderUserId == " + userXId.intValue() + " }.meta.location",
            equalTo("Tokyo"))
        .body(
            "find { it.senderUserId == " + userXId.intValue() + " }.meta.event",
            equalTo("Conference2024"));

    // Step 3: Update meta via idempotent POST (same friendship, new meta)
    given()
        .header("Authorization", "Bearer " + userXToken)
        .contentType(ContentType.JSON)
        .body(
            "{\"meta\":{\"location\":\"Osaka\",\"event\":\"Workshop2025\",\"notes\":\"Follow-up\"}}")
        .when()
        .post("/api/users/" + userYId + "/friendship")
        .then()
        .statusCode(200)
        .body("senderUserId", equalTo(userXId.intValue()))
        .body("recipientUserId", equalTo(userYId.intValue()))
        .body("meta.location", equalTo("Osaka"))
        .body("meta.event", equalTo("Workshop2025"))
        .body("meta.notes", equalTo("Follow-up"));

    // Step 4: Read friendship again and verify updated meta
    given()
        .header("Authorization", "Bearer " + userXToken)
        .when()
        .get("/api/friendships/" + userYId)
        .then()
        .statusCode(200)
        .body("meta.location", equalTo("Osaka"))
        .body("meta.event", equalTo("Workshop2025"))
        .body("meta.notes", equalTo("Follow-up"));

    // Verify updated meta from the other user's perspective
    given()
        .header("Authorization", "Bearer " + userYToken)
        .when()
        .get("/api/friendships/" + userXId)
        .then()
        .statusCode(200)
        .body("meta.location", equalTo("Osaka"))
        .body("meta.event", equalTo("Workshop2025"))
        .body("meta.notes", equalTo("Follow-up"));

    // Verify updated meta in received friendships list
    given()
        .header("Authorization", "Bearer " + userYToken)
        .when()
        .get("/api/me/friendships/received")
        .then()
        .statusCode(200)
        .body(
            "find { it.senderUserId == " + userXId.intValue() + " }.meta.location",
            equalTo("Osaka"))
        .body(
            "find { it.senderUserId == " + userXId.intValue() + " }.meta.event",
            equalTo("Workshop2025"))
        .body(
            "find { it.senderUserId == " + userXId.intValue() + " }.meta.notes",
            equalTo("Follow-up"));
  }
}
