package app.vagina.server.domain.error;

public final class NotFoundException extends DomainException {

  public NotFoundException(String message) {
    super(message);
  }

  public NotFoundException(String message, Throwable cause) {
    super(message, cause);
  }
}
