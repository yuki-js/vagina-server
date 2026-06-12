package app.aoki.quarkuscrud;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import org.junit.jupiter.api.Disabled;

/**
 * Native integration test variant of LlmIntegrationTest.
 *
 * <p>DISABLED: This test requires custom WireMock stub configuration which doesn't work with native
 * integration tests. @QuarkusTestResource lifecycle managers are not started
 * for @QuarkusIntegrationTest because the native binary runs as a separate process.
 *
 * <p>LLM functionality is tested in native mode via LlmBasicIntegrationIT which includes tests that
 * don't require custom WireMock configuration. WireMock-dependent tests (high variance, Japanese
 * names, error simulation, rate limiting) are only tested in JVM mode.
 */
@QuarkusIntegrationTest
@Disabled("Custom WireMock stubs don't work with native integration tests")
public class LlmIntegrationIT extends LlmIntegrationTest {
  // Would run all tests but WireMock customization not available in native mode
}
