package app.aoki.quarkuscrud.support;

import app.aoki.quarkuscrud.entity.User;
import app.aoki.quarkuscrud.service.AuthenticationService;
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

/**
 * JAX-RS filter for authenticating requests using JWT Bearer tokens. Automatically applied to
 * endpoints annotated with @Authenticated.
 *
 * <p>This filter delegates authentication logic to AuthenticationService, maintaining separation of
 * concerns between filter and service layers.
 */
@Provider
@Priority(Priorities.AUTHENTICATION)
public class AuthenticationFilter implements ContainerRequestFilter {

  @Context ResourceInfo resourceInfo;

  @Inject AuthenticationService authenticationService;

  @Inject JsonWebToken jwt;

  @Inject AuthenticatedUser authenticatedUser;

  @Override
  public void filter(ContainerRequestContext requestContext) {
    if (!requiresAuthentication()) {
      return;
    }

    // Get Authorization header
    String authHeader = requestContext.getHeaderString("Authorization");

    if (!authenticationService.hasBearerToken(authHeader)) {
      requestContext.abortWith(
          Response.status(Response.Status.UNAUTHORIZED)
              .entity(new ErrorResponse("No JWT token found"))
              .build());
      return;
    }

    // JWT validation is automatically done by Quarkus SmallRye JWT
    // Delegate authentication logic to service layer
    Optional<User> user = authenticationService.authenticateFromJwt(jwt);

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
