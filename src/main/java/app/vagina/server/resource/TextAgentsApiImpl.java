package app.vagina.server.resource;

import app.vagina.server.entity.TextAgentDefinition;
import app.vagina.server.generated.api.TextAgentsApi;
import app.vagina.server.generated.model.QueryTextAgent200Response;
import app.vagina.server.generated.model.QueryTextAgent200ResponseError;
import app.vagina.server.generated.model.QueryTextAgentRequest;
import app.vagina.server.generated.model.QueryTextAgentRequestImagesInner;
import app.vagina.server.generated.model.QueryTextAgentRequestToolResult;
import app.vagina.server.generated.model.QueryTextAgentRequestToolSchemasInner;
import app.vagina.server.generated.model.TextAgent;
import app.vagina.server.generated.model.TextAgentToolCall;
import app.vagina.server.generated.model.TextAgentWriteRequest;
import app.vagina.server.support.Authenticated;
import app.vagina.server.support.AuthenticatedUser;
import app.vagina.server.support.EnabledToolsJson;
import app.vagina.server.textagent.TextAgentRuntimeModels.QueryCommand;
import app.vagina.server.textagent.TextAgentRuntimeModels.QueryImageInput;
import app.vagina.server.textagent.TextAgentRuntimeModels.QueryResult;
import app.vagina.server.textagent.TextAgentRuntimeModels.QueryStatus;
import app.vagina.server.textagent.TextAgentRuntimeModels.ToolCall;
import app.vagina.server.textagent.TextAgentRuntimeModels.ToolCatalogEntry;
import app.vagina.server.textagent.TextAgentRuntimeModels.ToolResultSubmission;
import app.vagina.server.usecase.TextAgentUsecase;
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
  private static final String ENABLED_TOOL_LABEL = "Text agent enabled tool";
  private static final String ENABLED_TOOLS_LABEL = "text agent enabled tools";

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
    TextAgentDefinition created =
        textAgentUsecase.createTextAgent(userId, toWriteCommand(textAgentWriteRequest));
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
        textAgentUsecase.updateTextAgent(
            userId, textAgentId, toWriteCommand(textAgentWriteRequest));
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
    QueryResult result =
        textAgentUsecase.queryTextAgent(userId, textAgentId, toCommand(queryTextAgentRequest));
    return Response.ok(toGeneratedQueryResponse(result)).build();
  }

  private TextAgent toGeneratedModel(TextAgentDefinition definition) {
    TextAgent model = new TextAgent();
    model.setId(definition.getTextAgentId());
    model.setName(definition.getName());
    model.setPrompt(definition.getPrompt());
    model.setDescription(definition.getDescription());
    model.setTextModelId(definition.getTextModelId());
    model.setEnabledTools(
        EnabledToolsJson.deserialize(
            objectMapper, definition.getEnabledTools(), ENABLED_TOOLS_LABEL));
    if (definition.getCreatedAt() != null) {
      model.setCreatedAt(definition.getCreatedAt().atOffset(ZoneOffset.UTC));
    }
    return model;
  }

  private TextAgentUsecase.WriteCommand toWriteCommand(TextAgentWriteRequest model) {
    return new TextAgentUsecase.WriteCommand(
        model.getName(),
        model.getPrompt(),
        model.getDescription(),
        model.getTextModelId(),
        EnabledToolsJson.serialize(
            objectMapper, model.getEnabledTools(), ENABLED_TOOL_LABEL, ENABLED_TOOLS_LABEL));
  }

  private QueryCommand toCommand(QueryTextAgentRequest request) {
    return new QueryCommand(
        request.getVoiceSessionId(),
        request.getRequestId(),
        request.getPrompt(),
        toQueryImages(request.getImages()),
        toToolResultSubmission(request.getToolResult()),
        toToolCatalog(request.getToolSchemas()));
  }

  private List<QueryImageInput> toQueryImages(List<QueryTextAgentRequestImagesInner> images) {
    if (images == null) {
      return List.of();
    }
    return images.stream()
        .map(
            image ->
                new QueryImageInput(
                    image.getDataUri(),
                    image.getDetail() == null ? null : image.getDetail().value(),
                    image.getName()))
        .toList();
  }

  private List<ToolCatalogEntry> toToolCatalog(
      List<QueryTextAgentRequestToolSchemasInner> toolSchemas) {
    if (toolSchemas == null) {
      return List.of();
    }
    return toolSchemas.stream()
        .map(
            toolSchema ->
                new ToolCatalogEntry(
                    toolSchema.getName(),
                    toolSchema.getDescription(),
                    normalizeToolParameters(toolSchema.getParameters())))
        .toList();
  }

  private Map<String, Object> normalizeToolParameters(Object parameters) {
    if (parameters == null) {
      return Map.of();
    }
    if (!(parameters instanceof Map<?, ?> rawParameters)) {
      throw new IllegalArgumentException("Text agent tool schema parameters must be a JSON object");
    }
    Map<String, Object> normalized = new LinkedHashMap<>();
    for (Entry<?, ?> entry : rawParameters.entrySet()) {
      if (!(entry.getKey() instanceof String key)) {
        throw new IllegalArgumentException("Text agent tool schema parameter keys must be strings");
      }
      normalized.put(key, entry.getValue());
    }
    return normalized;
  }

  private ToolResultSubmission toToolResultSubmission(QueryTextAgentRequestToolResult toolResult) {
    if (toolResult == null) {
      return null;
    }
    return new ToolResultSubmission(
        toolResult.getToolCallId(),
        toolResult.getOutput(),
        Boolean.TRUE.equals(toolResult.getIsError()));
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
}
