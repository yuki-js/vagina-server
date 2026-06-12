package app.aoki.quarkuscrud.entity;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.time.LocalDateTime;

/**
 * Event user data revision entity.
 *
 * <p>Each record represents an immutable user data revision for a specific event. The latest
 * revision for an event-user pair is their current data. Revisions are accumulated over time.
 */
@RegisterForReflection
public class EventUserData {
  private Long id;
  private Long eventId;
  private Long userId;
  private String userData;
  private String usermeta;
  private String sysmeta;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;

  public EventUserData() {}

  public EventUserData(
      Long id,
      Long eventId,
      Long userId,
      String userData,
      String usermeta,
      String sysmeta,
      LocalDateTime createdAt,
      LocalDateTime updatedAt) {
    this.id = id;
    this.eventId = eventId;
    this.userId = userId;
    this.userData = userData;
    this.usermeta = usermeta;
    this.sysmeta = sysmeta;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public Long getEventId() {
    return eventId;
  }

  public void setEventId(Long eventId) {
    this.eventId = eventId;
  }

  public Long getUserId() {
    return userId;
  }

  public void setUserId(Long userId) {
    this.userId = userId;
  }

  public String getUserData() {
    return userData;
  }

  public void setUserData(String userData) {
    this.userData = userData;
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

  public String getRevisionMeta() {
    return sysmeta; // Deprecated: for backward compatibility
  }

  public void setRevisionMeta(String revisionMeta) {
    this.sysmeta = revisionMeta; // Deprecated: for backward compatibility
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
