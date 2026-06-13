package app.vagina.server;

import app.vagina.server.support.HarigataOidcMockServerResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;

@QuarkusIntegrationTest
@QuarkusTestResource(HarigataOidcMockServerResource.class)
public class OpenApiContractIT extends OpenApiContractTest {}
