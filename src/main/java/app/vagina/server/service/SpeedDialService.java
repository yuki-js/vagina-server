package app.vagina.server.service;

import app.vagina.server.entity.SpeedDialPreset;
import app.vagina.server.mapper.SpeedDialMapper;
import app.vagina.server.mapper.SpeedDialMapper.Row;
import app.vagina.server.support.Util;
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
  private static final String SPEED_DIAL_ID_PREFIX = "sd_";

  @Inject SpeedDialMapper speedDialMapper;
  @Inject VoiceAgentService voiceAgentService;

  public List<SpeedDialPreset> findByUserId(Long userId) {
    return speedDialMapper.findByUserId(userId).stream().map(this::toDomain).toList();
  }

  public Optional<SpeedDialPreset> findByUserIdAndSpeedDialId(Long userId, String speedDialId) {
    return speedDialMapper.findByUserIdAndSpeedDialId(userId, speedDialId).map(this::toDomain);
  }

  @Transactional
  public SpeedDialPreset ensureDefaultExists(Long userId) {
    Optional<SpeedDialPreset> existing = findByUserIdAndSpeedDialId(userId, DEFAULT_SPEED_DIAL_ID);
    if (existing.isPresent()) {
      return existing.get();
    }

    LocalDateTime now = LocalDateTime.now();
    SpeedDialPreset preset =
        new SpeedDialPreset(
            null,
            userId,
            DEFAULT_SPEED_DIAL_ID,
            DEFAULT_SPEED_DIAL_NAME,
            DEFAULT_SYSTEM_PROMPT,
            DEFAULT_DESCRIPTION,
            null,
            DEFAULT_VOICE,
            voiceAgentService.defaultModelId(),
            false,
            DEFAULT_ENABLED_TOOLS,
            now,
            now);
    Row row = toRow(preset);
    speedDialMapper.insert(row);
    preset.setGeneratedId(row.getId());
    return preset;
  }

  @Transactional
  public SpeedDialPreset create(Long userId, CreateCommand command) {
    LocalDateTime now = LocalDateTime.now();
    SpeedDialPreset preset =
        new SpeedDialPreset(
            null,
            userId,
            generateSpeedDialId(),
            command.name(),
            command.systemPrompt(),
            command.description(),
            command.iconEmoji(),
            command.voice(),
            command.voiceAgentId(),
            command.toolChoiceRequired(),
            command.enabledTools(),
            now,
            now);
    Row row = toRow(preset);
    speedDialMapper.insert(row);
    preset.setGeneratedId(row.getId());
    return preset;
  }

  @Transactional
  public Optional<SpeedDialPreset> update(Long userId, String speedDialId, UpdateCommand command) {
    Optional<SpeedDialPreset> existing = findByUserIdAndSpeedDialId(userId, speedDialId);
    if (existing.isEmpty()) {
      return Optional.empty();
    }

    SpeedDialPreset persisted = existing.get();
    persisted.updateConfiguration(
        command.name(),
        command.systemPrompt(),
        command.description(),
        command.iconEmoji(),
        command.voice(),
        command.voiceAgentId(),
        command.toolChoiceRequired(),
        command.enabledTools(),
        LocalDateTime.now());
    speedDialMapper.update(toRow(persisted));
    return Optional.of(persisted);
  }

  @Transactional
  public boolean delete(Long userId, String speedDialId) {
    return speedDialMapper.deleteByUserIdAndSpeedDialId(userId, speedDialId) > 0;
  }

  private SpeedDialPreset toDomain(Row row) {
    return new SpeedDialPreset(
        row.getId(),
        row.getUserId(),
        row.getSpeedDialId(),
        row.getName(),
        row.getSystemPrompt(),
        row.getDescription(),
        row.getIconEmoji(),
        row.getVoice(),
        row.getVoiceAgentId(),
        row.isToolChoiceRequired(),
        row.getEnabledTools(),
        row.getCreatedAt(),
        row.getUpdatedAt());
  }

  private Row toRow(SpeedDialPreset preset) {
    Row row = new Row();
    row.setId(preset.getId());
    row.setUserId(preset.getUserId());
    row.setSpeedDialId(preset.getSpeedDialId());
    row.setName(preset.getName());
    row.setSystemPrompt(preset.getSystemPrompt());
    row.setDescription(preset.getDescription());
    row.setIconEmoji(preset.getIconEmoji());
    row.setVoice(preset.getVoice());
    row.setVoiceAgentId(preset.getVoiceAgentId());
    row.setToolChoiceRequired(preset.isToolChoiceRequired());
    row.setEnabledTools(preset.getEnabledTools());
    row.setCreatedAt(preset.getCreatedAt());
    row.setUpdatedAt(preset.getUpdatedAt());
    return row;
  }

  private String generateSpeedDialId() {
    return SPEED_DIAL_ID_PREFIX + Util.hex(Util.randomBytes(16));
  }

  public record CreateCommand(
      String name,
      String systemPrompt,
      String description,
      String iconEmoji,
      String voice,
      String voiceAgentId,
      boolean toolChoiceRequired,
      String enabledTools) {}

  public record UpdateCommand(
      String name,
      String systemPrompt,
      String description,
      String iconEmoji,
      String voice,
      String voiceAgentId,
      boolean toolChoiceRequired,
      String enabledTools) {}
}
