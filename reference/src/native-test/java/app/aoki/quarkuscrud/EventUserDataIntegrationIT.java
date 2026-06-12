package app.aoki.quarkuscrud;

import io.quarkus.test.junit.QuarkusIntegrationTest;

/**
 * Native integration test variant of EventUserDataIntegrationTest. This test runs against the
 * native binary to ensure event user data handling works identically in native mode.
 */
@QuarkusIntegrationTest
public class EventUserDataIntegrationIT extends EventUserDataIntegrationTest {
  // This will run the same tests as EventUserDataIntegrationTest but in native mode
}
