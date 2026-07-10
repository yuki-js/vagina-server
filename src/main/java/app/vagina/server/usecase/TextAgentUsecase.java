package app.vagina.server.usecase;

import app.vagina.server.domain.error.ConflictException;
import app.vagina.server.domain.error.NotFoundException;
import app.vagina.server.entity.TextAgentDefinition;
import app.vagina.server.realtime.VhrpSession;
import app.vagina.server.realtime.VhrpSessionRegistry;
import app.vagina.server.service.TextAgentModelRegistryService;
import app.vagina.server.service.TextAgentService;
import app.vagina.server.support.EnabledToolsJson;
import app.vagina.server.support.EnabledToolsJson.ParseResult;
import app.vagina.server.textagent.TextAgentAdapter;
import app.vagina.server.textagent.TextAgentAdapterFactory;
import app.vagina.server.textagent.TextAgentRuntimeModels.ProviderContext;
import app.vagina.server.textagent.TextAgentRuntimeModels.ProviderSessionState;
import app.vagina.server.textagent.TextAgentRuntimeModels.QueryCommand;
import app.vagina.server.textagent.TextAgentRuntimeModels.QueryResult;
import app.vagina.server.textagent.TextAgentRuntimeModels.TextAgentModelBinding;
import app.vagina.server.textagent.TextAgentRuntimeModels.ToolCatalogEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@ApplicationScoped
public class TextAgentUsecase {
  private static final String QUERY_TEXT_AGENT_TOOL_NAME = "query_text_agent";
  private static final String END_CALL_TOOL_NAME = "end_call";
  private static final Duration PENDING_REQUEST_TTL = Duration.ofMinutes(5);

  @Inject TextAgentService textAgentService;
  @Inject TextAgentModelRegistryService textAgentModelRegistryService;
  @Inject VhrpSessionRegistry vhrpSessionRegistry;
  @Inject TextAgentAdapterFactory textAgentAdapterFactory;
  @Inject ObjectMapper objectMapper;

  public List<TextAgentDefinition> listTextAgents(Long currentUserId) {
    return textAgentService.findByUserId(currentUserId);
  }

  public TextAgentDefinition getTextAgent(Long currentUserId, String textAgentId) {
    return textAgentService
        .findByUserIdAndTextAgentId(currentUserId, textAgentId)
        .orElseThrow(() -> new NotFoundException("Text agent not found"));
  }

  @Transactional
  public TextAgentDefinition createTextAgent(Long currentUserId, WriteCommand command) {
    textAgentModelRegistryService.validateEntitledModelId(currentUserId, command.textModelId());
    return textAgentService.create(currentUserId, command.toCreateServiceCommand());
  }

  @Transactional
  public TextAgentDefinition updateTextAgent(
      Long currentUserId, String textAgentId, WriteCommand command) {
    textAgentModelRegistryService.validateEntitledModelId(currentUserId, command.textModelId());
    return textAgentService
        .update(currentUserId, textAgentId, command.toUpdateServiceCommand())
        .orElseThrow(() -> new NotFoundException("Text agent not found"));
  }

  public QueryResult queryTextAgent(Long currentUserId, String textAgentId, QueryCommand command) {
    command.requireValidShape();
    TextAgentDefinition textAgent = getTextAgent(currentUserId, textAgentId);
    textAgentModelRegistryService.validateEntitledModelId(
        currentUserId, textAgent.getTextModelId());
    VhrpSession session =
        vhrpSessionRegistry.getOwnedActiveSession(command.voiceSessionId(), currentUserId);
    ProviderSessionState sessionState =
        session.textAgentProviderState(textAgentId, initialBinding(textAgent));
    QueryResult pendingToolResult = validateAndApplyRequestState(command, sessionState);
    if (pendingToolResult != null) {
      return pendingToolResult;
    }
    TextAgentAdapter adapter = textAgentAdapterFactory.create(sessionState.binding());
    ProviderContext context =
        new ProviderContext(
            textAgent.toTextAgentProviderView(),
            command,
            sessionState,
            allowedToolCatalog(textAgent, command));
    try {
      QueryResult result = adapter.execute(context);
      adapter.applyResultToSessionState(context, result);
      return result;
    } catch (RuntimeException | Error e) {
      sessionState.clearRequestState();
      throw e;
    }
  }

  private TextAgentModelBinding initialBinding(TextAgentDefinition textAgent) {
    TextAgentModelRegistryService.TextAgentModelConfigView modelConfig =
        textAgentModelRegistryService.getModelConfig(textAgent.getTextModelId());
    return new TextAgentModelBinding(
        modelConfig.id(),
        modelConfig.provider(),
        modelConfig.baseUrl(),
        modelConfig.apiKey(),
        modelConfig.model());
  }

  private QueryResult validateAndApplyRequestState(
      QueryCommand command, ProviderSessionState sessionState) {
    boolean expired = sessionState.clearExpiredRequest(PENDING_REQUEST_TTL, Instant.now());
    if (expired && command.isToolResultStep()) {
      throw new ConflictException(
          "Submitted tool result is stale; the active text-agent request expired");
    }
    if (command.isPromptStep()) {
      validateAndStartPromptRequest(command, sessionState);
      return null;
    }
    return validateAndRecordToolResult(command, sessionState);
  }

  private void validateAndStartPromptRequest(
      QueryCommand command, ProviderSessionState sessionState) {
    if (!sessionState.hasActiveRequest()) {
      sessionState.startRequest(command.requestId());
      return;
    }
    String activeRequestId = sessionState.activeRequestId().orElse("<unknown>");
    throw new ConflictException(
        "Text agent request is waiting for pending tool results for request id: "
            + activeRequestId);
  }

  private QueryResult validateAndRecordToolResult(
      QueryCommand command, ProviderSessionState sessionState) {
    String requestId = command.requestId();
    String toolCallId = command.toolResult().toolCallId();
    String activeRequestId =
        sessionState
            .activeRequestId()
            .orElseThrow(
                () ->
                    new ConflictException(
                        "Submitted tool result does not match an active request"));
    if (!activeRequestId.equals(requestId)) {
      throw new ConflictException(
          "Submitted tool result request id "
              + requestId
              + " does not match active request id "
              + activeRequestId);
    }
    if (sessionState.hasAcceptedToolResult(toolCallId)) {
      throw new ConflictException(
          "Submitted duplicate tool result for tool call id: " + toolCallId);
    }
    if (!sessionState.hasPendingToolCall(toolCallId)) {
      throw new ConflictException(
          "Submitted tool result does not match pending tool call id: " + toolCallId);
    }
    if (!sessionState.acceptPendingToolResult(command.toolResult())) {
      throw new ConflictException(
          "Submitted duplicate tool result for tool call id: " + toolCallId);
    }
    if (sessionState.hasPendingToolCalls()) {
      return QueryResult.requiresTool(sessionState.pendingToolCalls());
    }
    return null;
  }

  private List<ToolCatalogEntry> allowedToolCatalog(
      TextAgentDefinition textAgent, QueryCommand command) {
    ParseResult enabledTools = EnabledToolsJson.parse(objectMapper, textAgent.getEnabledTools());
    if (!enabledTools.valid()) {
      return List.of();
    }

    return command.toolCatalog().stream()
        .filter(tool -> !QUERY_TEXT_AGENT_TOOL_NAME.equals(tool.name()))
        .filter(tool -> !END_CALL_TOOL_NAME.equals(tool.name()))
        .filter(tool -> enabledTools.overrides().getOrDefault(tool.name(), true))
        .toList();
  }

  @Transactional
  public void deleteTextAgent(Long currentUserId, String textAgentId) {
    boolean deleted = textAgentService.delete(currentUserId, textAgentId);
    if (!deleted) {
      throw new NotFoundException("Text agent not found");
    }
  }

  public record WriteCommand(
      String name, String prompt, String description, String textModelId, String enabledTools) {
    TextAgentService.CreateCommand toCreateServiceCommand() {
      return new TextAgentService.CreateCommand(
          name, prompt, description, textModelId, enabledTools);
    }

    TextAgentService.UpdateCommand toUpdateServiceCommand() {
      return new TextAgentService.UpdateCommand(
          name, prompt, description, textModelId, enabledTools);
    }
  }
}
