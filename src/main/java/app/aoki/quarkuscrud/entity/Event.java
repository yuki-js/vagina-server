package app.aoki.quarkuscrud.entity;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.time.LocalDateTime;

/**
 * Event entity representing quiz events.
 *
 * <p>Events have a lifecycle (created, active, ended, expired, deleted) and are associated with an
 * initiator user. Invitation codes are managed in a separate table for performance.
 */
@RegisterForReflection
public class Event {
  private Long id;
  private Long initiatorId;
  private EventStatus status;
  private String usermeta;
  private String sysmeta;
  private LocalDateTime expiresAt;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;

  public Event() {}

  public Event(
      Long id,
      Long initiatorId,
      EventStatus status,
      String usermeta,
      String sysmeta,
      LocalDateTime expiresAt,
      LocalDateTime createdAt,
      LocalDateTime updatedAt) {
    this.id = id;
    this.initiatorId = initiatorId;
    this.status = status;
    this.usermeta = usermeta;
    this.sysmeta = sysmeta;
    this.expiresAt = expiresAt;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public Long getInitiatorId() {
    return initiatorId;
  }

  public void setInitiatorId(Long initiatorId) {
    this.initiatorId = initiatorId;
  }

  public EventStatus getStatus() {
    return status;
  }

  public void setStatus(EventStatus status) {
    this.status = status;
  }

  public String getUsermeta() {
    return usermeta;
  }

  public void setUsermeta(String usermeta) {
    this.usermeta = usermeta;
  }

  public String getSysmeta() {
    return sysmeta;
  }

  public void setSysmeta(String sysmeta) {
    this.sysmeta = sysmeta;
  }

  public LocalDateTime getExpiresAt() {
    return expiresAt;
  }

  public void setExpiresAt(LocalDateTime expiresAt) {
    this.expiresAt = expiresAt;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime createdAt) {
    this.createdAt = createdAt;
  }

  public LocalDateTime getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(LocalDateTime updatedAt) {
    this.updatedAt = updatedAt;
  }
}
