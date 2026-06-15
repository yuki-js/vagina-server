package app.vagina.server.support;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.StringJoiner;

public final class Util {

  private static final ThreadLocal<SecureRandom> SECURE_RANDOM =
      ThreadLocal.withInitial(SecureRandom::new);
  private static final HexFormat HEX_FORMAT = HexFormat.of();

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

  public static String sha256Hex(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HEX_FORMAT.formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  public static String randomHexToken() {
    byte[] randomBytes = new byte[32];
    SECURE_RANDOM.get().nextBytes(randomBytes);
    return HEX_FORMAT.formatHex(randomBytes);
  }
}
