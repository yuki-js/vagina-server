package app.vagina.server.realtime;

import app.vagina.server.realtime.oai.OaiRealtimeAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.mutiny.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Config-backed {@link RealtimeAdapterFactory}: the {@code modelId} registry of judgment 7.
 *
 * <p>{@link VhrpSessionRegistry} injects this and calls {@link #create(String)} once per new
 * session. The flow is deliberately narrow:
 *
 * <ol>
 *   <li>look the {@code modelId} up in {@link RealtimeModelsConfig#models()};
 *   <li>absent → {@link UnknownModelException} (becomes {@code error(session.unknown_model)});
 *   <li>present → switch on {@link RealtimeModelsConfig.ModelConfig#provider()} only to pick the
 *       driver, then hand the {@code modelId} straight to it.
 * </ol>
 *
 * <p>The factory reads exactly one field: {@code provider()}, the driver discriminator. It does
 * <em>not</em> read or interpret {@code baseUrl}/{@code apiKey}/{@code transcriptionModel}/default
 * voice+instructions, and it does <em>not</em> build the canonical {@code RealtimeThread} or its
 * {@link ThreadPatchBuilder}. All of that is the driver's own concern: the OAI body takes the
 * {@code modelId}, injects {@link RealtimeModelsConfig} itself, and resolves its own connection
 * info — mirroring how the Dart {@code OaiRealtimeAdapter} owns its config and constructs its own
 * thread. Keeping connection details out of the factory is what lets a new provider be added here
 * in two lines without the factory learning any vendor's connection shape.
 *
 * <p>{@link VhrpEndpoint}, the wire, and the Flutter client are untouched by provider changes,
 * because everything downstream only ever sees the {@link RealtimeAdapter} type. The client never
 * learns which arm ran.
 */
@ApplicationScoped
public class ConfigRealtimeAdapterFactory implements RealtimeAdapterFactory {

  @Inject RealtimeModelsConfig modelsConfig;

  /** Shared Vert.x for the downstream OpenAI WebSocket; one event loop serves all sessions. */
  @Inject Vertx vertx;

  /** Single configured JSON mapper shared by every driver's encoder/parser. */
  @Inject ObjectMapper objectMapper;

  @Override
  public RealtimeAdapter create(String modelId) throws UnknownModelException {
    RealtimeModelsConfig.ModelConfig model = modelsConfig.models().get(modelId);
    if (model == null) {
      throw new UnknownModelException(modelId);
    }
    // Only the provider discriminator is read here to pick the driver; the resolved ModelConfig is
    // handed to the driver, which interprets its own baseUrl/apiKey/transcription/defaults.
    return switch (model.provider()) {
      case "oai" -> buildOaiAdapter(modelId, model);
      default -> throw new UnknownModelException(modelId);
    };
  }

  /**
   * Builds the OAI-family driver. The factory reads only {@code provider()} to select it and then
   * hands over the {@code modelId} and its resolved {@link RealtimeModelsConfig.ModelConfig} group
   * plus the shared Vert.x and JSON mapper; the OAI body owns its own {@code RealtimeThread} +
   * {@link ThreadPatchBuilder} and downstream connection, mirroring the Dart {@code
   * OaiRealtimeAdapter}. The factory therefore stays ignorant of every vendor's connection shape.
   */
  private RealtimeAdapter buildOaiAdapter(String modelId, RealtimeModelsConfig.ModelConfig model) {
    return new OaiRealtimeAdapter(modelId, model, vertx, objectMapper);
  }
}
