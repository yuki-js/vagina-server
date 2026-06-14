package app.vagina.server.domain.error;

public final class ConflictException extends DomainException {

  public ConflictException(String message) {
    super(message);
  }

  public ConflictException(String message, Throwable cause) {
    super(message, cause);
  }
}
