package app.vagina.server.realtime.oai_cc;

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
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.nio.charset.StandardCharsets;

/** OpenAI Chat Completions implementation of the hosted realtime adapter contract. */
public final class OaiCcRealtimeAdapter implements RealtimeAdapter {
  private static final String EXT_INPUT_NOISE_REDUCTION = "session.input_noise_reduction_selection";
  private static final String EXT_REASONING_EFFORT = "session.reasoning_effort_selection";
  private static final String EXT_TOOL_CHOICE_REQUIRED = "session.tool_choice_required";
  private static final String EXT_SELECTION_KEY = "selection";
  private static final String EXT_REQUIRED_KEY = "required";

  private static final String INTERRUPTED_TOOL_ERROR_MESSAGE =
      "Tool call cancelled by user interrupt.";
  private static final String INTERRUPTED_TOOL_ERROR_OUTPUT =
      "{\"error\":\"Tool call cancelled by user interrupt.\"}";

  private final String modelId;
  private final RealtimeModelsConfig.ModelConfig modelConfig;
  private final OaiCcClient client;
  private final RealtimeThread thread;
  private final ThreadPatchBuilder patch;

  private final BroadcastProcessor<RealtimeAdapterModels.ThreadPatchOps> threadPatchBus =
      BroadcastProcessor.create();
  private final BroadcastProcessor<RealtimeAdapterModels.ConnectionState> connectionBus =
      BroadcastProcessor.create();
  private final BroadcastProcessor<RealtimeAdapterModels.Error> errorBus = BroadcastProcessor.create();
  private final BroadcastProcessor<RealtimeAdapterModels.AssistantAudioFrame> audioBus =
      BroadcastProcessor.create();
  private final BroadcastProcessor<RealtimeAdapterModels.AssistantAudioFrame> audioDoneBus =
      BroadcastProcessor.create();
  private final BroadcastProcessor<Boolean> speakingBus = BroadcastProcessor.create();

  private final ObjectMapper json;
  private final Map<Integer, String> activeToolCallIndexToItemId = new LinkedHashMap<>();
  private final Map<String, String> toolCallIdToItemId = new LinkedHashMap<>();
  private final Set<String> activeResponseFunctionItemIds = new HashSet<>();
  private final Set<String> activeResponseFunctionCallIds = new HashSet<>();
  private final Map<String, String> assistantAudioIds = new LinkedHashMap<>();

  private OaiCcConnectConfig connectConfig;
  private Cancellable responseSubscription;
  private List<RealtimeAdapterModels.ToolDefinition> tools = List.of();
  private boolean toolChoiceRequired = false;
  private String reasoningEffort;
  private String sessionVoice;
  private String sessionInstructions = "";
  private RealtimeAdapterModels.AudioTurnMode audioTurnMode =
      RealtimeAdapterModels.AudioTurnMode.VOICE_ACTIVITY;
  private RealtimeAdapterModels.ConnectionState connectionState =
      RealtimeAdapterModels.ConnectionState.idle();
  private long localIdCounter = 0;
  private boolean disposed = false;

  public OaiCcRealtimeAdapter(
      String modelId, RealtimeModelsConfig.ModelConfig modelConfig, ObjectMapper json) {
    this.modelId = modelId;
    this.modelConfig = modelConfig;
    this.json = json;
    this.client = new OaiCcClient(json);
    this.thread = new RealtimeThread("thread_cc_" + UUID.randomUUID());
    this.thread.setConversationId("cc_" + UUID.randomUUID());
    this.patch = new ThreadPatchBuilder(thread);
  }

  @Override
  public Uni<Void> connect(String voice, String instructions) {
    ensureNotDisposed();
    setConnectionState(RealtimeAdapterModels.ConnectionState.connecting());
    this.sessionVoice = voice != null ? voice : modelConfig.voice().orElse(null);
    this.sessionInstructions =
        instructions != null ? instructions : modelConfig.instructions().orElse("");
    String baseUrl = modelConfig.baseUrl().map(String::trim).orElse("");
    if (baseUrl.isEmpty()) {
      setConnectionState(
          RealtimeAdapterModels.ConnectionState.failed(
              "Chat Completions model " + modelId + " has no base-url", null));
      return Uni.createFrom()
          .failure(new IllegalStateException("Chat Completions model " + modelId + " has no base-url"));
    }
    try {
      ResolvedEndpoint endpoint = resolveEndpoint(baseUrl, modelConfig.model().orElse(null));
      this.connectConfig =
          new OaiCcConnectConfig(
              endpoint.baseUri(), endpoint.model(), modelConfig.apiKey().orElse(null), Map.of());
    } catch (RuntimeException e) {
      setConnectionState(
          RealtimeAdapterModels.ConnectionState.failed(
              "Invalid Chat Completions model config for " + modelId, e));
      return Uni.createFrom().failure(e);
    }
    setConnectionState(RealtimeAdapterModels.ConnectionState.connected());
    return Uni.createFrom().voidItem();
  }

  @Override
  public Uni<Void> dispose() {
    if (disposed) {
      return Uni.createFrom().voidItem();
    }
    disposed = true;
    cleanupActiveCompletion();
    client.dispose();
    setConnectionState(RealtimeAdapterModels.ConnectionState.disconnected(null));
    threadPatchBus.onComplete();
    connectionBus.onComplete();
    errorBus.onComplete();
    audioBus.onComplete();
    audioDoneBus.onComplete();
    speakingBus.onComplete();
    return Uni.createFrom().voidItem();
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
  public RealtimeThread thread() {
    return thread;
  }

  @Override
  public Multi<RealtimeAdapterModels.ThreadPatchOps> threadPatches() {
    return threadPatchBus;
  }

  @Override
  public String conversationId() {
    return thread.conversationId();
  }

  @Override
  public Set<String> supportedExtensions() {
    return Set.of(EXT_REASONING_EFFORT, EXT_TOOL_CHOICE_REQUIRED);
  }

  @Override
  public void pushLiveAudioChunk(byte[] pcm) {
    // Chat Completions consumes completed turns only, matching standalone CC.
  }

  @Override
  public Uni<Void> setAudioTurnMode(RealtimeAdapterModels.AudioTurnMode mode) {
    ensureNotDisposed();
    this.audioTurnMode = mode;
    return Uni.createFrom().voidItem();
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
    return false;
  }

  @Override
  public Multi<Boolean> isUserSpeakingUpdates() {
    return speakingBus;
  }

  @Override
  public Uni<Void> registerTools(List<RealtimeAdapterModels.ToolDefinition> nextTools) {
    ensureNotDisposed();
    this.tools = nextTools == null ? List.of() : List.copyOf(nextTools);
    return Uni.createFrom().voidItem();
  }

  @Override
  public Uni<Void> setInstructions(String instructions) {
    ensureNotDisposed();
    String normalized = instructions == null ? "" : instructions.trim();
    this.sessionInstructions = normalized;
    return Uni.createFrom().voidItem();
  }

  @Override
  public Uni<Boolean> applyProviderExtension(String extensionType, Map<String, Object> payload) {
    ensureNotDisposed();
    switch (extensionType) {
      case EXT_INPUT_NOISE_REDUCTION -> {
        // Chat Completions has no equivalent for Realtime input noise reduction.
        // Do not advertise this capability, but accept-and-ignore if an older/eager client sends it.
        return Uni.createFrom().item(true);
      }
      case EXT_REASONING_EFFORT -> {
        Object selection = payload.get(EXT_SELECTION_KEY);
        if (selection != null && !(selection instanceof String)) {
          return Uni.createFrom()
              .failure(
                  new IllegalArgumentException(
                      "Reasoning effort selection must be a string or null"));
        }
        reasoningEffort = normalizeReasoningEffort((String) selection);
        return Uni.createFrom().item(true);
      }
      case EXT_TOOL_CHOICE_REQUIRED -> {
        Object required = payload.get(EXT_REQUIRED_KEY);
        if (!(required instanceof Boolean bool)) {
          return Uni.createFrom()
              .failure(new IllegalArgumentException("Tool choice required flag must be a bool"));
        }
        toolChoiceRequired = bool;
        return Uni.createFrom().item(true);
      }
      default -> {
        return Uni.createFrom().item(false);
      }
    }
  }

  @Override
  public Uni<String> sendAudioOneShot(byte[] audioBytes) {
    ensureNotDisposed();
    interrupt();
    String itemId = nextLocalId("cc_audio");
    RealtimeThread.Item userItem =
        patch.addItem(
            itemId,
            RealtimeThread.ItemType.MESSAGE,
            RealtimeThread.ItemRole.USER,
            RealtimeThread.ItemStatus.COMPLETED);
    RealtimeThread.AudioPart audioPart = patch.ensureAudioPart(userItem, null);
    audioPart.replaceAudio(audioBytes == null ? new byte[0] : audioBytes);
    patch.markPartDone(userItem, userItem.content().indexOf(audioPart));
    flush();
    return executeChatCompletion(buildMessages()).replaceWith(itemId);
  }

  @Override
  public Uni<String> sendText(String text) {
    ensureNotDisposed();
    interrupt();
    String itemId = nextLocalId("cc_msg");
    RealtimeThread.Item userItem =
        patch.addItem(
            itemId,
            RealtimeThread.ItemType.MESSAGE,
            RealtimeThread.ItemRole.USER,
            RealtimeThread.ItemStatus.COMPLETED);
    patch.replaceText(userItem, null, text == null ? "" : text);
    patch.markPartDone(userItem, 0);
    flush();
    return executeChatCompletion(buildMessages()).replaceWith(itemId);
  }

  @Override
  public Uni<String> sendImage(byte[] imageBytes) {
    ensureNotDisposed();
    return Uni.createFrom()
        .failure(new UnsupportedOperationException("Image input is not supported by hosted CC v1"));
  }

  @Override
  public Uni<String> sendFunctionOutput(
      String callId,
      String output,
      RealtimeAdapterModels.ToolOutputDisposition disposition,
      String errorMessage) {
    ensureNotDisposed();
    interrupt();
    String itemId = nextLocalId("cc_tool");
    RealtimeThread.Item item =
        patch.addItem(
            itemId,
            RealtimeThread.ItemType.FUNCTION_CALL_OUTPUT,
            RealtimeThread.ItemRole.ASSISTANT,
            RealtimeThread.ItemStatus.COMPLETED);
    patch.setCallId(item, callId);
    patch.setOutput(item, output);
    patch.setToolOutputDisposition(item, disposition);
    patch.setToolErrorMessage(item, errorMessage);
    flush();
    if (allToolCallsHaveOutputs()) {
      return executeChatCompletion(buildMessages()).replaceWith(itemId);
    }
    return Uni.createFrom().item(itemId);
  }

  @Override
  public Uni<Void> interrupt() {
    ensureNotDisposed();
    cleanupActiveCompletion();
    resolveCompletedPendingFunctionCallsAsInterrupted();
    if (markPendingFunctionCallsIncomplete()) {
      flush();
    }
    return Uni.createFrom().voidItem();
  }

  private Uni<Void> executeChatCompletion(List<Map<String, Object>> messages) {
    OaiCcConnectConfig config = connectConfig;
    if (config == null) {
      return Uni.createFrom().voidItem();
    }
    cleanupActiveCompletion();
    activeToolCallIndexToItemId.clear();
    activeResponseFunctionItemIds.clear();
    activeResponseFunctionCallIds.clear();

    String assistantItemId = nextLocalId("cc_asst");
    RealtimeThread.Item assistantItem =
        patch.addItem(
            assistantItemId,
            RealtimeThread.ItemType.MESSAGE,
            RealtimeThread.ItemRole.ASSISTANT,
            RealtimeThread.ItemStatus.IN_PROGRESS);
    RealtimeThread.TextPart textPart = patch.ensureTextPart(assistantItem, null);
    flush();

    OaiCcRequest request =
        new OaiCcRequest(
            config.model(), messages, true, sessionVoice, tools, toolChoiceRequired, reasoningEffort);
    responseSubscription =
        client
            .streamCompletions(config, request)
            .subscribe()
            .with(event -> onEvent(event, assistantItem, textPart), this::onStreamFailure);
    return Uni.createFrom().voidItem();
  }

  private void onEvent(
      OaiCcEvent event, RealtimeThread.Item assistantItem, RealtimeThread.TextPart textPart) {
    switch (event) {
      case OaiCcEvent.ContentDelta content -> {
        patch.appendText(assistantItem, assistantItem.content().indexOf(textPart), content.content());
        flush();
      }
      case OaiCcEvent.AudioDelta audio -> onAudioDelta(assistantItem, textPart, audio);
      case OaiCcEvent.ToolCallDelta tool -> onToolCallDelta(tool);
      case OaiCcEvent.Finished ignored -> onFinished(assistantItem, textPart);
      case OaiCcEvent.ErrorEvent error -> onProviderError(assistantItem, error.message());
    }
  }

  private void onAudioDelta(
      RealtimeThread.Item assistantItem, RealtimeThread.TextPart textPart, OaiCcEvent.AudioDelta audio) {
    if (audio.audioId() != null && !audio.audioId().isBlank()) {
      assistantAudioIds.put(assistantItem.id(), audio.audioId());
    }
    if (audio.transcript() != null && !audio.transcript().isEmpty()) {
      patch.appendText(assistantItem, assistantItem.content().indexOf(textPart), audio.transcript());
      flush();
    }
    if (audio.audioBase64() != null && !audio.audioBase64().isEmpty()) {
      try {
        byte[] pcm = Base64.getDecoder().decode(audio.audioBase64());
        RealtimeThread.AudioPart audioPart = patch.ensureAudioPart(assistantItem, null);
        patch.appendAudioChunk(audioPart, pcm);
        audioBus.onNext(
            new RealtimeAdapterModels.AssistantAudioFrame(
                assistantItem.id(), assistantItem.content().indexOf(audioPart), pcm));
        flush();
      } catch (IllegalArgumentException e) {
        emitError("audio_decode_error", "Failed to decode Chat Completions audio delta");
      }
    }
  }

  private void onToolCallDelta(OaiCcEvent.ToolCallDelta event) {
    RealtimeThread.Item item = findOrCreateToolCallItem(event);
    trackActiveFunctionCall(item);
    if (event.id() != null) {
      patch.setCallId(item, event.id());
    }
    if (event.name() != null) {
      patch.setName(item, event.name());
    }
    if (event.arguments() != null) {
      patch.setArguments(item, (item.arguments() == null ? "" : item.arguments()) + event.arguments());
    }
    flush();
  }

  private void onFinished(RealtimeThread.Item assistantItem, RealtimeThread.TextPart textPart) {
    boolean hasToolCalls = !activeResponseFunctionItemIds.isEmpty();
    if (hasToolCalls && textPart.text().isEmpty()) {
      patch.removeItem(assistantItem.id());
    } else {
      patch.markPartDone(assistantItem, assistantItem.content().indexOf(textPart));
      patch.setStatus(assistantItem, RealtimeThread.ItemStatus.COMPLETED);
    }
    for (RealtimeThread.Item item : thread.items()) {
      if (item.type() == RealtimeThread.ItemType.FUNCTION_CALL
          && item.status() == RealtimeThread.ItemStatus.IN_PROGRESS) {
        patch.setStatus(item, RealtimeThread.ItemStatus.COMPLETED);
        trackActiveFunctionCall(item);
      }
    }
    int audioIndex = lastAudioPartIndex(assistantItem);
    if (audioIndex >= 0) {
      patch.markPartDone(assistantItem, audioIndex);
      audioDoneBus.onNext(new RealtimeAdapterModels.AssistantAudioFrame(assistantItem.id(), audioIndex, new byte[0]));
    } else {
      audioDoneBus.onNext(new RealtimeAdapterModels.AssistantAudioFrame(assistantItem.id(), 0, new byte[0]));
    }
    flush();
    responseSubscription = null;
  }

  private void onProviderError(RealtimeThread.Item assistantItem, String message) {
    patch.setStatus(assistantItem, RealtimeThread.ItemStatus.INCOMPLETE);
    flush();
    emitError("chat_completions_error", message);
    responseSubscription = null;
  }

  private void onStreamFailure(Throwable error) {
    emitError("stream_error", error.getMessage());
    responseSubscription = null;
  }

  private RealtimeThread.Item findOrCreateToolCallItem(OaiCcEvent.ToolCallDelta event) {
    String existingByIndex = activeToolCallIndexToItemId.get(event.index());
    if (existingByIndex != null) {
      RealtimeThread.Item existing = thread.findItem(existingByIndex);
      if (existing != null) {
        return existing;
      }
    }
    if (event.id() != null) {
      String existingByCallId = toolCallIdToItemId.get(event.id());
      if (existingByCallId != null) {
        RealtimeThread.Item existing = thread.findItem(existingByCallId);
        if (existing != null) {
          activeToolCallIndexToItemId.put(event.index(), existing.id());
          return existing;
        }
      }
    }
    String itemId = nextLocalId("cc_call");
    activeToolCallIndexToItemId.put(event.index(), itemId);
    if (event.id() != null) {
      toolCallIdToItemId.put(event.id(), itemId);
    }
    RealtimeThread.Item item =
        patch.addItem(
            itemId,
            RealtimeThread.ItemType.FUNCTION_CALL,
            RealtimeThread.ItemRole.ASSISTANT,
            RealtimeThread.ItemStatus.IN_PROGRESS);
    if (event.id() != null) {
      patch.setCallId(item, event.id());
    }
    if (event.name() != null) {
      patch.setName(item, event.name());
    }
    if (event.arguments() != null) {
      patch.setArguments(item, event.arguments());
    }
    return item;
  }

  private List<Map<String, Object>> buildMessages() {
    List<Map<String, Object>> messages = new ArrayList<>();
    if (sessionInstructions != null && !sessionInstructions.isBlank()) {
      messages.add(Map.of("role", "system", "content", sessionInstructions));
    }
    Set<String> processedCallIds = new HashSet<>();
    for (int i = 0; i < thread.items().size(); i++) {
      RealtimeThread.Item item = thread.items().get(i);
      if (item.type() == RealtimeThread.ItemType.FUNCTION_CALL_OUTPUT) {
        continue;
      }
      if (item.type() == RealtimeThread.ItemType.FUNCTION_CALL) {
        i = addToolCallMessages(messages, processedCallIds, i);
        continue;
      }
      if (item.type() == RealtimeThread.ItemType.MESSAGE) {
        Map<String, Object> message = messageForItem(item);
        if (message != null) {
          messages.add(message);
        }
      }
    }
    return messages;
  }

  private int addToolCallMessages(
      List<Map<String, Object>> messages, Set<String> processedCallIds, int startIndex) {
    List<RealtimeThread.Item> calls = new ArrayList<>();
    int index = startIndex;
    while (index < thread.items().size()) {
      RealtimeThread.Item item = thread.items().get(index);
      if (item.type() == RealtimeThread.ItemType.FUNCTION_CALL) {
        if (item.callId() != null
            && item.name() != null
            && item.arguments() != null
            && !processedCallIds.contains(item.callId())) {
          calls.add(item);
        }
        index += 1;
        continue;
      }
      if (item.type() == RealtimeThread.ItemType.FUNCTION_CALL_OUTPUT) {
        index += 1;
        continue;
      }
      break;
    }
    if (!calls.isEmpty()) {
      List<Map<String, Object>> toolCalls = new ArrayList<>();
      for (RealtimeThread.Item call : calls) {
        processedCallIds.add(call.callId());
        toolCalls.add(
            Map.of(
                "id",
                call.callId(),
                "type",
                "function",
                "function",
                Map.of("name", call.name(), "arguments", call.arguments())));
      }
      messages.add(Map.of("role", "assistant", "tool_calls", toolCalls));
      for (RealtimeThread.Item call : calls) {
        RealtimeThread.Item output = findFunctionOutput(call.callId());
        if (output != null && output.output() != null) {
          messages.add(Map.of("role", "tool", "tool_call_id", call.callId(), "content", output.output()));
        }
      }
    }
    return index - 1;
  }

  private Map<String, Object> messageForItem(RealtimeThread.Item item) {
    String role = roleToWire(item.role());
    if (role == null) {
      return null;
    }
    if ("assistant".equals(role)) {
      String audioId = assistantAudioIds.get(item.id());
      if (audioId != null) {
        return Map.of("role", "assistant", "audio", Map.of("id", audioId));
      }
    }
    if ("user".equals(role)) {
      RealtimeThread.AudioPart audioPart = firstAudioPart(item);
      if (audioPart != null && !audioPart.audioChunks().isEmpty()) {
        return Map.of(
            "role",
            role,
            "content",
            List.of(
                Map.of(
                    "type",
                    "input_audio",
                    "input_audio",
                    Map.of("data", OaiCcWavEncoder.encodeBase64(joinAudio(audioPart)), "format", "wav"))));
      }
    }
    String text = textContent(item);
    return text.isBlank() ? null : Map.of("role", role, "content", text);
  }

  private String textContent(RealtimeThread.Item item) {
    List<String> fragments = new ArrayList<>();
    for (RealtimeThread.ContentPart part : item.content()) {
      if (part instanceof RealtimeThread.TextPart text && text.text() != null && !text.text().isEmpty()) {
        fragments.add(text.text());
      } else if (part instanceof RealtimeThread.AudioPart audio
          && audio.transcript() != null
          && !audio.transcript().isEmpty()) {
        fragments.add(audio.transcript());
      }
    }
    return String.join("\n", fragments).trim();
  }

  private void cleanupActiveCompletion() {
    Cancellable subscription = responseSubscription;
    if (subscription != null) {
      subscription.cancel();
      responseSubscription = null;
    }
    client.cancelOngoingRequest();
  }

  private void resolveCompletedPendingFunctionCallsAsInterrupted() {
    for (String callId : completedPendingFunctionCallIds()) {
      RealtimeThread.Item output =
          patch.addItem(
              nextLocalId("cc_tool_cancel"),
              RealtimeThread.ItemType.FUNCTION_CALL_OUTPUT,
              RealtimeThread.ItemRole.ASSISTANT,
              RealtimeThread.ItemStatus.COMPLETED);
      patch.setCallId(output, callId);
      patch.setOutput(output, INTERRUPTED_TOOL_ERROR_OUTPUT);
      patch.setToolOutputDisposition(output, RealtimeAdapterModels.ToolOutputDisposition.ERROR);
      patch.setToolErrorMessage(output, INTERRUPTED_TOOL_ERROR_MESSAGE);
    }
    flush();
  }

  private List<String> completedPendingFunctionCallIds() {
    Set<String> outputCallIds = outputCallIds();
    List<String> ids = new ArrayList<>();
    for (RealtimeThread.Item item : thread.items()) {
      if (item.type() == RealtimeThread.ItemType.FUNCTION_CALL
          && item.status() == RealtimeThread.ItemStatus.COMPLETED
          && item.callId() != null
          && !outputCallIds.contains(item.callId())
          && isActiveResponseFunctionCall(item)) {
        ids.add(item.callId());
      }
    }
    return ids;
  }

  private boolean markPendingFunctionCallsIncomplete() {
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
    return changed;
  }

  private boolean allToolCallsHaveOutputs() {
    Set<String> outputs = outputCallIds();
    for (RealtimeThread.Item item : thread.items()) {
      if (item.type() == RealtimeThread.ItemType.FUNCTION_CALL
          && item.callId() != null
          && !outputs.contains(item.callId())) {
        return false;
      }
    }
    return true;
  }

  private Set<String> outputCallIds() {
    Set<String> ids = new HashSet<>();
    for (RealtimeThread.Item item : thread.items()) {
      if (item.type() == RealtimeThread.ItemType.FUNCTION_CALL_OUTPUT && item.callId() != null) {
        ids.add(item.callId());
      }
    }
    return ids;
  }

  private RealtimeThread.Item findFunctionOutput(String callId) {
    for (RealtimeThread.Item item : thread.items()) {
      if (item.type() == RealtimeThread.ItemType.FUNCTION_CALL_OUTPUT
          && Objects.equals(item.callId(), callId)) {
        return item;
      }
    }
    return null;
  }

  private void trackActiveFunctionCall(RealtimeThread.Item item) {
    activeResponseFunctionItemIds.add(item.id());
    if (item.callId() != null && !item.callId().isBlank()) {
      activeResponseFunctionCallIds.add(item.callId());
    }
  }

  private boolean isActiveResponseFunctionCall(RealtimeThread.Item item) {
    return activeResponseFunctionItemIds.contains(item.id())
        || (item.callId() != null && activeResponseFunctionCallIds.contains(item.callId()));
  }

  private void flush() {
    List<Map<String, Object>> ops = patch.drainOps();
    if (!ops.isEmpty()) {
      threadPatchBus.onNext(new RealtimeAdapterModels.ThreadPatchOps(ops));
    }
  }

  private void setConnectionState(RealtimeAdapterModels.ConnectionState value) {
    this.connectionState = value;
    connectionBus.onNext(value);
  }

  private void emitError(String code, String message) {
    errorBus.onNext(new RealtimeAdapterModels.Error(code, message, null));
  }

  private static ResolvedEndpoint resolveEndpoint(String baseUrl, String configuredModel) {
    URI parsed = URI.create(baseUrl);
    String model = configuredModel == null || configuredModel.isBlank() ? "gpt-4o" : configuredModel;
    String query = parsed.getRawQuery();
    if (query == null || query.isBlank()) {
      return new ResolvedEndpoint(parsed, model);
    }

    List<String> retained = new ArrayList<>();
    for (String entry : query.split("&", -1)) {
      int separator = entry.indexOf('=');
      String rawName = separator < 0 ? entry : entry.substring(0, separator);
      String name = URLDecoder.decode(rawName, StandardCharsets.UTF_8);
      if ("model".equals(name)) {
        String rawValue = separator < 0 ? "" : entry.substring(separator + 1);
        String value = URLDecoder.decode(rawValue, StandardCharsets.UTF_8);
        if (!value.isBlank()) {
          model = value;
        }
      } else if (!entry.isBlank()) {
        retained.add(entry);
      }
    }

    try {
      URI clean =
          new URI(
              parsed.getScheme(),
              parsed.getRawUserInfo(),
              parsed.getHost(),
              parsed.getPort(),
              parsed.getRawPath(),
              retained.isEmpty() ? null : String.join("&", retained),
              parsed.getRawFragment());
      return new ResolvedEndpoint(clean, model);
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException("Invalid Chat Completions base URI", e);
    }
  }

  private record ResolvedEndpoint(URI baseUri, String model) {}

  private static String normalizeReasoningEffort(String selection) {
    if (selection == null || selection.isBlank() || "off".equals(selection)) {
      return null;
    }
    return switch (selection) {
      case "none" -> "none";
      case "minimal" -> "minimal";
      case "low" -> "low";
      case "medium" -> "medium";
      case "high" -> "high";
      case "xhigh" -> "xhigh";
      default -> throw new IllegalArgumentException("Unsupported reasoning effort selection");
    };
  }

  private void ensureNotDisposed() {
    if (disposed) {
      throw new IllegalStateException("OaiCcRealtimeAdapter is already disposed.");
    }
  }

  private String nextLocalId(String prefix) {
    localIdCounter += 1;
    return prefix + "_" + System.nanoTime() + "_" + localIdCounter;
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

  private static RealtimeThread.AudioPart firstAudioPart(RealtimeThread.Item item) {
    for (RealtimeThread.ContentPart part : item.content()) {
      if (part instanceof RealtimeThread.AudioPart audio) {
        return audio;
      }
    }
    return null;
  }

  private static int lastAudioPartIndex(RealtimeThread.Item item) {
    for (int index = item.content().size() - 1; index >= 0; index--) {
      if (item.content().get(index) instanceof RealtimeThread.AudioPart) {
        return index;
      }
    }
    return -1;
  }

  private static byte[] joinAudio(RealtimeThread.AudioPart audio) {
    int size = 0;
    for (byte[] chunk : audio.audioChunks()) {
      size += chunk.length;
    }
    byte[] joined = new byte[size];
    int offset = 0;
    for (byte[] chunk : audio.audioChunks()) {
      System.arraycopy(chunk, 0, joined, offset, chunk.length);
      offset += chunk.length;
    }
    return joined;
  }
}
