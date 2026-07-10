package app.vagina.server;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
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
class TextAgentsApiTest {

  @Test
  void createTextAgentGeneratesIdThenUpdateRoundTripsModelId() {
    String token = VhrpAuthTestSupport.obtainValidJwt();

    Map<String, Object> createBody =
        Map.of(
            "name", "Research Assistant",
            "prompt", "You are a careful research assistant.",
            "textModelId", "text-agent-prod",
            "enabledTools", Map.of());

    Response createResponse =
        given()
            .auth()
            .oauth2(token)
            .contentType(ContentType.JSON)
            .accept(ContentType.JSON)
            .body(createBody)
            .when()
            .post("/api/text-agents")
            .then()
            .statusCode(201)
            .body("id", matchesPattern("ta_[0-9a-f]{32}"))
            .body("textModelId", equalTo("text-agent-prod"))
            .extract()
            .response();

    String generatedId = createResponse.jsonPath().getString("id");

    Map<String, Object> updateBody =
        Map.of(
            "name", "Updated Research Assistant",
            "prompt", "You are an updated careful research assistant.",
            "textModelId", "text-agent-prod",
            "enabledTools", Map.of("document_read", true));

    given()
        .auth()
        .oauth2(token)
        .contentType(ContentType.JSON)
        .accept(ContentType.JSON)
        .body(updateBody)
        .when()
        .put("/api/text-agents/{textAgentId}", generatedId)
        .then()
        .statusCode(200)
        .body("id", equalTo(generatedId))
        .body("name", equalTo("Updated Research Assistant"))
        .body("textModelId", equalTo("text-agent-prod"))
        .body("enabledTools.document_read", equalTo(true));
  }

  @Test
  void createTextAgentRejectsUnknownTextModelId() {
    String token = VhrpAuthTestSupport.obtainValidJwt();

    Map<String, Object> body =
        Map.of(
            "name", "Bad Model",
            "prompt", "You should not be saved.",
            "textModelId", "missing-model",
            "enabledTools", Map.of());

    given()
        .auth()
        .oauth2(token)
        .contentType(ContentType.JSON)
        .accept(ContentType.JSON)
        .body(body)
        .when()
        .post("/api/text-agents")
        .then()
        .statusCode(400)
        .body("message", notNullValue());
  }

  @Test
  void listTextAgentModelsReturnsAvailableModelsAndPublicLockedModelsButExcludesStealthModels() {
    String token = VhrpAuthTestSupport.obtainValidJwt();

    given()
        .auth()
        .oauth2(token)
        .accept(ContentType.JSON)
        .when()
        .get("/api/text-agents/models")
        .then()
        .statusCode(200)
        .body("id", hasItem("text-agent-prod"))
        .body("find { it.id == 'text-agent-prod' }.isAvailable", equalTo(true))
        .body("id", hasItem("test-text-agent-public-entitled"))
        .body(
            "find { it.id == 'test-text-agent-public-entitled' }.displayName",
            equalTo("Test Locked Text Agent"))
        .body("find { it.id == 'test-text-agent-public-entitled' }.isAvailable", equalTo(false))
        .body("id", not(hasItem("test-text-agent-entitled")));
  }

  @Test
  void createTextAgentRejectsTextModelMissingRequiredEntitlement() {
    String token = VhrpAuthTestSupport.obtainValidJwt();

    Map<String, Object> body =
        Map.of(
            "name", "Locked Text Model",
            "prompt", "You should not be saved.",
            "textModelId", "test-text-agent-entitled",
            "enabledTools", Map.of());

    given()
        .auth()
        .oauth2(token)
        .contentType(ContentType.JSON)
        .accept(ContentType.JSON)
        .body(body)
        .when()
        .post("/api/text-agents")
        .then()
        .statusCode(403)
        .body(
            "message",
            equalTo(
                "Missing required entitlement for text agent model test-text-agent-entitled: premium.text"));
  }

  @Test
  void createTextAgentRejectsPublicTextModelMissingRequiredEntitlement() {
    String token = VhrpAuthTestSupport.obtainValidJwt();

    Map<String, Object> body =
        Map.of(
            "name", "Public Locked Text Model",
            "prompt", "You should not be saved.",
            "textModelId", "test-text-agent-public-entitled",
            "enabledTools", Map.of());

    given()
        .auth()
        .oauth2(token)
        .contentType(ContentType.JSON)
        .accept(ContentType.JSON)
        .body(body)
        .when()
        .post("/api/text-agents")
        .then()
        .statusCode(403)
        .body(
            "message",
            equalTo(
                "Missing required entitlement for text agent model test-text-agent-public-entitled: premium.text.public"));
  }
}
