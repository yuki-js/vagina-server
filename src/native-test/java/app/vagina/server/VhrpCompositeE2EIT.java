package app.vagina.server;

import app.vagina.server.support.HarigataOidcMockServerResource;
import app.vagina.server.support.NativeTestApplicationConfigResource;
import app.vagina.server.support.OaiCcWireMockServerResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;

@QuarkusIntegrationTest
@QuarkusTestResource(NativeTestApplicationConfigResource.class)
@QuarkusTestResource(HarigataOidcMockServerResource.class)
@QuarkusTestResource(OaiCcWireMockServerResource.class)
public class VhrpCompositeE2EIT extends VhrpCompositeE2ETest {}
