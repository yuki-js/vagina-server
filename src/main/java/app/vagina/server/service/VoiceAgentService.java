package app.vagina.server.service;

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

  public List<VoiceAgentView> listVoiceAgents() {
    return modelsConfig.models().keySet().stream()
        .sorted(Comparator.naturalOrder())
        .map(modelId -> new VoiceAgentView(modelId, modelId, isDefault(modelId)))
        .toList();
  }

  public String defaultModelId() {
    return modelsConfig.defaultModel();
  }

  public boolean isKnownModel(String modelId) {
    return modelsConfig.models().containsKey(modelId);
  }

  private boolean isDefault(String modelId) {
    return modelId.equals(modelsConfig.defaultModel());
  }

  public record VoiceAgentView(String id, String displayName, boolean isDefault) {}
}
