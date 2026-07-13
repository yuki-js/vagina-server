package app.vagina.server.service;

import app.vagina.server.config.TextAgentModelsConfig;
import app.vagina.server.domain.error.AuthorizationException;
import app.vagina.server.domain.error.ValidationException;
import app.vagina.server.textagent.TextAgentAdapterFactory;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@ApplicationScoped
public class TextAgentModelRegistryService {
  private static final Set<String> REASONING_EFFORTS =
      Set.of("none", "minimal", "low", "medium", "high", "xhigh", "max");
  private static final Set<String> REASONING_MODES = Set.of("standard", "pro");

  @Inject TextAgentModelsConfig modelsConfig;
  @Inject EntitlementService entitlementService;

  @PostConstruct
  void validateConfiguration() {
    if (!modelsConfig.models().containsKey(modelsConfig.defaultModel())) {
      throw new IllegalStateException(
          "Default text agent model is not registered: " + modelsConfig.defaultModel());
    }
    modelsConfig.models().forEach(this::validateReasoningConfiguration);
  }

  private void validateReasoningConfiguration(
      String modelId, TextAgentModelsConfig.ModelConfig model) {
    validateOptionalValue(modelId, "reasoning-effort", model.reasoningEffort(), REASONING_EFFORTS);
    validateOptionalValue(modelId, "reasoning-mode", model.reasoningMode(), REASONING_MODES);
    if (model.reasoningMode().isPresent()
        && !TextAgentAdapterFactory.PROVIDER_OPENAI_RESPONSES.equals(model.provider())) {
      throw new IllegalStateException(
          "Text agent model "
              + modelId
              + " configures reasoning-mode for unsupported provider "
              + model.provider());
    }
  }

  private void validateOptionalValue(
      String modelId, String property, Optional<String> value, Set<String> allowedValues) {
    value.ifPresent(
        configured -> {
          if (!allowedValues.contains(configured)) {
            throw new IllegalStateException(
                "Text agent model " + modelId + " has invalid " + property + ": " + configured);
          }
        });
  }

  public List<TextAgentModelCatalogItem> listTextAgentModels(Long userId) {
    return modelsConfig.models().entrySet().stream()
        .filter(entry -> isVisible(userId, entry.getKey()))
        .sorted(Comparator.comparing(entry -> entry.getKey()))
        .map(
            entry ->
                new TextAgentModelCatalogItem(
                    entry.getKey(),
                    entry.getValue().displayName(),
                    entry.getKey().equals(defaultModelId()),
                    isEntitled(userId, entry.getKey())))
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

  private boolean isVisible(Long userId, String modelId) {
    return isEntitled(userId, modelId) || !isStealth(modelId);
  }

  private boolean isEntitled(Long userId, String modelId) {
    String requiredEntitlement = requiredEntitlement(modelId);
    return requiredEntitlement == null
        || entitlementService.hasActiveEntitlement(userId, requiredEntitlement);
  }

  private boolean isStealth(String modelId) {
    return modelsConfig.models().get(modelId).isStealth();
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
        modelId,
        model.provider(),
        model.baseUrl(),
        model.apiKey(),
        model.model(),
        model.reasoningEffort().orElse(null),
        model.reasoningMode().orElse(null));
  }

  public record TextAgentModelCatalogItem(
      String id, String displayName, boolean isDefault, boolean isAvailable) {}

  public record TextAgentModelConfigView(
      String id,
      String provider,
      String baseUrl,
      String apiKey,
      String model,
      String reasoningEffort,
      String reasoningMode) {}
}
