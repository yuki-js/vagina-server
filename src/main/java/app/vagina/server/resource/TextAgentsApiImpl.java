package app.vagina.server.resource;

import app.vagina.server.entity.TextAgentDefinition;
import app.vagina.server.generated.api.TextAgentsApi;
import app.vagina.server.generated.model.TextAgent;
import app.vagina.server.generated.model.TextAgentCreateRequest;
import app.vagina.server.generated.model.TextAgentUpdateRequest;
import app.vagina.server.support.Authenticated;
import app.vagina.server.support.AuthenticatedUser;
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
  public Response createTextAgent(TextAgentCreateRequest textAgentCreateRequest) {
    Long userId = authenticatedUser.get().getId();
    TextAgentDefinition created =
        textAgentUsecase.createTextAgent(userId, toEntity(textAgentCreateRequest));
    return Response.status(Response.Status.CREATED).entity(toGeneratedModel(created)).build();
  }

  @Override
  public Response getTextAgent(String textAgentId) {
    Long userId = authenticatedUser.get().getId();
    TextAgentDefinition textAgentDefinition = textAgentUsecase.getTextAgent(userId, textAgentId);
    return Response.ok(toGeneratedModel(textAgentDefinition)).build();
  }

  @Override
  public Response updateTextAgent(String textAgentId, TextAgentUpdateRequest textAgentUpdateRequest) {
    Long userId = authenticatedUser.get().getId();
    TextAgentDefinition updated =
        textAgentUsecase.updateTextAgent(userId, textAgentId, toEntity(textAgentUpdateRequest));
    return Response.ok(toGeneratedModel(updated)).build();
  }

  @Override
  public Response deleteTextAgent(String textAgentId) {
    Long userId = authenticatedUser.get().getId();
    textAgentUsecase.deleteTextAgent(userId, textAgentId);
    return Response.noContent().build();
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

  private TextAgentDefinition toEntity(TextAgentCreateRequest model) {
    TextAgentDefinition definition = new TextAgentDefinition();
    definition.setName(model.getName());
    definition.setPrompt(model.getPrompt());
    definition.setDescription(model.getDescription());
    definition.setTextModelId(model.getTextModelId());
    definition.setEnabledTools(serializeEnabledTools(model.getEnabledTools()));
    return definition;
  }

  private TextAgentDefinition toEntity(TextAgentUpdateRequest model) {
    TextAgentDefinition definition = new TextAgentDefinition();
    definition.setName(model.getName());
    definition.setPrompt(model.getPrompt());
    definition.setDescription(model.getDescription());
    definition.setTextModelId(model.getTextModelId());
    definition.setEnabledTools(serializeEnabledTools(model.getEnabledTools()));
    return definition;
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
