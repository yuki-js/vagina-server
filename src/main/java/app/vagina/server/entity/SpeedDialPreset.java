package app.vagina.server.entity;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.time.LocalDateTime;

@RegisterForReflection
public class SpeedDialPreset {
  private Long id;
  private Long userId;
  private String speedDialId;
  private String name;
  private String systemPrompt;
  private String description;
  private String iconEmoji;
  private String voice;
  private String enabledTools;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;

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

  public String getSpeedDialId() {
    return speedDialId;
  }

  public void setSpeedDialId(String speedDialId) {
    this.speedDialId = speedDialId;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getSystemPrompt() {
    return systemPrompt;
  }

  public void setSystemPrompt(String systemPrompt) {
    this.systemPrompt = systemPrompt;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public String getIconEmoji() {
    return iconEmoji;
  }

  public void setIconEmoji(String iconEmoji) {
    this.iconEmoji = iconEmoji;
  }

  public String getVoice() {
    return voice;
  }

  public void setVoice(String voice) {
    this.voice = voice;
  }

  public String getEnabledTools() {
    return enabledTools;
  }

  public void setEnabledTools(String enabledTools) {
    this.enabledTools = enabledTools;
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
