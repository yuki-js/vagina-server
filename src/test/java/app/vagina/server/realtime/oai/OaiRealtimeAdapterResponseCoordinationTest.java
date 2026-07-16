package app.vagina.server.realtime.oai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.vagina.server.realtime.model.RealtimeAdapterModels;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.test.AssertSubscriber;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * Regression contracts for default-conversation response coordination.
 *
 * <p>The Realtime provider accepts conversation items independently from {@code response.create},
 * but permits only one response writing to the default conversation at a time. A user turn can
 * therefore be present in provider history while its competing create is rejected, leaving the UI
 * connected but permanently unanswered. These tests reproduce the event order observed against the
 * real provider and protect the adapter boundary where turns, interrupts, and tool continuations
 * must be serialized.
 */
class OaiRealtimeAdapterResponseCoordinationTest {
  private final ObjectMapper json = new ObjectMapper();

  /**
   * A response is create-pending from the moment its command is sent, not only after the provider
   * echoes {@code response.created}. An interrupt in that window must cancel A, retain A's user
   * item as history, and defer B's generation until A reaches a terminal event. Without this
   * contract the adapter sends two creates; the provider accepts both user items but rejects B's
   * create.
   */
  @Test
  void interruptDuringCreatePendingCancelsPendingGenerationAndStartsOnlyLatestTurn() {
    TestTransport transport = new TestTransport();
    OaiRealtimeAdapter adapter = adapter(transport);

    adapter.sendText("turn A").await().indefinitely();
    assertEquals(1, transport.countSent("response.create"));

    adapter.interrupt().await().indefinitely();
    adapter.sendText("turn B").await().indefinitely();

    assertEquals(1, transport.countSent("response.cancel"));
    assertEquals(1, transport.countSent("response.create"));

    // The provider acknowledges A only after cancel was already requested. It then terminates A;
    // that terminal boundary releases exactly one queued generation, the latest turn B.
    transport.emitResponseCreated("response_A");
    transport.emitResponseDone("response_A", "cancelled");

    assertEquals(2, transport.countSent("response.create"));
  }

  /**
   * One provider response may contain several function calls whose client executions finish at
   * different times. Each output item is submitted immediately, but inference must resume only once
   * the complete response-scoped batch has outputs. Starting after the first output makes a slower
   * output's later create conflict with the already-active continuation and permanently loses that
   * continuation.
   */
  @Test
  void multipleToolOutputsStartExactlyOneContinuationAfterTheWholeBatchCompletes() {
    TestTransport transport = new TestTransport();
    OaiRealtimeAdapter adapter = adapter(transport);
    transport.emitResponseCreated("response_tools");
    // The real provider first announces function-call items without call IDs, then supplies the
    // correlation keys in later arguments-done events. The response batch must retain
    // item-to-response
    // ownership across that split event sequence.
    transport.emitFunctionCallAddedWithoutCallId("response_tools", 0, "item_1");
    transport.emitFunctionCallAddedWithoutCallId("response_tools", 1, "item_2");
    transport.emitFunctionArgumentsDone("item_1", "call_1", "first");
    transport.emitFunctionArgumentsDone("item_2", "call_2", "second");
    transport.emitResponseDone("response_tools", "completed");

    adapter
        .sendFunctionOutput(
            "call_1", "one", RealtimeAdapterModels.ToolOutputDisposition.SUCCESS, null)
        .await()
        .indefinitely();

    assertEquals(0, transport.countSent("response.create"));

    adapter
        .sendFunctionOutput(
            "call_2", "two", RealtimeAdapterModels.ToolOutputDisposition.SUCCESS, null)
        .await()
        .indefinitely();

    assertEquals(2, transport.countSent("conversation.item.create"));
    assertEquals(1, transport.countSent("response.create"));
  }

  /**
   * Lifecycle events are response-ID scoped. A delayed duplicate terminal event for A must not mark
   * a younger response B inactive; otherwise a subsequent interrupt is silently skipped and B keeps
   * generating despite the user's barge-in.
   */
  @Test
  void staleDoneDoesNotClearAYoungerActiveResponse() {
    TestTransport transport = new TestTransport();
    OaiRealtimeAdapter adapter = adapter(transport);

    adapter.sendText("turn A").await().indefinitely();
    transport.emitResponseCreated("response_A");
    adapter.interrupt().await().indefinitely();
    adapter.sendText("turn B").await().indefinitely();
    transport.emitResponseDone("response_A", "cancelled");
    transport.emitResponseCreated("response_B");

    // This duplicate/stale A event must be ignored for B's lifecycle.
    transport.emitResponseDone("response_A", "cancelled");
    adapter.interrupt().await().indefinitely();

    assertEquals(2, transport.countSent("response.cancel"));
  }

  /**
   * The provider correlates a rejected create through {@code error.event_id}. The owning latest
   * generation waits for the provider's current response to terminate and retries once. A second
   * correlated conflict is terminal and must reach the adapter error stream instead of looping or
   * silently abandoning the turn.
   */
  @Test
  void correlatedActiveResponseConflictRetriesOnceThenReportsASecondFailure() {
    TestTransport transport = new TestTransport();
    OaiRealtimeAdapter adapter = adapter(transport);
    AssertSubscriber<RealtimeAdapterModels.Error> errors =
        adapter.errors().subscribe().withSubscriber(AssertSubscriber.create(1));

    adapter.sendText("latest turn").await().indefinitely();
    String firstCreateEventId = transport.lastEventId("response.create");
    transport.emitError(
        "conversation_already_has_active_response", firstCreateEventId, "response already active");

    assertEquals(1, transport.countSent("response.create"));

    // The conflicting provider response is not owned by this create, so any next terminal response
    // boundary releases the single queued retry.
    transport.emitResponseDone("provider_existing_response", "completed");
    assertEquals(2, transport.countSent("response.create"));

    String retryEventId = transport.lastEventId("response.create");
    transport.emitError(
        "conversation_already_has_active_response", retryEventId, "response still active");

    errors.assertItems(
        new RealtimeAdapterModels.Error(
            "conversation_already_has_active_response",
            "response still active",
            new OaiRealtimeEvent.ErrorDetail(
                "invalid_request_error",
                "conversation_already_has_active_response",
                "response still active",
                null,
                retryEventId)));
    assertEquals(2, transport.countSent("response.create"));
  }

  @Test
  void emptyAudioCommitProviderErrorIsIgnored() {
    TestTransport transport = new TestTransport();
    OaiRealtimeAdapter adapter = adapter(transport);
    AssertSubscriber<RealtimeAdapterModels.Error> errors =
        adapter.errors().subscribe().withSubscriber(AssertSubscriber.create(1));

    transport.emitError("input_audio_buffer_commit_empty", null, "empty audio buffer");

    assertTrue(errors.getItems().isEmpty());
  }

  @Test
  void cancelNotActiveCompletesCancellationAsIdempotentNoOpAndStartsQueuedTurn() {
    TestTransport transport = new TestTransport();
    OaiRealtimeAdapter adapter = adapter(transport);
    AssertSubscriber<RealtimeAdapterModels.Error> errors =
        adapter.errors().subscribe().withSubscriber(AssertSubscriber.create(1));

    adapter.sendText("turn A").await().indefinitely();
    transport.emitResponseCreated("response_A");
    adapter.sendText("turn B").await().indefinitely();
    String cancelEventId = transport.lastEventId("response.cancel");

    transport.emitError("response_cancel_not_active", cancelEventId, "no active response");

    assertTrue(errors.getItems().isEmpty());
    assertEquals(2, transport.countSent("response.create"));
  }

  private OaiRealtimeAdapter adapter(TestTransport transport) {
    return new OaiRealtimeAdapter(new OaiRealtimeClient(transport, json));
  }

  /**
   * Synchronous transport seam: inbound events are delivered on the publishing test thread and all
   * outbound commands are retained in order. This deliberately models protocol ordering without
   * introducing sleeps, sockets, or provider nondeterminism into the normal test suite.
   */
  private final class TestTransport implements OaiRealtimeTransport {
    private final BroadcastProcessor<JsonNode> inbound = BroadcastProcessor.create();
    private final CopyOnWriteArrayList<ObjectNode> sent = new CopyOnWriteArrayList<>();

    @Override
    public Multi<JsonNode> inboundMessages() {
      return inbound;
    }

    @Override
    public RealtimeAdapterModels.ConnectionState connectionState() {
      return RealtimeAdapterModels.ConnectionState.connected();
    }

    @Override
    public Multi<RealtimeAdapterModels.ConnectionState> connectionStateUpdates() {
      return Multi.createFrom().empty();
    }

    @Override
    public Uni<Void> connect(OaiRealtimeConnectConfig config) {
      return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Void> sendJson(ObjectNode payload) {
      sent.add(payload.deepCopy());
      return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Void> disconnect() {
      return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Void> dispose() {
      return Uni.createFrom().voidItem();
    }

    long countSent(String type) {
      return sent.stream().filter(frame -> type.equals(frame.path("type").asText())).count();
    }

    String lastEventId(String type) {
      return sent.stream()
          .filter(frame -> type.equals(frame.path("type").asText()))
          .map(frame -> frame.path("event_id").asText())
          .reduce((first, second) -> second)
          .orElseThrow();
    }

    /** Emits the provider acknowledgement that binds the pending create to a response ID. */
    void emitResponseCreated(String responseId) {
      ObjectNode response =
          json.createObjectNode().put("id", responseId).put("status", "in_progress");
      inbound.onNext(
          json.createObjectNode().put("type", "response.created").set("response", response));
    }

    /** Emits a response-ID-scoped terminal boundary. */
    void emitResponseDone(String responseId, String status) {
      ObjectNode response = json.createObjectNode().put("id", responseId).put("status", status);
      inbound.onNext(
          json.createObjectNode().put("type", "response.done").set("response", response));
    }

    /** Emits a provider error correlated to one outbound client command. */
    void emitError(String code, String eventId, String message) {
      ObjectNode error =
          json.createObjectNode()
              .put("type", "invalid_request_error")
              .put("code", code)
              .put("message", message)
              .put("event_id", eventId);
      inbound.onNext(json.createObjectNode().put("type", "error").set("error", error));
    }

    /** Emits the provider's initial function-call item before its call ID is known. */
    void emitFunctionCallAddedWithoutCallId(String responseId, int outputIndex, String itemId) {
      ObjectNode item =
          json.createObjectNode()
              .put("id", itemId)
              .put("type", "function_call")
              .put("status", "in_progress");
      ObjectNode event =
          json.createObjectNode()
              .put("type", "response.output_item.added")
              .put("response_id", responseId)
              .put("output_index", outputIndex);
      event.set("item", item);
      inbound.onNext(event);
    }

    /** Completes the split function-call event with the provider correlation key. */
    void emitFunctionArgumentsDone(String itemId, String callId, String name) {
      inbound.onNext(
          json.createObjectNode()
              .put("type", "response.function_call_arguments.done")
              .put("item_id", itemId)
              .put("call_id", callId)
              .put("name", name)
              .put("arguments", "{}"));
    }
  }
}
