package app.vagina.server.entity;

public enum ClientType {
  WEB("web"),
  MOBILE("mobile"),
  DESKTOP("desktop");

  private final String value;

  ClientType(String value) {
    this.value = value;
  }

  public String getValue() {
    return value;
  }

  public static ClientType fromValue(String value) {
    if (value == null) {
      return null;
    }
    for (ClientType type : values()) {
      if (type.value.equalsIgnoreCase(value) || type.name().equalsIgnoreCase(value)) {
        return type;
      }
    }
    throw new IllegalArgumentException("Unknown client type: " + value);
  }
}
