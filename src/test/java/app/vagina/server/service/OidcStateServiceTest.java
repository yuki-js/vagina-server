package app.vagina.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.vagina.server.domain.error.AuthenticationException;
import app.vagina.server.entity.ClientType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class OidcStateServiceTest {
  private static final String ROOT_SECRET =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
  private static final Instant NOW = Instant.ofEpochSecond(1_700_000_000L);

  @Test
  void stateIsCompactAndCanBeConsumedByAnotherInstanceWithTheSameRootSecret() {
    OidcStateService issuer = new OidcStateService(ROOT_SECRET, 10_000);
    OidcStateService consumer = new OidcStateService(ROOT_SECRET, 10_000);
    byte[] challenge = sha256("verifier");

    String state =
        issuer.issue("github", ClientType.MOBILE, challenge, NOW.plusSeconds(600), nonce((byte) 1));
    OidcStateService.ConsumedState consumed =
        consumer.consume("github", state, challenge, NOW.plusSeconds(1));

    assertTrue(state.startsWith("m."));
    assertTrue(state.length() <= 128);
    assertEquals(ClientType.MOBILE, consumed.clientType());
    assertEquals(NOW.plusSeconds(600), consumed.expiresAt());
  }

  @Test
  void rootSecretHexIsCaseInsensitive() {
    OidcStateService lowercase = new OidcStateService(ROOT_SECRET, 10_000);
    OidcStateService uppercase = new OidcStateService(ROOT_SECRET.toUpperCase(), 10_000);
    byte[] challenge = sha256("verifier");
    String state =
        lowercase.issue("github", ClientType.WEB, challenge, NOW.plusSeconds(600), nonce((byte) 8));

    OidcStateService.ConsumedState consumed =
        uppercase.consume("github", state, challenge, NOW.plusSeconds(1));

    assertEquals(ClientType.WEB, consumed.clientType());
  }

  @Test
  void providerIsCryptographicallyBoundToState() {
    OidcStateService service = new OidcStateService(ROOT_SECRET, 10_000);
    byte[] challenge = sha256("verifier");
    String state =
        service.issue("github", ClientType.WEB, challenge, NOW.plusSeconds(600), nonce((byte) 2));

    assertThrows(
        AuthenticationException.class,
        () -> service.consume("google", state, challenge, NOW.plusSeconds(1)));
  }

  @Test
  void clientTypePrefixIsCryptographicallyBoundToState() {
    OidcStateService service = new OidcStateService(ROOT_SECRET, 10_000);
    byte[] challenge = sha256("verifier");
    String state =
        service.issue("github", ClientType.WEB, challenge, NOW.plusSeconds(600), nonce((byte) 3));
    String tampered = "d." + state.substring(2);

    assertThrows(
        AuthenticationException.class,
        () -> service.consume("github", tampered, challenge, NOW.plusSeconds(1)));
  }

  @Test
  void rejectsWrongPkceChallengeAndExpiredState() {
    OidcStateService service = new OidcStateService(ROOT_SECRET, 10_000);
    byte[] challenge = sha256("verifier");
    String state =
        service.issue(
            "github", ClientType.DESKTOP, challenge, NOW.plusSeconds(10), nonce((byte) 4));

    assertThrows(
        AuthenticationException.class,
        () -> service.consume("github", state, sha256("wrong"), NOW.plusSeconds(1)));
    assertThrows(
        AuthenticationException.class,
        () -> service.consume("github", state, challenge, NOW.plusSeconds(10)));
  }

  @Test
  void rejectsReplayOnTheSameInstance() {
    OidcStateService service = new OidcStateService(ROOT_SECRET, 10_000);
    byte[] challenge = sha256("verifier");
    String state =
        service.issue("github", ClientType.WEB, challenge, NOW.plusSeconds(600), nonce((byte) 5));

    service.consume("github", state, challenge, NOW.plusSeconds(1));

    assertThrows(
        AuthenticationException.class,
        () -> service.consume("github", state, challenge, NOW.plusSeconds(2)));
  }

  @Test
  void replayCacheFailsClosedAtCapacityAndReclaimsExpiredEntries() {
    OidcStateService service = new OidcStateService(ROOT_SECRET, 1);
    byte[] challenge = sha256("verifier");
    String first =
        service.issue("github", ClientType.WEB, challenge, NOW.plusSeconds(10), nonce((byte) 6));
    String second =
        service.issue("github", ClientType.WEB, challenge, NOW.plusSeconds(20), nonce((byte) 7));

    service.consume("github", first, challenge, NOW.plusSeconds(1));
    assertThrows(
        AuthenticationException.class,
        () -> service.consume("github", second, challenge, NOW.plusSeconds(2)));

    OidcStateService.ConsumedState consumed =
        service.consume("github", second, challenge, NOW.plusSeconds(11));
    assertEquals(ClientType.WEB, consumed.clientType());
  }

  @Test
  void rejectsInvalidRootSecret() {
    assertThrows(IllegalArgumentException.class, () -> new OidcStateService("short", 10_000));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new OidcStateService(
                "gggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggg", 10_000));
    assertThrows(IllegalArgumentException.class, () -> new OidcStateService(ROOT_SECRET, 0));
  }

  private static byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static byte[] nonce(byte value) {
    byte[] nonce = new byte[16];
    Arrays.fill(nonce, value);
    return nonce;
  }
}
