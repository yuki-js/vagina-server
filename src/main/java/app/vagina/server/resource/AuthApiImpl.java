package app.vagina.server.resource;

import app.vagina.server.generated.api.AuthApi;
import app.vagina.server.generated.model.AuthTokenResponse;
import app.vagina.server.generated.model.ExchangeOidcLoginRequest;
import app.vagina.server.generated.model.RefreshSessionRequest;
import app.vagina.server.generated.model.StartOidcLogin200Response;
import app.vagina.server.generated.model.StartOidcLoginRequest;
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
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@ApplicationScoped
@Path("/")
public class AuthApiImpl implements AuthApi {

  @Inject AuthUsecase authUsecase;
  @Inject AuthenticatedUser authenticatedUser;

  @Override
  public Response exchangeOidcLogin(
      String provider, ExchangeOidcLoginRequest exchangeOidcLoginRequest) {
    AuthTokenResponse response = authUsecase.exchangeOidcLogin(provider, exchangeOidcLoginRequest);
    return Response.ok(response).build();
  }

  @Override
  @Authenticated
  @GET
  @Path("/me")
  @Produces(MediaType.APPLICATION_JSON)
  public Response getCurrentUser() {
    app.vagina.server.generated.model.User user =
        authUsecase.getCurrentUser(authenticatedUser.get().getId());
    return Response.ok(user).build();
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
    AuthTokenResponse response =
        authUsecase.refreshSession(refreshSessionRequest.getRefreshToken());
    return Response.ok(response).build();
  }

  @Override
  public Response startOidcLogin(String provider, StartOidcLoginRequest startOidcLoginRequest) {
    StartOidcLogin200Response response =
        authUsecase.startOidcLogin(provider, startOidcLoginRequest);
    return Response.ok(response).build();
  }
}
