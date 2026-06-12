package app.aoki.quarkuscrud;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
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
 * Integration tests for Event CRUD operations. Tests create, read, update, delete, and join
 * functionality for events.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class EventCrudIntegrationTest {

  private static String jwtToken;
  private static Long eventId;

  @Test
  @Order(0)
  public void setup() {
    // Create a guest user for testing
    Response response = given().contentType(ContentType.JSON).post("/api/auth/guest");
    jwtToken = response.getHeader("Authorization").substring(7);
  }

  @Test
  @Order(1)
  public void testCreateEventWithoutAuthentication() {
    given()
        .contentType(ContentType.JSON)
        .body("{\"title\":\"Test Event\",\"description\":\"Test Description\"}")
        .when()
        .post("/api/events")
        .then()
        .statusCode(401)
        .body("error", equalTo("No JWT token found"));
  }

  @Test
  @Order(2)
  public void testCreateEventWithAuthentication() {
    Response response =
        given()
            .header("Authorization", "Bearer " + jwtToken)
            .contentType(ContentType.JSON)
            .body("{}")
            .when()
            .post("/api/events")
            .then()
            .statusCode(201) // Event creation returns 201 according to API spec
            .body("id", notNullValue())
            .body("invitationCode", notNullValue())
            .extract()
            .response();

    eventId = response.jsonPath().getLong("id");
    assertNotNull(eventId, "Event ID should not be null");
  }

  @Test
  @Order(3)
  public void testGetEventById() {
    given()
        .header("Authorization", "Bearer " + jwtToken)
        .when()
        .get("/api/events/" + eventId)
        .then()
        .statusCode(200)
        .body("id", equalTo(eventId.intValue()))
        .body("status", notNullValue())
        .body("initiatorId", notNullValue());
  }

  @Test
  @Order(4)
  public void testGetNonExistentEvent() {
    given()
        .header("Authorization", "Bearer " + jwtToken)
        .when()
        .get("/api/events/999999")
        .then()
        .statusCode(404);
  }

  @Test
  @Order(5)
  public void testListEventsByUser() {
    // Get current user's ID
    Response userResponse =
        given().header("Authorization", "Bearer " + jwtToken).when().get("/api/me");
    long userId = userResponse.jsonPath().getLong("id");

    // List events for this user
    given()
        .header("Authorization", "Bearer " + jwtToken)
        .when()
        .get("/api/users/" + userId + "/events")
        .then()
        .statusCode(200)
        .body("size()", greaterThanOrEqualTo(1));
  }

  @Test
  @Order(6)
  public void testJoinEventByCode() {
    // Create a new user to join the event
    Response newUserResponse = given().contentType(ContentType.JSON).post("/api/auth/guest");
    String newUserToken = newUserResponse.getHeader("Authorization").substring(7);

    // Get the invitation code for the event
    Response eventResponse =
        given().header("Authorization", "Bearer " + jwtToken).when().get("/api/events/" + eventId);
    String invitationCode = eventResponse.jsonPath().getString("invitationCode");

    // Join the event using the code
    given()
        .header("Authorization", "Bearer " + newUserToken)
        .contentType(ContentType.JSON)
        .body("{\"invitationCode\":\"" + invitationCode + "\"}")
        .when()
        .post("/api/events/join-by-code")
        .then()
        .statusCode(201) // Joining returns 201 according to API spec
        .body("id", notNullValue());
  }

  @Test
  @Order(7)
  public void testListEventAttendees() {
    given()
        .header("Authorization", "Bearer " + jwtToken)
        .when()
        .get("/api/events/" + eventId + "/attendees")
        .then()
        .statusCode(200)
        .body("size()", greaterThanOrEqualTo(1));
  }

  @Test
  @Order(8)
  public void testJoinEventTwiceReturnsConflict() {
    // Create a new user
    Response newUserResponse = given().contentType(ContentType.JSON).post("/api/auth/guest");
    String newUserToken = newUserResponse.getHeader("Authorization").substring(7);

    // Get the invitation code
    Response eventResponse =
        given().header("Authorization", "Bearer " + jwtToken).when().get("/api/events/" + eventId);
    String invitationCode = eventResponse.jsonPath().getString("invitationCode");

    // Join the event first time
    given()
        .header("Authorization", "Bearer " + newUserToken)
        .contentType(ContentType.JSON)
        .body("{\"invitationCode\":\"" + invitationCode + "\"}")
        .when()
        .post("/api/events/join-by-code")
        .then()
        .statusCode(201);

    // Try to join again - should return 409 conflict
    given()
        .header("Authorization", "Bearer " + newUserToken)
        .contentType(ContentType.JSON)
        .body("{\"invitationCode\":\"" + invitationCode + "\"}")
        .when()
        .post("/api/events/join-by-code")
        .then()
        .statusCode(409); // Duplicate join should be rejected
  }

  @Test
  @Order(9)
  public void testListAttendedEventsByUser() {
    // Create a new user to join the event
    Response newUserResponse = given().contentType(ContentType.JSON).post("/api/auth/guest");
    String newUserToken = newUserResponse.getHeader("Authorization").substring(7);

    // Get the invitation code for the event
    Response eventResponse =
        given().header("Authorization", "Bearer " + jwtToken).when().get("/api/events/" + eventId);
    String invitationCode = eventResponse.jsonPath().getString("invitationCode");

    // Join the event using the code
    given()
        .header("Authorization", "Bearer " + newUserToken)
        .contentType(ContentType.JSON)
        .body("{\"invitationCode\":\"" + invitationCode + "\"}")
        .when()
        .post("/api/events/join-by-code")
        .then()
        .statusCode(201);

    // List attended events for the current user
    given()
        .header("Authorization", "Bearer " + newUserToken)
        .when()
        .get("/api/me/attended-events")
        .then()
        .statusCode(200)
        .body("size()", greaterThanOrEqualTo(1))
        .body("[0].id", equalTo(eventId.intValue()));
  }

  @Test
  @Order(10)
  public void testListAttendedEventsForUserWithNoEvents() {
    // Create a new user who hasn't joined any events
    Response newUserResponse = given().contentType(ContentType.JSON).post("/api/auth/guest");
    String newUserToken = newUserResponse.getHeader("Authorization").substring(7);

    // List attended events - should be empty
    given()
        .header("Authorization", "Bearer " + newUserToken)
        .when()
        .get("/api/me/attended-events")
        .then()
        .statusCode(200)
        .body("size()", equalTo(0));
  }

  @Test
  @Order(11)
  public void testMyAttendedEventsEndpointWorksCorrectly() {
    // Create a user
    Response userResponse = given().contentType(ContentType.JSON).post("/api/auth/guest");
    String userToken = userResponse.getHeader("Authorization").substring(7);

    // User creates an event
    Response eventResponse =
        given()
            .header("Authorization", "Bearer " + userToken)
            .contentType(ContentType.JSON)
            .body("{}")
            .when()
            .post("/api/events")
            .then()
            .statusCode(201)
            .extract()
            .response();
    String invitationCode = eventResponse.jsonPath().getString("invitationCode");
    long createdEventId = eventResponse.jsonPath().getLong("id");

    // Create another user and join the event
    Response otherUserResponse = given().contentType(ContentType.JSON).post("/api/auth/guest");
    String otherUserToken = otherUserResponse.getHeader("Authorization").substring(7);

    given()
        .header("Authorization", "Bearer " + otherUserToken)
        .contentType(ContentType.JSON)
        .body("{\"invitationCode\":\"" + invitationCode + "\"}")
        .when()
        .post("/api/events/join-by-code")
        .then()
        .statusCode(201);

    // Other user should be able to view their own attended events
    given()
        .header("Authorization", "Bearer " + otherUserToken)
        .when()
        .get("/api/me/attended-events")
        .then()
        .statusCode(200)
        .body("size()", greaterThanOrEqualTo(1))
        .body("[0].id", equalTo(((Long) createdEventId).intValue()));
  }

  @Test
  @Order(15)
  public void testDeleteEventWithoutAuthentication() {
    given()
        .when()
        .delete("/api/events/" + eventId)
        .then()
        .statusCode(401)
        .body("error", equalTo("No JWT token found"));
  }

  @Test
  @Order(16)
  public void testDeleteEventByNonInitiator() {
    // Create a new user who is not the event initiator
    Response newUserResponse = given().contentType(ContentType.JSON).post("/api/auth/guest");
    String newUserToken = newUserResponse.getHeader("Authorization").substring(7);

    // Try to delete the event - should fail with 403 Forbidden
    given()
        .header("Authorization", "Bearer " + newUserToken)
        .when()
        .delete("/api/events/" + eventId)
        .then()
        .statusCode(403)
        .body("error", equalTo("Only the event initiator can delete the event"));
  }

  @Test
  @Order(17)
  public void testDeleteNonExistentEvent() {
    given()
        .header("Authorization", "Bearer " + jwtToken)
        .when()
        .delete("/api/events/999999")
        .then()
        .statusCode(404)
        .body("error", equalTo("Event not found"));
  }

  @Test
  @Order(18)
  public void testDeleteEventByInitiator() {
    // Create a new event to delete
    Response response =
        given()
            .header("Authorization", "Bearer " + jwtToken)
            .contentType(ContentType.JSON)
            .body("{}")
            .when()
            .post("/api/events")
            .then()
            .statusCode(201)
            .body("id", notNullValue())
            .extract()
            .response();

    Long deleteEventId = response.jsonPath().getLong("id");

    // Delete the event
    given()
        .header("Authorization", "Bearer " + jwtToken)
        .when()
        .delete("/api/events/" + deleteEventId)
        .then()
        .statusCode(204); // No content on successful delete

    // Verify the event is marked as deleted (should still be retrievable but with DELETED status)
    given()
        .header("Authorization", "Bearer " + jwtToken)
        .when()
        .get("/api/events/" + deleteEventId)
        .then()
        .statusCode(200)
        .body("status", equalTo("deleted"));
  }

  @Test
  @Order(18)
  public void testUpdateEvent() {
    // Create a new event for update test
    Response createResponse =
        given()
            .header("Authorization", "Bearer " + jwtToken)
            .contentType(ContentType.JSON)
            .body("{\"meta\": {\"name\": \"Original Event\"}}")
            .when()
            .post("/api/events")
            .then()
            .statusCode(201)
            .body("id", notNullValue())
            .extract()
            .response();

    Long updateEventId = createResponse.jsonPath().getLong("id");

    // Update the event
    given()
        .header("Authorization", "Bearer " + jwtToken)
        .contentType(ContentType.JSON)
        .body(
            "{\"status\": \"active\", \"meta\": {\"name\": \"Updated Event\", \"description\":"
                + " \"New description\"}}")
        .when()
        .put("/api/events/" + updateEventId)
        .then()
        .statusCode(200)
        .body("id", equalTo(updateEventId.intValue()))
        .body("status", equalTo("active"))
        .body("meta.name", equalTo("Updated Event"))
        .body("meta.description", equalTo("New description"));

    // Verify the update persisted
    given()
        .header("Authorization", "Bearer " + jwtToken)
        .when()
        .get("/api/events/" + updateEventId)
        .then()
        .statusCode(200)
        .body("status", equalTo("active"))
        .body("meta.name", equalTo("Updated Event"));
  }

  @Test
  @Order(19)
  public void testUpdateEventUnauthorized() {
    // Create a second user
    Response response2 = given().contentType(ContentType.JSON).post("/api/auth/guest");
    String jwtToken2 = response2.getHeader("Authorization").substring(7);

    // Create an event with the first user
    Response createResponse =
        given()
            .header("Authorization", "Bearer " + jwtToken)
            .contentType(ContentType.JSON)
            .body("{}")
            .when()
            .post("/api/events")
            .then()
            .statusCode(201)
            .extract()
            .response();

    Long eventIdToUpdate = createResponse.jsonPath().getLong("id");

    // Try to update with the second user (should fail)
    given()
        .header("Authorization", "Bearer " + jwtToken2)
        .contentType(ContentType.JSON)
        .body("{\"status\": \"active\"}")
        .when()
        .put("/api/events/" + eventIdToUpdate)
        .then()
        .statusCode(403)
        .body("error", equalTo("Only the event initiator can update the event"));
  }

  @Test
  @Order(20)
  public void testUpdateNonExistentEvent() {
    given()
        .header("Authorization", "Bearer " + jwtToken)
        .contentType(ContentType.JSON)
        .body("{\"status\": \"active\"}")
        .when()
        .put("/api/events/999999")
        .then()
        .statusCode(404)
        .body("error", equalTo("Event not found"));
  }

  @Test
  @Order(21)
  public void testUpdateEventPartialUpdate() {
    // Create a new event
    Response createResponse =
        given()
            .header("Authorization", "Bearer " + jwtToken)
            .contentType(ContentType.JSON)
            .body("{\"meta\": {\"name\": \"Partial Update Test\", \"venue\": \"Online\"}}")
            .when()
            .post("/api/events")
            .then()
            .statusCode(201)
            .extract()
            .response();

    Long eventIdPartial = createResponse.jsonPath().getLong("id");

    // Update only the meta (status should remain unchanged)
    given()
        .header("Authorization", "Bearer " + jwtToken)
        .contentType(ContentType.JSON)
        .body("{\"meta\": {\"name\": \"Updated Name Only\"}}")
        .when()
        .put("/api/events/" + eventIdPartial)
        .then()
        .statusCode(200)
        .body("meta.name", equalTo("Updated Name Only"))
        .body("status", notNullValue()); // Status should still exist

    // Update only the status (meta should remain unchanged)
    given()
        .header("Authorization", "Bearer " + jwtToken)
        .contentType(ContentType.JSON)
        .body("{\"status\": \"ended\"}")
        .when()
        .put("/api/events/" + eventIdPartial)
        .then()
        .statusCode(200)
        .body("status", equalTo("ended"))
        .body("meta.name", equalTo("Updated Name Only")); // Meta should still have the updated name
  }
}
