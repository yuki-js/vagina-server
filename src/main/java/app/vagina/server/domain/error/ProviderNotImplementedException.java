package app.vagina.server.domain.error;

public final class ProviderNotImplementedException extends DomainException {

  public ProviderNotImplementedException(String message) {
    super(message);
  }

  public ProviderNotImplementedException(String message, Throwable cause) {
    super(message, cause);
  }
}
