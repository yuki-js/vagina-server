package app.vagina.server.entity;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.time.LocalDateTime;

@RegisterForReflection
public class EntitlementDefinition {
  private Long id;
  private final String entitlementKey;
  private final String displayName;
  private final String description;
  private final boolean enabled;
  private final LocalDateTime createdAt;
  private final LocalDateTime updatedAt;

  public EntitlementDefinition(
      Long id,
      String entitlementKey,
      String displayName,
      String description,
      boolean enabled,
      LocalDateTime createdAt,
      LocalDateTime updatedAt) {
    this.id = id;
    this.entitlementKey = entitlementKey;
    this.displayName = displayName;
    this.description = description;
    this.enabled = enabled;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public void setGeneratedId(Long id) {
    if (this.id != null && !this.id.equals(id)) {
      throw new IllegalStateException("Entitlement definition persistence id is already assigned");
    }
    this.id = id;
  }

  public Long getId() {
    return id;
  }

  public String getEntitlementKey() {
    return entitlementKey;
  }

  public String getDisplayName() {
    return displayName;
  }

  public String getDescription() {
    return description;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public LocalDateTime getUpdatedAt() {
    return updatedAt;
  }
}
