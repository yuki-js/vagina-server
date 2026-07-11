package app.vagina.server.realtime.oai_cc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.vagina.server.realtime.RealtimeModelsConfig;
import app.vagina.server.realtime.model.RealtimeAdapterModels;
import app.vagina.server.realtime.model.RealtimeThread;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class OaiCcRealtimeAdapterContextRetryTest {
  private static final String CONTEXT_ERROR =
      "{\"error\":{\"message\":\"too long\",\"type\":\"invalid_request_error\",\"param\":\"messages\",\"code\":\"context_length_exceeded\"}}";

  private final ObjectMapper json = new ObjectMapper();
  private HttpServer server;

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void retriesByRemovingOneOldestMessageWhilePinningSystemAndCurrentInput() throws Exception {
    List<JsonNode> requests = new ArrayList<>();
    server = startServer(exchange -> {
      JsonNode request = readRequest(exchange);
      requests.add(request);
      if (containsMessageContent(request, "current-user")) {
        if (textContextWeight(request) > 53) {
          respond(exchange, 400, "application/json", CONTEXT_ERROR);
        } else {
          respondWithText(exchange, "final-answer");
        }
      } else if (containsMessageContent(request, "middle-user")) {
        respondWithText(exchange, "answer-2");
      } else {
        respondWithText(exchange, "answer-1");
      }
    });

    OaiCcRealtimeAdapter adapter = adapter("Keep this instruction.");
    adapter.sendText("oldest-user").await().indefinitely();
    awaitThread(adapter, items -> containsText(items, "answer-1"));
    adapter.sendText("middle-user").await().indefinitely();
    awaitThread(adapter, items -> containsText(items, "answer-2"));
    int canonicalSize = adapter.thread().items().size();

    adapter.sendText("current-user").await().indefinitely();
    awaitThread(adapter, items -> containsText(items, "final-answer"));

    assertEquals(5, requests.size());
    assertEquals(
        List.of(
            "system:Keep this instruction.",
            "user:oldest-user",
            "assistant:answer-1",
            "user:middle-user",
            "assistant:answer-2",
            "user:current-user"),
        messageSignatures(requests.get(2)));
    assertEquals(
        List.of(
            "system:Keep this instruction.",
            "assistant:answer-1",
            "user:middle-user",
            "assistant:answer-2",
            "user:current-user"),
        messageSignatures(requests.get(3)));
    assertEquals(
        List.of(
            "system:Keep this instruction.",
            "user:middle-user",
            "assistant:answer-2",
            "user:current-user"),
        messageSignatures(requests.get(4)));
    assertTrue(textContextWeight(requests.get(2)) > 53);
    assertTrue(textContextWeight(requests.get(3)) > 53);
    assertTrue(textContextWeight(requests.get(4)) <= 53);
    assertEquals("Keep this instruction.", requests.get(4).path("messages").get(0).path("content").asText());
    assertEquals("current-user", lastMessage(requests.get(4)).path("content").asText());
    assertTrue(containsText(adapter.thread().items(), "oldest-user"));
    assertTrue(containsText(adapter.thread().items(), "answer-1"));
    assertEquals(canonicalSize + 2, adapter.thread().items().size());
    adapter.dispose().await().indefinitely();
  }

  @Test
  void removesToolCallAndAllOutputsAsOneRetryUnit() throws Exception {
    List<JsonNode> requests = new ArrayList<>();
    server = startServer(exchange -> {
      JsonNode request = readRequest(exchange);
      requests.add(request);
      if (containsMessageContent(request, "current-user")) {
        if (textContextWeight(request) > 30) {
          respond(exchange, 400, "application/json", CONTEXT_ERROR);
        } else {
          respondWithText(exchange, "after-trim");
        }
      } else if (request.toString().contains("tool-output")) {
        respondWithText(exchange, "after-tool");
      } else {
        respondWithToolCall(exchange);
      }
    });

    OaiCcRealtimeAdapter adapter = adapter("Pinned.");
    adapter
        .registerTools(
            List.of(new RealtimeAdapterModels.ToolDefinition("probe", "probe", Map.of())))
        .await()
        .indefinitely();
    adapter.sendText("old-tool-request").await().indefinitely();
    awaitThread(adapter, items -> items.stream().anyMatch(item -> "call_1".equals(item.callId())));
    adapter
        .sendFunctionOutput(
            "call_1", "tool-output", RealtimeAdapterModels.ToolOutputDisposition.SUCCESS, null)
        .await()
        .indefinitely();
    awaitThread(adapter, items -> containsText(items, "after-tool"));

    adapter.sendText("current-user").await().indefinitely();
    awaitThread(adapter, items -> containsText(items, "after-trim"));

    assertEquals(5, requests.size());
    assertEquals(
        List.of(
            "system:Pinned.",
            "user:old-tool-request",
            "assistant-tool:call_1",
            "tool:tool-output",
            "assistant:after-tool",
            "user:current-user"),
        messageSignatures(requests.get(2)));
    assertEquals(
        List.of(
            "system:Pinned.",
            "assistant-tool:call_1",
            "tool:tool-output",
            "assistant:after-tool",
            "user:current-user"),
        messageSignatures(requests.get(3)));
    assertEquals(
        List.of("system:Pinned.", "assistant:after-tool", "user:current-user"),
        messageSignatures(requests.get(4)));
    assertTrue(textContextWeight(requests.get(2)) > 30);
    assertTrue(textContextWeight(requests.get(3)) > 30);
    assertTrue(textContextWeight(requests.get(4)) <= 30);
    assertFalse(requests.get(4).toString().contains("call_1"));
    assertFalse(requests.get(4).toString().contains("tool-output"));
    assertTrue(
        adapter.thread().items().stream().anyMatch(item -> "tool-output".equals(item.output())));
    adapter.dispose().await().indefinitely();
  }

  @Test
  void forwardsContextErrorAfterHistoryIsExhausted() throws Exception {
    AtomicInteger requestCount = new AtomicInteger();
    server = startServer(exchange -> {
      requestCount.incrementAndGet();
      respond(exchange, 400, "application/json", CONTEXT_ERROR);
    });

    OaiCcRealtimeAdapter adapter = adapter("Pinned.");
    List<RealtimeAdapterModels.Error> errors = new ArrayList<>();
    adapter.errors().subscribe().with(errors::add);

    adapter.sendText("current-user").await().indefinitely();
    awaitCondition(() -> !errors.isEmpty());

    assertEquals(1, requestCount.get());
    assertEquals("chat_completions_error", errors.getFirst().code());
    assertTrue(errors.getFirst().cause() instanceof OaiCcHttpError);
    assertEquals(
        RealtimeThread.ItemStatus.INCOMPLETE, adapter.thread().items().getLast().status());
    adapter.dispose().await().indefinitely();
  }

  @Test
  void doesNotRetryUnrelatedHttpError() throws Exception {
    AtomicInteger requestCount = new AtomicInteger();
    server = startServer(exchange -> {
      requestCount.incrementAndGet();
      respond(
          exchange,
          400,
          "application/json",
          "{\"error\":{\"code\":\"invalid_request_error\"}}");
    });

    OaiCcRealtimeAdapter adapter = adapter("Pinned.");
    List<RealtimeAdapterModels.Error> errors = new ArrayList<>();
    adapter.errors().subscribe().with(errors::add);

    adapter.sendText("current-user").await().indefinitely();
    awaitCondition(() -> !errors.isEmpty());

    assertEquals(1, requestCount.get());
    assertEquals("chat_completions_error", errors.getFirst().code());
    adapter.dispose().await().indefinitely();
  }

  private OaiCcRealtimeAdapter adapter(String instructions) {
    OaiCcRealtimeAdapter adapter =
        new OaiCcRealtimeAdapter(
            "test-cc", modelConfig(server.getAddress().getPort()), new ObjectMapper());
    adapter.connect(null, instructions).await().indefinitely();
    return adapter;
  }

  private HttpServer startServer(ExchangeHandler handler) throws IOException {
    HttpServer result = HttpServer.create(new InetSocketAddress(0), 0);
    result.createContext(
        "/v1/chat/completions",
        exchange -> {
          try {
            handler.handle(exchange);
          } catch (Exception error) {
            exchange.close();
            throw error;
          }
        });
    result.start();
    return result;
  }

  private JsonNode readRequest(HttpExchange exchange) throws IOException {
    return json.readTree(exchange.getRequestBody());
  }

  private static void respondWithText(HttpExchange exchange, String text) throws IOException {
    respond(
        exchange,
        200,
        "text/event-stream",
        "data: {\"choices\":[{\"delta\":{\"content\":\"" + text + "\"}}]}\n\n"
            + "data: {\"choices\":[{\"finish_reason\":\"stop\",\"delta\":{}}]}\n\n"
            + "data: [DONE]\n\n");
  }

  private static void respondWithToolCall(HttpExchange exchange) throws IOException {
    respond(
        exchange,
        200,
        "text/event-stream",
        "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"function\":{\"name\":\"probe\",\"arguments\":\"{}\"}}]}}]}\n\n"
            + "data: {\"choices\":[{\"finish_reason\":\"tool_calls\",\"delta\":{}}]}\n\n"
            + "data: [DONE]\n\n");
  }

  private static void respond(
      HttpExchange exchange, int status, String contentType, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", contentType);
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  private static List<String> messageSignatures(JsonNode request) {
    List<String> signatures = new ArrayList<>();
    request.path("messages").forEach(
        message -> {
          String role = message.path("role").asText();
          if (message.has("tool_calls")) {
            signatures.add(
                "assistant-tool:" + message.path("tool_calls").get(0).path("id").asText());
          } else {
            signatures.add(role + ":" + message.path("content").asText());
          }
        });
    return signatures;
  }

  private static boolean containsMessageContent(JsonNode request, String content) {
    for (JsonNode message : request.path("messages")) {
      if (content.equals(message.path("content").asText())) {
        return true;
      }
    }
    return false;
  }

  private static int textContextWeight(JsonNode request) {
    int weight = 0;
    for (JsonNode message : request.path("messages")) {
      if (message.has("content") && message.path("content").isTextual()) {
        weight += message.path("content").asText().length();
      }
      if (message.has("tool_calls")) {
        for (JsonNode call : message.path("tool_calls")) {
          weight += call.path("function").path("arguments").asText().length();
        }
      }
    }
    return weight;
  }

  private static JsonNode lastMessage(JsonNode request) {
    JsonNode messages = request.path("messages");
    return messages.get(messages.size() - 1);
  }

  private static boolean containsText(List<RealtimeThread.Item> items, String expected) {
    return items.stream()
        .flatMap(item -> item.content().stream())
        .filter(RealtimeThread.TextPart.class::isInstance)
        .map(RealtimeThread.TextPart.class::cast)
        .anyMatch(part -> expected.equals(part.text()));
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
    assertTrue(condition.test(adapter.thread().items()), "Timed out waiting for adapter thread state");
  }

  private static void awaitCondition(Condition condition) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      if (condition.test()) {
        return;
      }
      Thread.sleep(10);
    }
    assertTrue(condition.test(), "Timed out waiting for condition");
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

  @FunctionalInterface
  private interface ExchangeHandler {
    void handle(HttpExchange exchange) throws IOException;
  }

  @FunctionalInterface
  private interface Condition {
    boolean test();
  }
}
