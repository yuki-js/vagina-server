package app.vagina.server.usecase;

import app.vagina.server.service.TextAgentModelRegistryService;
import app.vagina.server.service.TextAgentModelRegistryService.TextAgentModelPresetView;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;

@ApplicationScoped
public class TextAgentModelRegistryUsecase {

  @Inject TextAgentModelRegistryService textAgentModelRegistryService;

  public List<TextAgentModelPresetView> listTextAgentModels() {
    return textAgentModelRegistryService.listTextAgentModels();
  }
}
