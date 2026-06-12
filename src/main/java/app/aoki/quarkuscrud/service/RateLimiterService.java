package app.aoki.quarkuscrud.service;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.jboss.logging.Logger;

@ApplicationScoped
public class RateLimiterService {

  private static final Logger LOG = Logger.getLogger(RateLimiterService.class);

  // Rate limit configuration
  private static final int PER_USER_LIMIT = 100; // requests per minute
  private static final int GLOBAL_LIMIT = 300; // requests per minute
  private static final long WINDOW_SIZE_MILLIS = 60_000; // 1 minute

  // In-memory storage for rate limiting
  private final Map<Long, RateLimitWindow> userWindows = new ConcurrentHashMap<>();
  private final RateLimitWindow globalWindow = new RateLimitWindow();

  public boolean allowRequest(Long userId) {
    long now = System.currentTimeMillis();

    // Check global rate limit
    if (!checkLimit(globalWindow, GLOBAL_LIMIT, now)) {
      LOG.warnf("Global rate limit exceeded");
      return false;
    }

    // Check per-user rate limit
    RateLimitWindow userWindow = userWindows.computeIfAbsent(userId, k -> new RateLimitWindow());
    if (!checkLimit(userWindow, PER_USER_LIMIT, now)) {
      LOG.warnf("User %d rate limit exceeded", userId);
      return false;
    }

    // Increment counters
    globalWindow.increment();
    userWindow.increment();

    return true;
  }

  private boolean checkLimit(RateLimitWindow window, int limit, long now) {
    // Reset window if expired
    if (now - window.getWindowStart() >= WINDOW_SIZE_MILLIS) {
      window.reset(now);
    }

    return window.getCount() < limit;
  }

  private static class RateLimitWindow {
    private volatile long windowStart;
    private final AtomicInteger count;

    RateLimitWindow() {
      this.windowStart = System.currentTimeMillis();
      this.count = new AtomicInteger(0);
    }

    void reset(long newStart) {
      this.windowStart = newStart;
      this.count.set(0);
    }

    void increment() {
      this.count.incrementAndGet();
    }

    int getCount() {
      return this.count.get();
    }

    long getWindowStart() {
      return this.windowStart;
    }
  }

  // Cleanup method to remove old user windows (can be called periodically)
  public void cleanup() {
    long now = System.currentTimeMillis();
    userWindows
        .entrySet()
        .removeIf(
            entry -> now - entry.getValue().getWindowStart() >= WINDOW_SIZE_MILLIS * 2); // Keep
    // for 2
    // windows
  }
}
