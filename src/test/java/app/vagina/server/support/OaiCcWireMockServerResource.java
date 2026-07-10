package app.vagina.server.support;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.util.Map;

/** WireMock OpenAI-compatible Chat Completions endpoint for VHRP JVM/native tests. */
public class OaiCcWireMockServerResource implements QuarkusTestResourceLifecycleManager {

  private WireMockServer successServer;

  @Override
  public Map<String, String> start() {
    successServer = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    successServer.start();
    setupSuccessStubs();
    return Map.of("vagina.test.oai-cc.base-url", successServer.baseUrl() + "/v1");
  }

  @Override
  public void stop() {
    if (successServer != null) {
      successServer.stop();
    }
  }

  private void setupSuccessStubs() {
    successServer.stubFor(
        post(urlPathEqualTo("/v1/chat/completions"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "text/event-stream")
                    .withBody(
                        """
                        data: {"choices":[{"delta":{"content":"ok"}}]}
                        data: [DONE]

                        """)));
  }
}
