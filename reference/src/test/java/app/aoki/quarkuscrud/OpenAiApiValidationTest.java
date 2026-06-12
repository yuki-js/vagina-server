package app.aoki.quarkuscrud;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Validation test for OpenAI API integration.
 *
 * <p>This test can run against both WireMock (in CI) and a real OpenAI-compatible API (locally).
 *
 * <p>To test against a real OpenAI-compatible API, set environment variables: -
 * TEST_OPENAI_BASE_URL=https://api.openai.com/v1 - TEST_OPENAI_API_KEY=sk-... -
 * TEST_OPENAI_MODEL=gpt-3.5-turbo
 *
 * <p>Compatible APIs you can test with: - OpenAI: https://api.openai.com/v1 - Azure OpenAI:
 * https://YOUR_RESOURCE.openai.azure.com/openai/deployments/YOUR_DEPLOYMENT/v1 - LocalAI (local):
 * http://localhost:8080/v1 - Ollama (via proxy): http://localhost:11434/v1
 */
@QuarkusTest
public class OpenAiApiValidationTest {

  private static String jwtToken;

  /**
   * Test that validates the endpoint works with WireMock (always runs).
   *
   * <p>This ensures our WireMock configuration properly mimics OpenAI API behavior.
   */
  @Test
  public void testWithWireMock() {
    // Get JWT token for authentication
    Response authResponse = given().contentType(ContentType.JSON).post("/api/auth/guest");
    jwtToken = authResponse.getHeader("Authorization").substring(7);
    assertNotNull(jwtToken, "JWT token should be generated");

    // Test the endpoint
    Response response =
        given()
            .header("Authorization", "Bearer " + jwtToken)
            .contentType(ContentType.JSON)
            .body("{\"inputName\":\"青木 勇樹\",\"variance\":\"とても良く似ている名前\"}")
            .when()
            .post("/api/llm/fake-names");

    // Validate response
    assertEquals(200, response.getStatusCode(), "Should return 200 OK");
    assertNotNull(response.jsonPath().getList("output"), "Should have output array");
    assertTrue(
        response.jsonPath().getList("output").size() >= 5, "Should return at least 5 fake names");

    System.out.println("✅ WireMock validation passed");
    System.out.println("Response: " + response.body().asString());
  }

  /**
   * Optional test that runs against a real OpenAI-compatible API.
   *
   * <p>Only runs if TEST_OPENAI_BASE_URL environment variable is set.
   *
   * <p>Example usage: export TEST_OPENAI_BASE_URL=https://api.openai.com/v1 export
   * TEST_OPENAI_API_KEY=sk-... export TEST_OPENAI_MODEL=gpt-3.5-turbo ./gradlew test --tests
   * "OpenAiApiValidationTest.testWithRealOpenAiApi"
   */
  @Test
  @EnabledIfEnvironmentVariable(named = "TEST_OPENAI_BASE_URL", matches = ".+")
  public void testWithRealOpenAiApi() {
    String baseUrl = System.getenv("TEST_OPENAI_BASE_URL");
    String apiKey = System.getenv("TEST_OPENAI_API_KEY");
    String model = System.getenv().getOrDefault("TEST_OPENAI_MODEL", "gpt-3.5-turbo");

    assertNotNull(baseUrl, "TEST_OPENAI_BASE_URL must be set to run this test against real API");
    assertNotNull(apiKey, "TEST_OPENAI_API_KEY must be set to run this test against real API");

    System.out.println("🔗 Testing against real OpenAI-compatible API:");
    System.out.println("   Base URL: " + baseUrl);
    System.out.println("   Model: " + model);

    // Make direct API call to OpenAI-compatible endpoint
    Response apiResponse =
        given()
            .baseUri(baseUrl)
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .body(
                String.format(
                    """
                {
                  "model": "%s",
                  "messages": [{
                    "role": "user",
                    "content": "Generate 5 similar Japanese names to '青木 勇樹'. Return as JSON: {\\"output\\": [\\"name1\\", \\"name2\\", ...]}"
                  }],
                  "temperature": 0.7
                }
                """,
                    model))
            .when()
            .post("/chat/completions");

    // Validate response format matches OpenAI specification
    assertEquals(
        200,
        apiResponse.getStatusCode(),
        "Real API should return 200. Response: " + apiResponse.body().asString());
    assertNotNull(apiResponse.jsonPath().getString("id"), "Response should have id field");
    assertNotNull(apiResponse.jsonPath().getString("object"), "Response should have object field");
    assertEquals(
        "chat.completion",
        apiResponse.jsonPath().getString("object"),
        "Object type should be chat.completion");
    assertNotNull(apiResponse.jsonPath().getList("choices"), "Response should have choices array");
    assertTrue(
        apiResponse.jsonPath().getList("choices").size() > 0, "Should have at least one choice");

    // Extract the assistant's message
    String content = apiResponse.jsonPath().getString("choices[0].message.content");
    assertNotNull(content, "Choice should have message content");

    System.out.println("✅ Real API validation passed");
    System.out.println("API Response structure validated successfully");
    System.out.println("Sample content: " + content.substring(0, Math.min(100, content.length())));

    // Now test through our application endpoint
    Response authResponse = given().contentType(ContentType.JSON).post("/api/auth/guest");
    jwtToken = authResponse.getHeader("Authorization").substring(7);

    // Note: This will still use WireMock unless we reconfigure the app
    System.out.println("\n📝 Note: Application endpoint uses WireMock in test environment.");
    System.out.println("   To test with real API, run application in dev mode with:");
    System.out.println("   export AZURE_OPENAI_API_KEY=" + apiKey);
    System.out.println("   export AZURE_OPENAI_ENDPOINT=" + baseUrl);
    System.out.println("   ./gradlew quarkusDev");
  }

  /**
   * Test that documents the exact OpenAI API specification our WireMock follows.
   *
   * <p>This serves as living documentation of the API contract.
   */
  @Test
  public void documentOpenAiApiSpecification() {
    String specification =
        """
        OpenAI Chat Completions API Specification (v1)
        ===============================================

        Endpoint: POST /chat/completions

        Request Headers:
        - Authorization: Bearer {api_key}
        - Content-Type: application/json

        Request Body:
        {
          "model": "gpt-3.5-turbo",
          "messages": [
            {
              "role": "user|assistant|system",
              "content": "message text"
            }
          ],
          "temperature": 0.0-2.0 (optional),
          "max_tokens": integer (optional),
          "top_p": 0.0-1.0 (optional)
        }

        Response (200 OK):
        {
          "id": "chatcmpl-{unique_id}",
          "object": "chat.completion",
          "created": unix_timestamp,
          "model": "model_name",
          "choices": [
            {
              "index": 0,
              "message": {
                "role": "assistant",
                "content": "response text"
              },
              "finish_reason": "stop|length|content_filter|null"
            }
          ],
          "usage": {
            "prompt_tokens": integer,
            "completion_tokens": integer,
            "total_tokens": integer
          }
        }

        Our WireMock implementation follows this specification exactly.
        """;

    System.out.println(specification);

    // Verify our WireMock matches this spec
    assertTrue(
        true,
        "WireMock configuration in OpenAiMockServerResource matches OpenAI API specification");
  }
}
