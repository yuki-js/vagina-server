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
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class IntegrationUsermetaWorkflowTest {

  private static String user1Token;
  private static String user2Token;
  private static Long user1Id;
  private static Long user2Id;
  private static Long eventId;

  private String createGuestAndGetToken() {
    Response response = given().contentType(ContentType.JSON).post("/api/auth/guest");
    return response.getHeader("Authorization").substring(7);
  }

  private Long getUserId(String token) {
    Response me = given().header("Authorization", "Bearer " + token).get("/api/me");
    return me.jsonPath().getLong("id");
  }

  // ==================== Setup Phase ====================

  @Test
  @Order(1)
  public void setup_CreateUsers() {
    user1Token = createGuestAndGetToken();
    user2Token = createGuestAndGetToken();
    user1Id = getUserId(user1Token);
    user2Id = getUserId(user2Token);

    assertNotNull(user1Token);
    assertNotNull(user2Token);
    assertNotNull(user1Id);
    assertNotNull(user2Id);
  }

  // ==================== User Metadata Workflow ====================

  @Test
  @Order(2)
  public void workflow_User1_SetInitialMetadata() {
    Map<String, Object> meta =
        Map.of("preferences", Map.of("theme", "dark", "language", "en"), "onboarded", true);

    Map<String, Object> request = Map.of("usermeta", meta);

    given()
        .header("Authorization", "Bearer " + user1Token)
        .contentType(ContentType.JSON)
        .body(request)
        .put("/api/users/" + user1Id + "/meta")
        .then()
        .statusCode(200);
  }

  @Test
  @Order(3)
  public void workflow_User1_VerifyMetadata() {
    var response =
        given()
            .header("Authorization", "Bearer " + user1Token)
            .get("/api/users/" + user1Id + "/meta")
            .then()
            .statusCode(200)
            .extract()
            .as(UserMeta.class);

    Map<?, ?> metaData = (Map<?, ?>) response.getUsermeta();
    assertTrue((Boolean) metaData.get("onboarded"));
  }

  @Test
  @Order(4)
  public void workflow_User2_CannotAccessUser1Meta() {
    given()
        .header("Authorization", "Bearer " + user2Token)
        .get("/api/users/" + user1Id + "/meta")
        .then()
        .statusCode(403);
  }

  // ==================== Profile Metadata Workflow ====================

  @Test
  @Order(5)
  public void workflow_User1_CreateProfile() {
    given()
        .header("Authorization", "Bearer " + user1Token)
        .contentType(ContentType.JSON)
        .body("{\"realName\": \"Alice\", \"displayName\": \"Alice_Dev\"}")
        .put("/api/me/profile")
        .then()
        .statusCode(200);
  }

  @Test
  @Order(6)
  public void workflow_User1_SetProfileMetadata() {
    Map<String, Object> meta = Map.of("profileCompleted", true, "avatar", "url://avatar1.png");

    Map<String, Object> request = Map.of("usermeta", meta);

    given()
        .header("Authorization", "Bearer " + user1Token)
        .contentType(ContentType.JSON)
        .body(request)
        .put("/api/users/" + user1Id + "/profile/meta")
        .then()
        .statusCode(200);
  }

  @Test
  @Order(7)
  public void workflow_User1_VerifyProfileMetadata() {
    var response =
        given()
            .header("Authorization", "Bearer " + user1Token)
            .get("/api/users/" + user1Id + "/profile/meta")
            .then()
            .statusCode(200)
            .extract()
            .as(UserMeta.class);

    Map<?, ?> metaData = (Map<?, ?>) response.getUsermeta();
    assertEquals("url://avatar1.png", metaData.get("avatar"));
  }

  // ==================== Event Metadata Workflow ====================

  @Test
  @Order(8)
  public void workflow_User1_CreateEvent() {
    Event event =
        given()
            .header("Authorization", "Bearer " + user1Token)
            .contentType(ContentType.JSON)
            .body("{}")
            .post("/api/events")
            .then()
            .statusCode(201)
            .extract()
            .as(Event.class);

    eventId = event.getId();
    assertNotNull(eventId);
  }

  @Test
  @Order(9)
  public void workflow_User1_SetEventMetadata() {
    Map<String, Object> meta =
        Map.of(
            "eventType",
            "quiz",
            "difficulty",
            "medium",
            "tags",
            java.util.List.of("fun", "educational"));

    Map<String, Object> request = Map.of("usermeta", meta);

    given()
        .header("Authorization", "Bearer " + user1Token)
        .contentType(ContentType.JSON)
        .body(request)
        .put("/api/events/" + eventId + "/meta")
        .then()
        .statusCode(200);
  }

  @Test
  @Order(10)
  public void workflow_User2_CannotAccessEventMeta_NotAttendee() {
    given()
        .header("Authorization", "Bearer " + user2Token)
        .get("/api/events/" + eventId + "/meta")
        .then()
        .statusCode(403); // Not an attendee
  }

  @Test
  @Order(11)
  public void workflow_User2_JoinEvent() {
    // Get invitation code first
    Event event =
        given()
            .header("Authorization", "Bearer " + user1Token)
            .get("/api/events/" + eventId)
            .then()
            .statusCode(200)
            .extract()
            .as(Event.class);

    String invCode = event.getInvitationCode();

    // Join with user2
    given()
        .header("Authorization", "Bearer " + user2Token)
        .contentType(ContentType.JSON)
        .body("{\"invitationCode\": \"" + invCode + "\"}")
        .post("/api/events/join-by-code")
        .then()
        .statusCode(201);
  }

  @Test
  @Order(12)
  public void workflow_User2_CanNowAccessEventMeta() {
    var response =
        given()
            .header("Authorization", "Bearer " + user2Token)
            .get("/api/events/" + eventId + "/meta")
            .then()
            .statusCode(200)
            .extract()
            .as(UserMeta.class);

    Map<?, ?> metaData = (Map<?, ?>) response.getUsermeta();
    assertEquals("quiz", metaData.get("eventType"));
  }

  @Test
  @Order(13)
  public void workflow_User2_CanAlsoUpdateEventMeta() {
    Map<String, Object> meta = Map.of("participantCount", 2, "status", "active");

    Map<String, Object> request = Map.of("usermeta", meta);

    given()
        .header("Authorization", "Bearer " + user2Token)
        .contentType(ContentType.JSON)
        .body(request)
        .put("/api/events/" + eventId + "/meta")
        .then()
        .statusCode(200);
  }

  // ==================== Friendship Metadata Workflow ====================

  @Test
  @Order(14)
  public void workflow_User1_SendFriendshipToUser2() {
    given()
        .header("Authorization", "Bearer " + user1Token)
        .contentType(ContentType.JSON)
        .body("{}")
        .post("/api/users/" + user2Id + "/friendship")
        .then()
        .statusCode(200);
  }

  @Test
  @Order(15)
  public void workflow_User1_SetFriendshipMetadata() {
    Map<String, Object> meta = Map.of("relationship", "colleague", "meetDate", "2026-01-14");

    Map<String, Object> request = Map.of("usermeta", meta);

    given()
        .header("Authorization", "Bearer " + user1Token)
        .contentType(ContentType.JSON)
        .body(request)
        .put("/api/friendships/" + user2Id + "/meta")
        .then()
        .statusCode(200);
  }

  @Test
  @Order(16)
  public void workflow_User1_VerifyFriendshipMetadata() {
    var response =
        given()
            .header("Authorization", "Bearer " + user1Token)
            .get("/api/friendships/" + user2Id + "/meta")
            .then()
            .statusCode(200)
            .extract()
            .as(UserMeta.class);

    Map<?, ?> metaData = (Map<?, ?>) response.getUsermeta();
    assertEquals("colleague", metaData.get("relationship"));
  }

  // ==================== Cross-Resource Verification ====================

  @Test
  @Order(17)
  public void workflow_User1_AllMetadataIndependent() {
    // Verify user meta
    var userMeta =
        given()
            .header("Authorization", "Bearer " + user1Token)
            .get("/api/users/" + user1Id + "/meta")
            .then()
            .statusCode(200)
            .extract()
            .as(UserMeta.class);

    Map<?, ?> userMetaData = (Map<?, ?>) userMeta.getUsermeta();
    assertTrue((Boolean) userMetaData.get("onboarded"));

    // Verify profile meta
    var profileMeta =
        given()
            .header("Authorization", "Bearer " + user1Token)
            .get("/api/users/" + user1Id + "/profile/meta")
            .then()
            .statusCode(200)
            .extract()
            .as(UserMeta.class);

    Map<?, ?> profileMetaData = (Map<?, ?>) profileMeta.getUsermeta();
    assertEquals("url://avatar1.png", profileMetaData.get("avatar"));

    // Verify event meta
    var eventMeta =
        given()
            .header("Authorization", "Bearer " + user1Token)
            .get("/api/events/" + eventId + "/meta")
            .then()
            .statusCode(200)
            .extract()
            .as(UserMeta.class);

    Map<?, ?> eventMetaData = (Map<?, ?>) eventMeta.getUsermeta();
    assertEquals(2, eventMetaData.get("participantCount")); // Updated by user2

    // Verify friendship meta
    var friendshipMeta =
        given()
            .header("Authorization", "Bearer " + user1Token)
            .get("/api/friendships/" + user2Id + "/meta")
            .then()
            .statusCode(200)
            .extract()
            .as(UserMeta.class);

    Map<?, ?> friendshipMetaData = (Map<?, ?>) friendshipMeta.getUsermeta();
    assertEquals("colleague", friendshipMetaData.get("relationship"));
  }

  // ==================== Update and Overwrite ====================

  @Test
  @Order(18)
  public void workflow_User1_UpdateUserMetadata() {
    Map<String, Object> meta =
        Map.of(
            "preferences",
            Map.of("theme", "light"),
            "onboarded",
            true,
            "lastLogin",
            System.currentTimeMillis());

    Map<String, Object> request = Map.of("usermeta", meta);

    given()
        .header("Authorization", "Bearer " + user1Token)
        .contentType(ContentType.JSON)
        .body(request)
        .put("/api/users/" + user1Id + "/meta")
        .then()
        .statusCode(200);

    // Verify update
    var response =
        given()
            .header("Authorization", "Bearer " + user1Token)
            .get("/api/users/" + user1Id + "/meta")
            .then()
            .statusCode(200)
            .extract()
            .as(UserMeta.class);

    Map<?, ?> metaData = (Map<?, ?>) response.getUsermeta();
    Map<?, ?> prefs = (Map<?, ?>) metaData.get("preferences");
    assertEquals("light", prefs.get("theme")); // Changed from dark to light
  }

  @Test
  @Order(19)
  public void workflow_FinalVerification() {
    // All metadata operations should still work
    given()
        .header("Authorization", "Bearer " + user1Token)
        .get("/api/users/" + user1Id + "/meta")
        .then()
        .statusCode(200);

    given()
        .header("Authorization", "Bearer " + user1Token)
        .get("/api/users/" + user1Id + "/profile/meta")
        .then()
        .statusCode(200);

    given()
        .header("Authorization", "Bearer " + user1Token)
        .get("/api/events/" + eventId + "/meta")
        .then()
        .statusCode(200);

    given()
        .header("Authorization", "Bearer " + user1Token)
        .get("/api/friendships/" + user2Id + "/meta")
        .then()
        .statusCode(200);
  }
}
