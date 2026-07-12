package app.vagina.server.realtime.oai;

import app.vagina.server.realtime.ThreadPatchBuilder;
import app.vagina.server.realtime.model.RealtimeAdapterModels;
import app.vagina.server.realtime.model.RealtimeThread;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import io.smallrye.mutiny.subscription.Cancellable;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Subscribes to provider events and projects them into the canonical realtime thread and output
 * streams. Event routing and projection intentionally live together: every handler has one
 * transaction-like responsibility—apply a provider event, drain its patch operations, and emit any
 * associated audio or state signal.
 */
final class OaiRealtimeEventProjector {
  private final OaiRealtimeClient client;
  private final OaiRealtimeResponseCoordinator coordinator;
  private final Consumer<RealtimeAdapterModels.ConnectionState> connectionStateSink;
  private final Consumer<RealtimeAdapterModels.Error> errorSink;
  private final RealtimeThread thread;
  private final ThreadPatchBuilder patch;
  private final BroadcastProcessor<RealtimeAdapterModels.ThreadPatchOps> threadPatchBus =
      BroadcastProcessor.create();
  private final BroadcastProcessor<RealtimeAdapterModels.AssistantAudioFrame> audioBus =
      BroadcastProcessor.create();
  private final BroadcastProcessor<RealtimeAdapterModels.AssistantAudioFrame> audioDoneBus =
      BroadcastProcessor.create();
  private final BroadcastProcessor<Boolean> speakingBus = BroadcastProcessor.create();
  private final List<Cancellable> subscriptions = new ArrayList<>();
  private final Map<String, PendingToolOutputMetadata> pendingToolOutputMetadataByItemId =
      new HashMap<>();
  private final Set<String> activeResponseFunctionItemIds = new HashSet<>();
  private final Set<String> activeResponseFunctionCallIds = new HashSet<>();

  private String conversationId;
  private boolean userSpeaking;

  private record PendingToolOutputMetadata(
      RealtimeAdapterModels.ToolOutputDisposition disposition, String errorMessage) {}

  OaiRealtimeEventProjector(
      OaiRealtimeClient client,
      OaiRealtimeResponseCoordinator coordinator,
      Consumer<RealtimeAdapterModels.ConnectionState> connectionStateSink,
      Consumer<RealtimeAdapterModels.Error> errorSink,
      String threadId) {
    this.client = client;
    this.coordinator = coordinator;
    this.connectionStateSink = connectionStateSink;
    this.errorSink = errorSink;
    this.thread = new RealtimeThread(threadId);
    this.patch = new ThreadPatchBuilder(thread);
    subscribeClient();
  }

  RealtimeThread thread() {
    return thread;
  }

  Multi<RealtimeAdapterModels.ThreadPatchOps> threadPatches() {
    return threadPatchBus;
  }

  Multi<RealtimeAdapterModels.AssistantAudioFrame> assistantAudioStream() {
    return audioBus;
  }

  Multi<RealtimeAdapterModels.AssistantAudioFrame> assistantAudioCompleted() {
    return audioDoneBus;
  }

  Multi<Boolean> speakingUpdates() {
    return speakingBus;
  }

  String conversationId() {
    return conversationId;
  }

  boolean isUserSpeaking() {
    return userSpeaking;
  }

  void rememberToolOutput(
      String itemId, RealtimeAdapterModels.ToolOutputDisposition disposition, String errorMessage) {
    pendingToolOutputMetadataByItemId.put(
        itemId, new PendingToolOutputMetadata(disposition, errorMessage));
  }

  void projectLocalImage(String itemId, String dataUri) {
    RealtimeThread.Item item =
        patch.addItem(
            itemId,
            RealtimeThread.ItemType.MESSAGE,
            RealtimeThread.ItemRole.USER,
            RealtimeThread.ItemStatus.COMPLETED);
    patch.putImagePart(item, 0, dataUri, "auto");
    flush();
  }

  List<String> completedPendingFunctionCallIdsForInterrupt() {
    return completedPendingFunctionCallIds();
  }

  void markPendingFunctionCallsIncompleteForInterrupt() {
    markPendingFunctionCallsIncomplete();
  }

  void dispose() {
    for (Cancellable subscription : subscriptions) {
      subscription.cancel();
    }
    subscriptions.clear();
    setUserSpeaking(false);
    threadPatchBus.onComplete();
    audioBus.onComplete();
    audioDoneBus.onComplete();
    speakingBus.onComplete();
  }

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
          trackActiveFunctionCall(e.responseId(), item);
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
          coordinator.onResponseCreated(e.responseId());
          activeResponseFunctionItemIds.clear();
          activeResponseFunctionCallIds.clear();
        },
        "responseCreated");
    sub(
        client.events(OaiRealtimeEvent.ResponseDone.class),
        e -> coordinator.onResponseDone(e.responseId()),
        "responseDone");
  }

  private <T> void sub(Multi<T> stream, Consumer<T> onItem, String label) {
    subscriptions.add(
        stream.subscribe().with(onItem, t -> Log.errorf(t, "OAI adapter %s stream failed", label)));
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

  private void trackActiveFunctionCall(String responseId, RealtimeThread.Item item) {
    if (item == null || item.type() != RealtimeThread.ItemType.FUNCTION_CALL) {
      return;
    }
    activeResponseFunctionItemIds.add(item.id());
    String callId = item.callId();
    if (callId != null && !callId.isEmpty()) {
      activeResponseFunctionCallIds.add(callId);
    }
    coordinator.trackToolCall(responseId, item.id(), callId);
  }

  private boolean isActiveResponseFunctionCall(RealtimeThread.Item item) {
    String callId = item.callId();
    return activeResponseFunctionItemIds.contains(item.id())
        || (callId != null && activeResponseFunctionCallIds.contains(callId));
  }

  private void onConnectionState(RealtimeAdapterModels.ConnectionState state) {
    connectionStateSink.accept(state);
  }

  private void onErrorEvent(OaiRealtimeEvent.ErrorEvent event) {
    OaiRealtimeEvent.ErrorDetail detail = event.error();
    if (coordinator.onProviderError(detail)) {
      return;
    }
    emitError(detail.code() != null ? detail.code() : detail.type(), detail.message(), detail);
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
      emitError("audio_output_decode_error", "Failed to decode assistant audio delta.", error);
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
    trackActiveFunctionCall(null, item);
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
    trackActiveFunctionCall(null, item);
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
    RealtimeThread.Item previousUserItem = lastVisibleUserMessage();
    if (previousUserItem == null) {
      return patch.addItem(
          itemId,
          RealtimeThread.ItemType.MESSAGE,
          RealtimeThread.ItemRole.ASSISTANT,
          RealtimeThread.ItemStatus.IN_PROGRESS);
    }
    return patch.addItemAfter(
        previousUserItem.id(),
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
    RealtimeThread.Item previousAssistantItem = lastVisibleAssistantMessage();
    RealtimeThread.Item item =
        previousAssistantItem == null
            ? patch.addItem(
                itemId,
                RealtimeThread.ItemType.FUNCTION_CALL,
                RealtimeThread.ItemRole.ASSISTANT,
                status)
            : patch.addItemAfter(
                previousAssistantItem.id(),
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

  private RealtimeThread.Item lastVisibleUserMessage() {
    for (int index = thread.items().size() - 1; index >= 0; index--) {
      RealtimeThread.Item item = thread.items().get(index);
      if (item.type() == RealtimeThread.ItemType.MESSAGE
          && item.role() == RealtimeThread.ItemRole.USER
          && item.displayState() == RealtimeThread.ItemDisplayState.VISIBLE) {
        return item;
      }
    }
    return null;
  }

  private RealtimeThread.Item lastVisibleAssistantMessage() {
    for (int index = thread.items().size() - 1; index >= 0; index--) {
      RealtimeThread.Item item = thread.items().get(index);
      if (item.type() == RealtimeThread.ItemType.MESSAGE
          && item.role() == RealtimeThread.ItemRole.ASSISTANT
          && item.displayState() == RealtimeThread.ItemDisplayState.VISIBLE) {
        return item;
      }
    }
    return null;
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

  private void setUserSpeaking(boolean value) {
    if (userSpeaking == value) {
      return;
    }
    userSpeaking = value;
    speakingBus.onNext(value);
  }

  private void emitError(String code, String message, Object cause) {
    errorSink.accept(new RealtimeAdapterModels.Error(code, message, cause));
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

  private static boolean isBlank(String value) {
    return value == null || value.isEmpty();
  }
}
