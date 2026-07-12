package app.vagina.server.textagent;

import app.vagina.server.domain.error.ExternalServiceException;

/**
 * Signals a transient text-agent provider failure for which an already-executed tool result may be
 * submitted again without executing the tool again.
 */
public final class RetryableTextAgentProviderException extends ExternalServiceException {

  public RetryableTextAgentProviderException(String message) {
    super(message);
  }

  public RetryableTextAgentProviderException(String message, Throwable cause) {
    super(message, cause);
  }
}
