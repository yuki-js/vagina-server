package app.vagina.server.service;

import app.vagina.server.entity.AuthMethod;
import app.vagina.server.entity.ClientType;
import app.vagina.server.entity.OAuthLoginAttempt;
import app.vagina.server.mapper.OAuthLoginAttemptMapper;
import app.vagina.server.usecase.InvalidOidcAuthorizationException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class OidcStateService {

  private static final SecureRandom SECURE_RANDOM = new SecureRandom();
  private static final HexFormat HEX_FORMAT = HexFormat.of();

  @ConfigProperty(name = "vagina.auth.oauth.state.lifespan")
  Long stateLifespan;

  @Inject OAuthLoginAttemptMapper oauthLoginAttemptMapper;

  @Transactional
  public CreatedOidcState createState(
      String providerKey,
      ClientType clientType,
      String redirectUri,
      String codeChallenge,
      String codeChallengeMethod) {
    String rawState = randomToken();
    LocalDateTime now = LocalDateTime.now();

    OAuthLoginAttempt attempt = new OAuthLoginAttempt();
    attempt.setStateHash(sha256(rawState));
    attempt.setAuthMethod(AuthMethod.OIDC);
    attempt.setProviderKey(providerKey);
    attempt.setClientType(clientType);
    attempt.setRedirectUri(redirectUri);
    attempt.setCodeChallenge(codeChallenge);
    attempt.setCodeChallengeMethod(codeChallengeMethod);
    attempt.setExpiresAt(now.plusSeconds(stateLifespan));
    attempt.setConsumedAt(null);
    attempt.setSysmeta(null);
    attempt.setCreatedAt(now);
    attempt.setUpdatedAt(now);
    oauthLoginAttemptMapper.insert(attempt);

    return new CreatedOidcState(rawState, stateLifespan);
  }

  @Transactional
  public OAuthLoginAttempt consumeState(
      String providerKey, String rawState, String redirectUri, String codeVerifier) {
    OAuthLoginAttempt attempt =
        oauthLoginAttemptMapper
            .findByStateHash(sha256(rawState))
            .orElseThrow(() -> new InvalidOidcAuthorizationException("Unknown OIDC state"));

    LocalDateTime now = LocalDateTime.now();

    if (!providerKey.equals(attempt.getProviderKey())) {
      throw new InvalidOidcAuthorizationException("OIDC provider mismatch");
    }
    if (attempt.getConsumedAt() != null) {
      throw new InvalidOidcAuthorizationException("OIDC state already consumed");
    }
    if (attempt.getExpiresAt() == null || !attempt.getExpiresAt().isAfter(now)) {
      throw new InvalidOidcAuthorizationException("OIDC state expired");
    }
    if (!redirectUri.equals(attempt.getRedirectUri())) {
      throw new InvalidOidcAuthorizationException("OIDC redirect URI mismatch");
    }
    if (!matchesPkce(codeVerifier, attempt.getCodeChallenge(), attempt.getCodeChallengeMethod())) {
      throw new InvalidOidcAuthorizationException("OIDC PKCE verification failed");
    }

    int updated = oauthLoginAttemptMapper.markConsumedIfUnused(attempt.getId(), now, now);
    if (updated != 1) {
      throw new InvalidOidcAuthorizationException("OIDC state already consumed");
    }

    attempt.setConsumedAt(now);
    attempt.setUpdatedAt(now);
    return attempt;
  }

  private boolean matchesPkce(String codeVerifier, String expectedCodeChallenge, String method) {
    if (!"S256".equals(method)) {
      throw new InvalidOidcAuthorizationException("Unsupported PKCE method: " + method);
    }
    return expectedCodeChallenge.equals(generateS256CodeChallenge(codeVerifier));
  }

  public static String generateS256CodeChallenge(String codeVerifier) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
      return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  private String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HEX_FORMAT.formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  private String randomToken() {
    byte[] randomBytes = new byte[32];
    SECURE_RANDOM.nextBytes(randomBytes);
    return HEX_FORMAT.formatHex(randomBytes);
  }

  public record CreatedOidcState(String rawState, long expiresIn) {}
}
