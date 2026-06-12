package app.aoki.quarkuscrud.entity;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.time.LocalDateTime;

/**
 * User profile revision entity.
 *
 * <p>Each record represents an immutable profile revision. The latest revision for a user is their
 * current profile. Revisions are accumulated over time.
 */
@RegisterForReflection
public class UserProfile {
  private Long id;
  private Long userId;
  private String profileData;
  private String usermeta;
  private String sysmeta;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;

  public UserProfile() {}

  public UserProfile(
      Long id,
      Long userId,
      String profileData,
      String usermeta,
      String sysmeta,
      LocalDateTime createdAt,
      LocalDateTime updatedAt) {
    this.id = id;
    this.userId = userId;
    this.profileData = profileData;
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

  public Long getUserId() {
    return userId;
  }

  public void setUserId(Long userId) {
    this.userId = userId;
  }

  public String getProfileData() {
    return profileData;
  }

  public void setProfileData(String profileData) {
    this.profileData = profileData;
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
