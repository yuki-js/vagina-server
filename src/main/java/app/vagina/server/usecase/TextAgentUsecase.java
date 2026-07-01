package app.vagina.server.usecase;

import app.vagina.server.domain.error.ConflictException;
import app.vagina.server.domain.error.NotFoundException;
import app.vagina.server.domain.error.ValidationException;
import app.vagina.server.entity.TextAgentDefinition;
import app.vagina.server.realtime.VhrpSession;
import app.vagina.server.realtime.VhrpSessionRegistry;
import app.vagina.server.service.TextAgentModelRegistryService;
import app.vagina.server.service.TextAgentService;
import app.vagina.server.textagent.TextAgentAdapter;
import app.vagina.server.textagent.TextAgentAdapterFactory;
import app.vagina.server.textagent.TextAgentRuntimeModels.ProviderContext;
import app.vagina.server.textagent.TextAgentRuntimeModels.ProviderSessionState;
import app.vagina.server.textagent.TextAgentRuntimeModels.QueryCommand;
import app.vagina.server.textagent.TextAgentRuntimeModels.QueryResult;
import app.vagina.server.textagent.TextAgentRuntimeModels.TextAgentModelBinding;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.List;

@ApplicationScoped
public class TextAgentUsecase {
  @Inject TextAgentService textAgentService;
  @Inject TextAgentModelRegistryService textAgentModelRegistryService;
  @Inject VhrpSessionRegistry vhrpSessionRegistry;
  @Inject TextAgentAdapterFactory textAgentAdapterFactory;

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

  public QueryResult queryTextAgent(Long userId, String textAgentId, QueryCommand command) {
    command.requireValidShape();
    TextAgentDefinition textAgent = getTextAgent(userId, textAgentId);
    VhrpSession session = vhrpSessionRegistry.getOwnedActiveSession(command.voiceSessionId(), userId);
    ProviderSessionState sessionState =
        session.textAgentProviderState(textAgentId, initialBinding(textAgent));
    validatePendingToolState(command, sessionState);
    TextAgentAdapter adapter = textAgentAdapterFactory.create(sessionState.binding());
    ProviderContext context = new ProviderContext(textAgent, command, sessionState);
    QueryResult result = adapter.execute(context);
    adapter.applyResultToSessionState(context, result);
    return result;
  }

  private TextAgentModelBinding initialBinding(TextAgentDefinition textAgent) {
    TextAgentModelRegistryService.TextAgentModelConfigView modelConfig =
        textAgentModelRegistryService.getModelConfig(textAgent.getTextModelId());
    return textAgentAdapterFactory.binding(
        modelConfig.id(),
        modelConfig.provider(),
        modelConfig.baseUrl(),
        modelConfig.apiKey(),
        modelConfig.model());
  }

  private void validatePendingToolState(QueryCommand command, ProviderSessionState sessionState) {
    if (command.isPromptStep()) {
      if (sessionState.hasPendingToolCalls()) {
        throw new ConflictException("Text agent is waiting for a pending tool result");
      }
      return;
    }
    String toolCallId = command.toolResult().toolCallId();
    if (!sessionState.hasPendingToolCall(toolCallId)) {
      throw new ConflictException("Submitted tool result does not match a pending tool call");
    }
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
