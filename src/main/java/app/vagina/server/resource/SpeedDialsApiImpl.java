package app.vagina.server.resource;

import app.vagina.server.entity.SpeedDialPreset;
import app.vagina.server.generated.api.SpeedDialsApi;
import app.vagina.server.generated.model.SpeedDial;
import app.vagina.server.support.Authenticated;
import app.vagina.server.support.AuthenticatedUser;
import app.vagina.server.usecase.SpeedDialUsecase;
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
@Path("/speed-dials")
@Authenticated
public class SpeedDialsApiImpl implements SpeedDialsApi {
  private static final TypeReference<LinkedHashMap<String, Boolean>> ENABLED_TOOLS_TYPE =
      new TypeReference<>() {};

  @Inject SpeedDialUsecase speedDialUsecase;
  @Inject AuthenticatedUser authenticatedUser;
  @Inject ObjectMapper objectMapper;

  @Override
  public Response listSpeedDials() {
    Long userId = authenticatedUser.get().getId();
    List<SpeedDial> speedDials =
        speedDialUsecase.listSpeedDials(userId).stream().map(this::toGeneratedModel).toList();
    return Response.ok(speedDials).build();
  }

  @Override
  public Response getSpeedDial(String speedDialId) {
    Long userId = authenticatedUser.get().getId();
    SpeedDialPreset speedDialPreset = speedDialUsecase.getSpeedDial(userId, speedDialId);
    return Response.ok(toGeneratedModel(speedDialPreset)).build();
  }

  @Override
  public Response saveSpeedDial(String speedDialId, SpeedDial speedDial) {
    Long userId = authenticatedUser.get().getId();
    SpeedDialPreset saved =
        speedDialUsecase.saveSpeedDial(userId, speedDialId, toEntity(speedDial));
    return Response.ok(toGeneratedModel(saved)).build();
  }

  @Override
  public Response deleteSpeedDial(String speedDialId) {
    Long userId = authenticatedUser.get().getId();
    speedDialUsecase.deleteSpeedDial(userId, speedDialId);
    return Response.noContent().build();
  }

  private SpeedDial toGeneratedModel(SpeedDialPreset preset) {
    SpeedDial model = new SpeedDial();
    model.setId(preset.getSpeedDialId());
    model.setName(preset.getName());
    model.setSystemPrompt(preset.getSystemPrompt());
    model.setDescription(preset.getDescription());
    model.setIconEmoji(preset.getIconEmoji());
    model.setVoice(preset.getVoice());
    model.setVoiceAgentId(preset.getVoiceAgentId());
    model.setReasoningEffort(toGeneratedReasoningEffort(preset.getReasoningEffort()));
    model.setToolChoiceRequired(preset.isToolChoiceRequired());
    model.setEnabledTools(deserializeEnabledTools(preset.getEnabledTools()));
    if (preset.getCreatedAt() != null) {
      model.setCreatedAt(preset.getCreatedAt().atOffset(ZoneOffset.UTC));
    }
    return model;
  }

  private SpeedDialPreset toEntity(SpeedDial model) {
    SpeedDialPreset preset = new SpeedDialPreset();
    preset.setSpeedDialId(model.getId());
    preset.setName(model.getName());
    preset.setSystemPrompt(model.getSystemPrompt());
    preset.setDescription(model.getDescription());
    preset.setIconEmoji(model.getIconEmoji());
    preset.setVoice(model.getVoice());
    preset.setVoiceAgentId(model.getVoiceAgentId());
    preset.setReasoningEffort(toEntityReasoningEffort(model.getReasoningEffort()));
    preset.setToolChoiceRequired(Boolean.TRUE.equals(model.getToolChoiceRequired()));
    preset.setEnabledTools(serializeEnabledTools(model.getEnabledTools()));
    return preset;
  }

  private SpeedDial.ReasoningEffortEnum toGeneratedReasoningEffort(String reasoningEffort) {
    if (reasoningEffort == null || reasoningEffort.isBlank()) {
      return SpeedDial.ReasoningEffortEnum.OFF;
    }
    return SpeedDial.ReasoningEffortEnum.fromValue(reasoningEffort);
  }

  private String toEntityReasoningEffort(SpeedDial.ReasoningEffortEnum reasoningEffort) {
    if (reasoningEffort == null) {
      return SpeedDial.ReasoningEffortEnum.OFF.value();
    }
    return reasoningEffort.value();
  }

  private String serializeEnabledTools(Object enabledTools) {
    try {
      return objectMapper.writeValueAsString(normalizeEnabledTools(enabledTools));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to serialize speed dial enabled tools", e);
    }
  }

  private Map<String, Boolean> normalizeEnabledTools(Object enabledTools) {
    if (enabledTools == null) {
      return Map.of();
    }
    if (!(enabledTools instanceof Map<?, ?> rawEnabledTools)) {
      throw new IllegalArgumentException("Speed dial enabled tools must be a JSON object");
    }

    Map<String, Boolean> normalized = new LinkedHashMap<>();
    for (Entry<?, ?> entry : rawEnabledTools.entrySet()) {
      if (!(entry.getKey() instanceof String toolName)) {
        throw new IllegalArgumentException("Speed dial enabled tool names must be strings");
      }
      if (!(entry.getValue() instanceof Boolean enabled)) {
        throw new IllegalArgumentException("Speed dial enabled tool values must be booleans");
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
      throw new IllegalStateException("Failed to deserialize speed dial enabled tools", e);
    }
  }
}
