package app.aoki.quarkuscrud.entity;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Account lifecycle states.
 *
 * <p>This enum defines the different lifecycle stages of a user account from creation to deletion.
 */
@RegisterForReflection
public enum AccountLifecycle {
  /** Account has been created but not fully provisioned. */
  CREATED("created"),

  /** Account provisioning is complete (external entities created, etc.) and ready for login. */
  PROVISIONED("provisioned"),

  /** Account is active and has completed initial login/activation. */
  ACTIVE("active"),

  /** Account is temporarily paused/suspended. */
  PAUSED("paused"),

  /** Account is deleted. */
  DELETED("deleted");

  private final String value;

  AccountLifecycle(String value) {
    this.value = value;
  }

  public String getValue() {
    return value;
  }

  /**
   * Get AccountLifecycle from string value.
   *
   * @param value the string value
   * @return the corresponding AccountLifecycle
   * @throws IllegalArgumentException if value doesn't match any lifecycle state
   */
  public static AccountLifecycle fromValue(String value) {
    for (AccountLifecycle lifecycle : values()) {
      if (lifecycle.value.equals(value)) {
        return lifecycle;
      }
    }
    throw new IllegalArgumentException("Unknown account lifecycle: " + value);
  }
}
