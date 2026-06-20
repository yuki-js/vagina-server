package app.vagina.server.realtime;

import app.vagina.server.realtime.oai.OaiRealtimeAdapter;
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
 * {@code modelId}, injects {@link RealtimeModelsConfig} itself, and resolves its own connection info
 * — mirroring how the Dart {@code OaiRealtimeAdapter} owns its config and constructs its own thread.
 * Keeping connection details out of the factory is what lets a new provider be added here in two
 * lines without the factory learning any vendor's connection shape.
 *
 * <p>{@link VhrpEndpoint}, the wire, and the Flutter client are untouched by provider changes,
 * because everything downstream only ever sees the {@link RealtimeAdapter} type. The client never
 * learns which arm ran.
 */
@ApplicationScoped
public class ConfigRealtimeAdapterFactory implements RealtimeAdapterFactory {

  @Inject RealtimeModelsConfig modelsConfig;

  @Override
  public RealtimeAdapter create(String modelId) throws UnknownModelException {
    RealtimeModelsConfig.ModelConfig model = modelsConfig.models().get(modelId);
    if (model == null) {
      throw new UnknownModelException(modelId);
    }
    // Only the provider discriminator is read here; the modelId alone is forwarded to the driver,
    // which resolves its own connection info from RealtimeModelsConfig.
    return switch (model.provider()) {
      case "oai" -> buildOaiAdapter(modelId);
      default -> throw new UnknownModelException(modelId);
    };
  }

  /**
   * Builds the OAI-family driver, handing it only the {@code modelId} to self-resolve.
   *
   * <p>Per the standing decision, the factory passes nothing but the {@code modelId}: the OAI body
   * injects {@link RealtimeModelsConfig} itself and resolves baseUrl/apiKey/transcription/defaults
   * from its own {@code modelId} group, and owns its own {@code RealtimeThread} +
   * {@link ThreadPatchBuilder} internally (mirroring the Dart {@code OaiRealtimeAdapter}). The
   * factory therefore stays ignorant of every vendor's connection shape.
   */
  private RealtimeAdapter buildOaiAdapter(String modelId) {
    return new OaiRealtimeAdapter(modelId);
  }
}
