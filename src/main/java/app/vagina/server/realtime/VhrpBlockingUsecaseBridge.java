package app.vagina.server.realtime;

import app.vagina.server.entity.SpeedDialPreset;
import app.vagina.server.usecase.CallSessionUsecase;
import app.vagina.server.usecase.SpeedDialUsecase;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Boundary between VHRP's non-blocking realtime pipeline and traditional blocking usecases.
 *
 * <p>VHRP WebSocket handlers return {@link Uni} and are therefore executed as non-blocking by
 * Quarkus WebSockets Next unless explicitly moved. The existing application usecases remain
 * traditional blocking code and may enter transactional services. This bridge keeps those blocking
 * calls out of the event-loop without making usecase/service layers depend on realtime or reactive
 * transport details.
 */
@ApplicationScoped
public class VhrpBlockingUsecaseBridge {

  @Inject SpeedDialUsecase speedDialUsecase;
  @Inject CallSessionUsecase callSessionUsecase;

  public Uni<SpeedDialPreset> getSpeedDial(Long userId, String speedDialId) {
    return Uni.createFrom()
        .item(() -> speedDialUsecase.getSpeedDial(userId, speedDialId))
        .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
  }

  public Uni<Boolean> saveTerminalSession(CallSessionUsecase.TerminalSessionSaveCommand command) {
    return Uni.createFrom()
        .item(() -> callSessionUsecase.saveTerminalSession(command))
        .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
  }
}
