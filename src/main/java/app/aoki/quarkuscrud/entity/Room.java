package app.aoki.quarkuscrud.entity;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.time.LocalDateTime;

@RegisterForReflection
public class Room {
  private Long id;
  private String name;
  private String description;
  private Long userId;
  private String usermeta;
  private String sysmeta;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;

  public Room() {}

  public Room(
      Long id,
      String name,
      String description,
      Long userId,
      String usermeta,
      String sysmeta,
      LocalDateTime createdAt,
      LocalDateTime updatedAt) {
    this.id = id;
    this.name = name;
    this.description = description;
    this.userId = userId;
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

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public Long getUserId() {
    return userId;
  }

  public void setUserId(Long userId) {
    this.userId = userId;
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
