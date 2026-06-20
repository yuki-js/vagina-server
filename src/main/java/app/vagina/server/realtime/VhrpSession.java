package app.vagina.server.realtime;

import app.vagina.server.realtime.model.RealtimeAdapterModels;
import app.vagina.server.realtime.model.RealtimeThread;
import io.quarkus.logging.Log;
import io.quarkus.websockets.next.WebSocketConnection;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.subscription.Cancellable;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * One VHRP/1 session: the stateful core that outlives any single WebSocket connection.
 *
 * <p>A session is created by {@link VhrpSessionRegistry} from a {@code session.open} and serves a
 * sequence of connections over its lifetime (a fresh socket re-binds on resume). It owns:
 *
 * <ul>
 *   <li>the bound {@link RealtimeAdapter} (the vendor translation body), which it drives for C2S
 *       and which pushes S2C back through {@link #sendToClient};
 *   <li>the current socket binding, swappable across reconnects.
 * </ul>
 *
 * <p>The {@link VhrpEndpoint} is stateless and merely forwards: it calls {@link #dispatch} for each
 * inbound frame and {@link #attachConnection} when a socket binds. The current socket is swappable;
 * {@link #detach} clears it without destroying session state, which is what makes resume possible.
 *
 * <h2>Single recovery path: reconnect + full snapshot</h2>
 *
 * <p>This session keeps <em>no</em> replay log and stamps <em>no</em> stream sequence or thread
 * revision. A {@code thread.patch} is a fire-and-forget live delta. The one and only recovery for
 * any delivery gap — a dropped frame, a desync, a reconnect — is a fresh full {@code thread.snapshot}
 * built on demand from the adapter's canonical thread (which is always live in memory). That makes
 * two things follow:
 *
 * <ul>
 *   <li>{@link #handleSyncRequest} always answers with a full snapshot; there is no cursor to honor;
 *   <li>{@link #writeFrame} logs and absorbs a live-frame write failure rather than closing the
 *       socket itself. A genuinely dead socket is reclaimed by Quarkus via {@code @OnClose} →
 *       {@link VhrpEndpoint#onClose} → detach (after which the client reconnects and resyncs), so a
 *       manual close would only duplicate that while risking the needless teardown of a healthy
 *       connection on a possibly-transient failure. While detached, frames are simply dropped (the
 *       next attach resyncs).
 * </ul>
 *
 * <p>Threading: a single connection delivers frames serially (the endpoint's {@code
 * InboundProcessingMode.SERIAL} default), but the adapter pushes outbound on its own threads, so the
 * current-connection reference is volatile.
 */
public class VhrpSession {

  /** Stable resume handle; constant across the connections this session serves. */
  private final String sessionId;

  /** Canonical thread id reported in {@code session.ready}/{@code session.resumed}. */
  private final String threadId;

  private final VhrpCborCodec codec;

  /**
   * The vendor translation body for this session. Driven for C2S; it pushes S2C back through {@link
   * #sendToClient}. Defined two levels deeper (the {@code oai/} mirror) and intentionally unresolved
   * here.
   */
  private final RealtimeAdapter adapter;

  /** The socket currently serving this session, or {@code null} while detached. */
  private volatile WebSocketConnection currentConnection;

  /** Whether {@code session.ready} has ever been emitted; distinguishes new from resumed attach. */
  private volatile boolean everReady = false;

  /**
   * Subscriptions to the adapter's S2C output streams (patches/audio/vad/errors). Held for the
   * session's lifetime and cancelled on {@link #dispose()}; the adapter pushes on its own threads so
   * these feed straight into {@link #writeFrame}.
   */
  private final List<Cancellable> adapterSubscriptions = new ArrayList<>();

  public VhrpSession(String sessionId, String threadId, VhrpCborCodec codec, RealtimeAdapter adapter) {
    this.sessionId = sessionId;
    this.threadId = threadId;
    this.codec = codec;
    this.adapter = adapter;
    subscribeAdapterOutput();
  }

  public String sessionId() {
    return sessionId;
  }

  // ---------------------------------------------------------------------------
  // Adapter S2C subscription
  // ---------------------------------------------------------------------------

  /**
   * Subscribes the adapter's observation points and frames each into an S2C message. This is the S2C
   * half of the warp: the adapter (vendor body) speaks model types on Mutiny streams, and the
   * session turns them into wire frames written live to the current socket.
   *
   * <ul>
   *   <li>{@code threadPatches()} → {@code thread.patch} (bare op list);
   *   <li>{@code assistantAudioStream()} → {@code assistant.audio.chunk};
   *   <li>{@code assistantAudioCompleted()} → {@code assistant.audio.done};
   *   <li>{@code isUserSpeakingUpdates()} → {@code vad.state};
   *   <li>{@code errors()} → {@code error} (recoverable: an established-session fault is reported
   *       in-band, never closes here).
   * </ul>
   *
   * <p>A failure on the stream itself (not a write failure) is logged and does not tear the session
   * down. A failure to <em>write</em> a framed message is handled inside {@link #writeFrame}, which
   * closes the socket to force a reconnect + snapshot resync.
   */
  private void subscribeAdapterOutput() {
    adapterSubscriptions.add(
        adapter
            .threadPatches()
            .subscribe()
            .with(this::emitThreadPatch, t -> Log.errorf(t, "VHRP %s threadPatches failed", sessionId)));
    adapterSubscriptions.add(
        adapter
            .assistantAudioStream()
            .subscribe()
            .with(this::emitAssistantAudioChunk, t -> Log.errorf(t, "VHRP %s audioStream failed", sessionId)));
    adapterSubscriptions.add(
        adapter
            .assistantAudioCompleted()
            .subscribe()
            .with(this::emitAssistantAudioDone, t -> Log.errorf(t, "VHRP %s audioDone failed", sessionId)));
    adapterSubscriptions.add(
        adapter
            .isUserSpeakingUpdates()
            .subscribe()
            .with(this::emitVadState, t -> Log.errorf(t, "VHRP %s vad failed", sessionId)));
    adapterSubscriptions.add(
        adapter
            .errors()
            .subscribe()
            .with(this::emitError, t -> Log.errorf(t, "VHRP %s errors failed", sessionId)));
  }

  private void emitThreadPatch(RealtimeAdapterModels.ThreadPatchOps batch) {
    VhrpMessage.ThreadPatch patch = new VhrpMessage.ThreadPatch(batch.ops());
    sendToClient(patch).subscribe().with(ignored -> {}, t -> Log.errorf(t, "VHRP %s patch write", sessionId));
  }

  private void emitAssistantAudioChunk(RealtimeAdapterModels.AssistantAudioFrame frame) {
    VhrpMessage.AssistantAudioChunk chunk =
        new VhrpMessage.AssistantAudioChunk(frame.itemId(), frame.contentIndex(), frame.pcm());
    sendToClient(chunk).subscribe().with(ignored -> {}, t -> Log.errorf(t, "VHRP %s audio write", sessionId));
  }

  private void emitAssistantAudioDone(RealtimeAdapterModels.AssistantAudioFrame frame) {
    VhrpMessage.AssistantAudioDone done =
        new VhrpMessage.AssistantAudioDone(frame.itemId(), frame.contentIndex());
    sendToClient(done).subscribe().with(ignored -> {}, t -> Log.errorf(t, "VHRP %s audioDone write", sessionId));
  }

  private void emitVadState(Boolean speaking) {
    VhrpMessage.VadState vad = new VhrpMessage.VadState(Boolean.TRUE.equals(speaking));
    sendToClient(vad).subscribe().with(ignored -> {}, t -> Log.errorf(t, "VHRP %s vad write", sessionId));
  }

  private void emitError(RealtimeAdapterModels.Error error) {
    VhrpMessage.Error wire = new VhrpMessage.Error(null, error.code(), error.message(), true);
    sendToClient(wire).subscribe().with(ignored -> {}, t -> Log.errorf(t, "VHRP %s error write", sessionId));
  }

  /**
   * Cancels the adapter subscriptions and disposes the adapter. Called by the registry on explicit
   * close or retention expiry; not on a mere detach (resume must keep the streams alive).
   */
  public Uni<Void> dispose() {
    for (Cancellable subscription : adapterSubscriptions) {
      subscription.cancel();
    }
    adapterSubscriptions.clear();
    return adapter.dispose();
  }

  // ---------------------------------------------------------------------------
  // Connection binding
  // ---------------------------------------------------------------------------

  /**
   * Binds {@code connection} as the session's current socket and emits the opening S2C, correlated
   * to the {@code session.open} that triggered this attach via {@code replyToMessageId}.
   *
   * <p>First-ever attach emits {@code session.ready}; a later attach (resume) emits {@code
   * session.resumed} and nothing more. Per the recovery model, a re-bind only announces the rebind;
   * the client then asks for a fresh full {@code thread.snapshot} via {@code thread.sync.request}
   * (handled by {@link #handleSyncRequest}). The endpoint chains this after {@link
   * VhrpSessionRegistry#openOrResume}, passing the open's {@code messageId} so the reply correlates
   * to the right frame — on resume that is the resume open's own messageId, which is exactly why the
   * id is threaded through per attach rather than stored at session creation.
   */
  public Uni<Void> attachConnection(WebSocketConnection connection, String replyToMessageId) {
    this.currentConnection = connection;
    if (!everReady) {
      everReady = true;
      VhrpMessage.SessionReady ready =
          new VhrpMessage.SessionReady(
              replyToMessageId,
              sessionId,
              threadId,
              adapter.conversationId(),
              new ArrayList<>(adapter.supportedExtensions()));
      return writeFrame(ready);
    }
    // Resume: announce the rebind only. The client resyncs by sending thread.sync.request, which we
    // answer with a fresh full snapshot; we push no state here.
    VhrpMessage.SessionResumed resumed =
        new VhrpMessage.SessionResumed(replyToMessageId, sessionId, threadId, adapter.conversationId());
    return writeFrame(resumed);
  }

  /**
   * Unbinds the current socket without destroying session state. Called on close or abnormal drop;
   * the registry decides eventual disposal (explicit close or retention expiry).
   */
  public void detach(WebSocketConnection connection) {
    // Only clear if the detaching socket is still the active one: a fast resume may have already
    // re-bound a newer connection before the old one's close callback runs.
    if (this.currentConnection == connection) {
      this.currentConnection = null;
    }
  }

  // ---------------------------------------------------------------------------
  // Client -> Server dispatch
  // ---------------------------------------------------------------------------

  /**
   * Routes one decoded C2S message to the bound adapter and replies where the protocol requires it.
   *
   * <p>This is the dispatch table from the backend spec: each {@code turn.*}/{@code session.*}/{@code
   * tools.set}/{@code tool.result.submit} is acknowledged via {@code ack}; one-way messages
   * ({@code live.audio.chunk}, {@code audio.turn.mode.set}, {@code assistant.interrupt}) are not.
   */
  public Uni<Void> dispatch(VhrpMessage.C2S message) {
    return switch (message) {
      case VhrpMessage.AudioTurnModeSet m -> handleAudioTurnModeSet(m);
      case VhrpMessage.SessionInstructionsSet m -> handleSessionInstructionsSet(m);
      case VhrpMessage.LiveAudioChunk m -> handleLiveAudioChunk(m);
      case VhrpMessage.TurnAudioSubmit m -> handleTurnAudioSubmit(m);
      case VhrpMessage.TurnTextSubmit m -> handleTurnTextSubmit(m);
      case VhrpMessage.TurnImageSubmit m -> handleTurnImageSubmit(m);
      case VhrpMessage.ToolsSet m -> handleToolsSet(m);
      case VhrpMessage.SessionExtensionApply m -> handleSessionExtensionApply(m);
      case VhrpMessage.ToolResultSubmit m -> handleToolResultSubmit(m);
      case VhrpMessage.AssistantInterrupt m -> adapter.interrupt();
      case VhrpMessage.ThreadSyncRequest m -> handleSyncRequest(m);
      case VhrpMessage.SessionOpen ignored ->
          Uni.createFrom()
              .failure(
                  new VhrpException.ProtocolBadMessage(
                      "session.open is only valid as the first frame"));
    };
  }

  private Uni<Void> handleAudioTurnModeSet(VhrpMessage.AudioTurnModeSet m) {
    return adapter.setAudioTurnMode(RealtimeAdapterModels.AudioTurnMode.fromWire(m.mode()));
  }

  private Uni<Void> handleSessionInstructionsSet(VhrpMessage.SessionInstructionsSet m) {
    return adapter.setInstructions(m.instructions())
        .onItem().transformToUni(ignored -> ack(m.messageId()));
  }

  private Uni<Void> handleLiveAudioChunk(VhrpMessage.LiveAudioChunk m) {
    adapter.pushLiveAudioChunk(m.pcm());
    return Uni.createFrom().voidItem();
  }

  private Uni<Void> handleTurnAudioSubmit(VhrpMessage.TurnAudioSubmit m) {
    return adapter.sendAudioOneShot(m.pcm())
        .onItem().transformToUni(itemId -> ackItem(m.messageId(), m.clientItemId()));
  }

  private Uni<Void> handleTurnTextSubmit(VhrpMessage.TurnTextSubmit m) {
    return adapter.sendText(m.text())
        .onItem().transformToUni(itemId -> ackItem(m.messageId(), m.clientItemId()));
  }

  private Uni<Void> handleTurnImageSubmit(VhrpMessage.TurnImageSubmit m) {
    return adapter.sendImage(m.imageBytes())
        .onItem().transformToUni(itemId -> ackItem(m.messageId(), m.clientItemId()));
  }

  private Uni<Void> handleToolsSet(VhrpMessage.ToolsSet m) {
    // Boundary translation: wire ToolSpec -> model ToolDefinition so the adapter never sees a VHRP
    // type. The shapes are identical today; the mapping makes the dependency direction explicit.
    List<RealtimeAdapterModels.ToolDefinition> tools =
        m.tools().stream()
            .map(t -> new RealtimeAdapterModels.ToolDefinition(t.name(), t.description(), t.parameters()))
            .collect(Collectors.toList());
    return adapter.registerTools(tools).onItem().transformToUni(ignored -> ack(m.messageId()));
  }

  private Uni<Void> handleSessionExtensionApply(VhrpMessage.SessionExtensionApply m) {
    return adapter.applyProviderExtension(m.extensionType(), m.payload())
        .onItem().transformToUni(applied -> {
          if (!applied) {
            return Uni.createFrom().failure(
                new VhrpException.ExtensionUnsupported(
                    "Unsupported extension: " + m.extensionType()));
          }
          return ack(m.messageId());
        });
  }

  private Uni<Void> handleToolResultSubmit(VhrpMessage.ToolResultSubmit m) {
    // Boundary translation: wire disposition token -> model enum.
    RealtimeAdapterModels.ToolOutputDisposition disposition =
        RealtimeAdapterModels.ToolOutputDisposition.fromWire(m.disposition());
    return adapter.sendFunctionOutput(m.callId(), m.output(), disposition, m.errorMessage())
        .onItem().transformToUni(itemId -> ackItem(m.messageId(), m.clientItemId()));
  }

  /**
   * Answers a resync request with a fresh full snapshot. In the snapshot-only recovery model there
   * is no cursor or partial catch-up: whatever the client's situation, the current full thread state
   * fully reconstructs its projection.
   */
  private Uni<Void> handleSyncRequest(VhrpMessage.ThreadSyncRequest request) {
    return writeFrame(buildSnapshot());
  }

  /**
   * Frames a {@code thread.snapshot} from the adapter's canonical thread. The session owns wire
   * framing (ids); item serialization is delegated to {@link
   * ThreadPatchBuilder#snapshotItems(RealtimeThread)}, the same projector that backs the patch op
   * shapes, so a resync snapshot and the incremental patches stay consistent.
   */
  private VhrpMessage.ThreadSnapshot buildSnapshot() {
    RealtimeThread thread = adapter.thread();
    return new VhrpMessage.ThreadSnapshot(
        thread.id(), thread.conversationId(), ThreadPatchBuilder.snapshotItems(thread));
  }

  // ---------------------------------------------------------------------------
  // Server -> Client emission (called by the adapter)
  // ---------------------------------------------------------------------------

  /**
   * Sends one S2C message to the current socket. Pushed by the adapter at thread.patch flush points
   * and for audio/vad frames. If no socket is bound (mid-reconnect) the frame is dropped; the next
   * attach resyncs via a full snapshot.
   */
  public Uni<Void> sendToClient(VhrpMessage.S2C message) {
    return writeFrame(message);
  }

  /**
   * Writes one framed S2C message to the current socket. The session never closes the socket itself
   * on a write failure — connection teardown is left to the framework, which keeps the single
   * recovery path (reconnect + full snapshot) intact without churning live connections.
   *
   * <ul>
   *   <li>detached (no socket / closed): drop the frame. Recovery is reconnect + full snapshot, so a
   *       dropped live frame is intentionally not buffered.
   *   <li>write failure: logged and absorbed. Two reasons not to escalate to a manual close here.
   *       First, a permanent socket fault is already surfaced by Quarkus as {@code @OnClose} →
   *       {@link VhrpEndpoint#onClose} → detach, after which the client reconnects and resyncs via a
   *       fresh full {@code thread.snapshot}; a manual close would only duplicate that. Second, a
   *       manual close on a possibly-transient send failure would needlessly kill a live connection,
   *       forcing a wasteful reconnect + snapshot (and the handshake cost that implies). Absorbing
   *       the failure is the dominant choice: it never tears a healthy connection, and a genuinely
   *       dead one is reclaimed by {@code @OnClose} regardless.
   * </ul>
   *
   * <p>Note these audio/patch/vad frames are emitted from the adapter's own subscriptions (see
   * {@link #subscribeAdapterOutput}), not from a {@code @On*} callback, so their failures never reach
   * {@code @OnError}; logging here is the intended terminal handling. Failures on the request/reply
   * path ({@code ack} etc.), which do ride a {@code @OnBinaryMessage} Uni, are governed instead by
   * {@code quarkus.websockets-next.server.unhandled-failure-strategy} via {@code @OnError}.
   */
  private Uni<Void> writeFrame(VhrpMessage.S2C message) {
    WebSocketConnection connection = currentConnection;
    if (connection == null || connection.isClosed()) {
      return Uni.createFrom().voidItem();
    }
    return connection
        .sendBinary(codec.encode(message))
        .onFailure()
        .recoverWithUni(
            failure -> {
              Log.warnf(failure, "VHRP %s frame write failed; dropping (recovery via reconnect+snapshot)", sessionId);
              return Uni.createFrom().voidItem();
            });
  }

  // ---------------------------------------------------------------------------
  // Acks
  // ---------------------------------------------------------------------------

  private Uni<Void> ack(String messageId) {
    if (messageId == null) {
      return Uni.createFrom().voidItem();
    }
    // Plain ack: applied=true, no item correlation.
    return sendToClient(new VhrpMessage.Ack(messageId, true, null, true));
  }

  private Uni<Void> ackItem(String messageId, String clientItemId) {
    if (messageId == null) {
      return Uni.createFrom().voidItem();
    }
    return sendToClient(new VhrpMessage.Ack(messageId, true, clientItemId, true));
  }
}
