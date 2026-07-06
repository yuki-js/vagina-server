package app.vagina.server.entity;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.time.LocalDateTime;

@RegisterForReflection
public class User {
  private Long id;
  private AccountLifecycle accountLifecycle;
  private String usermeta;
  private String sysmeta;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;

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

  public void setGeneratedId(Long id) {
    if (this.id != null && !this.id.equals(id)) {
      throw new IllegalStateException("User persistence id is already assigned");
    }
    this.id = id;
  }

  public void changeAccountLifecycle(AccountLifecycle accountLifecycle, LocalDateTime updatedAt) {
    this.accountLifecycle = accountLifecycle;
    this.updatedAt = updatedAt;
  }

  public boolean isActive() {
    return accountLifecycle == AccountLifecycle.ACTIVE;
  }

  public Long getId() {
    return id;
  }

  public AccountLifecycle getAccountLifecycle() {
    return accountLifecycle;
  }

  public String getUsermeta() {
    return usermeta;
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
