package app.vagina.server.usecase;

import app.vagina.server.domain.error.ConflictException;
import app.vagina.server.domain.error.NotFoundException;
import app.vagina.server.domain.error.ValidationException;
import app.vagina.server.entity.SpeedDialPreset;
import app.vagina.server.service.SpeedDialService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class SpeedDialUsecase {
  @Inject SpeedDialService speedDialService;

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
  public SpeedDialPreset saveSpeedDial(Long userId, String pathSpeedDialId, SpeedDialPreset candidate) {
    if (!pathSpeedDialId.equals(candidate.getSpeedDialId())) {
      throw new ValidationException("Path/body speed dial id mismatch");
    }

    Optional<SpeedDialPreset> existing;
    if (SpeedDialService.DEFAULT_SPEED_DIAL_ID.equals(pathSpeedDialId)) {
      speedDialService.ensureDefaultExists(userId);
      existing = speedDialService.findByUserIdAndSpeedDialId(userId, pathSpeedDialId);
    } else {
      existing = speedDialService.findByUserIdAndSpeedDialId(userId, pathSpeedDialId);
    }

    if (SpeedDialService.DEFAULT_SPEED_DIAL_ID.equals(pathSpeedDialId)
        && existing.isPresent()
        && !SpeedDialService.DEFAULT_SPEED_DIAL_NAME.equals(candidate.getName())) {
      throw new ConflictException("Default speed dial cannot be renamed");
    }

    return speedDialService.save(userId, candidate);
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
