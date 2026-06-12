package app.vagina.server.support;

import app.vagina.server.entity.User;
import app.vagina.server.service.AuthService;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.util.Optional;
import org.eclipse.microprofile.jwt.JsonWebToken;

@Provider
@Priority(Priorities.AUTHENTICATION)
public class AuthenticationFilter implements ContainerRequestFilter {

  @Context ResourceInfo resourceInfo;

  @Inject AuthService authService;
  @Inject JsonWebToken jwt;
  @Inject AuthenticatedUser authenticatedUser;

  @Override
  public void filter(ContainerRequestContext requestContext) {
    if (!requiresAuthentication()) {
      return;
    }

    String authHeader = requestContext.getHeaderString("Authorization");
    if (!authService.hasBearerToken(authHeader)) {
      requestContext.abortWith(
          Response.status(Response.Status.UNAUTHORIZED)
              .entity(new ErrorResponse("No JWT token found"))
              .build());
      return;
    }

    Optional<User> user = authService.authenticateFromJwt(jwt);
    if (user.isEmpty()) {
      requestContext.abortWith(
          Response.status(Response.Status.UNAUTHORIZED)
              .entity(new ErrorResponse("Invalid JWT token"))
              .build());
      return;
    }

    authenticatedUser.set(user.get());
  }

  private boolean requiresAuthentication() {
    if (resourceInfo == null) {
      return true;
    }

    if (resourceInfo.getResourceMethod() != null
        && resourceInfo.getResourceMethod().isAnnotationPresent(Authenticated.class)) {
      return true;
    }

    return resourceInfo.getResourceClass() != null
        && resourceInfo.getResourceClass().isAnnotationPresent(Authenticated.class);
  }
}
