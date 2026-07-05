package app.vagina.server.support;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
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
      throw new IllegalStateException(sourceDescription + " is missing required field: " + field);
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
    return randomHex(32);
  }

  public static String randomPublicId(String prefix) {
    return prefix + randomHex(16);
  }

  public static boolean hasPngMagic(byte[] bytes) {
    return bytes.length >= 8
        && (bytes[0] & 0xFF) == 0x89
        && (bytes[1] & 0xFF) == 0x50
        && (bytes[2] & 0xFF) == 0x4E
        && (bytes[3] & 0xFF) == 0x47
        && (bytes[4] & 0xFF) == 0x0D
        && (bytes[5] & 0xFF) == 0x0A
        && (bytes[6] & 0xFF) == 0x1A
        && (bytes[7] & 0xFF) == 0x0A;
  }

  public static boolean hasJpegMagic(byte[] bytes) {
    return bytes.length >= 3
        && (bytes[0] & 0xFF) == 0xFF
        && (bytes[1] & 0xFF) == 0xD8
        && (bytes[2] & 0xFF) == 0xFF;
  }

  public static String urlEncodePathSegment(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  public static URI resolveUriWithPathSuffix(URI baseUri, String pathSuffix) {
    if (baseUri == null) {
      throw new IllegalArgumentException("Base URI is required");
    }
    if (pathSuffix == null || pathSuffix.isBlank() || !pathSuffix.startsWith("/")) {
      throw new IllegalArgumentException("Path suffix must start with /");
    }
    String path = baseUri.getPath();
    String normalizedPath = path == null || path.isBlank() ? "" : path.replaceAll("/+$", "");
    if (!normalizedPath.endsWith(pathSuffix)) {
      normalizedPath = normalizedPath + pathSuffix;
    }
    String querySuffix =
        baseUri.getQuery() == null || baseUri.getQuery().isBlank() ? "" : "?" + baseUri.getQuery();
    return baseUri.resolve(normalizedPath + querySuffix);
  }

  private static String randomHex(int byteLength) {
    byte[] randomBytes = new byte[byteLength];
    SECURE_RANDOM.get().nextBytes(randomBytes);
    return HEX_FORMAT.formatHex(randomBytes);
  }

  // ---------------------------------------------------------------------------
  // Map field extraction (mirror of the JsonNode helpers above, for decoded
  // CBOR/JSON object maps). Pure, dependency-free, and protocol-agnostic.
  // ---------------------------------------------------------------------------

  public static String requireString(Map<?, ?> map, String field) {
    Object value = map.get(field);
    if (!(value instanceof String text) || text.isEmpty()) {
      throw new IllegalStateException("Missing or invalid string field: " + field);
    }
    return text;
  }

  public static String optionalString(Map<?, ?> map, String field) {
    Object value = map.get(field);
    if (value == null) {
      return null;
    }
    if (!(value instanceof String text)) {
      throw new IllegalStateException("Field is not a string: " + field);
    }
    return text;
  }

  public static long requireLong(Map<?, ?> map, String field) {
    Object value = map.get(field);
    if (value instanceof Number number) {
      return number.longValue();
    }
    throw new IllegalStateException("Missing or invalid integer field: " + field);
  }

  public static byte[] requireBytes(Map<?, ?> map, String field, int maxBytes) {
    Object value = map.get(field);
    if (!(value instanceof byte[] bytes)) {
      throw new IllegalStateException("Missing or invalid byte field: " + field);
    }
    if (bytes.length > maxBytes) {
      throw new IllegalStateException(field + " exceeds limit");
    }
    return bytes;
  }

  public static Map<String, Object> asStringKeyedMap(Object value) {
    if (value == null) {
      return Map.of();
    }
    if (!(value instanceof Map<?, ?> map)) {
      throw new IllegalStateException("Expected a map payload");
    }
    Map<String, Object> result = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      if (!(entry.getKey() instanceof String key)) {
        throw new IllegalStateException("Map payload has a non-string key");
      }
      result.put(key, entry.getValue());
    }
    return result;
  }
}
