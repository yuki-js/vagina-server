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
  private String voiceAgentId;
  private String reasoningEffort;
  private boolean toolChoiceRequired;
  private String enabledTools;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;

  public SpeedDialPreset(
      Long id,
      Long userId,
      String speedDialId,
      String name,
      String systemPrompt,
      String description,
      String iconEmoji,
      String voice,
      String voiceAgentId,
      String reasoningEffort,
      boolean toolChoiceRequired,
      String enabledTools,
      LocalDateTime createdAt,
      LocalDateTime updatedAt) {
    this.id = id;
    this.userId = userId;
    this.speedDialId = speedDialId;
    this.name = name;
    this.systemPrompt = systemPrompt;
    this.description = description;
    this.iconEmoji = iconEmoji;
    this.voice = voice;
    this.voiceAgentId = voiceAgentId;
    this.reasoningEffort = reasoningEffort;
    this.toolChoiceRequired = toolChoiceRequired;
    this.enabledTools = enabledTools;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public void setGeneratedId(Long id) {
    if (this.id != null && !this.id.equals(id)) {
      throw new IllegalStateException("Speed dial persistence id is already assigned");
    }
    this.id = id;
  }

  public void updateConfiguration(
      String name,
      String systemPrompt,
      String description,
      String iconEmoji,
      String voice,
      String voiceAgentId,
      String reasoningEffort,
      boolean toolChoiceRequired,
      String enabledTools,
      LocalDateTime updatedAt) {
    this.name = name;
    this.systemPrompt = systemPrompt;
    this.description = description;
    this.iconEmoji = iconEmoji;
    this.voice = voice;
    this.voiceAgentId = voiceAgentId;
    this.reasoningEffort = reasoningEffort;
    this.toolChoiceRequired = toolChoiceRequired;
    this.enabledTools = enabledTools;
    this.updatedAt = updatedAt;
  }

  public boolean hasName(String expectedName) {
    return name != null && name.equals(expectedName);
  }

  public VoiceSessionConfig toVoiceSessionConfig() {
    return new VoiceSessionConfig(
        speedDialId,
        systemPrompt,
        voice,
        voiceAgentId,
        reasoningEffort,
        toolChoiceRequired,
        enabledTools);
  }

  public Long getId() {
    return id;
  }

  public Long getUserId() {
    return userId;
  }

  public String getSpeedDialId() {
    return speedDialId;
  }

  public String getName() {
    return name;
  }

  public String getSystemPrompt() {
    return systemPrompt;
  }

  public String getDescription() {
    return description;
  }

  public String getIconEmoji() {
    return iconEmoji;
  }

  public String getVoice() {
    return voice;
  }

  public String getVoiceAgentId() {
    return voiceAgentId;
  }

  public String getReasoningEffort() {
    return reasoningEffort;
  }

  public boolean isToolChoiceRequired() {
    return toolChoiceRequired;
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

  public record VoiceSessionConfig(
      String speedDialId,
      String systemPrompt,
      String voice,
      String voiceAgentId,
      String reasoningEffort,
      boolean toolChoiceRequired,
      String enabledTools) {}
}
