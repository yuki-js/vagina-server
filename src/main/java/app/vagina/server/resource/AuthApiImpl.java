package app.vagina.server.resource;

import app.vagina.server.domain.error.ValidationException;
import app.vagina.server.entity.ClientType;
import app.vagina.server.generated.api.AuthApi;
import app.vagina.server.generated.model.AuthTokenResponse;
import app.vagina.server.generated.model.ExchangeOidcLoginRequest;
import app.vagina.server.generated.model.ListOidcProviders200ResponseInner;
import app.vagina.server.generated.model.RefreshSessionRequest;
import app.vagina.server.generated.model.StartOidcLogin200Response;
import app.vagina.server.generated.model.StartOidcLoginRequest;
import app.vagina.server.generated.model.User;
import app.vagina.server.service.AuthenticationEventRecorder;
import app.vagina.server.support.AuthRequestMetadata;
import app.vagina.server.support.Authenticated;
import app.vagina.server.support.AuthenticatedUser;
import app.vagina.server.usecase.AuthUsecase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;

@ApplicationScoped
@Path("/")
public class AuthApiImpl implements AuthApi {

  @Inject AuthUsecase authUsecase;
  @Inject AuthenticatedUser authenticatedUser;
  @Inject AuthenticationEventRecorder authenticationEventRecorder;
  @Context HttpHeaders httpHeaders;

  @Override
  @GET
  @Path("/auth/oidc/providers")
  @Produces(MediaType.APPLICATION_JSON)
  public Response listOidcProviders() {
    List<ListOidcProviders200ResponseInner> providers =
        authUsecase.listOidcProviders().stream().map(this::toGeneratedOidcProvider).toList();
    return Response.ok(providers).build();
  }

  @Override
  public Response exchangeOidcLogin(
      String provider, ExchangeOidcLoginRequest exchangeOidcLoginRequest) {
    if (exchangeOidcLoginRequest == null) {
      throw new ValidationException("Request body is required");
    }
    AuthUsecase.AuthSessionResult result =
        authUsecase.exchangeOidcLogin(
            provider,
            exchangeOidcLoginRequest.getCode(),
            exchangeOidcLoginRequest.getState(),
            exchangeOidcLoginRequest.getCodeVerifier());
    authenticationEventRecorder.record(
        result.auditUserId(),
        result.tokenFamily(),
        "oidc_exchange",
        AuthRequestMetadata.from(httpHeaders));
    return Response.ok(toAuthTokenResponse(result)).build();
  }

  @Override
  @Authenticated
  @GET
  @Path("/me")
  @Produces(MediaType.APPLICATION_JSON)
  public Response getCurrentUser() {
    AuthUsecase.AuthUserView userView = authUsecase.getCurrentUser(authenticatedUser.get().getId());
    return Response.ok(toGeneratedUser(userView)).build();
  }

  @Override
  @POST
  @Path("/auth/logout")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response logout(RefreshSessionRequest refreshSessionRequest) {
    authUsecase.logout(refreshSessionRequest.getRefreshToken());
    return Response.noContent().build();
  }

  @Override
  @POST
  @Path("/auth/refresh")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response refreshSession(RefreshSessionRequest refreshSessionRequest) {
    AuthUsecase.AuthSessionResult result =
        authUsecase.refreshSession(refreshSessionRequest.getRefreshToken());
    authenticationEventRecorder.record(
        result.auditUserId(),
        result.tokenFamily(),
        "refresh",
        AuthRequestMetadata.from(httpHeaders));
    return Response.ok(toAuthTokenResponse(result)).build();
  }

  @Override
  public Response startOidcLogin(String provider, StartOidcLoginRequest startOidcLoginRequest) {
    if (startOidcLoginRequest == null) {
      throw new ValidationException("Request body is required");
    }
    ClientType clientType = ClientType.WEB;
    if (startOidcLoginRequest.getClientType() != null) {
      try {
        clientType = ClientType.fromValue(startOidcLoginRequest.getClientType().value());
      } catch (IllegalArgumentException e) {
        throw new ValidationException(e.getMessage(), e);
      }
    }
    StartOidcLoginRequest.CodeChallengeMethodEnum codeChallengeMethod =
        startOidcLoginRequest.getCodeChallengeMethod();
    if (codeChallengeMethod == null) {
      throw new ValidationException("codeChallengeMethod is required");
    }

    AuthUsecase.StartOidcLoginResult result =
        authUsecase.startOidcLogin(
            provider,
            clientType,
            startOidcLoginRequest.getCodeChallenge(),
            codeChallengeMethod.value());

    StartOidcLogin200Response response = new StartOidcLogin200Response();
    response.setAuthorizationUrl(result.authorizationUrl());
    return Response.ok(response).build();
  }

  private AuthTokenResponse toAuthTokenResponse(AuthUsecase.AuthSessionResult result) {
    AuthTokenResponse response = new AuthTokenResponse();
    response.setAccessToken(result.accessToken());
    response.setRefreshToken(result.refreshToken());
    response.setTokenType(result.tokenType());
    response.setExpiresIn(result.expiresIn());
    response.setUser(toGeneratedUser(result.user()));
    return response;
  }

  private ListOidcProviders200ResponseInner toGeneratedOidcProvider(
      AuthUsecase.OidcProviderView view) {
    ListOidcProviders200ResponseInner provider = new ListOidcProviders200ResponseInner();
    provider.setId(view.id());
    provider.setDisplayName(view.displayName());
    return provider;
  }

  private User toGeneratedUser(AuthUsecase.AuthUserView view) {
    User user = new User();
    user.setId(view.id());
    if (view.accountLifecycle() != null) {
      user.setAccountLifecycle(User.AccountLifecycleEnum.fromValue(view.accountLifecycle()));
    }
    user.setDisplayName(view.displayName());
    user.setAvatarUrl(view.avatarUrl());
    user.setEntitlements(view.entitlementKeys());
    user.setCreatedAt(view.createdAt());
    return user;
  }
}
