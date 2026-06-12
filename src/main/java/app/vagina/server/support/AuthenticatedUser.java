package app.vagina.server.support;

import app.vagina.server.entity.User;
import jakarta.enterprise.context.RequestScoped;

@RequestScoped
public class AuthenticatedUser {

  private User user;

  void set(User user) {
    this.user = user;
  }

  public User get() {
    if (user == null) {
      throw new IllegalStateException("No authenticated user found in request context");
    }
    return user;
  }
}
