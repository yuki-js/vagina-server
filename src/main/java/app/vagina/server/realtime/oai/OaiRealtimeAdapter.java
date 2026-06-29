package app.vagina.server.realtime.oai;

import app.vagina.server.realtime.RealtimeAdapter;
import app.vagina.server.realtime.RealtimeModelsConfig;
import app.vagina.server.realtime.ThreadPatchBuilder;
import app.vagina.server.realtime.model.RealtimeAdapterModels;
import app.vagina.server.realtime.model.RealtimeThread;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import io.smallrye.mutiny.subscription.Cancellable;
import io.vertx.mutiny.core.Vertx;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * OpenAI Realtime implementation of {@link RealtimeAdapter}: the server-side transcription of the
 * Dart {@code oai/realtime_adapter.dart}.
 *
 * <p>It owns all OAI-specific defaults (audio format, VAD, transcription model) and the full
 * translation between OpenAI events and a canonical {@link RealtimeThread}. The single structural
 * difference from the Dart original is judgment 4: where Dart mutated the thread and called {@code
 * _emitThreadUpdate()} to push the whole thread, here every mutation goes through a {@link
 * ThreadPatchBuilder} and {@link #flush()} drains the buffered ops into a {@link
 * RealtimeAdapterModels.ThreadPatchOps} on {@link #threadPatches()}, which the session writes as
 * one live {@code thread.patch}. There is no revision counter: a patch is a fire-and-forget live
 * delta, and any delivery gap is recovered by reconnect + a fresh full {@code thread.snapshot}
 * built from the canonical thread.
 *
 * <p>Assistant PCM does not ride a patch op: the audio bytes are base64-decoded from the OAI delta
 * (this downstream leg still uses base64) and pushed on {@link #assistantAudioStream()} as a {@link
 * RealtimeAdapterModels.AssistantAudioFrame}; the session frames those as VHRP {@code
 * assistant.audio.chunk} {@code bstr}s, so no base64 appears on the VHRP side.
 */
public final class OaiRealtimeAdapter implements RealtimeAdapter {

  // Provider-extension keys, mirroring the Dart RealtimeProviderExtensions. voice_selection is
  // intentionally absent (v1 unsupported, per audit point C), so it is not advertised either.
  private static final String EXT_INPUT_NOISE_REDUCTION = "session.input_noise_reduction_selection";
  private static final String EXT_REASONING_EFFORT = "session.reasoning_effort_selection";
  private static final String EXT_TOOL_CHOICE_REQUIRED = "session.tool_choice_required";
  private static final String EXT_SELECTION_KEY = "selection";
  private static final String EXT_REQUIRED_KEY = "required";

  private static final String DEFAULT_TRANSCRIPTION_MODEL = "gpt-4o-mini-transcribe";
  private static final String INTERRUPTED_TOOL_ERROR_MESSAGE =
      "Tool call cancelled by user interrupt.";
  private static final String INTERRUPTED_TOOL_ERROR_OUTPUT =
      "{\"error\":\"Tool call cancelled by user interrupt.\"}";

  private enum NoiseReduction {
    OFF,
    NEAR_FIELD,
    FAR_FIELD;

    static NoiseReduction fromPayload(Object value) {
      return switch (String.valueOf(value)) {
        case "off" -> OFF;
        case "nearField" -> NEAR_FIELD;
        case "farField" -> FAR_FIELD;
        default -> null;
      };
    }
  }

  private final String modelId;
  private final RealtimeModelsConfig.ModelConfig modelConfig;
  private final OaiRealtimeClient client;

  private final RealtimeThread thread;
  private final ThreadPatchBuilder patch;

  private final BroadcastProcessor<RealtimeAdapterModels.ThreadPatchOps> threadPatchBus =
      BroadcastProcessor.create();
  private final BroadcastProcessor<RealtimeAdapterModels.ConnectionState> connectionBus =
      BroadcastProcessor.create();
  private final BroadcastProcessor<RealtimeAdapterModels.Error> errorBus =
      BroadcastProcessor.create();
  private final BroadcastProcessor<RealtimeAdapterModels.AssistantAudioFrame> audioBus =
      BroadcastProcessor.create();
  private final BroadcastProcessor<RealtimeAdapterModels.AssistantAudioFrame> audioDoneBus =
      BroadcastProcessor.create();
  private final BroadcastProcessor<Boolean> speakingBus = BroadcastProcessor.create();

  private final List<Cancellable> subscriptions = new ArrayList<>();
  private final Map<String, PendingToolOutputMetadata> pendingToolOutputMetadataByItemId =
      new HashMap<>();

  private record PendingToolOutputMetadata(
      RealtimeAdapterModels.ToolOutputDisposition disposition, String errorMessage) {}

  // Session knobs (mirror the Dart fields).
  private List<RealtimeAdapterModels.ToolDefinition> tools = List.of();
  private NoiseReduction noiseReduction = NoiseReduction.NEAR_FIELD;
  private String reasoningEffort;
  private boolean toolChoiceRequired = false;
  private String sessionVoice;
  private String sessionInstructions;
  private String transcriptionModel = DEFAULT_TRANSCRIPTION_MODEL;
  private RealtimeAdapterModels.AudioTurnMode audioTurnMode =
      RealtimeAdapterModels.AudioTurnMode.VOICE_ACTIVITY;

  /**
   * Three-state active-response guard used to suppress spurious {@code response.cancel} when no
   * response is in flight.
   *
   * <ul>
   *   <li>{@code UNKNOWN} – initial state; also the state before the first {@code response.created}
   *       ever arrives (i.e. the provider does not emit that event, or no response has started
   *       yet). In this state {@link #interrupt()} behaves like the legacy path (sends cancel), so
   *       we do not accidentally break providers that do not surface {@code response.created}.
   *   <li>{@code ACTIVE} – {@code response.created} received; a cancel is meaningful.
   *   <li>{@code INACTIVE} – {@code response.done} (or equivalent terminal event) received; no
   *       in-flight response exists, so cancel is skipped with a debug log.
   * </ul>
   *
   * <p>Written from Vert.x event-loop callbacks (event subscriptions) and read from whatever thread
   * calls {@code interrupt()}. Declared {@code volatile} for safe cross-thread visibility of the
   * single-assignment write.
   */
  enum ResponseState {
    UNKNOWN,
    ACTIVE,
    INACTIVE
  }

  private volatile ResponseState responseState = ResponseState.UNKNOWN;
  private final Set<String> activeResponseFunctionItemIds = new HashSet<>();
  private final Set<String> activeResponseFunctionCallIds = new HashSet<>();

  private long localIdCounter = 0;
  private String conversationId;
  private boolean isUserSpeaking = false;
  private boolean disposed = false;
  private RealtimeAdapterModels.ConnectionState connectionState =
      RealtimeAdapterModels.ConnectionState.idle();

  public OaiRealtimeAdapter(
      String modelId,
      RealtimeModelsConfig.ModelConfig modelConfig,
      Vertx vertx,
      ObjectMapper json) {
    this.modelId = modelId;
    this.modelConfig = modelConfig;
    this.client = new OaiRealtimeClient(new OaiWebSocketTransport(vertx, json), json);
    this.thread = new RealtimeThread("thread_" + UUID.randomUUID());
    this.patch = new ThreadPatchBuilder(thread);
    subscribeClient();
  }

  /**
   * Package-private constructor for unit tests: accepts a pre-built {@link OaiRealtimeClient}
   * backed by a stub transport so tests can feed synthetic events without a live socket.
   */
  OaiRealtimeAdapter(OaiRealtimeClient client) {
    this.modelId = "test";
    this.modelConfig = null;
    this.client = client;
    this.thread = new RealtimeThread("thread_test");
    this.patch = new ThreadPatchBuilder(thread);
    subscribeClient();
  }

  // ---------------------------------------------------------------------------
  // Client event subscriptions (one per consumed OAI event, mirroring the Dart listeners)
  // ---------------------------------------------------------------------------

  private void subscribeClient() {
    sub(client.connectionStateUpdates(), this::onConnectionState, "connectionState");
    sub(client.events(OaiRealtimeEvent.ErrorEvent.class), this::onErrorEvent, "error");
    sub(
        client.events(OaiRealtimeEvent.ConversationCreated.class),
        this::onConversationCreated,
        "convCreated");
    sub(
        client.events(OaiRealtimeEvent.ConversationItemCreated.class),
        this::onConversationItemCreated,
        "itemCreated");
    sub(
        client.events(OaiRealtimeEvent.ConversationItemDeleted.class),
        this::onConversationItemDeleted,
        "itemDeleted");
    sub(
        client.events(OaiRealtimeEvent.InputAudioTranscriptionDelta.class),
        this::onInputTranscriptionDelta,
        "inDelta");
    sub(
        client.events(OaiRealtimeEvent.InputAudioTranscriptionCompleted.class),
        this::onInputTranscriptionCompleted,
        "inDone");
    sub(
        client.events(OaiRealtimeEvent.InputAudioBufferSpeechStarted.class),
        e -> setUserSpeaking(true),
        "speechStart");
    sub(
        client.events(OaiRealtimeEvent.InputAudioBufferSpeechStopped.class),
        e -> setUserSpeaking(false),
        "speechStop");
    sub(
        client.events(OaiRealtimeEvent.ResponseOutputItemAdded.class),
        e -> {
          RealtimeThread.Item item = upsertConversationItem(e.item());
          trackActiveFunctionCall(item);
          flush();
        },
        "itemAdded");
    sub(
        client.events(OaiRealtimeEvent.ResponseOutputItemDone.class),
        this::onOutputItemDone,
        "itemDone");
    sub(
        client.events(OaiRealtimeEvent.ResponseContentPartAdded.class),
        e -> onContentPart(e.itemId(), e.contentIndex(), e.part(), false),
        "partAdded");
    sub(
        client.events(OaiRealtimeEvent.ResponseContentPartDone.class),
        e -> onContentPart(e.itemId(), e.contentIndex(), e.part(), true),
        "partDone");
    sub(
        client.events(OaiRealtimeEvent.ResponseOutputTextDelta.class),
        this::onTextDelta,
        "textDelta");
    sub(client.events(OaiRealtimeEvent.ResponseOutputTextDone.class), this::onTextDone, "textDone");
    sub(
        client.events(OaiRealtimeEvent.ResponseOutputAudioDelta.class),
        this::onAudioDelta,
        "audioDelta");
    sub(
        client.events(OaiRealtimeEvent.ResponseOutputAudioDone.class),
        this::onAudioDone,
        "audioDoneEvt");
    sub(
        client.events(OaiRealtimeEvent.ResponseOutputAudioTranscriptDelta.class),
        this::onAudioTranscriptDelta,
        "atDelta");
    sub(
        client.events(OaiRealtimeEvent.ResponseOutputAudioTranscriptDone.class),
        this::onAudioTranscriptDone,
        "atDone");
    sub(
        client.events(OaiRealtimeEvent.ResponseFunctionCallArgumentsDelta.class),
        this::onFunctionArgsDelta,
        "fnDelta");
    sub(
        client.events(OaiRealtimeEvent.ResponseFunctionCallArgumentsDone.class),
        this::onFunctionArgsDone,
        "fnDone");
    sub(
        client.events(OaiRealtimeEvent.ResponseCreated.class),
        e -> {
          responseState = ResponseState.ACTIVE;
          activeResponseFunctionItemIds.clear();
          activeResponseFunctionCallIds.clear();
        },
        "responseCreated");
    sub(
        client.events(OaiRealtimeEvent.ResponseDone.class),
        e -> responseState = ResponseState.INACTIVE,
        "responseDone");
  }

  private <T> void sub(Multi<T> stream, Consumer<T> onItem, String label) {
    subscriptions.add(
        stream.subscribe().with(onItem, t -> Log.errorf(t, "OAI adapter %s stream failed", label)));
  }

  // ---------------------------------------------------------------------------
  // Observation points
  // ---------------------------------------------------------------------------

  @Override
  public RealtimeThread thread() {
    return thread;
  }

  @Override
  public Multi<RealtimeAdapterModels.ThreadPatchOps> threadPatches() {
    return threadPatchBus;
  }

  @Override
  public String conversationId() {
    return conversationId;
  }

  @Override
  public Set<String> supportedExtensions() {
    return Set.of(EXT_INPUT_NOISE_REDUCTION, EXT_REASONING_EFFORT, EXT_TOOL_CHOICE_REQUIRED);
  }

  @Override
  public RealtimeAdapterModels.ConnectionState connectionState() {
    return connectionState;
  }

  @Override
  public Multi<RealtimeAdapterModels.ConnectionState> connectionStateUpdates() {
    return connectionBus;
  }

  @Override
  public Multi<RealtimeAdapterModels.Error> errors() {
    return errorBus;
  }

  @Override
  public Multi<RealtimeAdapterModels.AssistantAudioFrame> assistantAudioStream() {
    return audioBus;
  }

  @Override
  public Multi<RealtimeAdapterModels.AssistantAudioFrame> assistantAudioCompleted() {
    return audioDoneBus;
  }

  @Override
  public boolean isUserSpeaking() {
    return isUserSpeaking;
  }

  @Override
  public Multi<Boolean> isUserSpeakingUpdates() {
    return speakingBus;
  }

  // ---------------------------------------------------------------------------
  // Lifecycle
  // ---------------------------------------------------------------------------

  @Override
  public Uni<Void> connect(String voice, String instructions) {
    ensureNotDisposed();
    this.sessionVoice = voice != null ? voice : modelConfig.voice().orElse(null);
    this.sessionInstructions =
        instructions != null ? instructions : modelConfig.instructions().orElse(null);
    this.transcriptionModel =
        modelConfig
            .transcriptionModel()
            .filter(s -> !s.isBlank())
            .map(String::trim)
            .orElse(DEFAULT_TRANSCRIPTION_MODEL);

    String baseUrl = modelConfig.baseUrl().map(String::trim).orElse("");
    if (baseUrl.isEmpty()) {
      return Uni.createFrom()
          .failure(new IllegalStateException("Realtime model " + modelId + " has no base-url"));
    }
    OaiRealtimeConnectConfig connectConfig =
        new OaiRealtimeConnectConfig(
            baseUrl, "/realtime", modelConfig.apiKey().orElse(null), Map.of());

    return client.connect(connectConfig).chain(() -> client.updateSession(buildSessionConfig()));
  }

  @Override
  public Uni<Void> dispose() {
    if (disposed) {
      return Uni.createFrom().voidItem();
    }
    disposed = true;
    setUserSpeaking(false);
    for (Cancellable subscription : subscriptions) {
      subscription.cancel();
    }
    subscriptions.clear();
    return client
        .dispose()
        .onItemOrFailure()
        .invoke(
            (ignored, error) -> {
              threadPatchBus.onComplete();
              connectionBus.onComplete();
              errorBus.onComplete();
              audioBus.onComplete();
              audioDoneBus.onComplete();
              speakingBus.onComplete();
            })
        .replaceWithVoid();
  }

  // ---------------------------------------------------------------------------
  // Audio input
  // ---------------------------------------------------------------------------

  @Override
  public void pushLiveAudioChunk(byte[] pcm) {
    if (disposed || pcm == null || pcm.length == 0 || !isConnected()) {
      return;
    }
    if (audioTurnMode != RealtimeAdapterModels.AudioTurnMode.VOICE_ACTIVITY) {
      return;
    }
    client
        .appendInputAudio(pcm)
        .subscribe()
        .with(ignored -> {}, t -> Log.errorf(t, "OAI adapter live audio append failed"));
  }

  @Override
  public Uni<Void> setAudioTurnMode(RealtimeAdapterModels.AudioTurnMode mode) {
    ensureNotDisposed();
    if (audioTurnMode == mode) {
      return Uni.createFrom().voidItem();
    }
    Uni<Void> pre = Uni.createFrom().voidItem();
    if (isConnected() && audioTurnMode == RealtimeAdapterModels.AudioTurnMode.MANUAL) {
      pre = client.clearInputAudioBuffer();
    }
    this.audioTurnMode = mode;
    if (!isConnected()) {
      return pre;
    }
    return pre.chain(() -> client.updateSession(buildSessionConfig()));
  }

  // ---------------------------------------------------------------------------
  // Tool configuration / session mutation
  // ---------------------------------------------------------------------------

  @Override
  public Uni<Void> registerTools(List<RealtimeAdapterModels.ToolDefinition> nextTools) {
    ensureNotDisposed();
    this.tools = List.copyOf(nextTools);
    if (!isConnected()) {
      return Uni.createFrom().voidItem();
    }
    return client.updateSession(buildSessionConfig());
  }

  @Override
  public Uni<Void> setInstructions(String instructions) {
    ensureNotDisposed();
    String normalized = instructions == null ? "" : instructions.trim();
    String next = normalized.isEmpty() ? "" : normalized;
    if (Objects.equals(sessionInstructions, next)) {
      return Uni.createFrom().voidItem();
    }
    this.sessionInstructions = next;
    if (!isConnected()) {
      return Uni.createFrom().voidItem();
    }
    return client.updateSession(buildSessionConfig());
  }

  @Override
  public Uni<Boolean> applyProviderExtension(String extensionType, Map<String, Object> payload) {
    ensureNotDisposed();
    boolean changed;
    switch (extensionType) {
      case EXT_INPUT_NOISE_REDUCTION -> {
        NoiseReduction selection = NoiseReduction.fromPayload(payload.get(EXT_SELECTION_KEY));
        if (selection == null) {
          return Uni.createFrom()
              .failure(new IllegalArgumentException("Unsupported input noise reduction selection"));
        }
        changed = noiseReduction != selection;
        noiseReduction = selection;
      }
      case EXT_REASONING_EFFORT -> {
        Object selection = payload.get(EXT_SELECTION_KEY);
        if (selection != null && !(selection instanceof String)) {
          return Uni.createFrom()
              .failure(
                  new IllegalArgumentException(
                      "Reasoning effort selection must be a string or null"));
        }
        String value = (String) selection;
        changed = !Objects.equals(reasoningEffort, value);
        reasoningEffort = value;
      }
      case EXT_TOOL_CHOICE_REQUIRED -> {
        Object required = payload.get(EXT_REQUIRED_KEY);
        if (!(required instanceof Boolean bool)) {
          return Uni.createFrom()
              .failure(new IllegalArgumentException("Tool choice required flag must be a bool"));
        }
        changed = toolChoiceRequired != bool;
        toolChoiceRequired = bool;
      }
      default -> {
        return Uni.createFrom().item(false);
      }
    }
    if (!changed || !isConnected()) {
      return Uni.createFrom().item(true);
    }
    return client.updateSession(buildSessionConfig()).replaceWith(true);
  }

  // ---------------------------------------------------------------------------
  // User content
  // ---------------------------------------------------------------------------

  @Override
  public Uni<String> sendAudioOneShot(byte[] audioBytes) {
    ensureNotDisposed();
    String itemId = nextLocalId("audio");
    if (!isConnected() || audioBytes == null || audioBytes.length == 0) {
      return Uni.createFrom().item(itemId);
    }
    // One-shot workaround transcription of the Dart adapter: PTT audio is pushed through the same
    // buffer-clear → append → commit → response.create path as hands-free, so transcription still
    // fires (the dedicated one-shot path suppresses it).
    return client
        .clearInputAudioBuffer()
        .chain(() -> client.appendInputAudio(audioBytes))
        .chain(client::commitInputAudioBuffer)
        .chain(() -> client.createResponse(null))
        .replaceWith(itemId);
  }

  @Override
  public Uni<String> sendText(String text) {
    ensureNotDisposed();
    String itemId = nextLocalId("msg");
    Map<String, Object> item =
        Map.of(
            "id",
            itemId,
            "type",
            "message",
            "role",
            "user",
            "content",
            List.of(Map.of("type", "input_text", "text", text)));
    return client
        .createConversationItem(null, item)
        .chain(() -> client.createResponse(null))
        .replaceWith(itemId);
  }

  @Override
  public Uni<String> sendImage(byte[] imageBytes) {
    ensureNotDisposed();
    String itemId = nextLocalId("msg");
    String mimeType = sniffImageMime(imageBytes);
    String dataUri =
        "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(imageBytes);
    Map<String, Object> item =
        Map.of(
            "id",
            itemId,
            "type",
            "message",
            "role",
            "user",
            "content",
            List.of(Map.of("type", "input_image", "image_url", dataUri, "detail", "auto")));
    return client
        .createConversationItem(null, item)
        .chain(() -> client.createResponse(null))
        .replaceWith(itemId);
  }

  @Override
  public Uni<String> sendFunctionOutput(
      String callId,
      String output,
      RealtimeAdapterModels.ToolOutputDisposition disposition,
      String errorMessage) {
    ensureNotDisposed();
    String itemId = nextLocalId("tool");
    RealtimeAdapterModels.ToolOutputDisposition canonicalDisposition =
        disposition == null ? RealtimeAdapterModels.ToolOutputDisposition.SUCCESS : disposition;
    pendingToolOutputMetadataByItemId.put(
        itemId, new PendingToolOutputMetadata(canonicalDisposition, errorMessage));
    Map<String, Object> item =
        Map.of("id", itemId, "type", "function_call_output", "call_id", callId, "output", output);
    return client
        .createConversationItem(null, item)
        .chain(() -> client.createResponse(null))
        .replaceWith(itemId);
  }

  // ---------------------------------------------------------------------------
  // Response control
  // ---------------------------------------------------------------------------

  @Override
  public Uni<Void> interrupt() {
    ensureNotDisposed();
    ResponseState state = responseState;
    Uni<Void> cancelGeneration;
    if (state == ResponseState.INACTIVE) {
      // No active response; sending response.cancel would produce the
      // "no active response found" error on the Azure/OpenAI side.
      Log.debugf("OAI adapter interrupt() skipped: responseState=%s", state);
      cancelGeneration = Uni.createFrom().voidItem();
    } else {
      // ACTIVE  → cancel is warranted.
      // UNKNOWN → legacy fallback: send cancel to avoid silently swallowing the interrupt on
      //           providers that do not emit response.created (keeps previous behaviour).
      cancelGeneration = client.cancelResponse();
    }
    return cancelGeneration
        .chain(this::resolveCompletedPendingFunctionCallsAsInterrupted)
        .invoke(this::markPendingFunctionCallsIncomplete);
  }

  private Uni<Void> resolveCompletedPendingFunctionCallsAsInterrupted() {
    Uni<Void> chain = Uni.createFrom().voidItem();
    for (String callId : completedPendingFunctionCallIds()) {
      chain = chain.chain(() -> createInterruptedFunctionOutput(callId));
    }
    return chain;
  }

  private Uni<Void> createInterruptedFunctionOutput(String callId) {
    String itemId = nextLocalId("tool");
    pendingToolOutputMetadataByItemId.put(
        itemId,
        new PendingToolOutputMetadata(
            RealtimeAdapterModels.ToolOutputDisposition.ERROR, INTERRUPTED_TOOL_ERROR_MESSAGE));
    Map<String, Object> item =
        Map.of(
            "id", itemId,
            "type", "function_call_output",
            "call_id", callId,
            "output", INTERRUPTED_TOOL_ERROR_OUTPUT);
    return client.createConversationItem(null, item);
  }

  private List<String> completedPendingFunctionCallIds() {
    Set<String> outputCallIds = outputCallIds();
    List<String> pendingCallIds = new ArrayList<>();
    for (RealtimeThread.Item item : thread.items()) {
      String callId = item.callId();
      if (item.type() != RealtimeThread.ItemType.FUNCTION_CALL
          || item.status() != RealtimeThread.ItemStatus.COMPLETED
          || callId == null
          || callId.isEmpty()
          || outputCallIds.contains(callId)
          || !isActiveResponseFunctionCall(item)) {
        continue;
      }
      pendingCallIds.add(callId);
    }
    return pendingCallIds;
  }

  private Set<String> outputCallIds() {
    Set<String> outputCallIds = new HashSet<>();
    for (RealtimeThread.Item item : thread.items()) {
      if (item.type() == RealtimeThread.ItemType.FUNCTION_CALL_OUTPUT && item.callId() != null) {
        outputCallIds.add(item.callId());
      }
    }
    return outputCallIds;
  }

  private void markPendingFunctionCallsIncomplete() {
    Set<String> outputCallIds = outputCallIds();
    boolean changed = false;
    for (RealtimeThread.Item item : thread.items()) {
      String callId = item.callId();
      if (item.type() != RealtimeThread.ItemType.FUNCTION_CALL
          || item.status() == RealtimeThread.ItemStatus.INCOMPLETE
          || !isActiveResponseFunctionCall(item)
          || (callId != null && outputCallIds.contains(callId))) {
        continue;
      }
      patch.setStatus(item, RealtimeThread.ItemStatus.INCOMPLETE);
      changed = true;
    }
    if (changed) {
      flush();
    }
  }

  private void trackActiveFunctionCall(RealtimeThread.Item item) {
    if (item == null
        || item.type() != RealtimeThread.ItemType.FUNCTION_CALL
        || responseState == ResponseState.INACTIVE) {
      return;
    }
    activeResponseFunctionItemIds.add(item.id());
    String callId = item.callId();
    if (callId != null && !callId.isEmpty()) {
      activeResponseFunctionCallIds.add(callId);
    }
  }

  private boolean isActiveResponseFunctionCall(RealtimeThread.Item item) {
    String callId = item.callId();
    return activeResponseFunctionItemIds.contains(item.id())
        || (callId != null && activeResponseFunctionCallIds.contains(callId));
  }

  /** Package-private: returns the current response-tracking state (test seam). */
  ResponseState responseStateForTest() {
    return responseState;
  }

  // ---------------------------------------------------------------------------
  // Event handlers (transcription of the Dart listeners)
  // ---------------------------------------------------------------------------

  private void onConnectionState(RealtimeAdapterModels.ConnectionState state) {
    setConnectionState(state);
  }

  private void onErrorEvent(OaiRealtimeEvent.ErrorEvent event) {
    OaiRealtimeEvent.ErrorDetail detail = event.error();
    emitError(detail.code() != null ? detail.code() : detail.type(), detail.message());
  }

  private void onConversationCreated(OaiRealtimeEvent.ConversationCreated event) {
    this.conversationId = event.conversation().id();
    patch.setConversationId(event.conversation().id());
    flush();
  }

  private void onConversationItemCreated(OaiRealtimeEvent.ConversationItemCreated event) {
    upsertConversationItem(event.item());
    flush();
  }

  private void onConversationItemDeleted(OaiRealtimeEvent.ConversationItemDeleted event) {
    String itemId = event.itemId();
    if (itemId == null || itemId.isEmpty()) {
      return;
    }
    patch.removeItem(itemId);
    flush();
  }

  private void onInputTranscriptionDelta(OaiRealtimeEvent.InputAudioTranscriptionDelta event) {
    String itemId = event.itemId();
    String delta = event.delta();
    if (isBlank(itemId) || isBlank(delta)) {
      return;
    }
    RealtimeThread.Item item = ensureUserMessageItem(itemId);
    if (item.displayState() != RealtimeThread.ItemDisplayState.VISIBLE) {
      patch.setDisplayState(item, RealtimeThread.ItemDisplayState.VISIBLE);
    }
    patch.appendTranscript(item, event.contentIndex(), delta);
    flush();
  }

  private void onInputTranscriptionCompleted(
      OaiRealtimeEvent.InputAudioTranscriptionCompleted event) {
    String itemId = event.itemId();
    String transcript = event.transcript();
    if (isBlank(itemId)) {
      return;
    }
    if (isBlank(transcript)) {
      return;
    }
    RealtimeThread.Item item = ensureUserMessageItem(itemId);
    patch.replaceTranscript(item, event.contentIndex(), transcript);
    patch.markPartDone(item, event.contentIndex());
    patch.setStatus(item, RealtimeThread.ItemStatus.COMPLETED);
    flush();
  }

  private void onOutputItemDone(OaiRealtimeEvent.ResponseOutputItemDone event) {
    RealtimeThread.Item item = upsertConversationItem(event.item());
    if (item == null) {
      return;
    }
    RealtimeThread.ItemStatus nextStatus =
        RealtimeThread.ItemStatus.fromWire(event.item().status());
    patch.setStatus(item, nextStatus);
    flush();
  }

  private void onContentPart(
      String itemId, Integer contentIndex, OaiRealtimeEvent.ContentPart part, boolean isDone) {
    if (isBlank(itemId)) {
      return;
    }
    RealtimeThread.Item item = ensureAssistantMessageItem(itemId);
    mergeContentPart(item, part, contentIndex, isDone);
    flush();
  }

  private void onTextDelta(OaiRealtimeEvent.ResponseOutputTextDelta event) {
    String itemId = event.itemId();
    String delta = event.delta();
    if (isBlank(itemId) || delta == null) {
      return;
    }
    RealtimeThread.Item item = ensureAssistantMessageItem(itemId);
    patch.appendText(item, event.contentIndex(), delta);
    flush();
  }

  private void onTextDone(OaiRealtimeEvent.ResponseOutputTextDone event) {
    String itemId = event.itemId();
    if (isBlank(itemId)) {
      return;
    }
    RealtimeThread.Item item = ensureAssistantMessageItem(itemId);
    if (event.text() != null) {
      patch.replaceText(item, event.contentIndex(), event.text());
      patch.markPartDone(item, event.contentIndex());
    } else if (event.contentIndex() != null) {
      patch.markPartDone(item, event.contentIndex());
    }
    flush();
  }

  private void onAudioDelta(OaiRealtimeEvent.ResponseOutputAudioDelta event) {
    String itemId = event.itemId();
    String delta = event.delta();
    if (isBlank(itemId) || delta == null) {
      return;
    }
    RealtimeThread.Item item = ensureAssistantMessageItem(itemId);
    RealtimeThread.AudioPart audioPart = patch.ensureAudioPart(item, event.contentIndex());
    try {
      byte[] pcm = Base64.getDecoder().decode(delta);
      patch.appendAudioChunk(audioPart, pcm);
      audioBus.onNext(
          new RealtimeAdapterModels.AssistantAudioFrame(
              item.id(), item.content().indexOf(audioPart), pcm));
    } catch (IllegalArgumentException error) {
      emitError("audio_output_decode_error", "Failed to decode assistant audio delta.");
    }
    flush();
  }

  private void onAudioDone(OaiRealtimeEvent.ResponseOutputAudioDone event) {
    String itemId = event.itemId();
    if (isBlank(itemId)) {
      return;
    }
    RealtimeThread.Item item = ensureAssistantMessageItem(itemId);
    patch.markPartDone(item, event.contentIndex());
    int contentIndex =
        event.contentIndex() != null ? event.contentIndex() : lastAudioPartIndex(item);
    audioDoneBus.onNext(
        new RealtimeAdapterModels.AssistantAudioFrame(item.id(), contentIndex, new byte[0]));
    flush();
  }

  private void onAudioTranscriptDelta(OaiRealtimeEvent.ResponseOutputAudioTranscriptDelta event) {
    String itemId = event.itemId();
    String delta = event.delta();
    if (isBlank(itemId) || delta == null) {
      return;
    }
    RealtimeThread.Item item = ensureAssistantMessageItem(itemId);
    patch.appendTranscript(item, event.contentIndex(), delta);
    flush();
  }

  private void onAudioTranscriptDone(OaiRealtimeEvent.ResponseOutputAudioTranscriptDone event) {
    String itemId = event.itemId();
    String transcript = event.transcript();
    if (isBlank(itemId) || transcript == null) {
      return;
    }
    RealtimeThread.Item item = ensureAssistantMessageItem(itemId);
    patch.replaceTranscript(item, event.contentIndex(), transcript);
    patch.markPartDone(item, event.contentIndex());
    flush();
  }

  private void onFunctionArgsDelta(OaiRealtimeEvent.ResponseFunctionCallArgumentsDelta event) {
    String itemId = event.itemId();
    String delta = event.delta();
    if (isBlank(itemId) || delta == null) {
      return;
    }
    RealtimeThread.Item item = ensureFunctionCallItem(itemId, event.callId(), null);
    trackActiveFunctionCall(item);
    patch.setArguments(item, (item.arguments() == null ? "" : item.arguments()) + delta);
    flush();
  }

  private void onFunctionArgsDone(OaiRealtimeEvent.ResponseFunctionCallArgumentsDone event) {
    String itemId = event.itemId();
    if (isBlank(itemId)) {
      return;
    }
    RealtimeThread.Item item = ensureFunctionCallItem(itemId, event.callId(), event.name());
    if (event.callId() != null) {
      patch.setCallId(item, event.callId());
    }
    if (event.name() != null) {
      patch.setName(item, event.name());
    }
    if (event.arguments() != null) {
      patch.setArguments(item, event.arguments());
    }
    trackActiveFunctionCall(item);
    flush();
  }

  // ---------------------------------------------------------------------------
  // Thread projection (transcription of the Dart upsert/ensure helpers, via the patch sink)
  // ---------------------------------------------------------------------------

  private RealtimeThread.Item upsertConversationItem(OaiRealtimeEvent.ConversationItem source) {
    if (source == null) {
      return null;
    }
    RealtimeThread.Item existing = thread.findItem(source.id());
    if (existing != null) {
      RealtimeThread.ItemRole role = mapRole(source.role());
      if (role != null) {
        patch.setRole(existing, role);
      }
      RealtimeThread.ItemStatus nextStatus = RealtimeThread.ItemStatus.fromWire(source.status());
      if (source.callId() != null) {
        patch.setCallId(existing, source.callId());
      }
      if (source.name() != null) {
        patch.setName(existing, source.name());
      }
      if (source.arguments() != null) {
        patch.setArguments(existing, source.arguments());
      }
      if (source.output() != null) {
        patch.setOutput(existing, source.output());
      }
      applyFunctionCallOutputMetadata(existing, source);
      patch.setStatus(existing, nextStatus);
      if (existing.content().isEmpty() && !source.content().isEmpty()) {
        for (OaiRealtimeEvent.ContentPart part : source.content()) {
          mergeContentPart(existing, part, null, true);
        }
      }
      return existing;
    }

    RealtimeThread.ItemType itemType = mapItemType(source.type());
    RealtimeThread.ItemStatus status = RealtimeThread.ItemStatus.fromWire(source.status());
    RealtimeThread.ItemRole role = mapRole(source.role());
    if (shouldDeferUserInputAudioProjection(itemType, role, source)) {
      return null;
    }
    RealtimeThread.ItemDisplayState displayState = initialDisplayState(role, source);
    RealtimeThread.Item item = patch.addItem(source.id(), itemType, role, status, displayState);
    if (source.callId() != null) {
      patch.setCallId(item, source.callId());
    }
    if (source.name() != null) {
      patch.setName(item, source.name());
    }
    if (source.arguments() != null) {
      patch.setArguments(item, source.arguments());
    }
    if (source.output() != null) {
      patch.setOutput(item, source.output());
    }
    applyFunctionCallOutputMetadata(item, source);
    for (OaiRealtimeEvent.ContentPart part : source.content()) {
      mergeContentPart(item, part, null, true);
    }
    return item;
  }

  private void applyFunctionCallOutputMetadata(
      RealtimeThread.Item item, OaiRealtimeEvent.ConversationItem source) {
    if (item.type() != RealtimeThread.ItemType.FUNCTION_CALL_OUTPUT) {
      return;
    }
    PendingToolOutputMetadata metadata = pendingToolOutputMetadataByItemId.remove(source.id());
    if (metadata == null && item.toolOutputDisposition() != null) {
      return;
    }
    RealtimeAdapterModels.ToolOutputDisposition disposition =
        metadata == null
            ? RealtimeAdapterModels.ToolOutputDisposition.SUCCESS
            : metadata.disposition();
    patch.setToolOutputDisposition(item, disposition);
    String errorMessage = metadata == null ? null : metadata.errorMessage();
    if (errorMessage != null) {
      patch.setToolErrorMessage(item, errorMessage);
    }
  }

  private RealtimeThread.Item ensureUserMessageItem(String itemId) {
    RealtimeThread.Item existing = thread.findItem(itemId);
    if (existing != null) {
      if (existing.role() == null) {
        patch.setRole(existing, RealtimeThread.ItemRole.USER);
      }
      return existing;
    }
    return patch.addItem(
        itemId,
        RealtimeThread.ItemType.MESSAGE,
        RealtimeThread.ItemRole.USER,
        RealtimeThread.ItemStatus.IN_PROGRESS);
  }

  private RealtimeThread.Item ensureAssistantMessageItem(String itemId) {
    RealtimeThread.Item existing = thread.findItem(itemId);
    if (existing != null) {
      return existing;
    }
    return patch.addItem(
        itemId,
        RealtimeThread.ItemType.MESSAGE,
        RealtimeThread.ItemRole.ASSISTANT,
        RealtimeThread.ItemStatus.IN_PROGRESS);
  }

  private RealtimeThread.Item ensureFunctionCallItem(String itemId, String callId, String name) {
    RealtimeThread.Item existing = thread.findItem(itemId);
    if (existing != null) {
      if (callId != null) {
        patch.setCallId(existing, callId);
      }
      if (name != null) {
        patch.setName(existing, name);
      }
      return existing;
    }
    RealtimeThread.ItemStatus status = RealtimeThread.ItemStatus.IN_PROGRESS;
    RealtimeThread.Item item =
        patch.addItem(
            itemId,
            RealtimeThread.ItemType.FUNCTION_CALL,
            RealtimeThread.ItemRole.ASSISTANT,
            status);
    if (callId != null) {
      patch.setCallId(item, callId);
    }
    if (name != null) {
      patch.setName(item, name);
    }
    return item;
  }

  private void mergeContentPart(
      RealtimeThread.Item item,
      OaiRealtimeEvent.ContentPart part,
      Integer contentIndex,
      boolean isDone) {
    if (part == null) {
      return;
    }
    switch (part.type()) {
      case "text", "input_text", "output_text" -> {
        if (part.text() != null && !part.text().isEmpty()) {
          patch.replaceText(item, contentIndex, part.text());
        } else {
          patch.ensureTextPart(item, contentIndex);
        }
        if (isDone) {
          patch.markPartDone(item, contentIndex);
        }
      }
      case "audio", "input_audio", "output_audio" -> {
        patch.ensureAudioPart(item, contentIndex);
        if (part.transcript() != null && !part.transcript().isEmpty()) {
          patch.replaceTranscript(item, contentIndex, part.transcript());
        }
        if (isDone) {
          patch.markPartDone(item, contentIndex);
        }
      }
      case "input_image" ->
          patch.putImagePart(
              item,
              contentIndex,
              part.imageUrl() != null ? part.imageUrl() : "",
              part.detail() != null ? part.detail() : "auto");
      default -> {
        // Unknown part kind: ignore, like the Dart switch falling through.
      }
    }
  }

  // ---------------------------------------------------------------------------
  // State + flush
  // ---------------------------------------------------------------------------

  /**
   * The {@code _emitThreadUpdate()} seam (judgment 4): drains the buffered ops and, when non-empty,
   * emits them as one {@link RealtimeAdapterModels.ThreadPatchOps} for the session to write as a
   * live {@code thread.patch}. An empty drain emits nothing, so no empty patch ever reaches the
   * wire. No revision is stamped: a patch is a fire-and-forget delta, recovered on gap by reconnect
   * + full snapshot.
   */
  private void flush() {
    List<Map<String, Object>> ops = patch.drainOps();
    if (ops.isEmpty()) {
      return;
    }
    threadPatchBus.onNext(new RealtimeAdapterModels.ThreadPatchOps(ops));
  }

  private void setConnectionState(RealtimeAdapterModels.ConnectionState value) {
    this.connectionState = value;
    connectionBus.onNext(value);
  }

  private void setUserSpeaking(boolean value) {
    if (isUserSpeaking == value) {
      return;
    }
    isUserSpeaking = value;
    speakingBus.onNext(value);
  }

  private void emitError(String code, String message) {
    errorBus.onNext(new RealtimeAdapterModels.Error(code, message, null));
  }

  // ---------------------------------------------------------------------------
  // Session config mapping (transcription of _buildSessionConfig)
  // ---------------------------------------------------------------------------

  private Map<String, Object> buildSessionConfig() {
    Map<String, Object> inputFormat = new LinkedHashMap<>();
    inputFormat.put("type", "audio/pcm");
    inputFormat.put("rate", 24000);

    Map<String, Object> input = new LinkedHashMap<>();
    input.put("format", inputFormat);
    Map<String, Object> noiseReductionConfig = buildNoiseReductionConfig();
    if (noiseReductionConfig != null) {
      input.put("noise_reduction", noiseReductionConfig);
    }
    input.put("transcription", Map.of("model", transcriptionModel));
    Map<String, Object> turnDetection = buildTurnDetectionConfig();
    if (turnDetection != null) {
      input.put("turn_detection", turnDetection);
    }

    Map<String, Object> outputFormat = new LinkedHashMap<>();
    outputFormat.put("type", "audio/pcm");
    outputFormat.put("rate", 24000);
    Map<String, Object> output = new LinkedHashMap<>();
    output.put("format", outputFormat);
    if (sessionVoice != null) {
      output.put("voice", sessionVoice);
    }

    Map<String, Object> audio = new LinkedHashMap<>();
    audio.put("input", input);
    audio.put("output", output);

    Map<String, Object> session = new LinkedHashMap<>();
    session.put("type", "realtime");
    session.put("audio", audio);
    if (reasoningEffort != null) {
      session.put("reasoning", Map.of("effort", reasoningEffort));
    }
    session.put("instructions", sessionInstructions == null ? "" : sessionInstructions);
    session.put("output_modalities", List.of("audio"));

    List<Map<String, Object>> toolMaps = new ArrayList<>();
    for (RealtimeAdapterModels.ToolDefinition tool : tools) {
      Map<String, Object> toolMap = new LinkedHashMap<>();
      toolMap.put("type", "function");
      toolMap.put("name", tool.name());
      if (tool.description() != null) {
        toolMap.put("description", tool.description());
      }
      toolMap.put("parameters", normalizeParametersSchema(tool.parameters()));
      toolMaps.add(toolMap);
    }
    session.put("tools", toolMaps);
    session.put(
        "tool_choice", tools.isEmpty() ? "none" : (toolChoiceRequired ? "required" : "auto"));
    return session;
  }

  private Map<String, Object> buildTurnDetectionConfig() {
    return switch (audioTurnMode) {
      case VOICE_ACTIVITY -> {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("type", "semantic_vad");
        config.put("eagerness", "low");
        config.put("create_response", true);
        config.put("interrupt_response", true);
        yield config;
      }
      case MANUAL -> null;
    };
  }

  private Map<String, Object> buildNoiseReductionConfig() {
    return switch (noiseReduction) {
      case OFF -> null;
      case NEAR_FIELD -> Map.of("type", "near_field");
      case FAR_FIELD -> Map.of("type", "far_field");
    };
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /**
   * Ensures the parameters map passed to the provider is a valid JSON Schema object.
   *
   * <p>Rules (no strict-ification — only fills missing structure):
   *
   * <ul>
   *   <li>null or empty map → {@code {"type":"object","properties":{}}}
   *   <li>Missing {@code type} field → {@code type} set to {@code "object"}
   *   <li>{@code type == "object"} but missing {@code properties} → empty map added
   *   <li>Otherwise returned as-is (preserves existing args schemas)
   * </ul>
   *
   * <p>This is the last line of defence before provider submission; it guards against any schema
   * corruption that occurred upstream (e.g. CBOR round-trip, client bug).
   */
  @SuppressWarnings("unchecked")
  private static Map<String, Object> normalizeParametersSchema(Map<String, Object> params) {
    if (params == null || params.isEmpty()) {
      Map<String, Object> minimal = new LinkedHashMap<>();
      minimal.put("type", "object");
      minimal.put("properties", new LinkedHashMap<String, Object>());
      return minimal;
    }
    Object type = params.get("type");
    if (type == null) {
      Map<String, Object> result = new LinkedHashMap<>(params);
      result.put("type", "object");
      if (!result.containsKey("properties")) {
        result.put("properties", new LinkedHashMap<String, Object>());
      }
      return result;
    }
    if ("object".equals(type) && !params.containsKey("properties")) {
      Map<String, Object> result = new LinkedHashMap<>(params);
      result.put("properties", new LinkedHashMap<String, Object>());
      return result;
    }
    return params;
  }

  private boolean isConnected() {
    return connectionState.isConnected();
  }

  private boolean shouldDeferUserInputAudioProjection(
      RealtimeThread.ItemType itemType,
      RealtimeThread.ItemRole role,
      OaiRealtimeEvent.ConversationItem source) {
    if (itemType != RealtimeThread.ItemType.MESSAGE
        || role != RealtimeThread.ItemRole.USER
        || source == null
        || source.content().isEmpty()) {
      return false;
    }
    boolean hasInputAudio = false;
    for (OaiRealtimeEvent.ContentPart part : source.content()) {
      if ("input_audio".equals(part.type())) {
        hasInputAudio = true;
        if (!isBlank(part.transcript())) {
          return false;
        }
      } else if (!isBlank(part.text()) || !isBlank(part.transcript())) {
        return false;
      } else if (!"audio".equals(part.type())) {
        return false;
      }
    }
    return hasInputAudio;
  }

  private RealtimeThread.ItemDisplayState initialDisplayState(
      RealtimeThread.ItemRole role, OaiRealtimeEvent.ConversationItem source) {
    if (role != RealtimeThread.ItemRole.USER || source == null || source.content().isEmpty()) {
      return RealtimeThread.ItemDisplayState.VISIBLE;
    }
    boolean hasInputAudio = false;
    for (OaiRealtimeEvent.ContentPart part : source.content()) {
      if ("input_audio".equals(part.type())) {
        hasInputAudio = true;
        if (!isBlank(part.transcript())) {
          return RealtimeThread.ItemDisplayState.VISIBLE;
        }
      } else if (!isBlank(part.text()) || !isBlank(part.transcript())) {
        return RealtimeThread.ItemDisplayState.VISIBLE;
      } else if (!"audio".equals(part.type())) {
        return RealtimeThread.ItemDisplayState.VISIBLE;
      }
    }
    return hasInputAudio
        ? RealtimeThread.ItemDisplayState.PENDING
        : RealtimeThread.ItemDisplayState.VISIBLE;
  }

  private int lastAudioPartIndex(RealtimeThread.Item item) {
    List<RealtimeThread.ContentPart> content = item.content();
    for (int i = content.size() - 1; i >= 0; i--) {
      if (content.get(i) instanceof RealtimeThread.AudioPart) {
        return i;
      }
    }
    return 0;
  }

  private static RealtimeThread.ItemType mapItemType(String value) {
    return switch (value == null ? "" : value) {
      case "function_call" -> RealtimeThread.ItemType.FUNCTION_CALL;
      case "function_call_output" -> RealtimeThread.ItemType.FUNCTION_CALL_OUTPUT;
      default -> RealtimeThread.ItemType.MESSAGE;
    };
  }

  private static RealtimeThread.ItemRole mapRole(String value) {
    return switch (value == null ? "" : value) {
      case "system" -> RealtimeThread.ItemRole.SYSTEM;
      case "user" -> RealtimeThread.ItemRole.USER;
      case "assistant" -> RealtimeThread.ItemRole.ASSISTANT;
      default -> null;
    };
  }

  private static String sniffImageMime(byte[] bytes) {
    if (bytes.length >= 4) {
      if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
        return "image/jpeg";
      }
      if ((bytes[0] & 0xFF) == 0x47 && (bytes[1] & 0xFF) == 0x49 && (bytes[2] & 0xFF) == 0x46) {
        return "image/gif";
      }
      if ((bytes[0] & 0xFF) == 0x89
          && (bytes[1] & 0xFF) == 0x50
          && (bytes[2] & 0xFF) == 0x4E
          && (bytes[3] & 0xFF) == 0x47) {
        return "image/png";
      }
    }
    return "image/png";
  }

  private void ensureNotDisposed() {
    if (disposed) {
      throw new IllegalStateException("OaiRealtimeAdapter is already disposed.");
    }
  }

  private static boolean isBlank(String value) {
    return value == null || value.isEmpty();
  }

  private String nextLocalId(String prefix) {
    localIdCounter += 1;
    return prefix + "_" + System.nanoTime() + "_" + localIdCounter;
  }
}
