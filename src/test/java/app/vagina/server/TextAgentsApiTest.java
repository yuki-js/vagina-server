package app.vagina.server;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
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
  void updateMissingTextAgentReturnsNotFound() {
    String token = VhrpAuthTestSupport.obtainValidJwt();

    Map<String, Object> body =
        Map.of(
            "name", "Missing Assistant",
            "prompt", "You should not be saved.",
            "textModelId", "text-agent-prod",
            "enabledTools", Map.of());

    given()
        .auth()
        .oauth2(token)
        .contentType(ContentType.JSON)
        .accept(ContentType.JSON)
        .body(body)
        .when()
        .put("/api/text-agents/ta_missing")
        .then()
        .statusCode(404)
        .body("message", notNullValue());
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
}
