package app.vagina.server.service;

import app.vagina.server.entity.SpeedDialPreset;
import app.vagina.server.mapper.SpeedDialMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class SpeedDialService {
  public static final String DEFAULT_SPEED_DIAL_ID = "default";
  public static final String DEFAULT_SPEED_DIAL_NAME = "Default";
  public static final String DEFAULT_SYSTEM_PROMPT = "You are a helpful AI assistant.";
  public static final String DEFAULT_DESCRIPTION = "Default voice assistant";
  public static final String DEFAULT_VOICE = "alloy";
  public static final String DEFAULT_ENABLED_TOOLS = "{}";

  @Inject SpeedDialMapper speedDialMapper;
  @Inject VoiceAgentService voiceAgentService;

  public List<SpeedDialPreset> findByUserId(Long userId) {
    return speedDialMapper.findByUserId(userId);
  }

  public Optional<SpeedDialPreset> findByUserIdAndSpeedDialId(Long userId, String speedDialId) {
    return speedDialMapper.findByUserIdAndSpeedDialId(userId, speedDialId);
  }

  @Transactional
  public SpeedDialPreset ensureDefaultExists(Long userId) {
    Optional<SpeedDialPreset> existing = findByUserIdAndSpeedDialId(userId, DEFAULT_SPEED_DIAL_ID);
    if (existing.isPresent()) {
      return existing.get();
    }

    LocalDateTime now = LocalDateTime.now();
    SpeedDialPreset preset = new SpeedDialPreset();
    preset.setUserId(userId);
    preset.setSpeedDialId(DEFAULT_SPEED_DIAL_ID);
    preset.setName(DEFAULT_SPEED_DIAL_NAME);
    preset.setSystemPrompt(DEFAULT_SYSTEM_PROMPT);
    preset.setDescription(DEFAULT_DESCRIPTION);
    preset.setIconEmoji(null);
    preset.setVoice(DEFAULT_VOICE);
    preset.setVoiceAgentId(voiceAgentService.defaultModelId());
    preset.setReasoningEffort("off");
    preset.setToolChoiceRequired(false);
    preset.setEnabledTools(DEFAULT_ENABLED_TOOLS);
    preset.setCreatedAt(now);
    preset.setUpdatedAt(now);
    speedDialMapper.insert(preset);
    return preset;
  }

  @Transactional
  public SpeedDialPreset save(Long userId, SpeedDialPreset candidate) {
    Optional<SpeedDialPreset> existing =
        findByUserIdAndSpeedDialId(userId, candidate.getSpeedDialId());
    LocalDateTime now = LocalDateTime.now();

    if (existing.isPresent()) {
      SpeedDialPreset persisted = existing.get();
      persisted.setName(candidate.getName());
      persisted.setSystemPrompt(candidate.getSystemPrompt());
      persisted.setDescription(candidate.getDescription());
      persisted.setIconEmoji(candidate.getIconEmoji());
      persisted.setVoice(candidate.getVoice());
      persisted.setVoiceAgentId(candidate.getVoiceAgentId());
      persisted.setReasoningEffort(candidate.getReasoningEffort());
      persisted.setToolChoiceRequired(candidate.isToolChoiceRequired());
      persisted.setEnabledTools(candidate.getEnabledTools());
      persisted.setUpdatedAt(now);
      speedDialMapper.update(persisted);
      return persisted;
    }

    candidate.setUserId(userId);
    candidate.setCreatedAt(now);
    candidate.setUpdatedAt(now);
    speedDialMapper.insert(candidate);
    return candidate;
  }

  @Transactional
  public boolean delete(Long userId, String speedDialId) {
    return speedDialMapper.deleteByUserIdAndSpeedDialId(userId, speedDialId) > 0;
  }
}
