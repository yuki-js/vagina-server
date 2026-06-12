package app.vagina.server.usecase;

import app.vagina.server.entity.AuthnProvider;
import app.vagina.server.entity.User;
import app.vagina.server.generated.model.AuthTokenResponse;
import app.vagina.server.generated.model.ExchangeOidcLoginRequest;
import app.vagina.server.generated.model.StartOidcLogin200Response;
import app.vagina.server.generated.model.StartOidcLoginRequest;
import app.vagina.server.service.HarigataOidcService;
import app.vagina.server.service.JwtService;
import app.vagina.server.service.OidcStateService;
import app.vagina.server.service.RefreshTokenService;
import app.vagina.server.service.UserService;
import app.vagina.server.service.model.OidcUserInfo;
import app.vagina.server.support.AppException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.time.ZoneOffset;
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

  public StartOidcLogin200Response startOidcLogin(String provider, StartOidcLoginRequest request) {
    ensureSupportedProvider(provider);
    var createdState = oidcStateService.createState(
        provider,
        app.vagina.server.entity.ClientType.fromValue(request.getClientType().value()),
        request.getRedirectUri().toString(),
        request.getCodeChallenge(),
        request.getCodeChallengeMethod().value());

    String authorizationUrl = harigataOidcService.buildAuthorizationUrl(
        request.getRedirectUri().toString(),
        createdState.rawState(),
        request.getCodeChallenge(),
        request.getCodeChallengeMethod().value());

    StartOidcLogin200Response response = new StartOidcLogin200Response();
    response.setAuthorizationUrl(URI.create(authorizationUrl));
    response.setState(createdState.rawState());
    response.setExpiresIn(createdState.expiresIn());
    return response;
  }

  @Transactional
  public AuthTokenResponse exchangeOidcLogin(String provider, ExchangeOidcLoginRequest request) {
    ensureSupportedProvider(provider);

    oidcStateService.consumeState(
        provider, request.getState(), request.getRedirectUri().toString(), request.getCodeVerifier());

    var tokenSet = harigataOidcService.exchangeAuthorizationCode(
        request.getCode(), request.getRedirectUri().toString(), request.getCodeVerifier());
    OidcUserInfo oidcUserInfo = harigataOidcService.fetchUserInfo(tokenSet.accessToken());

    User user = userService.getOrCreateOidcUser(provider, oidcUserInfo);
    AuthnProvider primaryAuthnProvider = userService
        .findPrimaryAuthnProvider(user.getId())
        .orElseThrow(() -> new IllegalStateException("User has no auth provider"));

    String accessToken = jwtService.generateAccessToken(user);
    var issuedRefreshToken = refreshTokenService.issueRefreshToken(user.getId());

    AuthTokenResponse response = new AuthTokenResponse();
    response.setAccessToken(accessToken);
    response.setRefreshToken(issuedRefreshToken.rawToken());
    response.setTokenType("Bearer");
    response.setExpiresIn(accessTokenLifespan);
    response.setUser(toGeneratedUser(user, primaryAuthnProvider));
    return response;
  }

  @Transactional
  public AuthTokenResponse refreshSession(String rawRefreshToken) {
    var rotatedRefreshToken = refreshTokenService
        .rotateRefreshToken(rawRefreshToken)
        .orElseThrow(() -> new AppException(Response.Status.UNAUTHORIZED, "Invalid refresh token"));

    User user = userService
        .findById(rotatedRefreshToken.persistedToken().getUserId())
        .orElseThrow(() -> new IllegalStateException("User not found for refresh token"));
    AuthnProvider primaryAuthnProvider = userService
        .findPrimaryAuthnProvider(user.getId())
        .orElseThrow(() -> new IllegalStateException("User has no auth provider"));

    String accessToken = jwtService.generateAccessToken(user);

    AuthTokenResponse response = new AuthTokenResponse();
    response.setAccessToken(accessToken);
    response.setRefreshToken(rotatedRefreshToken.rawToken());
    response.setTokenType("Bearer");
    response.setExpiresIn(accessTokenLifespan);
    response.setUser(toGeneratedUser(user, primaryAuthnProvider));
    return response;
  }

  @Transactional
  public void logout(String rawRefreshToken) {
    refreshTokenService.revokeRefreshToken(rawRefreshToken);
  }

  public app.vagina.server.generated.model.User getCurrentUser(Long userId) {
    User user = userService
        .findById(userId)
        .orElseThrow(() -> new IllegalStateException("Authenticated user not found"));
    AuthnProvider primaryAuthnProvider = userService.findPrimaryAuthnProvider(userId).orElse(null);
    return toGeneratedUser(user, primaryAuthnProvider);
  }

  private void ensureSupportedProvider(String provider) {
    if (!HARIGATA_PROVIDER.equals(provider)) {
      throw new AppException(Response.Status.BAD_REQUEST, "Unsupported OIDC provider: " + provider);
    }
  }

  private app.vagina.server.generated.model.User toGeneratedUser(User user, AuthnProvider primaryAuthnProvider) {
    String displayName = null;
    String avatarUrl = null;

    if (primaryAuthnProvider != null) {
      displayName = blankToNull(primaryAuthnProvider.getDisplayName());
      if (displayName == null) {
        displayName = blankToNull(primaryAuthnProvider.getProviderLogin());
      }
      avatarUrl = blankToNull(primaryAuthnProvider.getAvatarUrl());
    }

    app.vagina.server.generated.model.User response = new app.vagina.server.generated.model.User();
    response.setId(String.valueOf(user.getId()));
    if (user.getAccountLifecycle() != null) {
      response.setAccountLifecycle(
          app.vagina.server.generated.model.User.AccountLifecycleEnum.fromValue(
              user.getAccountLifecycle().getValue()));
    }
    response.setDisplayName(displayName);
    response.setAvatarUrl(avatarUrl);
    response.setCreatedAt(user.getCreatedAt().atOffset(ZoneOffset.UTC));
    return response;
  }

  private String blankToNull(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value;
  }
}
