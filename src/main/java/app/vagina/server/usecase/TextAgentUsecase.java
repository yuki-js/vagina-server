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
import app.vagina.server.textagent.TextAgentRuntimeModels.ToolCatalogEntry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class TextAgentUsecase {
  private static final String QUERY_TEXT_AGENT_TOOL_NAME = "query_text_agent";
  private static final String END_CALL_TOOL_NAME = "end_call";
  private static final TypeReference<Map<String, Boolean>> ENABLED_TOOLS_TYPE =
      new TypeReference<>() {};

  @Inject TextAgentService textAgentService;
  @Inject TextAgentModelRegistryService textAgentModelRegistryService;
  @Inject VhrpSessionRegistry vhrpSessionRegistry;
  @Inject TextAgentAdapterFactory textAgentAdapterFactory;
  @Inject ObjectMapper objectMapper;

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
    VhrpSession session =
        vhrpSessionRegistry.getOwnedActiveSession(command.voiceSessionId(), userId);
    ProviderSessionState sessionState =
        session.textAgentProviderState(textAgentId, initialBinding(textAgent));
    QueryResult pendingToolResult = validateAndApplyRequestState(command, sessionState);
    if (pendingToolResult != null) {
      return pendingToolResult;
    }
    TextAgentAdapter adapter = textAgentAdapterFactory.create(sessionState.binding());
    ProviderContext context =
        new ProviderContext(textAgent, command, sessionState, allowedToolCatalog(textAgent, command));
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

  private QueryResult validateAndApplyRequestState(
      QueryCommand command, ProviderSessionState sessionState) {
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
    throw new ConflictException("Text agent request is waiting for pending tool results");
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
          "Submitted tool result request id does not match the active request");
    }
    if (!sessionState.acceptPendingToolResult(command.toolResult())) {
      throw new ConflictException("Submitted tool result does not match a pending tool call");
    }
    if (sessionState.hasPendingToolCalls()) {
      return QueryResult.requiresTool(sessionState.pendingToolCalls());
    }
    return null;
  }

  private List<ToolCatalogEntry> allowedToolCatalog(
      TextAgentDefinition textAgent, QueryCommand command) {
    EnabledToolsParseResult enabledTools = parseEnabledTools(textAgent.getEnabledTools());
    if (!enabledTools.valid()) {
      return List.of();
    }

    // Product intent: Text Agent and Voice Agent / Speed Dial tool allow-lists are intentionally
    // independent. TA tool schemas are supplied by the client because ToolRunner executes tools on
    // the client. Do not derive this provider catalog from VhrpSession.textAgentToolCatalogSnapshot(),
    // VA tools.set, or the Voice Agent's Speed Dial exposed tools. A Voice Agent may delegate to a
    // smarter Text Agent that can use tools unavailable to the VA; preserving that independence is a
    // key product capability. end_call is a Voice Agent authority tool: child Text Agents must not be
    // delegated authority to terminate their parent voice call even when sparse enabledTools defaults
    // or explicit true apply.
    return command.toolCatalog().stream()
        .filter(tool -> !QUERY_TEXT_AGENT_TOOL_NAME.equals(tool.name()))
        .filter(tool -> !END_CALL_TOOL_NAME.equals(tool.name()))
        .filter(tool -> enabledTools.overrides().getOrDefault(tool.name(), true))
        .toList();
  }

  private EnabledToolsParseResult parseEnabledTools(String enabledToolsJson) {
    if (enabledToolsJson == null || enabledToolsJson.isBlank()) {
      return EnabledToolsParseResult.valid(Map.of());
    }
    try {
      Map<String, Boolean> parsed = objectMapper.readValue(enabledToolsJson, ENABLED_TOOLS_TYPE);
      if (parsed == null) {
        return EnabledToolsParseResult.valid(Map.of());
      }
      return EnabledToolsParseResult.valid(new HashMap<>(parsed));
    } catch (JsonProcessingException e) {
      return EnabledToolsParseResult.invalid();
    }
  }

  private record EnabledToolsParseResult(boolean valid, Map<String, Boolean> overrides) {
    private static EnabledToolsParseResult valid(Map<String, Boolean> overrides) {
      return new EnabledToolsParseResult(true, overrides);
    }

    private static EnabledToolsParseResult invalid() {
      return new EnabledToolsParseResult(false, Map.of());
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
