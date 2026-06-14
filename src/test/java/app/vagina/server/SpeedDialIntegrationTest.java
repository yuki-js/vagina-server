package app.vagina.server;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import app.vagina.server.service.AuthService;
import app.vagina.server.support.HarigataOidcMockServerResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Integration scenarios for the persisted Speed Dial API.
 *
 * <p>Actor flow:
 * 1. A human user signs in through OIDC from the client.
 * 2. The client opens the speed dial management screen before a call starts.
 * 3. The user creates, retrieves, lists, and deletes voice-agent presets.
 *
 * <p>These tests verify product semantics, not just transport success:
 * default preset materialization, path/body ID consistency, and protection of the
 * reserved default preset.
 */
@QuarkusTest
@QuarkusTestResource(HarigataOidcMockServerResource.class)
@TestMethodOrder(OrderAnnotation.class)
public class SpeedDialIntegrationTest {
  private static final String REDIRECT_URI = "https://example.com/callback";
  private static final String CODE_VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
  private static final String CODE_CHALLENGE = AuthService.generateS256CodeChallenge(CODE_VERIFIER);
  private static final String SPEED_DIAL_ID = "work-assistant";

  private static String accessToken;

  /**
   * Actor setup: the user completes the same Harigata OIDC browser flow that the
   * client uses before any authenticated speed-dial operation can happen.
   */
  @Test
  @Order(1)
  public void setupAuthenticatedUser() {
    Response startResponse =
        given()
            .contentType(ContentType.JSON)
            .body(
                Map.of(
                    "clientType",
                    "web",
                    "redirectUri",
                    REDIRECT_URI,
                    "codeChallenge",
                    CODE_CHALLENGE,
                    "codeChallengeMethod",
                    "S256"))
            .when()
            .post("/api/auth/oidc/harigata/start")
            .then()
            .statusCode(200)
            .extract()
            .response();

    String authorizationUrl = startResponse.jsonPath().getString("authorizationUrl");

    Response authorizeResponse =
        given()
            .redirects()
            .follow(false)
            .when()
            .get(authorizationUrl)
            .then()
            .statusCode(302)
            .extract()
            .response();

    RedirectPayload redirectPayload = parseRedirectPayload(authorizeResponse.getHeader("Location"));

    Response exchangeResponse =
        given()
            .contentType(ContentType.JSON)
            .body(
                Map.of(
                    "code",
                    redirectPayload.code(),
                    "state",
                    redirectPayload.state(),
                    "redirectUri",
                    REDIRECT_URI,
                    "codeVerifier",
                    CODE_VERIFIER))
            .when()
            .post("/api/auth/oidc/harigata/exchange")
            .then()
            .statusCode(200)
            .body("accessToken", notNullValue())
            .extract()
            .response();

    accessToken = exchangeResponse.jsonPath().getString("accessToken");
    assertNotNull(accessToken);
  }

  /**
   * Scenario: a freshly signed-in user opens the speed-dial screen before creating
   * any custom preset. The server should materialize the implicit default preset.
   */
  @Test
  @Order(2)
  public void testListSpeedDialsCreatesDefaultEntry() {
    given()
        .header("Authorization", "Bearer " + accessToken)
        .when()
        .get("/api/speed-dials")
        .then()
        .statusCode(200)
        .body("size()", equalTo(1))
        .body("[0].id", equalTo("default"))
        .body("[0].name", equalTo("Default"))
        .body("[0].voice", equalTo("alloy"));
  }

  /**
   * Scenario: the user creates a work-focused voice preset from the client settings
   * flow, then reopens the preset detail view and expects the same fields back.
   */
  @Test
  @Order(3)
  public void testSaveAndGetSpeedDial() {
    given()
        .header("Authorization", "Bearer " + accessToken)
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "id",
                SPEED_DIAL_ID,
                "name",
                "Work Assistant",
                "systemPrompt",
                "You help with work tasks.",
                "description",
                "Work-focused preset",
                "iconEmoji",
                "💼",
                "voice",
                "alloy",
                "enabledTools",
                Map.of("document_read", true, "document_patch", false)))
        .when()
        .put("/api/speed-dials/" + SPEED_DIAL_ID)
        .then()
        .statusCode(200)
        .body("id", equalTo(SPEED_DIAL_ID))
        .body("name", equalTo("Work Assistant"))
        .body("systemPrompt", equalTo("You help with work tasks."))
        .body("enabledTools.document_read", equalTo(true))
        .body("enabledTools.document_patch", equalTo(false));

    given()
        .header("Authorization", "Bearer " + accessToken)
        .when()
        .get("/api/speed-dials/" + SPEED_DIAL_ID)
        .then()
        .statusCode(200)
        .body("id", equalTo(SPEED_DIAL_ID))
        .body("name", equalTo("Work Assistant"))
        .body("iconEmoji", equalTo("💼"));
  }

  /**
   * Scenario: after saving one custom preset, the list screen should contain both the
   * protected default preset and the newly created user preset.
   */
  @Test
  @Order(4)
  public void testListShowsDefaultAndSavedEntries() {
    given()
        .header("Authorization", "Bearer " + accessToken)
        .when()
        .get("/api/speed-dials")
        .then()
        .statusCode(200)
        .body("size()", equalTo(2))
        .body("id", hasSize(2));
  }

  /**
   * Scenario: a buggy or hostile client sends a body whose ID disagrees with the
   * path target. The server must reject this instead of silently overwriting another preset.
   */
  @Test
  @Order(5)
  public void testPathBodyMismatchRejected() {
    given()
        .header("Authorization", "Bearer " + accessToken)
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "id",
                "different-id",
                "name",
                "Mismatch",
                "systemPrompt",
                "Mismatch prompt",
                "voice",
                "alloy",
                "enabledTools",
                Map.of()))
        .when()
        .put("/api/speed-dials/" + SPEED_DIAL_ID)
        .then()
        .statusCode(400)
        .body("message", equalTo("Path/body speed dial id mismatch"));
  }

  /**
   * Scenario: the client attempts destructive mutation of the reserved default preset.
   * Product rules require the default preset to stay present, named, and undeletable.
   */
  @Test
  @Order(6)
  public void testDefaultSpeedDialCannotBeDeletedOrRenamed() {
    given()
        .header("Authorization", "Bearer " + accessToken)
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "id",
                "default",
                "name",
                "Renamed Default",
                "systemPrompt",
                "You are a helpful AI assistant.",
                "description",
                "Default voice assistant",
                "voice",
                "alloy",
                "enabledTools",
                Map.of()))
        .when()
        .put("/api/speed-dials/default")
        .then()
        .statusCode(409)
        .body("message", equalTo("Default speed dial cannot be renamed"));

    given()
        .header("Authorization", "Bearer " + accessToken)
        .when()
        .delete("/api/speed-dials/default")
        .then()
        .statusCode(409)
        .body("message", equalTo("Default speed dial cannot be deleted"));
  }

  /**
   * Scenario: the user deletes a normal custom preset from the management screen.
   * A subsequent detail fetch should confirm that the preset is gone.
   */
  @Test
  @Order(7)
  public void testDeleteSpeedDial() {
    given()
        .header("Authorization", "Bearer " + accessToken)
        .when()
        .delete("/api/speed-dials/" + SPEED_DIAL_ID)
        .then()
        .statusCode(204);

    given()
        .header("Authorization", "Bearer " + accessToken)
        .when()
        .get("/api/speed-dials/" + SPEED_DIAL_ID)
        .then()
        .statusCode(404)
        .body("message", equalTo("Speed dial not found"));
  }

  private RedirectPayload parseRedirectPayload(String locationHeader) {
    String decodedLocation = URLDecoder.decode(locationHeader, StandardCharsets.UTF_8);
    URI redirectedUri = URI.create(decodedLocation);
    Map<String, String> queryParams = parseQueryParams(redirectedUri.getRawQuery());
    return new RedirectPayload(decodedLocation, queryParams.get("code"), queryParams.get("state"));
  }

  private Map<String, String> parseQueryParams(String rawQuery) {
    Map<String, String> values = new LinkedHashMap<>();
    if (rawQuery == null || rawQuery.isBlank()) {
      return values;
    }

    for (String pair : rawQuery.split("&")) {
      String[] parts = pair.split("=", 2);
      String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
      String value = parts.length > 1 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
      values.put(key, value);
    }
    return values;
  }

  private record RedirectPayload(String location, String code, String state) {}
}
