package app.vagina.server.realtime;

import app.vagina.server.realtime.model.RealtimeAdapterModels.AssistantAudioFrame;
import app.vagina.server.realtime.model.RealtimeAdapterModels.AudioTurnMode;
import app.vagina.server.realtime.model.RealtimeAdapterModels.ConnectionState;
import app.vagina.server.realtime.model.RealtimeAdapterModels.Error;
import app.vagina.server.realtime.model.RealtimeAdapterModels.ThreadPatchOps;
import app.vagina.server.realtime.model.RealtimeAdapterModels.ToolDefinition;
import app.vagina.server.realtime.model.RealtimeAdapterModels.ToolOutputDisposition;
import app.vagina.server.realtime.model.RealtimeThread;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.subscription.MultiEmitter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Controllable {@link RealtimeAdapter} stub used by the VHRP composite E2E tests.
 *
 * <p>Instances are created per VHRP session (not CDI-managed) by {@link
 * FakeRealtimeAdapterFactory}. The test retrieves the current instance from the factory and drives
 * it to simulate assistant responses without any real vendor connection.
 *
 * <p>Design: each observable stream is backed by a {@link MultiEmitter} stored via {@link
 * AtomicReference}. The emitter is wired when {@link VhrpSession} subscribes to the respective
 * {@link Multi} during {@link VhrpSession#subscribeAdapterOutput()} — which happens synchronously
 * in the {@code VhrpSession} constructor. After the session is created the test can push patches /
 * errors at will via {@link #emitPatch(List)} / {@link #emitError(String, String)}.
 */
public final class FakeRealtimeAdapter implements RealtimeAdapter {

  // --- controllable emitters ---

  private final AtomicReference<MultiEmitter<ThreadPatchOps>> patchEmitter =
      new AtomicReference<>();
  private final AtomicReference<MultiEmitter<AssistantAudioFrame>> audioEmitter =
      new AtomicReference<>();
  private final AtomicReference<MultiEmitter<AssistantAudioFrame>> audioDoneEmitter =
      new AtomicReference<>();
  private final AtomicReference<MultiEmitter<Boolean>> vadEmitter = new AtomicReference<>();
  private final AtomicReference<MultiEmitter<Error>> errorEmitter = new AtomicReference<>();

  // --- observables wired to the emitters ---
  // Note: Mutiny's emitter() consumer receives MultiEmitter<? super T>; we cast to exact type
  // because we store the emitter in a typed AtomicReference and emit only exact-T items.

  @SuppressWarnings("unchecked")
  private final Multi<ThreadPatchOps> patches =
      Multi.createFrom()
          .<ThreadPatchOps>emitter(e -> patchEmitter.set((MultiEmitter<ThreadPatchOps>) e));

  @SuppressWarnings("unchecked")
  private final Multi<AssistantAudioFrame> audioStream =
      Multi.createFrom()
          .<AssistantAudioFrame>emitter(
              e -> audioEmitter.set((MultiEmitter<AssistantAudioFrame>) e));

  @SuppressWarnings("unchecked")
  private final Multi<AssistantAudioFrame> audioDone =
      Multi.createFrom()
          .<AssistantAudioFrame>emitter(
              e -> audioDoneEmitter.set((MultiEmitter<AssistantAudioFrame>) e));

  @SuppressWarnings("unchecked")
  private final Multi<Boolean> vadUpdates =
      Multi.createFrom().<Boolean>emitter(e -> vadEmitter.set((MultiEmitter<Boolean>) e));

  @SuppressWarnings("unchecked")
  private final Multi<Error> errors =
      Multi.createFrom().<Error>emitter(e -> errorEmitter.set((MultiEmitter<Error>) e));

  // --- canonical thread (minimal, used only for snapshot) ---

  private final RealtimeThread thread;

  // --- interaction log (for test assertions) ---

  private final CopyOnWriteArrayList<String> sentTexts = new CopyOnWriteArrayList<>();
  private final CopyOnWriteArrayList<ToolResult> sentToolResults = new CopyOnWriteArrayList<>();
  private final AtomicBoolean disposed = new AtomicBoolean(false);
  private volatile String conversationId;

  /**
   * Immutable record of a single {@link #sendFunctionOutput} call, keyed by {@code callId}. Used by
   * E2E tests to assert that tool results sent by the client reached the adapter.
   */
  public record ToolResult(
      String callId, String output, ToolOutputDisposition disposition, String errorMessage) {}

  public FakeRealtimeAdapter(String threadId) {
    this.thread = new RealtimeThread(threadId);
  }

  // =========================================================================
  // Test control API
  // =========================================================================

  /**
   * Push a batch of patch ops to the session, which wraps them in a {@code thread.patch} frame and
   * sends it to the connected WebSocket client.
   */
  public void emitPatch(List<Map<String, Object>> ops) {
    MultiEmitter<ThreadPatchOps> e = patchEmitter.get();
    if (e != null) {
      e.emit(new ThreadPatchOps(ops));
    }
  }

  /** Push a recoverable error to the session. */
  public void emitError(String code, String message) {
    MultiEmitter<Error> e = errorEmitter.get();
    if (e != null) {
      e.emit(new Error(code, message, null));
    }
  }

  /**
   * Simulate a complete text response: add_item + put_part + append_text + set_status=completed.
   */
  public void emitTextResponse(String itemId, String text) {
    Map<String, Object> addItem = new LinkedHashMap<>();
    addItem.put("op", "add_item");
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("id", itemId);
    item.put("type", "message");
    item.put("role", "assistant");
    item.put("status", "in_progress");
    item.put("content", List.of());
    addItem.put("item", item);

    Map<String, Object> putPart = new LinkedHashMap<>();
    putPart.put("op", "put_part");
    putPart.put("itemId", itemId);
    putPart.put("contentIndex", 0);
    Map<String, Object> part = new LinkedHashMap<>();
    part.put("type", "text");
    part.put("isDone", false);
    putPart.put("part", part);

    Map<String, Object> appendText = new LinkedHashMap<>();
    appendText.put("op", "append_text");
    appendText.put("itemId", itemId);
    appendText.put("contentIndex", 0);
    appendText.put("delta", text);

    Map<String, Object> setStatus = new LinkedHashMap<>();
    setStatus.put("op", "set_status");
    setStatus.put("itemId", itemId);
    setStatus.put("status", "completed");

    emitPatch(List.of(addItem, putPart, appendText, setStatus));
  }

  /** Returns whether {@link #dispose()} has been called. */
  public boolean isDisposed() {
    return disposed.get();
  }

  /** Returns the texts sent via {@link #sendText(String)} for test assertions. */
  public List<String> sentTexts() {
    return new ArrayList<>(sentTexts);
  }

  /** Returns tool-result calls received via {@link #sendFunctionOutput} for test assertions. */
  public List<ToolResult> sentToolResults() {
    return new ArrayList<>(sentToolResults);
  }

  /**
   * Emit a function-call patch that represents the AI requesting a tool invocation.
   *
   * <p>Wire shape: {@code add_item}(type=functionCall) + {@code set_field} for each of {@code
   * callId}, {@code name}, {@code arguments} + {@code set_status}(requires_action). The {@code
   * callId} is the primary key that the client echoes back in {@code tool.result.submit}.
   */
  public void emitFunctionCall(String itemId, String callId, String name, String arguments) {
    Map<String, Object> addItem = new LinkedHashMap<>();
    addItem.put("op", "add_item");
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("id", itemId);
    item.put("type", "functionCall");
    item.put("status", "in_progress");
    addItem.put("item", item);

    Map<String, Object> setCallId = new LinkedHashMap<>();
    setCallId.put("op", "set_field");
    setCallId.put("itemId", itemId);
    setCallId.put("field", "callId");
    setCallId.put("value", callId);

    Map<String, Object> setName = new LinkedHashMap<>();
    setName.put("op", "set_field");
    setName.put("itemId", itemId);
    setName.put("field", "name");
    setName.put("value", name);

    Map<String, Object> setArgs = new LinkedHashMap<>();
    setArgs.put("op", "set_field");
    setArgs.put("itemId", itemId);
    setArgs.put("field", "arguments");
    setArgs.put("value", arguments);

    Map<String, Object> setStatus = new LinkedHashMap<>();
    setStatus.put("op", "set_status");
    setStatus.put("itemId", itemId);
    setStatus.put("status", "requires_action");

    emitPatch(List.of(addItem, setCallId, setName, setArgs, setStatus));
  }

  /** Complete all outbound streams (idempotent). */
  public void completeAll() {
    complete(patchEmitter);
    complete(audioEmitter);
    complete(audioDoneEmitter);
    complete(vadEmitter);
    complete(errorEmitter);
  }

  private static <T> void complete(AtomicReference<MultiEmitter<T>> ref) {
    MultiEmitter<T> e = ref.get();
    if (e != null) {
      e.complete();
    }
  }

  // =========================================================================
  // RealtimeAdapter interface
  // =========================================================================

  @Override
  public Uni<Void> connect(String voice, String instructions) {
    return Uni.createFrom().voidItem();
  }

  @Override
  public Uni<Void> dispose() {
    disposed.set(true);
    completeAll();
    return Uni.createFrom().voidItem();
  }

  @Override
  public ConnectionState connectionState() {
    return ConnectionState.connected();
  }

  @Override
  public Multi<ConnectionState> connectionStateUpdates() {
    return Multi.createFrom().empty();
  }

  @Override
  public Multi<Error> errors() {
    return errors;
  }

  @Override
  public RealtimeThread thread() {
    return thread;
  }

  @Override
  public Multi<ThreadPatchOps> threadPatches() {
    return patches;
  }

  @Override
  public String conversationId() {
    return conversationId;
  }

  @Override
  public Set<String> supportedExtensions() {
    return Set.of();
  }

  @Override
  public void pushLiveAudioChunk(byte[] pcm) {
    // no-op in fake
  }

  @Override
  public Uni<Void> setAudioTurnMode(AudioTurnMode mode) {
    return Uni.createFrom().voidItem();
  }

  @Override
  public Multi<AssistantAudioFrame> assistantAudioStream() {
    return audioStream;
  }

  @Override
  public Multi<AssistantAudioFrame> assistantAudioCompleted() {
    return audioDone;
  }

  @Override
  public boolean isUserSpeaking() {
    return false;
  }

  @Override
  public Multi<Boolean> isUserSpeakingUpdates() {
    return vadUpdates;
  }

  @Override
  public Uni<Void> registerTools(List<ToolDefinition> tools) {
    return Uni.createFrom().voidItem();
  }

  @Override
  public Uni<Void> setInstructions(String instructions) {
    return Uni.createFrom().voidItem();
  }

  @Override
  public Uni<Boolean> applyProviderExtension(String extensionType, Map<String, Object> payload) {
    return Uni.createFrom().item(false);
  }

  @Override
  public Uni<String> sendAudioOneShot(byte[] audioBytes) {
    return Uni.createFrom().item("fake-audio-" + System.nanoTime());
  }

  @Override
  public Uni<String> sendText(String text) {
    sentTexts.add(text);
    return Uni.createFrom().item("fake-text-" + System.nanoTime());
  }

  @Override
  public Uni<String> sendImage(byte[] imageBytes) {
    return Uni.createFrom().item("fake-image-" + System.nanoTime());
  }

  @Override
  public Uni<String> sendFunctionOutput(
      String callId, String output, ToolOutputDisposition disposition, String errorMessage) {
    sentToolResults.add(new ToolResult(callId, output, disposition, errorMessage));
    return Uni.createFrom().item("fake-output-" + System.nanoTime());
  }

  @Override
  public Uni<Void> interrupt() {
    return Uni.createFrom().voidItem();
  }
}
