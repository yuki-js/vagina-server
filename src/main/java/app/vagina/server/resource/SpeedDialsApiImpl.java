package app.vagina.server.resource;

import app.vagina.server.entity.SpeedDialPreset;
import app.vagina.server.generated.api.SpeedDialsApi;
import app.vagina.server.generated.model.SpeedDial;
import app.vagina.server.generated.model.SpeedDialCreateRequest;
import app.vagina.server.generated.model.SpeedDialUpdateRequest;
import app.vagina.server.support.Authenticated;
import app.vagina.server.support.AuthenticatedUser;
import app.vagina.server.support.EnabledToolsJson;
import app.vagina.server.usecase.SpeedDialUsecase;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import java.time.ZoneOffset;
import java.util.List;

@ApplicationScoped
@Path("/speed-dials")
@Authenticated
public class SpeedDialsApiImpl implements SpeedDialsApi {
  private static final String ENABLED_TOOL_LABEL = "Speed dial enabled tool";
  private static final String ENABLED_TOOLS_LABEL = "speed dial enabled tools";

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
  public Response createSpeedDial(SpeedDialCreateRequest speedDialCreateRequest) {
    Long userId = authenticatedUser.get().getId();
    SpeedDialPreset created =
        speedDialUsecase.createSpeedDial(userId, toCreateCommand(speedDialCreateRequest));
    return Response.status(Response.Status.CREATED).entity(toGeneratedModel(created)).build();
  }

  @Override
  public Response getSpeedDial(String speedDialId) {
    Long userId = authenticatedUser.get().getId();
    SpeedDialPreset speedDialPreset = speedDialUsecase.getSpeedDial(userId, speedDialId);
    return Response.ok(toGeneratedModel(speedDialPreset)).build();
  }

  @Override
  public Response updateSpeedDial(
      String speedDialId, SpeedDialUpdateRequest speedDialUpdateRequest) {
    Long userId = authenticatedUser.get().getId();
    SpeedDialPreset updated =
        speedDialUsecase.updateSpeedDial(
            userId, speedDialId, toUpdateCommand(speedDialUpdateRequest));
    return Response.ok(toGeneratedModel(updated)).build();
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
    model.setEnabledTools(
        EnabledToolsJson.deserialize(objectMapper, preset.getEnabledTools(), ENABLED_TOOLS_LABEL));
    if (preset.getCreatedAt() != null) {
      model.setCreatedAt(preset.getCreatedAt().atOffset(ZoneOffset.UTC));
    }
    return model;
  }

  private SpeedDialUsecase.CreateCommand toCreateCommand(SpeedDialCreateRequest model) {
    return new SpeedDialUsecase.CreateCommand(
        model.getName(),
        model.getSystemPrompt(),
        model.getDescription(),
        model.getIconEmoji(),
        model.getVoice(),
        model.getVoiceAgentId(),
        toEntityReasoningEffort(model.getReasoningEffort()),
        Boolean.TRUE.equals(model.getToolChoiceRequired()),
        serializeEnabledTools(model.getEnabledTools()));
  }

  private SpeedDialUsecase.UpdateCommand toUpdateCommand(SpeedDialUpdateRequest model) {
    return new SpeedDialUsecase.UpdateCommand(
        model.getName(),
        model.getSystemPrompt(),
        model.getDescription(),
        model.getIconEmoji(),
        model.getVoice(),
        model.getVoiceAgentId(),
        toEntityReasoningEffort(model.getReasoningEffort()),
        Boolean.TRUE.equals(model.getToolChoiceRequired()),
        serializeEnabledTools(model.getEnabledTools()));
  }

  private String serializeEnabledTools(Object enabledTools) {
    return EnabledToolsJson.serialize(
        objectMapper, enabledTools, ENABLED_TOOL_LABEL, ENABLED_TOOLS_LABEL);
  }

  private SpeedDial.ReasoningEffortEnum toGeneratedReasoningEffort(String reasoningEffort) {
    if (reasoningEffort == null || reasoningEffort.isBlank()) {
      return SpeedDial.ReasoningEffortEnum.OFF;
    }
    return SpeedDial.ReasoningEffortEnum.fromValue(reasoningEffort);
  }

  private String toEntityReasoningEffort(
      SpeedDialCreateRequest.ReasoningEffortEnum reasoningEffort) {
    return reasoningEffort == null ? defaultReasoningEffort() : reasoningEffort.value();
  }

  private String toEntityReasoningEffort(
      SpeedDialUpdateRequest.ReasoningEffortEnum reasoningEffort) {
    return reasoningEffort == null ? defaultReasoningEffort() : reasoningEffort.value();
  }

  private String defaultReasoningEffort() {
    return SpeedDialCreateRequest.ReasoningEffortEnum.OFF.value();
  }
}
