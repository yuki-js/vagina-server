package app.vagina.server.service;

import app.vagina.server.config.OidcConfig;
import app.vagina.server.domain.error.AuthenticationException;
import app.vagina.server.domain.error.ProviderNotImplementedException;
import app.vagina.server.domain.error.ValidationException;
import app.vagina.server.entity.AuthnProvider;
import app.vagina.server.entity.ClientType;
import app.vagina.server.entity.RefreshToken;
import app.vagina.server.entity.User;
import app.vagina.server.mapper.AuthnProviderMapper;
import app.vagina.server.mapper.RefreshTokenMapper;
import app.vagina.server.service.oidcprovider.OidcProviderBase;
import app.vagina.server.service.oidcprovider.OidcProviderBase.OidcTokenSet;
import app.vagina.server.service.oidcprovider.OidcProviderBase.OidcUserInfo;
import app.vagina.server.service.oidcprovider.OidcProviderRegistry;
import app.vagina.server.support.Util;
import io.smallrye.jwt.algorithm.SignatureAlgorithm;
import io.smallrye.jwt.auth.principal.JWTParser;
import io.smallrye.jwt.auth.principal.ParseException;
import io.smallrye.jwt.build.Jwt;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.jwt.JsonWebToken;

@ApplicationScoped
public class AuthService {
  private static final Set<String> DECLARED_BUT_NOT_IMPLEMENTED_PROVIDERS =
      Set.of("apple", "twitter");
  private static final int OIDC_STATE_NONCE_BYTES = 16;

  public record CreatedOidcState(
      String rawState,
      String redirectUri,
      String codeChallenge,
      String codeChallengeMethod,
      long expiresIn) {}

  public record ConsumedOidcState(String redirectUri) {}

  public record IssuedRefreshToken(String rawToken, RefreshToken persistedToken) {}

  public record RotateResult(String rawToken, RefreshToken persistedToken) {}

  public record ConfiguredOidcProvider(String id, String displayName) {}

  @ConfigProperty(name = "vagina.auth.access-token.lifespan")
  Long accessTokenLifespan;

  @ConfigProperty(name = "vagina.auth.refresh-token.lifespan")
  Long refreshTokenLifespan;

  @ConfigProperty(name = "vagina.auth.oauth.state.lifespan")
  Long stateLifespan;

  @ConfigProperty(name = "smallrye.jwt.new-token.issuer")
  String jwtIssuer;

  @Inject OidcConfig oidcConfig;
  @Inject OidcProviderRegistry oidcProviderRegistry;
  @Inject OidcStateService oidcStateService;
  @Inject RefreshTokenMapper refreshTokenMapper;
  @Inject AuthnProviderMapper authnProviderMapper;
  @Inject UserService userService;
  @Inject JWTParser jwtParser;

  public OidcProviderBase resolveProvider(String providerKey) {
    if (providerKey == null || providerKey.isBlank()) {
      throw new ValidationException("OIDC provider is required");
    }
    OidcProviderRegistry.RegisteredProvider provider =
        oidcProviderRegistry.resolveConfigured(providerKey);
    if (provider != null) {
      return provider.implementation();
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

  public List<ConfiguredOidcProvider> listConfiguredOidcProviders() {
    return oidcProviderRegistry.listConfigured().stream()
        .map(provider -> new ConfiguredOidcProvider(provider.id(), provider.displayName()))
        .toList();
  }

  public CreatedOidcState createState(
      String providerKey, ClientType clientType, String codeChallenge, String codeChallengeMethod) {
    ClientType effectiveClientType = clientType == null ? ClientType.WEB : clientType;
    String redirectUri = resolveRedirectUri(effectiveClientType);
    String normalizedCodeChallenge = validateCodeChallenge(codeChallenge);
    String normalizedCodeChallengeMethod = validateCodeChallengeMethod(codeChallengeMethod);
    byte[] challengeBytes =
        decodeS256CodeChallenge(normalizedCodeChallenge, ValidationException::new);
    byte[] nonce = Util.randomBytes(OIDC_STATE_NONCE_BYTES);
    String rawState =
        oidcStateService.issue(
            providerKey,
            effectiveClientType,
            challengeBytes,
            Instant.now().plusSeconds(stateLifespan),
            nonce);

    return new CreatedOidcState(
        rawState,
        redirectUri,
        normalizedCodeChallenge,
        normalizedCodeChallengeMethod,
        stateLifespan);
  }

  public ConsumedOidcState consumeState(String providerKey, String rawState, String codeVerifier) {
    String normalizedCodeVerifier = validateCodeVerifier(codeVerifier);
    byte[] challengeBytes =
        decodeS256CodeChallenge(
            generateS256CodeChallenge(normalizedCodeVerifier), AuthenticationException::new);
    OidcStateService.ConsumedState consumedState =
        oidcStateService.consume(providerKey, rawState, challengeBytes, Instant.now());
    return new ConsumedOidcState(resolveRedirectUri(consumedState.clientType()));
  }

  public String resolveRedirectUri(ClientType clientType) {
    ClientType effectiveClientType = clientType == null ? ClientType.WEB : clientType;
    OidcConfig.RedirectUriConfig redirectUri = oidcConfig.redirectUri();
    return switch (effectiveClientType) {
      case WEB -> requiredRedirectUri(redirectUri.web(), "web");
      case MOBILE ->
          !redirectUri.mobile().isBlank()
              ? redirectUri.mobile()
              : requiredRedirectUri(redirectUri.web(), "web");
      case DESKTOP ->
          !redirectUri.desktop().isBlank()
              ? redirectUri.desktop()
              : requiredRedirectUri(redirectUri.web(), "web");
    };
  }

  public static String generateS256CodeChallenge(String codeVerifier) {
    byte[] hash = Util.sha256(codeVerifier, StandardCharsets.US_ASCII);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
  }

  public String generateAccessToken(User user) {
    List<AuthnProvider> authnProviders =
        authnProviderMapper.findByUserId(user.getId()).stream()
            .map(this::toAuthnProviderDomain)
            .toList();
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

  @Transactional
  public IssuedRefreshToken issueRefreshToken(Long userId) {
    String rawToken = Util.hex(Util.randomBytes(32));
    String tokenFamily = UUID.randomUUID().toString();
    LocalDateTime now = LocalDateTime.now();

    RefreshToken refreshToken =
        new RefreshToken(
            null,
            userId,
            Util.hex(Util.sha256(rawToken)),
            tokenFamily,
            now,
            now.plusSeconds(refreshTokenLifespan),
            null,
            null,
            null,
            now,
            now);
    RefreshTokenMapper.Row row = toRefreshTokenRow(refreshToken);
    refreshTokenMapper.insert(row);
    refreshToken.setGeneratedId(row.getId());

    return new IssuedRefreshToken(rawToken, refreshToken);
  }

  @Transactional
  public Optional<RotateResult> rotateRefreshToken(String rawToken) {
    Optional<RefreshToken> currentOpt =
        refreshTokenMapper
            .findByTokenHash(Util.hex(Util.sha256(rawToken)))
            .map(this::toRefreshTokenDomain);
    if (currentOpt.isEmpty()) {
      return Optional.empty();
    }

    RefreshToken current = currentOpt.get();
    LocalDateTime now = LocalDateTime.now();

    if (current.isRevoked()) {
      revokeTokenFamily(current.getTokenFamily(), now);
      return Optional.empty();
    }

    if (current.isExpiredAt(now)) {
      return Optional.empty();
    }

    if (current.isRotated()) {
      revokeTokenFamily(current.getTokenFamily(), now);
      return Optional.empty();
    }

    int updated = refreshTokenMapper.markRotatedIfActive(current.getId(), now, now, now);
    if (updated != 1) {
      revokeTokenFamily(current.getTokenFamily(), now);
      return Optional.empty();
    }
    current.markRotated(now);

    String newRawToken = Util.hex(Util.randomBytes(32));
    RefreshToken replacement =
        current.createReplacement(
            Util.hex(Util.sha256(newRawToken)), now.plusSeconds(refreshTokenLifespan), now);
    RefreshTokenMapper.Row replacementRow = toRefreshTokenRow(replacement);
    refreshTokenMapper.insert(replacementRow);
    replacement.setGeneratedId(replacementRow.getId());

    return Optional.of(new RotateResult(newRawToken, replacement));
  }

  @Transactional
  public void revokeRefreshToken(String rawToken) {
    Optional<RefreshToken> refreshTokenOpt =
        refreshTokenMapper
            .findByTokenHash(Util.hex(Util.sha256(rawToken)))
            .map(this::toRefreshTokenDomain);
    if (refreshTokenOpt.isEmpty()) {
      return;
    }

    RefreshToken refreshToken = refreshTokenOpt.get();
    refreshToken.revoke(LocalDateTime.now());
    refreshTokenMapper.update(toRefreshTokenRow(refreshToken));
  }

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
    if (!user.isActive()) {
      return Optional.empty();
    }

    return Optional.of(user);
  }

  public boolean hasBearerToken(String authHeader) {
    return authHeader != null && authHeader.startsWith("Bearer ");
  }

  private String requiredRedirectUri(String redirectUri, String clientType) {
    if (redirectUri == null || redirectUri.isBlank()) {
      throw new IllegalStateException(
          "Missing OIDC redirect URI configuration for clientType=" + clientType);
    }
    return redirectUri;
  }

  private void revokeTokenFamily(String tokenFamily, LocalDateTime now) {
    List<RefreshToken> familyTokens =
        refreshTokenMapper.findByTokenFamily(tokenFamily).stream()
            .map(this::toRefreshTokenDomain)
            .toList();
    for (RefreshToken familyToken : familyTokens) {
      familyToken.revoke(now);
      refreshTokenMapper.update(toRefreshTokenRow(familyToken));
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

  private byte[] decodeS256CodeChallenge(
      String codeChallenge, Function<String, ? extends RuntimeException> exceptionFactory) {
    byte[] decoded;
    try {
      decoded = Base64.getUrlDecoder().decode(codeChallenge);
    } catch (IllegalArgumentException e) {
      throw exceptionFactory.apply("codeChallenge must be unpadded Base64URL");
    }
    if (decoded.length != 32
        || !Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(codeChallenge)) {
      throw exceptionFactory.apply("codeChallenge must be an S256 SHA-256 digest");
    }
    return decoded;
  }

  private AuthnProvider toAuthnProviderDomain(AuthnProviderMapper.Row row) {
    return new AuthnProvider(
        row.getId(),
        row.getUserId(),
        row.getAuthMethod(),
        row.getProviderKey(),
        row.getAuthIdentifier(),
        row.getExternalSubject(),
        row.getProviderLogin(),
        row.getDisplayName(),
        row.getAvatarUrl(),
        row.getEmail(),
        row.getEmailVerified(),
        row.getCreatedAt(),
        row.getUpdatedAt());
  }

  private RefreshToken toRefreshTokenDomain(RefreshTokenMapper.Row row) {
    return new RefreshToken(
        row.getId(),
        row.getUserId(),
        row.getTokenHash(),
        row.getTokenFamily(),
        row.getIssuedAt(),
        row.getExpiresAt(),
        row.getRotatedAt(),
        row.getRevokedAt(),
        row.getLastUsedAt(),
        row.getCreatedAt(),
        row.getUpdatedAt());
  }

  private RefreshTokenMapper.Row toRefreshTokenRow(RefreshToken refreshToken) {
    RefreshTokenMapper.Row row = new RefreshTokenMapper.Row();
    row.setId(refreshToken.getId());
    row.setUserId(refreshToken.getUserId());
    row.setTokenHash(refreshToken.getTokenHash());
    row.setTokenFamily(refreshToken.getTokenFamily());
    row.setIssuedAt(refreshToken.getIssuedAt());
    row.setExpiresAt(refreshToken.getExpiresAt());
    row.setRotatedAt(refreshToken.getRotatedAt());
    row.setRevokedAt(refreshToken.getRevokedAt());
    row.setLastUsedAt(refreshToken.getLastUsedAt());
    row.setCreatedAt(refreshToken.getCreatedAt());
    row.setUpdatedAt(refreshToken.getUpdatedAt());
    return row;
  }
}
