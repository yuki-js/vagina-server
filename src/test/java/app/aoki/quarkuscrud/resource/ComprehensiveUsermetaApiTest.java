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
public class ComprehensiveUsermetaApiTest {

  private String createGuestAndGetToken() {
    Response response = given().contentType(ContentType.JSON).post("/api/auth/guest");
    return response.getHeader("Authorization").substring(7);
  }

  private Long getUserId(String token) {
    Response me = given().header("Authorization", "Bearer " + token).get("/api/me");
    return me.jsonPath().getLong("id");
  }

  @Test
  public void testUser_GetMeta_Success() {
    String token = createGuestAndGetToken();
    Long userId = getUserId(token);

    given()
        .header("Authorization", "Bearer " + token)
        .when()
        .get("/api/users/" + userId + "/meta")
        .then()
        .statusCode(200);
  }

  @Test
  public void testUser_UpdateMeta_Success() {
    String token = createGuestAndGetToken();
    Long userId = getUserId(token);

    Map<String, Object> meta = Map.of("nickname", "TestUser1", "theme", "dark");
    Map<String, Object> request = Map.of("usermeta", meta);

    given()
        .header("Authorization", "Bearer " + token)
        .contentType(ContentType.JSON)
        .body(request)
        .when()
        .put("/api/users/" + userId + "/meta")
        .then()
        .statusCode(200);

    var response =
        given()
            .header("Authorization", "Bearer " + token)
            .get("/api/users/" + userId + "/meta")
            .then()
            .statusCode(200)
            .extract()
            .as(UserMeta.class);

    assertNotNull(response.getUsermeta());
    assertEquals("TestUser1", ((Map<?, ?>) response.getUsermeta()).get("nickname"));
  }

  @Test
  public void testUser_GetMeta_Forbidden() {
    String token1 = createGuestAndGetToken();
    String token2 = createGuestAndGetToken();
    Long userId1 = getUserId(token1);

    given()
        .header("Authorization", "Bearer " + token2)
        .get("/api/users/" + userId1 + "/meta")
        .then()
        .statusCode(403);
  }

  @Test
  public void testEvent_GetMeta_Success() {
    String token = createGuestAndGetToken();

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
        .get("/api/events/" + event.getId() + "/meta")
        .then()
        .statusCode(200);
  }

  @Test
  public void testEvent_UpdateMeta_Success() {
    String token = createGuestAndGetToken();

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

    Map<String, Object> meta = Map.of("theme", "party");
    Map<String, Object> request = Map.of("usermeta", meta);

    given()
        .header("Authorization", "Bearer " + token)
        .contentType(ContentType.JSON)
        .body(request)
        .put("/api/events/" + event.getId() + "/meta")
        .then()
        .statusCode(200);
  }

  @Test
  public void testFriendship_GetMeta_Success() {
    String token1 = createGuestAndGetToken();
    String token2 = createGuestAndGetToken();
    Long userId2 = getUserId(token2);

    given()
        .header("Authorization", "Bearer " + token1)
        .contentType(ContentType.JSON)
        .body("{}")
        .post("/api/users/" + userId2 + "/friendship")
        .then()
        .statusCode(200);

    given()
        .header("Authorization", "Bearer " + token1)
        .get("/api/friendships/" + userId2 + "/meta")
        .then()
        .statusCode(200);
  }

  @Test
  public void testFriendship_UpdateMeta_Success() {
    String token1 = createGuestAndGetToken();
    String token2 = createGuestAndGetToken();
    Long userId2 = getUserId(token2);

    given()
        .header("Authorization", "Bearer " + token1)
        .contentType(ContentType.JSON)
        .body("{}")
        .post("/api/users/" + userId2 + "/friendship")
        .then()
        .statusCode(200);

    Map<String, Object> meta = Map.of("note", "Best friend");
    Map<String, Object> request = Map.of("usermeta", meta);

    given()
        .header("Authorization", "Bearer " + token1)
        .contentType(ContentType.JSON)
        .body(request)
        .put("/api/friendships/" + userId2 + "/meta")
        .then()
        .statusCode(200);
  }

  // ==================== Additional Test Variants ====================

  @Test
  public void testUser_UpdateMeta_EmptyObject() {
    String token = createGuestAndGetToken();
    Long userId = getUserId(token);

    Map<String, Object> request = Map.of("usermeta", Map.of());

    given()
        .header("Authorization", "Bearer " + token)
        .contentType(ContentType.JSON)
        .body(request)
        .put("/api/users/" + userId + "/meta")
        .then()
        .statusCode(200);
  }

  @Test
  public void testUser_UpdateMeta_ComplexNested() {
    String token = createGuestAndGetToken();
    Long userId = getUserId(token);

    java.util.Map<String, Object> nested = new java.util.HashMap<>();
    nested.put("level1", Map.of("level2", Map.of("level3", "deep value")));
    nested.put("array", java.util.List.of(1, 2, 3));
    Map<String, Object> request = Map.of("usermeta", nested);

    given()
        .header("Authorization", "Bearer " + token)
        .contentType(ContentType.JSON)
        .body(request)
        .put("/api/users/" + userId + "/meta")
        .then()
        .statusCode(200);
  }

  @Test
  public void testEvent_UpdateMeta_VerifyPersistence() {
    String token = createGuestAndGetToken();

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

    Map<String, Object> meta = Map.of("color", "red", "priority", 5);
    Map<String, Object> request = Map.of("usermeta", meta);

    given()
        .header("Authorization", "Bearer " + token)
        .contentType(ContentType.JSON)
        .body(request)
        .put("/api/events/" + event.getId() + "/meta")
        .then()
        .statusCode(200);

    // Verify persistence
    var response =
        given()
            .header("Authorization", "Bearer " + token)
            .get("/api/events/" + event.getId() + "/meta")
            .then()
            .statusCode(200)
            .extract()
            .as(UserMeta.class);

    Map<?, ?> metaData = (Map<?, ?>) response.getUsermeta();
    assertEquals("red", metaData.get("color"));
    assertEquals(5, metaData.get("priority"));
  }

  @Test
  public void testEvent_GetMeta_NonExistentEvent() {
    String token = createGuestAndGetToken();

    given()
        .header("Authorization", "Bearer " + token)
        .get("/api/events/99999/meta")
        .then()
        .statusCode(404);
  }

  @Test
  public void testFriendship_UpdateMeta_VerifyPersistence() {
    String token1 = createGuestAndGetToken();
    String token2 = createGuestAndGetToken();
    Long userId2 = getUserId(token2);

    given()
        .header("Authorization", "Bearer " + token1)
        .contentType(ContentType.JSON)
        .body("{}")
        .post("/api/users/" + userId2 + "/friendship")
        .then()
        .statusCode(200);

    Map<String, Object> meta = Map.of("closeness", 10, "tags", java.util.List.of("work", "friend"));
    Map<String, Object> request = Map.of("usermeta", meta);

    given()
        .header("Authorization", "Bearer " + token1)
        .contentType(ContentType.JSON)
        .body(request)
        .put("/api/friendships/" + userId2 + "/meta")
        .then()
        .statusCode(200);

    // Verify
    var response =
        given()
            .header("Authorization", "Bearer " + token1)
            .get("/api/friendships/" + userId2 + "/meta")
            .then()
            .statusCode(200)
            .extract()
            .as(UserMeta.class);

    Map<?, ?> metaData = (Map<?, ?>) response.getUsermeta();
    assertEquals(10, metaData.get("closeness"));
  }

  @Test
  public void testFriendship_GetMeta_NoFriendship() {
    String token1 = createGuestAndGetToken();
    String token2 = createGuestAndGetToken();
    Long userId2 = getUserId(token2);

    // No friendship exists
    given()
        .header("Authorization", "Bearer " + token1)
        .get("/api/friendships/" + userId2 + "/meta")
        .then()
        .statusCode(404);
  }

  @Test
  public void testUser_Unauthenticated() {
    String token = createGuestAndGetToken();
    Long userId = getUserId(token);

    // No auth header
    given().get("/api/users/" + userId + "/meta").then().statusCode(401);
  }

  @Test
  public void testEvent_Unauthenticated() {
    String token = createGuestAndGetToken();

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
    given().get("/api/events/" + event.getId() + "/meta").then().statusCode(401);
  }

  @Test
  public void testUser_UpdateMeta_Overwrite() {
    String token = createGuestAndGetToken();
    Long userId = getUserId(token);

    // Set first metadata
    Map<String, Object> request1 = Map.of("usermeta", Map.of("key1", "value1"));
    given()
        .header("Authorization", "Bearer " + token)
        .contentType(ContentType.JSON)
        .body(request1)
        .put("/api/users/" + userId + "/meta")
        .then()
        .statusCode(200);

    // Overwrite with new metadata
    Map<String, Object> request2 = Map.of("usermeta", Map.of("key2", "value2"));
    given()
        .header("Authorization", "Bearer " + token)
        .contentType(ContentType.JSON)
        .body(request2)
        .put("/api/users/" + userId + "/meta")
        .then()
        .statusCode(200);

    // Verify only key2 exists
    var response =
        given()
            .header("Authorization", "Bearer " + token)
            .get("/api/users/" + userId + "/meta")
            .then()
            .statusCode(200)
            .extract()
            .as(UserMeta.class);

    Map<?, ?> metaData = (Map<?, ?>) response.getUsermeta();
    assertFalse(metaData.containsKey("key1"));
    assertEquals("value2", metaData.get("key2"));
  }
}
