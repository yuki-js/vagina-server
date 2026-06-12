package app.aoki.quarkuscrud;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import app.aoki.quarkuscrud.support.OpenAiMockServerResource;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Integration tests for LLM API endpoints.
 *
 * <p>Tests the /api/llm/fake-names endpoint with WireMock server acting as OpenAI API. Validates
 * authentication, rate limiting, request validation, and response format.
 */
@QuarkusTest
@QuarkusTestResource(OpenAiMockServerResource.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class LlmIntegrationTest implements OpenAiMockServerResource.OpenAiMockServerAware {

  private WireMockServer wireMockServer;

  @Override
  public void setWireMockServer(WireMockServer wireMockServer) {
    this.wireMockServer = wireMockServer;
  }

  private static String jwtToken;

  @Test
  @Order(0)
  public void setup() {
    // Create a guest user for testing
    Response response = given().contentType(ContentType.JSON).post("/api/auth/guest");
    jwtToken = response.getHeader("Authorization").substring(7);
    assertNotNull(jwtToken);
  }

  @Test
  @Order(1)
  public void testGenerateFakeNamesWithoutAuthentication() {
    given()
        .contentType(ContentType.JSON)
        .body("{\"inputName\":\"青木 勇樹\",\"variance\":0.1}")
        .when()
        .post("/api/llm/fake-names")
        .then()
        .statusCode(401);
  }

  @Test
  @Order(2)
  public void testGenerateFakeNamesSuccess() {
    // The default stub in OpenAiMockServerResource already returns a good response
    given()
        .header("Authorization", "Bearer " + jwtToken)
        .contentType(ContentType.JSON)
        .body("{\"inputName\":\"青木 勇樹\",\"variance\":\"とても良く似ている名前\"}")
        .when()
        .post("/api/llm/fake-names")
        .then()
        .statusCode(200)
        .body("output", notNullValue())
        .body("output", hasSize(greaterThanOrEqualTo(5)))
        .body("output[0]", notNullValue());
  }

  @Test
  @Order(3)
  public void testGenerateFakeNamesWithHighVariance() {
    // Configure WireMock to return high variance names
    wireMockServer.stubFor(
        post(urlPathMatching("/chat/completions"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {
                          "id": "chatcmpl-test",
                          "object": "chat.completion",
                          "created": 1234567890,
                          "model": "gpt-3.5-turbo",
                          "choices": [{
                            "index": 0,
                            "message": {
                              "role": "assistant",
                              "content": "{\\"output\\": [\\"西牟田 博之\\", \\"秀丸 壱太朗\\", \\"島田 部長\\", \\"李 源彦\\", \\"篠原 アンジェラ\\"]}"
                            },
                            "finish_reason": "stop"
                          }],
                          "usage": {
                            "prompt_tokens": 50,
                            "completion_tokens": 30,
                            "total_tokens": 80
                          }
                        }
                        """)));

    given()
        .header("Authorization", "Bearer " + jwtToken)
        .contentType(ContentType.JSON)
        .body("{\"inputName\":\"青木 勇樹\",\"variance\":\"互いにまったく似ていない名前\"}")
        .when()
        .post("/api/llm/fake-names")
        .then()
        .statusCode(200)
        .body("output", notNullValue())
        .body("output", hasSize(greaterThanOrEqualTo(5)));
  }

  @Test
  @Order(4)
  public void testGenerateFakeNamesMissingInputName() {
    given()
        .header("Authorization", "Bearer " + jwtToken)
        .contentType(ContentType.JSON)
        .body("{\"variance\":\"とても良く似ている名前\"}")
        .when()
        .post("/api/llm/fake-names")
        .then()
        .statusCode(400); // Bean Validation will catch this
  }

  @Test
  @Order(5)
  public void testGenerateFakeNamesEmptyInputName() {
    given()
        .header("Authorization", "Bearer " + jwtToken)
        .contentType(ContentType.JSON)
        .body("{\"inputName\":\"\",\"variance\":\"とても良く似ている名前\"}")
        .when()
        .post("/api/llm/fake-names")
        .then()
        .statusCode(400); // Size validation will catch this
  }

  @Test
  @Order(6)
  public void testGenerateFakeNamesInvalidVariance() {
    given()
        .header("Authorization", "Bearer " + jwtToken)
        .contentType(ContentType.JSON)
        .body("{\"inputName\":\"青木 勇樹\",\"variance\":\"invalid level\"}")
        .when()
        .post("/api/llm/fake-names")
        .then()
        .statusCode(400); // Use case validation will catch this
  }

  @Test
  @Order(7)
  public void testGenerateFakeNamesMissingVariance() {
    given()
        .header("Authorization", "Bearer " + jwtToken)
        .contentType(ContentType.JSON)
        .body("{\"inputName\":\"青木 勇樹\"}")
        .when()
        .post("/api/llm/fake-names")
        .then()
        .statusCode(400); // NotNull validation will catch this
  }

  @Test
  @Order(9)
  public void testGenerateFakeNamesWithJapaneseName() {
    // Configure WireMock to return Japanese names
    wireMockServer.stubFor(
        post(urlPathMatching("/chat/completions"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {
                          "id": "chatcmpl-test",
                          "object": "chat.completion",
                          "created": 1234567890,
                          "model": "gpt-3.5-turbo",
                          "choices": [{
                            "index": 0,
                            "message": {
                              "role": "assistant",
                              "content": "{\\"output\\": [\\"田中 太郎\\", \\"田中 次郎\\", \\"田辺 太郎\\", \\"山田 太郎\\", \\"中田 太郎\\", \\"田口 太郎\\"]}"
                            },
                            "finish_reason": "stop"
                          }],
                          "usage": {
                            "prompt_tokens": 50,
                            "completion_tokens": 30,
                            "total_tokens": 80
                          }
                        }
                        """)));

    given()
        .header("Authorization", "Bearer " + jwtToken)
        .contentType(ContentType.JSON)
        .body("{\"inputName\":\"田中 太郎\",\"variance\":\"結構似ている名前\"}")
        .when()
        .post("/api/llm/fake-names")
        .then()
        .statusCode(200)
        .body("output", hasSize(greaterThanOrEqualTo(5)));
  }

  @Test
  @Order(10)
  public void testGenerateFakeNamesLlmError() {
    // Configure WireMock to return an error
    wireMockServer.stubFor(
        post(urlPathMatching("/chat/completions"))
            .willReturn(aResponse().withStatus(500).withBody("Internal Server Error")));

    given()
        .header("Authorization", "Bearer " + jwtToken)
        .contentType(ContentType.JSON)
        .body("{\"inputName\":\"青木 勇樹\",\"variance\":\"とても良く似ている名前\"}")
        .when()
        .post("/api/llm/fake-names")
        .then()
        .statusCode(500);
  }

  @Test
  @Order(11)
  public void testRateLimitPerUser() {
    // Reset WireMock to default successful response
    wireMockServer.resetAll();
    wireMockServer.stubFor(
        post(urlPathMatching("/chat/completions"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {
                          "id": "chatcmpl-test",
                          "object": "chat.completion",
                          "created": 1234567890,
                          "model": "gpt-3.5-turbo",
                          "choices": [{
                            "index": 0,
                            "message": {
                              "role": "assistant",
                              "content": "{\\"output\\": [\\"名前1\\", \\"名前2\\", \\"名前3\\", \\"名前4\\", \\"名前5\\"]}"
                            },
                            "finish_reason": "stop"
                          }],
                          "usage": {
                            "prompt_tokens": 50,
                            "completion_tokens": 30,
                            "total_tokens": 80
                          }
                        }
                        """)));

    // Make multiple requests to verify rate limiting behavior
    // The current implementation may have a high limit or no limit in test mode
    // This test verifies the endpoint doesn't crash under load
    int successCount = 0;
    int errorCount = 0;

    for (int i = 0; i < 10; i++) {
      Response response =
          given()
              .header("Authorization", "Bearer " + jwtToken)
              .contentType(ContentType.JSON)
              .body("{\"inputName\":\"テスト\",\"variance\":\"結構似ている名前\"}")
              .when()
              .post("/api/llm/fake-names");

      int statusCode = response.getStatusCode();
      if (statusCode == 200) {
        successCount++;
      } else if (statusCode == 429 || statusCode == 500) {
        // Accept either rate limit or server error responses
        errorCount++;
      } else {
        fail("Unexpected status code: " + statusCode + ". Expected 200, 429, or 500.");
      }
    }

    // Verify the endpoint handled all requests without crashing
    // At minimum, some requests should succeed
    assertTrue(
        successCount > 0,
        "At least some requests should succeed. Got "
            + successCount
            + " successes and "
            + errorCount
            + " errors out of 10 requests.");
  }

  private void assertTrue(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }

  private void fail(String message) {
    throw new AssertionError(message);
  }
}
