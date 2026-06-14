package app.vagina.server.domain.error;

public final class AuthenticationException extends DomainException {

  public AuthenticationException(String message) {
    super(message);
  }

  public AuthenticationException(String message, Throwable cause) {
    super(message, cause);
  }
}
