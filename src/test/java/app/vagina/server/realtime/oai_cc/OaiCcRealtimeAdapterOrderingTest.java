package app.vagina.server.realtime.oai_cc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.vagina.server.realtime.RealtimeModelsConfig;
import app.vagina.server.realtime.model.RealtimeAdapterModels;
import app.vagina.server.realtime.model.RealtimeThread;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class OaiCcRealtimeAdapterOrderingTest {
  private HttpServer server;

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void preservesProviderOrderAcrossPreambleMultipleToolsOutputsAndContinuation() throws Exception {
    AtomicInteger requestCount = new AtomicInteger();
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          int currentRequest = requestCount.incrementAndGet();
          if (currentRequest == 1) {
            respondWithSse(
                exchange,
                "data: {\"choices\":[{\"delta\":{\"content\":\"Before tools.\"}}]}\n\n"
                    + "data: {\"choices\":[{\"delta\":{\"tool_calls\":["
                    + "{\"index\":0,\"id\":\"call_1\",\"function\":{\"name\":\"first\",\"arguments\":\"{}\"}},"
                    + "{\"index\":1,\"id\":\"call_2\",\"function\":{\"name\":\"second\",\"arguments\":\"{}\"}}]}}]}\n\n"
                    + "data: {\"choices\":[{\"finish_reason\":\"tool_calls\",\"delta\":{}}]}\n\n"
                    + "data: [DONE]\n\n");
          } else {
            respondWithSse(
                exchange,
                "data: {\"choices\":[{\"delta\":{\"content\":\"After tools.\"}}]}\n\n"
                    + "data: {\"choices\":[{\"finish_reason\":\"stop\",\"delta\":{}}]}\n\n"
                    + "data: [DONE]\n\n");
          }
        });
    server.start();

    OaiCcRealtimeAdapter adapter =
        new OaiCcRealtimeAdapter(
            "test-cc", modelConfig(server.getAddress().getPort()), new ObjectMapper());
    adapter.connect(null, null).await().indefinitely();
    adapter
        .registerTools(
            List.of(
                new RealtimeAdapterModels.ToolDefinition("first", "first", Map.of()),
                new RealtimeAdapterModels.ToolDefinition("second", "second", Map.of())))
        .await()
        .indefinitely();

    adapter.sendText("Run both tools.").await().indefinitely();
    awaitThread(
        adapter,
        items ->
            items.stream()
                    .filter(item -> item.type() == RealtimeThread.ItemType.FUNCTION_CALL)
                    .count()
                == 2);

    List<RealtimeThread.Item> firstResponse = adapter.thread().items();
    assertEquals(
        List.of("Run both tools.", "Before tools.", "first", "second"),
        firstResponse.stream().map(OaiCcRealtimeAdapterOrderingTest::displayValue).toList());

    adapter
        .sendFunctionOutput(
            "call_1", "one", RealtimeAdapterModels.ToolOutputDisposition.SUCCESS, null)
        .await()
        .indefinitely();
    adapter
        .sendFunctionOutput(
            "call_2", "two", RealtimeAdapterModels.ToolOutputDisposition.SUCCESS, null)
        .await()
        .indefinitely();
    awaitThread(
        adapter, items -> items.stream().anyMatch(item -> "After tools.".equals(text(item))));

    assertEquals(
        List.of(
            "Run both tools.", "Before tools.", "first", "second", "one", "two", "After tools."),
        adapter.thread().items().stream()
            .map(OaiCcRealtimeAdapterOrderingTest::displayValue)
            .toList());
    assertEquals(2, requestCount.get());
    adapter.dispose().await().indefinitely();
  }

  /**
   * An interrupted partial tool call remains in canonical history without an output. It must not
   * participate in a later response's tool-output barrier; otherwise the new tool result is
   * accepted but its continuation HTTP request is never started, leaving the call silently
   * unresponsive.
   */
  @Test
  void interruptedHistoricalToolCallDoesNotBlockLaterToolContinuation() throws Exception {
    AtomicInteger requestCount = new AtomicInteger();
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          int currentRequest = requestCount.incrementAndGet();
          if (currentRequest == 1) {
            respondWithSse(
                exchange,
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_old\",\"function\":{\"name\":\"first\",\"arguments\":\"{\"}}]}}]}\n\n");
          } else if (currentRequest == 2) {
            respondWithSse(
                exchange,
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_new\",\"function\":{\"name\":\"second\",\"arguments\":\"{}\"}}]}}]}\n\n"
                    + "data: {\"choices\":[{\"finish_reason\":\"tool_calls\",\"delta\":{}}]}\n\n"
                    + "data: [DONE]\n\n");
          } else {
            respondWithSse(
                exchange,
                "data: {\"choices\":[{\"delta\":{\"content\":\"Recovered.\"}}]}\n\n"
                    + "data: {\"choices\":[{\"finish_reason\":\"stop\",\"delta\":{}}]}\n\n"
                    + "data: [DONE]\n\n");
          }
        });
    server.start();

    OaiCcRealtimeAdapter adapter =
        new OaiCcRealtimeAdapter(
            "test-cc", modelConfig(server.getAddress().getPort()), new ObjectMapper());
    adapter.connect(null, null).await().indefinitely();
    adapter
        .registerTools(
            List.of(
                new RealtimeAdapterModels.ToolDefinition("first", "first", Map.of()),
                new RealtimeAdapterModels.ToolDefinition("second", "second", Map.of())))
        .await()
        .indefinitely();

    adapter.sendText("Start the old tool.").await().indefinitely();
    awaitThread(
        adapter, items -> items.stream().anyMatch(item -> "call_old".equals(item.callId())));
    adapter.interrupt().await().indefinitely();

    adapter.sendText("Start the new tool.").await().indefinitely();
    awaitThread(
        adapter, items -> items.stream().anyMatch(item -> "call_new".equals(item.callId())));
    adapter
        .sendFunctionOutput(
            "call_new", "new-output", RealtimeAdapterModels.ToolOutputDisposition.SUCCESS, null)
        .await()
        .indefinitely();
    awaitThread(adapter, items -> items.stream().anyMatch(item -> "Recovered.".equals(text(item))));

    assertEquals(3, requestCount.get());
    adapter.dispose().await().indefinitely();
  }

  private static void awaitThread(
      OaiCcRealtimeAdapter adapter, Predicate<List<RealtimeThread.Item>> condition)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      if (condition.test(adapter.thread().items())) {
        return;
      }
      Thread.sleep(10);
    }
    assertTrue(
        condition.test(adapter.thread().items()), "Timed out waiting for adapter thread state");
  }

  private static String displayValue(RealtimeThread.Item item) {
    if (item.type() == RealtimeThread.ItemType.FUNCTION_CALL) {
      return item.name();
    }
    if (item.type() == RealtimeThread.ItemType.FUNCTION_CALL_OUTPUT) {
      return item.output();
    }
    return text(item);
  }

  private static String text(RealtimeThread.Item item) {
    return item.content().stream()
        .filter(RealtimeThread.TextPart.class::isInstance)
        .map(RealtimeThread.TextPart.class::cast)
        .map(RealtimeThread.TextPart::text)
        .findFirst()
        .orElse("");
  }

  private static void respondWithSse(HttpExchange exchange, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
    exchange.sendResponseHeaders(200, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  private static RealtimeModelsConfig.ModelConfig modelConfig(int port) {
    return new RealtimeModelsConfig.ModelConfig() {
      @Override
      public String provider() {
        return "oai_cc";
      }

      @Override
      public String displayName() {
        return "Test CC";
      }

      @Override
      public String baseUrl() {
        return "http://127.0.0.1:" + port + "/v1";
      }

      @Override
      public String apiKey() {
        return "test-key";
      }

      @Override
      public String model() {
        return "test-model";
      }

      @Override
      public Optional<String> transcriptionModel() {
        return Optional.empty();
      }

      @Override
      public Optional<String> requiredEntitlement() {
        return Optional.empty();
      }

      @Override
      public boolean isStealth() {
        return false;
      }
    };
  }
}
