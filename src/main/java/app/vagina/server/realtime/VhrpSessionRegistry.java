package app.vagina.server.realtime;

import app.vagina.server.entity.User;
import app.vagina.server.service.AuthService;
import io.quarkus.logging.Log;
import io.quarkus.websockets.next.WebSocketConnection;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
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
 *   <li>resolve the driver from {@code modelId} (the {@code RealtimeAdapter} mirror), keeping vendor
 *       choice server-side;
 *   <li>create a new session or rebind a retained one, returning it for the endpoint to attach.
 * </ol>
 */
@ApplicationScoped
public class VhrpSessionRegistry {

  /** How long a detached session is retained for resume before disposal. */
  private static final Duration RETENTION_WINDOW = Duration.ofMinutes(2);

  /** Replay-log capacity handed to each new session; bounds how far a resume can catch up. */
  private static final int REPLAY_LOG_CAPACITY = 512;

  /** Live and retained sessions keyed by stable sessionId. Concurrent: connections span threads. */
  private final ConcurrentHashMap<String, Entry> sessions = new ConcurrentHashMap<>();

  @Inject AuthService authService;
  @Inject RealtimeAdapterFactory adapterFactory;
  @Inject VhrpCborCodec codec;

  /** A session plus the bookkeeping the registry needs to police retention. */
  private static final class Entry {
    final VhrpSession session;
    volatile boolean attached;
    volatile Instant detachedAt;

    Entry(VhrpSession session) {
      this.session = session;
      this.attached = true;
    }
  }

  /**
   * Authenticates, resolves the driver, and returns the session to serve {@code connection} — newly
   * created, or rebound when the open carries a valid {@code resume}.
   *
   * <p>The returned {@link Uni} fails with a {@link VhrpException} carrying the right code
   * (auth/unknown-model/resume); the endpoint reports it and, because no session is bound yet,
   * closes. The
   * endpoint stamps the binding pointer and then calls {@link VhrpSession#attachConnection}.
   */
  public Uni<VhrpSession> openOrResume(VhrpMessage.SessionOpen open, WebSocketConnection connection) {
    Optional<User> user = authService.authenticateFromJwt(open.token());
    if (user.isEmpty()) {
      return Uni.createFrom()
          .failure(
              new VhrpException.AuthInvalidJwt("Invalid or missing session.open token"));
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
          .failure(
              new VhrpException.SessionUnknownModel("Unknown modelId: " + open.modelId()));
    }

    String sessionId = newSessionId();
    String threadId = newThreadId();
    VhrpSession session =
        new VhrpSession(sessionId, threadId, codec, adapter, REPLAY_LOG_CAPACITY);
    sessions.put(sessionId, new Entry(session));
    Log.infof(
        "VHRP session %s created for user %s on model %s",
        sessionId, user.getId(), open.modelId());

    // connect() opens the downstream vendor connection and applies voice/instructions from the
    // open; the session is returned only once the adapter is ready to be driven.
    return adapter
        .connect(open.voice(), open.instructions())
        .replaceWith(session);
  }

  private Uni<VhrpSession> resume(VhrpMessage.SessionOpen open, User user) {
    VhrpMessage.ResumeRequest request = open.resume();
    Entry entry = sessions.get(request.sessionId());
    if (entry == null) {
      // Either never existed or already evicted past the retention window.
      return Uni.createFrom()
          .failure(
              new VhrpException.ResumeNotAvailable(
                  "No retained session for " + request.sessionId()));
    }
    // TODO: verify the resuming user owns this session before rebinding, so a leaked sessionId
    //   cannot be hijacked under a different token.
    entry.attached = true;
    entry.detachedAt = null;
    Log.infof("VHRP session %s resumed by user %s", request.sessionId(), user.getId());
    return Uni.createFrom().item(entry.session);
  }

  /**
   * Marks the session detached and starts its retention timer. The session is kept (not disposed) so
   * a later {@code session.open.resume} can rebind it; disposal happens on expiry or explicit close.
   */
  public void onConnectionDetached(VhrpSession session, WebSocketConnection connection) {
    session.detach(connection);
    Entry entry = sessions.get(session.sessionId());
    if (entry != null) {
      entry.attached = false;
      entry.detachedAt = Instant.now();
    }
    // TODO: schedule a sweep (or run a periodic one) that disposes entries whose detachedAt is older
    //   than RETENTION_WINDOW, calling the adapter's dispose to release the downstream connection.ｇｔ
  }

  private String newSessionId() {
    return "s_" + java.util.UUID.randomUUID();
  }

  private String newThreadId() {
    return "t_" + java.util.UUID.randomUUID();
  }
}
