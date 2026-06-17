package app.vagina.server.domain.error;

public final class AuthorizationException extends DomainException {

  public AuthorizationException(String message) {
    super(message);
  }

  public AuthorizationException(String message, Throwable cause) {
    super(message, cause);
  }
}
