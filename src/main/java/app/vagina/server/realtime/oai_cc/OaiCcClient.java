package app.vagina.server.realtime.oai_cc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.subscription.MultiEmitter;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

/** Thin HTTP/SSE client for OpenAI-compatible Chat Completions streaming. */
public final class OaiCcClient {
  private final HttpClient http;
  private final ObjectMapper json;
  private final OaiCcEvent.Parser parser;
  private CompletableFuture<?> activeRequest;

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
              LineSubscriber subscriber = new LineSubscriber(emitter, parser);
              CompletableFuture<HttpResponse<Void>> future =
                  http.sendAsync(httpRequest, HttpResponse.BodyHandlers.fromLineSubscriber(subscriber));
              activeRequest =
                  future.whenComplete(
                      (response, error) -> {
                        if (error != null) {
                          emitter.emit(
                              new OaiCcEvent.ErrorEvent(
                                  "Chat Completions request failed: " + error.getMessage()));
                        } else if (response.statusCode() < 200 || response.statusCode() >= 300) {
                          emitter.emit(
                              new OaiCcEvent.ErrorEvent(
                                  "Chat Completions API returned HTTP " + response.statusCode()));
                        }
                        emitter.complete();
                      });
              emitter.onTermination(() -> future.cancel(true));
            });
  }

  public void cancelOngoingRequest() {
    CompletableFuture<?> request = activeRequest;
    if (request != null) {
      request.cancel(true);
      activeRequest = null;
    }
  }

  public void dispose() {
    cancelOngoingRequest();
  }

  private HttpRequest buildRequest(OaiCcConnectConfig config, OaiCcRequest request) {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(config.chatCompletionsUri())
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(request.toJson(json)));
    if (config.apiKey() != null && !config.apiKey().isBlank()) {
      String token = config.apiKey().trim();
      builder.header("Authorization", "Bearer " + token);
      builder.header("api-key", token);
    }
    for (Map.Entry<String, String> header : config.extraHeaders().entrySet()) {
      builder.header(header.getKey(), header.getValue());
    }
    return builder.build();
  }

  private static final class LineSubscriber implements Flow.Subscriber<String> {
    private final MultiEmitter<? super OaiCcEvent> emitter;
    private final OaiCcEvent.Parser parser;
    private final AtomicBoolean subscribed = new AtomicBoolean(false);
    private Flow.Subscription subscription;

    private LineSubscriber(MultiEmitter<? super OaiCcEvent> emitter, OaiCcEvent.Parser parser) {
      this.emitter = emitter;
      this.parser = parser;
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
      emitter.emit(new OaiCcEvent.ErrorEvent("Chat Completions stream failed: " + throwable.getMessage()));
    }

    @Override
    public void onComplete() {
      // Completion is handled by the sendAsync future so HTTP status can be included.
    }
  }
}
