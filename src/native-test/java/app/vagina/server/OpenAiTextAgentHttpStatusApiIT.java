package app.vagina.server;

import app.vagina.server.support.NativeTestApplicationConfigResource;
import io.quarkiverse.wiremock.devservice.ConnectWireMock;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;

@QuarkusIntegrationTest
@QuarkusTestResource(NativeTestApplicationConfigResource.class)
@ConnectWireMock
public class OpenAiTextAgentHttpStatusApiIT extends OpenAiTextAgentHttpStatusApiTest {}
