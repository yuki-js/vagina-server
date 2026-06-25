package app.vagina.server.realtime.oai;

import app.vagina.server.realtime.model.RealtimeAdapterModels;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import io.vertx.core.MultiMap;
import io.vertx.core.http.UpgradeRejectedException;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.core.http.WebSocket;
import io.vertx.mutiny.core.http.WebSocketClient;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Vert.x WebSocket implementation of {@link OaiRealtimeTransport}, the server analogue of the Dart
 * {@code websocket_realtime_transport.dart}.
 *
 * <p>It owns the single downstream socket to OpenAI and nothing else: inbound text frames are
 * parsed to {@link JsonNode} and pushed onto {@link #inboundMessages()}; outbound {@link
 * ObjectNode}s are written as text frames. Unlike the Dart client there is no in-transport
 * reconnect loop — the Qua↔Oai leg is the stable server-server link (the backend-spec audio-relay
 * judgment), so a drop surfaces as a {@code FAILED}/{@code DISCONNECTED} state and the
 * adapter/session decide what to do.
 *
 * <p>Threading: Vert.x delivers socket callbacks on an event-loop thread; the {@link
 * BroadcastProcessor}s simply forward to subscribers, so no extra synchronization is needed beyond
 * the volatile socket reference.
 */
public final class OaiWebSocketTransport implements OaiRealtimeTransport {

  private final Vertx vertx;
  private final ObjectMapper json;

  private final BroadcastProcessor<JsonNode> inbound = BroadcastProcessor.create();
  private final BroadcastProcessor<RealtimeAdapterModels.ConnectionState> stateUpdates =
      BroadcastProcessor.create();

  private final AtomicReference<RealtimeAdapterModels.ConnectionState> lastState =
      new AtomicReference<>(RealtimeAdapterModels.ConnectionState.idle());

  private volatile WebSocketClient client;
  private volatile WebSocket socket;
  private volatile boolean disposed = false;

  public OaiWebSocketTransport(Vertx vertx, ObjectMapper json) {
    this.vertx = vertx;
    this.json = json;
  }

  @Override
  public Multi<JsonNode> inboundMessages() {
    return inbound;
  }

  @Override
  public RealtimeAdapterModels.ConnectionState connectionState() {
    return lastState.get();
  }

  @Override
  public Multi<RealtimeAdapterModels.ConnectionState> connectionStateUpdates() {
    return stateUpdates;
  }

  @Override
  public Uni<Void> connect(OaiRealtimeConnectConfig config) {
    ensureNotDisposed();
    OaiRealtimeConnectConfig.Target target = config.resolveTarget();

    WebSocketConnectOptions options =
        new WebSocketConnectOptions()
            .setHost(target.host())
            .setPort(target.port())
            .setURI(target.path())
            .setSsl(target.ssl());

    applyAuthHeaders(options, config.bearerToken());
    for (Map.Entry<String, String> header : config.extraHeaders().entrySet()) {
      options.addHeader(header.getKey(), header.getValue());
    }

    emitState(RealtimeAdapterModels.ConnectionState.connecting());

    WebSocketClient ws = vertx.createWebSocketClient();
    this.client = ws;

    return ws.connect(options)
        .onItem()
        .invoke(this::bindSocket)
        .onItem()
        .invoke(ignored -> emitState(RealtimeAdapterModels.ConnectionState.connected()))
        .onFailure()
        .invoke(
            error -> {
              // ── DIAG: log rejected-upgrade responses (e.g. 302) ──────────
              // Useful for diagnosing auth failures; kept as permanent WARN-level diagnostic.
              if (error instanceof UpgradeRejectedException ure) {
                StringBuilder diagResp = new StringBuilder();
                diagResp.append("[DIAG-WS-REJECTED] status=").append(ure.getStatus());
                MultiMap respHeaders = ure.getHeaders();
                if (respHeaders != null) {
                  diagResp.append(" response-headers: [");
                  respHeaders.forEach(
                      e ->
                          diagResp
                              .append(e.getKey())
                              .append(": ")
                              .append(maskHeaderValue(e.getKey(), e.getValue()))
                              .append("; "));
                  diagResp.append("]");
                }
                if (ure.getBody() != null && ure.getBody().length() > 0) {
                  String bodyPrefix = ure.getBody().toString("UTF-8");
                  if (bodyPrefix.length() > 1024) {
                    bodyPrefix = bodyPrefix.substring(0, 1024) + "...(truncated)";
                  }
                  diagResp.append(" body-prefix=").append(bodyPrefix);
                }
                Log.warn(diagResp.toString());
              }
              // ── END DIAG ─────────────────────────────────────────────────
              Log.errorf(error, "OAI realtime transport failed to connect");
              emitState(
                  RealtimeAdapterModels.ConnectionState.failed(
                      "Failed to connect OpenAI realtime transport", error));
            })
        .replaceWithVoid();
  }

  /**
   * Applies authentication headers to the given {@link WebSocketConnectOptions}.
   *
   * <p>Both {@code Authorization: Bearer <token>} (OpenAI public API) and {@code api-key: <token>}
   * (Azure OpenAI / Azure AI Services) are added so that the same code works for both providers
   * without any provider-specific branching.
   *
   * <p>Package-private to allow direct unit-testing without a running Vert.x instance.
   */
  static void applyAuthHeaders(WebSocketConnectOptions options, String token) {
    if (token == null || token.isBlank()) {
      return;
    }
    String trimmed = token.trim();
    options.addHeader("Authorization", "Bearer " + trimmed);
    options.addHeader("api-key", trimmed);
  }

  /** Pattern that matches {@code api-key=<value>} in a URL query string or fragment. */
  private static final Pattern API_KEY_QUERY_PATTERN =
      Pattern.compile("(api-key=)[^&\\s#\"']+", Pattern.CASE_INSENSITIVE);

  /**
   * Masks secret values in response headers for DIAG logging.
   *
   * <ul>
   *   <li>{@code Authorization} and {@code api-key} header values are fully replaced with {@code
   *       [REDACTED]}.
   *   <li>For {@code Location} headers, any {@code api-key=<value>} query parameter is masked in
   *       place so the URL structure is still visible.
   * </ul>
   */
  static String maskHeaderValue(String headerName, String value) {
    if (headerName == null || value == null) {
      return value;
    }
    String lower = headerName.toLowerCase();
    if (lower.equals("authorization") || lower.equals("api-key")) {
      return "[REDACTED]";
    }
    if (lower.equals("location")) {
      return API_KEY_QUERY_PATTERN.matcher(value).replaceAll("$1[REDACTED]");
    }
    return value;
  }

  private void bindSocket(WebSocket ws) {
    this.socket = ws;
    // textMessageHandler reassembles continuation frames into a full text message before delivery.
    ws.textMessageHandler(this::handleText);
    ws.closeHandler(
        () -> {
          if (!disposed) {
            emitState(
                RealtimeAdapterModels.ConnectionState.disconnected(
                    "OpenAI realtime socket closed"));
          }
        });
    ws.exceptionHandler(
        error -> {
          Log.errorf(error, "OAI realtime transport stream error");
          emitState(
              RealtimeAdapterModels.ConnectionState.failed(
                  "OpenAI realtime transport stream failed", error));
        });
  }

  private static final int DIAG_PREVIEW_LIMIT = 1000;

  private void handleText(String message) {
    try {
      JsonNode node = json.readTree(message);
      if (node != null && node.isObject()) {
        Log.debugf("[DIAG-OAI-IN] %s", summarizePayload(node));
        inbound.onNext(node);
      } else {
        Log.warnf("OAI realtime transport dropped a non-object frame");
      }
    } catch (Exception error) {
      // A single malformed frame must not tear the stream down; log and continue.
      Log.errorf(error, "OAI realtime transport failed to decode inbound frame");
    }
  }

  @Override
  public Uni<Void> sendJson(ObjectNode payload) {
    ensureNotDisposed();
    WebSocket ws = socket;
    if (ws == null || !connectionState().isConnected()) {
      return Uni.createFrom()
          .failure(new IllegalStateException("Cannot send to OpenAI realtime while disconnected"));
    }
    Log.debugf("[DIAG-OAI-OUT] %s", summarizePayload(payload));
    return ws.writeTextMessage(payload.toString());
  }

  private static String summarizePayload(JsonNode payload) {
    if (payload == null || !payload.isObject()) {
      return preview(String.valueOf(payload));
    }

    final String type = text(payload, "type");
    return switch (type == null ? "" : type) {
      case "input_audio_buffer.append" -> {
        final String audio = text(payload, "audio");
        yield "type=input_audio_buffer.append audioBase64Chars="
            + (audio == null ? 0 : audio.length());
      }
      case "conversation.item.create" -> summarizeConversationItemCreate(payload);
      case "response.text.delta", "response.output_text.delta" ->
          "type="
              + type
              + " item_id="
              + text(payload, "item_id")
              + " content_index="
              + payload.path("content_index").asText("?")
              + " delta="
              + preview(text(payload, "delta"));
      case "response.text.done", "response.output_text.done" ->
          "type="
              + type
              + " item_id="
              + text(payload, "item_id")
              + " content_index="
              + payload.path("content_index").asText("?")
              + " text="
              + preview(text(payload, "text"));
      case "response.audio.delta", "response.output_audio.delta" -> {
        final String delta = text(payload, "delta");
        yield "type="
            + type
            + " item_id="
            + text(payload, "item_id")
            + " content_index="
            + payload.path("content_index").asText("?")
            + " deltaBase64Chars="
            + (delta == null ? 0 : delta.length());
      }
      case "response.audio_transcript.delta", "response.output_audio_transcript.delta" ->
          "type="
              + type
              + " item_id="
              + text(payload, "item_id")
              + " content_index="
              + payload.path("content_index").asText("?")
              + " delta="
              + preview(text(payload, "delta"));
      default -> preview(payload.toString());
    };
  }

  private static String summarizeConversationItemCreate(JsonNode payload) {
    final JsonNode item = payload.get("item");
    final JsonNode content = item == null ? null : item.get("content");
    final JsonNode firstPart =
        content != null && content.isArray() && !content.isEmpty() ? content.get(0) : null;
    return "type=conversation.item.create item.id="
        + text(item, "id")
        + " item.type="
        + text(item, "type")
        + " role="
        + text(item, "role")
        + " firstPart.type="
        + text(firstPart, "type")
        + " firstPart.text="
        + preview(text(firstPart, "text"));
  }

  private static String preview(String value) {
    if (value == null) {
      return "null";
    }
    final String normalized = value.replace("\n", "\\n").replace("\r", "\\r");
    if (normalized.length() <= DIAG_PREVIEW_LIMIT) {
      return normalized;
    }
    return normalized.substring(0, DIAG_PREVIEW_LIMIT)
        + "...(truncated,"
        + normalized.length()
        + " chars)";
  }

  private static String text(JsonNode node, String field) {
    if (node == null) {
      return null;
    }
    final JsonNode value = node.get(field);
    return value == null || value.isNull() ? null : value.asText();
  }

  @Override
  public Uni<Void> disconnect() {
    if (disposed) {
      return Uni.createFrom().voidItem();
    }
    WebSocket ws = socket;
    this.socket = null;
    if (ws == null) {
      emitState(RealtimeAdapterModels.ConnectionState.disconnected(null));
      return Uni.createFrom().voidItem();
    }
    return ws.close()
        .onItemOrFailure()
        .invoke(
            (ignored, error) -> emitState(RealtimeAdapterModels.ConnectionState.disconnected(null)))
        .replaceWithVoid();
  }

  @Override
  public Uni<Void> dispose() {
    if (disposed) {
      return Uni.createFrom().voidItem();
    }
    disposed = true;
    WebSocket ws = socket;
    this.socket = null;
    WebSocketClient wsClient = client;
    this.client = null;

    Uni<Void> closeSocket =
        ws == null
            ? Uni.createFrom().voidItem()
            : ws.close().onFailure().recoverWithNull().replaceWithVoid();
    return closeSocket
        .onItemOrFailure()
        .invoke(
            (ignored, error) -> {
              if (wsClient != null) {
                wsClient.closeAndForget();
              }
              inbound.onComplete();
              stateUpdates.onComplete();
            })
        .replaceWithVoid();
  }

  private void emitState(RealtimeAdapterModels.ConnectionState state) {
    lastState.set(state);
    stateUpdates.onNext(state);
  }

  private void ensureNotDisposed() {
    if (disposed) {
      throw new IllegalStateException("OAI realtime transport is already disposed");
    }
  }
}
