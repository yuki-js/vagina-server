package app.aoki.quarkuscrud.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** Unit tests for AuthMethod enum. */
public class AuthMethodTest {

  @Test
  public void testAnonymousMethod() {
    assertEquals("anonymous", AuthMethod.ANONYMOUS.getValue());
  }

  @Test
  public void testOidcMethod() {
    assertEquals("oidc", AuthMethod.OIDC.getValue());
  }

  @Test
  public void testFromValueAnonymous() {
    AuthMethod method = AuthMethod.fromValue("anonymous");
    assertEquals(AuthMethod.ANONYMOUS, method);
  }

  @Test
  public void testFromValueOidc() {
    AuthMethod method = AuthMethod.fromValue("oidc");
    assertEquals(AuthMethod.OIDC, method);
  }

  @Test
  public void testFromValueInvalid() {
    assertThrows(IllegalArgumentException.class, () -> AuthMethod.fromValue("invalid"));
  }

  @Test
  public void testFromValueNull() {
    assertThrows(IllegalArgumentException.class, () -> AuthMethod.fromValue(null));
  }
}
