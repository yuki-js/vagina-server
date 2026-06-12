package app.aoki.quarkuscrud;

import io.quarkus.test.junit.QuarkusIntegrationTest;

/**
 * Native integration test variant of OpenApiContractTest. This test runs against the native binary
 * to ensure OpenAPI contract validation works identically in native mode.
 */
@QuarkusIntegrationTest
public class OpenApiContractIT extends OpenApiContractTest {
  // This will run the same tests as OpenApiContractTest but in native mode
}
