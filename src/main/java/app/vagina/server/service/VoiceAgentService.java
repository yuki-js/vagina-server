package app.vagina.server.service;

import app.vagina.server.domain.error.AuthorizationException;
import app.vagina.server.domain.error.ValidationException;
import app.vagina.server.realtime.RealtimeModelsConfig;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

@ApplicationScoped
public class VoiceAgentService {
  private static final Set<String> REASONING_EFFORTS =
      Set.of("none", "minimal", "low", "medium", "high", "xhigh", "max");

  @Inject RealtimeModelsConfig modelsConfig;
  @Inject EntitlementService entitlementService;

  @PostConstruct
  void validateConfiguration() {
    if (!modelsConfig.models().containsKey(modelsConfig.defaultModel())) {
      throw new IllegalStateException(
          "Default voice agent is not registered: " + modelsConfig.defaultModel());
    }
    modelsConfig.models().forEach(this::validateReasoningConfiguration);
  }

  private void validateReasoningConfiguration(
      String modelId, RealtimeModelsConfig.ModelConfig model) {
    model
        .reasoningEffort()
        .ifPresent(
            configured -> {
              if (!REASONING_EFFORTS.contains(configured)) {
                throw new IllegalStateException(
                    "Voice agent model "
                        + modelId
                        + " has invalid reasoning-effort: "
                        + configured);
              }
            });
  }

  public List<ModelCatalogItem> listVoiceAgents(Long userId) {
    return modelsConfig.models().keySet().stream()
        .filter(modelId -> isVisible(userId, modelId))
        .sorted(Comparator.naturalOrder())
        .map(
            modelId ->
                new ModelCatalogItem(
                    modelId,
                    displayName(modelId),
                    modelId.equals(defaultModelId()),
                    isEntitled(userId, modelId)))
        .toList();
  }

  public String defaultModelId() {
    return modelsConfig.defaultModel();
  }

  public void validateEntitledModelId(Long userId, String modelId) {
    if (modelId == null || modelId.isBlank()) {
      throw new ValidationException("Voice agent id is required");
    }
    if (!modelsConfig.models().containsKey(modelId)) {
      throw new ValidationException("Unknown voice agent id: " + modelId);
    }
    String requiredEntitlement = requiredEntitlement(modelId);
    if (requiredEntitlement == null) {
      return;
    }
    if (!entitlementService.hasActiveEntitlement(userId, requiredEntitlement)) {
      throw new AuthorizationException(
          "Missing required entitlement for voice agent " + modelId + ": " + requiredEntitlement);
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

  private String displayName(String modelId) {
    return modelsConfig.models().get(modelId).displayName();
  }

  public record ModelCatalogItem(
      String id, String displayName, boolean isDefault, boolean isAvailable) {}
}
