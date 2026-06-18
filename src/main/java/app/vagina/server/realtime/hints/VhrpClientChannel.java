











/**
 * Per-connection VHRP/1 output channel and framing state.
 *
 * <p>This support-layer component turns the domain events the usecase emits into VHRP/1 wire frames
 * and writes them to the client. The usecase depends on it as a concrete bean injected directly; no
 * output-port interface is introduced, mirroring how {@code ModelDriver} is a concrete service (one
 * implementation today, so an abstraction would be speculative; YAGNI). It also exposes the
 * protocol-only methods the resource endpoint calls directly (resume priming, resync,
 * malformed/unsupported/transport-failure reporting, transport id).
 *
 * <p>It is {@link SessionScoped}, so there is exactly one instance per WebSocket connection,
 * matching the {@code @SessionScoped} endpoint. It therefore owns this connection's framing state —
 * the monotonic {@code streamSeq} and the current {@code threadRevision} — without any shared map.
 *
 * <p>Framing policy (chosen for this slice): every domain mutation is emitted immediately as a
 * single-op {@code thread.patch}, and frames are written non-blocking via {@link
 * WebSocketConnection#sendBinary}. This is the most direct, spec-faithful mapping; batching ops into
 * multi-op patches can be introduced later without changing the domain port.
 *
 * <p>Resume/resync are stubbed to the spec's recovery error for now: full replay/snapshot recovery
 * needs a replay log and checkpoint store, which are out of scope for this slice. The forward path
 * (session.ready, thread.patch/snapshot, audio, vad, ack, error) is fully framed.
 */
@SessionScoped
public class VhrpClientChannel {

  @Inject WebSocketConnection connection;
  @Inject VhrpCodec codec;

  private long streamSeq = 0L;
  private long threadRevision = 0L;

  /**
   * The in-flight inbound {@code messageId}, captured by the endpoint before dispatch so that
   * correlated replies ({@code ack}, {@code error}) can set {@code replyTo} without the domain layer
   * ever seeing the protocol id.
   */
  // TODO(VHRP): side-channel — beginInbound() writes this and sendReply()/sendError() read it,
  // relying on the (currently undocumented) assumption that WebSocket callbacks are serialized per
  // connection. reportUnsupportedMessage() also re-sets it, giving two write paths. Make the reply
  // correlation explicit (e.g. pass messageId through, or document+enforce the serialization).
  private String inflightMessageId;

  // ---------------------------------------------------------------------------
  // Protocol-only API (called by the resource endpoint, not part of the domain port)
  // ---------------------------------------------------------------------------

  /** Transport-level connection id; also used as the initial thread id. */
  public String clientId() {
    return connection.id();
  }

  /** Record the inbound message id about to be dispatched, for reply correlation. */
  public void beginInbound(String messageId) {
    this.inflightMessageId = messageId;
  }

  // TODO(VHRP): resume is stubbed to resume.not_available. Real replay/snapshot recovery needs a
  // ReplayLog + CheckpointStore and a real session-id mapping (see sessionEstablished).
  /** Prime resume state from {@code session.open.resume}. Full replay recovery is not yet wired. */
  public void primeResume(VhrpEnvelope.VhrpResumeRequest resume) {
    if (resume == null) {
      return;
    }
    Log.debugf("Resume requested for session %s (replay not yet implemented)", resume.sessionId());
    sendError("resume.not_available", "Resume is not supported yet.", true);
  }

  // TODO(VHRP): resync is stubbed to state.out_of_sync. Real gap recovery must replay messages after
  // afterStreamSeq or fall back to a thread.snapshot (needs ReplayLog + CheckpointStore).
  /** Handle {@code thread.sync.request}. Full replay/snapshot recovery is not yet wired. */
  public void resync(VhrpEnvelope.VhrpSyncRequest request) {
    Log.debugf("Resync requested after streamSeq %d (not yet implemented)", request.afterStreamSeq());
    sendError("state.out_of_sync", "Resync is not supported yet.", true);
  }

  /** Report a frame that could not be decoded. */
  public void reportMalformedFrame(Throwable cause) {
    Log.warnf(cause, "Malformed VHRP frame on connection %s", connection.id());
    sendError("protocol.bad_message", "Malformed VHRP frame.", true);
  }

  /** Report an unsupported or unknown message type. */
  public void reportUnsupportedMessage(String type, String messageId) {
    this.inflightMessageId = messageId;
    sendError(
        "protocol.unsupported_message_type", "Unsupported message type: " + type, true);
  }

  /** Report a transport-level failure surfaced by the WebSocket runtime. */
  public void reportTransportFailure(Throwable error) {
    Log.warnf(error, "VHRP transport failure on connection %s", connection.id());
  }

  /**
   * Send a final {@code error} frame and close the connection.
   *
   * <p>Used by the endpoint's handshake state machine to terminate a connection that violates the
   * pre-/post-authentication message rules (an unrecoverable protocol error).
   */
  public void disconnect(String code, String message) {
    sendError(code, message, false);
    if (connection.isOpen()) {
      connection.close().subscribe().with(item -> {}, failure -> {});
    }
  }

  // ---------------------------------------------------------------------------
  // Session lifecycle (domain port)
  // ---------------------------------------------------------------------------

  public void sessionEstablished(
      String threadId, String conversationId, List<String> capabilities) {
    Map<String, Object> body = new LinkedHashMap<>();
    // TODO(VHRP): sessionId is a resume handle in the spec, not the transport id. Equating it with
    // connection.id() blocks cross-connection resume. Mint/track a real session id (with ReplayLog/
    // CheckpointStore) instead of reusing the transport connection id.
    body.put("sessionId", connection.id());
    body.put("threadId", threadId);
    if (conversationId != null) {
      body.put("conversationId", conversationId);
    }
    Map<String, Object> caps = new LinkedHashMap<>();
    caps.put("extensions", capabilities == null ? List.of() : capabilities);
    body.put("capabilities", caps);
    sendReply(VhrpMessageType.SESSION_READY, body);
  }

  public void submissionAccepted(String clientItemId) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("accepted", true);
    body.put("clientItemId", clientItemId);
    body.put("applied", true);
    sendReply(VhrpMessageType.ACK, body);
  }

  // ---------------------------------------------------------------------------
  // Thread projection (domain port): each mutation -> single-op thread.patch
  // ---------------------------------------------------------------------------

  public void threadReset(RealtimeThread thread) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("threadId", thread.id());
    if (thread.conversationId() != null) {
      body.put("conversationId", thread.conversationId());
    }
    body.put("snapshotKind", "i_frame");
    body.put("threadRevision", threadRevision);
    List<Object> items = new ArrayList<>();
    for (RealtimeThread.Item item : thread.items()) {
      items.add(snapshotItem(item));
    }
    body.put("items", items);
    sendStateful(VhrpMessageType.THREAD_SNAPSHOT, body);
  }

  public void itemAdded(RealtimeThread.Item item) {
    Map<String, Object> op = new LinkedHashMap<>();
    op.put("op", "add_item");
    op.put("item", snapshotItem(item));
    sendPatch(op);
  }

  public void itemRemoved(String itemId) {
    Map<String, Object> op = new LinkedHashMap<>();
    op.put("op", "remove_item");
    op.put("itemId", itemId);
    sendPatch(op);
  }

  public void itemStatusChanged(String itemId, RealtimeThread.Status status) {
    Map<String, Object> op = new LinkedHashMap<>();
    op.put("op", "set_status");
    op.put("itemId", itemId);
    op.put("status", statusWire(status));
    sendPatch(op);
  }

  public void itemRoleChanged(String itemId, RealtimeThread.Role role) {
    Map<String, Object> op = new LinkedHashMap<>();
    op.put("op", "set_role");
    op.put("itemId", itemId);
    op.put("role", roleWire(role));
    sendPatch(op);
  }

  public void itemFieldChanged(String itemId, RealtimeThread.Field field, Object value) {
    Map<String, Object> op = new LinkedHashMap<>();
    op.put("op", "set_field");
    op.put("itemId", itemId);
    op.put("field", fieldWire(field));
    op.put("value", value);
    sendPatch(op);
  }

  public void partPut(String itemId, int contentIndex, RealtimeThread.ContentPart part) {
    Map<String, Object> op = new LinkedHashMap<>();
    op.put("op", "put_part");
    op.put("itemId", itemId);
    op.put("contentIndex", contentIndex);
    op.put("part", partWire(part));
    sendPatch(op);
  }

  public void textAppended(String itemId, int contentIndex, String delta) {
    sendPatch(textOp("append_text", itemId, contentIndex, "delta", delta));
  }

  public void textReplaced(String itemId, int contentIndex, String text) {
    sendPatch(textOp("replace_text", itemId, contentIndex, "text", text));
  }

  public void transcriptAppended(String itemId, int contentIndex, String delta) {
    sendPatch(textOp("append_transcript", itemId, contentIndex, "delta", delta));
  }

  public void transcriptReplaced(String itemId, int contentIndex, String text) {
    sendPatch(textOp("replace_transcript", itemId, contentIndex, "text", text));
  }

  public void conversationIdChanged(String conversationId) {
    Map<String, Object> op = new LinkedHashMap<>();
    op.put("op", "set_conversation_id");
    op.put("conversationId", conversationId);
    sendPatch(op);
  }

  // ---------------------------------------------------------------------------
  // Assistant audio (domain port)
  // ---------------------------------------------------------------------------

  public void assistantAudioProduced(byte[] pcm, String itemId, int contentIndex) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("itemId", itemId);
    body.put("contentIndex", contentIndex);
    body.put("pcm", pcm);
    sendStateful(VhrpMessageType.ASSISTANT_AUDIO_CHUNK, body);
  }

  public void assistantAudioFinished(String itemId, int contentIndex) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("itemId", itemId);
    body.put("contentIndex", contentIndex);
    sendStateful(VhrpMessageType.ASSISTANT_AUDIO_DONE, body);
  }

  // ---------------------------------------------------------------------------
  // VAD (domain port)
  // ---------------------------------------------------------------------------

  public void userSpeakingChanged(boolean speaking) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("isSpeaking", speaking);
    sendStateful(VhrpMessageType.VAD_STATE, body);
  }

  // ---------------------------------------------------------------------------
  // Problems (domain port)
  // ---------------------------------------------------------------------------

  public void problemRaised(String code, String message, boolean recoverable) {
    sendError(code, message, recoverable);
  }

  // ---------------------------------------------------------------------------
  // Wire framing helpers
  // ---------------------------------------------------------------------------

  // TODO(VHRP): itemRevision is omitted from every op here (and from snapshotItem). The wire spec
  // requires per-item itemRevision on snapshot items and patch ops; add monotonic itemRevision
  // tracking when ReplayLog/CheckpointStore land.
  private void sendPatch(Map<String, Object> op) {
    // TODO(VHRP): threadRevision is advanced before the send is confirmed. If emit() fails, the
    // revision (and streamSeq below) is consumed without a delivered frame -> guaranteed
    // base/targetThreadRevision desync. Allocate revision/seq only after a confirmed send, or roll
    // back on failure.
    long base = threadRevision;
    long target = ++threadRevision;
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("patchKind", "p_frame");
    body.put("baseThreadRevision", base);
    body.put("targetThreadRevision", target);
    List<Object> ops = new ArrayList<>(1);
    ops.add(op);
    body.put("ops", ops);
    sendStateful(VhrpMessageType.THREAD_PATCH, body);
  }

  private void sendStateful(VhrpMessageType type, Map<String, Object> body) {
    // TODO(VHRP): ++streamSeq is consumed before the send is confirmed (see emit()); a failed send
    // leaves a hole in the client's streamSeq sequence -> spurious gap detection. Allocate streamSeq
    // only on confirmed send.
    VhrpEnvelope envelope = new VhrpEnvelope(type.wireValue(), null, ++streamSeq, null, body);
    emit(envelope);
  }

  private void sendReply(VhrpMessageType type, Map<String, Object> body) {
    VhrpEnvelope envelope =
        new VhrpEnvelope(type.wireValue(), null, ++streamSeq, inflightMessageId, body);
    emit(envelope);
  }

  private void sendError(String code, String message, boolean recoverable) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("code", code);
    body.put("message", message);
    body.put("recoverable", recoverable);
    VhrpEnvelope envelope =
        new VhrpEnvelope(
            VhrpMessageType.ERROR.wireValue(), null, ++streamSeq, inflightMessageId, body);
    emit(envelope);
  }

  private void emit(VhrpEnvelope envelope) {
    if (!connection.isOpen()) {
      return;
    }
    // TODO(VHRP): send failure is swallowed (empty failure handler, no log, no recovery). A dropped
    // stateful frame silently desyncs streamSeq/threadRevision. At minimum log the failure; ideally
    // tie streamSeq/revision allocation to send success and trigger resync on failure.
    connection.sendBinary(codec.encode(envelope)).subscribe().with(item -> {}, failure -> {});
  }

  // ---------------------------------------------------------------------------
  // Domain -> wire shape helpers
  // ---------------------------------------------------------------------------

  private Map<String, Object> snapshotItem(RealtimeThread.Item item) {
    // TODO(VHRP): spec requires itemRevision on snapshot items; not emitted here (see sendPatch).
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("id", item.id());
    map.put("type", itemTypeWire(item.type()));
    if (item.role() != null) {
      map.put("role", roleWire(item.role()));
    }
    map.put("status", statusWire(item.status()));
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
    List<Object> parts = new ArrayList<>();
    for (RealtimeThread.ContentPart part : item.content()) {
      parts.add(partWire(part));
    }
    map.put("content", parts);
    return map;
  }

  private Map<String, Object> partWire(RealtimeThread.ContentPart part) {
    Map<String, Object> map = new LinkedHashMap<>();
    if (part instanceof RealtimeThread.TextPart text) {
      map.put("type", "text");
      map.put("isDone", text.isDone());
    } else if (part instanceof RealtimeThread.AudioPart audio) {
      map.put("type", "audio");
      map.put("isDone", audio.isDone());
    } else if (part instanceof RealtimeThread.ImagePart image) {
      map.put("type", "image");
      map.put("imageUrl", image.imageUrl());
      map.put("detail", image.detail());
    }
    return map;
  }

  private Map<String, Object> textOp(
      String op, String itemId, int contentIndex, String key, String value) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("op", op);
    map.put("itemId", itemId);
    map.put("contentIndex", contentIndex);
    map.put(key, value);
    return map;
  }

  private String itemTypeWire(RealtimeThread.ItemType type) {
    return switch (type) {
      case MESSAGE -> "message";
      case FUNCTION_CALL -> "functionCall";
      case FUNCTION_CALL_OUTPUT -> "functionCallOutput";
    };
  }

  private String roleWire(RealtimeThread.Role role) {
    return switch (role) {
      case SYSTEM -> "system";
      case USER -> "user";
      case ASSISTANT -> "assistant";
    };
  }

  private String statusWire(RealtimeThread.Status status) {
    return switch (status) {
      case IN_PROGRESS -> "in_progress";
      case COMPLETED -> "completed";
      case INCOMPLETE -> "incomplete";
    };
  }

  private String fieldWire(RealtimeThread.Field field) {
    return switch (field) {
      case CALL_ID -> "callId";
      case NAME -> "name";
      case ARGUMENTS -> "arguments";
      case OUTPUT -> "output";
      case TOOL_OUTPUT_DISPOSITION -> "toolOutputDisposition";
      case TOOL_ERROR_MESSAGE -> "toolErrorMessage";
    };
  }
}
