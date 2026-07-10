package app.vagina.server.service;

import app.vagina.server.domain.error.AuthorizationException;
import app.vagina.server.domain.error.ValidationException;
import app.vagina.server.realtime.RealtimeModelsConfig;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Comparator;
import java.util.List;

@ApplicationScoped
public class VoiceAgentService {

  @Inject RealtimeModelsConfig modelsConfig;
  @Inject EntitlementService entitlementService;

  @PostConstruct
  void validateDefaultModel() {
    if (!modelsConfig.models().containsKey(modelsConfig.defaultModel())) {
      throw new IllegalStateException(
          "Default voice agent is not registered: " + modelsConfig.defaultModel());
    }
  }

  public List<ModelCatalogItem> listVoiceAgents(Long userId) {
    return modelsConfig.models().keySet().stream()
        .filter(modelId -> isEntitled(userId, modelId))
        .sorted(Comparator.naturalOrder())
        .map(modelId -> new ModelCatalogItem(modelId, modelId, modelId.equals(defaultModelId())))
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

  public record ModelCatalogItem(String id, String displayName, boolean isDefault) {}
}
