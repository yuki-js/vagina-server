package app.vagina.server.realtime.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Semi-mutable realtime conversation thread, the server mirror of the Dart {@code
 * realtime_thread.dart}.
 *
 * <p>It is intentionally mutable to support efficient delta accumulation: the {@code oai/}
 * translation body drives the mutation helpers as vendor events arrive, and the session reads the
 * resulting state to build VHRP {@code thread.patch}/{@code thread.snapshot}. Item and part, the
 * three part kinds, and the three enums are aggregated here per judgment 8.
 *
 * <p>Threading note: a single session mutates its thread from the serial inbound path and from
 * adapter callbacks; the lists are plain {@link ArrayList}s, so the session is responsible for not
 * mutating concurrently (the {@code oai/} body funnels mutations through one place).
 */
public final class RealtimeThread {

  /** Item kinds; mirrors Dart {@code RealtimeThreadItemType}. Only these three exist on the wire. */
  public enum ItemType {
    MESSAGE,
    FUNCTION_CALL,
    FUNCTION_CALL_OUTPUT
  }

  /** Item roles; mirrors Dart {@code RealtimeThreadItemRole}. */
  public enum ItemRole {
    SYSTEM,
    USER,
    ASSISTANT
  }

  /** UI projection state. Keeps the canonical item in the thread while controlling chat visibility. */
  public enum ItemDisplayState {
    VISIBLE("visible"),
    PENDING("pending"),
    HIDDEN("hidden");

    private final String wireValue;

    ItemDisplayState(String wireValue) {
      this.wireValue = wireValue;
    }

    public String wireValue() {
      return wireValue;
    }

    public static ItemDisplayState fromWire(String value) {
      return switch (value) {
        case "pending" -> PENDING;
        case "hidden" -> HIDDEN;
        default -> VISIBLE;
      };
    }
  }

  /**
   * Item status; mirrors Dart {@code RealtimeThreadItemStatus} including the wire token. Progression
   * is one-way: {@code IN_PROGRESS -> COMPLETED} or {@code IN_PROGRESS -> INCOMPLETE}.
   */
  public enum ItemStatus {
    IN_PROGRESS("in_progress"),
    COMPLETED("completed"),
    INCOMPLETE("incomplete");

    private final String wireValue;

    ItemStatus(String wireValue) {
      this.wireValue = wireValue;
    }

    public String wireValue() {
      return wireValue;
    }

    public static ItemStatus fromWire(String value) {
      return switch (value) {
        case "completed" -> COMPLETED;
        case "incomplete" -> INCOMPLETE;
        default -> IN_PROGRESS;
      };
    }
  }

  // ---------------------------------------------------------------------------
  // Content parts
  // ---------------------------------------------------------------------------

  /** Base of the three content part kinds. {@code done} marks a part as stable. */
  public abstract static sealed class ContentPart
      permits TextPart, AudioPart, ImagePart {
    private final String type;
    private boolean done;

    protected ContentPart(String type, boolean done) {
      this.type = type;
      this.done = done;
    }

    public String type() {
      return type;
    }

    public boolean isDone() {
      return done;
    }

    public void markDone() {
      this.done = true;
    }
  }

  /** Text part; accumulates deltas. Mirrors Dart {@code RealtimeThreadTextPart}. */
  public static final class TextPart extends ContentPart {
    private String text;

    public TextPart() {
      this("", false);
    }

    public TextPart(String text, boolean done) {
      super("text", done);
      this.text = text;
    }

    public String text() {
      return text;
    }

    public void appendDelta(String delta) {
      this.text += delta;
    }

    public void replaceText(String value) {
      this.text = value;
    }
  }

  /**
   * Audio part; holds PCM chunks plus an optional transcript. Mirrors Dart {@code
   * RealtimeThreadAudioPart}. A snapshot may carry transcript only with no chunks.
   */
  public static final class AudioPart extends ContentPart {
    private final List<byte[]> audioChunks = new ArrayList<>();
    private String transcript;

    public AudioPart() {
      super("audio", false);
    }

    public List<byte[]> audioChunks() {
      return audioChunks;
    }

    public String transcript() {
      return transcript;
    }

    public void appendAudioDelta(byte[] chunk) {
      audioChunks.add(chunk);
    }

    public void replaceAudio(byte[] chunk) {
      audioChunks.clear();
      audioChunks.add(chunk);
    }

    public void appendTranscriptDelta(String delta) {
      this.transcript = (transcript == null ? "" : transcript) + delta;
    }

    public void replaceTranscript(String value) {
      this.transcript = value;
    }
  }

  /**
   * Image part; holds a stable asset URL and detail. Mirrors Dart {@code RealtimeThreadImagePart}.
   * Raw bytes are not retained here; the backend turns submitted bytes into an asset URL.
   */
  public static final class ImagePart extends ContentPart {
    private final String imageUrl;
    private final String detail;

    public ImagePart(String imageUrl, String detail) {
      super("image", true);
      this.imageUrl = imageUrl;
      this.detail = detail;
    }

    public String imageUrl() {
      return imageUrl;
    }

    public String detail() {
      return detail;
    }
  }

  // ---------------------------------------------------------------------------
  // Item
  // ---------------------------------------------------------------------------

  /** One thread item. Mirrors Dart {@code RealtimeThreadItem}; fields are semi-mutable for deltas. */
  public static final class Item {
    private final String id;
    private final ItemType type;
    private ItemRole role;
    private ItemStatus status;
    private ItemDisplayState displayState = ItemDisplayState.VISIBLE;
    private final List<ContentPart> content = new ArrayList<>();
    private String callId;
    private String name;
    private String arguments;
    private String output;
    private RealtimeAdapterModels.ToolOutputDisposition toolOutputDisposition;
    private String toolErrorMessage;

    public Item(String id, ItemType type, ItemRole role, ItemStatus status) {
      this.id = id;
      this.type = type;
      this.role = role;
      this.status = status == null ? ItemStatus.IN_PROGRESS : status;
    }

    public String id() {
      return id;
    }

    public ItemType type() {
      return type;
    }

    public ItemRole role() {
      return role;
    }

    public void setRole(ItemRole role) {
      this.role = role;
    }

    public ItemStatus status() {
      return status;
    }

    public void setStatus(ItemStatus status) {
      this.status = status;
    }

    public ItemDisplayState displayState() {
      return displayState;
    }

    public void setDisplayState(ItemDisplayState displayState) {
      this.displayState = displayState == null ? ItemDisplayState.VISIBLE : displayState;
    }

    public List<ContentPart> content() {
      return content;
    }

    public String callId() {
      return callId;
    }

    public void setCallId(String callId) {
      this.callId = callId;
    }

    public String name() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public String arguments() {
      return arguments;
    }

    public void setArguments(String arguments) {
      this.arguments = arguments;
    }

    public String output() {
      return output;
    }

    public void setOutput(String output) {
      this.output = output;
    }

    public RealtimeAdapterModels.ToolOutputDisposition toolOutputDisposition() {
      return toolOutputDisposition;
    }

    public void setToolOutputDisposition(RealtimeAdapterModels.ToolOutputDisposition value) {
      this.toolOutputDisposition = value;
    }

    public String toolErrorMessage() {
      return toolErrorMessage;
    }

    public void setToolErrorMessage(String value) {
      this.toolErrorMessage = value;
    }

    public boolean isDone() {
      return status == ItemStatus.COMPLETED;
    }

    /** Returns the part at {@code contentIndex}, or {@code null} if out of range. */
    public ContentPart findContentPart(int contentIndex) {
      if (contentIndex < 0 || contentIndex >= content.size()) {
        return null;
      }
      return content.get(contentIndex);
    }

    /** Upserts {@code part} at {@code contentIndex}; appends when the index is absent/out of range. */
    public void putContentPart(ContentPart part, Integer contentIndex) {
      if (contentIndex == null || contentIndex < 0 || contentIndex >= content.size()) {
        content.add(part);
        return;
      }
      content.set(contentIndex, part);
    }

    public void markDone() {
      this.status = ItemStatus.COMPLETED;
      for (ContentPart part : content) {
        part.markDone();
      }
    }

    public void markIncomplete() {
      this.status = ItemStatus.INCOMPLETE;
    }
  }

  // ---------------------------------------------------------------------------
  // Thread
  // ---------------------------------------------------------------------------

  private final String id;
  private String conversationId;
  private final List<Item> items = new ArrayList<>();

  public RealtimeThread(String id) {
    this.id = id;
  }

  public String id() {
    return id;
  }

  public String conversationId() {
    return conversationId;
  }

  public void setConversationId(String conversationId) {
    this.conversationId = conversationId;
  }

  public List<Item> items() {
    return items;
  }

  /** Returns the item with {@code itemId}, or {@code null} if none exists. */
  public Item findItem(String itemId) {
    for (Item item : items) {
      if (item.id().equals(itemId)) {
        return item;
      }
    }
    return null;
  }

  public void addItem(Item item) {
    items.add(item);
  }

  /** Removes the item with {@code itemId}; returns whether anything was removed. */
  public boolean removeItem(String itemId) {
    return items.removeIf(item -> item.id().equals(itemId));
  }
}
