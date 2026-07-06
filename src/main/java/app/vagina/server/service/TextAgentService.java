package app.vagina.server.service;

import app.vagina.server.entity.TextAgentDefinition;
import app.vagina.server.mapper.TextAgentMapper;
import app.vagina.server.mapper.TextAgentMapper.Row;
import app.vagina.server.support.Util;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class TextAgentService {
  public static final String DEFAULT_ENABLED_TOOLS = "{}";
  private static final String TEXT_AGENT_ID_PREFIX = "ta_";

  @Inject TextAgentMapper textAgentMapper;

  public List<TextAgentDefinition> findByUserId(Long userId) {
    return textAgentMapper.findByUserId(userId).stream().map(this::toDomain).toList();
  }

  public Optional<TextAgentDefinition> findByUserIdAndTextAgentId(Long userId, String textAgentId) {
    return textAgentMapper.findByUserIdAndTextAgentId(userId, textAgentId).map(this::toDomain);
  }

  @Transactional
  public TextAgentDefinition create(Long userId, CreateCommand command) {
    LocalDateTime now = LocalDateTime.now();
    TextAgentDefinition definition =
        new TextAgentDefinition(
            null,
            userId,
            generateTextAgentId(),
            command.name(),
            command.prompt(),
            command.description(),
            command.textModelId(),
            command.enabledTools(),
            now,
            now);
    Row row = toRow(definition);
    textAgentMapper.insert(row);
    definition.setGeneratedId(row.getId());
    return definition;
  }

  @Transactional
  public Optional<TextAgentDefinition> update(
      Long userId, String textAgentId, UpdateCommand command) {
    Optional<TextAgentDefinition> existing = findByUserIdAndTextAgentId(userId, textAgentId);
    if (existing.isEmpty()) {
      return Optional.empty();
    }

    TextAgentDefinition persisted = existing.get();
    persisted.updateDefinition(
        command.name(),
        command.prompt(),
        command.description(),
        command.textModelId(),
        command.enabledTools(),
        LocalDateTime.now());
    textAgentMapper.update(toRow(persisted));
    return Optional.of(persisted);
  }

  @Transactional
  public boolean delete(Long userId, String textAgentId) {
    return textAgentMapper.deleteByUserIdAndTextAgentId(userId, textAgentId) > 0;
  }

  private TextAgentDefinition toDomain(Row row) {
    return new TextAgentDefinition(
        row.getId(),
        row.getUserId(),
        row.getTextAgentId(),
        row.getName(),
        row.getPrompt(),
        row.getDescription(),
        row.getTextModelId(),
        row.getEnabledTools(),
        row.getCreatedAt(),
        row.getUpdatedAt());
  }

  private Row toRow(TextAgentDefinition definition) {
    Row row = new Row();
    row.setId(definition.getId());
    row.setUserId(definition.getUserId());
    row.setTextAgentId(definition.getTextAgentId());
    row.setName(definition.getName());
    row.setPrompt(definition.getPrompt());
    row.setDescription(definition.getDescription());
    row.setTextModelId(definition.getTextModelId());
    row.setEnabledTools(definition.getEnabledTools());
    row.setCreatedAt(definition.getCreatedAt());
    row.setUpdatedAt(definition.getUpdatedAt());
    return row;
  }

  private String generateTextAgentId() {
    return Util.randomPublicId(TEXT_AGENT_ID_PREFIX);
  }

  public record CreateCommand(
      String name, String prompt, String description, String textModelId, String enabledTools) {}

  public record UpdateCommand(
      String name, String prompt, String description, String textModelId, String enabledTools) {}
}
