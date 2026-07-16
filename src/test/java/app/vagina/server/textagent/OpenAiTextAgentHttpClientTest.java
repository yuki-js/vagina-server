package app.vagina.server.textagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.vagina.server.domain.error.ExternalServiceException;
import app.vagina.server.entity.TextAgentDefinition;
import app.vagina.server.support.Constants;
import app.vagina.server.textagent.TextAgentRuntimeModels.ProviderContext;
import app.vagina.server.textagent.TextAgentRuntimeModels.ProviderSessionState;
import app.vagina.server.textagent.TextAgentRuntimeModels.QueryCommand;
import app.vagina.server.textagent.TextAgentRuntimeModels.TextAgentModelBinding;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;

class OpenAiTextAgentHttpClientTest {
  @Test
  void textAgentRequestTimeoutIsThirtyMinutes() {
    RecordingHttpClient http = new RecordingHttpClient(200, "{\"value\":\"ok\"}");
    OpenAiTextAgentHttpClient client =
        new OpenAiTextAgentHttpClient(new ObjectMapper(), http, 1024);

    client.postJson(
        context(),
        URI.create("https://provider.test/v1/responses"),
        Map.of(),
        TestResponse.class,
        "failed");

    assertEquals(Duration.ofMinutes(30), http.request.timeout().orElseThrow());
    assertEquals(Duration.ofSeconds(10), Constants.SERVER_COMMON_HTTP_TIMEOUT);
  }

  @Test
  void appliesRequestTimeoutAndParsesSuccess() {
    RecordingHttpClient http = new RecordingHttpClient(200, "{\"value\":\"ok\"}");
    OpenAiTextAgentHttpClient client =
        new OpenAiTextAgentHttpClient(new ObjectMapper(), http, 1024, Duration.ofMillis(123));

    TestResponse response =
        client
            .postJson(
                context(),
                URI.create("https://provider.test/v1/responses"),
                Map.of(),
                TestResponse.class,
                "failed")
            .body();

    assertEquals("ok", response.value());
    assertEquals(Duration.ofMillis(123), http.request.timeout().orElseThrow());
  }

  @Test
  void oversizedSuccessAndErrorBodiesAreExternalServiceFailures() {
    for (int status : List.of(200, 401)) {
      RecordingHttpClient http = new RecordingHttpClient(status, "12345");
      OpenAiTextAgentHttpClient client =
          new OpenAiTextAgentHttpClient(new ObjectMapper(), http, 4, Duration.ofSeconds(1));

      assertThrows(
          ExternalServiceException.class,
          () ->
              client.postJson(
                  context(),
                  URI.create("https://provider.test/v1/responses"),
                  Map.of(),
                  TestResponse.class,
                  "failed"));
      assertTrue(http.cancelled.get());
    }
  }

  @Test
  void timeoutRemainsRetryableAndExistingStatusClassificationIsPreserved() {
    RecordingHttpClient timeout = new RecordingHttpClient(new HttpTimeoutException("timeout"));
    OpenAiTextAgentHttpClient timeoutClient =
        new OpenAiTextAgentHttpClient(new ObjectMapper(), timeout, 1024, Duration.ofSeconds(1));
    assertThrows(
        RetryableTextAgentProviderException.class,
        () ->
            timeoutClient.postJson(
                context(),
                URI.create("https://provider.test/v1/responses"),
                Map.of(),
                TestResponse.class,
                "failed"));

    for (int status : List.of(429, 500)) {
      OpenAiTextAgentHttpClient client =
          new OpenAiTextAgentHttpClient(
              new ObjectMapper(),
              new RecordingHttpClient(status, "{\"error\":{\"code\":\"x\",\"type\":\"x\"}}"),
              1024,
              Duration.ofSeconds(1));
      assertThrows(
          RetryableTextAgentProviderException.class,
          () ->
              client.postJson(
                  context(),
                  URI.create("https://provider.test/v1/responses"),
                  Map.of(),
                  TestResponse.class,
                  "failed"));
    }

    OpenAiTextAgentHttpClient authClient =
        new OpenAiTextAgentHttpClient(
            new ObjectMapper(),
            new RecordingHttpClient(401, "{\"error\":{\"code\":\"bad_key\",\"type\":\"auth\"}}"),
            1024,
            Duration.ofSeconds(1));
    OpenAiTextAgentHttpClient.PostJsonResult<TestResponse> auth =
        authClient.postJson(
            context(),
            URI.create("https://provider.test/v1/responses"),
            Map.of(),
            TestResponse.class,
            "failed");
    assertTrue(auth.rejectedByProvider());
    assertEquals("provider_authentication_error", auth.providerFailure().code());
  }

  private ProviderContext context() {
    TextAgentModelBinding binding =
        new TextAgentModelBinding(
            "model", "openai_responses", "https://provider.test/v1", "key", "gpt", null, null);
    return new ProviderContext(
        new TextAgentDefinition(null, 7L, "ta", "name", "prompt", null, "model", "{}", null, null)
            .toTextAgentProviderView(),
        new QueryCommand("voice", "request", "hello", List.of(), null, List.of()),
        new ProviderSessionState("ta", binding));
  }

  record TestResponse(String value) {}

  private static final class RecordingHttpClient extends HttpClient {
    private final int status;
    private final String body;
    private final IOException failure;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private HttpRequest request;

    RecordingHttpClient(int status, String body) {
      this.status = status;
      this.body = body;
      this.failure = null;
    }

    RecordingHttpClient(IOException failure) {
      this.status = 0;
      this.body = null;
      this.failure = failure;
    }

    @Override
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
        throws IOException {
      this.request = request;
      if (failure != null) {
        throw failure;
      }
      HttpResponse.ResponseInfo info =
          new HttpResponse.ResponseInfo() {
            public int statusCode() {
              return status;
            }

            public HttpHeaders headers() {
              return HttpHeaders.of(Map.of(), (a, b) -> true);
            }

            public HttpClient.Version version() {
              return HttpClient.Version.HTTP_1_1;
            }
          };
      HttpResponse.BodySubscriber<T> subscriber = handler.apply(info);
      subscriber.onSubscribe(
          new Flow.Subscription() {
            public void request(long count) {}

            public void cancel() {
              cancelled.set(true);
            }
          });
      subscriber.onNext(List.of(ByteBuffer.wrap(body.getBytes(StandardCharsets.UTF_8))));
      subscriber.onComplete();
      T responseBody;
      try {
        responseBody = subscriber.getBody().toCompletableFuture().join();
      } catch (CompletionException error) {
        if (error.getCause() instanceof RuntimeException runtimeException) {
          throw runtimeException;
        }
        throw error;
      }
      return new FixedResponse<>(request, status, responseBody);
    }

    @Override
    public Optional<CookieHandler> cookieHandler() {
      return Optional.empty();
    }

    @Override
    public Optional<Duration> connectTimeout() {
      return Optional.empty();
    }

    @Override
    public Redirect followRedirects() {
      return Redirect.NEVER;
    }

    @Override
    public Optional<ProxySelector> proxy() {
      return Optional.empty();
    }

    @Override
    public SSLContext sslContext() {
      return null;
    }

    @Override
    public SSLParameters sslParameters() {
      return null;
    }

    @Override
    public Optional<Authenticator> authenticator() {
      return Optional.empty();
    }

    @Override
    public Version version() {
      return Version.HTTP_1_1;
    }

    @Override
    public Optional<Executor> executor() {
      return Optional.empty();
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request, HttpResponse.BodyHandler<T> handler) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request,
        HttpResponse.BodyHandler<T> handler,
        HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
      throw new UnsupportedOperationException();
    }
  }

  private record FixedResponse<T>(HttpRequest request, int statusCode, T body)
      implements HttpResponse<T> {
    public Optional<HttpResponse<T>> previousResponse() {
      return Optional.empty();
    }

    public HttpHeaders headers() {
      return HttpHeaders.of(Map.of(), (a, b) -> true);
    }

    public Optional<SSLSession> sslSession() {
      return Optional.empty();
    }

    public URI uri() {
      return request.uri();
    }

    public HttpClient.Version version() {
      return HttpClient.Version.HTTP_1_1;
    }
  }
}
