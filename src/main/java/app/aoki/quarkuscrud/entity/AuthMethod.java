package app.aoki.quarkuscrud.entity;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Authentication method types.
 *
 * <p>This enum defines the different authentication methods available.
 */
@RegisterForReflection
public enum AuthMethod {
  /** Anonymous authentication - users created locally without external credentials. */
  ANONYMOUS("anonymous"),

  /** External OpenID Connect authentication provider. */
  OIDC("oidc");

  private final String value;

  AuthMethod(String value) {
    this.value = value;
  }

  public String getValue() {
    return value;
  }

  /**
   * Get AuthMethod from string value.
   *
   * @param value the string value
   * @return the corresponding AuthMethod
   * @throws IllegalArgumentException if value doesn't match any method
   */
  public static AuthMethod fromValue(String value) {
    for (AuthMethod method : values()) {
      if (method.value.equals(value)) {
        return method;
      }
    }
    throw new IllegalArgumentException("Unknown authentication method: " + value);
  }
}
