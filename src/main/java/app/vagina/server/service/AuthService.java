package app.vagina.server.service;

import app.vagina.server.domain.error.AuthenticationException;
import app.vagina.server.domain.error.ProviderNotImplementedException;
import app.vagina.server.domain.error.ValidationException;
import app.vagina.server.entity.AccountLifecycle;
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
import app.vagina.server.support.Util;
import io.smallrye.jwt.algorithm.SignatureAlgorithm;
import io.smallrye.jwt.auth.principal.JWTParser;
import io.smallrye.jwt.auth.principal.ParseException;
import io.smallrye.jwt.build.Jwt;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.jwt.JsonWebToken;

@ApplicationScoped
public class AuthService {
  private static final Set<String> DECLARED_BUT_NOT_IMPLEMENTED_PROVIDERS =
      Set.of("google", "apple", "twitter");

  // --- records ---
  public record CreatedOidcState(
      String rawState,
      String redirectUri,
      String codeChallenge,
      String codeChallengeMethod,
      long expiresIn) {}

  public record IssuedRefreshToken(String rawToken, RefreshToken persistedToken) {}

  public record RotateResult(String rawToken, RefreshToken persistedToken) {}

  // --- config ---
  @ConfigProperty(name = "vagina.auth.access-token.lifespan")
  Long accessTokenLifespan;

  @ConfigProperty(name = "vagina.auth.refresh-token.lifespan")
  Long refreshTokenLifespan;

  @ConfigProperty(name = "vagina.auth.oauth.state.lifespan")
  Long stateLifespan;

  @ConfigProperty(name = "vagina.auth.oidc.redirect-uri.web", defaultValue = "")
  String webRedirectUri;

  @ConfigProperty(name = "vagina.auth.oidc.redirect-uri.mobile", defaultValue = "")
  String mobileRedirectUri;

  @ConfigProperty(name = "vagina.auth.oidc.redirect-uri.desktop", defaultValue = "")
  String desktopRedirectUri;

  @ConfigProperty(name = "smallrye.jwt.new-token.issuer")
  String jwtIssuer;

  // --- injects ---
  @Inject Instance<OidcProviderBase> oidcProviders;
  @Inject OAuthLoginAttemptMapper oauthLoginAttemptMapper;
  @Inject RefreshTokenMapper refreshTokenMapper;
  @Inject AuthnProviderMapper authnProviderMapper;
  @Inject UserService userService;
  @Inject JWTParser jwtParser;

  // ========================
  // OIDC Provider delegation
  // ========================

  public OidcProviderBase resolveProvider(String providerKey) {
    if (providerKey == null || providerKey.isBlank()) {
      throw new ValidationException("OIDC provider is required");
    }
    for (OidcProviderBase provider : oidcProviders) {
      if (providerKey.equals(provider.getProviderKey()) && provider.isConfigured()) {
        return provider;
      }
    }
    if (DECLARED_BUT_NOT_IMPLEMENTED_PROVIDERS.contains(providerKey)) {
      throw new ProviderNotImplementedException("OIDC provider not implemented: " + providerKey);
    }
    throw new ValidationException("Unsupported OIDC provider: " + providerKey);
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
      String providerKey, ClientType clientType, String codeChallenge, String codeChallengeMethod) {
    String rawState = Util.randomHexToken();
    LocalDateTime now = LocalDateTime.now();
    String redirectUri = resolveRedirectUri(clientType);
    String normalizedCodeChallenge = validateCodeChallenge(codeChallenge);
    String normalizedCodeChallengeMethod = validateCodeChallengeMethod(codeChallengeMethod);

    OAuthLoginAttempt attempt = new OAuthLoginAttempt();
    attempt.setStateHash(Util.sha256Hex(rawState));
    attempt.setAuthMethod(AuthMethod.OIDC);
    attempt.setProviderKey(providerKey);
    attempt.setClientType(clientType);
    attempt.setRedirectUri(redirectUri);
    attempt.setCodeChallenge(normalizedCodeChallenge);
    attempt.setCodeChallengeMethod(normalizedCodeChallengeMethod);
    attempt.setExpiresAt(now.plusSeconds(stateLifespan));
    attempt.setConsumedAt(null);
    attempt.setSysmeta(null);
    attempt.setCreatedAt(now);
    attempt.setUpdatedAt(now);
    oauthLoginAttemptMapper.insert(attempt);

    return new CreatedOidcState(
        rawState,
        redirectUri,
        normalizedCodeChallenge,
        normalizedCodeChallengeMethod,
        stateLifespan);
  }

  @Transactional
  public OAuthLoginAttempt consumeState(String providerKey, String rawState, String codeVerifier) {
    OAuthLoginAttempt attempt =
        oauthLoginAttemptMapper
            .findByStateHash(Util.sha256Hex(rawState))
            .orElseThrow(() -> new AuthenticationException("Unknown OIDC state"));

    LocalDateTime now = LocalDateTime.now();

    if (!providerKey.equals(attempt.getProviderKey())) {
      throw new AuthenticationException("OIDC provider mismatch");
    }
    if (attempt.getConsumedAt() != null) {
      throw new AuthenticationException("OIDC state already consumed");
    }
    if (attempt.getExpiresAt() == null || !attempt.getExpiresAt().isAfter(now)) {
      throw new AuthenticationException("OIDC state expired");
    }
    String normalizedCodeVerifier = validateCodeVerifier(codeVerifier);
    if (!"S256".equals(attempt.getCodeChallengeMethod())) {
      throw new AuthenticationException(
          "Unsupported PKCE method: " + attempt.getCodeChallengeMethod());
    }
    if (!attempt.getCodeChallenge().equals(generateS256CodeChallenge(normalizedCodeVerifier))) {
      throw new AuthenticationException("OIDC PKCE verification failed");
    }

    int updated = oauthLoginAttemptMapper.markConsumedIfUnused(attempt.getId(), now, now);
    if (updated != 1) {
      throw new AuthenticationException("OIDC state already consumed");
    }

    attempt.setConsumedAt(now);
    attempt.setUpdatedAt(now);
    return attempt;
  }

  public String resolveRedirectUri(ClientType clientType) {
    ClientType effectiveClientType = clientType == null ? ClientType.WEB : clientType;
    return switch (effectiveClientType) {
      case WEB -> requiredRedirectUri(webRedirectUri, "web");
      case MOBILE ->
          (mobileRedirectUri != null && !mobileRedirectUri.isBlank())
              ? mobileRedirectUri
              : requiredRedirectUri(webRedirectUri, "web");
      case DESKTOP ->
          (desktopRedirectUri != null && !desktopRedirectUri.isBlank())
              ? desktopRedirectUri
              : requiredRedirectUri(webRedirectUri, "web");
    };
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
    String rawToken = Util.randomHexToken();
    String tokenFamily = UUID.randomUUID().toString();
    LocalDateTime now = LocalDateTime.now();

    RefreshToken refreshToken = new RefreshToken();
    refreshToken.setUserId(userId);
    refreshToken.setTokenHash(Util.sha256Hex(rawToken));
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
    Optional<RefreshToken> currentOpt =
        refreshTokenMapper.findByTokenHash(Util.sha256Hex(rawToken));
    if (currentOpt.isEmpty()) {
      return Optional.empty();
    }

    RefreshToken current = currentOpt.get();
    LocalDateTime now = LocalDateTime.now();

    if (current.getRevokedAt() != null) {
      revokeTokenFamily(current.getTokenFamily(), now, "rotated_token_reuse_detected");
      return Optional.empty();
    }

    if (current.getExpiresAt() == null || !current.getExpiresAt().isAfter(now)) {
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

    String newRawToken = Util.randomHexToken();
    RefreshToken replacement = new RefreshToken();
    replacement.setUserId(current.getUserId());
    replacement.setTokenHash(Util.sha256Hex(newRawToken));
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
    Optional<RefreshToken> refreshTokenOpt =
        refreshTokenMapper.findByTokenHash(Util.sha256Hex(rawToken));
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

  /**
   * Authenticate a raw JWT string carried in-band (for example a VHRP/1 {@code session.open}
   * token).
   *
   * <p>REST requests are authenticated from the {@code Authorization} header, which MP-JWT parses
   * for us; an in-band token arrives as a raw string with no header. This {@link String} overload
   * does that parsing itself and then delegates to {@link #authenticateFromJwt(JsonWebToken)} to
   * resolve the user, keeping both entry points behaviourally identical.
   *
   * <p>Consistent with the {@link JsonWebToken} overload, this never throws: a missing, malformed,
   * or unresolvable token yields {@link Optional#empty()}. Callers that need to treat that as a
   * failure decide so themselves (for example via {@code orElseThrow}).
   *
   * @param rawToken the raw JWT string
   * @return the authenticated user, or empty if the token is missing, invalid, or unresolvable
   */
  public Optional<User> authenticateFromJwt(String rawToken) {
    if (rawToken == null || rawToken.isBlank()) {
      return Optional.empty();
    }
    try {
      return authenticateFromJwt(jwtParser.parse(rawToken));
    } catch (ParseException e) {
      return Optional.empty();
    }
  }

  public Optional<User> authenticateFromJwt(JsonWebToken jwt) {
    if (jwt == null || jwt.getClaim("uid") == null) {
      return Optional.empty();
    }

    Object uidClaim = jwt.getClaim("uid");
    Long userId;
    try {
      if (uidClaim instanceof Long value) {
        userId = value;
      } else if (uidClaim instanceof Integer value) {
        userId = value.longValue();
      } else {
        userId = Long.parseLong(String.valueOf(uidClaim));
      }
    } catch (NumberFormatException e) {
      return Optional.empty();
    }

    Optional<User> userOpt = userService.findById(userId);
    if (userOpt.isEmpty()) {
      return Optional.empty();
    }

    User user = userOpt.get();
    if (user.getAccountLifecycle() != AccountLifecycle.ACTIVE) {
      return Optional.empty();
    }

    return Optional.of(user);
  }

  public boolean hasBearerToken(String authHeader) {
    return authHeader != null && authHeader.startsWith("Bearer ");
  }

  // ========================
  // Private helpers
  // ========================

  private String requiredRedirectUri(String redirectUri, String clientType) {
    if (redirectUri == null || redirectUri.isBlank()) {
      throw new IllegalStateException(
          "Missing OIDC redirect URI configuration for clientType=" + clientType);
    }
    return redirectUri;
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

  private String validateCodeChallenge(String codeChallenge) {
    if (codeChallenge == null) {
      throw new ValidationException("codeChallenge is required");
    }
    String normalized = codeChallenge.trim();
    if (normalized.isEmpty()) {
      throw new ValidationException("codeChallenge is required");
    }
    return normalized;
  }

  private String validateCodeChallengeMethod(String codeChallengeMethod) {
    if (codeChallengeMethod == null) {
      throw new ValidationException("codeChallengeMethod is required");
    }
    String normalized = codeChallengeMethod.trim();
    if (!"S256".equals(normalized)) {
      throw new ValidationException("Unsupported codeChallengeMethod: " + normalized);
    }
    return normalized;
  }

  private String validateCodeVerifier(String codeVerifier) {
    if (codeVerifier == null) {
      throw new AuthenticationException("codeVerifier is required");
    }
    String normalized = codeVerifier.trim();
    if (normalized.isEmpty()) {
      throw new AuthenticationException("codeVerifier is required");
    }
    return normalized;
  }
}
