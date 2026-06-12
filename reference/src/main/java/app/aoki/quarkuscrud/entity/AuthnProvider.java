package app.aoki.quarkuscrud.entity;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.time.LocalDateTime;

/**
 * Authentication provider entity.
 *
 * <p>Represents authentication credentials and provider information for users. A user can have
 * multiple authentication providers.
 */
@RegisterForReflection
public class AuthnProvider {
  private Long id;
  private Long userId;
  private AuthMethod authMethod;
  private String authIdentifier;
  private String externalSubject;
  private String usermeta;
  private String sysmeta;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;

  public AuthnProvider() {}

  public AuthnProvider(
      Long id,
      Long userId,
      AuthMethod authMethod,
      String authIdentifier,
      String externalSubject,
      String usermeta,
      String sysmeta,
      LocalDateTime createdAt,
      LocalDateTime updatedAt) {
    this.id = id;
    this.userId = userId;
    this.authMethod = authMethod;
    this.authIdentifier = authIdentifier;
    this.externalSubject = externalSubject;
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

  public AuthMethod getAuthMethod() {
    return authMethod;
  }

  public void setAuthMethod(AuthMethod authMethod) {
    this.authMethod = authMethod;
  }

  public String getAuthIdentifier() {
    return authIdentifier;
  }

  public void setAuthIdentifier(String authIdentifier) {
    this.authIdentifier = authIdentifier;
  }

  public String getExternalSubject() {
    return externalSubject;
  }

  public void setExternalSubject(String externalSubject) {
    this.externalSubject = externalSubject;
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

  /**
   * Get the effective subject for JWT claims.
   *
   * <p>For anonymous auth, returns authIdentifier. For external providers, returns externalSubject.
   *
   * @return the subject to use in JWT tokens
   */
  public String getEffectiveSubject() {
    return authMethod == AuthMethod.ANONYMOUS ? authIdentifier : externalSubject;
  }
}
