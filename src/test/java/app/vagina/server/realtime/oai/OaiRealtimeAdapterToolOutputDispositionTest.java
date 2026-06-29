package app.vagina.server.realtime.oai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import app.vagina.server.realtime.model.RealtimeAdapterModels;
import app.vagina.server.realtime.model.RealtimeThread;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.test.AssertSubscriber;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class OaiRealtimeAdapterToolOutputDispositionTest {

  private final ObjectMapper json = new ObjectMapper();

  @Test
  void sendFunctionOutputProjectsSubmittedErrorDispositionIntoCanonicalThread() {
    TestTransport transport = new TestTransport();
    OaiRealtimeAdapter adapter = new OaiRealtimeAdapter(new OaiRealtimeClient(transport, json));
    AssertSubscriber<RealtimeAdapterModels.ThreadPatchOps> patches =
        adapter.threadPatches().subscribe().withSubscriber(AssertSubscriber.create(10));

    String itemId =
        adapter
            .sendFunctionOutput(
                "call_1",
                "{\"success\":false,\"error\":\"query_text_agent is disabled\"}",
                RealtimeAdapterModels.ToolOutputDisposition.ERROR,
                "query_text_agent is disabled")
            .await()
            .indefinitely();

    transport.emitConversationItemCreated(
        itemId,
        "call_1",
        "{\"success\":false,\"error\":\"query_text_agent is disabled\"}");

    RealtimeThread.Item output = adapter.thread().findItem(itemId);
    assertNotNull(output);
    assertEquals(RealtimeThread.ItemType.FUNCTION_CALL_OUTPUT, output.type());
    assertEquals(RealtimeAdapterModels.ToolOutputDisposition.ERROR, output.toolOutputDisposition());
    assertEquals("query_text_agent is disabled", output.toolErrorMessage());
    assertEquals("error", patchFieldValue(patches, itemId, "toolOutputDisposition"));
    assertEquals("query_text_agent is disabled", patchFieldValue(patches, itemId, "toolErrorMessage"));
  }

  @Test
  void interruptedFunctionOutputProjectsErrorDispositionIntoCanonicalThread() {
    TestTransport transport = new TestTransport();
    OaiRealtimeAdapter adapter = new OaiRealtimeAdapter(new OaiRealtimeClient(transport, json));
    AssertSubscriber<RealtimeAdapterModels.ThreadPatchOps> patches =
        adapter.threadPatches().subscribe().withSubscriber(AssertSubscriber.create(10));
    transport.emitResponseCreated("response_1");
    transport.emitFunctionCallOutputItemAdded(
        "call_item_1", "call_interrupted", "query_text_agent", "{\"question\":\"hello\"}");

    adapter.interrupt().await().indefinitely();
    String itemId = transport.lastFunctionCallOutputItemId();

    transport.emitConversationItemCreated(
        itemId, "call_interrupted", "{\"error\":\"Tool call cancelled by user interrupt.\"}");

    RealtimeThread.Item output = adapter.thread().findItem(itemId);
    assertNotNull(output);
    assertEquals(RealtimeAdapterModels.ToolOutputDisposition.ERROR, output.toolOutputDisposition());
    assertEquals("Tool call cancelled by user interrupt.", output.toolErrorMessage());
    assertEquals("error", patchFieldValue(patches, itemId, "toolOutputDisposition"));
    assertEquals("Tool call cancelled by user interrupt.", patchFieldValue(patches, itemId, "toolErrorMessage"));
  }

  private static String patchFieldValue(
      AssertSubscriber<RealtimeAdapterModels.ThreadPatchOps> patches, String itemId, String field) {
    return patches.getItems().stream()
        .flatMap(patch -> patch.ops().stream())
        .filter(op -> "set_field".equals(op.get("op")))
        .filter(op -> itemId.equals(op.get("itemId")))
        .filter(op -> field.equals(op.get("field")))
        .map(op -> String.valueOf(op.get("value")))
        .reduce((first, second) -> second)
        .orElseThrow(() -> new AssertionError("Missing patch field " + field + " for " + itemId));
  }

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
      sent.add(payload);
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

    void emitResponseCreated(String responseId) {
      ObjectNode response = json.createObjectNode();
      response.put("id", responseId);

      ObjectNode event = json.createObjectNode();
      event.put("type", "response.created");
      event.set("response", response);
      inbound.onNext(event);
    }

    void emitFunctionCallOutputItemAdded(
        String itemId, String callId, String name, String arguments) {
      ObjectNode item = json.createObjectNode();
      item.put("id", itemId);
      item.put("type", "function_call");
      item.put("status", "completed");
      item.put("call_id", callId);
      item.put("name", name);
      item.put("arguments", arguments);

      ObjectNode event = json.createObjectNode();
      event.put("type", "response.output_item.added");
      event.put("response_id", "response_1");
      event.put("output_index", 0);
      event.set("item", item);
      inbound.onNext(event);
    }

    void emitConversationItemCreated(String itemId, String callId, String output) {
      ObjectNode item = json.createObjectNode();
      item.put("id", itemId);
      item.put("type", "function_call_output");
      item.put("call_id", callId);
      item.put("output", output);

      ObjectNode event = json.createObjectNode();
      event.put("type", "conversation.item.created");
      event.set("item", item);
      inbound.onNext(event);
    }

    String lastFunctionCallOutputItemId() {
      return sent.stream()
          .filter(frame -> "conversation.item.create".equals(frame.path("type").asText()))
          .map(frame -> frame.path("item"))
          .filter(item -> "function_call_output".equals(item.path("type").asText()))
          .map(item -> item.path("id").asText())
          .reduce((first, second) -> second)
          .orElseThrow(() -> new AssertionError("No function_call_output command was sent"));
    }
  }
}
