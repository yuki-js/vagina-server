package app.vagina.server.service;

import app.vagina.server.config.TextAgentModelsConfig;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class TextAgentModelRegistryService {

  @Inject TextAgentModelsConfig modelsConfig;

  @PostConstruct
  void validateDefaultModel() {
    if (!modelsConfig.models().containsKey(modelsConfig.defaultModel())) {
      throw new IllegalStateException(
          "Default text agent model is not registered: " + modelsConfig.defaultModel());
    }
  }

  public List<TextAgentModelPresetView> listTextAgentModels() {
    return modelsConfig.models().entrySet().stream()
        .sorted(Comparator.comparing(entry -> entry.getKey()))
        .map(
            entry ->
                new TextAgentModelPresetView(
                    entry.getKey(),
                    entry.getValue().displayName().orElse(entry.getKey()),
                    isDefault(entry.getKey())))
        .toList();
  }

  public String defaultModelId() {
    return modelsConfig.defaultModel();
  }

  public boolean isKnownModel(String modelId) {
    return modelsConfig.models().containsKey(modelId);
  }

  public TextAgentModelConfigView getModelConfig(String modelId) {
    TextAgentModelsConfig.ModelConfig model = modelsConfig.models().get(modelId);
    if (model == null) {
      throw new IllegalArgumentException("Unknown text model id: " + modelId);
    }
    return new TextAgentModelConfigView(
        modelId, model.provider(), model.baseUrl(), model.apiKey(), model.model());
  }

  private boolean isDefault(String modelId) {
    return modelId.equals(modelsConfig.defaultModel());
  }

  public record TextAgentModelPresetView(String id, String displayName, boolean isDefault) {}

  public record TextAgentModelConfigView(
      String id,
      String provider,
      Optional<String> baseUrl,
      Optional<String> apiKey,
      Optional<String> model) {}
}
