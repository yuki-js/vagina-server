package app.vagina.server;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;

import app.vagina.server.support.HarigataOidcMockServerResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
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
  void saveSpeedDialRoundTripsVoiceAgentId() {
    String token = VhrpAuthTestSupport.obtainValidJwt();

    Map<String, Object> body =
        Map.of(
            "id", "custom-step2",
            "name", "Custom Step2",
            "systemPrompt", "You are a step2 test assistant.",
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
        .body(body)
        .when()
        .put("/api/speed-dials/custom-step2")
        .then()
        .statusCode(200)
        .body("id", equalTo("custom-step2"))
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
  void saveSpeedDialRejectsUnknownVoiceAgentId() {
    String token = VhrpAuthTestSupport.obtainValidJwt();

    Map<String, Object> body =
        Map.of(
            "id", "bad-agent",
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
        .put("/api/speed-dials/bad-agent")
        .then()
        .statusCode(400)
        .body("message", notNullValue());
  }
}
