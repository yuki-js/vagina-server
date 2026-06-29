package app.vagina.server.usecase;

import app.vagina.server.domain.error.NotFoundException;
import app.vagina.server.domain.error.ValidationException;
import app.vagina.server.entity.TextAgentDefinition;
import app.vagina.server.service.TextAgentModelRegistryService;
import app.vagina.server.service.TextAgentService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.List;

@ApplicationScoped
public class TextAgentUsecase {
  @Inject TextAgentService textAgentService;
  @Inject TextAgentModelRegistryService textAgentModelRegistryService;

  public List<TextAgentDefinition> listTextAgents(Long userId) {
    return textAgentService.findByUserId(userId);
  }

  public TextAgentDefinition getTextAgent(Long userId, String textAgentId) {
    return textAgentService
        .findByUserIdAndTextAgentId(userId, textAgentId)
        .orElseThrow(() -> new NotFoundException("Text agent not found"));
  }

  @Transactional
  public TextAgentDefinition createTextAgent(Long userId, TextAgentDefinition candidate) {
    validateTextModel(candidate.getTextModelId());
    return textAgentService.create(userId, candidate);
  }

  @Transactional
  public TextAgentDefinition updateTextAgent(
      Long userId, String textAgentId, TextAgentDefinition candidate) {
    validateTextModel(candidate.getTextModelId());
    return textAgentService
        .update(userId, textAgentId, candidate)
        .orElseThrow(() -> new NotFoundException("Text agent not found"));
  }

  private void validateTextModel(String textModelId) {
    if (textModelId == null || textModelId.isBlank()) {
      throw new ValidationException("Text model id is required");
    }
    if (!textAgentModelRegistryService.isKnownModel(textModelId)) {
      throw new ValidationException("Unknown text model id: " + textModelId);
    }
  }

  @Transactional
  public void deleteTextAgent(Long userId, String textAgentId) {
    boolean deleted = textAgentService.delete(userId, textAgentId);
    if (!deleted) {
      throw new NotFoundException("Text agent not found");
    }
  }
}
