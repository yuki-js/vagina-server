package app.vagina.server.realtime;

import static org.junit.jupiter.api.Assertions.*;

import app.vagina.server.realtime.model.RealtimeThread;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Tests for the VHRP thread projection wire shapes. */
class ThreadPatchBuilderTest {

  @Test
  void textPartShapeCarriesTextForUserBubbleRendering() {
    // Contract: a text part embedded in add_item / put_part / snapshot must carry its text.
    // Without this, hosted clients rebuild an empty RealtimeThreadTextPart and show a tiny empty
    // chat bubble instead of the user's submitted message.
    RealtimeThread.TextPart part = new RealtimeThread.TextPart("静かに。", true);

    Map<String, Object> shape = ThreadPatchBuilder.partShape(part);

    assertEquals("text", shape.get("type"));
    assertEquals(true, shape.get("isDone"));
    assertEquals("静かに。", shape.get("text"));
  }

  @Test
  void itemShapeCarriesDisplayStateForHostedVisibilityProjection() {
    RealtimeThread.Item item =
        new RealtimeThread.Item(
            "msg_pending",
            RealtimeThread.ItemType.MESSAGE,
            RealtimeThread.ItemRole.USER,
            RealtimeThread.ItemStatus.COMPLETED);
    item.setDisplayState(RealtimeThread.ItemDisplayState.PENDING);

    Map<String, Object> shape = ThreadPatchBuilder.itemShape(item);

    assertEquals("pending", shape.get("displayState"));
  }

  @Test
  void itemShapeEmbedsTextPartTextForCanonicalAddItemAndSnapshot() {
    // Contract: OpenAI conversation.item.added can arrive with a completed input_text part already
    // present. The server projects that item through itemShape for add_item and snapshot. The text
    // must survive this projection so the hosted client can render the user bubble.
    RealtimeThread.Item item =
        new RealtimeThread.Item(
            "msg_1",
            RealtimeThread.ItemType.MESSAGE,
            RealtimeThread.ItemRole.USER,
            RealtimeThread.ItemStatus.COMPLETED);
    item.content().add(new RealtimeThread.TextPart("静かに。", true));

    Map<String, Object> shape = ThreadPatchBuilder.itemShape(item);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> content = (List<Map<String, Object>>) shape.get("content");
    assertNotNull(content);
    assertEquals(1, content.size());
    assertEquals("text", content.get(0).get("type"));
    assertEquals(true, content.get(0).get("isDone"));
    assertEquals("静かに。", content.get(0).get("text"));
  }
}
