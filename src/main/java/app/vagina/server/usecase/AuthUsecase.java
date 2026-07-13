package app.vagina.server.usecase;

import app.vagina.server.domain.error.AuthenticationException;
import app.vagina.server.entity.AccountLifecycle;
import app.vagina.server.entity.AuthnProvider;
import app.vagina.server.entity.ClientType;
import app.vagina.server.entity.User;
import app.vagina.server.service.AuthService;
import app.vagina.server.service.EntitlementService;
import app.vagina.server.service.UserService;
import app.vagina.server.service.oidcprovider.OidcProviderBase.OidcUserInfo;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class AuthUsecase {
  public record StartOidcLoginResult(URI authorizationUrl, String state, long expiresIn) {}

  public record AuthUserView(
      String id,
      String accountLifecycle,
      String displayName,
      String avatarUrl,
      List<String> entitlementKeys,
      OffsetDateTime createdAt) {}

  public record AuthSessionResult(
      String accessToken,
      String refreshToken,
      String tokenType,
      long expiresIn,
      AuthUserView user,
      Long auditUserId,
      String tokenFamily) {}

  public record OidcProviderView(String id, String displayName) {}

  @ConfigProperty(name = "vagina.auth.access-token.lifespan")
  Long accessTokenLifespan;

  @Inject UserService userService;
  @Inject AuthService authService;
  @Inject EntitlementService entitlementService;

  public List<OidcProviderView> listOidcProviders() {
    return authService.listConfiguredOidcProviders().stream()
        .map(provider -> new OidcProviderView(provider.id(), provider.displayName()))
        .toList();
  }

  public StartOidcLoginResult startOidcLogin(
      String provider, ClientType clientType, String codeChallenge, String codeChallengeMethod) {
    authService.resolveProvider(provider);
    var createdState =
        authService.createState(provider, clientType, codeChallenge, codeChallengeMethod);

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
  public AuthSessionResult exchangeOidcLogin(
      String provider, String code, String state, String codeVerifier) {
    var consumedState = authService.consumeState(provider, state, codeVerifier);

    var tokenSet =
        authService.exchangeAuthorizationCode(
            provider, code, consumedState.getRedirectUri(), codeVerifier);
    OidcUserInfo oidcUserInfo = authService.fetchUserInfo(provider, tokenSet.accessToken());

    User user = userService.getOrCreateOidcUser(provider, oidcUserInfo);

    if (user.getAccountLifecycle() != AccountLifecycle.ACTIVE) {
      throw new AuthenticationException("Account is not active");
    }

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
        toAuthUserView(user, primaryAuthnProvider),
        user.getId(),
        issuedRefreshToken.persistedToken().getTokenFamily());
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

    if (user.getAccountLifecycle() != AccountLifecycle.ACTIVE) {
      throw new AuthenticationException("Account is not active");
    }

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
        toAuthUserView(user, primaryAuthnProvider),
        user.getId(),
        rotatedRefreshToken.persistedToken().getTokenFamily());
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
      displayName =
          Optional.ofNullable(primaryAuthnProvider.getDisplayName())
              .filter(value -> !value.isBlank())
              .or(
                  () ->
                      Optional.ofNullable(primaryAuthnProvider.getProviderLogin())
                          .filter(value -> !value.isBlank()))
              .orElse(null);
      avatarUrl =
          Optional.ofNullable(primaryAuthnProvider.getAvatarUrl())
              .filter(value -> !value.isBlank())
              .orElse(null);
    }

    String accountLifecycle =
        user.getAccountLifecycle() == null ? null : user.getAccountLifecycle().getValue();
    List<String> entitlementKeys = entitlementService.listActiveEntitlementKeys(user.getId());
    OffsetDateTime createdAt = user.getCreatedAt().atOffset(ZoneOffset.UTC);
    return new AuthUserView(
        String.valueOf(user.getId()),
        accountLifecycle,
        displayName,
        avatarUrl,
        entitlementKeys,
        createdAt);
  }
}
