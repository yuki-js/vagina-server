package app.vagina.server.entity;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.time.LocalDateTime;

@RegisterForReflection
public class TextAgentDefinition {
  private Long id;
  private Long userId;
  private String textAgentId;
  private String name;
  private String prompt;
  private String description;
  private String textModelId;
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

  public String getTextAgentId() {
    return textAgentId;
  }

  public void setTextAgentId(String textAgentId) {
    this.textAgentId = textAgentId;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getPrompt() {
    return prompt;
  }

  public void setPrompt(String prompt) {
    this.prompt = prompt;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public String getTextModelId() {
    return textModelId;
  }

  public void setTextModelId(String textModelId) {
    this.textModelId = textModelId;
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
