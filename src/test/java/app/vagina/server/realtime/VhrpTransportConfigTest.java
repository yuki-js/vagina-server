package app.vagina.server.realtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.junit.QuarkusTest;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

@QuarkusTest
class VhrpTransportConfigTest {
  private static final long MAX_WEBSOCKET_BYTES = 9L * 1024 * 1024;

  @Test
  void websocketLimitsIncludeHeadroomForEightMibImagePayload() {
    assertEquals(
        MAX_WEBSOCKET_BYTES,
        ConfigProvider.getConfig()
            .getValue("quarkus.websockets-next.server.max-frame-size", Long.class));
    assertEquals(
        MAX_WEBSOCKET_BYTES,
        ConfigProvider.getConfig()
            .getValue("quarkus.websockets-next.server.max-message-size", Long.class));
  }
}
