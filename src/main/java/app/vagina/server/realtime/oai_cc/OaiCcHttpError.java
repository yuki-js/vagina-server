package app.vagina.server.realtime.oai_cc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Structured details from a non-success Chat Completions HTTP response. */
public record OaiCcHttpError(
    int statusCode, String body, String providerCode, String providerType, String providerParam) {
  private static final String CONTEXT_LENGTH_EXCEEDED = "context_length_exceeded";

  static OaiCcHttpError parse(int statusCode, String body, ObjectMapper json) {
    String code = null;
    String type = null;
    String param = null;
    try {
      JsonNode root = json.readTree(body);
      JsonNode error = root == null ? null : root.get("error");
      code = text(error, "code");
      type = text(error, "type");
      param = text(error, "param");
    } catch (Exception ignored) {
      // Preserve the status and raw body; malformed provider errors are not retryable context
      // errors.
    }
    return new OaiCcHttpError(statusCode, body, code, type, param);
  }

  public boolean isContextLengthExceeded() {
    return statusCode == 400 && CONTEXT_LENGTH_EXCEEDED.equals(providerCode);
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node == null ? null : node.get(field);
    return value == null || value.isNull() ? null : value.asText();
  }
}
