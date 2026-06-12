package app.aoki.quarkuscrud;

import io.quarkus.test.junit.QuarkusIntegrationTest;

/**
 * Native integration test variant of AuthenticationIntegrationTest. This test runs against the
 * native binary to ensure authentication behaves identically in native mode.
 */
@QuarkusIntegrationTest
public class AuthenticationIntegrationIT extends AuthenticationIntegrationTest {
  // This will run the same tests as AuthenticationIntegrationTest but in native mode
}
