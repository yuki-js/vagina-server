package app.vagina.server.realtime;

import app.vagina.server.support.Constants;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Typed view of the {@code modelId} registry (judgment 7): {@code modelId -> (provider, downstream
 * connection info)}.
 *
 * <p>This is the configuration substrate behind {@link RealtimeAdapterFactory}. Speed Dial presets
 * choose a voice-agent id; this mapping is the single server-side place that decides which vendor
 * backs it and how to reach that vendor. Keeping it a {@link ConfigMapping} (rather than a swarm of
 * {@code @ConfigProperty} fields) lets an arbitrary set of model ids live under one prefix, each
 * with its own group, matching the schema documented in {@code 03_quarkus_backend_spec.md}:
 *
 * <pre>
 * vagina.realtime.models.voice-agent-prod.provider=oai
 * vagina.realtime.models.voice-agent-prod.base-url=${OAI_REALTIME_BASE_URL:}
 * vagina.realtime.models.voice-agent-prod.api-key=${OAI_REALTIME_API_KEY:}
 * vagina.realtime.models.voice-agent-prod.model=gpt-realtime-2
 * vagina.realtime.models.voice-agent-prod.transcription-model=gpt-4o-mini-transcribe
 * </pre>
 *
 * <p>The map key is the literal {@code modelId} segment, so {@code
 * models().get("voice-agent-prod")} resolves that group. A {@code modelId} absent from this map is
 * an unknown model and becomes {@code error(session.unknown_model)} upstream.
 */
@ConfigMapping(prefix = "vagina.realtime")
public interface RealtimeModelsConfig {

  /** The default hosted voice-agent model id exposed to clients and used for new presets. */
  String defaultModel();

  /** How long a detached hosted-realtime session remains resumable before terminal expiry. */
  @WithDefault(Constants.VHRP_RESUME_RETENTION_CONFIG_DEFAULT)
  Duration resumeRetention();

  /** Per-{@code modelId} driver/connection groups, keyed by the {@code modelId} string. */
  Map<String, ModelConfig> models();

  /** One model's driver selection and required downstream connection info. */
  interface ModelConfig {

    /**
     * Driver discriminator, e.g. {@code "oai"}; {@link RealtimeAdapterFactory} switches on this.
     */
    String provider();

    /** User-facing name exposed in the voice-agent catalog. */
    String displayName();

    /** Downstream vendor base URL; opaque to the factory, interpreted by the driver. */
    String baseUrl();

    /**
     * Downstream vendor credential; use {@link Constants#NO_AUTH_API_KEY} only for intentional
     * no-auth upstreams.
     */
    String apiKey();

    /** Provider model name; use a documented sentinel only when a provider does not consume it. */
    String model();

    /** ASR model name for providers that transcribe input audio. */
    Optional<String> transcriptionModel();

    /** Optional entitlement key associated with this voice-agent model. Not enforced here. */
    Optional<String> requiredEntitlement();

    /** Whether a missing-entitlement model is hidden from catalog responses. */
    @WithName("is-stealth")
    @WithDefault("false")
    boolean isStealth();
  }
}
