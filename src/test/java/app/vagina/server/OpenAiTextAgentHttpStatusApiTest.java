package app.vagina.server;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import app.vagina.server.realtime.VhrpTestClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.client.WireMock;
import io.quarkiverse.wiremock.devservice.ConnectWireMock;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.vertx.core.Vertx;
import java.net.URL;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@ConnectWireMock
class OpenAiTextAgentHttpStatusApiTest {
  private static final String MODEL_ID = "test-text-agent-wiremock";
  private static final String SAFE_AUTHENTICATION_MESSAGE =
      "This AI model is currently unavailable. Please contact the service administrator.";

  @TestHTTPResource("/")
  URL testServerUrl;

  WireMock wireMock;
  private Vertx vertx;
  private VhrpTestClient client;

  @BeforeEach
  void setUp() {
    wireMock.resetToDefaultMappings();
    wireMock.resetRequests();
    vertx = Vertx.vertx();
    client = new VhrpTestClient(vertx);
  }

  @AfterEach
  void tearDown() throws Exception {
    if (client != null) {
      client.close();
    }
    if (vertx != null) {
      vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void successfulProviderResponseIsDeserializedAcrossApplicationBoundary() throws Exception {
    stubResponses(
        200,
        """
        {
          "id": "resp_native_parse",
          "output": [{
            "type": "message",
            "content": [{"type": "output_text", "text": "native parse ok"}]
          }]
        }
        """);
    QueryFixture fixture = createFixture();

    query(fixture)
        .then()
        .statusCode(200)
        .body("status", equalTo("completed"))
        .body("text", equalTo("native parse ok"));
  }

  @Test
  void providerAuthenticationFailureReturnsSanitizedFailedQuery() throws Exception {
    stubResponses(
        401,
        """
        {
          "error": {
            "message": "Invalid API key: secret-provider-value",
            "type": "invalid_request_error",
            "code": "invalid_api_key"
          }
        }
        """);
    QueryFixture fixture = createFixture();

    query(fixture)
        .then()
        .statusCode(200)
        .body("status", equalTo("failed"))
        .body("error.code", equalTo("provider_authentication_error"))
        .body("error.message", equalTo(SAFE_AUTHENTICATION_MESSAGE));
  }

  @Test
  void providerServerFailureReturnsBadGateway() throws Exception {
    stubResponses(
        503,
        """
        {"error":{"message":"Temporary failure","type":"server_error","code":"server_error"}}
        """);
    QueryFixture fixture = createFixture();

    query(fixture).then().statusCode(502);
  }

  private QueryFixture createFixture() throws Exception {
    String token = VhrpAuthTestSupport.obtainValidJwt();
    client.connect(testServerUrl.getPort(), "vhrp.cbor.v1");
    client.sendSessionOpen(token, "default");
    JsonNode ready = client.waitForMessage("session.ready", 20, TimeUnit.SECONDS);
    String voiceSessionId = ready.path("body").path("sessionId").asText(null);
    assertNotNull(voiceSessionId, "session.ready must expose sessionId");

    Response createResponse =
        given()
            .auth()
            .oauth2(token)
            .contentType(ContentType.JSON)
            .accept(ContentType.JSON)
            .body(
                Map.of(
                    "name",
                    "HTTP status test agent",
                    "prompt",
                    "Reply concisely.",
                    "textModelId",
                    MODEL_ID,
                    "enabledTools",
                    Map.of()))
            .when()
            .post("/api/text-agents")
            .then()
            .statusCode(201)
            .extract()
            .response();
    return new QueryFixture(token, createResponse.jsonPath().getString("id"), voiceSessionId);
  }

  private Response query(QueryFixture fixture) {
    return given()
        .auth()
        .oauth2(fixture.token())
        .contentType(ContentType.JSON)
        .accept(ContentType.JSON)
        .body(
            Map.of(
                "voiceSessionId",
                fixture.voiceSessionId(),
                "requestId",
                "req_" + UUID.randomUUID(),
                "prompt",
                "Reply with the test marker."))
        .when()
        .post("/api/text-agents/{textAgentId}/query", fixture.textAgentId());
  }

  private void stubResponses(int status, String body) {
    wireMock.register(
        post(urlPathEqualTo("/v1/responses"))
            .withRequestBody(matchingJsonPath("$.model", matching("gpt-5\\.5")))
            .atPriority(1)
            .willReturn(
                aResponse()
                    .withStatus(status)
                    .withHeader("Content-Type", "application/json")
                    .withBody(body)));
  }

  private record QueryFixture(String token, String textAgentId, String voiceSessionId) {}
}
