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

  public TextAgentDefinition(
      Long id,
      Long userId,
      String textAgentId,
      String name,
      String prompt,
      String description,
      String textModelId,
      String enabledTools,
      LocalDateTime createdAt,
      LocalDateTime updatedAt) {
    this.id = id;
    this.userId = userId;
    this.textAgentId = textAgentId;
    this.name = name;
    this.prompt = prompt;
    this.description = description;
    this.textModelId = textModelId;
    this.enabledTools = enabledTools;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public void setGeneratedId(Long id) {
    if (this.id != null && !this.id.equals(id)) {
      throw new IllegalStateException("Text agent persistence id is already assigned");
    }
    this.id = id;
  }

  public void updateDefinition(
      String name,
      String prompt,
      String description,
      String textModelId,
      String enabledTools,
      LocalDateTime updatedAt) {
    this.name = name;
    this.prompt = prompt;
    this.description = description;
    this.textModelId = textModelId;
    this.enabledTools = enabledTools;
    this.updatedAt = updatedAt;
  }

  public TextAgentProviderView toTextAgentProviderView() {
    return new TextAgentProviderView(textAgentId, name, prompt, description, textModelId);
  }

  public Long getId() {
    return id;
  }

  public Long getUserId() {
    return userId;
  }

  public String getTextAgentId() {
    return textAgentId;
  }

  public String getName() {
    return name;
  }

  public String getPrompt() {
    return prompt;
  }

  public String getDescription() {
    return description;
  }

  public String getTextModelId() {
    return textModelId;
  }

  public String getEnabledTools() {
    return enabledTools;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public LocalDateTime getUpdatedAt() {
    return updatedAt;
  }

  public record TextAgentProviderView(
      String textAgentId, String name, String prompt, String description, String textModelId) {}
}
