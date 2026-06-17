package app.vagina.server.domain.error;

public final class ExternalServiceException extends DomainException {

  public ExternalServiceException(String message) {
    super(message);
  }

  public ExternalServiceException(String message, Throwable cause) {
    super(message, cause);
  }
}
