

/**
 * Hosted realtime conversation use case: the stateful domain core of a VHRP/1 session.
 *
 * <p>It is {@link SessionScoped}, so there is one instance per WebSocket connection. It owns the
 * connection's conversation state — the canonical {@link RealtimeThread}, the session configuration
 * (model/voice/instructions/tools/turn-mode), the interrupt {@code generation} epoch, and the
 * pending tool-call set — and the essential business logic that operates on them. The thread is a
 * domain concept and lives here, never in the presentation layer.
 *
 * <p>It emits what the client should observe through the injected {@link VhrpClientChannel}, purely
 * in domain/mutation vocabulary. The channel is a concrete support component injected directly — no
 * output-port interface is introduced, mirroring how {@link ModelDriver} is depended on as a
 * concrete service (one implementation today, so an abstraction would be speculative; YAGNI). It
 * receives model
 * output as <b>data</b>: it hands the {@link ModelDriver} a {@code Consumer<RealtimeModelEvent>}
 * (its own private {@link #onModelEvent} handler) and consumes the normalized event stream, rather
 * than implementing a driver-defined callback interface. The driver is a thin service that does only
 * vendor I/O; all conversation logic — optimistic user items, assistant item lifecycle, the
 * tool-output barrier, interrupt handling, and mapping model events onto thread mutations — stays in
 * this use case.
 */
@SessionScoped
public class HostedRealtimeUsecase {

  private static final List<String> SUPPORTED_EXTENSIONS =
      List.of(
          "session.voice_selection",
          "session.input_noise_reduction_selection",
          "session.reasoning_effort_selection",
          "session.tool_choice_required");

  @Inject VhrpClientChannel port;
  @Inject ModelDriver driver;
  @Inject AuthService authService;

  // --- per-connection state owned by this session-scoped use case ---
  private final RealtimeThread thread = new RealtimeThread("thread_" + UUID.randomUUID(), null);
  private ModelDriver.Session session;
  private String modelId;
  private String voice;
  private String instructions;
  private RealtimeTurnMode turnMode = RealtimeTurnMode.VOICE_ACTIVITY;
  private List<RealtimeToolDefinition> tools = List.of();

  private long generation = 0L;
  private long localItemCounter = 0L;

  // current assistant turn projection state
  private String assistantItemId;
  private int assistantTextIndex = -1;
  private int assistantAudioIndex = -1;
  private int assistantNextContentIndex = 0;

  // tool-output barrier: callIds awaiting a result within the current generation
  private final Set<String> pendingToolCallIds = new LinkedHashSet<>();

  // ---------------------------------------------------------------------------
  // Connection lifecycle
  // ---------------------------------------------------------------------------

  /** A client connected; the empty canonical thread is ready and we await {@code session.open}. */
  public void registerClient() {
    // The thread is initialized as a field; nothing else is authorized until session.open.
  }

  /** The client's transport detached; close the upstream model session if one was opened. */
  public void releaseClient() {
    if (session != null) {
      driver.closeSession(session);
      session = null;
    }
  }

  // ---------------------------------------------------------------------------
  // Session configuration
  // ---------------------------------------------------------------------------

  /**
   * Authenticate the JWT, capture session config, open the model session, then become ready.
   *
   * <p>Authentication failure is signalled by letting the authenticator's exception propagate: this
   * method does not catch it and does not emit a problem. The resource endpoint relies on that —
   * "openSession returned without throwing" is its definition of a successful, authenticated session
   * for its handshake state machine, so swallowing the failure here would falsely authenticate.
   */
  public void openSession(RealtimeSessionConfig config) {
    final long userId =
        authService
            .authenticateFromJwt(config.token())
            .orElseThrow(() -> new AuthenticationException("Realtime session authentication failed"))
            .getId();

    this.modelId = config.modelId();
    this.voice = config.voice();
    this.instructions = config.instructions();
    this.turnMode = config.turnMode() == null ? RealtimeTurnMode.VOICE_ACTIVITY : config.turnMode();

    this.session =
        driver.openSession(
            userId, modelId, voice, instructions, turnMode, tools, this::onModelEvent);

    port.sessionEstablished(thread.id(), thread.conversationId(), SUPPORTED_EXTENSIONS);
  }

  /** Switch turn handling; applies to subsequent input. */
  public void setAudioTurnMode(RealtimeTurnMode mode) {
    this.turnMode = mode;
    if (session != null) {
      driver.updateTurnMode(session, mode);
    }
  }

  /** Replace session instructions; only affects subsequent responses. */
  public void updateInstructions(String newInstructions) {
    String normalized =
        newInstructions == null || newInstructions.isBlank() ? null : newInstructions;
    this.instructions = normalized;
    if (session != null) {
      driver.updateInstructions(session, normalized);
    }
  }

  /** Register the tool catalog for the session. */
  public void setTools(List<RealtimeToolDefinition> newTools) {
    this.tools = newTools == null ? List.of() : List.copyOf(newTools);
    if (session != null) {
      driver.updateTools(session, this.tools);
    }
  }

  /** Apply a provider extension; unsupported extensions surface as a recoverable problem. */
  public void applyExtension(RealtimeExtensionRequest extension) {
    if (session == null) {
      port.problemRaised("session.unknown_model", "No active session.", true);
      return;
    }
    boolean applied =
        driver.applyExtension(session, extension.extensionType(), extension.payload());
    if (!applied) {
      port.problemRaised(
          "extension.unsupported",
          "Extension not supported: " + extension.extensionType(),
          true);
    }
  }

  // ---------------------------------------------------------------------------
  // User content (each implies "and generate a response")
  // ---------------------------------------------------------------------------

  /** Forward streaming microphone PCM; ignored unless in voice-activity mode. */
  public void ingestLiveAudio(RealtimeLiveAudioChunk chunk) {
    if (session == null || turnMode != RealtimeTurnMode.VOICE_ACTIVITY) {
      return;
    }
    driver.streamUserAudio(session, chunk.pcm(), chunk.sequence());
  }

  /** Submit one completed user audio turn: append an optimistic item, then drive a response. */
  public void submitAudioTurn(RealtimeAudioTurn turn) {
    Item item = new Item(turn.clientItemId(), ItemType.MESSAGE, Role.USER, Status.COMPLETED);
    item.content().add(new AudioPart(null, false));
    thread.addItem(item);
    port.itemAdded(item);
    port.submissionAccepted(turn.clientItemId());
    if (session != null) {
      driver.submitUserAudio(
          session,
          generation,
          turn.clientItemId(),
          turn.pcm(),
          turn.sampleRate(),
          turn.channels(),
          turn.bitDepth());
    }
  }

  /** Submit one user text turn: append a completed user item, then drive a response. */
  public void submitTextTurn(RealtimeTextTurn turn) {
    Item item = new Item(turn.clientItemId(), ItemType.MESSAGE, Role.USER, Status.COMPLETED);
    item.content().add(new TextPart(turn.text(), true));
    thread.addItem(item);
    port.itemAdded(item);
    port.submissionAccepted(turn.clientItemId());
    if (session != null) {
      driver.submitUserText(session, generation, turn.clientItemId(), turn.text());
    }
  }

  /** Submit one user image turn; the asset URL is filled in later via {@link #onUserImageStored}. */
  public void submitImageTurn(RealtimeImageTurn turn) {
    Item item = new Item(turn.clientItemId(), ItemType.MESSAGE, Role.USER, Status.COMPLETED);
    thread.addItem(item);
    port.itemAdded(item);
    port.submissionAccepted(turn.clientItemId());
    if (session != null) {
      driver.submitUserImage(session, generation, turn.clientItemId(), turn.imageBytes());
    }
  }

  /**
   * Record a tool result and, once every pending call in this generation has a result, resume the
   * assistant. This is the v1 barrier: assistant generation is deferred until the queue drains.
   */
  public void submitToolResult(RealtimeToolResult result) {
    Item output =
        new Item(
            result.clientItemId(), ItemType.FUNCTION_CALL_OUTPUT, Role.ASSISTANT, Status.COMPLETED);
    output.setCallId(result.callId());
    output.setOutput(result.output());
    output.setToolOutputDisposition(
        result.disposition() == null ? RealtimeToolDisposition.SUCCESS : result.disposition());
    output.setToolErrorMessage(result.errorMessage());
    thread.addItem(output);
    port.itemAdded(output);
    port.submissionAccepted(result.clientItemId());

    pendingToolCallIds.remove(result.callId());
    if (session != null) {
      driver.provideToolResult(
          session, result.callId(), result.output(), result.disposition(), result.errorMessage());
      if (pendingToolCallIds.isEmpty()) {
        driver.resumeAfterToolResult(session, generation);
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Response control
  // ---------------------------------------------------------------------------

  /** Interrupt: advance the generation epoch so stale model events drop, then stop upstream. */
  public void interruptAssistant(RealtimeInterruptReason reason) {
    generation++;
    if (assistantItemId != null) {
      Item current = thread.findItem(assistantItemId);
      if (current != null && current.status() == Status.IN_PROGRESS) {
        current.setStatus(Status.INCOMPLETE);
        port.itemStatusChanged(assistantItemId, Status.INCOMPLETE);
      }
    }
    clearAssistantTurn();
    pendingToolCallIds.clear();
    if (session != null) {
      driver.interruptGeneration(session);
    }
  }

  // ---------------------------------------------------------------------------
  // Model output — consumed as data, mapped onto thread mutations and port emits
  // ---------------------------------------------------------------------------

  /**
   * Consume one normalized model event. This is the {@code Consumer<RealtimeModelEvent>} handed to
   * the driver; it dispatches each sealed variant to the matching private handler, so the use case
   * drives and consumes the driver rather than implementing a driver-defined callback interface.
   */
  private void onModelEvent(RealtimeModelEvent event) {
    switch (event) {
      case RealtimeModelEvent.UserTranscript e -> onUserTranscript(e.userItemId(), e.transcript());
      case RealtimeModelEvent.UserImageStored e ->
          onUserImageStored(e.userItemId(), e.imageUrl(), e.detail());
      case RealtimeModelEvent.AssistantTurnStarted e -> onAssistantTurnStarted(e.generation());
      case RealtimeModelEvent.AssistantTextDelta e ->
          onAssistantTextDelta(e.generation(), e.delta());
      case RealtimeModelEvent.AssistantTranscriptDelta e ->
          onAssistantTranscriptDelta(e.generation(), e.delta());
      case RealtimeModelEvent.AssistantAudioChunk e ->
          onAssistantAudioChunk(e.generation(), e.pcm());
      case RealtimeModelEvent.AssistantAudioDone e -> onAssistantAudioDone(e.generation());
      case RealtimeModelEvent.ToolCallRequested e ->
          onToolCallRequested(e.generation(), e.callId(), e.name(), e.arguments());
      case RealtimeModelEvent.AssistantTurnCompleted e -> onAssistantTurnCompleted(e.generation());
      case RealtimeModelEvent.ModelProblem e ->
          onModelProblem(e.generation(), e.code(), e.message(), e.recoverable());
    }
  }

  private void onUserTranscript(String userItemId, String transcript) {
    Item item = thread.findItem(userItemId);
    if (item == null) {
      return;
    }
    int index = indexOfFirstAudioPart(item);
    if (index < 0) {
      return;
    }
    ((AudioPart) item.content().get(index)).replaceTranscript(transcript);
    port.transcriptReplaced(userItemId, index, transcript);
  }

  private void onUserImageStored(String userItemId, String imageUrl, String detail) {
    Item item = thread.findItem(userItemId);
    if (item == null) {
      return;
    }
    RealtimeThread.ImagePart part = new RealtimeThread.ImagePart(imageUrl, detail);
    int index = item.content().size();
    item.content().add(part);
    port.partPut(userItemId, index, part);
  }

  private void onAssistantTurnStarted(long eventGeneration) {
    if (isStale(eventGeneration)) {
      return;
    }
    clearAssistantTurn();
    assistantItemId = nextLocalId("msga");
    Item item = new Item(assistantItemId, ItemType.MESSAGE, Role.ASSISTANT, Status.IN_PROGRESS);
    thread.addItem(item);
    port.itemAdded(item);
  }

  private void onAssistantTextDelta(long eventGeneration, String delta) {
    if (isStale(eventGeneration) || assistantItemId == null) {
      return;
    }
    TextPart part = ensureAssistantTextPart();
    part.appendDelta(delta);
    port.textAppended(assistantItemId, assistantTextIndex, delta);
  }

  private void onAssistantTranscriptDelta(long eventGeneration, String delta) {
    if (isStale(eventGeneration) || assistantItemId == null) {
      return;
    }
    AudioPart part = ensureAssistantAudioPart();
    part.appendTranscriptDelta(delta);
    port.transcriptAppended(assistantItemId, assistantAudioIndex, delta);
  }

  private void onAssistantAudioChunk(long eventGeneration, byte[] pcm) {
    if (isStale(eventGeneration) || assistantItemId == null) {
      return;
    }
    ensureAssistantAudioPart();
    port.assistantAudioProduced(pcm, assistantItemId, assistantAudioIndex);
  }

  private void onAssistantAudioDone(long eventGeneration) {
    if (isStale(eventGeneration) || assistantItemId == null || assistantAudioIndex < 0) {
      return;
    }
    Item item = thread.findItem(assistantItemId);
    if (item != null) {
      item.content().get(assistantAudioIndex).markDone();
    }
    port.assistantAudioFinished(assistantItemId, assistantAudioIndex);
  }

  private void onToolCallRequested(
      long eventGeneration, String callId, String name, String arguments) {
    if (isStale(eventGeneration)) {
      return;
    }
    String itemId = nextLocalId("call");
    Item call = new Item(itemId, ItemType.FUNCTION_CALL, Role.ASSISTANT, Status.COMPLETED);
    call.setCallId(callId);
    call.setName(name);
    call.setArguments(arguments);
    thread.addItem(call);
    pendingToolCallIds.add(callId);
    port.itemAdded(call);
  }

  private void onAssistantTurnCompleted(long eventGeneration) {
    if (isStale(eventGeneration) || assistantItemId == null) {
      return;
    }
    Item item = thread.findItem(assistantItemId);
    if (item != null && item.status() == Status.IN_PROGRESS) {
      item.setStatus(Status.COMPLETED);
      port.itemStatusChanged(assistantItemId, Status.COMPLETED);
    }
    clearAssistantTurn();
  }

  private void onModelProblem(
      long eventGeneration, String code, String message, boolean recoverable) {
    if (isStale(eventGeneration)) {
      return;
    }
    port.problemRaised(code, message, recoverable);
  }

  // ---------------------------------------------------------------------------
  // Internal domain helpers
  // ---------------------------------------------------------------------------

  private boolean isStale(long eventGeneration) {
    return eventGeneration != generation;
  }

  private void clearAssistantTurn() {
    assistantItemId = null;
    assistantTextIndex = -1;
    assistantAudioIndex = -1;
    assistantNextContentIndex = 0;
  }

  private TextPart ensureAssistantTextPart() {
    Item item = thread.findItem(assistantItemId);
    if (assistantTextIndex < 0) {
      TextPart part = new TextPart("", false);
      assistantTextIndex = assistantNextContentIndex++;
      item.content().add(part);
      port.partPut(assistantItemId, assistantTextIndex, part);
      return part;
    }
    return (TextPart) item.content().get(assistantTextIndex);
  }

  private AudioPart ensureAssistantAudioPart() {
    Item item = thread.findItem(assistantItemId);
    if (assistantAudioIndex < 0) {
      AudioPart part = new AudioPart(null, false);
      assistantAudioIndex = assistantNextContentIndex++;
      item.content().add(part);
      port.partPut(assistantItemId, assistantAudioIndex, part);
      return part;
    }
    return (AudioPart) item.content().get(assistantAudioIndex);
  }

  private int indexOfFirstAudioPart(Item item) {
    List<RealtimeThread.ContentPart> content = item.content();
    for (int i = 0; i < content.size(); i++) {
      if (content.get(i) instanceof AudioPart) {
        return i;
      }
    }
    return -1;
  }

  private String nextLocalId(String prefix) {
    localItemCounter++;
    return prefix + "_" + thread.id() + "_" + localItemCounter;
  }
}
