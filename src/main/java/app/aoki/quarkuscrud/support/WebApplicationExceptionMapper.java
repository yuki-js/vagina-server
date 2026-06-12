package app.aoki.quarkuscrud.support;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Exception mapper for WebApplicationException. This ensures that exceptions thrown by the
 * application preserve the existing error JSON format: {"error": "message"}. This mapper only
 * handles cases where the response entity is a plain string, converting it to the proper JSON
 * format.
 */
@Provider
public class WebApplicationExceptionMapper implements ExceptionMapper<WebApplicationException> {

  @Override
  public Response toResponse(WebApplicationException exception) {
    Response originalResponse = exception.getResponse();

    // If the response already has a properly formatted entity, return it as-is
    if (originalResponse.hasEntity()
        && originalResponse.getEntity() instanceof String errorString) {
      // Check if it's already a JSON object (starts with '{')
      if (errorString.trim().startsWith("{")) {
        return originalResponse;
      }
      // If it's a plain string, wrap it in an error object
      return Response.status(originalResponse.getStatus())
          .entity(new ErrorResponse(errorString))
          .build();
    }

    // For responses without entities, use the exception message
    return Response.status(originalResponse.getStatus())
        .entity(new ErrorResponse(exception.getMessage()))
        .build();
  }
}
