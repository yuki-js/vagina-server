package app.vagina.server.service;

import app.vagina.server.config.TextAgentModelsConfig;
import app.vagina.server.domain.error.AuthorizationException;
import app.vagina.server.domain.error.ValidationException;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class TextAgentModelRegistryService {

  @Inject TextAgentModelsConfig modelsConfig;
  @Inject EntitlementService entitlementService;

  @PostConstruct
  void validateDefaultModel() {
    if (!modelsConfig.models().containsKey(modelsConfig.defaultModel())) {
      throw new IllegalStateException(
          "Default text agent model is not registered: " + modelsConfig.defaultModel());
    }
  }

  public List<TextAgentModelCatalogItem> listTextAgentModels(Long userId) {
    return modelsConfig.models().entrySet().stream()
        .filter(entry -> isEntitled(userId, entry.getKey()))
        .sorted(Comparator.comparing(entry -> entry.getKey()))
        .map(
            entry ->
                new TextAgentModelCatalogItem(
                    entry.getKey(),
                    entry.getValue().displayName().orElse(entry.getKey()),
                    entry.getKey().equals(defaultModelId())))
        .toList();
  }

  public String defaultModelId() {
    return modelsConfig.defaultModel();
  }

  public void validateEntitledModelId(Long userId, String modelId) {
    if (modelId == null || modelId.isBlank()) {
      throw new ValidationException("Text model id is required");
    }
    if (!modelsConfig.models().containsKey(modelId)) {
      throw new ValidationException("Unknown text model id: " + modelId);
    }
    String requiredEntitlement = requiredEntitlement(modelId);
    if (requiredEntitlement == null) {
      return;
    }
    if (!entitlementService.hasActiveEntitlement(userId, requiredEntitlement)) {
      throw new AuthorizationException(
          "Missing required entitlement for text agent model "
              + modelId
              + ": "
              + requiredEntitlement);
    }
  }

  private boolean isEntitled(Long userId, String modelId) {
    String requiredEntitlement = requiredEntitlement(modelId);
    return requiredEntitlement == null
        || entitlementService.hasActiveEntitlement(userId, requiredEntitlement);
  }

  private String requiredEntitlement(String modelId) {
    return modelsConfig
        .models()
        .get(modelId)
        .requiredEntitlement()
        .map(String::trim)
        .filter(value -> !value.isEmpty())
        .orElse(null);
  }

  public TextAgentModelConfigView getModelConfig(String modelId) {
    TextAgentModelsConfig.ModelConfig model = modelsConfig.models().get(modelId);
    if (model == null) {
      throw new IllegalArgumentException("Unknown text model id: " + modelId);
    }
    return new TextAgentModelConfigView(
        modelId, model.provider(), model.baseUrl(), model.apiKey(), model.model());
  }

  public record TextAgentModelCatalogItem(String id, String displayName, boolean isDefault) {}

  public record TextAgentModelConfigView(
      String id,
      String provider,
      Optional<String> baseUrl,
      Optional<String> apiKey,
      Optional<String> model) {}
}
