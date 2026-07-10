package app.vagina.server.realtime.oai;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Opaque connection configuration for the OpenAI Realtime protocol family, the server mirror of the
 * Dart {@code realtime_connect_config.dart}.
 *
 * <p>{@code baseUri} owns only the endpoint and non-model query parameters. The canonical model is
 * provided separately by {@link #model()} and appended as the {@code model} query parameter during
 * {@link #resolveTarget()}; specifying {@code model} inside {@code baseUri} is rejected. {@code
 * epFragment} is appended to the base path and the scheme is upgraded to {@code ws}/{@code wss} as
 * needed by {@link #resolveTarget()}. {@code bearerToken} becomes the {@code Authorization: Bearer}
 * header and {@code extraHeaders} are added verbatim during the WebSocket handshake.
 */
public record OaiRealtimeConnectConfig(
    String baseUri,
    String epFragment,
    String model,
    String bearerToken,
    Map<String, String> extraHeaders) {

  public OaiRealtimeConnectConfig {
    if (epFragment == null || epFragment.isBlank()) {
      epFragment = "/realtime";
    }
    if (model == null || model.isBlank()) {
      throw new IllegalArgumentException("Realtime model is required");
    }
    model = model.trim();
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
    String query = queryWithCanonicalModel(base.getRawQuery());
    if (query != null && !query.isBlank()) {
      path = path + "?" + query;
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

  private String queryWithCanonicalModel(String rawQuery) {
    List<String> retained = new ArrayList<>();
    if (rawQuery != null && !rawQuery.isBlank()) {
      for (String entry : rawQuery.split("&", -1)) {
        if (entry.isBlank()) {
          continue;
        }
        int separator = entry.indexOf('=');
        String rawName = separator < 0 ? entry : entry.substring(0, separator);
        String name = URLDecoder.decode(rawName, StandardCharsets.UTF_8);
        if ("model".equals(name)) {
          throw new IllegalArgumentException("Realtime base URI must not contain model query");
        }
        retained.add(entry);
      }
    }
    retained.add("model=" + URLEncoder.encode(model, StandardCharsets.UTF_8));
    return String.join("&", retained);
  }

  /** Resolved WebSocket coordinates ready for a Vert.x {@code WebSocketConnectOptions}. */
  public record Target(String host, int port, boolean ssl, String path) {}
}
