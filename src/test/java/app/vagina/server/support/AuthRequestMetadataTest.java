package app.vagina.server.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import jakarta.ws.rs.core.HttpHeaders;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class AuthRequestMetadataTest {

  @Test
  void selectsFirstPublicForwardedAddressAndIgnoresLocalValues() {
    assertEquals(
        "203.0.113.9",
        AuthRequestMetadata.firstPublicIp(
            "127.0.0.1, 10.0.0.8, 192.168.1.2, malformed, 203.0.113.9, 8.8.8.8"));
  }

  @Test
  void returnsNullWhenNoPublicAddressExists() {
    assertNull(AuthRequestMetadata.firstPublicIp(null));
    assertNull(AuthRequestMetadata.firstPublicIp("localhost, 127.0.0.1, 10.0.0.1, ::1, fc00::1"));
  }

  @Test
  void capturesNullableMetadataAndBoundsUserAgent() {
    HttpHeaders headers = Mockito.mock(HttpHeaders.class);
    Mockito.when(headers.getHeaderString("X-Forwarded-For")).thenReturn("8.8.8.8");
    Mockito.when(headers.getHeaderString(HttpHeaders.USER_AGENT)).thenReturn("a".repeat(2048));

    AuthRequestMetadata metadata = AuthRequestMetadata.from(headers);

    assertEquals("8.8.8.8", metadata.ipAddress());
    assertEquals(AuthRequestMetadata.MAX_USER_AGENT_LENGTH, metadata.userAgent().length());
    assertEquals(new AuthRequestMetadata(null, null), AuthRequestMetadata.from(null));
  }
}
