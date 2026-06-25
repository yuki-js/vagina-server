package app.vagina.server.realtime.oai;

import java.net.URI;
import java.util.Map;

/**
 * Opaque connection configuration for the OpenAI Realtime protocol family, the server mirror of the
 * Dart {@code realtime_connect_config.dart}.
 *
 * <p>{@code baseUri} is treated as completely opaque: the transport never inspects its contents.
 * The {@code epFragment} is appended to the base path and the scheme is upgraded to {@code
 * ws}/{@code wss} as needed by {@link #resolveTarget()}. {@code bearerToken} becomes the {@code
 * Authorization: Bearer} header and {@code extraHeaders} are added verbatim during the WebSocket
 * handshake.
 */
public record OaiRealtimeConnectConfig(
    String baseUri, String epFragment, String bearerToken, Map<String, String> extraHeaders) {

  public OaiRealtimeConnectConfig {
    if (epFragment == null || epFragment.isBlank()) {
      epFragment = "/realtime";
    }
    extraHeaders = extraHeaders == null ? Map.of() : Map.copyOf(extraHeaders);
  }

  /**
   * Resolves the concrete WebSocket endpoint coordinates from the opaque base URI and endpoint
   * fragment, mirroring the Dart {@code resolveRealtimeEndpoint}: trailing slash stripped from the
   * base path, fragment appended, {@code http(s)} upgraded to {@code ws(s)}.
   */
  public Target resolveTarget() {
    URI base = URI.create(baseUri);

    String basePath = base.getPath() == null ? "" : base.getPath();
    if (basePath.endsWith("/")) {
      basePath = basePath.substring(0, basePath.length() - 1);
    }
    String fragment = epFragment.startsWith("/") ? epFragment : "/" + epFragment;
    String path = basePath + fragment;
    if (base.getQuery() != null && !base.getQuery().isBlank()) {
      path = path + "?" + base.getQuery();
    }

    String scheme = base.getScheme() == null ? "wss" : base.getScheme();
    boolean ssl =
        switch (scheme) {
          case "http", "ws" -> false;
          default -> true; // https, wss, or anything else defaults to secure
        };

    int port = base.getPort();
    if (port == -1) {
      port = ssl ? 443 : 80;
    }

    return new Target(base.getHost(), port, ssl, path);
  }

  /** Resolved WebSocket coordinates ready for a Vert.x {@code WebSocketConnectOptions}. */
  public record Target(String host, int port, boolean ssl, String path) {}
}
