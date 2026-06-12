package app.aoki.quarkuscrud.entity;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Event status states.
 *
 * <p>This enum defines the different lifecycle stages of an event.
 */
@RegisterForReflection
public enum EventStatus {
  /** Event has been created but not yet started. */
  CREATED("created"),

  /** Event is active and ongoing. */
  ACTIVE("active"),

  /** Event has ended normally. */
  ENDED("ended"),

  /** Event has expired. */
  EXPIRED("expired"),

  /** Event is deleted. */
  DELETED("deleted");

  private final String value;

  EventStatus(String value) {
    this.value = value;
  }

  public String getValue() {
    return value;
  }

  /**
   * Get EventStatus from string value.
   *
   * @param value the string value
   * @return the corresponding EventStatus
   * @throws IllegalArgumentException if value doesn't match any status
   */
  public static EventStatus fromValue(String value) {
    for (EventStatus status : values()) {
      if (status.value.equals(value)) {
        return status;
      }
    }
    throw new IllegalArgumentException("Unknown event status: " + value);
  }
}
