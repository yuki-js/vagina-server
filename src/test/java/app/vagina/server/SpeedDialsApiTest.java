package app.vagina.server;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

import io.quarkiverse.wiremock.devservice.ConnectWireMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.util.Map;
import org.junit.jupiter.api.Test;

@QuarkusTest
@ConnectWireMock
class SpeedDialsApiTest {

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
            .body("reasoningEffort", equalTo("off"))
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
        .body("voiceAgentId", equalTo("voice-agent-prod-cc"))
        .body("reasoningEffort", equalTo("off"));

    given()
        .auth()
        .oauth2(token)
        .accept(ContentType.JSON)
        .when()
        .get("/api/speed-dials")
        .then()
        .statusCode(200)
        .body("voiceAgentId", hasItem("voice-agent-prod-cc"))
        .body("reasoningEffort", hasItem("off"));
  }

  @Test
  void createSpeedDialRejectsNonCanonicalReasoningEffort() {
    String token = VhrpAuthTestSupport.obtainValidJwt();

    Map<String, Object> body =
        Map.of(
            "name", "Bad Reasoning Effort",
            "systemPrompt", "You should not be saved.",
            "voice", "alloy",
            "voiceAgentId", "voice-agent-prod",
            "enabledTools", Map.of(),
            "reasoningEffort", "OFF",
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
        .statusCode(400);
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
  void listVoiceAgentsReturnsAvailableModelsAndPublicLockedModelsButExcludesStealthModels() {
    String token = VhrpAuthTestSupport.obtainValidJwt();

    given()
        .auth()
        .oauth2(token)
        .accept(ContentType.JSON)
        .when()
        .get("/api/voice-agents")
        .then()
        .statusCode(200)
        .body("id", hasItem("voice-agent-prod-cc"))
        .body("find { it.id == 'voice-agent-prod-cc' }.displayName", equalTo("Test Voice Agent CC"))
        .body("find { it.id == 'voice-agent-prod-cc' }.isAvailable", equalTo(true))
        .body("id", hasItem("test-voice-agent-public-entitled"))
        .body(
            "find { it.id == 'test-voice-agent-public-entitled' }.displayName",
            equalTo("Test Locked Voice Agent"))
        .body("find { it.id == 'test-voice-agent-public-entitled' }.isAvailable", equalTo(false))
        .body("id", not(hasItem("test-voice-agent-entitled")));
  }

  @Test
  void createSpeedDialRejectsVoiceAgentMissingRequiredEntitlement() {
    String token = VhrpAuthTestSupport.obtainValidJwt();

    Map<String, Object> body =
        Map.of(
            "name", "Locked Agent",
            "systemPrompt", "You should not be saved.",
            "voice", "alloy",
            "voiceAgentId", "test-voice-agent-entitled",
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
        .statusCode(403)
        .body(
            "message",
            equalTo(
                "Missing required entitlement for voice agent test-voice-agent-entitled: premium.voice"));
  }

  @Test
  void createSpeedDialRejectsPublicVoiceAgentMissingRequiredEntitlement() {
    String token = VhrpAuthTestSupport.obtainValidJwt();

    Map<String, Object> body =
        Map.of(
            "name", "Public Locked Agent",
            "systemPrompt", "You should not be saved.",
            "voice", "alloy",
            "voiceAgentId", "test-voice-agent-public-entitled",
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
        .statusCode(403)
        .body(
            "message",
            equalTo(
                "Missing required entitlement for voice agent test-voice-agent-public-entitled: premium.voice.public"));
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
