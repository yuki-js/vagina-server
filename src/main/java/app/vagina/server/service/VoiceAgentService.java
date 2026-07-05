package app.vagina.server.service;

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

  @PostConstruct
  void validateDefaultModel() {
    if (!modelsConfig.models().containsKey(modelsConfig.defaultModel())) {
      throw new IllegalStateException(
          "Default voice agent is not registered: " + modelsConfig.defaultModel());
    }
  }

  public List<ModelCatalogItem> listVoiceAgents() {
    return modelsConfig.models().keySet().stream()
        .sorted(Comparator.naturalOrder())
        .map(modelId -> new ModelCatalogItem(modelId, modelId, modelId.equals(defaultModelId())))
        .toList();
  }

  public String defaultModelId() {
    return modelsConfig.defaultModel();
  }

  public boolean isKnownModel(String modelId) {
    return modelsConfig.models().containsKey(modelId);
  }

  public void validateKnownModelId(String modelId) {
    if (modelId == null || modelId.isBlank()) {
      throw new ValidationException("Voice agent id is required");
    }
    if (!isKnownModel(modelId)) {
      throw new ValidationException("Unknown voice agent id: " + modelId);
    }
  }

  public record ModelCatalogItem(String id, String displayName, boolean isDefault) {}
}
