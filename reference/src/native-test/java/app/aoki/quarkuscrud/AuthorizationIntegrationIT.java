package app.aoki.quarkuscrud;

import io.quarkus.test.junit.QuarkusIntegrationTest;

/**
 * Native integration test variant of AuthorizationIntegrationTest. This test runs against the
 * native binary to ensure authorization behaves identically in native mode.
 */
@QuarkusIntegrationTest
public class AuthorizationIntegrationIT extends AuthorizationIntegrationTest {
  // This will run the same tests as AuthorizationIntegrationTest but in native mode
}
