package app.vagina.server.usecase;

import app.vagina.server.entity.AuthnProvider;
import app.vagina.server.entity.User;
import app.vagina.server.service.HarigataOidcService;
import app.vagina.server.service.JwtService;
import app.vagina.server.service.OidcStateService;
import app.vagina.server.service.RefreshTokenService;
import app.vagina.server.service.UserService;
import app.vagina.server.service.model.OidcUserInfo;
import app.vagina.server.usecase.model.AuthSession;
import app.vagina.server.usecase.model.AuthUserView;
import app.vagina.server.usecase.model.OidcAuthorizationStart;
import app.vagina.server.usecase.model.OidcLoginExchangeRequest;
import app.vagina.server.usecase.model.OidcLoginStartRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class AuthUsecase {

  private static final String HARIGATA_PROVIDER = "harigata";

  @ConfigProperty(name = "vagina.auth.access-token.lifespan")
  Long accessTokenLifespan;

  @Inject
  UserService userService;
  @Inject
  JwtService jwtService;
  @Inject
  RefreshTokenService refreshTokenService;
  @Inject
  OidcStateService oidcStateService;
  @Inject
  HarigataOidcService harigataOidcService;

  public OidcAuthorizationStart startOidcLogin(String provider, OidcLoginStartRequest request) {
    ensureSupportedProvider(provider);
    var createdState = oidcStateService.createState(
        provider,
        request.clientType(),
        request.redirectUri(),
        request.codeChallenge(),
        request.codeChallengeMethod());

    String authorizationUrl = harigataOidcService.buildAuthorizationUrl(
        request.redirectUri(),
        createdState.rawState(),
        request.codeChallenge(),
        request.codeChallengeMethod());

    return new OidcAuthorizationStart(
        authorizationUrl, createdState.rawState(), createdState.expiresIn());
  }

  @Transactional
  public AuthSession exchangeOidcLogin(String provider, OidcLoginExchangeRequest request) {
    ensureSupportedProvider(provider);

    oidcStateService.consumeState(
        provider, request.state(), request.redirectUri(), request.codeVerifier());

    var tokenSet = harigataOidcService.exchangeAuthorizationCode(
        request.code(), request.redirectUri(), request.codeVerifier());
    OidcUserInfo oidcUserInfo = harigataOidcService.fetchUserInfo(tokenSet.accessToken());

    User user = userService.getOrCreateOidcUser(provider, oidcUserInfo);
    AuthnProvider primaryAuthnProvider = userService
        .findPrimaryAuthnProvider(user.getId())
        .orElseThrow(() -> new IllegalStateException("User has no auth provider"));

    String accessToken = jwtService.generateAccessToken(user);
    var issuedRefreshToken = refreshTokenService.issueRefreshToken(user.getId());

    return new AuthSession(
        toAuthUserView(user, primaryAuthnProvider),
        accessToken,
        issuedRefreshToken.rawToken(),
        accessTokenLifespan);
  }

  @Transactional
  public AuthSession refreshSession(String rawRefreshToken) {
    var rotatedRefreshToken = refreshTokenService
        .rotateRefreshToken(rawRefreshToken)
        .orElseThrow(InvalidRefreshTokenException::new);

    User user = userService
        .findById(rotatedRefreshToken.persistedToken().getUserId())
        .orElseThrow(() -> new IllegalStateException("User not found for refresh token"));
    AuthnProvider primaryAuthnProvider = userService
        .findPrimaryAuthnProvider(user.getId())
        .orElseThrow(() -> new IllegalStateException("User has no auth provider"));

    String accessToken = jwtService.generateAccessToken(user);

    return new AuthSession(
        toAuthUserView(user, primaryAuthnProvider),
        accessToken,
        rotatedRefreshToken.rawToken(),
        accessTokenLifespan);
  }

  @Transactional
  public void logout(String rawRefreshToken) {
    refreshTokenService.revokeRefreshToken(rawRefreshToken);
  }

  public AuthUserView getCurrentUser(Long userId) {
    User user = userService
        .findById(userId)
        .orElseThrow(() -> new IllegalStateException("Authenticated user not found"));
    AuthnProvider primaryAuthnProvider = userService.findPrimaryAuthnProvider(userId).orElse(null);
    return toAuthUserView(user, primaryAuthnProvider);
  }

  private void ensureSupportedProvider(String provider) {
    if (!HARIGATA_PROVIDER.equals(provider)) {
      throw new UnsupportedAuthProviderException(provider);
    }
  }

  private AuthUserView toAuthUserView(User user, AuthnProvider primaryAuthnProvider) {
    String displayName = null;
    String avatarUrl = null;

    if (primaryAuthnProvider != null) {
      displayName = blankToNull(primaryAuthnProvider.getDisplayName());
      if (displayName == null) {
        displayName = blankToNull(primaryAuthnProvider.getProviderLogin());
      }
      avatarUrl = blankToNull(primaryAuthnProvider.getAvatarUrl());
    }

    return new AuthUserView(
        user.getId(), user.getAccountLifecycle(), displayName, avatarUrl, user.getCreatedAt());
  }

  private String blankToNull(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value;
  }
}
