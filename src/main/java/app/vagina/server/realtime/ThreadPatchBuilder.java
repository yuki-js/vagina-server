package app.vagina.server.realtime;

import app.vagina.server.realtime.model.RealtimeAdapterModels;
import app.vagina.server.realtime.model.RealtimeThread;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Judgment 4 made concrete: the per-session, stateful mutation sink.
 *
 * <p>The Dart {@code OaiRealtimeAdapter} mutates its {@code RealtimeThread} destructively and then
 * calls {@code _emitThreadUpdate()} to push the <em>whole</em> thread. VHRP instead requires a
 * delta op stream ({@code thread.patch}). This class absorbs that clash exactly where the backend
 * spec says to: it wraps the thread's mutation helpers so that every mutation simultaneously
 *
 * <ol>
 *   <li>updates the canonical {@link RealtimeThread}, and
 *   <li>appends the corresponding op to an outbound op buffer.
 * </ol>
 *
 * <p>Where the Dart code called {@code _emitThreadUpdate()}, the {@code oai/} mirror calls {@link
 * #drainOps()} and the session flushes the buffered ops as one {@code thread.patch} (this builder
 * owns only the op <em>content</em>; the patch carries no revision/sequence — it is a
 * fire-and-forget live delta, recovered on gap by reconnect + full snapshot). One builder belongs
 * to one session, so it is intentionally mutable and not thread-safe; the {@code oai/} body funnels
 * all mutations through this one place.
 *
 * <p>The op wire shapes here mirror the {@code thread.patch.ops} table in {@code
 * 02_vhrp_wire_protocol.md}. PCM bytes never enter an op: assistant audio rides its own {@code
 * assistant.audio.chunk} frame, so {@link #appendAudioChunk} mutates the part without recording an
 * op.
 */
public final class ThreadPatchBuilder {

  private final RealtimeThread thread;

  /** Ops accumulated since the last {@link #drainOps()}; flushed as one {@code thread.patch}. */
  private final List<Map<String, Object>> pendingOps = new ArrayList<>();

  public ThreadPatchBuilder(RealtimeThread thread) {
    this.thread = thread;
  }

  /** The canonical thread this sink mutates; the session reads it to build a snapshot. */
  public RealtimeThread thread() {
    return thread;
  }

  // ---------------------------------------------------------------------------
  // Flush
  // ---------------------------------------------------------------------------

  /** Whether any op has been buffered since the last drain. */
  public boolean hasPendingOps() {
    return !pendingOps.isEmpty();
  }

  /**
   * Returns the buffered ops and clears the buffer. This is the {@code _emitThreadUpdate()} seam:
   * the session wraps the returned list in a {@code thread.patch}. Returns an empty list when there
   * is nothing to flush, so the session can skip emitting an empty patch.
   */
  public List<Map<String, Object>> drainOps() {
    if (pendingOps.isEmpty()) {
      return List.of();
    }
    List<Map<String, Object>> drained = new ArrayList<>(pendingOps);
    pendingOps.clear();
    return drained;
  }

  // ---------------------------------------------------------------------------
  // Item-level mutations
  // ---------------------------------------------------------------------------

  /** Adds a fresh item and records {@code add_item}. */
  public RealtimeThread.Item addItem(
      String id,
      RealtimeThread.ItemType type,
      RealtimeThread.ItemRole role,
      RealtimeThread.ItemStatus status) {
    return addItem(id, type, role, status, RealtimeThread.ItemDisplayState.VISIBLE);
  }

  public RealtimeThread.Item addItem(
      String id,
      RealtimeThread.ItemType type,
      RealtimeThread.ItemRole role,
      RealtimeThread.ItemStatus status,
      RealtimeThread.ItemDisplayState displayState) {
    RealtimeThread.Item item = new RealtimeThread.Item(id, type, role, status);
    item.setDisplayState(displayState);
    thread.addItem(item);
    Map<String, Object> op = newOp("add_item");
    op.put("item", itemShape(item));
    return item;
  }

  /**
   * Find-or-create for a message item, mirroring the Dart {@code _ensureUserMessageItem} / {@code
   * _ensureAssistantMessageItem}: returns the existing item if present, otherwise creates an {@code
   * in_progress} message item and records {@code add_item}.
   */
  public RealtimeThread.Item ensureMessageItem(String id, RealtimeThread.ItemRole role) {
    RealtimeThread.Item existing = thread.findItem(id);
    if (existing != null) {
      return existing;
    }
    return addItem(
        id, RealtimeThread.ItemType.MESSAGE, role, RealtimeThread.ItemStatus.IN_PROGRESS);
  }

  /** Removes an item and records {@code remove_item}; no-op (and no op) when the item is absent. */
  public void removeItem(String itemId) {
    if (thread.removeItem(itemId)) {
      Map<String, Object> op = newOp("remove_item");
      op.put("itemId", itemId);
    }
  }

  /** Sets item status and records {@code set_status}. */
  public void setStatus(RealtimeThread.Item item, RealtimeThread.ItemStatus status) {
    item.setStatus(status);
    Map<String, Object> op = newOp("set_status");
    op.put("itemId", item.id());
    op.put("status", status.wireValue());
  }

  /** Sets item role and records {@code set_role}. */
  public void setRole(RealtimeThread.Item item, RealtimeThread.ItemRole role) {
    item.setRole(role);
    Map<String, Object> op = newOp("set_role");
    op.put("itemId", item.id());
    op.put("role", roleToWire(role));
  }

  /** Sets item display state and records {@code set_field}. */
  public void setDisplayState(
      RealtimeThread.Item item, RealtimeThread.ItemDisplayState displayState) {
    item.setDisplayState(displayState);
    recordSetField(item.id(), "displayState", item.displayState().wireValue());
  }

  // ---------------------------------------------------------------------------
  // Function-call field mutations (all funnel through set_field)
  // ---------------------------------------------------------------------------

  public void setCallId(RealtimeThread.Item item, String callId) {
    item.setCallId(callId);
    recordSetField(item.id(), "callId", callId);
  }

  public void setName(RealtimeThread.Item item, String name) {
    item.setName(name);
    recordSetField(item.id(), "name", name);
  }

  public void setArguments(RealtimeThread.Item item, String arguments) {
    item.setArguments(arguments);
    recordSetField(item.id(), "arguments", arguments);
  }

  public void setOutput(RealtimeThread.Item item, String output) {
    item.setOutput(output);
    recordSetField(item.id(), "output", output);
  }

  public void setToolOutputDisposition(
      RealtimeThread.Item item, RealtimeAdapterModels.ToolOutputDisposition disposition) {
    item.setToolOutputDisposition(disposition);
    recordSetField(item.id(), "toolOutputDisposition", dispositionToWire(disposition));
  }

  public void setToolErrorMessage(RealtimeThread.Item item, String errorMessage) {
    item.setToolErrorMessage(errorMessage);
    recordSetField(item.id(), "toolErrorMessage", errorMessage);
  }

  // ---------------------------------------------------------------------------
  // Content-part mutations
  // ---------------------------------------------------------------------------

  /**
   * Find-or-create a text part (mirrors Dart {@code _findOrCreateTextPart}); records {@code
   * put_part} only when a part is actually created.
   */
  public RealtimeThread.TextPart ensureTextPart(RealtimeThread.Item item, Integer contentIndex) {
    RealtimeThread.TextPart existing = findPart(item, contentIndex, RealtimeThread.TextPart.class);
    if (existing != null) {
      return existing;
    }
    RealtimeThread.TextPart created = new RealtimeThread.TextPart();
    item.putContentPart(created, contentIndex);
    recordPutPart(item.id(), indexOf(item, created), created);
    return created;
  }

  /**
   * Find-or-create an audio part (mirrors Dart {@code _findOrCreateAudioPart}); records {@code
   * put_part} only when a part is actually created.
   */
  public RealtimeThread.AudioPart ensureAudioPart(RealtimeThread.Item item, Integer contentIndex) {
    RealtimeThread.AudioPart existing =
        findPart(item, contentIndex, RealtimeThread.AudioPart.class);
    if (existing != null) {
      return existing;
    }
    RealtimeThread.AudioPart created = new RealtimeThread.AudioPart();
    item.putContentPart(created, contentIndex);
    recordPutPart(item.id(), indexOf(item, created), created);
    return created;
  }

  /** Upserts an image part and records {@code put_part}. */
  public void putImagePart(
      RealtimeThread.Item item, Integer contentIndex, String imageUrl, String detail) {
    RealtimeThread.ImagePart part = new RealtimeThread.ImagePart(imageUrl, detail);
    item.putContentPart(part, contentIndex);
    recordPutPart(item.id(), indexOf(item, part), part);
  }

  /** Appends a text delta and records {@code append_text}. */
  public void appendText(RealtimeThread.Item item, Integer contentIndex, String delta) {
    RealtimeThread.TextPart part = ensureTextPart(item, contentIndex);
    part.appendDelta(delta);
    Map<String, Object> op = newOp("append_text");
    op.put("itemId", item.id());
    op.put("contentIndex", indexOf(item, part));
    op.put("delta", delta);
  }

  /** Replaces a text part's whole text and records {@code replace_text}. */
  public void replaceText(RealtimeThread.Item item, Integer contentIndex, String text) {
    RealtimeThread.TextPart part = ensureTextPart(item, contentIndex);
    part.replaceText(text);
    Map<String, Object> op = newOp("replace_text");
    op.put("itemId", item.id());
    op.put("contentIndex", indexOf(item, part));
    op.put("text", text);
  }

  /** Appends an audio transcript delta and records {@code append_transcript}. */
  public void appendTranscript(RealtimeThread.Item item, Integer contentIndex, String delta) {
    RealtimeThread.AudioPart part = ensureAudioPart(item, contentIndex);
    part.appendTranscriptDelta(delta);
    Map<String, Object> op = newOp("append_transcript");
    op.put("itemId", item.id());
    op.put("contentIndex", indexOf(item, part));
    op.put("delta", delta);
  }

  /** Replaces an audio transcript wholesale and records {@code replace_transcript}. */
  public void replaceTranscript(RealtimeThread.Item item, Integer contentIndex, String text) {
    RealtimeThread.AudioPart part = ensureAudioPart(item, contentIndex);
    part.replaceTranscript(text);
    Map<String, Object> op = newOp("replace_transcript");
    op.put("itemId", item.id());
    op.put("contentIndex", indexOf(item, part));
    op.put("text", text);
  }

  /**
   * Appends assistant PCM to an audio part <em>without</em> recording an op. The bytes ride their
   * own {@code assistant.audio.chunk} frame, never a patch op; only the part's existence (via
   * {@link #ensureAudioPart}) and its transcript are reflected in patches.
   */
  public void appendAudioChunk(RealtimeThread.AudioPart part, byte[] pcm) {
    part.appendAudioDelta(pcm);
  }

  /**
   * Marks the located part done and re-records {@code put_part} so the client observes {@code
   * isDone:true}. Mirrors the Dart adapter calling {@code part.markDone()} on {@code
   * content_part.done} / {@code output_text.done} / {@code output_audio.done}: the only wire shape
   * carrying {@code isDone} is {@code put_part}, so a done transition re-upserts the same part.
   * With a {@code null} index the last part of any kind is used, matching {@link #findPart}
   * semantics for an unspecified index. No-op (and no op) when no part is located.
   */
  public void markPartDone(RealtimeThread.Item item, Integer contentIndex) {
    RealtimeThread.ContentPart part = locatePart(item, contentIndex);
    if (part == null) {
      return;
    }
    part.markDone();
    recordPutPart(item.id(), indexOf(item, part), part);
  }

  /** Locates a part by explicit index, or the last part when {@code contentIndex} is null. */
  private RealtimeThread.ContentPart locatePart(RealtimeThread.Item item, Integer contentIndex) {
    if (contentIndex != null) {
      return item.findContentPart(contentIndex);
    }
    List<RealtimeThread.ContentPart> content = item.content();
    return content.isEmpty() ? null : content.get(content.size() - 1);
  }

  // ---------------------------------------------------------------------------
  // Thread-level mutation
  // ---------------------------------------------------------------------------

  /** Sets the thread conversation id and records {@code set_conversation_id}. */
  public void setConversationId(String conversationId) {
    thread.setConversationId(conversationId);
    Map<String, Object> op = newOp("set_conversation_id");
    op.put("conversationId", conversationId);
  }

  // ---------------------------------------------------------------------------
  // Op buffer plumbing
  // ---------------------------------------------------------------------------

  /**
   * Creates an op map seeded with {@code op}, registers it in the buffer, and returns it for the
   * caller to enrich with op-specific fields. Because the same reference is buffered, post-return
   * {@code put}s are visible at drain time.
   */
  private Map<String, Object> newOp(String opName) {
    Map<String, Object> op = new LinkedHashMap<>();
    op.put("op", opName);
    pendingOps.add(op);
    return op;
  }

  private void recordSetField(String itemId, String field, Object value) {
    Map<String, Object> op = newOp("set_field");
    op.put("itemId", itemId);
    op.put("field", field);
    op.put("value", value);
  }

  private void recordPutPart(String itemId, int contentIndex, RealtimeThread.ContentPart part) {
    Map<String, Object> op = newOp("put_part");
    op.put("itemId", itemId);
    op.put("contentIndex", contentIndex);
    op.put("part", partShape(part));
  }

  // ---------------------------------------------------------------------------
  // Locators / token mapping
  // ---------------------------------------------------------------------------

  /**
   * Mirrors the Dart {@code _findContentPartOfType}: at an explicit {@code contentIndex} the part
   * there must be of {@code type}; with a {@code null} index the last part of {@code type} wins.
   */
  private <T extends RealtimeThread.ContentPart> T findPart(
      RealtimeThread.Item item, Integer contentIndex, Class<T> type) {
    if (contentIndex != null) {
      RealtimeThread.ContentPart at = item.findContentPart(contentIndex);
      return type.isInstance(at) ? type.cast(at) : null;
    }
    List<RealtimeThread.ContentPart> content = item.content();
    for (int i = content.size() - 1; i >= 0; i--) {
      RealtimeThread.ContentPart candidate = content.get(i);
      if (type.isInstance(candidate)) {
        return type.cast(candidate);
      }
    }
    return null;
  }

  private static int indexOf(RealtimeThread.Item item, RealtimeThread.ContentPart part) {
    return item.content().indexOf(part);
  }

  private static String roleToWire(RealtimeThread.ItemRole role) {
    if (role == null) {
      return null;
    }
    return switch (role) {
      case SYSTEM -> "system";
      case USER -> "user";
      case ASSISTANT -> "assistant";
    };
  }

  private static String dispositionToWire(RealtimeAdapterModels.ToolOutputDisposition disposition) {
    if (disposition == null) {
      return null;
    }
    return disposition == RealtimeAdapterModels.ToolOutputDisposition.ERROR ? "error" : "success";
  }

  private static String itemTypeToWire(RealtimeThread.ItemType type) {
    return switch (type) {
      case MESSAGE -> "message";
      case FUNCTION_CALL -> "functionCall";
      case FUNCTION_CALL_OUTPUT -> "functionCallOutput";
    };
  }

  // ---------------------------------------------------------------------------
  // Canonical item⇄wire projection
  //
  // The single projection shared by three call sites: add_item (op.item),
  // put_part (op.part), and snapshot (items[]). Keeping all three on one code
  // path is what guarantees a resync snapshot and the incremental patches agree
  // on shape. Part shapes follow 02_vhrp_wire_protocol.md verbatim.
  // ---------------------------------------------------------------------------

  /**
   * Projects one item to its {@code add_item.item} / snapshot wire map.
   *
   * <p>Only fields that are set appear, so a plain message item stays small and a functionCall /
   * functionCallOutput item carries its callId/name/arguments/output and tool dispositions. The
   * content parts are projected via {@link #partShape}, so an item shape embeds the same part
   * shapes a standalone {@code put_part} would emit.
   */
  static Map<String, Object> itemShape(RealtimeThread.Item item) {
    Map<String, Object> shape = new LinkedHashMap<>();
    shape.put("id", item.id());
    shape.put("type", itemTypeToWire(item.type()));
    putIfPresent(shape, "role", roleToWire(item.role()));
    shape.put("status", item.status().wireValue());
    shape.put("displayState", item.displayState().wireValue());
    putIfPresent(shape, "callId", item.callId());
    putIfPresent(shape, "name", item.name());
    putIfPresent(shape, "arguments", item.arguments());
    putIfPresent(shape, "output", item.output());
    putIfPresent(shape, "toolOutputDisposition", dispositionToWire(item.toolOutputDisposition()));
    putIfPresent(shape, "toolErrorMessage", item.toolErrorMessage());

    List<Map<String, Object>> parts = new ArrayList<>();
    for (RealtimeThread.ContentPart part : item.content()) {
      parts.add(partShape(part));
    }
    shape.put("content", parts);
    return shape;
  }

  /**
   * Projects one content part to its {@code put_part.part} / snapshot wire map, per the part shapes
   * in {@code 02_vhrp_wire_protocol.md}:
   *
   * <pre>
   * text:  { "type":"text",  "isDone": bool, "text": ... }
   * audio: { "type":"audio", "isDone": bool }     (transcript carried when present)
   * image: { "type":"image", "imageUrl":..., "detail":... }
   * </pre>
   *
   * <p>Per the snapshot rule, an audio part carries only its transcript; PCM (its {@code
   * audioChunks}) never enters a patch op or a snapshot — assistant audio rides {@code
   * assistant.audio.chunk}. So this projection deliberately omits {@code audioChunks} entirely.
   */
  static Map<String, Object> partShape(RealtimeThread.ContentPart part) {
    Map<String, Object> shape = new LinkedHashMap<>();
    shape.put("type", part.type());
    return switch (part) {
      case RealtimeThread.TextPart text -> {
        shape.put("isDone", part.isDone());
        shape.put("text", text.text());
        yield shape;
      }
      case RealtimeThread.AudioPart audio -> {
        shape.put("isDone", part.isDone());
        // transcript only; audioChunks stay out of the wire shape by design.
        putIfPresent(shape, "transcript", audio.transcript());
        yield shape;
      }
      case RealtimeThread.ImagePart image -> {
        shape.put("imageUrl", image.imageUrl());
        shape.put("detail", image.detail());
        yield shape;
      }
    };
  }

  /**
   * The shared snapshot projector referenced by {@link VhrpSession#buildSnapshot()}: serializes the
   * whole canonical thread's items to wire maps, consistent with the per-op shapes above. Audio
   * parts carry transcript only with empty {@code audioChunks}, per the snapshot rule.
   */
  static List<Map<String, Object>> snapshotItems(RealtimeThread thread) {
    List<Map<String, Object>> items = new ArrayList<>();
    for (RealtimeThread.Item item : thread.items()) {
      items.add(itemShape(item));
    }
    return items;
  }

  private static void putIfPresent(Map<String, Object> shape, String field, Object value) {
    if (value != null) {
      shape.put(field, value);
    }
  }
}
