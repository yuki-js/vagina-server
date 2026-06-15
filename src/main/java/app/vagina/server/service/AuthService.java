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
import app.vagina.server.support.Util;
import io.smallrye.jwt.algorithm.SignatureAlgorithm;
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
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.jwt.JsonWebToken;

@ApplicationScoped
public class AuthService {

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

  @ConfigProperty(name = "vagina.secret")
  Optional<String> systemSecret;


  @ConfigProperty(name = "smallrye.jwt.new-token.issuer")
  String jwtIssuer;

  // --- injects ---
  @Inject Instance<OidcProviderBase> oidcProviders;
  @Inject OAuthLoginAttemptMapper oauthLoginAttemptMapper;
  @Inject RefreshTokenMapper refreshTokenMapper;
  @Inject AuthnProviderMapper authnProviderMapper;
  @Inject UserService userService;

  private final String runtimePkceSecret = Util.randomHexToken();

  // ========================
  // OIDC Provider delegation
  // ========================

  public OidcProviderBase resolveProvider(String providerKey) {
    for (OidcProviderBase provider : oidcProviders) {
      if (providerKey.equals(provider.getProviderKey())) {
        return provider;
      }
    }
    throw new IllegalArgumentException("Unsupported OIDC provider: " + providerKey);
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
  public CreatedOidcState createState(String providerKey, ClientType clientType) {
    String rawState = Util.randomHexToken();
    LocalDateTime now = LocalDateTime.now();
    String redirectUri = resolveRedirectUri(clientType);
    String codeVerifier = buildPkceCodeVerifierForState(rawState);
    String codeChallenge = generateS256CodeChallenge(codeVerifier);
    String codeChallengeMethod = "S256";

    OAuthLoginAttempt attempt = new OAuthLoginAttempt();
    attempt.setStateHash(Util.sha256Hex(rawState));
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

    return new CreatedOidcState(
        rawState, redirectUri, codeChallenge, codeChallengeMethod, stateLifespan);
  }

  @Transactional
  public OAuthLoginAttempt consumeState(String providerKey, String rawState) {
    OAuthLoginAttempt attempt =
        oauthLoginAttemptMapper
            .findByStateHash(Util.sha256Hex(rawState))
            .orElseThrow(() -> new SecurityException("Unknown OIDC state"));

    LocalDateTime now = LocalDateTime.now();

    if (!providerKey.equals(attempt.getProviderKey())) {
      throw new SecurityException("OIDC provider mismatch");
    }
    if (attempt.getConsumedAt() != null) {
      throw new SecurityException("OIDC state already consumed");
    }
    if (attempt.getExpiresAt() == null || !attempt.getExpiresAt().isAfter(now)) {
      throw new SecurityException("OIDC state expired");
    }
    String codeVerifier = buildPkceCodeVerifierForState(rawState);
    if (!"S256".equals(attempt.getCodeChallengeMethod())) {
      throw new SecurityException("Unsupported PKCE method: " + attempt.getCodeChallengeMethod());
    }
    if (!attempt.getCodeChallenge().equals(generateS256CodeChallenge(codeVerifier))) {
      throw new SecurityException("OIDC PKCE verification failed");
    }

    int updated = oauthLoginAttemptMapper.markConsumedIfUnused(attempt.getId(), now, now);
    if (updated != 1) {
      throw new SecurityException("OIDC state already consumed");
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

  public String buildPkceCodeVerifierForState(String rawState) {
    if (rawState == null || rawState.isBlank()) {
      throw new SecurityException("OIDC state is required");
    }
    String pkceSecret = runtimePkceSecret;
    if (systemSecret != null) {
      Optional<String> configuredSecret = systemSecret.filter(value -> !value.isBlank());
      if (configuredSecret.isPresent()) {
        pkceSecret = configuredSecret.get();
      }
    }
    return generateS256CodeChallenge(pkceSecret + ":" + rawState);
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
    Optional<RefreshToken> currentOpt = refreshTokenMapper.findByTokenHash(Util.sha256Hex(rawToken));
    if (currentOpt.isEmpty()) {
      return Optional.empty();
    }

    RefreshToken current = currentOpt.get();
    LocalDateTime now = LocalDateTime.now();

    if (current.getRevokedAt() != null
        || current.getExpiresAt() == null
        || !current.getExpiresAt().isAfter(now)) {
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

}
