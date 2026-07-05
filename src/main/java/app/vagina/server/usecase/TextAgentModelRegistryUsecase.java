package app.vagina.server.usecase;

import app.vagina.server.service.TextAgentModelRegistryService;
import app.vagina.server.service.VoiceAgentService.ModelCatalogItem;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;

@ApplicationScoped
public class TextAgentModelRegistryUsecase {

  @Inject TextAgentModelRegistryService textAgentModelRegistryService;

  public List<ModelCatalogItem> listTextAgentModels() {
    return textAgentModelRegistryService.listTextAgentModels();
  }
}
