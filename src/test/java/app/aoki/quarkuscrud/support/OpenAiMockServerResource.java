package app.aoki.quarkuscrud.support;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.util.Map;

/**
 * Test resource that starts a WireMock server to mock OpenAI API endpoints.
 *
 * <p>This allows tests to run against a real HTTP server without making actual calls to OpenAI,
 * providing more realistic integration testing compared to mocking at the Java level.
 */
public class OpenAiMockServerResource implements QuarkusTestResourceLifecycleManager {

  private WireMockServer wireMockServer;

  @Override
  public Map<String, String> start() {
    // Start WireMock server on a random available port
    wireMockServer = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    wireMockServer.start();

    // Configure default stub for chat completions endpoint
    setupDefaultStubs();

    // Return configuration to override the Azure OpenAI endpoint
    String baseUrl = "http://localhost:" + wireMockServer.port() + "/";
    return Map.of(
        "quarkus.langchain4j.azure-openai.endpoint",
        baseUrl,
        "quarkus.langchain4j.azure-openai.api-key",
        "test-api-key");
  }

  @Override
  public void stop() {
    if (wireMockServer != null) {
      wireMockServer.stop();
    }
  }

  @Override
  public void inject(Object testInstance) {
    // Inject the WireMockServer instance into test classes that have a field for it
    if (testInstance instanceof OpenAiMockServerAware) {
      ((OpenAiMockServerAware) testInstance).setWireMockServer(wireMockServer);
    }
  }

  private void setupDefaultStubs() {
    // Default stub for chat completions - matches Azure OpenAI API path structure
    // Azure OpenAI uses paths like: /openai/deployments/{deployment-name}/chat/completions
    // This stub validates the request has required headers and body structure
    wireMockServer.stubFor(
        post(urlPathMatching(".*/chat/completions"))
            .withHeader("api-key", matching(".*"))
            .withHeader("Content-Type", equalTo("application/json"))
            .withRequestBody(matchingJsonPath("$.messages"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {
                          "id": "chatcmpl-test-123456",
                          "object": "chat.completion",
                          "created": 1234567890,
                          "model": "gpt-3.5-turbo",
                          "choices": [{
                            "index": 0,
                            "message": {
                              "role": "assistant",
                              "content": "{\\"output\\": [\\"青木 優香\\", \\"青木 優空\\", \\"青山 裕子\\", \\"青木 雄\\", \\"青木 悠斗\\"]}"
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

    // Stub for requests without proper authentication
    wireMockServer.stubFor(
        post(urlPathMatching(".*/chat/completions"))
            .withHeader("api-key", absent())
            .willReturn(
                aResponse()
                    .withStatus(401)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {
                          "error": {
                            "message": "Invalid authentication",
                            "type": "invalid_request_error",
                            "code": "invalid_api_key"
                          }
                        }
                        """)));
  }

  /** Interface for test classes that want access to the WireMockServer. */
  public interface OpenAiMockServerAware {
    void setWireMockServer(WireMockServer wireMockServer);
  }
}
