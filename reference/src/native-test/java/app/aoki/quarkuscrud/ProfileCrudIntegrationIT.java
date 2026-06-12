package app.aoki.quarkuscrud;

import io.quarkus.test.junit.QuarkusIntegrationTest;

/**
 * Native integration test variant of ProfileCrudIntegrationTest. This test runs against the native
 * binary to ensure profile CRUD operations work identically in native mode.
 */
@QuarkusIntegrationTest
public class ProfileCrudIntegrationIT extends ProfileCrudIntegrationTest {
  // This will run the same tests as ProfileCrudIntegrationTest but in native mode
}
