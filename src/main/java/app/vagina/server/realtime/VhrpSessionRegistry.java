package app.vagina.server.realtime;

import app.vagina.server.domain.error.ConflictException;
import app.vagina.server.domain.error.NotFoundException;
import app.vagina.server.domain.error.ValidationException;
import app.vagina.server.entity.SpeedDialPreset;
import app.vagina.server.entity.User;
import app.vagina.server.realtime.model.RealtimeAdapterModels;
import app.vagina.server.service.AuthService;
import app.vagina.server.service.VoiceAgentService;
import app.vagina.server.support.Constants;
import app.vagina.server.support.EnabledToolsJson;
import app.vagina.server.support.EnabledToolsJson.ParseResult;
import app.vagina.server.usecase.CallSessionUsecase;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.logging.Log;
import io.quarkus.websockets.next.WebSocketConnection;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Application-scoped authority over VHRP/1 sessions: the only place resume is realized.
 *
 * <p>{@link VhrpSession} state must outlive any single socket because VHRP resume reconnects on a
 * <em>new</em> connection carrying {@code session.open.body.resume}. This registry therefore keys
 * sessions by their stable {@code sessionId}, retains a detached session for a bounded window so it
 * can be rebound, and disposes it on explicit close or retention expiry. The stateless {@link
 * VhrpEndpoint} delegates here and never sees this lifetime directly.
 *
 * <p>Bootstrap responsibilities for a first frame ({@link #openOrResume}):
 *
 * <ol>
 *   <li>authenticate {@code session.open.body.token} (reuses {@link AuthService}, whose String
 *       overload is documented for exactly this in-band VHRP token);
 *   <li>resolve the driver from the user-owned Speed Dial's voice-agent id (the {@code
 *       RealtimeAdapter} mirror), keeping vendor choice server-side;
 *   <li>create a new session or rebind a retained one, returning it for the endpoint to attach.
 * </ol>
 */
@ApplicationScoped
public class VhrpSessionRegistry {

  /** Live and retained sessions keyed by stable sessionId. Concurrent: connections span threads. */
  private final ConcurrentHashMap<String, Entry> sessions = new ConcurrentHashMap<>();

  @Inject AuthService authService;
  @Inject RealtimeAdapterFactory adapterFactory;
  @Inject VhrpCborCodec codec;
  @Inject Vertx vertx;
  @Inject VhrpBlockingUsecaseBridge blockingUsecaseBridge;
  @Inject RealtimeModelsConfig realtimeConfig;
  @Inject VoiceAgentService voiceAgentService;
  @Inject ObjectMapper objectMapper;

  /** A session plus the bookkeeping the registry needs to police retention and ownership. */
  private static final class Entry {
    final VhrpSession session;

    /**
     * The id of the user who created this session. A resume must come from the same user, so a
     * leaked sessionId cannot be rebound under a different token.
     */
    final String ownerUserId;

    final AtomicBoolean terminalHistorySaved = new AtomicBoolean(false);
    volatile boolean attached;
    volatile Instant detachedAt;

    Entry(VhrpSession session, String ownerUserId) {
      this.session = session;
      this.ownerUserId = ownerUserId;
      this.attached = true;
    }
  }

  /**
   * Authenticates, resolves the driver, and returns the session to serve {@code connection} — newly
   * created, or rebound when the open carries a valid {@code resume}.
   *
   * <p>The returned {@link Uni} fails with a {@link VhrpException} carrying the right code
   * (auth/unknown-model/resume); the endpoint reports it and, because no session is bound yet,
   * closes. The endpoint stamps the binding pointer and then calls {@link
   * VhrpSession#attachConnection}.
   */
  public Uni<VhrpSession> openOrResume(
      VhrpMessage.SessionOpen open, WebSocketConnection connection) {
    Optional<User> user = authService.authenticateFromJwt(open.token());
    if (user.isEmpty()) {
      return Uni.createFrom()
          .failure(new VhrpException.AuthInvalidJwt("Invalid or missing session.open token"));
    }

    if (open.resume() != null) {
      return resume(open, user.get());
    }
    return create(open, user.get());
  }

  private Uni<VhrpSession> create(VhrpMessage.SessionOpen open, User user) {
    if (open.speedDialId() == null || open.speedDialId().isBlank()) {
      return Uni.createFrom()
          .failure(new VhrpException.ProtocolBadMessage("session.open speedDialId is required"));
    }

    return blockingUsecaseBridge
        .getSpeedDial(user.getId(), open.speedDialId())
        .onFailure(NotFoundException.class)
        .transform(ignored -> new VhrpException.ProtocolBadMessage("Speed dial not found"))
        .chain(speedDial -> createWithSpeedDial(open, user, speedDial));
  }

  private Uni<VhrpSession> createWithSpeedDial(
      VhrpMessage.SessionOpen open, User user, SpeedDialPreset speedDial) {
    SpeedDialPreset.VoiceSessionConfig runtimeConfig = speedDial.toVoiceSessionConfig();
    RealtimeAdapter adapter;
    String voiceAgentId = runtimeConfig.voiceAgentId();
    voiceAgentService.validateEntitledModelId(user.getId(), voiceAgentId);
    try {
      adapter = adapterFactory.create(voiceAgentId);
    } catch (RealtimeAdapterFactory.UnknownModelException e) {
      return Uni.createFrom()
          .failure(new VhrpException.SessionUnknownModel("Unknown voiceAgentId: " + voiceAgentId));
    }

    String sessionId = newSessionId();
    String threadId = newThreadId();
    LocalDateTime startedAt = LocalDateTime.now();
    ParseResult toolOverrides = parseToolOverrides(runtimeConfig.enabledTools());
    VhrpSession session =
        new VhrpSession(
            sessionId,
            threadId,
            codec,
            adapter,
            user.getId(),
            startedAt,
            runtimeConfig.speedDialId(),
            voiceAgentId,
            toolOverrides);
    return adapter
        .setAudioTurnMode(RealtimeAdapterModels.AudioTurnMode.fromWire(open.audioTurnMode()))
        .chain(() -> applyServerOwnedExtensions(adapter, runtimeConfig))
        .chain(() -> adapter.connect(runtimeConfig.voice(), runtimeConfig.systemPrompt()))
        .invoke(
            () -> {
              sessions.put(sessionId, new Entry(session, user.getId().toString()));
              Log.debugf(
                  "VHRP session %s created for user %s on speed dial %s / voice agent %s",
                  sessionId, user.getId(), runtimeConfig.speedDialId(), voiceAgentId);
            })
        .replaceWith(session)
        .onFailure()
        .call(
            failure ->
                session
                    .dispose()
                    .onFailure()
                    .invoke(
                        disposeFailure ->
                            Log.warnf(
                                disposeFailure,
                                "VHRP %s cleanup failed after bootstrap failure",
                                sessionId))
                    .onFailure()
                    .recoverWithNull());
  }

  private Uni<VhrpSession> resume(VhrpMessage.SessionOpen open, User user) {
    VhrpMessage.ResumeRequest request = open.resume();
    Entry entry = sessions.get(request.sessionId());
    if (entry == null) {
      // Either never existed or already evicted past the retention window.
      return resumeNotAvailable(request.sessionId());
    }
    if (isExpired(entry, Instant.now())) {
      closeIfStillDetached(request.sessionId(), entry.detachedAt);
      return resumeNotAvailable(request.sessionId());
    }
    // Ownership check: a resume must come from the same user that created the session, so a leaked
    // sessionId cannot be rebound under a different token. Report not-available rather than a
    // distinct auth error so a probe cannot tell "wrong owner" from "no such session".
    if (!Objects.equals(entry.ownerUserId, user.getId().toString())) {
      Log.warnf(
          "VHRP resume of %s rejected: user %s is not the owner",
          request.sessionId(), user.getId());
      return resumeNotAvailable(request.sessionId());
    }
    entry.attached = true;
    entry.detachedAt = null;
    Log.debugf("VHRP session %s resumed by user %s", request.sessionId(), user.getId());
    return Uni.createFrom().item(entry.session);
  }

  /**
   * Marks the session detached. The session is kept (not disposed) so a later {@code
   * session.open.resume} can rebind it until the retention timer performs terminal close.
   */
  public void onConnectionDetached(VhrpSession session, WebSocketConnection connection) {
    session.detach(connection);
    Entry entry = sessions.get(session.sessionId());
    if (entry != null) {
      Instant detachedAt = Instant.now();
      entry.attached = false;
      entry.detachedAt = detachedAt;
      scheduleRetentionExpiry(session.sessionId(), detachedAt);
    }
  }

  public VhrpSession getOwnedActiveSession(String sessionId, Long userId) {
    if (sessionId == null || sessionId.isBlank()) {
      throw new ConflictException("Voice session is not active");
    }
    Entry entry = sessions.get(sessionId);
    if (entry == null || isExpired(entry, Instant.now()) || !entry.attached) {
      throw new ConflictException("Voice session is not active");
    }
    if (!Objects.equals(entry.ownerUserId, userId == null ? null : userId.toString())) {
      throw new ConflictException("Voice session is not active");
    }
    return entry.session;
  }

  /** Terminally closes a session after an explicit client {@code session.end}. Idempotent. */
  public Uni<Void> closeExplicitly(VhrpSession session) {
    Entry entry = sessions.get(session.sessionId());
    if (entry == null || !sessions.remove(session.sessionId(), entry)) {
      return Uni.createFrom().voidItem();
    }
    Log.debugf("VHRP session %s explicitly ended", session.sessionId());
    return terminalClose(entry, LocalDateTime.now());
  }

  private void scheduleRetentionExpiry(String sessionId, Instant detachedAt) {
    vertx.setTimer(
        resumeRetention().toMillis(), ignored -> closeIfStillDetached(sessionId, detachedAt));
  }

  private void closeIfStillDetached(String sessionId, Instant detachedAt) {
    Entry entry = sessions.get(sessionId);
    if (entry == null || entry.attached || !Objects.equals(entry.detachedAt, detachedAt)) {
      return;
    }
    if (!isExpired(entry, Instant.now()) || !sessions.remove(sessionId, entry)) {
      return;
    }
    Log.debugf("VHRP session %s expired after detach retention", sessionId);
    terminalClose(entry, LocalDateTime.now())
        .subscribe()
        .with(ignored -> {}, t -> Log.warnf(t, "VHRP %s terminal close", sessionId));
  }

  private Uni<Void> applyServerOwnedExtensions(
      RealtimeAdapter adapter, SpeedDialPreset.VoiceSessionConfig speedDial) {
    return adapter
        .applyProviderExtension(
            "session.tool_choice_required", Map.of("required", speedDial.toolChoiceRequired()))
        .replaceWithVoid();
  }

  private Uni<Void> terminalClose(Entry entry, LocalDateTime endedAt) {
    return saveTerminalHistoryBestEffort(entry, endedAt).chain(() -> entry.session.dispose());
  }

  private Uni<Boolean> saveTerminalHistoryBestEffort(Entry entry, LocalDateTime endedAt) {
    if (!entry.terminalHistorySaved.compareAndSet(false, true)) {
      return Uni.createFrom().item(false);
    }
    VhrpSession session = entry.session;
    CallSessionUsecase.TerminalSessionSaveCommand command =
        new CallSessionUsecase.TerminalSessionSaveCommand(
            session.userId(),
            session.sessionId(),
            session.threadId(),
            session.speedDialId(),
            session.voiceAgentId(),
            session.startedAt(),
            endedAt,
            session.buildSessionHistoryThread());
    return blockingUsecaseBridge
        .saveTerminalSession(command)
        .onFailure(ValidationException.class)
        .invoke(e -> Log.debugf(e, "VHRP %s terminal history not saved", entry.session.sessionId()))
        .onFailure(ValidationException.class)
        .recoverWithItem(false)
        .onFailure()
        .invoke(
            e -> Log.warnf(e, "VHRP %s terminal history save failed", entry.session.sessionId()))
        .onFailure()
        .recoverWithItem(false);
  }

  private boolean isExpired(Entry entry, Instant now) {
    return !entry.attached
        && entry.detachedAt != null
        && !entry.detachedAt.plus(resumeRetention()).isAfter(now);
  }

  private Duration resumeRetention() {
    return realtimeConfig != null
        ? realtimeConfig.resumeRetention()
        : Constants.VHRP_RESUME_RETENTION;
  }

  private Uni<VhrpSession> resumeNotAvailable(String sessionId) {
    return Uni.createFrom()
        .failure(new VhrpException.ResumeNotAvailable("No retained session for " + sessionId));
  }

  private String newSessionId() {
    return "s_" + UUID.randomUUID();
  }

  private String newThreadId() {
    return "t_" + UUID.randomUUID();
  }

  private ParseResult parseToolOverrides(String enabledToolsJson) {
    ParseResult enabledTools = EnabledToolsJson.parse(objectMapper, enabledToolsJson);
    if (!enabledTools.valid()) {
      Log.warnf(
          enabledTools.error(),
          "Failed to parse Speed Dial enabled_tools JSON, denying all Voice Agent tools");
    }
    return enabledTools;
  }
}
