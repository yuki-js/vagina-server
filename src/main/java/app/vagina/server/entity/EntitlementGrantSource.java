package app.vagina.server.entity;

import java.util.Locale;

public enum EntitlementGrantSource {
  MANUAL("manual"),
  SUBSCRIPTION("subscription"),
  SYSTEM("system"),
  MIGRATION("migration");

  private final String value;

  EntitlementGrantSource(String value) {
    this.value = value;
  }

  public String getValue() {
    return value;
  }

  public static EntitlementGrantSource fromValue(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Entitlement grant source is required");
    }
    String normalized = value.trim().toLowerCase(Locale.ROOT);
    for (EntitlementGrantSource source : values()) {
      if (source.value.equals(normalized)) {
        return source;
      }
    }
    throw new IllegalArgumentException("Unknown entitlement grant source: " + value);
  }
}
