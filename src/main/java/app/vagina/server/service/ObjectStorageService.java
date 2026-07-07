package app.vagina.server.service;

import app.vagina.server.config.ObjectStorageConfig;
import app.vagina.server.support.Constants;
import app.vagina.server.support.Util;
import io.quarkus.logging.Log;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.core.buffer.Buffer;
import io.vertx.mutiny.ext.web.client.HttpResponse;
import io.vertx.mutiny.ext.web.client.WebClient;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

@ApplicationScoped
public class ObjectStorageService {
  private static final Duration HTTP_TIMEOUT = Constants.SERVER_COMMON_HTTP_TIMEOUT;
  private static final String SERVICE = "s3";
  private static final String SIGNING_ALGORITHM = "AWS4-HMAC-SHA256";
  private static final DateTimeFormatter AMZ_DATE =
      DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
  private static final DateTimeFormatter DATE_STAMP =
      DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
  private static final HexFormat HEX = HexFormat.of();

  @Inject Vertx vertx;
  @Inject ObjectStorageConfig config;

  private WebClient webClient;

  @PostConstruct
  void init() {
    webClient = WebClient.create(vertx);
  }

  public void save(String key, byte[] payload, String contentType) {
    byte[] body = payload == null ? new byte[0] : payload;
    RequestSpec spec = requestSpec("PUT", key, body, contentType);
    var request = webClient.putAbs(spec.url());
    spec.headers().forEach(request::putHeader);
    HttpResponse<Buffer> response =
        request.sendBuffer(Buffer.buffer(body)).await().atMost(HTTP_TIMEOUT);
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw failure("Blob save failed", key, response);
    }
  }

  public Optional<byte[]> read(String key) {
    RequestSpec spec = requestSpec("GET", key, new byte[0], "application/octet-stream");
    var request = webClient.getAbs(spec.url());
    spec.headers().forEach(request::putHeader);
    HttpResponse<Buffer> response = request.send().await().atMost(HTTP_TIMEOUT);
    if (response.statusCode() == 404) {
      return Optional.empty();
    }
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw failure("Blob read failed", key, response);
    }
    Buffer body = response.body();
    return Optional.of(body == null ? new byte[0] : body.getBytes());
  }

  public void delete(String key) {
    RequestSpec spec = requestSpec("DELETE", key, new byte[0], "application/octet-stream");
    var request = webClient.deleteAbs(spec.url());
    spec.headers().forEach(request::putHeader);
    HttpResponse<Buffer> response = request.send().await().atMost(HTTP_TIMEOUT);
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw failure("Blob delete failed", key, response);
    }
  }

  public boolean head(String key) {
    RequestSpec spec = requestSpec("HEAD", key, new byte[0], "application/octet-stream");
    var request = webClient.headAbs(spec.url());
    spec.headers().forEach(request::putHeader);
    HttpResponse<Buffer> response = request.send().await().atMost(HTTP_TIMEOUT);
    if (response.statusCode() == 404) {
      return false;
    }
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw failure("Blob head failed", key, response);
    }
    return true;
  }

  private RequestSpec requestSpec(String method, String key, byte[] payload, String contentType) {
    requireConfigured();
    String normalizedKey = normalizeKey(key);
    URI endpoint = URI.create(config.endpoint());
    String endpointBase = config.endpoint().replaceAll("/+$", "");
    String objectPath =
        "/" + Util.urlEncodePathSegment(config.bucket()) + "/" + encodeObjectKey(normalizedKey);
    String url = endpointBase + objectPath;
    Instant now = Instant.now();
    String amzDate = AMZ_DATE.format(now);
    String dateStamp = DATE_STAMP.format(now);
    String payloadHash = sha256Hex(payload);
    String host = endpoint.getHost();
    int port = endpoint.getPort();
    String scheme = endpoint.getScheme();
    if (port > 0
        && !("http".equals(scheme) && port == 80)
        && !("https".equals(scheme) && port == 443)) {
      host = host + ":" + port;
    }

    TreeMap<String, String> canonicalHeaderMap = new TreeMap<>();
    canonicalHeaderMap.put("host", host);
    if (contentType != null && !contentType.isBlank() && !"HEAD".equals(method)) {
      canonicalHeaderMap.put("content-type", contentType);
    }
    canonicalHeaderMap.put("x-amz-content-sha256", payloadHash);
    canonicalHeaderMap.put("x-amz-date", amzDate);

    String signedHeaders = String.join(";", canonicalHeaderMap.keySet());
    StringBuilder canonicalHeaders = new StringBuilder();
    canonicalHeaderMap.forEach(
        (name, value) -> canonicalHeaders.append(name).append(':').append(value).append('\n'));
    String canonicalRequest =
        method
            + "\n"
            + objectPath
            + "\n"
            + "\n"
            + canonicalHeaders
            + "\n"
            + signedHeaders
            + "\n"
            + payloadHash;
    String credentialScope = dateStamp + "/" + config.region() + "/" + SERVICE + "/aws4_request";
    String stringToSign =
        SIGNING_ALGORITHM
            + "\n"
            + amzDate
            + "\n"
            + credentialScope
            + "\n"
            + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
    String signature = HEX.formatHex(hmac(signingKey(dateStamp), stringToSign));
    String authorization =
        SIGNING_ALGORITHM
            + " Credential="
            + config.accessKey()
            + "/"
            + credentialScope
            + ", SignedHeaders="
            + signedHeaders
            + ", Signature="
            + signature;

    LinkedHashMap<String, String> requestHeaders = new LinkedHashMap<>(canonicalHeaderMap);
    requestHeaders.put("Authorization", authorization);
    return new RequestSpec(url, requestHeaders);
  }

  private byte[] signingKey(String dateStamp) {
    byte[] kDate = hmac(("AWS4" + config.secretKey()).getBytes(StandardCharsets.UTF_8), dateStamp);
    byte[] kRegion = hmac(kDate, config.region());
    byte[] kService = hmac(kRegion, SERVICE);
    return hmac(kService, "aws4_request");
  }

  private byte[] hmac(byte[] key, String value) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    } catch (InvalidKeyException | NoSuchAlgorithmException e) {
      throw new IllegalStateException("S3 signature could not be calculated", e);
    }
  }

  private String sha256Hex(byte[] value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HEX.formatHex(digest.digest(value));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  private RuntimeException failure(String message, String key, HttpResponse<Buffer> response) {
    String body = response.bodyAsString();
    Log.warnf("%s: key=%s status=%s body=%s", message, key, response.statusCode(), body);
    return new IllegalStateException(message + ": " + key + " status=" + response.statusCode());
  }

  private void requireConfigured() {
    if (config.endpoint().isBlank()
        || config.bucket().isBlank()
        || config.accessKey().isBlank()
        || config.secretKey().isBlank()) {
      throw new IllegalStateException("Object storage is not configured");
    }
  }

  private String normalizeKey(String key) {
    if (key == null || key.isBlank()) {
      throw new IllegalArgumentException("Blob key is required");
    }
    String prefix = config.pathPrefix().map(String::trim).orElse("");
    String normalized = key.startsWith("/") ? key.substring(1) : key;
    if (prefix.isBlank()) {
      return normalized;
    }
    String normalizedPrefix =
        prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
    return normalizedPrefix + "/" + normalized;
  }

  private String encodeObjectKey(String key) {
    String[] parts = key.split("/", -1);
    StringBuilder encoded = new StringBuilder();
    for (int i = 0; i < parts.length; i++) {
      if (i > 0) {
        encoded.append('/');
      }
      encoded.append(Util.urlEncodePathSegment(parts[i]));
    }
    return encoded.toString();
  }

  private record RequestSpec(String url, Map<String, String> headers) {}
}
