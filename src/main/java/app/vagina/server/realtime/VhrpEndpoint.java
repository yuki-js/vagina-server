package app.vagina.server.realtime;

import io.quarkus.logging.Log;
import io.quarkus.websockets.next.CloseReason;
import io.quarkus.websockets.next.OnBinaryMessage;
import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnError;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.UserData;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import io.smallrye.mutiny.Uni;
import io.vertx.core.buffer.Buffer;
import jakarta.inject.Inject;

/**
 * VHRP/1 exit portal: the hosted-realtime WebSocket endpoint.
 *
 * <p>This is the server-side mouth of the "warp" described in {@code
 * client/docs/hosted_realtime/03_quarkus_backend_spec.md}. It owns only transport-level concerns:
 * WebSocket lifecycle and binary CBOR frame I/O. It does not interpret VHRP message bodies, project
 * threads, talk to any model vendor, or implement resume; those live behind {@link
 * VhrpSessionRegistry}, {@link VhrpSession}, and the {@code RealtimeAdapter} mirror.
 *
 * <h2>Why this class is stateless</h2>
 *
 * <p>{@link WebSocket} endpoints are {@code Singleton} and shared across all connections (see its
 * Javadoc), so this class must not hold per-connection fields. Two distinct lifetimes are involved:
 *
 * <ul>
 *   <li>the <em>connection</em>: one physical WebSocket; its per-connection state lives in {@link
 *       WebSocketConnection#userData()} and dies with the socket;
 *   <li>the <em>session</em>: outlives any single connection because VHRP resume reconnects on a
 *       <em>new</em> socket carrying {@code session.open.body.resume}. Session authority therefore
 *       lives in the application-scoped {@link VhrpSessionRegistry}, not here and not in {@code
 *       userData}.
 * </ul>
 *
 * <p>Consequently {@link WebSocketConnection#userData()} only stores a <em>binding pointer</em> from
 * the current connection to its {@link VhrpSession}; the registry remains the source of truth.
 * {@link #onClose(WebSocketConnection)} detaches rather than destroys, which is precisely what makes
 * resume possible.
 *
 * <h2>One error funnel, context decides close</h2>
 *
 * <p>Inbound handlers never catch protocol errors. Decode and dispatch simply throw / fail with a
 * {@link VhrpException}; Quarkus routes both synchronous throws and failed {@link Uni}s to {@link
 * #onError}. That single funnel reports the {@code error} frame and decides close-vs-keep purely by
 * context: if no session is bound to this connection yet, the failure happened during {@code
 * session.open} bootstrap and the socket is closed (there is nothing to keep open); once a session
 * is bound, every failure is recoverable in-band and the socket is kept. No per-code disposition,
 * no close reason carried on the exception.
 *
 * <p>Wire contract (see {@code 02_vhrp_wire_protocol.md}): scheme {@code wss}, path {@code
 * /api/hosted-realtime/v1/connect}, subprotocol {@code vhrp.cbor.v1}, one binary frame per CBOR map,
 * first application message MUST be {@code session.open}.
 */
@WebSocket(path = "/api/hosted-realtime/v1/connect")
public class VhrpEndpoint {

  /** Subprotocol that the handshake must have negotiated for a valid VHRP/1 connection. */
  private static final String VHRP_SUBPROTOCOL = "vhrp.cbor.v1";

  /**
   * Binding pointer from the current connection to its session. Authority stays in {@link
   * VhrpSessionRegistry}; this is only the "which session is this socket currently serving" handle.
   * Its presence is also how the error funnel knows bootstrap has completed.
   */
  private static final UserData.TypedKey<VhrpSession> SESSION_KEY =
      new UserData.TypedKey<>("vhrp.session");

  /** Close sent when the negotiated subprotocol is not {@code vhrp.cbor.v1} (RFC-6455 1003-ish). */
  private static final CloseReason CLOSE_BAD_SUBPROTOCOL =
      new CloseReason(4406, "Subprotocol must be " + VHRP_SUBPROTOCOL);

  /** Close sent when bootstrap fails; the specific reason already went out as an {@code error} frame. */
  private static final CloseReason CLOSE_BOOTSTRAP_FAILED =
      new CloseReason(4400, "Session bootstrap failed");

  @Inject VhrpSessionRegistry sessionRegistry;
  @Inject VhrpCborCodec codec;

  @OnOpen
  public Uni<Void> onOpen(WebSocketConnection connection) {
    // Defensive: a correctly configured server rejects a bad subprotocol during the handshake
    // (quarkus.websockets-next.server.supported-subprotocols), but a client that connected with no
    // subprotocol would otherwise slip through to the first frame. Reject here so every surviving
    // connection is known to be vhrp.cbor.v1.
    if (!VHRP_SUBPROTOCOL.equals(connection.subprotocol())) {
      Log.warnf(
          "VHRP rejecting connection %s: subprotocol=%s",
          connection.id(), connection.subprotocol());
      return connection.close(CLOSE_BAD_SUBPROTOCOL);
    }
    // No session yet: per the wire contract the session is bootstrapped by the first application
    // frame (session.open). No token exists before that frame, so no authentication happens here.
    Log.infof("VHRP connection opened: %s", connection.id());
    return Uni.createFrom().voidItem();
  }

  /**
   * Receives one VHRP application message as a single binary CBOR frame and routes it.
   *
   * <p>Returns a {@link Uni}, keeping this on the event loop (see {@link OnBinaryMessage} execution
   * model). It does not catch anything: a decode failure throws {@link VhrpException} synchronously
   * and a dispatch failure surfaces as a failed {@link Uni}; both reach {@link #onError}.
   */
  @OnBinaryMessage
  public Uni<Void> onBinaryMessage(WebSocketConnection connection, Buffer frame) {
    VhrpMessage.C2S message = codec.decode(frame);
    VhrpSession bound = connection.userData().get(SESSION_KEY);
    if (bound == null) {
      return bootstrap(connection, message);
    }
    // Established session: hand the C2S message to the session, which owns the dispatch table
    // (session.* / turn.* / tools.set / ... -> RealtimeAdapter) and the connection binding.
    return bound.dispatch(message);
  }

  /**
   * Establishes (or resumes) a session from the first frame on a yet-unbound connection.
   *
   * <p>The endpoint only enforces the transport-level invariant "first frame is {@code
   * session.open}". Token verification, driver resolution, new-vs-resume, and the {@code
   * session.ready}/{@code session.resumed} reply all belong to {@link VhrpSessionRegistry} and
   * {@link VhrpSession}; resume in particular is deliberately invisible here. Any failure simply
   * propagates as a failed {@link Uni} to {@link #onError}, which closes because no session is bound.
   */
  private Uni<Void> bootstrap(WebSocketConnection connection, VhrpMessage message) {
    if (!(message instanceof VhrpMessage.SessionOpen open)) {
      return Uni.createFrom()
          .failure(
              new VhrpException.ProtocolBadMessage(
                  "First VHRP message must be session.open, got " + message.type()));
    }
    // Registry verifies open.token(), resolves the driver from open.modelId(), and either creates a
    // new session or rebinds a retained one (open.resume()). Binding the pointer marks bootstrap as
    // complete; from the next frame on, the error funnel treats failures as recoverable.
    return sessionRegistry
        .openOrResume(open, connection)
        .invoke(session -> connection.userData().put(SESSION_KEY, session))
        // attachConnection makes the session start writing to this socket: it replies session.ready
        // (new) or session.resumed (resume), correlated via this open's messageId, then the ongoing
        // thread.patch / assistant.audio.chunk / vad.state stream. The endpoint stays stateless; the
        // session owns that subscription so it survives reconnects.
        .chain(session -> session.attachConnection(connection, open.messageId()));
  }

  @OnClose
  public void onClose(WebSocketConnection connection) {
    VhrpSession bound = connection.userData().get(SESSION_KEY);
    if (bound == null) {
      Log.infof("VHRP connection closed before session bootstrap: %s", connection.id());
      return;
    }
    // Detach, do NOT destroy: the session must outlive this socket so a later session.open with
    // body.resume can rebind it within the retention window. The registry decides eventual disposal
    // (explicit close or retention expiry). This is the single place detachment happens, so it
    // covers graceful close and the close we trigger from onError alike.
    Log.infof("VHRP connection %s detaching from its session", connection.id());
    sessionRegistry.onConnectionDetached(bound, connection);
  }

  /**
   * The single error funnel. Reports the failure as an {@code error} frame, then decides close
   * purely by context: a failure before a session is bound happened during bootstrap and closes the
   * socket; once a session is bound, the failure is recoverable and the socket is kept. Session
   * detachment on close is left entirely to {@link #onClose}, which fires for the close we trigger
   * here too — so this method never touches the registry.
   */
  @OnError
  public Uni<Void> onError(WebSocketConnection connection, Throwable error) {
    boolean bootstrapped = connection.userData().get(SESSION_KEY) != null;

    // Normalize to a VhrpException so reporting is uniform: a non-protocol fault (bug, unexpected
    // runtime error) is wrapped as a generic bad-message rather than special-cased here.
    VhrpException ve;
    if (error instanceof VhrpException already) {
      ve = already;
    } else {
      ve = new VhrpException.ProtocolBadMessage("Unexpected server error", error);
      Log.errorf(error, "VHRP unexpected error on connection %s", connection.id());
    }

    boolean recoverable = bootstrapped;
    Uni<Void> report =
        connection.sendBinary(
            codec.encode(VhrpMessage.Error.of(ve.wireCode(), ve.getMessage(), recoverable)));
    if (recoverable) {
      Log.debugf("VHRP recoverable error on %s: %s", connection.id(), ve.wireCode());
      return report;
    }
    Log.warnf("VHRP bootstrap error on %s: %s", connection.id(), ve.wireCode());
    return report.call(() -> connection.close(CLOSE_BOOTSTRAP_FAILED));
  }
}
