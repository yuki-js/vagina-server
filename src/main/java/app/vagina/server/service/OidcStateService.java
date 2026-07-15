package app.vagina.server.service;

import app.vagina.server.domain.error.AuthenticationException;
import app.vagina.server.domain.error.ValidationException;
import app.vagina.server.entity.ClientType;
import app.vagina.server.support.Util;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class OidcStateService {
  private static final byte FORMAT_VERSION = 1;
  private static final int NONCE_BYTES = 16;
  private static final int PKCE_CHALLENGE_BYTES = 32;
  private static final int TAG_BYTES = 32;
  private static final int PAYLOAD_BYTES = 1 + Long.BYTES + NONCE_BYTES + PKCE_CHALLENGE_BYTES;
  private static final byte[] HKDF_SALT = "vagina/root-key/v1".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] HKDF_INFO =
      "vagina/oidc-state/v1".getBytes(StandardCharsets.US_ASCII);

  private final byte[] signingKey;
  private final int replayCacheCapacity;
  private final ConcurrentHashMap<String, Long> consumedStates = new ConcurrentHashMap<>();

  public OidcStateService(
      @ConfigProperty(name = "vagina.secret") String encodedRootSecret,
      @ConfigProperty(
              name = "vagina.auth.oauth.state.replay-cache-capacity",
              defaultValue = "10000")
          int replayCacheCapacity) {
    this.signingKey = deriveSigningKey(decodeRootSecret(encodedRootSecret));
    if (replayCacheCapacity < 1) {
      throw new IllegalArgumentException("OIDC state replay cache capacity must be positive");
    }
    this.replayCacheCapacity = replayCacheCapacity;
  }

  public String issue(
      String providerKey,
      ClientType clientType,
      byte[] codeChallenge,
      Instant expiresAt,
      byte[] nonce) {
    requireProviderKey(providerKey);
    if (clientType == null) {
      throw new ValidationException("OIDC client type is required");
    }
    requireLength(codeChallenge, PKCE_CHALLENGE_BYTES, "OIDC PKCE challenge");
    requireLength(nonce, NONCE_BYTES, "OIDC state nonce");
    if (expiresAt == null) {
      throw new IllegalArgumentException("OIDC state expiry is required");
    }

    char prefix = prefix(clientType);
    ByteBuffer payload = ByteBuffer.allocate(PAYLOAD_BYTES);
    payload.put(FORMAT_VERSION);
    payload.putLong(expiresAt.getEpochSecond());
    payload.put(nonce);
    payload.put(codeChallenge);
    byte[] payloadBytes = payload.array();
    byte[] tag = authenticate(providerKey, prefix, payloadBytes);

    byte[] encoded =
        ByteBuffer.allocate(payloadBytes.length + tag.length).put(payloadBytes).put(tag).array();
    return prefix + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(encoded);
  }

  public ConsumedState consume(
      String providerKey, String state, byte[] expectedCodeChallenge, Instant now) {
    requireProviderKey(providerKey);
    requireLength(expectedCodeChallenge, PKCE_CHALLENGE_BYTES, "OIDC PKCE challenge");
    if (now == null) {
      throw new IllegalArgumentException("Current time is required");
    }
    if (state == null || state.length() < 3 || state.charAt(1) != '.') {
      throw invalidState();
    }

    char suppliedPrefix = state.charAt(0);
    ClientType clientType = clientType(suppliedPrefix);
    byte[] encoded;
    try {
      encoded = Base64.getUrlDecoder().decode(state.substring(2));
    } catch (IllegalArgumentException e) {
      throw invalidState();
    }
    if (encoded.length != PAYLOAD_BYTES + TAG_BYTES) {
      throw invalidState();
    }

    byte[] payload = Arrays.copyOfRange(encoded, 0, PAYLOAD_BYTES);
    byte[] suppliedTag = Arrays.copyOfRange(encoded, PAYLOAD_BYTES, encoded.length);
    byte[] expectedTag = authenticate(providerKey, suppliedPrefix, payload);
    if (!MessageDigest.isEqual(suppliedTag, expectedTag)) {
      throw invalidState();
    }

    ByteBuffer decoded = ByteBuffer.wrap(payload);
    if (decoded.get() != FORMAT_VERSION) {
      throw invalidState();
    }
    long expiresAtEpochSecond = decoded.getLong();
    byte[] nonce = new byte[NONCE_BYTES];
    decoded.get(nonce);
    byte[] stateCodeChallenge = new byte[PKCE_CHALLENGE_BYTES];
    decoded.get(stateCodeChallenge);

    Instant expiresAt;
    try {
      expiresAt = Instant.ofEpochSecond(expiresAtEpochSecond);
    } catch (RuntimeException e) {
      throw invalidState();
    }
    if (!expiresAt.isAfter(now)) {
      throw new AuthenticationException("OIDC state expired");
    }
    if (!MessageDigest.isEqual(stateCodeChallenge, expectedCodeChallenge)) {
      throw new AuthenticationException("OIDC PKCE verification failed");
    }

    rememberConsumed(state, expiresAtEpochSecond, now.getEpochSecond());
    return new ConsumedState(clientType, expiresAt);
  }

  public record ConsumedState(ClientType clientType, Instant expiresAt) {}

  private synchronized void rememberConsumed(
      String state, long expiresAtEpochSecond, long nowEpochSecond) {
    evictExpired(nowEpochSecond);
    String fingerprint = sha256Base64Url(state);
    if (consumedStates.containsKey(fingerprint)) {
      throw new AuthenticationException("OIDC state already consumed");
    }
    if (consumedStates.size() >= replayCacheCapacity) {
      throw new AuthenticationException("OIDC state replay cache is full");
    }
    consumedStates.put(fingerprint, expiresAtEpochSecond);
  }

  private void evictExpired(long nowEpochSecond) {
    Iterator<Map.Entry<String, Long>> iterator = consumedStates.entrySet().iterator();
    while (iterator.hasNext()) {
      if (iterator.next().getValue() <= nowEpochSecond) {
        iterator.remove();
      }
    }
  }

  private byte[] authenticate(String providerKey, char prefix, byte[] payload) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
      mac.update("oidc-state\0".getBytes(StandardCharsets.US_ASCII));
      mac.update(providerKey.getBytes(StandardCharsets.UTF_8));
      mac.update((byte) 0);
      mac.update((byte) prefix);
      mac.update((byte) '.');
      mac.update(payload);
      return mac.doFinal();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HMAC-SHA-256 is not available", e);
    }
  }

  private static byte[] decodeRootSecret(String encodedRootSecret) {
    if (encodedRootSecret == null || encodedRootSecret.isBlank()) {
      throw new IllegalArgumentException("vagina.secret is required");
    }
    String normalized = encodedRootSecret.trim();
    if (normalized.length() != 64) {
      throw new IllegalArgumentException(
          "vagina.secret must contain exactly 64 hexadecimal digits");
    }
    try {
      return Util.parseHex(normalized);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("vagina.secret must be hexadecimal", e);
    }
  }

  private static byte[] deriveSigningKey(byte[] rootSecret) {
    byte[] pseudorandomKey = hmac(HKDF_SALT, rootSecret);
    ByteArrayOutputStream output = new ByteArrayOutputStream(32);
    byte[] previous = new byte[0];
    int counter = 1;
    while (output.size() < 32) {
      ByteBuffer input = ByteBuffer.allocate(previous.length + HKDF_INFO.length + 1);
      input.put(previous).put(HKDF_INFO).put((byte) counter++);
      previous = hmac(pseudorandomKey, input.array());
      output.write(previous, 0, Math.min(previous.length, 32 - output.size()));
    }
    return output.toByteArray();
  }

  private static byte[] hmac(byte[] key, byte[] value) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return mac.doFinal(value);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HMAC-SHA-256 is not available", e);
    }
  }

  private static String sha256Base64Url(String value) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(Util.sha256(value));
  }

  private static char prefix(ClientType clientType) {
    return switch (clientType) {
      case WEB -> 'w';
      case MOBILE -> 'm';
      case DESKTOP -> 'd';
    };
  }

  private static ClientType clientType(char prefix) {
    return switch (prefix) {
      case 'w' -> ClientType.WEB;
      case 'm' -> ClientType.MOBILE;
      case 'd' -> ClientType.DESKTOP;
      default -> throw invalidState();
    };
  }

  private static void requireProviderKey(String providerKey) {
    if (providerKey == null || providerKey.isBlank()) {
      throw new ValidationException("OIDC provider is required");
    }
  }

  private static void requireLength(byte[] value, int expectedLength, String name) {
    if (value == null || value.length != expectedLength) {
      throw new IllegalArgumentException(name + " must be " + expectedLength + " bytes");
    }
  }

  private static AuthenticationException invalidState() {
    return new AuthenticationException("Invalid OIDC state");
  }
}
