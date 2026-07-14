package app.vagina.server.realtime.oai_cc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.vagina.server.support.BoundedBodyHandlers.ResponseBodyTooLargeException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class OaiCcClientTest {
  private HttpServer server;

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void boundsSuccessfulSseAndErrorBodies() throws Exception {
    for (int status : List.of(200, 500)) {
      startServer(exchange -> respond(exchange, status, "12345"));
      OaiCcClient client = client(4, Duration.ofSeconds(1));
      List<OaiCcEvent> events = collect(client);

      List<OaiCcEvent.ErrorEvent> errors =
          events.stream().filter(OaiCcEvent.ErrorEvent.class::isInstance).map(OaiCcEvent.ErrorEvent.class::cast).toList();
      assertEquals(1, errors.size());
      assertInstanceOf(
          ResponseBodyTooLargeException.class, rootCause(errors.getFirst().upstreamError()));
      client.dispose();
      stopServer();
      server = null;
    }
  }

  @Test
  void timeoutFlowsAsErrorEventAndRequestTimeoutIsApplied() throws Exception {
    startServer(
        exchange -> {
          try {
            Thread.sleep(500);
            respond(exchange, 200, "data: [DONE]\n\n");
          } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
          }
        });
    OaiCcClient client = client(1024, Duration.ofMillis(50));

    List<OaiCcEvent> events = collect(client);

    OaiCcEvent.ErrorEvent error =
        (OaiCcEvent.ErrorEvent)
            events.stream()
                .filter(OaiCcEvent.ErrorEvent.class::isInstance)
                .findFirst()
                .orElseThrow();
    assertTrue(rootCause(error.upstreamError()) instanceof HttpTimeoutException);
    client.dispose();
  }

  private OaiCcClient client(long maxBytes, Duration timeout) {
    return new OaiCcClient(
        new ObjectMapper(),
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build(),
        maxBytes,
        timeout);
  }

  private List<OaiCcEvent> collect(OaiCcClient client) {
    List<OaiCcEvent> events = new CopyOnWriteArrayList<>();
    client
        .streamCompletions(
            new OaiCcConnectConfig(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"),
                "model",
                "key",
                Map.of()),
            new OaiCcRequest(
                "model",
                List.of(Map.of("role", "user", "content", "hi")),
                false,
                null,
                List.of(),
                false,
                null))
        .subscribe()
        .with(events::add);
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (events.stream().noneMatch(OaiCcEvent.ErrorEvent.class::isInstance)
        && System.nanoTime() < deadline) {
      try {
        Thread.sleep(10);
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        throw new AssertionError(error);
      }
    }
    assertTrue(events.stream().anyMatch(OaiCcEvent.ErrorEvent.class::isInstance));
    return events;
  }

  private Throwable rootCause(Object error) {
    Throwable current = (Throwable) error;
    while (current.getCause() != null) {
      current = current.getCause();
    }
    return current;
  }

  private void startServer(ExchangeHandler handler) throws IOException {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/v1/chat/completions", handler::handle);
    server.start();
  }

  private void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  @FunctionalInterface
  private interface ExchangeHandler {
    void handle(HttpExchange exchange) throws IOException;
  }
}
