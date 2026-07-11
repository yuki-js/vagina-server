package app.vagina.server;

import app.vagina.server.support.NativeTestApplicationConfigResource;
import io.quarkiverse.wiremock.devservice.ConnectWireMock;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.TestProfile;

@QuarkusIntegrationTest
@QuarkusTestResource(NativeTestApplicationConfigResource.class)
@ConnectWireMock
@TestProfile(VhrpResumeRetentionE2ETest.ShortRetentionProfile.class)
public class VhrpResumeRetentionE2EIT extends VhrpResumeRetentionE2ETest {}
