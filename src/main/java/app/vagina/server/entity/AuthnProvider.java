package app.vagina.server.entity;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.time.LocalDateTime;

@RegisterForReflection
public class AuthnProvider {
  private Long id;
  private Long userId;
  private AuthMethod authMethod;
  private String providerKey;
  private String authIdentifier;
  private String externalSubject;
  private String providerLogin;
  private String displayName;
  private String avatarUrl;
  private String email;
  private Boolean emailVerified;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;

  public AuthnProvider(
      Long id,
      Long userId,
      AuthMethod authMethod,
      String providerKey,
      String authIdentifier,
      String externalSubject,
      String providerLogin,
      String displayName,
      String avatarUrl,
      String email,
      Boolean emailVerified,
      LocalDateTime createdAt,
      LocalDateTime updatedAt) {
    this.id = id;
    this.userId = userId;
    this.authMethod = authMethod;
    this.providerKey = providerKey;
    this.authIdentifier = authIdentifier;
    this.externalSubject = externalSubject;
    this.providerLogin = providerLogin;
    this.displayName = displayName;
    this.avatarUrl = avatarUrl;
    this.email = email;
    this.emailVerified = emailVerified;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public void setGeneratedId(Long id) {
    if (this.id != null && !this.id.equals(id)) {
      throw new IllegalStateException("Auth provider persistence id is already assigned");
    }
    this.id = id;
  }

  public void syncProviderProfile(
      String providerLogin,
      String displayName,
      String avatarUrl,
      String email,
      Boolean emailVerified,
      LocalDateTime updatedAt) {
    this.providerLogin = providerLogin;
    this.displayName = displayName;
    this.avatarUrl = avatarUrl;
    this.email = email;
    this.emailVerified = emailVerified;
    this.updatedAt = updatedAt;
  }

  public String getEffectiveSubject() {
    return externalSubject;
  }

  public Long getId() {
    return id;
  }

  public Long getUserId() {
    return userId;
  }

  public AuthMethod getAuthMethod() {
    return authMethod;
  }

  public String getProviderKey() {
    return providerKey;
  }

  public String getAuthIdentifier() {
    return authIdentifier;
  }

  public String getExternalSubject() {
    return externalSubject;
  }

  public String getProviderLogin() {
    return providerLogin;
  }

  public String getDisplayName() {
    return displayName;
  }

  public String getAvatarUrl() {
    return avatarUrl;
  }

  public String getEmail() {
    return email;
  }

  public Boolean getEmailVerified() {
    return emailVerified;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public LocalDateTime getUpdatedAt() {
    return updatedAt;
  }
}
