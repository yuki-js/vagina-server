package app.vagina.server.realtime.oai_cc;

import app.vagina.server.support.Constants;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.subscription.MultiEmitter;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

/** Thin HTTP/SSE client for OpenAI-compatible Chat Completions streaming. */
public final class OaiCcClient {
  private final HttpClient http;
  private final ObjectMapper json;
  private final OaiCcEvent.Parser parser;
  private ActiveRequest activeRequest;

  public OaiCcClient(ObjectMapper json) {
    this.http = HttpClient.newHttpClient();
    this.json = json;
    this.parser = new OaiCcEvent.Parser(json);
  }

  public Multi<OaiCcEvent> streamCompletions(OaiCcConnectConfig config, OaiCcRequest request) {
    return Multi.createFrom()
        .emitter(
            emitter -> {
              cancelOngoingRequest();
              HttpRequest httpRequest = buildRequest(config, request);
              ActiveRequest requestState = new ActiveRequest();
              LineSubscriber subscriber = new LineSubscriber(emitter, parser, requestState);
              CompletableFuture<HttpResponse<Void>> future =
                  http.sendAsync(httpRequest, responseHandler(emitter, subscriber));
              requestState.future =
                  future.whenComplete(
                      (response, error) -> {
                        if (error != null) {
                          if (!requestState.cancelled.get()) {
                            emitter.emit(
                                new OaiCcEvent.ErrorEvent(
                                    "Chat Completions request failed: " + error.getMessage(),
                                    error));
                          }
                        }
                        emitter.complete();
                      });
              activeRequest = requestState;
              emitter.onTermination(
                  () -> {
                    requestState.cancelled.set(true);
                    future.cancel(true);
                  });
            });
  }

  public void cancelOngoingRequest() {
    ActiveRequest request = activeRequest;
    if (request != null) {
      request.cancelled.set(true);
      if (request.future != null) {
        request.future.cancel(true);
      }
      activeRequest = null;
    }
  }

  public void dispose() {
    cancelOngoingRequest();
    try {
      http.close();
    } catch (Exception ignored) {
      // Best-effort: dispose must stay idempotent and never throw.
    }
  }

  private HttpResponse.BodyHandler<Void> responseHandler(
      MultiEmitter<? super OaiCcEvent> emitter, LineSubscriber subscriber) {
    return responseInfo -> {
      int statusCode = responseInfo.statusCode();
      if (statusCode >= 200 && statusCode < 300) {
        return HttpResponse.BodySubscribers.fromLineSubscriber(subscriber);
      }
      return HttpResponse.BodySubscribers.mapping(
          HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8),
          body -> {
            emitter.emit(
                new OaiCcEvent.ErrorEvent(
                    "Chat Completions API returned HTTP " + statusCode,
                    new HttpError(statusCode, body)));
            return null;
          });
    };
  }

  private HttpRequest buildRequest(OaiCcConnectConfig config, OaiCcRequest request) {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(config.chatCompletionsUri())
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(request.toJson(json)));
    String token = config.apiKey().trim();
    if (!Constants.NO_AUTH_API_KEY.equals(token)) {
      builder.header("Authorization", "Bearer " + token);
      builder.header("api-key", token);
    }
    for (Map.Entry<String, String> header : config.extraHeaders().entrySet()) {
      builder.header(header.getKey(), header.getValue());
    }
    return builder.build();
  }

  private record HttpError(int statusCode, String body) {}

  private static final class ActiveRequest {
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private CompletableFuture<?> future;
  }

  private static final class LineSubscriber implements Flow.Subscriber<String> {
    private final MultiEmitter<? super OaiCcEvent> emitter;
    private final OaiCcEvent.Parser parser;
    private final ActiveRequest request;
    private final AtomicBoolean subscribed = new AtomicBoolean(false);
    private Flow.Subscription subscription;

    private LineSubscriber(
        MultiEmitter<? super OaiCcEvent> emitter, OaiCcEvent.Parser parser, ActiveRequest request) {
      this.emitter = emitter;
      this.parser = parser;
      this.request = request;
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
      this.subscription = subscription;
      if (subscribed.compareAndSet(false, true)) {
        subscription.request(1);
      } else {
        subscription.cancel();
      }
    }

    @Override
    public void onNext(String line) {
      OaiCcEvent event = parser.parseLine(line);
      if (event != null) {
        emitter.emit(event);
      }
      subscription.request(1);
    }

    @Override
    public void onError(Throwable throwable) {
      if (!request.cancelled.get()) {
        emitter.emit(
            new OaiCcEvent.ErrorEvent(
                "Chat Completions stream failed: " + throwable.getMessage(), throwable));
      }
    }

    @Override
    public void onComplete() {
      // Completion is handled by the sendAsync future so HTTP status can be included.
    }
  }
}
