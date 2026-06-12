package app.aoki.quarkuscrud.support;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.stream.Collectors;

/**
 * Exception mapper for Bean Validation constraint violations. Converts validation errors into JSON
 * error responses matching the existing API error format: {"error": "message"}.
 */
@Provider
public class ConstraintViolationExceptionMapper
    implements ExceptionMapper<ConstraintViolationException> {

  @Override
  public Response toResponse(ConstraintViolationException exception) {
    String errorMessage =
        exception.getConstraintViolations().stream()
            .map(this::formatViolation)
            .collect(Collectors.joining(", "));

    return Response.status(Response.Status.BAD_REQUEST)
        .entity(new ErrorResponse("Validation failed: " + errorMessage))
        .build();
  }

  private String formatViolation(ConstraintViolation<?> violation) {
    String propertyPath = violation.getPropertyPath().toString();
    // Extract just the property name from the path (e.g., "createRoom.arg0.name" -> "name")
    String propertyName = propertyPath.substring(propertyPath.lastIndexOf('.') + 1);
    return propertyName + " " + violation.getMessage();
  }
}
