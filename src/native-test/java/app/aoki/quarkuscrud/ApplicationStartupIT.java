package app.aoki.quarkuscrud;

import io.quarkus.test.junit.QuarkusIntegrationTest;

/**
 * Native integration test variant of ApplicationStartupTest. This test runs against the native
 * binary to ensure the application behaves identically in native mode as it does in JVM mode.
 */
@QuarkusIntegrationTest
public class ApplicationStartupIT extends ApplicationStartupTest {
  // This will run the same tests as ApplicationStartupTest but in native mode
}
