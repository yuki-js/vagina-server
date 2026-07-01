package app.vagina.server.resource;

import app.vagina.server.entity.TextAgentDefinition;
import app.vagina.server.generated.api.TextAgentsApi;
import app.vagina.server.generated.model.QueryTextAgent200Response;
import app.vagina.server.generated.model.QueryTextAgent200ResponseError;
import app.vagina.server.generated.model.QueryTextAgentRequest;
import app.vagina.server.generated.model.QueryTextAgentRequestToolResult;
import app.vagina.server.generated.model.TextAgent;
import app.vagina.server.generated.model.TextAgentToolCall;
import app.vagina.server.generated.model.TextAgentWriteRequest;
import app.vagina.server.support.Authenticated;
import app.vagina.server.support.AuthenticatedUser;
import app.vagina.server.textagent.TextAgentRuntimeModels.QueryCommand;
import app.vagina.server.textagent.TextAgentRuntimeModels.QueryResult;
import app.vagina.server.textagent.TextAgentRuntimeModels.QueryStatus;
import app.vagina.server.textagent.TextAgentRuntimeModels.ToolCall;
import app.vagina.server.textagent.TextAgentRuntimeModels.ToolResultSubmission;
import app.vagina.server.usecase.TextAgentUsecase;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

@ApplicationScoped
@Path("/text-agents")
@Authenticated
public class TextAgentsApiImpl implements TextAgentsApi {
  private static final TypeReference<LinkedHashMap<String, Boolean>> ENABLED_TOOLS_TYPE =
      new TypeReference<>() {};

  @Inject TextAgentUsecase textAgentUsecase;
  @Inject AuthenticatedUser authenticatedUser;
  @Inject ObjectMapper objectMapper;

  @Override
  public Response listTextAgents() {
    Long userId = authenticatedUser.get().getId();
    List<TextAgent> textAgents =
        textAgentUsecase.listTextAgents(userId).stream().map(this::toGeneratedModel).toList();
    return Response.ok(textAgents).build();
  }

  @Override
  public Response createTextAgent(TextAgentWriteRequest textAgentWriteRequest) {
    Long userId = authenticatedUser.get().getId();
    TextAgentDefinition created = textAgentUsecase.createTextAgent(userId, toEntity(textAgentWriteRequest));
    return Response.status(Response.Status.CREATED).entity(toGeneratedModel(created)).build();
  }

  @Override
  public Response getTextAgent(String textAgentId) {
    Long userId = authenticatedUser.get().getId();
    TextAgentDefinition textAgentDefinition = textAgentUsecase.getTextAgent(userId, textAgentId);
    return Response.ok(toGeneratedModel(textAgentDefinition)).build();
  }

  @Override
  public Response updateTextAgent(String textAgentId, TextAgentWriteRequest textAgentWriteRequest) {
    Long userId = authenticatedUser.get().getId();
    TextAgentDefinition updated =
        textAgentUsecase.updateTextAgent(userId, textAgentId, toEntity(textAgentWriteRequest));
    return Response.ok(toGeneratedModel(updated)).build();
  }

  @Override
  public Response deleteTextAgent(String textAgentId) {
    Long userId = authenticatedUser.get().getId();
    textAgentUsecase.deleteTextAgent(userId, textAgentId);
    return Response.noContent().build();
  }

  @Override
  public Response queryTextAgent(String textAgentId, QueryTextAgentRequest queryTextAgentRequest) {
    Long userId = authenticatedUser.get().getId();
    QueryResult result = textAgentUsecase.queryTextAgent(userId, textAgentId, toCommand(queryTextAgentRequest));
    return Response.ok(toGeneratedQueryResponse(result)).build();
  }

  private TextAgent toGeneratedModel(TextAgentDefinition definition) {
    TextAgent model = new TextAgent();
    model.setId(definition.getTextAgentId());
    model.setName(definition.getName());
    model.setPrompt(definition.getPrompt());
    model.setDescription(definition.getDescription());
    model.setTextModelId(definition.getTextModelId());
    model.setEnabledTools(deserializeEnabledTools(definition.getEnabledTools()));
    if (definition.getCreatedAt() != null) {
      model.setCreatedAt(definition.getCreatedAt().atOffset(ZoneOffset.UTC));
    }
    return model;
  }

  private TextAgentDefinition toEntity(TextAgentWriteRequest model) {
    TextAgentDefinition definition = new TextAgentDefinition();
    definition.setName(model.getName());
    definition.setPrompt(model.getPrompt());
    definition.setDescription(model.getDescription());
    definition.setTextModelId(model.getTextModelId());
    definition.setEnabledTools(serializeEnabledTools(model.getEnabledTools()));
    return definition;
  }

  private QueryCommand toCommand(QueryTextAgentRequest request) {
    return new QueryCommand(
        request.getVoiceSessionId(),
        request.getRequestId(),
        request.getPrompt(),
        toToolResultSubmission(request.getToolResult()));
  }

  private ToolResultSubmission toToolResultSubmission(QueryTextAgentRequestToolResult toolResult) {
    if (toolResult == null) {
      return null;
    }
    return new ToolResultSubmission(
        toolResult.getToolCallId(), toolResult.getOutput(), Boolean.TRUE.equals(toolResult.getIsError()));
  }

  private QueryTextAgent200Response toGeneratedQueryResponse(QueryResult result) {
    QueryTextAgent200Response response = new QueryTextAgent200Response();
    response.setStatus(toGeneratedQueryStatus(result.status()));
    response.setText(result.text());
    response.setToolCalls(result.toolCalls().stream().map(this::toGeneratedToolCall).toList());
    if (result.error() != null) {
      response.setError(
          new QueryTextAgent200ResponseError()
              .code(result.error().code())
              .message(result.error().message()));
    }
    return response;
  }

  private QueryTextAgent200Response.StatusEnum toGeneratedQueryStatus(QueryStatus status) {
    return switch (status) {
      case COMPLETED -> QueryTextAgent200Response.StatusEnum.COMPLETED;
      case REQUIRES_TOOL -> QueryTextAgent200Response.StatusEnum.REQUIRES_TOOL;
      case FAILED -> QueryTextAgent200Response.StatusEnum.FAILED;
    };
  }

  private TextAgentToolCall toGeneratedToolCall(ToolCall toolCall) {
    return new TextAgentToolCall()
        .id(toolCall.id())
        .name(toolCall.name())
        .arguments(toolCall.arguments());
  }

  private String serializeEnabledTools(Object enabledTools) {
    try {
      return objectMapper.writeValueAsString(normalizeEnabledTools(enabledTools));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to serialize text agent enabled tools", e);
    }
  }

  private Map<String, Boolean> normalizeEnabledTools(Object enabledTools) {
    if (enabledTools == null) {
      return Map.of();
    }
    if (!(enabledTools instanceof Map<?, ?> rawEnabledTools)) {
      throw new IllegalArgumentException("Text agent enabled tools must be a JSON object");
    }

    Map<String, Boolean> normalized = new LinkedHashMap<>();
    for (Entry<?, ?> entry : rawEnabledTools.entrySet()) {
      if (!(entry.getKey() instanceof String toolName)) {
        throw new IllegalArgumentException("Text agent enabled tool names must be strings");
      }
      if (!(entry.getValue() instanceof Boolean enabled)) {
        throw new IllegalArgumentException("Text agent enabled tool values must be booleans");
      }
      normalized.put(toolName, enabled);
    }
    return normalized;
  }

  private Map<String, Boolean> deserializeEnabledTools(String enabledToolsJson) {
    if (enabledToolsJson == null || enabledToolsJson.isBlank()) {
      return Map.of();
    }

    try {
      return objectMapper.readValue(enabledToolsJson, ENABLED_TOOLS_TYPE);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to deserialize text agent enabled tools", e);
    }
  }
}
