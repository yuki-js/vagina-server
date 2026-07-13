package app.vagina.server.support;

import jakarta.ws.rs.core.HttpHeaders;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

public record AuthRequestMetadata(String ipAddress, String userAgent) {
  static final int MAX_USER_AGENT_LENGTH = 1024;

  public static AuthRequestMetadata from(HttpHeaders headers) {
    if (headers == null) {
      return new AuthRequestMetadata(null, null);
    }
    return new AuthRequestMetadata(
        firstPublicIp(headers.getHeaderString("X-Forwarded-For")),
        truncate(headers.getHeaderString(HttpHeaders.USER_AGENT), MAX_USER_AGENT_LENGTH));
  }

  static String firstPublicIp(String forwardedFor) {
    if (forwardedFor == null || forwardedFor.isBlank()) {
      return null;
    }
    for (String candidate : forwardedFor.split(",")) {
      String normalized = candidate.trim();
      InetAddress address = parseIpLiteral(normalized);
      if (address != null && isPublic(address)) {
        return address.getHostAddress();
      }
    }
    return null;
  }

  private static InetAddress parseIpLiteral(String value) {
    if (value.isEmpty() || (!value.contains(":") && !isIpv4Literal(value))) {
      return null;
    }
    try {
      InetAddress address = InetAddress.getByName(value);
      if (address instanceof Inet6Address || address instanceof Inet4Address) {
        return address;
      }
    } catch (UnknownHostException ignored) {
      // Malformed forwarded values are ignored; authentication must remain available.
    }
    return null;
  }

  private static boolean isIpv4Literal(String value) {
    String[] parts = value.split("\\.", -1);
    if (parts.length != 4) {
      return false;
    }
    for (String part : parts) {
      if (part.isEmpty() || part.length() > 3) {
        return false;
      }
      for (int index = 0; index < part.length(); index++) {
        if (!Character.isDigit(part.charAt(index))) {
          return false;
        }
      }
      if (Integer.parseInt(part) > 255) {
        return false;
      }
    }
    return true;
  }

  private static boolean isPublic(InetAddress address) {
    if (address.isAnyLocalAddress()
        || address.isLoopbackAddress()
        || address.isLinkLocalAddress()
        || address.isSiteLocalAddress()
        || address.isMulticastAddress()) {
      return false;
    }
    byte[] bytes = address.getAddress();
    if (address instanceof Inet4Address) {
      int first = Byte.toUnsignedInt(bytes[0]);
      int second = Byte.toUnsignedInt(bytes[1]);
      return first != 0
          && first != 10
          && first != 127
          && first < 224
          && !(first == 100 && second >= 64 && second <= 127)
          && !(first == 169 && second == 254)
          && !(first == 172 && second >= 16 && second <= 31)
          && !(first == 192 && second == 168);
    }
    int first = Byte.toUnsignedInt(bytes[0]);
    return (first & 0xfe) != 0xfc;
  }

  private static String truncate(String value, int maximumLength) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.length() <= maximumLength ? value : value.substring(0, maximumLength);
  }
}
