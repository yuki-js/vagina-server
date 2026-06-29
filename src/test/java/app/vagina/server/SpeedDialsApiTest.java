package app.vagina.server;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;

import app.vagina.server.support.HarigataOidcMockServerResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.util.Map;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(HarigataOidcMockServerResource.class)
class SpeedDialsApiTest {

  @Test
  void defaultSpeedDialGetsDefaultVoiceAgentId() {
    String token = VhrpAuthTestSupport.obtainValidJwt();

    given()
        .auth()
        .oauth2(token)
        .accept(ContentType.JSON)
        .when()
        .get("/api/speed-dials/default")
        .then()
        .statusCode(200)
        .body("id", equalTo("default"))
        .body("voiceAgentId", equalTo("voice-agent-prod"));
  }

  @Test
  void createSpeedDialGeneratesIdThenUpdateRoundTripsVoiceAgentId() {
    String token = VhrpAuthTestSupport.obtainValidJwt();

    Map<String, Object> createBody =
        Map.of(
            "name", "Custom Step2",
            "systemPrompt", "You are a step2 test assistant.",
            "voice", "alloy",
            "voiceAgentId", "voice-agent-prod-cc",
            "enabledTools", Map.of(),
            "reasoningEffort", "off",
            "toolChoiceRequired", false);

    Response createResponse =
        given()
            .auth()
            .oauth2(token)
            .contentType(ContentType.JSON)
            .accept(ContentType.JSON)
            .body(createBody)
            .when()
            .post("/api/speed-dials")
            .then()
            .statusCode(201)
            .body("id", matchesPattern("sd_[0-9a-f]{32}"))
            .body("voiceAgentId", equalTo("voice-agent-prod-cc"))
            .extract()
            .response();

    String generatedId = createResponse.jsonPath().getString("id");

    Map<String, Object> updateBody =
        Map.of(
            "name", "Custom Step2 Updated",
            "systemPrompt", "You are an updated step2 test assistant.",
            "voice", "alloy",
            "voiceAgentId", "voice-agent-prod-cc",
            "enabledTools", Map.of(),
            "reasoningEffort", "off",
            "toolChoiceRequired", false);

    given()
        .auth()
        .oauth2(token)
        .contentType(ContentType.JSON)
        .accept(ContentType.JSON)
        .body(updateBody)
        .when()
        .put("/api/speed-dials/{speedDialId}", generatedId)
        .then()
        .statusCode(200)
        .body("id", equalTo(generatedId))
        .body("name", equalTo("Custom Step2 Updated"))
        .body("voiceAgentId", equalTo("voice-agent-prod-cc"));

    given()
        .auth()
        .oauth2(token)
        .accept(ContentType.JSON)
        .when()
        .get("/api/speed-dials")
        .then()
        .statusCode(200)
        .body("voiceAgentId", hasItem("voice-agent-prod-cc"));
  }

  @Test
  void updateMissingSpeedDialReturnsNotFound() {
    String token = VhrpAuthTestSupport.obtainValidJwt();

    Map<String, Object> body =
        Map.of(
            "name", "Missing Agent",
            "systemPrompt", "You should not be saved.",
            "voice", "alloy",
            "voiceAgentId", "voice-agent-prod",
            "enabledTools", Map.of(),
            "reasoningEffort", "off",
            "toolChoiceRequired", false);

    given()
        .auth()
        .oauth2(token)
        .contentType(ContentType.JSON)
        .accept(ContentType.JSON)
        .body(body)
        .when()
        .put("/api/speed-dials/sd_missing")
        .then()
        .statusCode(404)
        .body("message", notNullValue());
  }

  @Test
  void createSpeedDialRejectsUnknownVoiceAgentId() {
    String token = VhrpAuthTestSupport.obtainValidJwt();

    Map<String, Object> body =
        Map.of(
            "name", "Bad Agent",
            "systemPrompt", "You should not be saved.",
            "voice", "alloy",
            "voiceAgentId", "missing-agent",
            "enabledTools", Map.of(),
            "reasoningEffort", "off",
            "toolChoiceRequired", false);

    given()
        .auth()
        .oauth2(token)
        .contentType(ContentType.JSON)
        .accept(ContentType.JSON)
        .body(body)
        .when()
        .post("/api/speed-dials")
        .then()
        .statusCode(400)
        .body("message", notNullValue());
  }

  @Test
  void defaultSpeedDialRenameRemainsProtected() {
    String token = VhrpAuthTestSupport.obtainValidJwt();

    Map<String, Object> body =
        Map.of(
            "name", "Renamed Default",
            "systemPrompt", "You are still default.",
            "voice", "alloy",
            "voiceAgentId", "voice-agent-prod",
            "enabledTools", Map.of(),
            "reasoningEffort", "off",
            "toolChoiceRequired", false);

    given()
        .auth()
        .oauth2(token)
        .contentType(ContentType.JSON)
        .accept(ContentType.JSON)
        .body(body)
        .when()
        .put("/api/speed-dials/default")
        .then()
        .statusCode(409)
        .body("message", notNullValue());
  }

  @Test
  void defaultSpeedDialDeleteRemainsProtected() {
    String token = VhrpAuthTestSupport.obtainValidJwt();

    given()
        .auth()
        .oauth2(token)
        .when()
        .delete("/api/speed-dials/default")
        .then()
        .statusCode(409)
        .body("message", notNullValue());
  }
}
