package app.aoki.quarkuscrud.service;

import app.aoki.quarkuscrud.entity.AuthMethod;
import app.aoki.quarkuscrud.entity.User;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;

/**
 * Service for handling authentication logic across multiple providers.
 *
 * <p>This service centralizes authentication operations, separating business logic from the filter
 * layer. It handles JWT token validation and user lookup for all authentication methods (anonymous,
 * external OIDC, etc.).
 */
@ApplicationScoped
public class AuthenticationService {

  private static final Logger LOG = Logger.getLogger(AuthenticationService.class);

  @Inject UserService userService;
  @Inject MeterRegistry meterRegistry;

  /**
   * Authenticates a user from a JWT token.
   *
   * <p>The authentication strategy depends on the JWT issuer and groups:
   *
   * <ul>
   *   <li>For anonymous users: looks up by authIdentifier (JWT subject)
   *   <li>For external OIDC users: looks up by method and externalSubject (JWT subject)
   * </ul>
   *
   * @param jwt the validated JWT token
   * @return the authenticated user, or empty if user not found
   */
  public Optional<User> authenticateFromJwt(JsonWebToken jwt) {
    if (jwt == null || jwt.getSubject() == null) {
      LOG.warn("Authentication failed: JWT or subject is null");
      Counter.builder("authentication.attempts")
          .description("Number of authentication attempts")
          .tag("result", "failed")
          .tag("reason", "invalid_jwt")
          .register(meterRegistry)
          .increment();
      return Optional.empty();
    }

    String subject = jwt.getSubject();
    if (subject == null || subject.isEmpty()) {
      LOG.warn("Authentication failed: JWT subject is null or empty");
      Counter.builder("authentication.attempts")
          .description("Number of authentication attempts")
          .tag("result", "failed")
          .tag("reason", "empty_subject")
          .register(meterRegistry)
          .increment();
      return Optional.empty();
    }

    Timer.Sample sample = Timer.start(meterRegistry);

    // Determine authentication method from JWT groups
    AuthMethod authMethod = determineMethodFromJwt(jwt);
    LOG.debugf("Authenticating user with method: %s, subject: %s", authMethod, subject);

    try {
      Optional<User> result;
      switch (authMethod) {
        case ANONYMOUS:
          // For anonymous users, subject is the authIdentifier
          result = userService.findByAuthIdentifier(subject);
          break;

        case OIDC:
          // For OIDC users, subject is the externalSubject from the provider
          result = userService.findByMethodAndExternalSubject(authMethod, subject);
          break;

        default:
          LOG.warnf("Authentication failed: unsupported auth method: %s", authMethod);
          result = Optional.empty();
      }

      // Record authentication metrics
      Counter.builder("authentication.attempts")
          .description("Number of authentication attempts")
          .tag("auth_method", authMethod.name().toLowerCase())
          .tag("result", result.isPresent() ? "success" : "failed")
          .register(meterRegistry)
          .increment();

      if (result.isPresent()) {
        LOG.infof(
            "Successfully authenticated user ID: %d with method: %s",
            result.get().getId(), authMethod);
      } else {
        LOG.warnf(
            "Authentication failed: user not found for method: %s, subject: %s",
            authMethod, subject);
      }

      return result;
    } finally {
      sample.stop(
          Timer.builder("authentication.time")
              .description("Time taken to authenticate a user")
              .tag("auth_method", authMethod.name().toLowerCase())
              .register(meterRegistry));
    }
  }

  /**
   * Determines the authentication method from JWT claims.
   *
   * <p>Uses the groups claim to identify the method. For locally-issued tokens, the group is set
   * explicitly. For external OIDC tokens, we can check the issuer claim or other provider-specific
   * claims.
   *
   * @param jwt the JWT token
   * @return the authentication method
   */
  private AuthMethod determineMethodFromJwt(JsonWebToken jwt) {
    // Check groups claim first
    if (jwt.getGroups() != null) {
      if (jwt.getGroups().contains("anonymous")) {
        return AuthMethod.ANONYMOUS;
      }
      if (jwt.getGroups().contains("oidc")) {
        return AuthMethod.OIDC;
      }
    }

    // For external OIDC providers, we would check the issuer claim here
    // String issuer = jwt.getIssuer();
    // if (issuer != null && issuer.startsWith("https://accounts.google.com")) {
    //   return AuthMethod.OIDC;
    // }

    // Default to anonymous for backward compatibility
    return AuthMethod.ANONYMOUS;
  }

  /**
   * Checks if a request has a valid Bearer token in the Authorization header.
   *
   * @param authHeader the Authorization header value
   * @return true if the header contains a Bearer token
   */
  public boolean hasBearerToken(String authHeader) {
    return authHeader != null && authHeader.startsWith("Bearer ");
  }

  /**
   * Authenticates or creates a user from an external OIDC token.
   *
   * <p>This method is designed for future OIDC integration. When an external OIDC token is
   * received, this method will:
   *
   * <ol>
   *   <li>Validate the token (signature, expiration, issuer)
   *   <li>Extract the subject and other claims
   *   <li>Look up or create the user in our database
   *   <li>Return the authenticated user
   * </ol>
   *
   * @param jwt the validated external OIDC JWT token
   * @return the authenticated user
   */
  public Optional<User> authenticateFromExternalOidc(JsonWebToken jwt) {
    // TODO: Implement when integrating external OIDC provider
    String externalSubject = jwt.getSubject();
    User user = userService.getOrCreateExternalUser(AuthMethod.OIDC, externalSubject);
    return Optional.of(user);
  }
}
