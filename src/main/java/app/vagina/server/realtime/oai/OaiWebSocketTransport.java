package app.vagina.server.realtime.oai;

import app.vagina.server.realtime.model.RealtimeAdapterModels;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.core.http.WebSocket;
import io.vertx.mutiny.core.http.WebSocketClient;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Vert.x WebSocket implementation of {@link OaiRealtimeTransport}, the server analogue of the Dart
 * {@code websocket_realtime_transport.dart}.
 *
 * <p>It owns the single downstream socket to OpenAI and nothing else: inbound text frames are parsed
 * to {@link JsonNode} and pushed onto {@link #inboundMessages()}; outbound {@link ObjectNode}s are
 * written as text frames. Unlike the Dart client there is no in-transport reconnect loop — the
 * Qua↔Oai leg is the stable server-server link (the backend-spec audio-relay judgment), so a drop
 * surfaces as a {@code FAILED}/{@code DISCONNECTED} state and the adapter/session decide what to do.
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

    String token = config.bearerToken();
    if (token != null && !token.isBlank()) {
      options.addHeader("Authorization", "Bearer " + token.trim());
    }
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
              Log.errorf(error, "OAI realtime transport failed to connect");
              emitState(
                  RealtimeAdapterModels.ConnectionState.failed(
                      "Failed to connect OpenAI realtime transport", error));
            })
        .replaceWithVoid();
  }

  private void bindSocket(WebSocket ws) {
    this.socket = ws;
    // textMessageHandler reassembles continuation frames into a full text message before delivery.
    ws.textMessageHandler(this::handleText);
    ws.closeHandler(
        () -> {
          if (!disposed) {
            emitState(
                RealtimeAdapterModels.ConnectionState.disconnected("OpenAI realtime socket closed"));
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

  private void handleText(String message) {
    try {
      JsonNode node = json.readTree(message);
      if (node != null && node.isObject()) {
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
    return ws.writeTextMessage(payload.toString());
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
        .invoke((ignored, error) -> emitState(RealtimeAdapterModels.ConnectionState.disconnected(null)))
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
        ws == null ? Uni.createFrom().voidItem() : ws.close().onFailure().recoverWithNull().replaceWithVoid();
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
