package app.vagina.server.realtime;

import app.vagina.server.entity.User;
import app.vagina.server.realtime.model.RealtimeAdapterModels;
import app.vagina.server.service.AuthService;
import io.quarkus.logging.Log;
import io.quarkus.websockets.next.WebSocketConnection;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

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
 *   <li>resolve the driver from {@code modelId} (the {@code RealtimeAdapter} mirror), keeping
 *       vendor choice server-side;
 *   <li>create a new session or rebind a retained one, returning it for the endpoint to attach.
 * </ol>
 */
@ApplicationScoped
public class VhrpSessionRegistry {

  private static final Duration RESUME_RETENTION = Duration.ofSeconds(15);

  /** Live and retained sessions keyed by stable sessionId. Concurrent: connections span threads. */
  private final ConcurrentHashMap<String, Entry> sessions = new ConcurrentHashMap<>();

  @Inject AuthService authService;
  @Inject RealtimeAdapterFactory adapterFactory;
  @Inject VhrpCborCodec codec;
  @Inject Vertx vertx;

  /** A session plus the bookkeeping the registry needs to police retention and ownership. */
  private static final class Entry {
    final VhrpSession session;

    /**
     * The id of the user who created this session. A resume must come from the same user, so a
     * leaked sessionId cannot be rebound under a different token.
     */
    final String ownerUserId;

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
    RealtimeAdapter adapter;
    try {
      adapter = adapterFactory.create(open.modelId());
    } catch (RealtimeAdapterFactory.UnknownModelException e) {
      return Uni.createFrom()
          .failure(new VhrpException.SessionUnknownModel("Unknown modelId: " + open.modelId()));
    }

    String sessionId = newSessionId();
    String threadId = newThreadId();
    VhrpSession session = new VhrpSession(sessionId, threadId, codec, adapter);
    sessions.put(sessionId, new Entry(session, user.getId().toString()));
    Log.debugf(
        "VHRP session %s created for user %s on model %s", sessionId, user.getId(), open.modelId());

    // Apply the session.open initial turn mode before connect(): while disconnected the adapter
    // only
    // records the mode, so the first session.update connect() sends already carries it — no second
    // update, no missed initial mode. Then connect() opens the downstream vendor connection and
    // applies voice/instructions; the session is returned once the adapter is ready to be driven.
    return adapter
        .setAudioTurnMode(RealtimeAdapterModels.AudioTurnMode.fromWire(open.audioTurnMode()))
        .chain(() -> adapter.connect(open.voice(), open.instructions()))
        .replaceWith(session);
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

  /** Terminally closes a session after an explicit client {@code session.end}. Idempotent. */
  public Uni<Void> closeExplicitly(VhrpSession session) {
    Entry entry = sessions.get(session.sessionId());
    if (entry == null || !sessions.remove(session.sessionId(), entry)) {
      return Uni.createFrom().voidItem();
    }
    Log.debugf("VHRP session %s explicitly ended", session.sessionId());
    return entry.session.dispose();
  }

  private void scheduleRetentionExpiry(String sessionId, Instant detachedAt) {
    vertx.setTimer(RESUME_RETENTION.toMillis(), ignored -> closeIfStillDetached(sessionId, detachedAt));
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
    entry.session.dispose().subscribe().with(ignored -> {}, t -> Log.warnf(t, "VHRP %s dispose", sessionId));
  }

  private boolean isExpired(Entry entry, Instant now) {
    return !entry.attached
        && entry.detachedAt != null
        && !entry.detachedAt.plus(RESUME_RETENTION).isAfter(now);
  }

  private Uni<VhrpSession> resumeNotAvailable(String sessionId) {
    return Uni.createFrom()
        .failure(new VhrpException.ResumeNotAvailable("No retained session for " + sessionId));
  }

  private String newSessionId() {
    return "s_" + java.util.UUID.randomUUID();
  }

  private String newThreadId() {
    return "t_" + java.util.UUID.randomUUID();
  }
}
