package app.aoki.quarkuscrud.resource;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

import app.aoki.quarkuscrud.generated.model.Event;
import app.aoki.quarkuscrud.generated.model.UserMeta;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.util.Map;
import org.junit.jupiter.api.*;

@QuarkusTest
public class AdditionalUsermetaApiTest {

  private String createGuestAndGetToken() {
    Response response = given().contentType(ContentType.JSON).post("/api/auth/guest");
    return response.getHeader("Authorization").substring(7);
  }

  private Long getUserId(String token) {
    Response me = given().header("Authorization", "Bearer " + token).get("/api/me");
    return me.jsonPath().getLong("id");
  }

  private void createProfile(String token) {
    given()
        .header("Authorization", "Bearer " + token)
        .contentType(ContentType.JSON)
        .body("{\"realName\": \"Test User\", \"displayName\": \"Tester\"}")
        .put("/api/me/profile")
        .then()
        .statusCode(200);
  }

  // ==================== User Profile Meta Tests ====================

  @Test
  public void testUserProfile_GetMeta_Success() {
    String token = createGuestAndGetToken();
    Long userId = getUserId(token);
    createProfile(token);

    given()
        .header("Authorization", "Bearer " + token)
        .get("/api/users/" + userId + "/profile/meta")
        .then()
        .statusCode(200);
  }

  @Test
  public void testUserProfile_UpdateMeta_Success() {
    String token = createGuestAndGetToken();
    Long userId = getUserId(token);
    createProfile(token);

    Map<String, Object> meta = Map.of("theme", "dark", "fontSize", 14);
    Map<String, Object> request = Map.of("usermeta", meta);

    given()
        .header("Authorization", "Bearer " + token)
        .contentType(ContentType.JSON)
        .body(request)
        .put("/api/users/" + userId + "/profile/meta")
        .then()
        .statusCode(200);

    // Verify persistence
    var response =
        given()
            .header("Authorization", "Bearer " + token)
            .get("/api/users/" + userId + "/profile/meta")
            .then()
            .statusCode(200)
            .extract()
            .as(UserMeta.class);

    Map<?, ?> metaData = (Map<?, ?>) response.getUsermeta();
    assertEquals("dark", metaData.get("theme"));
    assertEquals(14, metaData.get("fontSize"));
  }

  @Test
  public void testUserProfile_GetMeta_Forbidden() {
    String token1 = createGuestAndGetToken();
    String token2 = createGuestAndGetToken();
    Long userId1 = getUserId(token1);
    createProfile(token1);

    // token2 trying to access token1's profile meta
    given()
        .header("Authorization", "Bearer " + token2)
        .get("/api/users/" + userId1 + "/profile/meta")
        .then()
        .statusCode(403);
  }

  @Test
  public void testUserProfile_UpdateMeta_Forbidden() {
    String token1 = createGuestAndGetToken();
    String token2 = createGuestAndGetToken();
    Long userId1 = getUserId(token1);
    createProfile(token1);

    Map<String, Object> request = Map.of("usermeta", Map.of("hack", "attempt"));

    // token2 trying to update token1's profile meta
    given()
        .header("Authorization", "Bearer " + token2)
        .contentType(ContentType.JSON)
        .body(request)
        .put("/api/users/" + userId1 + "/profile/meta")
        .then()
        .statusCode(403);
  }

  @Test
  public void testUserProfile_GetMeta_NoProfile() {
    String token = createGuestAndGetToken();
    Long userId = getUserId(token);

    // No profile created yet
    given()
        .header("Authorization", "Bearer " + token)
        .get("/api/users/" + userId + "/profile/meta")
        .then()
        .statusCode(404);
  }

  // ==================== Event User Data Meta Tests ====================

  @Test
  public void testEventUserData_GetMeta_Success() {
    String token = createGuestAndGetToken();
    Long userId = getUserId(token);

    Event event =
        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body("{}")
            .post("/api/events")
            .then()
            .statusCode(201)
            .extract()
            .as(Event.class);

    given()
        .header("Authorization", "Bearer " + token)
        .get("/api/events/" + event.getId() + "/users/" + userId + "/meta")
        .then()
        .statusCode(200);
  }

  @Test
  public void testEventUserData_UpdateMeta_Success() {
    String token = createGuestAndGetToken();
    Long userId = getUserId(token);

    Event event =
        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body("{}")
            .post("/api/events")
            .then()
            .statusCode(201)
            .extract()
            .as(Event.class);

    Map<String, Object> meta = Map.of("score", 100, "level", "expert");
    Map<String, Object> request = Map.of("usermeta", meta);

    given()
        .header("Authorization", "Bearer " + token)
        .contentType(ContentType.JSON)
        .body(request)
        .put("/api/events/" + event.getId() + "/users/" + userId + "/meta")
        .then()
        .statusCode(200);

    // Verify
    var response =
        given()
            .header("Authorization", "Bearer " + token)
            .get("/api/events/" + event.getId() + "/users/" + userId + "/meta")
            .then()
            .statusCode(200)
            .extract()
            .as(UserMeta.class);

    Map<?, ?> metaData = (Map<?, ?>) response.getUsermeta();
    assertEquals(100, metaData.get("score"));
    assertEquals("expert", metaData.get("level"));
  }

  @Test
  public void testEventUserData_UpdateMeta_OtherUserForbidden() {
    String token1 = createGuestAndGetToken();
    String token2 = createGuestAndGetToken();
    Long userId1 = getUserId(token1);
    Long userId2 = getUserId(token2);

    Event event =
        given()
            .header("Authorization", "Bearer " + token1)
            .contentType(ContentType.JSON)
            .body("{}")
            .post("/api/events")
            .then()
            .statusCode(201)
            .extract()
            .as(Event.class);

    // Join event with token2
    given()
        .header("Authorization", "Bearer " + token2)
        .contentType(ContentType.JSON)
        .body("{\"invitationCode\": \"" + event.getInvitationCode() + "\"}")
        .post("/api/events/join-by-code")
        .then()
        .statusCode(201);

    Map<String, Object> request = Map.of("usermeta", Map.of("hack", "attempt"));

    // token2 trying to update token1's event user data
    given()
        .header("Authorization", "Bearer " + token2)
        .contentType(ContentType.JSON)
        .body(request)
        .put("/api/events/" + event.getId() + "/users/" + userId1 + "/meta")
        .then()
        .statusCode(403);
  }

  @Test
  public void testEventUserData_GetMeta_AttendeeCanRead() {
    String token1 = createGuestAndGetToken();
    String token2 = createGuestAndGetToken();
    Long userId1 = getUserId(token1);

    Event event =
        given()
            .header("Authorization", "Bearer " + token1)
            .contentType(ContentType.JSON)
            .body("{}")
            .post("/api/events")
            .then()
            .statusCode(201)
            .extract()
            .as(Event.class);

    // Join event with token2
    given()
        .header("Authorization", "Bearer " + token2)
        .contentType(ContentType.JSON)
        .body("{\"invitationCode\": \"" + event.getInvitationCode() + "\"}")
        .post("/api/events/join-by-code")
        .then()
        .statusCode(201);

    // token2 can read token1's event user data
    given()
        .header("Authorization", "Bearer " + token2)
        .get("/api/events/" + event.getId() + "/users/" + userId1 + "/meta")
        .then()
        .statusCode(200);
  }

  @Test
  public void testEventUserData_GetMeta_NonAttendeeForbidden() {
    String token1 = createGuestAndGetToken();
    String token2 = createGuestAndGetToken();
    Long userId1 = getUserId(token1);

    Event event =
        given()
            .header("Authorization", "Bearer " + token1)
            .contentType(ContentType.JSON)
            .body("{}")
            .post("/api/events")
            .then()
            .statusCode(201)
            .extract()
            .as(Event.class);

    // token2 is NOT an attendee, should get 403
    given()
        .header("Authorization", "Bearer " + token2)
        .get("/api/events/" + event.getId() + "/users/" + userId1 + "/meta")
        .then()
        .statusCode(403);
  }

  // ==================== Event Attendee Meta Tests ====================

  @Test
  public void testEventAttendee_GetMeta_Success() {
    String token = createGuestAndGetToken();
    Long userId = getUserId(token);

    Event event =
        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body("{}")
            .post("/api/events")
            .then()
            .statusCode(201)
            .extract()
            .as(Event.class);

    given()
        .header("Authorization", "Bearer " + token)
        .get("/api/events/" + event.getId() + "/attendees/" + userId + "/meta")
        .then()
        .statusCode(200);
  }

  @Test
  public void testEventAttendee_UpdateMeta_Success() {
    String token = createGuestAndGetToken();
    Long userId = getUserId(token);

    Event event =
        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body("{}")
            .post("/api/events")
            .then()
            .statusCode(201)
            .extract()
            .as(Event.class);

    Map<String, Object> meta = Map.of("role", "organizer", "priority", 1);
    Map<String, Object> request = Map.of("usermeta", meta);

    given()
        .header("Authorization", "Bearer " + token)
        .contentType(ContentType.JSON)
        .body(request)
        .put("/api/events/" + event.getId() + "/attendees/" + userId + "/meta")
        .then()
        .statusCode(200);

    // Verify
    var response =
        given()
            .header("Authorization", "Bearer " + token)
            .get("/api/events/" + event.getId() + "/attendees/" + userId + "/meta")
            .then()
            .statusCode(200)
            .extract()
            .as(UserMeta.class);

    Map<?, ?> metaData = (Map<?, ?>) response.getUsermeta();
    assertEquals("organizer", metaData.get("role"));
    assertEquals(1, metaData.get("priority"));
  }

  @Test
  public void testEventAttendee_UpdateMeta_AllAttendeesCanUpdate() {
    String token1 = createGuestAndGetToken();
    String token2 = createGuestAndGetToken();
    Long userId1 = getUserId(token1);
    Long userId2 = getUserId(token2);

    Event event =
        given()
            .header("Authorization", "Bearer " + token1)
            .contentType(ContentType.JSON)
            .body("{}")
            .post("/api/events")
            .then()
            .statusCode(201)
            .extract()
            .as(Event.class);

    // Join event with token2
    given()
        .header("Authorization", "Bearer " + token2)
        .contentType(ContentType.JSON)
        .body("{\"invitationCode\": \"" + event.getInvitationCode() + "\"}")
        .post("/api/events/join-by-code")
        .then()
        .statusCode(201);

    Map<String, Object> request = Map.of("usermeta", Map.of("note", "Team player"));

    // token2 can update token1's attendee meta (per authorization rules)
    given()
        .header("Authorization", "Bearer " + token2)
        .contentType(ContentType.JSON)
        .body(request)
        .put("/api/events/" + event.getId() + "/attendees/" + userId1 + "/meta")
        .then()
        .statusCode(200);
  }

  @Test
  public void testEventAttendee_GetMeta_NonAttendeeForbidden() {
    String token1 = createGuestAndGetToken();
    String token2 = createGuestAndGetToken();
    Long userId1 = getUserId(token1);

    Event event =
        given()
            .header("Authorization", "Bearer " + token1)
            .contentType(ContentType.JSON)
            .body("{}")
            .post("/api/events")
            .then()
            .statusCode(201)
            .extract()
            .as(Event.class);

    // token2 is NOT an attendee
    given()
        .header("Authorization", "Bearer " + token2)
        .get("/api/events/" + event.getId() + "/attendees/" + userId1 + "/meta")
        .then()
        .statusCode(403);
  }

  @Test
  public void testEventAttendee_UpdateMeta_NonAttendeeForbidden() {
    String token1 = createGuestAndGetToken();
    String token2 = createGuestAndGetToken();
    Long userId1 = getUserId(token1);

    Event event =
        given()
            .header("Authorization", "Bearer " + token1)
            .contentType(ContentType.JSON)
            .body("{}")
            .post("/api/events")
            .then()
            .statusCode(201)
            .extract()
            .as(Event.class);

    Map<String, Object> request = Map.of("usermeta", Map.of("hack", "attempt"));

    // token2 is NOT an attendee
    given()
        .header("Authorization", "Bearer " + token2)
        .contentType(ContentType.JSON)
        .body(request)
        .put("/api/events/" + event.getId() + "/attendees/" + userId1 + "/meta")
        .then()
        .statusCode(403);
  }

  // ==================== Complex Scenarios ====================

  @Test
  public void testUserProfile_ComplexNestedMetadata() {
    String token = createGuestAndGetToken();
    Long userId = getUserId(token);
    createProfile(token);

    Map<String, Object> nested =
        Map.of(
            "preferences",
            Map.of("notifications", Map.of("email", true, "push", false)),
            "history",
            java.util.List.of("item1", "item2", "item3"));

    Map<String, Object> request = Map.of("usermeta", nested);

    given()
        .header("Authorization", "Bearer " + token)
        .contentType(ContentType.JSON)
        .body(request)
        .put("/api/users/" + userId + "/profile/meta")
        .then()
        .statusCode(200);

    var response =
        given()
            .header("Authorization", "Bearer " + token)
            .get("/api/users/" + userId + "/profile/meta")
            .then()
            .statusCode(200)
            .extract()
            .as(UserMeta.class);

    assertNotNull(response.getUsermeta());
  }

  @Test
  public void testEventUserData_NullMetadata() {
    String token = createGuestAndGetToken();
    Long userId = getUserId(token);

    Event event =
        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body("{}")
            .post("/api/events")
            .then()
            .statusCode(201)
            .extract()
            .as(Event.class);

    // Map.of doesn't allow null values, so use HashMap instead
    Map<String, Object> request = new java.util.HashMap<>();
    request.put("usermeta", null);

    given()
        .header("Authorization", "Bearer " + token)
        .contentType(ContentType.JSON)
        .body(request)
        .put("/api/events/" + event.getId() + "/users/" + userId + "/meta")
        .then()
        .statusCode(200);
  }

  @Test
  public void testEventAttendee_MetadataOverwrite() {
    String token = createGuestAndGetToken();
    Long userId = getUserId(token);

    Event event =
        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body("{}")
            .post("/api/events")
            .then()
            .statusCode(201)
            .extract()
            .as(Event.class);

    // Set first metadata
    Map<String, Object> request1 = Map.of("usermeta", Map.of("key1", "value1"));
    given()
        .header("Authorization", "Bearer " + token)
        .contentType(ContentType.JSON)
        .body(request1)
        .put("/api/events/" + event.getId() + "/attendees/" + userId + "/meta")
        .then()
        .statusCode(200);

    // Overwrite
    Map<String, Object> request2 = Map.of("usermeta", Map.of("key2", "value2"));
    given()
        .header("Authorization", "Bearer " + token)
        .contentType(ContentType.JSON)
        .body(request2)
        .put("/api/events/" + event.getId() + "/attendees/" + userId + "/meta")
        .then()
        .statusCode(200);

    // Verify only key2 exists
    var response =
        given()
            .header("Authorization", "Bearer " + token)
            .get("/api/events/" + event.getId() + "/attendees/" + userId + "/meta")
            .then()
            .statusCode(200)
            .extract()
            .as(UserMeta.class);

    Map<?, ?> metaData = (Map<?, ?>) response.getUsermeta();
    assertFalse(metaData.containsKey("key1"));
    assertEquals("value2", metaData.get("key2"));
  }

  @Test
  public void testUserProfile_Unauthenticated() {
    String token = createGuestAndGetToken();
    Long userId = getUserId(token);
    createProfile(token);

    // No auth header
    given().get("/api/users/" + userId + "/profile/meta").then().statusCode(401);
  }

  @Test
  public void testEventUserData_Unauthenticated() {
    String token = createGuestAndGetToken();
    Long userId = getUserId(token);

    Event event =
        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body("{}")
            .post("/api/events")
            .then()
            .statusCode(201)
            .extract()
            .as(Event.class);

    // No auth header
    given()
        .get("/api/events/" + event.getId() + "/users/" + userId + "/meta")
        .then()
        .statusCode(401);
  }

  @Test
  public void testEventAttendee_Unauthenticated() {
    String token = createGuestAndGetToken();
    Long userId = getUserId(token);

    Event event =
        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body("{}")
            .post("/api/events")
            .then()
            .statusCode(201)
            .extract()
            .as(Event.class);

    // No auth header
    given()
        .get("/api/events/" + event.getId() + "/attendees/" + userId + "/meta")
        .then()
        .statusCode(401);
  }
}
