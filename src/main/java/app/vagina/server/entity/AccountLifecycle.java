package app.vagina.server.entity;

public enum AccountLifecycle {
  CREATED("created"),
  ACTIVE("active"),
  PAUSED("paused"),
  DELETED("deleted");

  private final String value;

  AccountLifecycle(String value) {
    this.value = value;
  }

  public String getValue() {
    return value;
  }

  public static AccountLifecycle fromValue(String value) {
    if (value == null) {
      return null;
    }
    for (AccountLifecycle lifecycle : values()) {
      if (lifecycle.value.equalsIgnoreCase(value) || lifecycle.name().equalsIgnoreCase(value)) {
        return lifecycle;
      }
    }
    throw new IllegalArgumentException("Unknown account lifecycle: " + value);
  }
}
