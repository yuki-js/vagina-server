package app.vagina.server.support;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class AppExceptionMapper implements ExceptionMapper<AppException> {

  @Override
  public Response toResponse(AppException e) {
    return Response.status(e.getStatusCode())
        .type(MediaType.APPLICATION_JSON)
        .entity(new ErrorResponse(e.getMessage()))
        .build();
  }
}
