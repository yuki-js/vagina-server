





/**
 * Server-side canonical conversation thread for a hosted realtime session.
 *
 * <p>This is pure domain state: the accumulated conversation the usecase owns and mutates as the
 * model produces output. It is a faithful port of the client's {@code RealtimeThread} model, so the
 * two projections stay structurally aligned, but it knows nothing about VHRP, CBOR, {@code
 * streamSeq}, or revisions — framing those mutations onto the wire is the support layer's job.
 *
 * <p>It is intentionally a single aggregate file with nested item/part/enacted-field types rather
 * than a cluster of tiny standalone files, mirroring how {@code RealtimeEntities} groups the small
 * realtime value types. The thread and its items/parts are deliberately mutable to support
 * efficient delta accumulation (append/replace), exactly like the client model.
 *
 * <p>One intentional difference from the client model: an {@link AudioPart} here carries only the
 * transcript, not base64 audio chunks. Assistant PCM travels on the {@code assistant.audio.chunk}
 * stream, and snapshots are not required to reproduce historical waveforms, so the canonical thread
 * keeps meaning (transcript) rather than bytes.
 */
public final class RealtimeThread {

  /** The kind of a thread item. */
  public enum ItemType {
    MESSAGE,
    FUNCTION_CALL,
    FUNCTION_CALL_OUTPUT
  }

  /** Who authored an item. */
  public enum Role {
    SYSTEM,
    USER,
    ASSISTANT
  }

  /** Lifecycle status of an item. */
  public enum Status {
    IN_PROGRESS,
    COMPLETED,
    INCOMPLETE
  }

  /**
   * Settable scalar fields of an item, used by the usecase to express a single field mutation that
   * the support layer frames as a {@code set_field} op.
   */
  public enum Field {
    CALL_ID,
    NAME,
    ARGUMENTS,
    OUTPUT,
    TOOL_OUTPUT_DISPOSITION,
    TOOL_ERROR_MESSAGE
  }

  private final String id;
  private String conversationId;
  private final List<Item> items = new ArrayList<>();

  public RealtimeThread(String id, String conversationId) {
    this.id = id;
    this.conversationId = conversationId;
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

  public boolean removeItem(String itemId) {
    return items.removeIf(item -> item.id().equals(itemId));
  }

  /** A single conversation item (message, function call, or function-call output). */
  public static final class Item {
    private final String id;
    private final ItemType type;
    private Role role;
    private Status status;
    private final List<ContentPart> content = new ArrayList<>();
    private String callId;
    private String name;
    private String arguments;
    private String output;
    private RealtimeToolDisposition toolOutputDisposition;
    private String toolErrorMessage;

    public Item(String id, ItemType type, Role role, Status status) {
      this.id = id;
      this.type = type;
      this.role = role;
      this.status = status;
    }

    public String id() {
      return id;
    }

    public ItemType type() {
      return type;
    }

    public Role role() {
      return role;
    }

    public void setRole(Role role) {
      this.role = role;
    }

    public Status status() {
      return status;
    }

    public void setStatus(Status status) {
      this.status = status;
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

    public RealtimeToolDisposition toolOutputDisposition() {
      return toolOutputDisposition;
    }

    public void setToolOutputDisposition(RealtimeToolDisposition toolOutputDisposition) {
      this.toolOutputDisposition = toolOutputDisposition;
    }

    public String toolErrorMessage() {
      return toolErrorMessage;
    }

    public void setToolErrorMessage(String toolErrorMessage) {
      this.toolErrorMessage = toolErrorMessage;
    }
  }

  /** A content part of a message item. Sealed over the three supported part kinds. */
  public abstract static sealed class ContentPart permits TextPart, AudioPart, ImagePart {
    private boolean done;

    protected ContentPart(boolean done) {
      this.done = done;
    }

    public boolean isDone() {
      return done;
    }

    public void markDone() {
      this.done = true;
    }
  }

  /** A text content part that accumulates via append or replace. */
  public static final class TextPart extends ContentPart {
    private String text;

    public TextPart(String text, boolean done) {
      super(done);
      this.text = text == null ? "" : text;
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
   * An audio content part. Server-side it holds only the transcript; PCM rides the assistant audio
   * stream and is not retained here.
   */
  public static final class AudioPart extends ContentPart {
    private String transcript;

    public AudioPart(String transcript, boolean done) {
      super(done);
      this.transcript = transcript;
    }

    public String transcript() {
      return transcript;
    }

    public void appendTranscriptDelta(String delta) {
      this.transcript = (transcript == null ? "" : transcript) + delta;
    }

    public void replaceTranscript(String value) {
      this.transcript = value;
    }
  }

  /** An image content part referencing a stored asset URL. */
  public static final class ImagePart extends ContentPart {
    private final String imageUrl;
    private final String detail;

    public ImagePart(String imageUrl, String detail) {
      super(true);
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
}
