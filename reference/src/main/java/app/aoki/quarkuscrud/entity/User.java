package app.aoki.quarkuscrud.entity;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.time.LocalDateTime;

/**
 * User entity representing all users in the system.
 *
 * <p>Users track account lifecycle and flexible metadata. Authentication is handled separately in
 * the authn_providers table. Profile revisions are stored in user_profiles and the latest is always
 * retrieved via query (ORDER BY created_at DESC LIMIT 1).
 */
@RegisterForReflection
public class User {
  private Long id;
  private AccountLifecycle accountLifecycle;
  private String usermeta;
  private String sysmeta;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;

  public User() {}

  public User(
      Long id,
      AccountLifecycle accountLifecycle,
      String usermeta,
      String sysmeta,
      LocalDateTime createdAt,
      LocalDateTime updatedAt) {
    this.id = id;
    this.accountLifecycle = accountLifecycle;
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

  public AccountLifecycle getAccountLifecycle() {
    return accountLifecycle;
  }

  public void setAccountLifecycle(AccountLifecycle accountLifecycle) {
    this.accountLifecycle = accountLifecycle;
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
}
