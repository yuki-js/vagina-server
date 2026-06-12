package app.aoki.quarkuscrud;

import io.quarkus.test.junit.QuarkusIntegrationTest;

/**
 * Native integration test variant of EventCrudIntegrationTest. This test runs against the native
 * binary to ensure event CRUD operations work identically in native mode.
 */
@QuarkusIntegrationTest
public class EventCrudIntegrationIT extends EventCrudIntegrationTest {
  // This will run the same tests as EventCrudIntegrationTest but in native mode
}
