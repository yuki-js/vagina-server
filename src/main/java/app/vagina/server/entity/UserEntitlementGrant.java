package app.vagina.server.entity;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.time.LocalDateTime;

@RegisterForReflection
public class UserEntitlementGrant {
  private Long id;
  private final Long userId;
  private final Long entitlementId;
  private final EntitlementGrantSource grantSource;
  private final LocalDateTime validFrom;
  private final LocalDateTime expiresAt;
  private final LocalDateTime revokedAt;
  private final String grantReason;
  private final String revokeReason;
  private final String sysmeta;
  private final LocalDateTime createdAt;
  private final LocalDateTime updatedAt;

  public UserEntitlementGrant(
      Long id,
      Long userId,
      Long entitlementId,
      EntitlementGrantSource grantSource,
      LocalDateTime validFrom,
      LocalDateTime expiresAt,
      LocalDateTime revokedAt,
      String grantReason,
      String revokeReason,
      String sysmeta,
      LocalDateTime createdAt,
      LocalDateTime updatedAt) {
    this.id = id;
    this.userId = userId;
    this.entitlementId = entitlementId;
    this.grantSource = grantSource;
    this.validFrom = validFrom;
    this.expiresAt = expiresAt;
    this.revokedAt = revokedAt;
    this.grantReason = grantReason;
    this.revokeReason = revokeReason;
    this.sysmeta = sysmeta;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public void setGeneratedId(Long id) {
    if (this.id != null && !this.id.equals(id)) {
      throw new IllegalStateException("User entitlement grant persistence id is already assigned");
    }
    this.id = id;
  }

  public boolean isRevoked() {
    return revokedAt != null;
  }

  public boolean isEffectiveAt(LocalDateTime now) {
    return !isRevoked() && !validFrom.isAfter(now) && (expiresAt == null || expiresAt.isAfter(now));
  }

  public Long getId() {
    return id;
  }

  public Long getUserId() {
    return userId;
  }

  public Long getEntitlementId() {
    return entitlementId;
  }

  public EntitlementGrantSource getGrantSource() {
    return grantSource;
  }

  public LocalDateTime getValidFrom() {
    return validFrom;
  }

  public LocalDateTime getExpiresAt() {
    return expiresAt;
  }

  public LocalDateTime getRevokedAt() {
    return revokedAt;
  }

  public String getGrantReason() {
    return grantReason;
  }

  public String getRevokeReason() {
    return revokeReason;
  }

  public String getSysmeta() {
    return sysmeta;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public LocalDateTime getUpdatedAt() {
    return updatedAt;
  }
}
