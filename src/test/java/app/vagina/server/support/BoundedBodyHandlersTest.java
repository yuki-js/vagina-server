package app.vagina.server.support;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.vagina.server.support.BoundedBodyHandlers.BoundedBodySubscriber;
import app.vagina.server.support.BoundedBodyHandlers.ResponseBodyTooLargeException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;

class BoundedBodyHandlersTest {
  @Test
  void acceptsBodiesWithinAndExactlyAtLimitAcrossChunks() {
    assertArrayEquals(new byte[] {1, 2, 3}, completedBody(4, new byte[] {1}, new byte[] {2, 3}));
    assertArrayEquals(
        new byte[] {1, 2, 3, 4}, completedBody(4, new byte[] {1, 2}, new byte[] {3, 4}));
  }

  @Test
  void cancelsAndFailsWhenCumulativeBodyExceedsLimitByOneByte() {
    HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
    BoundedBodySubscriber<byte[]> subscriber = new BoundedBodySubscriber<>(delegate, 4);
    RecordingSubscription subscription = new RecordingSubscription();
    subscriber.onSubscribe(subscription);
    subscriber.onNext(List.of(ByteBuffer.wrap(new byte[] {1, 2})));
    subscriber.onNext(List.of(ByteBuffer.wrap(new byte[] {3, 4, 5})));

    CompletionException error =
        assertThrows(
            CompletionException.class, () -> subscriber.getBody().toCompletableFuture().join());
    assertInstanceOf(ResponseBodyTooLargeException.class, error.getCause());
    assertTrue(subscription.cancelled);
    assertTrue(subscription.requests > 0);
  }

  @Test
  void cancelsASecondSubscriptionWithoutReplacingTheFirst() {
    HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
    BoundedBodySubscriber<byte[]> subscriber = new BoundedBodySubscriber<>(delegate, 4);
    RecordingSubscription first = new RecordingSubscription();
    RecordingSubscription second = new RecordingSubscription();

    subscriber.onSubscribe(first);
    subscriber.onSubscribe(second);
    subscriber.onNext(List.of(ByteBuffer.wrap(new byte[] {1, 2})));
    subscriber.onComplete();

    assertFalse(first.cancelled);
    assertTrue(second.cancelled);
    assertArrayEquals(new byte[] {1, 2}, subscriber.getBody().toCompletableFuture().join());
  }

  private byte[] completedBody(long limit, byte[]... chunks) {
    HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
    BoundedBodySubscriber<byte[]> subscriber = new BoundedBodySubscriber<>(delegate, limit);
    RecordingSubscription subscription = new RecordingSubscription();
    subscriber.onSubscribe(subscription);
    for (byte[] chunk : chunks) {
      subscriber.onNext(List.of(ByteBuffer.wrap(chunk)));
    }
    subscriber.onComplete();
    return subscriber.getBody().toCompletableFuture().join();
  }

  private static final class RecordingSubscription implements Flow.Subscription {
    private long requests;
    private boolean cancelled;

    @Override
    public void request(long count) {
      requests += count;
    }

    @Override
    public void cancel() {
      cancelled = true;
    }
  }
}
