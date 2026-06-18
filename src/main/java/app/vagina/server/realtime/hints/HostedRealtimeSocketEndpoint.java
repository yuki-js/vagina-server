
















/**
 * VHRP/1 WebSocket endpoint (resource layer).
 *
 * <p>Transport receptionist for the hosted realtime voice protocol at {@code
 * /api/hosted-realtime/v1/connect}, negotiating the binary {@code vhrp.cbor.v1} subprotocol. Like
 * {@code VfsApiImpl} for JSON-RPC, this resource only does WebSocket reception and dispatch; it
 * holds no business logic and — importantly — no domain state.
 *
 * <p>It is {@link SessionScoped}, which in Quarkus WebSockets Next means one instance per
 * connection. That, together with depending on the session-scoped usecase and channel, binds the
 * whole graph to a single connection. The endpoint deliberately does <b>not</b> hold or even name
 * the conversation thread: the canonical {@code RealtimeThread} is domain state owned by the
 * {@link HostedRealtimeUsecase} (itself {@code @SessionScoped}). Presentation never sees it.
 *
 * <p>Layering:
 *
 * <ul>
 *   <li><b>support</b> owns all non-domain wire concerns: CBOR marshalling ({@link VhrpCodec}), the
 *       wire envelope ({@link VhrpEnvelope}) and kind ({@link VhrpMessageType}), wire→domain
 *       decoding ({@link VhrpInboundDecoder}), and the per-connection framing/output channel ({@link
 *       VhrpClientChannel}).
 *   <li><b>usecase</b> ({@link HostedRealtimeUsecase}) is invoked purely with domain values produced
 *       by the decoder; the endpoint passes no map, envelope, {@code streamSeq}, or transport type
 *       into it, and passes no thread either.
 * </ul>
 *
 * <p>Protocol-state messages ({@code thread.sync.request} and the {@code resume} block of {@code
 * session.open}) are not domain concerns and go to the support channel directly, never to the
 * usecase. Malformed frames and unsupported types are likewise reported through the channel.
 */
@WebSocket(path = "/api/hosted-realtime/v1/connect")
@SessionScoped
public class HostedRealtimeSocketEndpoint {

  @Inject VhrpCodec codec;
  @Inject VhrpInboundDecoder decoder;
  @Inject HostedRealtimeUsecase usecase;

  /**
   * Per-connection output channel and framing state (support, non-domain, session-scoped). The
   * usecase emits through it as its domain-event port; the endpoint additionally calls its
   * protocol-only methods (resume/resync/error reporting) directly.
   */
  @Inject VhrpClientChannel channel;

  /**
   * Handshake gate (session-scoped state). A connection starts unauthenticated: the only message it
   * may send is {@code session.open}. The first {@code session.open} that completes without throwing
   * flips this to {@code true}, after which {@code session.open} is no longer allowed and every other
   * message is. Any violation closes the connection.
   */
  private boolean authenticated = false;

  /** Connection accepted. No upgrade-time authentication; the usecase prepares its domain state. */
  @OnOpen
  public void onOpen() {
    usecase.registerClient();
  }

  /**
   * One inbound binary frame is one VHRP/1 CBOR envelope. Decode it (support), enforce the handshake
   * state machine, then either translate to domain values and invoke a domain intent on the usecase
   * or — for protocol-state messages — let the support channel handle it.
   *
   * <p>Handshake rules, enforced here in the presentation layer:
   *
   * <ul>
   *   <li>before authentication, only {@code session.open} is accepted; anything else disconnects;
   *   <li>{@code session.open} delegates to {@link HostedRealtimeUsecase#openSession}, which throws
   *       on authentication failure — success (no throw) marks the connection authenticated, a throw
   *       disconnects it;
   *   <li>after authentication, {@code session.open} is rejected (disconnect) and all other messages
   *       are allowed.
   * </ul>
   */
  @OnBinaryMessage
  public void onBinaryMessage(byte[] frame) {
    final VhrpEnvelope envelope = decodeOrReport(frame);
    if (envelope == null) {
      return;
    }

    // Capture the inbound message id so the support channel can correlate replies
    // (ack/error/session.ready -> replyTo) without exposing it to the domain usecase.
    channel.beginInbound(envelope.getMessageId());

    final VhrpMessageType type = VhrpMessageType.fromWire(envelope.getType());

    // Handshake gate: session.open is its own state-transition path; everything else requires an
    // already-authenticated connection.
    if (type == VhrpMessageType.SESSION_OPEN) {
      handleSessionOpen(envelope);
      return;
    }
    if (!authenticated) {
      channel.disconnect("auth.invalid_jwt", "The first message must be session.open.");
      return;
    }

    // Core dispatch: an authenticated message is routed to its domain intent (usecase, in domain
    // values only) or its protocol-state handler (support channel). Kept inline on purpose — this
    // is the heart of the endpoint and should be readable in one place.
    switch (type) {
      // --- domain intents: usecase receives decoded domain values only ---
      case AUDIO_TURN_MODE_SET -> usecase.setAudioTurnMode(decoder.readAudioTurnMode(envelope));
      case SESSION_INSTRUCTIONS_SET -> usecase.updateInstructions(decoder.readInstructions(envelope));
      case LIVE_AUDIO_CHUNK -> usecase.ingestLiveAudio(decoder.readLiveAudioChunk(envelope));
      case TURN_AUDIO_SUBMIT -> usecase.submitAudioTurn(decoder.readAudioTurn(envelope));
      case TURN_TEXT_SUBMIT -> usecase.submitTextTurn(decoder.readTextTurn(envelope));
      case TURN_IMAGE_SUBMIT -> usecase.submitImageTurn(decoder.readImageTurn(envelope));
      case TOOLS_SET -> usecase.setTools(decoder.readToolCatalog(envelope));
      case SESSION_EXTENSION_APPLY -> usecase.applyExtension(decoder.readExtension(envelope));
      case TOOL_RESULT_SUBMIT -> usecase.submitToolResult(decoder.readToolResult(envelope));
      case ASSISTANT_INTERRUPT -> usecase.interruptAssistant(decoder.readInterruptReason(envelope));

      // --- protocol-state: handled by support framing, never the domain usecase ---
      case THREAD_SYNC_REQUEST -> channel.resync(decoder.readSyncRequest(envelope));

      default -> {
        Log.debugf(
            "Unsupported VHRP message type '%s' on connection %s",
            envelope.getType(), channel.clientId());
        channel.reportUnsupportedMessage(envelope.getType(), envelope.getMessageId());
      }
    }
  }

  /** Decode one frame, or report it as malformed and return {@code null}. */
  private VhrpEnvelope decodeOrReport(byte[] frame) {
    try {
      return codec.decode(frame);
    } catch (RuntimeException decodeFailure) {
      channel.reportMalformedFrame(decodeFailure);
      return null;
    }
  }

  /**
   * Handle a {@code session.open}: reject it on an already-authenticated connection, otherwise run
   * the use case; success authenticates the connection (and primes resume), a throw disconnects it.
   */
  private void handleSessionOpen(VhrpEnvelope envelope) {
    if (authenticated) {
      channel.disconnect(
          "protocol.bad_message", "session.open is not allowed on an established session.");
      return;
    }
    try {
      usecase.openSession(decoder.readSessionConfig(envelope));
    } catch (RuntimeException authFailure) {
      Log.debugf(authFailure, "session.open rejected on connection %s", channel.clientId());
      channel.disconnect("auth.invalid_jwt", "Authentication failed.");
      return;
    }
    authenticated = true;
    // resume is VHRP stream-continuity state, not a domain concern.
    channel.primeResume(decoder.readResumeRequest(envelope));
  }

  /** Transport closed. Tell the usecase the client detached so it can settle domain state. */
  @OnClose
  public void onClose() {
    usecase.releaseClient();
  }

  /**
   * Transport-level error from the WebSocket runtime. The transport specifics are non-domain and
   * recorded by the support channel; the usecase is then told the client is gone.
   */
  @OnError
  public void onError(Throwable error) {
    channel.reportTransportFailure(error);
    usecase.releaseClient();
  }
}
