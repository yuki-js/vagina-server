package app.vagina.server.entity;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.time.LocalDateTime;

@RegisterForReflection
public class RefreshToken {
  private Long id;
  private Long userId;
  private String tokenHash;
  private String tokenFamily;
  private LocalDateTime issuedAt;
  private LocalDateTime expiresAt;
  private LocalDateTime rotatedAt;
  private LocalDateTime revokedAt;
  private LocalDateTime lastUsedAt;
  private String sysmeta;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;

  public RefreshToken(
      Long id,
      Long userId,
      String tokenHash,
      String tokenFamily,
      LocalDateTime issuedAt,
      LocalDateTime expiresAt,
      LocalDateTime rotatedAt,
      LocalDateTime revokedAt,
      LocalDateTime lastUsedAt,
      String sysmeta,
      LocalDateTime createdAt,
      LocalDateTime updatedAt) {
    this.id = id;
    this.userId = userId;
    this.tokenHash = tokenHash;
    this.tokenFamily = tokenFamily;
    this.issuedAt = issuedAt;
    this.expiresAt = expiresAt;
    this.rotatedAt = rotatedAt;
    this.revokedAt = revokedAt;
    this.lastUsedAt = lastUsedAt;
    this.sysmeta = sysmeta;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public void setGeneratedId(Long id) {
    if (this.id != null && !this.id.equals(id)) {
      throw new IllegalStateException("Refresh token persistence id is already assigned");
    }
    this.id = id;
  }

  public boolean isRevoked() {
    return revokedAt != null;
  }

  public boolean isRotated() {
    return rotatedAt != null;
  }

  public boolean isExpiredAt(LocalDateTime now) {
    return expiresAt == null || !expiresAt.isAfter(now);
  }

  public void markRotated(LocalDateTime rotatedAt) {
    this.rotatedAt = rotatedAt;
    this.lastUsedAt = rotatedAt;
    this.updatedAt = rotatedAt;
  }

  public void revoke(LocalDateTime revokedAt) {
    this.revokedAt = revokedAt;
    this.updatedAt = revokedAt;
  }

  public void revokeWithReason(LocalDateTime revokedAt, String reason) {
    revoke(revokedAt);
    if (sysmeta == null || sysmeta.isBlank()) {
      sysmeta = "{\"reason\":\"" + reason + "\"}";
    }
  }

  public RefreshToken createReplacement(
      String tokenHash, LocalDateTime expiresAt, LocalDateTime now) {
    return new RefreshToken(
        null, userId, tokenHash, tokenFamily, now, expiresAt, null, null, null, sysmeta, now, now);
  }

  public Long getId() {
    return id;
  }

  public Long getUserId() {
    return userId;
  }

  public String getTokenHash() {
    return tokenHash;
  }

  public String getTokenFamily() {
    return tokenFamily;
  }

  public LocalDateTime getIssuedAt() {
    return issuedAt;
  }

  public LocalDateTime getExpiresAt() {
    return expiresAt;
  }

  public LocalDateTime getRotatedAt() {
    return rotatedAt;
  }

  public LocalDateTime getRevokedAt() {
    return revokedAt;
  }

  public LocalDateTime getLastUsedAt() {
    return lastUsedAt;
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
