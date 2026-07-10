package app.vagina.server.usecase;

import app.vagina.server.service.VoiceAgentService;
import app.vagina.server.service.VoiceAgentService.ModelCatalogItem;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;

@ApplicationScoped
public class VoiceAgentUsecase {

  @Inject VoiceAgentService voiceAgentService;

  public List<VoiceAgentView> listVoiceAgents(Long currentUserId) {
    return voiceAgentService.listVoiceAgents(currentUserId).stream().map(this::toView).toList();
  }

  private VoiceAgentView toView(ModelCatalogItem item) {
    return new VoiceAgentView(item.id(), item.displayName(), item.isDefault());
  }

  public record VoiceAgentView(String id, String displayName, boolean isDefault) {}
}
