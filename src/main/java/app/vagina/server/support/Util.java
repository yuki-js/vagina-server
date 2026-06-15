package app.vagina.server.support;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.StringJoiner;

public final class Util {

  private Util() {}

  public static String formEncode(Map<String, String> params) {
    StringJoiner joiner = new StringJoiner("&");
    params.forEach(
        (k, v) ->
            joiner.add(
                URLEncoder.encode(k, StandardCharsets.UTF_8)
                    + "="
                    + URLEncoder.encode(v, StandardCharsets.UTF_8)));
    return joiner.toString();
  }

  public static String requiredText(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull() || value.asText().isBlank()) {
      throw new IllegalStateException("Missing required field in OIDC response: " + field);
    }
    return value.asText();
  }

  public static String optionalText(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return (value != null && !value.isNull()) ? value.asText() : null;
  }

  public static String requireJsonField(JsonNode doc, String field, String sourceDescription) {
    if (!doc.has(field) || doc.get(field).isNull()) {
      throw new IllegalStateException(
          sourceDescription + " is missing required field: " + field);
    }
    return doc.get(field).asText();
  }
}