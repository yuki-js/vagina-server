package app.aoki.quarkuscrud;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Integration tests for authorization and access control. Tests that users can only modify their
 * own resources and validates multi-user scenarios.
 */
@QuarkusTest
@TestMethodOrder(OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class AuthorizationIntegrationTest {

  private static String user1Token;
  private static String user2Token;
  private static Long user1Id;
  private static Long user2Id;
  private static Long user1EventId;

  @Test
  @Order(0)
  public void setup() {
    // Create two different guest users for testing authorization
    Response user1Response = given().contentType(ContentType.JSON).post("/api/auth/guest");
    user1Token = user1Response.getHeader("Authorization").substring(7);
    user1Id = user1Response.jsonPath().getLong("id");

    Response user2Response = given().contentType(ContentType.JSON).post("/api/auth/guest");
    user2Token = user2Response.getHeader("Authorization").substring(7);
    user2Id = user2Response.jsonPath().getLong("id");

    // User 1 creates an event
    Response eventResponse =
        given()
            .header("Authorization", "Bearer " + user1Token)
            .contentType(ContentType.JSON)
            .body("{}")
            .post("/api/events");
    user1EventId = eventResponse.jsonPath().getLong("id");

    // Set up profiles
    given()
        .header("Authorization", "Bearer " + user1Token)
        .contentType(ContentType.JSON)
        .body("{\"profileData\":{\"displayName\":\"User One\"}}")
        .put("/api/me/profile");

    given()
        .header("Authorization", "Bearer " + user2Token)
        .contentType(ContentType.JSON)
        .body("{\"profileData\":{\"displayName\":\"User Two\"}}")
        .put("/api/me/profile");
  }

  @Test
  @Order(1)
  public void testUserCanAccessOwnEvent() {
    given()
        .header("Authorization", "Bearer " + user1Token)
        .when()
        .get("/api/events/" + user1EventId)
        .then()
        .statusCode(200)
        .body("id", equalTo(user1EventId.intValue()))
        .body("initiatorId", equalTo(user1Id.intValue()));
  }

  @Test
  @Order(2)
  public void testAnotherUserCanViewEvent() {
    // User 2 should be able to view User 1's event (events are generally viewable)
    given()
        .header("Authorization", "Bearer " + user2Token)
        .when()
        .get("/api/events/" + user1EventId)
        .then()
        .statusCode(200)
        .body("id", equalTo(user1EventId.intValue()));
  }

  @Test
  @Order(3)
  public void testUserCannotUpdateAnotherUsersProfile() {
    // User 2 tries to update User 1's profile (should fail)
    // The API only allows updating /api/me/profile, so this is inherently protected
    // But we can verify User 2 cannot update as if they were User 1
    given()
        .header("Authorization", "Bearer " + user2Token)
        .contentType(ContentType.JSON)
        .body("{\"profileData\":{\"displayName\":\"Hacked Name\"}}")
        .when()
        .put("/api/me/profile")
        .then()
        .statusCode(200)
        .body(
            "profileData.displayName",
            equalTo("Hacked Name")); // This updates User 2's profile, not User 1's

    // Verify User 1's profile is unchanged
    given()
        .header("Authorization", "Bearer " + user1Token)
        .when()
        .get("/api/me/profile")
        .then()
        .statusCode(200)
        .body("profileData.displayName", equalTo("User One"));
  }

  @Test
  @Order(4)
  public void testUserCanUpdateOwnProfile() {
    given()
        .header("Authorization", "Bearer " + user1Token)
        .contentType(ContentType.JSON)
        .body("{\"profileData\":{\"displayName\":\"Updated by Owner\",\"bio\":\"Owner update\"}}")
        .when()
        .put("/api/me/profile")
        .then()
        .statusCode(200)
        .body("profileData.displayName", equalTo("Updated by Owner"))
        .body("profileData.bio", equalTo("Owner update"));
  }

  @Test
  @Order(5)
  public void testUserCanListAnotherUsersEvents() {
    // User 2 can view User 1's events (public information)
    // This tests authorization: other users can view events
    given()
        .header("Authorization", "Bearer " + user2Token)
        .when()
        .get("/api/users/" + user1Id + "/events")
        .then()
        .statusCode(200)
        .body("size()", greaterThanOrEqualTo(1));
  }

  @Test
  @Order(6)
  public void testUnauthorizedAccessReturns401() {
    given()
        .when()
        .get("/api/me")
        .then()
        .statusCode(401)
        .body("error", equalTo("No JWT token found"));
  }

  @Test
  @Order(7)
  public void testUserCanReceiveFriendshipFromAnother() {
    // User 1 sends friendship to User 2
    given()
        .header("Authorization", "Bearer " + user1Token)
        .contentType(ContentType.JSON)
        .body("{\"fromUserId\":" + user1Id + "}")
        .when()
        .post("/api/users/" + user2Id + "/friendship")
        .then()
        .statusCode(anyOf(is(200), is(201))) // Idempotent operation
        .body("senderUserId", equalTo(user1Id.intValue()))
        .body("recipientUserId", equalTo(user2Id.intValue()));

    // User 2 can see the received friendship
    given()
        .header("Authorization", "Bearer " + user2Token)
        .when()
        .get("/api/me/friendships/received")
        .then()
        .statusCode(200)
        .body("size()", greaterThanOrEqualTo(1));
  }

  // ==================== CWE-284 Access Control Tests ====================

  /**
   * Test CWE-284 fix: Event owner can see their own invitation code. This verifies that the owner
   * still has access to the invitation code after the access control fix.
   */
  @Test
  @Order(8)
  public void testEventOwnerCanSeeInvitationCode() {
    // User 1 (owner) should see the invitation code for their own event
    given()
        .header("Authorization", "Bearer " + user1Token)
        .when()
        .get("/api/events/" + user1EventId)
        .then()
        .statusCode(200)
        .body("id", equalTo(user1EventId.intValue()))
        .body("initiatorId", equalTo(user1Id.intValue()))
        .body("invitationCode", notNullValue());
  }

  /**
   * Test CWE-284 fix: Non-owner cannot see another user's event invitation code. This is the core
   * test for the CWE-284 vulnerability fix - invitation codes should be hidden from non-owners.
   */
  @Test
  @Order(9)
  public void testNonOwnerCannotSeeInvitationCode() {
    // User 2 (non-owner) should NOT see the invitation code for User 1's event
    given()
        .header("Authorization", "Bearer " + user2Token)
        .when()
        .get("/api/events/" + user1EventId)
        .then()
        .statusCode(200)
        .body("id", equalTo(user1EventId.intValue()))
        .body("invitationCode", nullValue());
  }

  /**
   * Test CWE-284 fix: Owner can see invitation codes when listing their own events. Verifies that
   * the listEventsByUser endpoint also respects access control.
   */
  @Test
  @Order(10)
  public void testOwnerCanSeeInvitationCodeInEventList() {
    // User 1 listing their own events should see invitation codes
    given()
        .header("Authorization", "Bearer " + user1Token)
        .when()
        .get("/api/users/" + user1Id + "/events")
        .then()
        .statusCode(200)
        .body("size()", greaterThanOrEqualTo(1))
        .body("[0].invitationCode", notNullValue());
  }

  /**
   * Test CWE-284 fix: Non-owner cannot see invitation codes when listing another user's events.
   * This verifies that the listEventsByUser endpoint also protects invitation codes.
   */
  @Test
  @Order(11)
  public void testNonOwnerCannotSeeInvitationCodeInEventList() {
    // User 2 listing User 1's events should NOT see invitation codes
    given()
        .header("Authorization", "Bearer " + user2Token)
        .when()
        .get("/api/users/" + user1Id + "/events")
        .then()
        .statusCode(200)
        .body("size()", greaterThanOrEqualTo(1))
        .body("[0].invitationCode", nullValue());
  }

  /**
   * Test CWE-284 fix: Event owner can see attendees of their own event. Verifies access control for
   * the attendee list endpoint.
   */
  @Test
  @Order(12)
  public void testEventOwnerCanSeeAttendees() {
    // User 1 (owner) should be able to see attendees of their event
    given()
        .header("Authorization", "Bearer " + user1Token)
        .when()
        .get("/api/events/" + user1EventId + "/attendees")
        .then()
        .statusCode(200);
  }

  /**
   * Test CWE-284 fix: Non-owner and non-attendee cannot see attendees of an event. This tests the
   * access control for the attendee list - only owners and attendees should see the list.
   */
  @Test
  @Order(13)
  public void testNonOwnerNonAttendeeCannotSeeAttendees() {
    // User 2 (non-owner, non-attendee) should NOT be able to see attendees
    given()
        .header("Authorization", "Bearer " + user2Token)
        .when()
        .get("/api/events/" + user1EventId + "/attendees")
        .then()
        .statusCode(403);
  }

  /**
   * Test CWE-284 fix: Attendee (non-owner) can see attendees after joining. This tests that
   * attendees who are not the event owner can still access the attendee list.
   */
  @Test
  @Order(14)
  public void testAttendeeNonOwnerCanSeeAttendeesAfterJoining() {
    // First get the invitation code as the owner
    Response eventResponse =
        given()
            .header("Authorization", "Bearer " + user1Token)
            .when()
            .get("/api/events/" + user1EventId);
    String invitationCode = eventResponse.jsonPath().getString("invitationCode");

    // User 2 joins the event using the invitation code
    Response joinResponse =
        given()
            .header("Authorization", "Bearer " + user2Token)
            .contentType(ContentType.JSON)
            .body("{\"invitationCode\":\"" + invitationCode + "\"}")
            .when()
            .post("/api/events/join-by-code");

    // Accept either 201 (joined successfully) or 409 (already joined from previous test)
    int statusCode = joinResponse.getStatusCode();
    assertTrue(statusCode == 201 || statusCode == 409, "Expected 201 or 409, got " + statusCode);

    // User 2 (now an attendee but not owner) should be able to see attendees
    given()
        .header("Authorization", "Bearer " + user2Token)
        .when()
        .get("/api/events/" + user1EventId + "/attendees")
        .then()
        .statusCode(200)
        .body("size()", greaterThanOrEqualTo(1));
  }
}
