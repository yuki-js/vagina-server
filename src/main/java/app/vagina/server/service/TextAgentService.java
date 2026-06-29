package app.vagina.server.service;

import app.vagina.server.entity.TextAgentDefinition;
import app.vagina.server.mapper.TextAgentMapper;
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
    return textAgentMapper.findByUserId(userId);
  }

  public Optional<TextAgentDefinition> findByUserIdAndTextAgentId(Long userId, String textAgentId) {
    return textAgentMapper.findByUserIdAndTextAgentId(userId, textAgentId);
  }

  @Transactional
  public TextAgentDefinition create(Long userId, TextAgentDefinition candidate) {
    LocalDateTime now = LocalDateTime.now();
    candidate.setUserId(userId);
    candidate.setTextAgentId(generateTextAgentId());
    candidate.setCreatedAt(now);
    candidate.setUpdatedAt(now);
    textAgentMapper.insert(candidate);
    return candidate;
  }

  @Transactional
  public Optional<TextAgentDefinition> update(
      Long userId, String textAgentId, TextAgentDefinition candidate) {
    Optional<TextAgentDefinition> existing = findByUserIdAndTextAgentId(userId, textAgentId);
    if (existing.isEmpty()) {
      return Optional.empty();
    }

    TextAgentDefinition persisted = existing.get();
    persisted.setName(candidate.getName());
    persisted.setPrompt(candidate.getPrompt());
    persisted.setDescription(candidate.getDescription());
    persisted.setTextModelId(candidate.getTextModelId());
    persisted.setEnabledTools(candidate.getEnabledTools());
    persisted.setUpdatedAt(LocalDateTime.now());
    textAgentMapper.update(persisted);
    return Optional.of(persisted);
  }

  @Transactional
  public boolean delete(Long userId, String textAgentId) {
    return textAgentMapper.deleteByUserIdAndTextAgentId(userId, textAgentId) > 0;
  }

  private String generateTextAgentId() {
    return Util.randomPublicId(TEXT_AGENT_ID_PREFIX);
  }
}
