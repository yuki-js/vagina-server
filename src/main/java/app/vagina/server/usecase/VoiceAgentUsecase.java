package app.vagina.server.usecase;

import app.vagina.server.service.VoiceAgentService;
import app.vagina.server.service.VoiceAgentService.ModelCatalogItem;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;

@ApplicationScoped
public class VoiceAgentUsecase {

  @Inject VoiceAgentService voiceAgentService;

  public List<ModelCatalogItem> listVoiceAgents() {
    return voiceAgentService.listVoiceAgents();
  }
}
