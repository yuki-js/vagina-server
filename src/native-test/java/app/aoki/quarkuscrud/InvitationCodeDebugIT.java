package app.aoki.quarkuscrud;

import io.quarkus.test.junit.QuarkusIntegrationTest;

/**
 * Native integration test variant of InvitationCodeDebugTest. This test runs against the native
 * binary to ensure invitation code debugging works identically in native mode.
 */
@QuarkusIntegrationTest
public class InvitationCodeDebugIT extends InvitationCodeDebugTest {
  // This will run the same tests as InvitationCodeDebugTest but in native mode
}
