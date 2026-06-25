package app.vagina.server.realtime;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test-only CDI {@link Alternative} that replaces {@link ConfigRealtimeAdapterFactory}.
 *
 * <p>Being {@code @Alternative @Priority(1)} makes Quarkus CDI pick this bean over the production
 * {@link ConfigRealtimeAdapterFactory} (which has no explicit priority). No production code is
 * changed; this class lives entirely in {@code src/test/java}.
 *
 * <p>Every call to {@link #create(String)} allocates a fresh {@link FakeRealtimeAdapter}, registers
 * it in the internal list, and returns it. Tests retrieve the latest (or a specific) adapter via
 * {@link #lastCreated()} / {@link #allCreated()} and drive it to simulate assistant responses or
 * errors.
 *
 * <p>The factory is {@code @ApplicationScoped} so it outlives individual sessions and test methods;
 * call {@link #reset()} in {@code @AfterEach} to clear adapter history between tests.
 */
@ApplicationScoped
@Alternative
@Priority(1)
public class FakeRealtimeAdapterFactory implements RealtimeAdapterFactory {

  /** Thread-safe log of adapters created during the test run, keyed by creation order. */
  private final CopyOnWriteArrayList<FakeRealtimeAdapter> created = new CopyOnWriteArrayList<>();

  /** Keeps a per-modelId counter so each adapter gets a unique thread id in its fake thread. */
  private final AtomicInteger threadCounter = new AtomicInteger(0);

  /**
   * Creates a fresh {@link FakeRealtimeAdapter} for the supplied {@code modelId}, regardless of
   * whether that model id exists in the configuration. The test can use any model id string.
   */
  @Override
  public RealtimeAdapter create(String modelId) throws UnknownModelException {
    String threadId = "fake-thread-" + threadCounter.incrementAndGet();
    FakeRealtimeAdapter adapter = new FakeRealtimeAdapter(threadId);
    created.add(adapter);
    return adapter;
  }

  // =========================================================================
  // Test control API
  // =========================================================================

  /**
   * Returns the most recently created adapter, or {@code null} if none has been created yet. Use
   * this after a WebSocket {@code session.open} has been processed.
   */
  public FakeRealtimeAdapter lastCreated() {
    if (created.isEmpty()) {
      return null;
    }
    return created.get(created.size() - 1);
  }

  /** Returns all adapters created since the last {@link #reset()}, in creation order. */
  public java.util.List<FakeRealtimeAdapter> allCreated() {
    return java.util.List.copyOf(created);
  }

  /**
   * Clears the adapter history. Should be called from {@code @AfterEach} to prevent state leakage
   * between test methods.
   */
  public void reset() {
    created.clear();
  }
}
