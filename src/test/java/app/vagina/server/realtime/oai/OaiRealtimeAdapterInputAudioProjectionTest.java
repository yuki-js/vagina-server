package app.vagina.server.realtime.oai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import app.vagina.server.realtime.model.RealtimeAdapterModels;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Regression tests for projecting OpenAI input-audio transcription events into VHRP thread patches. */
class OaiRealtimeAdapterInputAudioProjectionTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private BroadcastProcessor<JsonNode> inboundBus;
  private OaiRealtimeAdapter adapter;
  private List<RealtimeAdapterModels.ThreadPatchOps> patches;

  @BeforeEach
  void setUp() {
    inboundBus = BroadcastProcessor.create();
    OaiRealtimeTransport stub = new StubTransport(inboundBus);
    OaiRealtimeClient client = new OaiRealtimeClient(stub, JSON);
    adapter = new OaiRealtimeAdapter(client);
    patches = new ArrayList<>();
    adapter.threadPatches().subscribe().with(patches::add);
  }

  @Test
  void blankInputAudioTranscriptionNeverEmitsUserAudioItem() throws Exception {
    pushEvent(
        "{\"type\":\"conversation.item.added\","
            + "\"previous_item_id\":null,"
            + "\"item\":{"
            + "\"id\":\"item-empty\","
            + "\"type\":\"message\","
            + "\"status\":\"completed\","
            + "\"role\":\"user\","
            + "\"content\":[{\"type\":\"input_audio\"}]"
            + "}}}");
    assertNull(adapter.thread().findItem("item-empty"));
    assertEquals(0, patches.size());

    pushEvent(
        "{\"type\":\"conversation.item.input_audio_transcription.completed\","
            + "\"item_id\":\"item-empty\","
            + "\"content_index\":0,"
            + "\"transcript\":\"\"}");

    assertNull(adapter.thread().findItem("item-empty"));
    assertEquals(0, patches.size());
  }

  @Test
  void inputAudioItemIsDeferredUntilTranscriptionTextArrives() throws Exception {
    pushEvent(
        "{\"type\":\"conversation.item.added\","
            + "\"previous_item_id\":null,"
            + "\"item\":{"
            + "\"id\":\"item-pending\","
            + "\"type\":\"message\","
            + "\"status\":\"completed\","
            + "\"role\":\"user\","
            + "\"content\":[{\"type\":\"input_audio\",\"transcript\":null}]"
            + "}}}");

    assertNull(adapter.thread().findItem("item-pending"));
    assertEquals(0, patches.size());

    pushEvent(
        "{\"type\":\"conversation.item.input_audio_transcription.delta\","
            + "\"item_id\":\"item-pending\","
            + "\"content_index\":0,"
            + "\"delta\":\"聞\"}");

    assertEquals("visible", adapter.thread().findItem("item-pending").displayState().wireValue());
    assertEquals("add_item", patches.get(0).ops().get(0).get("op"));
    @SuppressWarnings("unchecked")
    Map<String, Object> item = (Map<String, Object>) patches.get(0).ops().get(0).get("item");
    assertEquals("visible", item.get("displayState"));
    assertEquals("append_transcript", patches.get(0).ops().get(2).get("op"));
  }

  @Test
  void nonBlankInputAudioTranscriptionKeepsUserAudioItemAndWritesTranscript() throws Exception {
    pushEvent(
        "{\"type\":\"conversation.item.added\","
            + "\"previous_item_id\":null,"
            + "\"item\":{"
            + "\"id\":\"item-spoken\","
            + "\"type\":\"message\","
            + "\"status\":\"completed\","
            + "\"role\":\"user\","
            + "\"content\":[{\"type\":\"input_audio\"}]"
            + "}}}");
    assertNull(adapter.thread().findItem("item-spoken"));
    assertEquals(0, patches.size());

    pushEvent(
        "{\"type\":\"conversation.item.input_audio_transcription.completed\","
            + "\"item_id\":\"item-spoken\","
            + "\"content_index\":0,"
            + "\"transcript\":\"これでいいですか?\"}");

    assertEquals(1, adapter.thread().items().size());
    assertEquals("item-spoken", adapter.thread().items().get(0).id());
    assertEquals(5, patches.get(0).ops().size());
    assertEquals("add_item", patches.get(0).ops().get(0).get("op"));
    @SuppressWarnings("unchecked")
    Map<String, Object> item = (Map<String, Object>) patches.get(0).ops().get(0).get("item");
    assertEquals("visible", item.get("displayState"));
    assertEquals("replace_transcript", patches.get(0).ops().get(2).get("op"));
    assertEquals("これでいいですか?", patches.get(0).ops().get(2).get("text"));
    assertEquals("put_part", patches.get(0).ops().get(3).get("op"));
    assertEquals("set_status", patches.get(0).ops().get(4).get("op"));
  }

  private void pushEvent(String json) throws Exception {
    inboundBus.onNext(JSON.readTree(json));
  }

  private static final class StubTransport implements OaiRealtimeTransport {

    private final BroadcastProcessor<JsonNode> inbound;
    private final BroadcastProcessor<RealtimeAdapterModels.ConnectionState> stateUpdates =
        BroadcastProcessor.create();
    private final AtomicInteger sendCount = new AtomicInteger(0);

    StubTransport(BroadcastProcessor<JsonNode> inbound) {
      this.inbound = inbound;
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
