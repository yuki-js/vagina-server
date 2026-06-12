package app.vagina.server.usecase;

public class InvalidOidcAuthorizationException extends RuntimeException {

  public InvalidOidcAuthorizationException(String message) {
    super(message);
  }
}
