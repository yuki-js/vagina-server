package app.aoki.quarkuscrud;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import org.junit.jupiter.api.Disabled;

/**
 * Native integration test variant of OpenAiApiValidationTest.
 *
 * <p>DISABLED: This test expects a WireMock server to be available but doesn't
 * declare @QuarkusTestResource. In native mode, the WireMock server is not started, causing
 * connection failures.
 *
 * <p>The OpenAI API validation is already tested in JVM mode. Native mode testing would require an
 * external mock server setup.
 */
@QuarkusIntegrationTest
@Disabled("WireMock server not available in native integration tests")
public class OpenAiApiValidationIT extends OpenAiApiValidationTest {
  // Would run same tests but WireMock server not available in native mode
}
