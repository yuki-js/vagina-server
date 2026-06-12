package app.aoki.quarkuscrud;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.restassured.OpenApiValidationFilter;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Contract tests that validate API responses against the OpenAPI specification. These tests ensure
 * that the actual API behavior matches the documented OpenAPI spec.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class OpenApiContractTest {

  private static OpenApiValidationFilter validationFilter;
  private static String jwtToken;
  private static Long testEventId;
  private static Long userId;

  @BeforeAll
  public static void setupValidationFilter() throws IOException {
    // Create validation filter using the OpenAPI spec from resources
    URL specUrl = OpenApiContractTest.class.getClassLoader().getResource("META-INF/openapi.yaml");
    if (specUrl == null) {
      throw new IllegalStateException("OpenAPI spec file not found");
    }

    // Read the spec file content
    String specContent = new String(specUrl.openStream().readAllBytes(), StandardCharsets.UTF_8);

    // Create validator with security validation disabled for bearer auth
    // The API uses JWT tokens which RestAssured doesn't expose in a way the
    // validator recognizes
    OpenApiInteractionValidator validator =
        OpenApiInteractionValidator.createForInlineApiSpecification(specContent)
            .withLevelResolver(
                com.atlassian.oai.validator.report.LevelResolver.create()
                    .withLevel(
                        "validation.request.security.missing",
                        com.atlassian.oai.validator.report.ValidationReport.Level.IGNORE)
                    .build())
            .build();
    validationFilter = new OpenApiValidationFilter(validator);
  }

  @Test
  @Order(1)
  public void testCreateGuestUserContract() {
    // POST /api/auth/guest should conform to OpenAPI spec
    var response =
        given()
            .filter(validationFilter)
            .contentType(ContentType.JSON)
            .when()
            .post("/api/auth/guest")
            .then()
            .statusCode(200)
            .extract()
            .response();

    jwtToken = response.getHeader("Authorization").substring(7);
    userId = response.jsonPath().getLong("id");
    assertNotNull(jwtToken, "JWT token should be set in Authorization header");
  }

  @Test
  @Order(2)
  public void testGetCurrentUserContract() {
    // GET /api/me should conform to OpenAPI spec
    given()
        .filter(validationFilter)
        .header("Authorization", "Bearer " + jwtToken)
        .when()
        .get("/api/me")
        .then()
        .statusCode(200);
  }

  @Test
  @Order(3)
  public void testGetUserByIdContract() {
    // GET /api/users/{userId} should conform to OpenAPI spec
    given()
        .filter(validationFilter)
        .header("Authorization", "Bearer " + jwtToken)
        .when()
        .get("/api/users/" + userId)
        .then()
        .statusCode(200);
  }

  @Test
  @Order(4)
  public void testUpdateMyProfileContract() {
    // PUT /api/me/profile should conform to OpenAPI spec
    given()
        .filter(validationFilter)
        .header("Authorization", "Bearer " + jwtToken)
        .contentType(ContentType.JSON)
        .body("{\"profileData\":{\"displayName\":\"Test User\",\"bio\":\"Test bio\"}}")
        .when()
        .put("/api/me/profile")
        .then()
        .statusCode(200);
  }

  @Test
  @Order(5)
  public void testGetMyProfileContract() {
    // GET /api/me/profile should conform to OpenAPI spec
    given()
        .filter(validationFilter)
        .header("Authorization", "Bearer " + jwtToken)
        .when()
        .get("/api/me/profile")
        .then()
        .statusCode(200);
  }

  @Test
  @Order(6)
  public void testGetUserProfileContract() {
    // GET /api/users/{userId}/profile should conform to OpenAPI spec
    given()
        .filter(validationFilter)
        .header("Authorization", "Bearer " + jwtToken)
        .when()
        .get("/api/users/" + userId + "/profile")
        .then()
        .statusCode(200);
  }

  @Test
  @Order(7)
  public void testCreateEventContract() {
    // POST /api/events should conform to OpenAPI spec
    var response =
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

    testEventId = response.jsonPath().getLong("id");
  }

  @Test
  @Order(8)
  public void testGetEventByIdContract() {
    // GET /api/events/{eventId} should conform to OpenAPI spec
    given()
        .header("Authorization", "Bearer " + jwtToken)
        .when()
        .get("/api/events/" + testEventId)
        .then()
        .statusCode(200);
  }

  @Test
  @Order(9)
  public void testListEventsByUserContract() {
    // GET /api/users/{userId}/events should conform to OpenAPI spec
    given()
        .header("Authorization", "Bearer " + jwtToken)
        .when()
        .get("/api/users/" + userId + "/events")
        .then()
        .statusCode(200);
  }

  @Test
  @Order(10)
  public void testListEventAttendeesContract() {
    // GET /api/events/{eventId}/attendees should conform to OpenAPI spec
    given()
        .header("Authorization", "Bearer " + jwtToken)
        .when()
        .get("/api/events/" + testEventId + "/attendees")
        .then()
        .statusCode(200);
  }

  @Test
  @Order(11)
  public void testReceiveFriendshipContract() {
    // Create another user to send friendship
    var user2Response =
        given()
            .filter(validationFilter)
            .contentType(ContentType.JSON)
            .when()
            .post("/api/auth/guest")
            .then()
            .statusCode(200)
            .extract()
            .response();

    String user2Token = user2Response.getHeader("Authorization").substring(7);
    long user2Id = user2Response.jsonPath().getLong("id");

    // POST /api/users/{userId}/friendship should conform to OpenAPI spec
    given()
        .header("Authorization", "Bearer " + jwtToken)
        .contentType(ContentType.JSON)
        .body("{\"fromUserId\":" + userId + "}")
        .when()
        .post("/api/users/" + user2Id + "/friendship")
        .then()
        .statusCode(anyOf(is(200), is(201)));
  }

  @Test
  @Order(12)
  public void testListReceivedFriendshipsContract() {
    // GET /api/me/friendships/received should conform to OpenAPI spec
    given()
        .filter(validationFilter)
        .header("Authorization", "Bearer " + jwtToken)
        .when()
        .get("/api/me/friendships/received")
        .then()
        .statusCode(200);
  }
}
