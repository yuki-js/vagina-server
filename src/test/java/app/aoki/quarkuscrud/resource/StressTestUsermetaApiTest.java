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
public class StressTestUsermetaApiTest {

  private String createGuestAndGetToken() {
    Response response = given().contentType(ContentType.JSON).post("/api/auth/guest");
    return response.getHeader("Authorization").substring(7);
  }

  private Long getUserId(String token) {
    Response me = given().header("Authorization", "Bearer " + token).get("/api/me");
    return me.jsonPath().getLong("id");
  }

  // ==================== Repeated Operations ====================

  @Test
  public void testUser_RepeatedReads() {
    String token = createGuestAndGetToken();
    Long userId = getUserId(token);

    Map<String, Object> request = Map.of("usermeta", Map.of("test", "value"));
    given()
        .header("Authorization", "Bearer " + token)
        .contentType(ContentType.JSON)
        .body(request)
        .put("/api/users/" + userId + "/meta")
        .then()
        .statusCode(200);

    // Read 10 times
    for (int i = 0; i < 10; i++) {
      given()
          .header("Authorization", "Bearer " + token)
          .get("/api/users/" + userId + "/meta")
          .then()
          .statusCode(200);
    }
  }

  @Test
  public void testEvent_AlternatingReadWrite() {
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

    for (int i = 0; i < 5; i++) {
      // Write
      Map<String, Object> request = Map.of("usermeta", Map.of("counter", i));
      given()
          .header("Authorization", "Bearer " + token)
          .contentType(ContentType.JSON)
          .body(request)
          .put("/api/events/" + event.getId() + "/meta")
          .then()
          .statusCode(200);

      // Read
      var response =
          given()
              .header("Authorization", "Bearer " + token)
              .get("/api/events/" + event.getId() + "/meta")
              .then()
              .statusCode(200)
              .extract()
              .as(UserMeta.class);

      Map<?, ?> metaData = (Map<?, ?>) response.getUsermeta();
      assertEquals(i, metaData.get("counter"));
    }
  }

  // ==================== Multiple Resources ====================

  @Test
  public void testMultipleUsers_IndependentMetadata() {
    String token1 = createGuestAndGetToken();
    String token2 = createGuestAndGetToken();
    String token3 = createGuestAndGetToken();

    Long userId1 = getUserId(token1);
    Long userId2 = getUserId(token2);
    Long userId3 = getUserId(token3);

    // Set different metadata for each user
    given()
        .header("Authorization", "Bearer " + token1)
        .contentType(ContentType.JSON)
        .body(Map.of("usermeta", Map.of("user", "1")))
        .put("/api/users/" + userId1 + "/meta")
        .then()
        .statusCode(200);

    given()
        .header("Authorization", "Bearer " + token2)
        .contentType(ContentType.JSON)
        .body(Map.of("usermeta", Map.of("user", "2")))
        .put("/api/users/" + userId2 + "/meta")
        .then()
        .statusCode(200);

    given()
        .header("Authorization", "Bearer " + token3)
        .contentType(ContentType.JSON)
        .body(Map.of("usermeta", Map.of("user", "3")))
        .put("/api/users/" + userId3 + "/meta")
        .then()
        .statusCode(200);

    // Verify each user has correct metadata
    var response1 =
        given()
            .header("Authorization", "Bearer " + token1)
            .get("/api/users/" + userId1 + "/meta")
            .then()
            .statusCode(200)
            .extract()
            .as(UserMeta.class);
    assertEquals("1", ((Map<?, ?>) response1.getUsermeta()).get("user"));

    var response2 =
        given()
            .header("Authorization", "Bearer " + token2)
            .get("/api/users/" + userId2 + "/meta")
            .then()
            .statusCode(200)
            .extract()
            .as(UserMeta.class);
    assertEquals("2", ((Map<?, ?>) response2.getUsermeta()).get("user"));
  }

  @Test
  public void testMultipleEvents_IndependentMetadata() {
    String token = createGuestAndGetToken();

    // Create 3 events
    Event event1 =
        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body("{}")
            .post("/api/events")
            .then()
            .statusCode(201)
            .extract()
            .as(Event.class);

    Event event2 =
        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body("{}")
            .post("/api/events")
            .then()
            .statusCode(201)
            .extract()
            .as(Event.class);

    Event event3 =
        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body("{}")
            .post("/api/events")
            .then()
            .statusCode(201)
            .extract()
            .as(Event.class);

    // Set different metadata for each
    given()
        .header("Authorization", "Bearer " + token)
        .contentType(ContentType.JSON)
        .body(Map.of("usermeta", Map.of("event", "A")))
        .put("/api/events/" + event1.getId() + "/meta")
        .then()
        .statusCode(200);

    given()
        .header("Authorization", "Bearer " + token)
        .contentType(ContentType.JSON)
        .body(Map.of("usermeta", Map.of("event", "B")))
        .put("/api/events/" + event2.getId() + "/meta")
        .then()
        .statusCode(200);

    given()
        .header("Authorization", "Bearer " + token)
        .contentType(ContentType.JSON)
        .body(Map.of("usermeta", Map.of("event", "C")))
        .put("/api/events/" + event3.getId() + "/meta")
        .then()
        .statusCode(200);

    // Verify
    var responseA =
        given()
            .header("Authorization", "Bearer " + token)
            .get("/api/events/" + event1.getId() + "/meta")
            .then()
            .statusCode(200)
            .extract()
            .as(UserMeta.class);
    assertEquals("A", ((Map<?, ?>) responseA.getUsermeta()).get("event"));

    var responseB =
        given()
            .header("Authorization", "Bearer " + token)
            .get("/api/events/" + event2.getId() + "/meta")
            .then()
            .statusCode(200)
            .extract()
            .as(UserMeta.class);
    assertEquals("B", ((Map<?, ?>) responseB.getUsermeta()).get("event"));
  }

  // ==================== Cross-Resource Tests ====================

  @Test
  public void testUserAndEvent_SeparateMetadata() {
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

    // Set user metadata
    given()
        .header("Authorization", "Bearer " + token)
        .contentType(ContentType.JSON)
        .body(Map.of("usermeta", Map.of("type", "user")))
        .put("/api/users/" + userId + "/meta")
        .then()
        .statusCode(200);

    // Set event metadata
    given()
        .header("Authorization", "Bearer " + token)
        .contentType(ContentType.JSON)
        .body(Map.of("usermeta", Map.of("type", "event")))
        .put("/api/events/" + event.getId() + "/meta")
        .then()
        .statusCode(200);

    // Verify they're independent
    var userMeta =
        given()
            .header("Authorization", "Bearer " + token)
            .get("/api/users/" + userId + "/meta")
            .then()
            .statusCode(200)
            .extract()
            .as(UserMeta.class);
    assertEquals("user", ((Map<?, ?>) userMeta.getUsermeta()).get("type"));

    var eventMeta =
        given()
            .header("Authorization", "Bearer " + token)
            .get("/api/events/" + event.getId() + "/meta")
            .then()
            .statusCode(200)
            .extract()
            .as(UserMeta.class);
    assertEquals("event", ((Map<?, ?>) eventMeta.getUsermeta()).get("type"));
  }

  // ==================== Idempotency Tests ====================

  @Test
  public void testUser_IdempotentWrites() {
    String token = createGuestAndGetToken();
    Long userId = getUserId(token);

    Map<String, Object> request = Map.of("usermeta", Map.of("key", "value"));

    // Write same data 5 times
    for (int i = 0; i < 5; i++) {
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
    assertEquals("value", metaData.get("key"));
  }

  // ==================== Edge Case Values ====================

  @Test
  public void testUser_EmptyStringValues() {
    String token = createGuestAndGetToken();
    Long userId = getUserId(token);

    Map<String, Object> request = Map.of("usermeta", Map.of("empty", "", "blank", "   "));

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
    assertEquals("", metaData.get("empty"));
    assertEquals("   ", metaData.get("blank"));
  }

  @Test
  public void testEvent_NumericEdgeCases() {
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
    meta.put("zero", 0);
    meta.put("negative", -999);
    meta.put("large", 9999999999L);
    meta.put("decimal", 0.0000001);

    Map<String, Object> request = Map.of("usermeta", meta);

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

  // ==================== First Read Tests ====================

  @Test
  public void testUser_ReadBeforeWrite() {
    String token = createGuestAndGetToken();
    Long userId = getUserId(token);

    // Read before any write - should get empty/null metadata
    var response =
        given()
            .header("Authorization", "Bearer " + token)
            .get("/api/users/" + userId + "/meta")
            .then()
            .statusCode(200)
            .extract()
            .as(UserMeta.class);

    // Metadata should be null or empty
    assertTrue(
        response.getUsermeta() == null
            || (response.getUsermeta() instanceof Map
                && ((Map<?, ?>) response.getUsermeta()).isEmpty()));
  }

  @Test
  public void testEvent_ReadBeforeWrite() {
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

    // Read before any meta update
    var response =
        given()
            .header("Authorization", "Bearer " + token)
            .get("/api/events/" + event.getId() + "/meta")
            .then()
            .statusCode(200)
            .extract()
            .as(UserMeta.class);

    // Metadata should be null or empty
    assertTrue(
        response.getUsermeta() == null
            || (response.getUsermeta() instanceof Map
                && ((Map<?, ?>) response.getUsermeta()).isEmpty()));
  }

  // ==================== Rapid Fire Tests ====================

  @Test
  public void testUser_RapidFireUpdates() {
    String token = createGuestAndGetToken();
    Long userId = getUserId(token);

    // Rapidly update 20 times
    for (int i = 0; i < 20; i++) {
      given()
          .header("Authorization", "Bearer " + token)
          .contentType(ContentType.JSON)
          .body(Map.of("usermeta", Map.of("count", i)))
          .put("/api/users/" + userId + "/meta")
          .then()
          .statusCode(200);
    }

    // Final value should be 19
    var response =
        given()
            .header("Authorization", "Bearer " + token)
            .get("/api/users/" + userId + "/meta")
            .then()
            .statusCode(200)
            .extract()
            .as(UserMeta.class);

    Map<?, ?> metaData = (Map<?, ?>) response.getUsermeta();
    assertEquals(19, metaData.get("count"));
  }
}
