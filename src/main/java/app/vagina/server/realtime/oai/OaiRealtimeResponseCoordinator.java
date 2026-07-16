package app.vagina.server.realtime.oai;

import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Serializes response creation and cancellation for the provider's default conversation.
 *
 * <p>The provider accepts conversation items independently from generation commands but permits
 * only one response at a time. This coordinator is therefore the sole owner of create-pending,
 * active, cancel-pending, conflict retry, latest-turn-wins, and response-scoped tool barrier state.
 */
final class OaiRealtimeResponseCoordinator {
  private static final String ACTIVE_RESPONSE_CONFLICT = "conversation_already_has_active_response";
  private static final String RESPONSE_CANCEL_NOT_ACTIVE = "response_cancel_not_active";

  interface Commands {
    Uni<Void> createResponse(String eventId);

    Uni<Void> cancelResponse(String eventId);

    void emitError(String code, String message, Object cause);
  }

  enum State {
    UNKNOWN,
    ACTIVE,
    INACTIVE
  }

  private enum Phase {
    IDLE,
    CREATE_PENDING,
    ACTIVE,
    CANCEL_PENDING
  }

  private record Generation(long number, String reason, int conflictRetries) {
    Generation retry() {
      return new Generation(number, reason, conflictRetries + 1);
    }
  }

  /** One response-scoped tool barrier; call IDs never leak into a later response batch. */
  private static final class ToolBatch {
    private final String responseId;
    private final Set<String> expectedCallIds = new HashSet<>();
    private final Set<String> submittedCallIds = new HashSet<>();
    private boolean terminal;
    private boolean interrupted;
    private boolean continuationRequested;

    private ToolBatch(String responseId) {
      this.responseId = responseId;
    }

    private boolean isReady() {
      return terminal
          && !interrupted
          && !continuationRequested
          && !expectedCallIds.isEmpty()
          && submittedCallIds.containsAll(expectedCallIds);
    }
  }

  private final Commands commands;
  private final Map<String, ToolBatch> toolBatchesByResponseId = new HashMap<>();
  private final Map<String, ToolBatch> toolBatchesByItemId = new HashMap<>();
  private final Map<String, ToolBatch> toolBatchesByCallId = new HashMap<>();

  private Phase phase = Phase.IDLE;
  private long generationCounter;
  private long commandCounter;
  private Generation inFlightGeneration;
  private Generation queuedGeneration;
  private String createEventId;
  private String cancelEventId;
  private String activeResponseId;
  private boolean cancelRequested;
  private boolean waitForAnyTerminalAfterConflict;

  OaiRealtimeResponseCoordinator(Commands commands) {
    this.commands = commands;
  }

  /** While busy, a newer request replaces queued work and cancels the current generation. */
  synchronized Uni<Void> requestGeneration(String reason) {
    Generation generation = new Generation(++generationCounter, reason, 0);
    if (phase == Phase.IDLE) {
      return startGenerationLocked(generation);
    }
    if (!reason.startsWith("tools:")) {
      markCurrentToolBatchInterruptedLocked();
    }
    queuedGeneration = generation;
    return requestCancelLocked();
  }

  /** Cancels active or create-pending work and discards queued generation authority. */
  synchronized Uni<Void> interrupt() {
    markCurrentToolBatchInterruptedLocked();
    queuedGeneration = null;
    if (phase == Phase.IDLE) {
      return Uni.createFrom().voidItem();
    }
    return requestCancelLocked();
  }

  synchronized void onResponseCreated(String responseId) {
    if (isBlank(responseId)) {
      return;
    }
    activeResponseId = responseId;
    toolBatchesByResponseId.computeIfAbsent(responseId, ToolBatch::new);
    phase = cancelRequested ? Phase.CANCEL_PENDING : Phase.ACTIVE;
  }

  synchronized void onResponseDone(String responseId) {
    ToolBatch batch = toolBatchesByResponseId.get(responseId);
    if (batch != null) {
      batch.terminal = true;
    }

    boolean matchesCurrent =
        Objects.equals(activeResponseId, responseId)
            || (waitForAnyTerminalAfterConflict && activeResponseId == null);
    if (!matchesCurrent) {
      return;
    }
    phase = Phase.IDLE;
    activeResponseId = null;
    inFlightGeneration = null;
    createEventId = null;
    cancelEventId = null;
    cancelRequested = false;
    waitForAnyTerminalAfterConflict = false;

    if (queuedGeneration == null && batch != null) {
      requestToolContinuationIfReadyLocked(batch);
    }
    startQueuedGenerationLocked();
  }

  /**
   * Bridges split function-call events: item ownership arrives with a response ID, while a later
   * arguments event may be the first event carrying the call ID.
   */
  synchronized void trackToolCall(String responseId, String itemId, String callId) {
    ToolBatch batch = null;
    if (!isBlank(responseId)) {
      batch = toolBatchesByResponseId.computeIfAbsent(responseId, ToolBatch::new);
      if (!isBlank(itemId)) {
        toolBatchesByItemId.put(itemId, batch);
      }
    } else if (!isBlank(itemId)) {
      batch = toolBatchesByItemId.get(itemId);
    }
    if (batch == null || isBlank(callId)) {
      Log.debugf(
          "Realtime tool batch correlation pending: responseId=%s itemId=%s callId=%s batchFound=%s",
          responseId, itemId, callId, batch != null);
      return;
    }
    batch.expectedCallIds.add(callId);
    toolBatchesByCallId.put(callId, batch);
  }

  synchronized Uni<Void> onToolOutputSubmitted(String callId) {
    ToolBatch batch = toolBatchesByCallId.get(callId);
    if (batch == null) {
      return requestGeneration("tool:" + callId);
    }
    batch.submittedCallIds.add(callId);
    if (!batch.isReady()) {
      return Uni.createFrom().voidItem();
    }
    batch.continuationRequested = true;
    return requestGeneration("tools:" + batch.responseId);
  }

  /**
   * Consumes provider errors that are state-machine signals at this boundary: cancel-not-active means
   * the idempotent cancel postcondition is already satisfied, while a correlated active-response
   * conflict gets one retry after the provider's current response terminates.
   */
  synchronized boolean onProviderError(OaiRealtimeEvent.ErrorDetail detail) {
    if (isCorrelatedCancelNotActive(detail)) {
      completeCancellationLocked();
      return true;
    }
    if (!ACTIVE_RESPONSE_CONFLICT.equals(detail.code())
        || !Objects.equals(createEventId, detail.eventId())
        || inFlightGeneration == null) {
      return false;
    }
    if (inFlightGeneration.conflictRetries() >= 1) {
      return false;
    }
    queuedGeneration = inFlightGeneration.retry();
    inFlightGeneration = null;
    createEventId = null;
    cancelEventId = null;
    activeResponseId = null;
    cancelRequested = false;
    phase = Phase.ACTIVE;
    waitForAnyTerminalAfterConflict = true;
    return true;
  }

  synchronized State state() {
    return switch (phase) {
      case IDLE -> State.INACTIVE;
      case CREATE_PENDING -> State.UNKNOWN;
      case ACTIVE, CANCEL_PENDING -> State.ACTIVE;
    };
  }

  private Uni<Void> startGenerationLocked(Generation generation) {
    phase = Phase.CREATE_PENDING;
    inFlightGeneration = generation;
    createEventId = nextCommandIdLocked("create", generation.number());
    cancelEventId = null;
    cancelRequested = false;
    return commands.createResponse(createEventId);
  }

  private Uni<Void> requestCancelLocked() {
    if (cancelRequested) {
      return Uni.createFrom().voidItem();
    }
    cancelRequested = true;
    phase = Phase.CANCEL_PENDING;
    cancelEventId = nextCommandIdLocked("cancel", generationCounter);
    return commands.cancelResponse(cancelEventId);
  }

  private String nextCommandIdLocked(String operation, long generation) {
    return "vagina_" + operation + "_" + generation + "_" + (++commandCounter);
  }

  private void startQueuedGenerationLocked() {
    Generation next = queuedGeneration;
    queuedGeneration = null;
    if (next == null) {
      return;
    }
    startGenerationLocked(next)
        .subscribe()
        .with(
            ignored -> {},
            error ->
                commands.emitError(
                    "response_create_failed", "Failed to start queued Realtime response.", error));
  }

  private void requestToolContinuationIfReadyLocked(ToolBatch batch) {
    if (!batch.isReady()) {
      return;
    }
    batch.continuationRequested = true;
    requestGeneration("tools:" + batch.responseId)
        .subscribe()
        .with(
            ignored -> {},
            error ->
                commands.emitError(
                    "response_create_failed",
                    "Failed to continue Realtime response after tool outputs.",
                    error));
  }

  private void markCurrentToolBatchInterruptedLocked() {
    if (activeResponseId == null) {
      return;
    }
    ToolBatch batch = toolBatchesByResponseId.get(activeResponseId);
    if (batch != null) {
      batch.interrupted = true;
    }
  }

  private boolean isCorrelatedCancelNotActive(OaiRealtimeEvent.ErrorDetail detail) {
    return RESPONSE_CANCEL_NOT_ACTIVE.equals(detail.code())
        && cancelRequested
        && Objects.equals(cancelEventId, detail.eventId());
  }

  private void completeCancellationLocked() {
    phase = Phase.IDLE;
    activeResponseId = null;
    inFlightGeneration = null;
    createEventId = null;
    cancelEventId = null;
    cancelRequested = false;
    waitForAnyTerminalAfterConflict = false;
    startQueuedGenerationLocked();
  }

  private static boolean isBlank(String value) {
    return value == null || value.isEmpty();
  }
}
