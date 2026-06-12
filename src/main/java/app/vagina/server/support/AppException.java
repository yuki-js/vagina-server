package app.vagina.server.support;

import jakarta.ws.rs.core.Response;

public class AppException extends RuntimeException {

  private final Response.Status status;

  public AppException(Response.Status status, String message) {
    super(message);
    this.status = status;
  }

  public Response.Status getStatus() {
    return status;
  }
}
