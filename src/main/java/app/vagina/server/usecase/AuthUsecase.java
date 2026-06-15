package app.vagina.server.usecase;

import app.vagina.server.domain.error.AuthenticationException;
import app.vagina.server.entity.AuthnProvider;
import app.vagina.server.entity.ClientType;
import app.vagina.server.entity.User;
import app.vagina.server.service.AuthService;
import app.vagina.server.service.UserService;
import app.vagina.server.service.oidcprovider.OidcProviderBase.OidcUserInfo;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class AuthUsecase {
  public record StartOidcLoginResult(URI authorizationUrl, String state, long expiresIn) {}

  public record AuthUserView(
      String id,
      String accountLifecycle,
      String displayName,
      String avatarUrl,
      OffsetDateTime createdAt) {}

  public record AuthSessionResult(
      String accessToken,
      String refreshToken,
      String tokenType,
      long expiresIn,
      AuthUserView user) {}

  @ConfigProperty(name = "vagina.auth.access-token.lifespan")
  Long accessTokenLifespan;

  @Inject UserService userService;
  @Inject AuthService authService;

  public StartOidcLoginResult startOidcLogin(
      String provider, ClientType clientType) {
    authService.resolveProvider(provider);
    var createdState = authService.createState(provider, clientType);

    String authorizationUrl =
        authService.buildAuthorizationUrl(
            provider,
            createdState.redirectUri(),
            createdState.rawState(),
            createdState.codeChallenge(),
            createdState.codeChallengeMethod());

    return new StartOidcLoginResult(
        URI.create(authorizationUrl), createdState.rawState(), createdState.expiresIn());
  }

  @Transactional
  public AuthSessionResult exchangeOidcLogin(String provider, String code, String state) {
    try {
      var consumedState = authService.consumeState(provider, state);
      String codeVerifier = authService.buildPkceCodeVerifierForState(state);

      var tokenSet =
          authService.exchangeAuthorizationCode(
              provider,
              code,
              consumedState.getRedirectUri(),
              codeVerifier);
      OidcUserInfo oidcUserInfo = authService.fetchUserInfo(provider, tokenSet.accessToken());

      User user = userService.getOrCreateOidcUser(provider, oidcUserInfo);
      AuthnProvider primaryAuthnProvider =
          userService
              .findPrimaryAuthnProvider(user.getId())
              .orElseThrow(() -> new IllegalStateException("User has no auth provider"));

      String accessToken = authService.generateAccessToken(user);
      var issuedRefreshToken = authService.issueRefreshToken(user.getId());

      return new AuthSessionResult(
          accessToken,
          issuedRefreshToken.rawToken(),
          "Bearer",
          accessTokenLifespan,
          toAuthUserView(user, primaryAuthnProvider));
    } catch (SecurityException e) {
      throw new AuthenticationException(e.getMessage(), e);
    }
  }

  @Transactional
  public AuthSessionResult refreshSession(String rawRefreshToken) {
    var rotatedRefreshToken =
        authService
            .rotateRefreshToken(rawRefreshToken)
            .orElseThrow(() -> new AuthenticationException("Invalid refresh token"));

    User user =
        userService
            .findById(rotatedRefreshToken.persistedToken().getUserId())
            .orElseThrow(() -> new IllegalStateException("User not found for refresh token"));
    AuthnProvider primaryAuthnProvider =
        userService
            .findPrimaryAuthnProvider(user.getId())
            .orElseThrow(() -> new IllegalStateException("User has no auth provider"));

    String accessToken = authService.generateAccessToken(user);

    return new AuthSessionResult(
        accessToken,
        rotatedRefreshToken.rawToken(),
        "Bearer",
        accessTokenLifespan,
        toAuthUserView(user, primaryAuthnProvider));
  }

  @Transactional
  public void logout(String rawRefreshToken) {
    authService.revokeRefreshToken(rawRefreshToken);
  }

  public AuthUserView getCurrentUser(Long userId) {
    User user =
        userService
            .findById(userId)
            .orElseThrow(() -> new IllegalStateException("Authenticated user not found"));
    AuthnProvider primaryAuthnProvider = userService.findPrimaryAuthnProvider(userId).orElse(null);
    return toAuthUserView(user, primaryAuthnProvider);
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

    String accountLifecycle =
        user.getAccountLifecycle() == null ? null : user.getAccountLifecycle().getValue();
    OffsetDateTime createdAt = user.getCreatedAt().atOffset(ZoneOffset.UTC);
    return new AuthUserView(
        String.valueOf(user.getId()),
        accountLifecycle,
        displayName,
        avatarUrl,
        createdAt);
  }

  private String blankToNull(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value;
  }
}
