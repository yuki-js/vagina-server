package app.vagina.server.realtime;

import app.vagina.server.realtime.model.RealtimeAdapterModels;
import app.vagina.server.realtime.model.RealtimeThread;
import app.vagina.server.realtime.model.RealtimeThread.AudioPart;
import app.vagina.server.realtime.model.RealtimeThread.ContentPart;
import app.vagina.server.realtime.model.RealtimeThread.ImagePart;
import app.vagina.server.realtime.model.RealtimeThread.Item;
import app.vagina.server.realtime.model.RealtimeThread.TextPart;
import io.quarkus.logging.Log;
import io.quarkus.websockets.next.WebSocketConnection;
import io.smallrye.mutiny.Uni;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * One VHRP/1 session: the stateful core that outlives any single WebSocket connection.
 *
 * <p>A session is created by {@link VhrpSessionRegistry} from a {@code session.open} and serves a
 * sequence of connections over its lifetime (a fresh socket re-binds on resume). It owns everything
 * that must survive a reconnect:
 *
 * <ul>
 *   <li>the monotonic {@code streamSeq} stamped on every stateful S2C message;
 *   <li>the bound {@link RealtimeAdapter} (the vendor translation body), which it drives for C2S
 *       and which pushes S2C back through {@link #sendToClient};
 *   <li>a bounded replay log so a resumed connection can be brought forward without a full
 *       snapshot.
 * </ul>
 *
 * <p>The {@link VhrpEndpoint} is stateless and merely forwards: it calls {@link #dispatch} for each
 * inbound frame and {@link #attachConnection} when a socket binds. The current socket is swappable;
 * {@link #detach} clears it without destroying session state, which is what makes resume possible.
 *
 * <h2>Patch building</h2>
 *
 * <p>This class also owns the op-list and snapshot serialization logic (previously attributed to a
 * hypothetical {@code ThreadPatchBuilder}). Keeping it here collocates streamSeq/revision adoption
 * with the serialization that needs both, and avoids a collaborator whose only client would be this
 * class anyway.
 *
 * <p>Threading: a single connection delivers frames serially (the endpoint's {@code
 * InboundProcessingMode.SERIAL} default), but the adapter pushes outbound on its own threads, so
 * {@code streamSeq} is an {@link AtomicLong} and the current-connection reference is volatile.
 */
public class VhrpSession {

  /** Stable resume handle; constant across the connections this session serves. */
  private final String sessionId;

  /** Canonical thread id reported in {@code session.ready}/{@code session.resumed}. */
  private final String threadId;

  private final VhrpCborCodec codec;

  /**
   * The vendor translation body for this session. Driven for C2S; it calls back into {@link
   * #nextStreamSeq} and {@link #sendToClient} to emit S2C. Defined two levels deeper (the {@code
   * oai/} mirror) and intentionally unresolved here.
   */
  private final RealtimeAdapter adapter;

  /** Monotonic server send counter; 1-based, advanced once per stateful S2C message. */
  private final AtomicLong streamSeq = new AtomicLong(0);

  /**
   * Bounded history of already-sent stateful S2C messages, oldest first, used to satisfy a resume's
   * {@code afterStreamSeq} without a full snapshot. Capacity bounds the retention window.
   */
  private final Deque<VhrpMessage.S2C> replayLog = new ArrayDeque<>();

  private final int replayLogCapacity;

  /** The socket currently serving this session, or {@code null} while detached. */
  private volatile WebSocketConnection currentConnection;

  /** Whether {@code session.ready} has ever been emitted; distinguishes new from resumed attach. */
  private volatile boolean everReady = false;

  public VhrpSession(
      String sessionId,
      String threadId,
      VhrpCborCodec codec,
      RealtimeAdapter adapter,
      int replayLogCapacity) {
    this.sessionId = sessionId;
    this.threadId = threadId;
    this.codec = codec;
    this.adapter = adapter;
    this.replayLogCapacity = replayLogCapacity;
  }

  public String sessionId() {
    return sessionId;
  }

  // ---------------------------------------------------------------------------
  // Connection binding
  // ---------------------------------------------------------------------------

  /**
   * Binds {@code connection} as the session's current socket and emits the opening S2C.
   *
   * <p>First-ever attach emits {@code session.ready}; a later attach (resume) emits {@code
   * session.resumed} and then replays buffered messages newer than the client's last applied
   * sequence. The endpoint chains this after {@link VhrpSessionRegistry#openOrResume}.
   */
  public Uni<Void> attachConnection(WebSocketConnection connection) {
    this.currentConnection = connection;
    if (!everReady) {
      everReady = true;
      VhrpMessage.SessionReady ready =
          new VhrpMessage.SessionReady(
              null, // replyTo is filled by the registry, which holds the session.open messageId
              nextStreamSeq(),
              sessionId,
              threadId,
              adapter.conversationId(),
              new ArrayList<>(adapter.supportedExtensions()));
      return writeFrame(ready);
    }
    VhrpMessage.SessionResumed resumed =
        new VhrpMessage.SessionResumed(
            null,
            nextStreamSeq(),
            sessionId,
            threadId,
            adapter.conversationId(),
            "replay",
            adapter.threadRevision());
    // TODO: choose "replay" vs "snapshot" from how much of replayLog still covers the client's
    //   afterStreamSeq; for now always replay what we still hold.
    return writeFrame(resumed).chain(this::replayBuffered);
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

  private Uni<Void> handleSyncRequest(VhrpMessage.ThreadSyncRequest request) {
    // delta_or_snapshot: replay if the requested afterStreamSeq is still covered by the log,
    // otherwise fall back to a fresh snapshot from the adapter's canonical thread.
    boolean snapshotOnly = "snapshot_only".equals(request.mode());
    if (!snapshotOnly && coversStreamSeq(request.afterStreamSeq())) {
      return replayAfter(request.afterStreamSeq());
    }
    return writeFrame(buildSnapshot());
  }

  /**
   * Frames a {@code thread.snapshot} from the adapter's canonical thread. The session owns wire
   * framing (streamSeq, threadId/conversationId/revision); item serialization is handled by the
   * private {@link #snapshotItems(RealtimeThread)} helper, keeping it consistent with the patch op
   * serialization produced by {@link #serializeItem}.
   */
  private VhrpMessage.ThreadSnapshot buildSnapshot() {
    RealtimeThread thread = adapter.thread();
    return new VhrpMessage.ThreadSnapshot(
        nextStreamSeq(),
        thread.id(),
        thread.conversationId(),
        "i_frame",
        adapter.threadRevision(),
        snapshotItems(thread));
  }

  // ---------------------------------------------------------------------------
  // Server -> Client emission (called by the adapter)
  // ---------------------------------------------------------------------------

  /**
   * Reserves the next {@code streamSeq}. The adapter calls this when it constructs a stateful S2C
   * message so sequence ownership stays with the session even though the message is built upstream.
   */
  public long nextStreamSeq() {
    return streamSeq.incrementAndGet();
  }

  /**
   * Sends one already-sequenced S2C message to the current socket and records it for replay. Pushed
   * by the adapter at thread.patch flush points and for audio/vad frames.
   *
   * <p>If no socket is currently bound (mid-reconnect) the frame is still logged so a resuming
   * connection can replay it; it is simply not written now.
   */
  public Uni<Void> sendToClient(VhrpMessage.S2C message) {
    return writeFrame(message);
  }

  private Uni<Void> writeFrame(VhrpMessage.S2C message) {
    rememberForReplay(message);
    WebSocketConnection connection = currentConnection;
    if (connection == null || connection.isClosed()) {
      // Detached: keep the message in the replay log only. A resumed connection will pick it up.
      return Uni.createFrom().voidItem();
    }
    return connection.sendBinary(codec.encode(message));
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

  // ---------------------------------------------------------------------------
  // Replay log
  // ---------------------------------------------------------------------------

  private void rememberForReplay(VhrpMessage.S2C message) {
    // ack/session.ready carry no streamSeq and are not part of resync; only log sequenced frames.
    if (!isSequenced(message)) {
      return;
    }
    synchronized (replayLog) {
      replayLog.addLast(message);
      while (replayLog.size() > replayLogCapacity) {
        replayLog.removeFirst();
      }
    }
  }

  private boolean coversStreamSeq(long afterStreamSeq) {
    synchronized (replayLog) {
      VhrpMessage.S2C oldest = replayLog.peekFirst();
      // Covered when the oldest retained frame is at or before the next one the client needs.
      return oldest == null || streamSeqOf(oldest) <= afterStreamSeq + 1;
    }
  }

  private Uni<Void> replayBuffered() {
    return replayAfter(0);
  }

  private Uni<Void> replayAfter(long afterStreamSeq) {
    Uni<Void> chain = Uni.createFrom().voidItem();
    synchronized (replayLog) {
      for (VhrpMessage.S2C message : replayLog) {
        if (streamSeqOf(message) > afterStreamSeq) {
          // Re-send already-sequenced frames as-is; do NOT advance streamSeq on replay.
          WebSocketConnection connection = currentConnection;
          if (connection != null && !connection.isClosed()) {
            chain = chain.chain(() -> connection.sendBinary(codec.encode(message)));
          }
        }
      }
    }
    return chain;
  }

  private static boolean isSequenced(VhrpMessage.S2C message) {
    return switch (message) {
      case VhrpMessage.Ack ignored -> false;
      case VhrpMessage.SessionReady ignored -> false;
      default -> true;
    };
  }

  private static long streamSeqOf(VhrpMessage.S2C message) {
    return switch (message) {
      case VhrpMessage.ThreadSnapshot m -> m.streamSeq();
      case VhrpMessage.ThreadPatch m -> m.streamSeq();
      case VhrpMessage.AssistantAudioChunk m -> m.streamSeq();
      case VhrpMessage.AssistantAudioDone m -> m.streamSeq();
      case VhrpMessage.VadState m -> m.streamSeq();
      case VhrpMessage.SessionResumed m -> m.streamSeq();
      case VhrpMessage.Error m -> m.streamSeq();
      // Non-sequenced frames never enter the replay log (see isSequenced), so this is unreachable.
      default -> 0L;
    };
  }

  // ---------------------------------------------------------------------------
  // Patch op builders (inline; previously a hypothetical ThreadPatchBuilder)
  // ---------------------------------------------------------------------------

  /**
   * Builds an {@code add_item} op map. Called by the OAI adapter's mutation sink when a new item
   * is added to the canonical thread.
   */
  public static Map<String, Object> opAddItem(Item item) {
    Map<String, Object> op = new LinkedHashMap<>();
    op.put("op", "add_item");
    op.put("item", serializeItem(item));
    return op;
  }

  public static Map<String, Object> opRemoveItem(String itemId) {
    Map<String, Object> op = new LinkedHashMap<>();
    op.put("op", "remove_item");
    op.put("itemId", itemId);
    return op;
  }

  public static Map<String, Object> opSetStatus(String itemId, String status) {
    Map<String, Object> op = new LinkedHashMap<>();
    op.put("op", "set_status");
    op.put("itemId", itemId);
    op.put("status", status);
    return op;
  }

  public static Map<String, Object> opSetField(String itemId, String field, Object value) {
    Map<String, Object> op = new LinkedHashMap<>();
    op.put("op", "set_field");
    op.put("itemId", itemId);
    op.put("field", field);
    op.put("value", value);
    return op;
  }

  public static Map<String, Object> opPutPart(String itemId, int contentIndex, ContentPart part) {
    Map<String, Object> op = new LinkedHashMap<>();
    op.put("op", "put_part");
    op.put("itemId", itemId);
    op.put("contentIndex", contentIndex);
    op.put("part", serializePart(part));
    return op;
  }

  public static Map<String, Object> opAppendText(String itemId, int contentIndex, String delta) {
    Map<String, Object> op = new LinkedHashMap<>();
    op.put("op", "append_text");
    op.put("itemId", itemId);
    op.put("contentIndex", contentIndex);
    op.put("delta", delta);
    return op;
  }

  public static Map<String, Object> opReplaceText(String itemId, int contentIndex, String text) {
    Map<String, Object> op = new LinkedHashMap<>();
    op.put("op", "replace_text");
    op.put("itemId", itemId);
    op.put("contentIndex", contentIndex);
    op.put("text", text);
    return op;
  }

  public static Map<String, Object> opAppendTranscript(
      String itemId, int contentIndex, String delta) {
    Map<String, Object> op = new LinkedHashMap<>();
    op.put("op", "append_transcript");
    op.put("itemId", itemId);
    op.put("contentIndex", contentIndex);
    op.put("delta", delta);
    return op;
  }

  public static Map<String, Object> opReplaceTranscript(
      String itemId, int contentIndex, String text) {
    Map<String, Object> op = new LinkedHashMap<>();
    op.put("op", "replace_transcript");
    op.put("itemId", itemId);
    op.put("contentIndex", contentIndex);
    op.put("text", text);
    return op;
  }

  public static Map<String, Object> opSetConversationId(String conversationId) {
    Map<String, Object> op = new LinkedHashMap<>();
    op.put("op", "set_conversation_id");
    op.put("conversationId", conversationId);
    return op;
  }

  // ---------------------------------------------------------------------------
  // Snapshot item serialization
  // ---------------------------------------------------------------------------

  /**
   * Serialises all items of {@code thread} into the {@code thread.snapshot.body.items} shape.
   * Uses the same per-item serializer as the patch op builders so client-side patch applier and
   * snapshot replacer see identical item/part shapes.
   */
  private static List<Map<String, Object>> snapshotItems(RealtimeThread thread) {
    List<Map<String, Object>> items = new ArrayList<>();
    for (Item item : thread.items()) {
      items.add(serializeItem(item));
    }
    return List.copyOf(items);
  }

  private static Map<String, Object> serializeItem(Item item) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("id", item.id());
    map.put("type", wireItemType(item.type()));
    if (item.role() != null) {
      map.put("role", item.role().name().toLowerCase());
    }
    map.put("status", item.status().wireValue());
    if (item.callId() != null) {
      map.put("callId", item.callId());
    }
    if (item.name() != null) {
      map.put("name", item.name());
    }
    if (item.arguments() != null) {
      map.put("arguments", item.arguments());
    }
    if (item.output() != null) {
      map.put("output", item.output());
    }
    if (!item.content().isEmpty()) {
      List<Map<String, Object>> parts = new ArrayList<>();
      for (ContentPart part : item.content()) {
        parts.add(serializePart(part));
      }
      map.put("content", parts);
    }
    return map;
  }

  private static Map<String, Object> serializePart(ContentPart part) {
    Map<String, Object> map = new LinkedHashMap<>();
    switch (part) {
      case TextPart tp -> {
        map.put("type", "text");
        map.put("isDone", tp.isDone());
      }
      case AudioPart ap -> {
        map.put("type", "audio");
        map.put("isDone", ap.isDone());
        // Spec: snapshot carries transcript only; raw PCM chunks are non-goal for recovery.
        if (ap.transcript() != null) {
          map.put("transcript", ap.transcript());
        }
      }
      case ImagePart ip -> {
        map.put("type", "image");
        map.put("imageUrl", ip.imageUrl());
        map.put("detail", ip.detail());
      }
    }
    return map;
  }

  private static String wireItemType(RealtimeThread.ItemType type) {
    return switch (type) {
      case MESSAGE -> "message";
      case FUNCTION_CALL -> "functionCall";
      case FUNCTION_CALL_OUTPUT -> "functionCallOutput";
    };
  }
}
