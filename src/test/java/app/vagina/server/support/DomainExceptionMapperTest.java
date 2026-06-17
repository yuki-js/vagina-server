package app.vagina.server.support;

import static org.junit.jupiter.api.Assertions.assertEquals;

import app.vagina.server.domain.error.AuthenticationException;
import app.vagina.server.domain.error.AuthorizationException;
import app.vagina.server.domain.error.ConflictException;
import app.vagina.server.domain.error.ExternalServiceException;
import app.vagina.server.domain.error.NotFoundException;
import app.vagina.server.domain.error.ProviderNotImplementedException;
import app.vagina.server.domain.error.ValidationException;
import org.junit.jupiter.api.Test;

class DomainExceptionMapperTest {

  private final DomainExceptionMapper mapper = new DomainExceptionMapper();

  @Test
  void mapsAuthenticationTo401() {
    assertEquals(401, mapper.toResponse(new AuthenticationException("auth failed")).getStatus());
  }

  @Test
  void mapsAuthorizationTo403() {
    assertEquals(403, mapper.toResponse(new AuthorizationException("forbidden")).getStatus());
  }

  @Test
  void mapsValidationTo400() {
    assertEquals(400, mapper.toResponse(new ValidationException("bad input")).getStatus());
  }

  @Test
  void mapsNotFoundTo404() {
    assertEquals(404, mapper.toResponse(new NotFoundException("not found")).getStatus());
  }

  @Test
  void mapsConflictTo409() {
    assertEquals(409, mapper.toResponse(new ConflictException("conflict")).getStatus());
  }

  @Test
  void mapsProviderNotImplementedTo501() {
    assertEquals(
        501,
        mapper.toResponse(new ProviderNotImplementedException("not implemented")).getStatus());
  }

  @Test
  void mapsExternalServiceTo502() {
    assertEquals(502, mapper.toResponse(new ExternalServiceException("upstream failed")).getStatus());
  }
}
