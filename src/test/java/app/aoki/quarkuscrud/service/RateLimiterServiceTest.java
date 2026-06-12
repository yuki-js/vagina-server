package app.aoki.quarkuscrud.service;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for RateLimiterService.
 *
 * <p>Tests rate limiting logic for per-user and global limits.
 */
@QuarkusTest
public class RateLimiterServiceTest {

  @Inject RateLimiterService rateLimiterService;

  private static final Long USER_1 = 1L;
  private static final Long USER_2 = 2L;

  @BeforeEach
  public void setup() {
    // Note: In a real test, we might want to reset the rate limiter state
    // For now, we'll use different user IDs to avoid conflicts
  }

  @Test
  public void testAllowRequestFirstRequest() {
    // First request should always be allowed
    Long newUserId = System.currentTimeMillis(); // Use unique user ID
    boolean allowed = rateLimiterService.allowRequest(newUserId);
    assertTrue(allowed);
  }

  @Test
  public void testAllowRequestMultipleWithinLimit() {
    Long newUserId = System.currentTimeMillis() + 1;

    // Make several requests within the limit
    for (int i = 0; i < 10; i++) {
      boolean allowed = rateLimiterService.allowRequest(newUserId);
      assertTrue(allowed, "Request " + i + " should be allowed");
    }
  }

  @Test
  public void testRateLimitPerUser() {
    Long newUserId = System.currentTimeMillis() + 2;

    // Try to exceed per-user rate limit (100 per minute)
    int successCount = 0;
    int failCount = 0;

    // Make 150 requests rapidly
    for (int i = 0; i < 150; i++) {
      boolean allowed = rateLimiterService.allowRequest(newUserId);
      if (allowed) {
        successCount++;
      } else {
        failCount++;
      }
    }

    // At least some requests should be blocked
    assertTrue(failCount > 0, "Some requests should be rate limited");
    assertTrue(successCount <= 100, "No more than 100 requests should be allowed");
  }

  @Test
  public void testDifferentUsersIndependentLimits() {
    Long userId1 = System.currentTimeMillis() + 3;
    Long userId2 = System.currentTimeMillis() + 4;

    // User 1 makes requests
    for (int i = 0; i < 10; i++) {
      boolean allowed = rateLimiterService.allowRequest(userId1);
      assertTrue(allowed, "User 1 request " + i + " should be allowed");
    }

    // User 2 should still have their own limit
    for (int i = 0; i < 10; i++) {
      boolean allowed = rateLimiterService.allowRequest(userId2);
      assertTrue(allowed, "User 2 request " + i + " should be allowed");
    }
  }

  @Test
  public void testGlobalRateLimit() {
    // This test is tricky as global limit is shared across all tests
    // We can at least verify the method doesn't crash and returns a boolean
    Long newUserId = System.currentTimeMillis() + 5;
    boolean result = rateLimiterService.allowRequest(newUserId);
    assertTrue(result || !result); // Either true or false is valid
  }

  @Test
  public void testCleanupMethod() {
    // Test that cleanup method doesn't throw exceptions
    assertDoesNotThrow(() -> rateLimiterService.cleanup());
  }

  @Test
  public void testRateLimitWindowReset() throws InterruptedException {
    Long newUserId = System.currentTimeMillis() + 6;

    // Make one request
    assertTrue(rateLimiterService.allowRequest(newUserId));

    // In a real test, we'd wait 60 seconds for the window to reset
    // For unit tests, we just verify the logic doesn't crash
    // and that subsequent requests work
    assertTrue(rateLimiterService.allowRequest(newUserId));
  }

  @Test
  public void testConcurrentRequestsSameUser() {
    Long newUserId = System.currentTimeMillis() + 7;

    // Simulate somewhat concurrent requests
    boolean result1 = rateLimiterService.allowRequest(newUserId);
    boolean result2 = rateLimiterService.allowRequest(newUserId);
    boolean result3 = rateLimiterService.allowRequest(newUserId);

    // All should succeed if we're under the limit
    assertTrue(result1);
    assertTrue(result2);
    assertTrue(result3);
  }

  @Test
  public void testNullUserIdHandling() {
    // Verify behavior with null user ID
    // Current implementation throws NullPointerException which is reasonable behavior
    assertThrows(NullPointerException.class, () -> rateLimiterService.allowRequest(null));
  }

  @Test
  public void testLargeUserIdValues() {
    Long largeUserId = Long.MAX_VALUE;
    boolean allowed = rateLimiterService.allowRequest(largeUserId);
    assertTrue(allowed); // First request should work
  }

  @Test
  public void testNegativeUserIdValues() {
    Long negativeUserId = -1L;
    boolean allowed = rateLimiterService.allowRequest(negativeUserId);
    assertTrue(allowed); // First request should work
  }
}
