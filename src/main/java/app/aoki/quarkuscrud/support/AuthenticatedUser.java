package app.aoki.quarkuscrud.support;

import app.aoki.quarkuscrud.entity.User;
import jakarta.enterprise.context.RequestScoped;

/** Helper to access the authenticated user from the request context. */
@RequestScoped
public class AuthenticatedUser {

  private User user;

  // Package-private setter for AuthenticationFilter to set the user
  void set(User user) {
    this.user = user;
  }

  /**
   * Gets the authenticated user from the request context.
   *
   * @return the authenticated user
   * @throws IllegalStateException if no authenticated user is found (should not happen
   *     if @Authenticated is used correctly)
   */
  public User get() {
    if (user == null) {
      throw new IllegalStateException("No authenticated user found in request context");
    }
    return user;
  }
}
