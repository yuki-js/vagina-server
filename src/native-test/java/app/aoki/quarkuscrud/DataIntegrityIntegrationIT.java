package app.aoki.quarkuscrud;

import io.quarkus.test.junit.QuarkusIntegrationTest;

/**
 * Native integration test variant of DataIntegrityIntegrationTest. This test runs against the
 * native binary to ensure data integrity is maintained in native mode.
 */
@QuarkusIntegrationTest
public class DataIntegrityIntegrationIT extends DataIntegrityIntegrationTest {
  // This will run the same tests as DataIntegrityIntegrationTest but in native mode
}
