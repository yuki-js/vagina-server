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
public class EdgeCaseUsermetaApiTest {

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

  // ==================== Large Metadata Tests ====================

  @Test
  public void testUser_LargeMetadata() {
    String token = createGuestAndGetToken();
    Long userId = getUserId(token);

    // Create large metadata object
    java.util.Map<String, Object> largeMeta = new java.util.HashMap<>();
    for (int i = 0; i < 100; i++) {
      largeMeta.put("key" + i, "value" + i);
    }

    Map<String, Object> request = Map.of("usermeta", largeMeta);

    given()
        .header("Authorization", "Bearer " + token)
        .contentType(ContentType.JSON)
        .body(request)
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

    Map<?, ?> metaData = (Map<?, ?>) response.getUsermeta();
    assertEquals(100, metaData.size());
  }

  @Test
  public void testEvent_VeryNestedMetadata() {
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

    // Create deeply nested structure
    Map<String, Object> nested =
        Map.of(
            "level1",
            Map.of(
                "level2",
                Map.of(
                    "level3",
                    Map.of("level4", Map.of("level5", Map.of("deep", "value", "number", 42))))));

    Map<String, Object> request = Map.of("usermeta", nested);

    given()
        .header("Authorization", "Bearer " + token)
        .contentType(ContentType.JSON)
        .body(request)
        .put("/api/events/" + event.getId() + "/meta")
        .then()
        .statusCode(200);

    var response =
        given()
            .header("Authorization", "Bearer " + token)
            .get("/api/events/" + event.getId() + "/meta")
            .then()
            .statusCode(200)
            .extract()
            .as(UserMeta.class);

    assertNotNull(response.getUsermeta());
  }

  // ==================== Special Characters and Unicode ====================

  @Test
  public void testUser_UnicodeMetadata() {
    String token = createGuestAndGetToken();
    Long userId = getUserId(token);

    Map<String, Object> meta =
        Map.of(
            "japanese", "こんにちは世界",
            "emoji", "🎉🌟✨",
            "chinese", "你好世界",
            "korean", "안녕하세요");

    Map<String, Object> request = Map.of("usermeta", meta);

    given()
        .header("Authorization", "Bearer " + token)
        .contentType(ContentType.JSON)
        .body(request)
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

    Map<?, ?> metaData = (Map<?, ?>) response.getUsermeta();
    assertEquals("こんにちは世界", metaData.get("japanese"));
    assertEquals("🎉🌟✨", metaData.get("emoji"));
  }

  @Test
  public void testEvent_SpecialCharactersInKeys() {
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

    java.util.Map<String, Object> meta = new java.util.HashMap<>();
    meta.put("key-with-dash", "value1");
    meta.put("key_with_underscore", "value2");
    meta.put("key.with.dots", "value3");
    meta.put("key with spaces", "value4");

    Map<String, Object> request = Map.of("usermeta", meta);

    given()
        .header("Authorization", "Bearer " + token)
        .contentType(ContentType.JSON)
        .body(request)
        .put("/api/events/" + event.getId() + "/meta")
        .then()
        .statusCode(200);
  }

  // ==================== Data Type Tests ====================

  @Test
  public void testUser_MixedDataTypes() {
    String token = createGuestAndGetToken();
    Long userId = getUserId(token);

    java.util.Map<String, Object> meta = new java.util.HashMap<>();
    meta.put("string", "text");
    meta.put("number", 123);
    meta.put("decimal", 45.67);
    meta.put("boolean", true);
    meta.put("nullValue", null);
    meta.put("array", java.util.List.of(1, 2, 3));
    meta.put("object", Map.of("nested", "value"));

    Map<String, Object> request = Map.of("usermeta", meta);

    given()
        .header("Authorization", "Bearer " + token)
        .contentType(ContentType.JSON)
        .body(request)
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

    Map<?, ?> metaData = (Map<?, ?>) response.getUsermeta();
    assertEquals("text", metaData.get("string"));
    assertEquals(123, metaData.get("number"));
    assertEquals(true, metaData.get("boolean"));
  }

  // ==================== Multiple Updates ====================

  @Test
  public void testUser_MultipleSequentialUpdates() {
    String token = createGuestAndGetToken();
    Long userId = getUserId(token);

    for (int i = 0; i < 5; i++) {
      Map<String, Object> request =
          Map.of("usermeta", Map.of("iteration", i, "timestamp", System.currentTimeMillis()));

      given()
          .header("Authorization", "Bearer " + token)
          .contentType(ContentType.JSON)
          .body(request)
          .put("/api/users/" + userId + "/meta")
          .then()
          .statusCode(200);
    }

    var response =
        given()
            .header("Authorization", "Bearer " + token)
            .get("/api/users/" + userId + "/meta")
            .then()
            .statusCode(200)
            .extract()
            .as(UserMeta.class);

    Map<?, ?> metaData = (Map<?, ?>) response.getUsermeta();
    assertEquals(4, metaData.get("iteration")); // Last iteration
  }

  // ==================== Empty and Minimal Cases ====================

  @Test
  public void testFriendship_EmptyMetadata() {
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

    Map<String, Object> request = Map.of("usermeta", Map.of());

    given()
        .header("Authorization", "Bearer " + token1)
        .contentType(ContentType.JSON)
        .body(request)
        .put("/api/friendships/" + userId2 + "/meta")
        .then()
        .statusCode(200);

    var response =
        given()
            .header("Authorization", "Bearer " + token1)
            .get("/api/friendships/" + userId2 + "/meta")
            .then()
            .statusCode(200)
            .extract()
            .as(UserMeta.class);

    Map<?, ?> metaData = (Map<?, ?>) response.getUsermeta();
    assertTrue(metaData.isEmpty());
  }

  @Test
  public void testUserProfile_MinimalMetadata() {
    String token = createGuestAndGetToken();
    Long userId = getUserId(token);
    createProfile(token);

    Map<String, Object> request = Map.of("usermeta", Map.of("a", "b"));

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

    Map<?, ?> metaData = (Map<?, ?>) response.getUsermeta();
    assertEquals(1, metaData.size());
    assertEquals("b", metaData.get("a"));
  }

  // ==================== Authorization Edge Cases ====================

  @Test
  public void testUser_InvalidToken() {
    String token = createGuestAndGetToken();
    Long userId = getUserId(token);

    given()
        .header("Authorization", "Bearer invalid_token_xyz")
        .get("/api/users/" + userId + "/meta")
        .then()
        .statusCode(401);
  }

  @Test
  public void testEvent_ExpiredToken() {
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

    // Try with malformed token
    given()
        .header("Authorization", "Bearer")
        .get("/api/events/" + event.getId() + "/meta")
        .then()
        .statusCode(401);
  }

  // ==================== Not Found Cases ====================

  @Test
  public void testUser_NonExistentUser() {
    String token = createGuestAndGetToken();

    given()
        .header("Authorization", "Bearer " + token)
        .get("/api/users/999999/meta")
        .then()
        .statusCode(403); // Forbidden because you can only access your own
  }

  @Test
  public void testEvent_NonExistentEvent() {
    String token = createGuestAndGetToken();

    given()
        .header("Authorization", "Bearer " + token)
        .get("/api/events/999999/meta")
        .then()
        .statusCode(404);
  }

  @Test
  public void testFriendship_NonExistentFriendship() {
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

  // ==================== Array Data Tests ====================

  @Test
  public void testUser_ArraysInMetadata() {
    String token = createGuestAndGetToken();
    Long userId = getUserId(token);

    java.util.Map<String, Object> meta = new java.util.HashMap<>();
    meta.put("tags", java.util.List.of("important", "work", "personal"));
    meta.put("numbers", java.util.List.of(1, 2, 3, 4, 5));
    java.util.List<Object> mixedList = new java.util.ArrayList<>();
    mixedList.add("text");
    mixedList.add(123);
    mixedList.add(true);
    mixedList.add(null);
    meta.put("mixed", mixedList);

    Map<String, Object> request = Map.of("usermeta", meta);

    given()
        .header("Authorization", "Bearer " + token)
        .contentType(ContentType.JSON)
        .body(request)
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

    Map<?, ?> metaData = (Map<?, ?>) response.getUsermeta();
    assertTrue(metaData.containsKey("tags"));
    assertTrue(metaData.get("tags") instanceof java.util.List);
  }
}
