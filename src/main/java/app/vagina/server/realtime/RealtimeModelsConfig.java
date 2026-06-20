package app.vagina.server.realtime;

import io.smallrye.config.ConfigMapping;
import java.util.Map;
import java.util.Optional;

/**
 * Typed view of the {@code modelId} registry (judgment 7): {@code modelId -> (provider, downstream
 * connection info, defaults)}.
 *
 * <p>This is the configuration substrate behind {@link RealtimeAdapterFactory}. The client sends
 * only a {@code modelId} in {@code session.open}; this mapping is the single server-side place that
 * decides which vendor backs it and how to reach that vendor. Keeping it a {@link ConfigMapping}
 * (rather than a swarm of {@code @ConfigProperty} fields) lets an arbitrary set of model ids live
 * under one prefix, each with its own group, matching the schema documented in {@code
 * 03_quarkus_backend_spec.md}:
 *
 * <pre>
 * vagina.realtime.models.voice-agent-prod.provider=oai
 * vagina.realtime.models.voice-agent-prod.base-url=${OAI_REALTIME_BASE_URL:}
 * vagina.realtime.models.voice-agent-prod.api-key=${OAI_REALTIME_API_KEY:}
 * vagina.realtime.models.voice-agent-prod.transcription-model=gpt-4o-mini-transcribe
 * </pre>
 *
 * <p>The map key is the literal {@code modelId} segment, so {@code models().get("voice-agent-prod")}
 * resolves that group. A {@code modelId} absent from this map is an unknown model and becomes {@code
 * error(session.unknown_model)} upstream.
 */
@ConfigMapping(prefix = "vagina.realtime")
public interface RealtimeModelsConfig {

  /** Per-{@code modelId} driver/connection groups, keyed by the {@code modelId} string. */
  Map<String, ModelConfig> models();

  /**
   * One model's driver selection and downstream connection info. Only {@link #provider()} is
   * required; the rest are provider-specific and optional so a driver can default what it does not
   * need (for example a stub driver needs no {@code apiKey}).
   */
  interface ModelConfig {

    /** Driver discriminator, e.g. {@code "oai"}; {@link RealtimeAdapterFactory} switches on this. */
    String provider();

    /** Downstream vendor base URL; opaque to the factory, interpreted by the driver. */
    Optional<String> baseUrl();

    /** Downstream vendor credential; opaque to the factory, interpreted by the driver. */
    Optional<String> apiKey();

    /** Optional ASR model override for drivers that transcribe input audio. */
    Optional<String> transcriptionModel();

    /** Optional default voice applied when {@code session.open} carries none. */
    Optional<String> voice();

    /** Optional default instructions applied when {@code session.open} carries none. */
    Optional<String> instructions();
  }
}
