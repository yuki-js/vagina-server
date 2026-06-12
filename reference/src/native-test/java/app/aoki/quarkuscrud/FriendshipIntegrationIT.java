package app.aoki.quarkuscrud;

import io.quarkus.test.junit.QuarkusIntegrationTest;

/**
 * Native integration test variant of FriendshipIntegrationTest. This test runs against the native
 * binary to ensure friendship features work identically in native mode.
 */
@QuarkusIntegrationTest
public class FriendshipIntegrationIT extends FriendshipIntegrationTest {
  // This will run the same tests as FriendshipIntegrationTest but in native mode
}
