package app.vagina.server.realtime.oai;

import app.vagina.server.realtime.model.RealtimeAdapterModels;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;

/**
 * Transport abstraction for the OpenAI Realtime binding, mirroring the Dart {@code
 * realtime_transport.dart}.
 *
 * <p>This layer owns only connection lifecycle and JSON frame I/O over the downstream
 * (Quarkus→OpenAI) link. It performs no event accumulation, no business interpretation, and no
 * provider-agnostic mapping; those live in {@link OaiRealtimeClient} and {@link
 * OaiRealtimeAdapter}. Connection state is surfaced as the VHRP-agnostic {@link
 * RealtimeAdapterModels.ConnectionState} so the adapter can map it onto its own contract without an
 * OAI-specific phase type leaking upward.
 */
public interface OaiRealtimeTransport {

  /** Inbound OpenAI events as parsed JSON trees, one per received text frame. */
  Multi<JsonNode> inboundMessages();

  RealtimeAdapterModels.ConnectionState connectionState();

  Multi<RealtimeAdapterModels.ConnectionState> connectionStateUpdates();

  Uni<Void> connect(OaiRealtimeConnectConfig config);

  /** Serializes {@code payload} to JSON and writes it as one text frame. */
  Uni<Void> sendJson(ObjectNode payload);

  Uni<Void> disconnect();

  Uni<Void> dispose();
}
