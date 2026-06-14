package app.vagina.server.support;

import app.vagina.server.domain.error.AuthenticationException;
import app.vagina.server.domain.error.ConflictException;
import app.vagina.server.domain.error.DomainException;
import app.vagina.server.domain.error.NotFoundException;
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
    return Status.INTERNAL_SERVER_ERROR;
  }
}
