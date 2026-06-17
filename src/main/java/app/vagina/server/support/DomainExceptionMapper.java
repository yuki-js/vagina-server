package app.vagina.server.support;

import app.vagina.server.domain.error.AuthenticationException;
import app.vagina.server.domain.error.AuthorizationException;
import app.vagina.server.domain.error.ConflictException;
import app.vagina.server.domain.error.DomainException;
import app.vagina.server.domain.error.ExternalServiceException;
import app.vagina.server.domain.error.NotFoundException;
import app.vagina.server.domain.error.ProviderNotImplementedException;
import app.vagina.server.domain.error.ValidationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class DomainExceptionMapper implements ExceptionMapper<DomainException> {

  @Override
  public Response toResponse(DomainException exception) {
    return Response.status(toStatus(exception))
        .type(MediaType.APPLICATION_JSON)
        .entity(new ErrorResponse(exception.getMessage()))
        .build();
  }

  private Status toStatus(DomainException exception) {
    if (exception instanceof AuthenticationException) {
      return Status.UNAUTHORIZED;
    }
    if (exception instanceof NotFoundException) {
      return Status.NOT_FOUND;
    }
    if (exception instanceof ConflictException) {
      return Status.CONFLICT;
    }
    if (exception instanceof AuthorizationException) {
      return Status.FORBIDDEN;
    }
    if (exception instanceof ValidationException) {
      return Status.BAD_REQUEST;
    }
    if (exception instanceof ProviderNotImplementedException) {
      return Status.NOT_IMPLEMENTED;
    }
    if (exception instanceof ExternalServiceException) {
      return Status.BAD_GATEWAY;
    }
    return Status.INTERNAL_SERVER_ERROR;
  }
}
