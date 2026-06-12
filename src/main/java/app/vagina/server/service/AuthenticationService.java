package app.vagina.server.service;

import app.vagina.server.entity.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import org.eclipse.microprofile.jwt.JsonWebToken;

@ApplicationScoped
public class AuthenticationService {

  @Inject UserService userService;

  public Optional<User> authenticateFromJwt(JsonWebToken jwt) {
    if (jwt == null || jwt.getClaim("uid") == null) {
      return Optional.empty();
    }

    Object uidClaim = jwt.getClaim("uid");
    Long userId;
    if (uidClaim instanceof Long value) {
      userId = value;
    } else if (uidClaim instanceof Integer value) {
      userId = value.longValue();
    } else {
      userId = Long.parseLong(String.valueOf(uidClaim));
    }

    return userService.findById(userId);
  }

  public boolean hasBearerToken(String authHeader) {
    return authHeader != null && authHeader.startsWith("Bearer ");
  }
}
