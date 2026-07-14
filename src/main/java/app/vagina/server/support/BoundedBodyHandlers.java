package app.vagina.server.support;

import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** HTTP response body handlers that reject bodies larger than a configured byte limit. */
public final class BoundedBodyHandlers {
  private BoundedBodyHandlers() {}

  public static <T> HttpResponse.BodyHandler<T> bounded(
      HttpResponse.BodyHandler<T> delegate, long maxBytes) {
    if (maxBytes < 0) {
      throw new IllegalArgumentException("Maximum response body size must not be negative");
    }
    return responseInfo -> new BoundedBodySubscriber<>(delegate.apply(responseInfo), maxBytes);
  }

  static final class BoundedBodySubscriber<T> implements HttpResponse.BodySubscriber<T> {
    private final HttpResponse.BodySubscriber<T> delegate;
    private final long maxBytes;
    private Flow.Subscription subscription;
    private long receivedBytes;
    private boolean terminated;

    BoundedBodySubscriber(HttpResponse.BodySubscriber<T> delegate, long maxBytes) {
      this.delegate = delegate;
      this.maxBytes = maxBytes;
    }

    @Override
    public CompletionStage<T> getBody() {
      return delegate.getBody();
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
      if (this.subscription != null) {
        subscription.cancel();
        return;
      }
      this.subscription = subscription;
      delegate.onSubscribe(subscription);
    }

    @Override
    public void onNext(List<ByteBuffer> item) {
      if (terminated) {
        return;
      }
      long chunkBytes = 0;
      for (ByteBuffer buffer : item) {
        chunkBytes += buffer.remaining();
      }
      if (chunkBytes > maxBytes - receivedBytes) {
        terminated = true;
        subscription.cancel();
        delegate.onError(new ResponseBodyTooLargeException(maxBytes));
        return;
      }
      receivedBytes += chunkBytes;
      delegate.onNext(item);
    }

    @Override
    public void onError(Throwable throwable) {
      if (!terminated) {
        terminated = true;
        delegate.onError(throwable);
      }
    }

    @Override
    public void onComplete() {
      if (!terminated) {
        terminated = true;
        delegate.onComplete();
      }
    }
  }

  public static final class ResponseBodyTooLargeException extends RuntimeException {
    public ResponseBodyTooLargeException(long maxBytes) {
      super("HTTP response body exceeded the " + maxBytes + " byte limit");
    }
  }
}
