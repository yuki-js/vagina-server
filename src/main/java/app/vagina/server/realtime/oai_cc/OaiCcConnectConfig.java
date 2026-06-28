package app.vagina.server.realtime.oai_cc;

import java.net.URI;
import java.util.Map;

/** Connection information for OpenAI-compatible Chat Completions. */
public record OaiCcConnectConfig(
    URI baseUri, String model, String apiKey, Map<String, String> extraHeaders) {

  public OaiCcConnectConfig {
    if (baseUri == null) {
      throw new IllegalArgumentException("Chat Completions base URI is required");
    }
    if (model == null || model.isBlank()) {
      throw new IllegalArgumentException("Chat Completions model is required");
    }
    extraHeaders = extraHeaders == null ? Map.of() : Map.copyOf(extraHeaders);
  }

  public URI chatCompletionsUri() {
    String path = baseUri.getPath();
    String normalizedPath = path == null || path.isBlank() ? "" : path.replaceAll("/+$", "");
    if (!normalizedPath.endsWith("/chat/completions")) {
      normalizedPath = normalizedPath + "/chat/completions";
    }
    return baseUri.resolve(normalizedPath + querySuffix(baseUri));
  }

  private static String querySuffix(URI uri) {
    return uri.getQuery() == null || uri.getQuery().isBlank() ? "" : "?" + uri.getQuery();
  }
}
