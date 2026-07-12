package app.vagina.server.realtime.oai;

import app.vagina.server.realtime.RealtimeAdapter;
import app.vagina.server.realtime.RealtimeModelsConfig;
import app.vagina.server.realtime.model.RealtimeAdapterModels;
import app.vagina.server.realtime.model.RealtimeThread;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import io.vertx.mutiny.core.Vertx;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Thin OpenAI Realtime facade.
 *
 * <p>Session policy belongs to {@link OaiRealtimeSessionConfig}; provider response serialization
 * belongs to {@link OaiRealtimeResponseCoordinator}; subscriptions and canonical-thread projection
 * belong to {@link OaiRealtimeEventProjector}. This class implements the provider-neutral adapter
 * contract and sequences those collaborators, but owns no provider event state machine.
 */
public final class OaiRealtimeAdapter implements RealtimeAdapter {
  private static final String INTERRUPTED_TOOL_ERROR_MESSAGE =
      "Tool call cancelled by user interrupt.";
  private static final String INTERRUPTED_TOOL_ERROR_OUTPUT =
      "{\"error\":\"Tool call cancelled by user interrupt.\"}";

  private final String modelId;
  private final RealtimeModelsConfig.ModelConfig modelConfig;
  private final OaiRealtimeClient client;
  private final OaiRealtimeSessionConfig sessionConfig = new OaiRealtimeSessionConfig();
  private final OaiRealtimeResponseCoordinator responseCoordinator;
  private final OaiRealtimeEventProjector eventProjector;
  private final BroadcastProcessor<RealtimeAdapterModels.ConnectionState> connectionBus =
      BroadcastProcessor.create();
  private final BroadcastProcessor<RealtimeAdapterModels.Error> errorBus =
      BroadcastProcessor.create();

  private long localIdCounter;
  private boolean disposed;
  private RealtimeAdapterModels.ConnectionState connectionState =
      RealtimeAdapterModels.ConnectionState.idle();

  public OaiRealtimeAdapter(
      String modelId,
      RealtimeModelsConfig.ModelConfig modelConfig,
      Vertx vertx,
      ObjectMapper json) {
    this(
        modelId,
        modelConfig,
        new OaiRealtimeClient(new OaiWebSocketTransport(vertx, json), json),
        "thread_" + UUID.randomUUID());
  }

  /** Test seam backed by a fake transport through a real protocol client. */
  OaiRealtimeAdapter(OaiRealtimeClient client) {
    this("test", null, client, "thread_test");
  }

  private OaiRealtimeAdapter(
      String modelId,
      RealtimeModelsConfig.ModelConfig modelConfig,
      OaiRealtimeClient client,
      String threadId) {
    this.modelId = modelId;
    this.modelConfig = modelConfig;
    this.client = client;
    this.responseCoordinator =
        new OaiRealtimeResponseCoordinator(
            new OaiRealtimeResponseCoordinator.Commands() {
              @Override
              public Uni<Void> createResponse(String eventId) {
                return client.createResponse(eventId, null);
              }

              @Override
              public Uni<Void> cancelResponse(String eventId) {
                return client.cancelResponse(eventId);
              }

              @Override
              public void emitError(String code, String message, Object cause) {
                OaiRealtimeAdapter.this.emitError(code, message, cause);
              }
            });
    this.eventProjector =
        new OaiRealtimeEventProjector(
            client, responseCoordinator, this::setConnectionState, errorBus::onNext, threadId);
  }

  @Override
  public RealtimeThread thread() {
    return eventProjector.thread();
  }

  @Override
  public Multi<RealtimeAdapterModels.ThreadPatchOps> threadPatches() {
    return eventProjector.threadPatches();
  }

  @Override
  public String conversationId() {
    return eventProjector.conversationId();
  }

  @Override
  public Set<String> supportedExtensions() {
    return sessionConfig.supportedExtensions();
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
    return eventProjector.assistantAudioStream();
  }

  @Override
  public Multi<RealtimeAdapterModels.AssistantAudioFrame> assistantAudioCompleted() {
    return eventProjector.assistantAudioCompleted();
  }

  @Override
  public boolean isUserSpeaking() {
    return eventProjector.isUserSpeaking();
  }

  @Override
  public Multi<Boolean> isUserSpeakingUpdates() {
    return eventProjector.speakingUpdates();
  }

  @Override
  public Uni<Void> connect(String voice, String instructions) {
    ensureNotDisposed();
    String transcriptionModel = modelConfig.transcriptionModel().map(String::trim).orElse("");
    if (transcriptionModel.isEmpty()) {
      return Uni.createFrom()
          .failure(
              new IllegalStateException(
                  "Realtime model " + modelId + " has no transcription-model"));
    }
    String baseUrl = modelConfig.baseUrl().trim();
    if (baseUrl.isEmpty()) {
      return Uni.createFrom()
          .failure(new IllegalStateException("Realtime model " + modelId + " has no base-url"));
    }
    String providerModel = modelConfig.model().trim();
    if (providerModel.isEmpty()) {
      return Uni.createFrom()
          .failure(new IllegalStateException("Realtime model " + modelId + " has no model"));
    }
    sessionConfig.initialize(voice, instructions, transcriptionModel);
    OaiRealtimeConnectConfig connectConfig =
        new OaiRealtimeConnectConfig(
            baseUrl, "/realtime", providerModel, modelConfig.apiKey(), Map.of());
    return client
        .connect(connectConfig)
        .chain(() -> client.updateSession(sessionConfig.toWireMap()));
  }

  @Override
  public Uni<Void> dispose() {
    if (disposed) {
      return Uni.createFrom().voidItem();
    }
    disposed = true;
    eventProjector.dispose();
    return client
        .dispose()
        .onItemOrFailure()
        .invoke(
            (ignored, error) -> {
              connectionBus.onComplete();
              errorBus.onComplete();
            })
        .replaceWithVoid();
  }

  @Override
  public void pushLiveAudioChunk(byte[] pcm) {
    if (disposed || pcm == null || pcm.length == 0 || !isConnected()) {
      return;
    }
    if (sessionConfig.audioTurnMode() != RealtimeAdapterModels.AudioTurnMode.VOICE_ACTIVITY) {
      return;
    }
    client
        .appendInputAudio(pcm)
        .subscribe()
        .with(ignored -> {}, error -> Log.errorf(error, "OAI adapter live audio append failed"));
  }

  @Override
  public Uni<Void> setAudioTurnMode(RealtimeAdapterModels.AudioTurnMode mode) {
    ensureNotDisposed();
    RealtimeAdapterModels.AudioTurnMode previous = sessionConfig.audioTurnMode();
    if (!sessionConfig.setAudioTurnMode(mode)) {
      return Uni.createFrom().voidItem();
    }
    Uni<Void> beforeUpdate = Uni.createFrom().voidItem();
    if (isConnected() && previous == RealtimeAdapterModels.AudioTurnMode.MANUAL) {
      beforeUpdate = client.clearInputAudioBuffer();
    }
    if (!isConnected()) {
      return beforeUpdate;
    }
    return beforeUpdate.chain(() -> client.updateSession(sessionConfig.toWireMap()));
  }

  @Override
  public Uni<Void> registerTools(List<RealtimeAdapterModels.ToolDefinition> tools) {
    ensureNotDisposed();
    sessionConfig.setTools(tools);
    return updateConnectedSession();
  }

  @Override
  public Uni<Void> setInstructions(String instructions) {
    ensureNotDisposed();
    if (!sessionConfig.setClientInstructions(instructions)) {
      return Uni.createFrom().voidItem();
    }
    return updateConnectedSession();
  }

  @Override
  public Uni<Boolean> applyProviderExtension(String extensionType, Map<String, Object> payload) {
    ensureNotDisposed();
    final OaiRealtimeSessionConfig.ExtensionResult result;
    try {
      result = sessionConfig.applyExtension(extensionType, payload);
    } catch (IllegalArgumentException error) {
      return Uni.createFrom().failure(error);
    }
    if (!result.supported()) {
      return Uni.createFrom().item(false);
    }
    if (!result.changed() || !isConnected()) {
      return Uni.createFrom().item(true);
    }
    return client.updateSession(sessionConfig.toWireMap()).replaceWith(true);
  }

  @Override
  public Uni<String> sendAudioOneShot(byte[] audioBytes) {
    ensureNotDisposed();
    String itemId = nextLocalId("audio");
    if (!isConnected() || audioBytes == null || audioBytes.length == 0) {
      return Uni.createFrom().item(itemId);
    }
    return client
        .clearInputAudioBuffer()
        .chain(() -> client.appendInputAudio(audioBytes))
        .chain(client::commitInputAudioBuffer)
        .chain(() -> responseCoordinator.requestGeneration("audio:" + itemId))
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
        .chain(() -> responseCoordinator.requestGeneration("text:" + itemId))
        .replaceWith(itemId);
  }

  @Override
  public Uni<String> sendImage(byte[] imageBytes) {
    ensureNotDisposed();
    String itemId = nextLocalId("msg");
    String dataUri =
        "data:"
            + sniffImageMime(imageBytes)
            + ";base64,"
            + Base64.getEncoder().encodeToString(imageBytes);
    eventProjector.projectLocalImage(itemId, dataUri);
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
        .chain(() -> responseCoordinator.requestGeneration("image:" + itemId))
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
    eventProjector.rememberToolOutput(itemId, canonicalDisposition, errorMessage);
    Map<String, Object> item =
        Map.of("id", itemId, "type", "function_call_output", "call_id", callId, "output", output);
    return client
        .createConversationItem(null, item)
        .chain(() -> responseCoordinator.onToolOutputSubmitted(callId))
        .replaceWith(itemId);
  }

  @Override
  public Uni<Void> interrupt() {
    ensureNotDisposed();
    return responseCoordinator
        .interrupt()
        .chain(this::resolveCompletedPendingFunctionCallsAsInterrupted)
        .invoke(eventProjector::markPendingFunctionCallsIncompleteForInterrupt);
  }

  private Uni<Void> resolveCompletedPendingFunctionCallsAsInterrupted() {
    Uni<Void> chain = Uni.createFrom().voidItem();
    for (String callId : eventProjector.completedPendingFunctionCallIdsForInterrupt()) {
      chain = chain.chain(() -> createInterruptedFunctionOutput(callId));
    }
    return chain;
  }

  private Uni<Void> createInterruptedFunctionOutput(String callId) {
    String itemId = nextLocalId("tool");
    eventProjector.rememberToolOutput(
        itemId, RealtimeAdapterModels.ToolOutputDisposition.ERROR, INTERRUPTED_TOOL_ERROR_MESSAGE);
    Map<String, Object> item =
        Map.of(
            "id", itemId,
            "type", "function_call_output",
            "call_id", callId,
            "output", INTERRUPTED_TOOL_ERROR_OUTPUT);
    return client.createConversationItem(null, item);
  }

  private Uni<Void> updateConnectedSession() {
    return isConnected()
        ? client.updateSession(sessionConfig.toWireMap())
        : Uni.createFrom().voidItem();
  }

  private boolean isConnected() {
    return connectionState.isConnected();
  }

  private void setConnectionState(RealtimeAdapterModels.ConnectionState state) {
    connectionState = state;
    connectionBus.onNext(state);
  }

  private void emitError(String code, String message, Object cause) {
    errorBus.onNext(new RealtimeAdapterModels.Error(code, message, cause));
  }

  private void ensureNotDisposed() {
    if (disposed) {
      throw new IllegalStateException("OaiRealtimeAdapter is already disposed.");
    }
  }

  private String nextLocalId(String prefix) {
    localIdCounter += 1;
    return prefix + "_" + System.nanoTime() + "_" + localIdCounter;
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
}
