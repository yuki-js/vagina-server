package app.vagina.server.realtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import app.vagina.server.realtime.model.RealtimeThread;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ThreadPatchBuilderOrderingTest {

  @Test
  void addItemAfterInsertsCanonicalThreadItemAfterParentAndEmitsPreviousItemId() {
    RealtimeThread thread = new RealtimeThread("thread-1");
    ThreadPatchBuilder patch = new ThreadPatchBuilder(thread);

    patch.addItem(
        "user-1",
        RealtimeThread.ItemType.MESSAGE,
        RealtimeThread.ItemRole.USER,
        RealtimeThread.ItemStatus.COMPLETED);
    patch.addItem(
        "user-2",
        RealtimeThread.ItemType.MESSAGE,
        RealtimeThread.ItemRole.USER,
        RealtimeThread.ItemStatus.COMPLETED);
    patch.addItemAfter(
        "user-1",
        "assistant-1",
        RealtimeThread.ItemType.MESSAGE,
        RealtimeThread.ItemRole.ASSISTANT,
        RealtimeThread.ItemStatus.IN_PROGRESS);

    assertEquals(
        List.of("user-1", "assistant-1", "user-2"),
        thread.items().stream().map(RealtimeThread.Item::id).toList());

    List<Map<String, Object>> ops = patch.drainOps();
    assertEquals("assistant-1", ((Map<?, ?>) ops.get(2).get("item")).get("id"));
    assertEquals("user-1", ops.get(2).get("previousItemId"));
  }
}
