package app.vagina.server.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;
import java.util.Map;
import java.util.Optional;

@ConfigMapping(prefix = "vagina.text-agent")
public interface TextAgentModelsConfig {

  String defaultModel();

  Map<String, ModelConfig> models();

  interface ModelConfig {
    String provider();

    String displayName();

    String baseUrl();

    String apiKey();

    String model();

    Optional<String> requiredEntitlement();

    @WithName("is-stealth")
    @WithDefault("false")
    boolean isStealth();
  }
}
