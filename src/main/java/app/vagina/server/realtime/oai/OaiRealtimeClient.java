package app.vagina.server.realtime.oai;

import app.vagina.server.realtime.model.RealtimeAdapterModels;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import io.smallrye.mutiny.subscription.Cancellable;
import java.util.Map;

/**
 * Thin OpenAI Realtime binding, the server mirror of the Dart {@code realtime_binding.dart}.
 *
 * <p>Responsibilities, and nothing beyond them:
 *
 * <ul>
 *   <li>connect/disconnect the {@link OaiRealtimeTransport};
 *   <li>parse inbound JSON frames into typed {@link OaiRealtimeEvent}s via {@link
 *       OaiRealtimeEventParser};
 *   <li>expose one {@link Multi} per consumed concrete event type;
 *   <li>encode and send outbound {@link OaiRealtimeCommand}s.
 * </ul>
 *
 * <p>It holds no business logic, response coordination, or canonical-thread projection. A single
 * {@link BroadcastProcessor} fans every parsed event out; {@link #events(Class)} filters by
 * concrete type for the event projector.
 */
public final class OaiRealtimeClient {

  private final OaiRealtimeTransport transport;
  private final OaiRealtimeEventParser parser;
  private final OaiRealtimeCommand.Encoder encoder;

  private final BroadcastProcessor<OaiRealtimeEvent> eventBus = BroadcastProcessor.create();
  private final Cancellable inboundSubscription;

  private volatile boolean disposed = false;

  public OaiRealtimeClient(OaiRealtimeTransport transport, ObjectMapper json) {
    this.transport = transport;
    this.parser = new OaiRealtimeEventParser();
    this.encoder = new OaiRealtimeCommand.Encoder(json);
    this.inboundSubscription =
        transport
            .inboundMessages()
            .subscribe()
            .with(this::handleInbound, this::handleInboundFailure);
  }

  // ---------------------------------------------------------------------------
  // Connection observation
  // ---------------------------------------------------------------------------

  public RealtimeAdapterModels.ConnectionState connectionState() {
    return transport.connectionState();
  }

  public Multi<RealtimeAdapterModels.ConnectionState> connectionStateUpdates() {
    return transport.connectionStateUpdates();
  }

  /** One typed stream per concrete event class, mirroring the Dart per-event getters. */
  public <T extends OaiRealtimeEvent> Multi<T> events(Class<T> type) {
    return eventBus.filter(type::isInstance).map(type::cast);
  }

  // ---------------------------------------------------------------------------
  // Lifecycle
  // ---------------------------------------------------------------------------

  public Uni<Void> connect(OaiRealtimeConnectConfig config) {
    ensureNotDisposed();
    return transport.connect(config);
  }

  public Uni<Void> disconnect() {
    ensureNotDisposed();
    return transport.disconnect();
  }

  public Uni<Void> dispose() {
    if (disposed) {
      return Uni.createFrom().voidItem();
    }
    disposed = true;
    inboundSubscription.cancel();
    return transport
        .dispose()
        .onItemOrFailure()
        .invoke((ignored, error) -> eventBus.onComplete())
        .replaceWithVoid();
  }

  // ---------------------------------------------------------------------------
  // Outbound commands (mirror the Dart binding method set)
  // ---------------------------------------------------------------------------

  public Uni<Void> updateSession(Map<String, Object> session) {
    return send(new OaiRealtimeCommand.SessionUpdate(session));
  }

  public Uni<Void> appendInputAudio(byte[] audioBytes) {
    return send(new OaiRealtimeCommand.InputAudioBufferAppend(audioBytes));
  }

  public Uni<Void> commitInputAudioBuffer() {
    return send(new OaiRealtimeCommand.InputAudioBufferCommit());
  }

  public Uni<Void> clearInputAudioBuffer() {
    return send(new OaiRealtimeCommand.InputAudioBufferClear());
  }

  public Uni<Void> createConversationItem(String previousItemId, Map<String, Object> item) {
    return send(new OaiRealtimeCommand.ConversationItemCreate(previousItemId, item));
  }

  /**
   * Starts one default-conversation response correlated by a caller-owned event ID.
   *
   * <p>The provider echoes this ID inside {@code error.event_id}; the adapter uses that correlation
   * to retry only the generation whose create was rejected.
   */
  public Uni<Void> createResponse(String eventId, Map<String, Object> response) {
    return send(new OaiRealtimeCommand.ResponseCreate(eventId, response));
  }

  /** Cancels the active or create-pending default-conversation response. */
  public Uni<Void> cancelResponse(String eventId) {
    return send(new OaiRealtimeCommand.ResponseCancel(eventId));
  }

  // ---------------------------------------------------------------------------
  // Internals
  // ---------------------------------------------------------------------------

  private Uni<Void> send(OaiRealtimeCommand command) {
    ensureNotDisposed();
    return transport.sendJson(encoder.encode(command));
  }

  private void handleInbound(JsonNode payload) {
    try {
      OaiRealtimeEvent event = parser.parse(payload);
      if (event != null) {
        eventBus.onNext(event);
      }
    } catch (OaiRealtimeEventParser.ProtocolException error) {
      // A malformed frame is logged, not fatal: the downstream link survives, like the Dart binding
      // emitting a connection error without tearing the stream down.
      Log.warnf(error, "OAI realtime client dropped a malformed frame");
    } catch (Exception error) {
      Log.errorf(error, "OAI realtime client unexpected parse error");
    }
  }

  private void handleInboundFailure(Throwable error) {
    Log.errorf(error, "OAI realtime client inbound stream failed");
  }

  private void ensureNotDisposed() {
    if (disposed) {
      throw new IllegalStateException("OAI realtime client is already disposed");
    }
  }
}
