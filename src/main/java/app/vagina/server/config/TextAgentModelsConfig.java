package app.vagina.server.config;

import io.smallrye.config.ConfigMapping;
import java.util.Map;
import java.util.Optional;

@ConfigMapping(prefix = "vagina.text-agent")
public interface TextAgentModelsConfig {

  String defaultModel();

  Map<String, ModelConfig> models();

  interface ModelConfig {
    String provider();

    Optional<String> displayName();

    Optional<String> baseUrl();

    Optional<String> apiKey();

    Optional<String> model();
  }
}
