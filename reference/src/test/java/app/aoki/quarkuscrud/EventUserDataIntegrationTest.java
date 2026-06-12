package app.aoki.quarkuscrud;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
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
 * Integration tests for Event User Data operations. Tests the CRUD operations for user-specific
 * data within events.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class EventUserDataIntegrationTest {

  private static String initiatorToken;
  private static String attendeeToken;
  private static String nonAttendeeToken;
  private static Long eventId;
  private static Long initiatorUserId;
  private static Long attendeeUserId;
  private static Long nonAttendeeUserId;

  @Test
  @Order(0)
  public void setup() {
    // Create initiator user (event creator)
    Response initiatorResponse = given().contentType(ContentType.JSON).post("/api/auth/guest");
    initiatorToken = initiatorResponse.getHeader("Authorization").substring(7);
    Response initiatorUserResponse =
        given().header("Authorization", "Bearer " + initiatorToken).when().get("/api/me");
    initiatorUserId = initiatorUserResponse.jsonPath().getLong("id");

    // Create attendee user
    Response attendeeResponse = given().contentType(ContentType.JSON).post("/api/auth/guest");
    attendeeToken = attendeeResponse.getHeader("Authorization").substring(7);
    Response attendeeUserResponse =
        given().header("Authorization", "Bearer " + attendeeToken).when().get("/api/me");
    attendeeUserId = attendeeUserResponse.jsonPath().getLong("id");

    // Create non-attendee user
    Response nonAttendeeResponse = given().contentType(ContentType.JSON).post("/api/auth/guest");
    nonAttendeeToken = nonAttendeeResponse.getHeader("Authorization").substring(7);
    Response nonAttendeeUserResponse =
        given().header("Authorization", "Bearer " + nonAttendeeToken).when().get("/api/me");
    nonAttendeeUserId = nonAttendeeUserResponse.jsonPath().getLong("id");

    // Create event as initiator
    Response eventResponse =
        given()
            .header("Authorization", "Bearer " + initiatorToken)
            .contentType(ContentType.JSON)
            .body("{\"meta\":{\"name\":\"Test Event\"}}")
            .when()
            .post("/api/events");
    eventId = eventResponse.jsonPath().getLong("id");
    String invitationCode = eventResponse.jsonPath().getString("invitationCode");
    assertNotNull(eventId, "Event ID should not be null");

    // Have the attendee join the event
    given()
        .header("Authorization", "Bearer " + attendeeToken)
        .contentType(ContentType.JSON)
        .body("{\"invitationCode\":\"" + invitationCode + "\"}")
        .when()
        .post("/api/events/join-by-code")
        .then()
        .statusCode(anyOf(is(200), is(201)));
  }

  @Test
  @Order(1)
  public void testGetUserDataWithoutAuthentication() {
    given()
        .when()
        .get("/api/events/" + eventId + "/users/" + attendeeUserId)
        .then()
        .statusCode(401)
        .body("error", equalTo("No JWT token found"));
  }

  @Test
  @Order(2)
  public void testGetUserDataInitiallyNotFound() {
    // User data doesn't exist yet, should return 404
    given()
        .header("Authorization", "Bearer " + attendeeToken)
        .when()
        .get("/api/events/" + eventId + "/users/" + attendeeUserId)
        .then()
        .statusCode(404)
        .body("error", equalTo("User data not found"));
  }

  @Test
  @Order(3)
  public void testGetUserDataAsNonAttendee() {
    // Non-attendee should get 403 when trying to access user data
    given()
        .header("Authorization", "Bearer " + nonAttendeeToken)
        .when()
        .get("/api/events/" + eventId + "/users/" + attendeeUserId)
        .then()
        .statusCode(403)
        .body("error", equalTo("Access denied. You are not an attendee of this event."));
  }

  @Test
  @Order(4)
  public void testUpdateOwnUserDataAsAttendee() {
    // Attendee updates their own user data
    given()
        .header("Authorization", "Bearer " + attendeeToken)
        .contentType(ContentType.JSON)
        .body("{\"userData\":{\"score\":100,\"team\":\"alpha\"}}")
        .when()
        .put("/api/events/" + eventId + "/users/" + attendeeUserId)
        .then()
        .statusCode(200)
        .body("id", notNullValue())
        .body("eventId", equalTo(eventId.intValue()))
        .body("userId", equalTo(attendeeUserId.intValue()))
        .body("userData.score", equalTo(100))
        .body("userData.team", equalTo("alpha"));
  }

  @Test
  @Order(5)
  public void testGetUserDataAfterUpdate() {
    // Attendee reads their own user data after update
    given()
        .header("Authorization", "Bearer " + attendeeToken)
        .when()
        .get("/api/events/" + eventId + "/users/" + attendeeUserId)
        .then()
        .statusCode(200)
        .body("id", notNullValue())
        .body("eventId", equalTo(eventId.intValue()))
        .body("userId", equalTo(attendeeUserId.intValue()))
        .body("userData.score", equalTo(100))
        .body("userData.team", equalTo("alpha"));
  }

  @Test
  @Order(6)
  public void testGetOtherUserDataAsAttendee() {
    // First, initiator needs to join the event as attendee and add their data
    // Get the invitation code
    Response eventResponse =
        given()
            .header("Authorization", "Bearer " + initiatorToken)
            .when()
            .get("/api/events/" + eventId);
    String invitationCode = eventResponse.jsonPath().getString("invitationCode");

    // Initiator joins their own event as attendee
    given()
        .header("Authorization", "Bearer " + initiatorToken)
        .contentType(ContentType.JSON)
        .body("{\"invitationCode\":\"" + invitationCode + "\"}")
        .when()
        .post("/api/events/join-by-code")
        .then()
        .statusCode(anyOf(is(200), is(201), is(409))); // 409 if already joined

    // Initiator adds their user data
    given()
        .header("Authorization", "Bearer " + initiatorToken)
        .contentType(ContentType.JSON)
        .body("{\"userData\":{\"score\":200,\"team\":\"beta\"}}")
        .when()
        .put("/api/events/" + eventId + "/users/" + initiatorUserId)
        .then()
        .statusCode(200);

    // Attendee reads initiator's data
    given()
        .header("Authorization", "Bearer " + attendeeToken)
        .when()
        .get("/api/events/" + eventId + "/users/" + initiatorUserId)
        .then()
        .statusCode(200)
        .body("userData.score", equalTo(200))
        .body("userData.team", equalTo("beta"));
  }

  @Test
  @Order(7)
  public void testUpdateOtherUserDataForbidden() {
    // Trying to update another user's data should return 403
    given()
        .header("Authorization", "Bearer " + attendeeToken)
        .contentType(ContentType.JSON)
        .body("{\"userData\":{\"score\":999}}")
        .when()
        .put("/api/events/" + eventId + "/users/" + initiatorUserId)
        .then()
        .statusCode(403)
        .body("error", equalTo("Access denied. You can only update your own data."));
  }

  @Test
  @Order(8)
  public void testUpdateUserDataAsNonAttendee() {
    // Non-attendee trying to update their own data in an event they haven't joined
    given()
        .header("Authorization", "Bearer " + nonAttendeeToken)
        .contentType(ContentType.JSON)
        .body("{\"userData\":{\"score\":50}}")
        .when()
        .put("/api/events/" + eventId + "/users/" + nonAttendeeUserId)
        .then()
        .statusCode(403)
        .body("error", equalTo("Access denied. You are not an attendee of this event."));
  }

  @Test
  @Order(9)
  public void testGetUserDataFromNonExistentEvent() {
    given()
        .header("Authorization", "Bearer " + attendeeToken)
        .when()
        .get("/api/events/999999/users/" + attendeeUserId)
        .then()
        .statusCode(404)
        .body("error", equalTo("Event not found"));
  }

  @Test
  @Order(10)
  public void testUpdateUserDataInNonExistentEvent() {
    given()
        .header("Authorization", "Bearer " + attendeeToken)
        .contentType(ContentType.JSON)
        .body("{\"userData\":{\"score\":50}}")
        .when()
        .put("/api/events/999999/users/" + attendeeUserId)
        .then()
        .statusCode(404)
        .body("error", equalTo("Event not found"));
  }

  @Test
  @Order(11)
  public void testUpdateUserDataWithRevisionMeta() {
    // Update user data with revision metadata
    given()
        .header("Authorization", "Bearer " + attendeeToken)
        .contentType(ContentType.JSON)
        .body(
            "{\"userData\":{\"score\":150,\"team\":\"gamma\"},\"revisionMeta\":{\"source\":\"mobile-app\"}}")
        .when()
        .put("/api/events/" + eventId + "/users/" + attendeeUserId)
        .then()
        .statusCode(200)
        .body("userData.score", equalTo(150))
        .body("userData.team", equalTo("gamma"))
        .body("revisionMeta.source", equalTo("mobile-app"));
  }

  @Test
  @Order(12)
  public void testUpdateUserDataWithSpecialCharacters() {
    // Update user data with special characters and unicode
    given()
        .header("Authorization", "Bearer " + attendeeToken)
        .contentType(ContentType.JSON)
        .body("{\"userData\":{\"nickname\":\"テストユーザー\",\"bio\":\"Bio with émojis 🎉\"}}")
        .when()
        .put("/api/events/" + eventId + "/users/" + attendeeUserId)
        .then()
        .statusCode(200)
        .body("userData.nickname", equalTo("テストユーザー"))
        .body("userData.bio", equalTo("Bio with émojis 🎉"));
  }
}
