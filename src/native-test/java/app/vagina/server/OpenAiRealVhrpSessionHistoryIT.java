package app.vagina.server;

import app.vagina.server.support.HarigataOidcMockServerResource;
import app.vagina.server.support.NativeTestApplicationConfigResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;

@QuarkusIntegrationTest
@QuarkusTestResource(NativeTestApplicationConfigResource.class)
@QuarkusTestResource(HarigataOidcMockServerResource.class)
public class OpenAiRealVhrpSessionHistoryIT extends OpenAiRealVhrpSessionHistoryTest {}
