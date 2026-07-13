package app.vagina.server.resource;

import app.vagina.server.entity.SpeedDialPreset;
import app.vagina.server.generated.api.SpeedDialsApi;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    List<Map<String, Object>> speedDials =
        speedDialUsecase.listSpeedDials(userId).stream().map(this::toResponseBody).toList();
    return Response.ok(speedDials).build();
  }

  @Override
  public Response createSpeedDial(SpeedDialCreateRequest speedDialCreateRequest) {
    Long userId = authenticatedUser.get().getId();
    SpeedDialPreset created =
        speedDialUsecase.createSpeedDial(userId, toCreateCommand(speedDialCreateRequest));
    return Response.status(Response.Status.CREATED).entity(toResponseBody(created)).build();
  }

  @Override
  public Response getSpeedDial(String speedDialId) {
    Long userId = authenticatedUser.get().getId();
    SpeedDialPreset speedDialPreset = speedDialUsecase.getSpeedDial(userId, speedDialId);
    return Response.ok(toResponseBody(speedDialPreset)).build();
  }

  @Override
  public Response updateSpeedDial(
      String speedDialId, SpeedDialUpdateRequest speedDialUpdateRequest) {
    Long userId = authenticatedUser.get().getId();
    SpeedDialPreset updated =
        speedDialUsecase.updateSpeedDial(
            userId, speedDialId, toUpdateCommand(speedDialUpdateRequest));
    return Response.ok(toResponseBody(updated)).build();
  }

  @Override
  public Response deleteSpeedDial(String speedDialId) {
    Long userId = authenticatedUser.get().getId();
    speedDialUsecase.deleteSpeedDial(userId, speedDialId);
    return Response.noContent().build();
  }

  private Map<String, Object> toResponseBody(SpeedDialPreset preset) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("id", preset.getSpeedDialId());
    body.put("name", preset.getName());
    body.put("systemPrompt", preset.getSystemPrompt());
    body.put("description", preset.getDescription());
    body.put("iconEmoji", preset.getIconEmoji());
    body.put("voice", preset.getVoice());
    body.put("voiceAgentId", preset.getVoiceAgentId());
    body.put("toolChoiceRequired", preset.isToolChoiceRequired());
    body.put(
        "enabledTools",
        EnabledToolsJson.deserialize(objectMapper, preset.getEnabledTools(), ENABLED_TOOLS_LABEL));
    body.put(
        "createdAt",
        preset.getCreatedAt() == null ? null : preset.getCreatedAt().atOffset(ZoneOffset.UTC));
    return body;
  }

  private SpeedDialUsecase.CreateCommand toCreateCommand(SpeedDialCreateRequest model) {
    return new SpeedDialUsecase.CreateCommand(
        model.getName(),
        model.getSystemPrompt(),
        model.getDescription(),
        model.getIconEmoji(),
        model.getVoice(),
        model.getVoiceAgentId(),
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
        Boolean.TRUE.equals(model.getToolChoiceRequired()),
        serializeEnabledTools(model.getEnabledTools()));
  }

  private String serializeEnabledTools(Object enabledTools) {
    return EnabledToolsJson.serialize(
        objectMapper, enabledTools, ENABLED_TOOL_LABEL, ENABLED_TOOLS_LABEL);
  }
}
