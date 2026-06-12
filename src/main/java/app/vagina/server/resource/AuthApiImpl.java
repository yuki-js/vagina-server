package app.vagina.server.resource;

import app.vagina.server.entity.ClientType;
import app.vagina.server.generated.api.AuthApi;
import app.vagina.server.generated.model.AuthTokenResponse;
import app.vagina.server.generated.model.ExchangeOidcLoginRequest;
import app.vagina.server.generated.model.RefreshSessionRequest;
import app.vagina.server.generated.model.StartOidcLogin200Response;
import app.vagina.server.generated.model.StartOidcLoginRequest;
import app.vagina.server.support.Authenticated;
import app.vagina.server.support.AuthenticatedUser;
import app.vagina.server.support.ErrorResponse;
import app.vagina.server.usecase.AuthUsecase;
import app.vagina.server.usecase.InvalidOidcAuthorizationException;
import app.vagina.server.usecase.InvalidRefreshTokenException;
import app.vagina.server.usecase.UnsupportedAuthProviderException;
import app.vagina.server.usecase.model.AuthSession;
import app.vagina.server.usecase.model.AuthUserView;
import app.vagina.server.usecase.model.OidcAuthorizationStart;
import app.vagina.server.usecase.model.OidcLoginExchangeRequest;
import app.vagina.server.usecase.model.OidcLoginStartRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.ZoneOffset;

@ApplicationScoped
@Path("/")
public class AuthApiImpl implements AuthApi {

  @Inject AuthUsecase authUsecase;
  @Inject AuthenticatedUser authenticatedUser;

  @Override
  public Response exchangeOidcLogin(
      String provider, ExchangeOidcLoginRequest exchangeOidcLoginRequest) {
    try {
      AuthSession session =
          authUsecase.exchangeOidcLogin(
              provider,
              new OidcLoginExchangeRequest(
                  exchangeOidcLoginRequest.getCode(),
                  exchangeOidcLoginRequest.getState(),
                  exchangeOidcLoginRequest.getRedirectUri().toString(),
                  exchangeOidcLoginRequest.getCodeVerifier()));
      return Response.ok(toAuthTokenResponse(session)).build();
    } catch (UnsupportedAuthProviderException e) {
      return Response.status(Response.Status.BAD_REQUEST)
          .entity(new ErrorResponse(e.getMessage()))
          .build();
    } catch (InvalidOidcAuthorizationException e) {
      return Response.status(Response.Status.UNAUTHORIZED)
          .entity(new ErrorResponse(e.getMessage()))
          .build();
    } catch (Exception e) {
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
          .entity(new ErrorResponse("Failed to exchange OIDC login"))
          .build();
    }
  }

  @Override
  @Authenticated
  @GET
  @Path("/me")
  @Produces(MediaType.APPLICATION_JSON)
  public Response getCurrentUser() {
    AuthUserView userView = authUsecase.getCurrentUser(authenticatedUser.get().getId());
    return Response.ok(toGeneratedUser(userView)).build();
  }

  @Override
  @POST
  @Path("/auth/logout")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response logout(RefreshSessionRequest refreshSessionRequest) {
    try {
      authUsecase.logout(refreshSessionRequest.getRefreshToken());
      return Response.noContent().build();
    } catch (Exception e) {
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
          .entity(new ErrorResponse("Failed to logout"))
          .build();
    }
  }

  @Override
  @POST
  @Path("/auth/refresh")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response refreshSession(RefreshSessionRequest refreshSessionRequest) {
    try {
      return Response.ok(
              toAuthTokenResponse(
                  authUsecase.refreshSession(refreshSessionRequest.getRefreshToken())))
          .build();
    } catch (InvalidRefreshTokenException e) {
      return Response.status(Response.Status.UNAUTHORIZED)
          .entity(new ErrorResponse(e.getMessage()))
          .build();
    } catch (Exception e) {
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
          .entity(new ErrorResponse("Failed to refresh session"))
          .build();
    }
  }

  @Override
  public Response startOidcLogin(String provider, StartOidcLoginRequest startOidcLoginRequest) {
    try {
      OidcAuthorizationStart authStart =
          authUsecase.startOidcLogin(
              provider,
              new OidcLoginStartRequest(
                  ClientType.fromValue(startOidcLoginRequest.getClientType().value()),
                  startOidcLoginRequest.getRedirectUri().toString(),
                  startOidcLoginRequest.getCodeChallenge(),
                  startOidcLoginRequest.getCodeChallengeMethod().value()));

      StartOidcLogin200Response response = new StartOidcLogin200Response();
      response.setAuthorizationUrl(java.net.URI.create(authStart.authorizationUrl()));
      response.setState(authStart.state());
      response.setExpiresIn(authStart.expiresIn());
      return Response.ok(response).build();
    } catch (UnsupportedAuthProviderException e) {
      return Response.status(Response.Status.BAD_REQUEST)
          .entity(new ErrorResponse(e.getMessage()))
          .build();
    } catch (Exception e) {
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
          .entity(new ErrorResponse("Failed to start OIDC login"))
          .build();
    }
  }

  private AuthTokenResponse toAuthTokenResponse(AuthSession session) {
    AuthTokenResponse response = new AuthTokenResponse();
    response.setAccessToken(session.accessToken());
    response.setRefreshToken(session.refreshToken());
    response.setTokenType("Bearer");
    response.setExpiresIn(session.expiresIn());
    response.setUser(toGeneratedUser(session.user()));
    return response;
  }

  private app.vagina.server.generated.model.User toGeneratedUser(AuthUserView userView) {
    app.vagina.server.generated.model.User response = new app.vagina.server.generated.model.User();
    response.setId(String.valueOf(userView.id()));
    if (userView.accountLifecycle() != null) {
      response.setAccountLifecycle(
          app.vagina.server.generated.model.User.AccountLifecycleEnum.fromValue(
              userView.accountLifecycle().getValue()));
    }
    response.setDisplayName(userView.displayName());
    response.setAvatarUrl(userView.avatarUrl());
    response.setCreatedAt(userView.createdAt().atOffset(ZoneOffset.UTC));
    return response;
  }
}
