package app.vagina.server.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketConnectOptions;
import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Test-side VHRP/1 WebSocket client.
 *
 * <p>Combines:
 *
 * <ul>
 *   <li>A Vert.x {@link HttpClient} that connects to the running Quarkus test server;
 *   <li>A CBOR C2S frame encoder (mirrors the wire shape that {@link VhrpCborCodec#encode} emits
 *       for S2C, applying the same {@code type/body/[messageId]} envelope for C2S);
 *   <li>A CBOR S2C frame decoder backed by {@link VhrpCborCodec#decode}'s sibling plain-Jackson
 *       reader, returning decoded {@link JsonNode} maps so tests can assert on any field without
 *       depending on server-internal record types.
 * </ul>
 *
 * <p>Decoded inbound frames are queued in a thread-safe list; {@link #waitForMessage(String, long,
 * TimeUnit)} blocks until a frame of the given {@code type} arrives or the timeout elapses.
 *
 * <p>Call {@link #connect(int, String)} to open the WebSocket and {@link #close()} when done. The
 * client is not thread-safe beyond its inbound queue; do not send from multiple threads.
 */
public final class VhrpTestClient implements Closeable {

  /** Plain Jackson CBOR mapper for both encoding and decoding in tests. */
  private final ObjectMapper cbor = new ObjectMapper(new CBORFactory());

  private final Vertx vertx;
  private HttpClient httpClient;
  private volatile WebSocket ws;

  /** All S2C frames decoded so far, newest at the end. */
  private final CopyOnWriteArrayList<JsonNode> received = new CopyOnWriteArrayList<>();

  private final AtomicBoolean closed = new AtomicBoolean(false);
  private final AtomicInteger closeCode = new AtomicInteger(-1);
  private final AtomicReference<String> closedSubProtocol = new AtomicReference<>();

  /** Notified whenever a new frame arrives, for blocking poll. */
  private final Object receiveLock = new Object();

  public VhrpTestClient(Vertx vertx) {
    this.vertx = vertx;
  }

  // =========================================================================
  // Connection lifecycle
  // =========================================================================

  /**
   * Connects to {@code ws://localhost:<port>/api/hosted-realtime/v1/connect} without offering a
   * WebSocket subprotocol. Blocks until the WebSocket handshake completes or throws.
   */
  public void connect(int port) throws Exception {
    connect(port, null);
  }

  /**
   * Connects to {@code ws://localhost:<port>/api/hosted-realtime/v1/connect}, optionally offering a
   * WebSocket subprotocol. Blocks until the WebSocket handshake completes or throws.
   */
  public void connect(int port, String subProtocol) throws Exception {
    httpClient =
        vertx.createHttpClient(
            new HttpClientOptions()
                .setDefaultHost("localhost")
                .setDefaultPort(port)
                .setMaxPoolSize(4));

    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<Throwable> connectError = new AtomicReference<>();
    AtomicReference<WebSocket> wsRef = new AtomicReference<>();

    WebSocketConnectOptions opts =
        new WebSocketConnectOptions()
            .setHost("localhost")
            .setPort(port)
            .setURI("/api/hosted-realtime/v1/connect");
    if (subProtocol != null && !subProtocol.isBlank()) {
      opts.addSubProtocol(subProtocol);
    }

    httpClient
        .webSocket(opts)
        .onSuccess(
            socket -> {
              wsRef.set(socket);
              socket
                  .binaryMessageHandler(
                      buf -> {
                        try {
                          JsonNode decoded = cbor.readTree(buf.getBytes());
                          received.add(decoded);
                          synchronized (receiveLock) {
                            receiveLock.notifyAll();
                          }
                        } catch (IOException e) {
                          // CBOR decode failure — record a synthetic error node
                          received.add(
                              cbor.createObjectNode()
                                  .put("type", "_decode_error")
                                  .put("error", e.getMessage()));
                          synchronized (receiveLock) {
                            receiveLock.notifyAll();
                          }
                        }
                      })
                  .closeHandler(
                      v -> {
                        closed.set(true);
                        closeCode.set(
                            socket.closeStatusCode() != null ? socket.closeStatusCode() : -1);
                        synchronized (receiveLock) {
                          receiveLock.notifyAll();
                        }
                      });
              closedSubProtocol.set(socket.subProtocol());
              latch.countDown();
            })
        .onFailure(
            err -> {
              connectError.set(err);
              latch.countDown();
            });

    if (!latch.await(10, TimeUnit.SECONDS)) {
      throw new AssertionError("WebSocket connect timed out");
    }
    if (connectError.get() != null) {
      throw new RuntimeException("WebSocket connect failed", connectError.get());
    }
    ws = wsRef.get();
  }

  /**
   * The sub-protocol negotiated by the server during the WebSocket handshake. Null if the
   * connection failed before the handshake completed.
   */
  public String negotiatedSubProtocol() {
    return closedSubProtocol.get();
  }

  @Override
  public void close() {
    WebSocket socket = ws;
    if (socket != null) {
      socket.close();
    }
    if (httpClient != null) {
      httpClient.close();
    }
  }

  // =========================================================================
  // Send helpers (C2S CBOR encoding)
  // =========================================================================

  /**
   * Sends a raw C2S CBOR envelope. The {@code body} map is encoded as a nested CBOR map. Callers
   * that need a {@code messageId} should include it in the top-level fields map.
   *
   * @param type the VHRP message type string
   * @param body body key/value pairs; values can be String, Number, Boolean, byte[], Map, List, or
   *     null.
   * @param topLevel optional extra top-level fields (e.g. {@code messageId}); may be null
   */
  public void send(String type, Map<String, Object> body, Map<String, Object> topLevel) {
    ObjectNode root = cbor.createObjectNode();
    root.put("type", type);
    if (topLevel != null) {
      topLevel.forEach((k, v) -> putValue(root, k, v));
    }
    ObjectNode bodyNode = root.putObject("body");
    if (body != null) {
      body.forEach((k, v) -> putValue(bodyNode, k, v));
    }
    try {
      byte[] bytes = cbor.writeValueAsBytes(root);
      ws.writeBinaryMessage(Buffer.buffer(bytes));
    } catch (IOException e) {
      throw new RuntimeException("CBOR encode failed", e);
    }
  }

  /** Convenience: send without extra top-level fields. */
  public void send(String type, Map<String, Object> body) {
    send(type, body, null);
  }

  /** Convenience: send {@code session.open}. */
  public String sendSessionOpen(String jwt, String speedDialId) {
    String msgId = UUID.randomUUID().toString();
    send(
        "session.open",
        Map.of("token", jwt, "speedDialId", speedDialId),
        Map.of("messageId", msgId));
    return msgId;
  }

  /** Convenience: send {@code session.open} with {@code resume.sessionId}. */
  public String sendSessionOpenResume(String jwt, String speedDialId, String sessionId) {
    String msgId = UUID.randomUUID().toString();
    send(
        "session.open",
        Map.of("token", jwt, "speedDialId", speedDialId, "resume", Map.of("sessionId", sessionId)),
        Map.of("messageId", msgId));
    return msgId;
  }

  /** Convenience: send {@code assistant.interrupt}. */
  public void sendAssistantInterrupt(String reason) {
    send("assistant.interrupt", Map.of("reason", reason));
  }

  /** Convenience: send {@code turn.text.submit}. */
  public String sendTurnTextSubmit(String clientItemId, String text) {
    String msgId = UUID.randomUUID().toString();
    send(
        "turn.text.submit",
        Map.of("clientItemId", clientItemId, "text", text),
        Map.of("messageId", msgId));
    return msgId;
  }

  /** Convenience: send {@code session.instructions.set}. */
  public String sendSessionInstructionsSet(String instructions) {
    String msgId = UUID.randomUUID().toString();
    send(
        "session.instructions.set",
        Map.of("instructions", instructions),
        Map.of("messageId", msgId));
    return msgId;
  }

  /** Convenience: send {@code tools.set}. */
  public String sendToolsSet(List<Map<String, Object>> tools) {
    String msgId = UUID.randomUUID().toString();
    send("tools.set", Map.of("tools", tools), Map.of("messageId", msgId));
    return msgId;
  }

  /** Convenience: send {@code session.extension.apply}. */
  public String sendSessionExtensionApply(String extensionType, Map<String, Object> payload) {
    String msgId = UUID.randomUUID().toString();
    send(
        "session.extension.apply",
        Map.of("extensionType", extensionType, "payload", payload),
        Map.of("messageId", msgId));
    return msgId;
  }

  /** Convenience: send {@code thread.sync.request}. */
  public String sendThreadSyncRequest() {
    String msgId = UUID.randomUUID().toString();
    send("thread.sync.request", Map.of("reason", "test"), Map.of("messageId", msgId));
    return msgId;
  }

  /**
   * Convenience: send {@code tool.result.submit} carrying {@code callId}, {@code output}, and
   * {@code disposition} ({@code "success"} or {@code "error"}).
   *
   * @param clientItemId caller-assigned client item id (echoed in ack)
   * @param callId the function-call primary key emitted by the server in the thread.patch
   * @param output the tool's output string
   * @param disposition {@code "success"} or {@code "error"}
   */
  public String sendToolResultSubmit(
      String clientItemId, String callId, String output, String disposition) {
    String msgId = UUID.randomUUID().toString();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("clientItemId", clientItemId);
    body.put("callId", callId);
    body.put("output", output);
    body.put("disposition", disposition);
    send("tool.result.submit", body, Map.of("messageId", msgId));
    return msgId;
  }

  // =========================================================================
  // Receive helpers
  // =========================================================================

  /** Returns all decoded frames received so far, in arrival order. */
  public List<JsonNode> allReceived() {
    return Collections.unmodifiableList(new ArrayList<>(received));
  }

  /**
   * Blocks until a frame of the given {@code type} is present in the queue, then returns the
   * <em>first</em> such frame. Throws {@link AssertionError} if {@code timeoutMs} elapses first.
   */
  public JsonNode waitForMessage(String type, long timeout, TimeUnit unit)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + unit.toMillis(timeout);
    while (true) {
      for (JsonNode node : received) {
        JsonNode t = node.get("type");
        if (t != null && type.equals(t.asText())) {
          return node;
        }
      }
      long remaining = deadline - System.currentTimeMillis();
      if (remaining <= 0) {
        throw new AssertionError(
            "Timed out waiting for VHRP message type='" + type + "'. Received: " + received);
      }
      synchronized (receiveLock) {
        receiveLock.wait(Math.min(remaining, 100));
      }
    }
  }

  /** Blocks until the number of received frames is greater than {@code previousCount}. */
  public JsonNode waitForNextMessageAfterCount(int previousCount, long timeout, TimeUnit unit)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + unit.toMillis(timeout);
    while (true) {
      List<JsonNode> snapshot = allReceived();
      if (snapshot.size() > previousCount) {
        return snapshot.get(previousCount);
      }
      long remaining = deadline - System.currentTimeMillis();
      if (remaining <= 0) {
        throw new AssertionError(
            "Timed out waiting for next VHRP message after count="
                + previousCount
                + ". Received: "
                + received);
      }
      synchronized (receiveLock) {
        receiveLock.wait(Math.min(remaining, 100));
      }
    }
  }

  /** Waits for an ack whose replyTo matches {@code msgId}. */
  public JsonNode waitForAckReplyingTo(String msgId, long timeout, TimeUnit unit)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + unit.toMillis(timeout);
    while (true) {
      for (JsonNode node : received) {
        JsonNode frameType = node.get("type");
        JsonNode replyTo = node.get("replyTo");
        if (frameType != null
            && "ack".equals(frameType.asText())
            && replyTo != null
            && msgId.equals(replyTo.asText())) {
          return node;
        }
      }
      long remaining = deadline - System.currentTimeMillis();
      if (remaining <= 0) {
        throw new AssertionError("Timed out waiting for ack replyTo=" + msgId);
      }
      synchronized (receiveLock) {
        receiveLock.wait(Math.min(remaining, 100));
      }
    }
  }

  /**
   * Blocks until the WebSocket is marked closed. Useful to verify the server closed after a
   * protocol error. Returns the close status code, or -1 if not available.
   */
  public int waitForClose(long timeout, TimeUnit unit) throws InterruptedException {
    long deadline = System.currentTimeMillis() + unit.toMillis(timeout);
    while (!closed.get()) {
      long remaining = deadline - System.currentTimeMillis();
      if (remaining <= 0) {
        throw new AssertionError("Timed out waiting for WebSocket close");
      }
      synchronized (receiveLock) {
        receiveLock.wait(Math.min(remaining, 100));
      }
    }
    return closeCode.get();
  }

  /** Returns true if the WebSocket has been closed by the server or locally. */
  public boolean isClosed() {
    return closed.get();
  }

  // =========================================================================
  // CBOR value helpers
  // =========================================================================

  @SuppressWarnings("unchecked")
  private void putValue(ObjectNode node, String key, Object value) {
    if (value == null) {
      node.putNull(key);
    } else if (value instanceof String s) {
      node.put(key, s);
    } else if (value instanceof Boolean b) {
      node.put(key, b);
    } else if (value instanceof Integer i) {
      node.put(key, i);
    } else if (value instanceof Long l) {
      node.put(key, l);
    } else if (value instanceof Double d) {
      node.put(key, d);
    } else if (value instanceof byte[] bytes) {
      node.put(key, bytes); // CBOR bstr (major type 2)
    } else if (value instanceof Map<?, ?> m) {
      ObjectNode sub = node.putObject(key);
      ((Map<String, Object>) m).forEach((k, v) -> putValue(sub, k, v));
    } else if (value instanceof List<?> l) {
      var arr = node.putArray(key);
      for (Object item : l) {
        if (item instanceof Map<?, ?> m) {
          ObjectNode sub = arr.addObject();
          ((Map<String, Object>) m).forEach((k, v) -> putValue(sub, k, v));
        } else if (item instanceof String s) {
          arr.add(s);
        } else if (item instanceof Boolean b) {
          arr.add(b);
        } else if (item instanceof Integer i) {
          arr.add(i);
        } else if (item instanceof Long valueLong) {
          arr.add(valueLong);
        } else if (item instanceof Double d) {
          arr.add(d);
        } else if (item == null) {
          arr.addNull();
        } else {
          arr.addPOJO(item);
        }
      }
    } else {
      node.put(key, String.valueOf(value));
    }
  }
}
