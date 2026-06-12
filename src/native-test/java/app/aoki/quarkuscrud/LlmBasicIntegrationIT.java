package app.aoki.quarkuscrud;

import io.quarkus.test.junit.QuarkusIntegrationTest;

/**
 * Native integration test variant for basic LLM integration tests.
 *
 * <p>These tests don't require custom WireMock configuration, so they can run in native mode.
 */
@QuarkusIntegrationTest
public class LlmBasicIntegrationIT extends LlmBasicIntegrationTest {
  // Inherits tests that work without custom WireMock stubs
}
