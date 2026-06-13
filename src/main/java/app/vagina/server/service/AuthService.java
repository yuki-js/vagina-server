package app.vagina.server.service;

import app.vagina.server.entity.AuthMethod;
import app.vagina.server.entity.AuthnProvider;
import app.vagina.server.entity.ClientType;
import app.vagina.server.entity.OAuthLoginAttempt;
import app.vagina.server.entity.RefreshToken;
import app.vagina.server.entity.User;
import app.vagina.server.mapper.AuthnProviderMapper;
import app.vagina.server.mapper.OAuthLoginAttemptMapper;
import app.vagina.server.mapper.RefreshTokenMapper;
import app.vagina.server.service.oidcprovider.OidcProviderBase;
import app.vagina.server.service.oidcprovider.OidcProviderBase.OidcTokenSet;
import app.vagina.server.service.oidcprovider.OidcProviderBase.OidcUserInfo;
import app.vagina.server.support.AppException;
import io.smallrye.jwt.algorithm.SignatureAlgorithm;
import io.smallrye.jwt.build.Jwt;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.jwt.JsonWebToken;

@ApplicationScoped
public class AuthService {

  // --- records ---
  public record CreatedOidcState(String rawState, long expiresIn) {}

  public record IssuedRefreshToken(String rawToken, RefreshToken persistedToken) {}

  public record RotateResult(String rawToken, RefreshToken persistedToken) {}

  // --- config ---
  @ConfigProperty(name = "vagina.auth.access-token.lifespan")
  Long accessTokenLifespan;

  @ConfigProperty(name = "vagina.auth.refresh-token.lifespan")
  Long refreshTokenLifespan;

  @ConfigProperty(name = "vagina.auth.oauth.state.lifespan")
  Long stateLifespan;

  @ConfigProperty(name = "smallrye.jwt.new-token.issuer")
  String jwtIssuer;

  // --- injects ---
  @Inject Instance<OidcProviderBase> oidcProviders;
  @Inject OAuthLoginAttemptMapper oauthLoginAttemptMapper;
  @Inject RefreshTokenMapper refreshTokenMapper;
  @Inject AuthnProviderMapper authnProviderMapper;
  @Inject UserService userService;

  private static final ThreadLocal<SecureRandom> SECURE_RANDOM =
      ThreadLocal.withInitial(SecureRandom::new);
  private static final HexFormat HEX_FORMAT = HexFormat.of();

  // ========================
  // OIDC Provider delegation
  // ========================

  public OidcProviderBase resolveProvider(String providerKey) {
    for (OidcProviderBase provider : oidcProviders) {
      if (providerKey.equals(provider.getProviderKey())) {
        return provider;
      }
    }
    throw new AppException(
        Response.Status.BAD_REQUEST, "Unsupported OIDC provider: " + providerKey);
  }

  public String buildAuthorizationUrl(
      String providerKey,
      String redirectUri,
      String state,
      String codeChallenge,
      String codeChallengeMethod) {
    return resolveProvider(providerKey)
        .buildAuthorizationUrl(redirectUri, state, codeChallenge, codeChallengeMethod);
  }

  public OidcTokenSet exchangeAuthorizationCode(
      String providerKey, String code, String redirectUri, String codeVerifier) {
    return resolveProvider(providerKey).exchangeAuthorizationCode(code, redirectUri, codeVerifier);
  }

  public OidcUserInfo fetchUserInfo(String providerKey, String accessToken) {
    return resolveProvider(providerKey).fetchUserInfo(accessToken);
  }

  // ========================
  // OIDC State
  // ========================

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
            .orElseThrow(
                () -> new AppException(Response.Status.UNAUTHORIZED, "Unknown OIDC state"));

    LocalDateTime now = LocalDateTime.now();

    if (!providerKey.equals(attempt.getProviderKey())) {
      throw new AppException(Response.Status.UNAUTHORIZED, "OIDC provider mismatch");
    }
    if (attempt.getConsumedAt() != null) {
      throw new AppException(Response.Status.UNAUTHORIZED, "OIDC state already consumed");
    }
    if (attempt.getExpiresAt() == null || !attempt.getExpiresAt().isAfter(now)) {
      throw new AppException(Response.Status.UNAUTHORIZED, "OIDC state expired");
    }
    if (!redirectUri.equals(attempt.getRedirectUri())) {
      throw new AppException(Response.Status.UNAUTHORIZED, "OIDC redirect URI mismatch");
    }
    if (!matchesPkce(codeVerifier, attempt.getCodeChallenge(), attempt.getCodeChallengeMethod())) {
      throw new AppException(Response.Status.UNAUTHORIZED, "OIDC PKCE verification failed");
    }

    int updated = oauthLoginAttemptMapper.markConsumedIfUnused(attempt.getId(), now, now);
    if (updated != 1) {
      throw new AppException(Response.Status.UNAUTHORIZED, "OIDC state already consumed");
    }

    attempt.setConsumedAt(now);
    attempt.setUpdatedAt(now);
    return attempt;
  }

  public static String generateS256CodeChallenge(String codeVerifier) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  // ========================
  // JWT
  // ========================

  public String generateAccessToken(User user) {
    List<AuthnProvider> authnProviders = authnProviderMapper.findByUserId(user.getId());
    if (authnProviders.isEmpty()) {
      throw new IllegalStateException("User has no authentication providers");
    }

    AuthnProvider primaryAuthnProvider = authnProviders.get(0);
    String subject = "user:" + user.getId();
    String authMethod = primaryAuthnProvider.getAuthMethod().getValue();

    return Jwt.issuer(jwtIssuer)
        .upn(subject)
        .subject(subject)
        .groups(authMethod)
        .claim("uid", user.getId())
        .claim("auth_method", authMethod)
        .claim("provider_key", primaryAuthnProvider.getProviderKey())
        .claim("provider_subject", primaryAuthnProvider.getEffectiveSubject())
        .expiresIn(accessTokenLifespan)
        .jws()
        .algorithm(SignatureAlgorithm.ES256)
        .sign();
  }

  // ========================
  // Refresh Token
  // ========================

  @Transactional
  public IssuedRefreshToken issueRefreshToken(Long userId) {
    String rawToken = randomOpaqueToken();
    String tokenFamily = UUID.randomUUID().toString();
    LocalDateTime now = LocalDateTime.now();

    RefreshToken refreshToken = new RefreshToken();
    refreshToken.setUserId(userId);
    refreshToken.setTokenHash(sha256(rawToken));
    refreshToken.setTokenFamily(tokenFamily);
    refreshToken.setIssuedAt(now);
    refreshToken.setExpiresAt(now.plusSeconds(refreshTokenLifespan));
    refreshToken.setRotatedAt(null);
    refreshToken.setRevokedAt(null);
    refreshToken.setLastUsedAt(null);
    refreshToken.setSysmeta(null);
    refreshToken.setCreatedAt(now);
    refreshToken.setUpdatedAt(now);
    refreshTokenMapper.insert(refreshToken);

    return new IssuedRefreshToken(rawToken, refreshToken);
  }

  @Transactional
  public Optional<RotateResult> rotateRefreshToken(String rawToken) {
    Optional<RefreshToken> currentOpt = refreshTokenMapper.findByTokenHash(sha256(rawToken));
    if (currentOpt.isEmpty()) {
      return Optional.empty();
    }

    RefreshToken current = currentOpt.get();
    LocalDateTime now = LocalDateTime.now();

    if (isRevoked(current) || isExpired(current)) {
      return Optional.empty();
    }

    if (current.getRotatedAt() != null) {
      revokeTokenFamily(current.getTokenFamily(), now, "rotated_token_reuse_detected");
      return Optional.empty();
    }

    int updated = refreshTokenMapper.markRotatedIfActive(current.getId(), now, now, now);
    if (updated != 1) {
      revokeTokenFamily(current.getTokenFamily(), now, "rotated_token_reuse_detected");
      return Optional.empty();
    }

    String newRawToken = randomOpaqueToken();
    RefreshToken replacement = new RefreshToken();
    replacement.setUserId(current.getUserId());
    replacement.setTokenHash(sha256(newRawToken));
    replacement.setTokenFamily(current.getTokenFamily());
    replacement.setIssuedAt(now);
    replacement.setExpiresAt(now.plusSeconds(refreshTokenLifespan));
    replacement.setRotatedAt(null);
    replacement.setRevokedAt(null);
    replacement.setLastUsedAt(null);
    replacement.setSysmeta(current.getSysmeta());
    replacement.setCreatedAt(now);
    replacement.setUpdatedAt(now);
    refreshTokenMapper.insert(replacement);

    return Optional.of(new RotateResult(newRawToken, replacement));
  }

  @Transactional
  public void revokeRefreshToken(String rawToken) {
    Optional<RefreshToken> refreshTokenOpt = refreshTokenMapper.findByTokenHash(sha256(rawToken));
    if (refreshTokenOpt.isEmpty()) {
      return;
    }

    RefreshToken refreshToken = refreshTokenOpt.get();
    LocalDateTime now = LocalDateTime.now();
    refreshToken.setRevokedAt(now);
    refreshToken.setUpdatedAt(now);
    refreshTokenMapper.update(refreshToken);
  }

  // ========================
  // Authentication
  // ========================

  public Optional<User> authenticateFromJwt(JsonWebToken jwt) {
    if (jwt == null || jwt.getClaim("uid") == null) {
      return Optional.empty();
    }

    Object uidClaim = jwt.getClaim("uid");
    Long userId;
    if (uidClaim instanceof Long value) {
      userId = value;
    } else if (uidClaim instanceof Integer value) {
      userId = value.longValue();
    } else {
      userId = Long.parseLong(String.valueOf(uidClaim));
    }

    return userService.findById(userId);
  }

  public boolean hasBearerToken(String authHeader) {
    return authHeader != null && authHeader.startsWith("Bearer ");
  }

  // ========================
  // Private helpers
  // ========================

  private boolean matchesPkce(String codeVerifier, String expectedCodeChallenge, String method) {
    if (!"S256".equals(method)) {
      throw new AppException(Response.Status.UNAUTHORIZED, "Unsupported PKCE method: " + method);
    }
    return expectedCodeChallenge.equals(generateS256CodeChallenge(codeVerifier));
  }

  private void revokeTokenFamily(String tokenFamily, LocalDateTime now, String reason) {
    List<RefreshToken> familyTokens = refreshTokenMapper.findByTokenFamily(tokenFamily);
    for (RefreshToken familyToken : familyTokens) {
      familyToken.setRevokedAt(now);
      familyToken.setUpdatedAt(now);
      if (familyToken.getSysmeta() == null || familyToken.getSysmeta().isBlank()) {
        familyToken.setSysmeta("{\"reason\":\"" + reason + "\"}");
      }
      refreshTokenMapper.update(familyToken);
    }
  }

  private boolean isRevoked(RefreshToken refreshToken) {
    return refreshToken.getRevokedAt() != null;
  }

  private boolean isExpired(RefreshToken refreshToken) {
    return refreshToken.getExpiresAt() == null
        || !refreshToken.getExpiresAt().isAfter(LocalDateTime.now());
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
    SECURE_RANDOM.get().nextBytes(randomBytes);
    return HEX_FORMAT.formatHex(randomBytes);
  }

  private String randomOpaqueToken() {
    return randomToken();
  }
}
