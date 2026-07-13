package app.vagina.server.entity;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.time.LocalDateTime;

@RegisterForReflection
public class OAuthLoginAttempt {
  private Long id;
  private String stateHash;
  private AuthMethod authMethod;
  private String providerKey;
  private ClientType clientType;
  private String redirectUri;
  private String codeChallenge;
  private String codeChallengeMethod;
  private LocalDateTime expiresAt;
  private LocalDateTime consumedAt;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;

  public OAuthLoginAttempt(
      Long id,
      String stateHash,
      AuthMethod authMethod,
      String providerKey,
      ClientType clientType,
      String redirectUri,
      String codeChallenge,
      String codeChallengeMethod,
      LocalDateTime expiresAt,
      LocalDateTime consumedAt,
      LocalDateTime createdAt,
      LocalDateTime updatedAt) {
    this.id = id;
    this.stateHash = stateHash;
    this.authMethod = authMethod;
    this.providerKey = providerKey;
    this.clientType = clientType;
    this.redirectUri = redirectUri;
    this.codeChallenge = codeChallenge;
    this.codeChallengeMethod = codeChallengeMethod;
    this.expiresAt = expiresAt;
    this.consumedAt = consumedAt;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public void setGeneratedId(Long id) {
    if (this.id != null && !this.id.equals(id)) {
      throw new IllegalStateException("OAuth login attempt persistence id is already assigned");
    }
    this.id = id;
  }

  public boolean isConsumed() {
    return consumedAt != null;
  }

  public boolean isExpiredAt(LocalDateTime now) {
    return expiresAt == null || !expiresAt.isAfter(now);
  }

  public void markConsumed(LocalDateTime consumedAt) {
    this.consumedAt = consumedAt;
    this.updatedAt = consumedAt;
  }

  public Long getId() {
    return id;
  }

  public String getStateHash() {
    return stateHash;
  }

  public AuthMethod getAuthMethod() {
    return authMethod;
  }

  public String getProviderKey() {
    return providerKey;
  }

  public ClientType getClientType() {
    return clientType;
  }

  public String getRedirectUri() {
    return redirectUri;
  }

  public String getCodeChallenge() {
    return codeChallenge;
  }

  public String getCodeChallengeMethod() {
    return codeChallengeMethod;
  }

  public LocalDateTime getExpiresAt() {
    return expiresAt;
  }

  public LocalDateTime getConsumedAt() {
    return consumedAt;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public LocalDateTime getUpdatedAt() {
    return updatedAt;
  }
}
