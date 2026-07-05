package app.vagina.server.usecase;

import app.vagina.server.domain.error.ConflictException;
import app.vagina.server.domain.error.NotFoundException;
import app.vagina.server.entity.SpeedDialPreset;
import app.vagina.server.service.SpeedDialService;
import app.vagina.server.service.VoiceAgentService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.List;

@ApplicationScoped
public class SpeedDialUsecase {
  @Inject SpeedDialService speedDialService;
  @Inject VoiceAgentService voiceAgentService;

  public List<SpeedDialPreset> listSpeedDials(Long userId) {
    speedDialService.ensureDefaultExists(userId);
    return speedDialService.findByUserId(userId);
  }

  public SpeedDialPreset getSpeedDial(Long userId, String speedDialId) {
    if (SpeedDialService.DEFAULT_SPEED_DIAL_ID.equals(speedDialId)) {
      speedDialService.ensureDefaultExists(userId);
    }

    return speedDialService
        .findByUserIdAndSpeedDialId(userId, speedDialId)
        .orElseThrow(() -> new NotFoundException("Speed dial not found"));
  }

  @Transactional
  public SpeedDialPreset createSpeedDial(Long userId, SpeedDialPreset candidate) {
    voiceAgentService.validateKnownModelId(candidate.getVoiceAgentId());
    return speedDialService.create(userId, candidate);
  }

  @Transactional
  public SpeedDialPreset updateSpeedDial(
      Long userId, String speedDialId, SpeedDialPreset candidate) {
    voiceAgentService.validateKnownModelId(candidate.getVoiceAgentId());

    if (SpeedDialService.DEFAULT_SPEED_DIAL_ID.equals(speedDialId)) {
      speedDialService.ensureDefaultExists(userId);
      if (!SpeedDialService.DEFAULT_SPEED_DIAL_NAME.equals(candidate.getName())) {
        throw new ConflictException("Default speed dial cannot be renamed");
      }
    }

    return speedDialService
        .update(userId, speedDialId, candidate)
        .orElseThrow(() -> new NotFoundException("Speed dial not found"));
  }

  @Transactional
  public void deleteSpeedDial(Long userId, String speedDialId) {
    if (SpeedDialService.DEFAULT_SPEED_DIAL_ID.equals(speedDialId)) {
      throw new ConflictException("Default speed dial cannot be deleted");
    }

    boolean deleted = speedDialService.delete(userId, speedDialId);
    if (!deleted) {
      throw new NotFoundException("Speed dial not found");
    }
  }
}
