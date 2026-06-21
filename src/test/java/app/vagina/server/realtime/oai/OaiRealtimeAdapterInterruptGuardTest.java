package app.vagina.server.realtime.oai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.vagina.server.realtime.model.RealtimeAdapterModels;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the active-response guard added to {@link OaiRealtimeAdapter#interrupt()}.
 *
 * <p>Uses a stub {@link OaiRealtimeTransport} so no real socket or Quarkus container is needed.
 * Events are pushed via {@link #pushEvent(String)} which formats a minimal JSON frame and writes it
 * onto the stub's inbound stream; the parser inside {@link OaiRealtimeClient} converts it to a
 * typed event and the adapter's subscription updates {@code responseState} synchronously on the
 * same thread (Mutiny {@code BroadcastProcessor} delivers on the publishing thread when no
 * executor override is present).
 */
class OaiRealtimeAdapterInterruptGuardTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** The stub inbound stream: tests push JSON frames here. */
  private BroadcastProcessor<JsonNode> inboundBus;

  /** Tracks every {@code sendJson} call payload, for asserting cancel commands. */
  private List<ObjectNode> sentFrames;

  /** Counts how many times {@code sendJson} was called. */
  private AtomicInteger sendCount;

  private OaiRealtimeAdapter adapter;

  @BeforeEach
  void setUp() {
    inboundBus = BroadcastProcessor.create();
    sentFrames = new ArrayList<>();
    sendCount = new AtomicInteger(0);

    OaiRealtimeTransport stub = new StubTransport(inboundBus, sentFrames, sendCount);
    OaiRealtimeClient client = new OaiRealtimeClient(stub, JSON);
    adapter = new OaiRealtimeAdapter(client);
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /**
   * Pushes a minimal {@code response.created} frame: the real OpenAI payload wraps the response
   * object under the key {@code response}, as parsed by {@link OaiRealtimeEventParser}.
   */
  private void pushResponseCreated(String responseId) throws Exception {
    String json =
        "{\"type\":\"response.created\",\"response\":{\"id\":\""
            + responseId
            + "\",\"status\":\"in_progress\"}}";
    inboundBus.onNext(JSON.readTree(json));
  }

  /**
   * Pushes a minimal {@code response.done} frame with the given terminal status (e.g. {@code
   * "completed"} or {@code "cancelled"}).
   */
  private void pushResponseDone(String responseId, String status) throws Exception {
    String json =
        "{\"type\":\"response.done\",\"response\":{\"id\":\""
            + responseId
            + "\",\"status\":\""
            + status
            + "\"}}";
    inboundBus.onNext(JSON.readTree(json));
  }

  /** Returns true when any sent frame has {@code "type":"response.cancel"}. */
  private boolean cancelWasSent() {
    return sentFrames.stream()
        .anyMatch(f -> "response.cancel".equals(f.path("type").asText()));
  }

  /** Clears the sent-frame log between sub-steps. */
  private void clearSentFrames() {
    sentFrames.clear();
    sendCount.set(0);
  }

  // ---------------------------------------------------------------------------
  // Tests
  // ---------------------------------------------------------------------------

  /**
   * Initial state is {@code UNKNOWN}; {@link OaiRealtimeAdapter#interrupt()} must fall back to
   * sending {@code response.cancel} (legacy behaviour for providers that do not emit
   * {@code response.created}).
   */
  @Test
  void interrupt_initialUnknownState_sendsCancelAsFallback() {
    assertEquals(OaiRealtimeAdapter.ResponseState.UNKNOWN, adapter.responseStateForTest());

    adapter.interrupt().await().indefinitely();

    assertTrue(cancelWasSent(), "cancel must be sent in UNKNOWN state (legacy fallback)");
  }

  /**
   * After {@code response.created}, state becomes {@code ACTIVE} and {@link
   * OaiRealtimeAdapter#interrupt()} must send {@code response.cancel}.
   */
  @Test
  void interrupt_afterResponseCreated_sendsCancelAndStateIsActive() throws Exception {
    pushResponseCreated("resp-1");

    assertEquals(OaiRealtimeAdapter.ResponseState.ACTIVE, adapter.responseStateForTest());

    adapter.interrupt().await().indefinitely();

    assertTrue(cancelWasSent(), "cancel must be sent when an active response is tracked");
  }

  /**
   * After {@code response.done}, state becomes {@code INACTIVE} and {@link
   * OaiRealtimeAdapter#interrupt()} must be a no-op (no {@code response.cancel} sent).
   */
  @Test
  void interrupt_afterResponseDone_noopAndStateIsInactive() throws Exception {
    pushResponseCreated("resp-2");
    pushResponseDone("resp-2", "completed");

    assertEquals(OaiRealtimeAdapter.ResponseState.INACTIVE, adapter.responseStateForTest());

    adapter.interrupt().await().indefinitely();

    assertFalse(cancelWasSent(), "cancel must NOT be sent when no active response exists");
    assertEquals(0, sendCount.get(), "sendJson must not be called when responseState=INACTIVE");
  }

  /**
   * Interrupt is also suppressed when the previous response was cancelled by the provider
   * ({@code response.done} with status {@code "cancelled"}).
   */
  @Test
  void interrupt_afterResponseCancelled_noopAndStateIsInactive() throws Exception {
    pushResponseCreated("resp-3");
    pushResponseDone("resp-3", "cancelled");

    assertEquals(OaiRealtimeAdapter.ResponseState.INACTIVE, adapter.responseStateForTest());

    adapter.interrupt().await().indefinitely();

    assertFalse(cancelWasSent(), "cancel must NOT be sent after provider-side cancellation");
  }

  /**
   * Full lifecycle: UNKNOWN → ACTIVE (response.created) → INACTIVE (response.done) → ACTIVE again
   * (next response.created). Flag must track each transition correctly.
   */
  @Test
  void responseState_tracksLifecycleCorrectly() throws Exception {
    // Start: UNKNOWN
    assertEquals(OaiRealtimeAdapter.ResponseState.UNKNOWN, adapter.responseStateForTest());

    // First response starts
    pushResponseCreated("resp-4a");
    assertEquals(OaiRealtimeAdapter.ResponseState.ACTIVE, adapter.responseStateForTest());

    // First response finishes
    pushResponseDone("resp-4a", "completed");
    assertEquals(OaiRealtimeAdapter.ResponseState.INACTIVE, adapter.responseStateForTest());

    // Second response starts
    pushResponseCreated("resp-4b");
    assertEquals(OaiRealtimeAdapter.ResponseState.ACTIVE, adapter.responseStateForTest());

    // interrupt() in ACTIVE → sends cancel
    clearSentFrames();
    adapter.interrupt().await().indefinitely();
    assertTrue(cancelWasSent(), "cancel must be sent when re-activated response exists");

    // Second response finishes
    pushResponseDone("resp-4b", "completed");
    assertEquals(OaiRealtimeAdapter.ResponseState.INACTIVE, adapter.responseStateForTest());

    // interrupt() in INACTIVE → no-op
    clearSentFrames();
    adapter.interrupt().await().indefinitely();
    assertFalse(cancelWasSent(), "cancel must NOT be sent after second response completes");
  }

  // ---------------------------------------------------------------------------
  // Stub transport
  // ---------------------------------------------------------------------------

  /**
   * Minimal {@link OaiRealtimeTransport} implementation for tests.
   *
   * <ul>
   *   <li>{@link #inboundMessages()} returns the externally-supplied {@link BroadcastProcessor}.
   *   <li>{@link #sendJson(ObjectNode)} records every frame in {@code sentFrames} and increments
   *       {@code sendCount}; returns a resolved {@code Uni<Void>}.
   *   <li>All other methods are no-ops that return resolved Uni/Multi/state as appropriate.
   * </ul>
   */
  private static final class StubTransport implements OaiRealtimeTransport {

    private final BroadcastProcessor<JsonNode> inbound;
    private final List<ObjectNode> sentFrames;
    private final AtomicInteger sendCount;
    private final BroadcastProcessor<RealtimeAdapterModels.ConnectionState> stateUpdates =
        BroadcastProcessor.create();

    StubTransport(
        BroadcastProcessor<JsonNode> inbound,
        List<ObjectNode> sentFrames,
        AtomicInteger sendCount) {
      this.inbound = inbound;
      this.sentFrames = sentFrames;
      this.sendCount = sendCount;
    }

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
      return stateUpdates;
    }

    @Override
    public Uni<Void> connect(OaiRealtimeConnectConfig config) {
      return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Void> sendJson(ObjectNode payload) {
      sentFrames.add(payload.deepCopy());
      sendCount.incrementAndGet();
      return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Void> disconnect() {
      return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Void> dispose() {
      inbound.onComplete();
      stateUpdates.onComplete();
      return Uni.createFrom().voidItem();
    }
  }
}
