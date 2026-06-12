package app.vagina.server.entity;

public enum AuthMethod {
  OIDC("oidc");

  private final String value;

  AuthMethod(String value) {
    this.value = value;
  }

  public String getValue() {
    return value;
  }

  public static AuthMethod fromValue(String value) {
    if (value == null) {
      return null;
    }
    for (AuthMethod method : values()) {
      if (method.value.equalsIgnoreCase(value) || method.name().equalsIgnoreCase(value)) {
        return method;
      }
    }
    throw new IllegalArgumentException("Unknown auth method: " + value);
  }
}
