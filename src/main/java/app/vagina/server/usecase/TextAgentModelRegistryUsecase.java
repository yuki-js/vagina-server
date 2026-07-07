package app.vagina.server.usecase;

import app.vagina.server.service.TextAgentModelRegistryService;
import app.vagina.server.service.TextAgentModelRegistryService.TextAgentModelCatalogItem;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;

@ApplicationScoped
public class TextAgentModelRegistryUsecase {

  @Inject TextAgentModelRegistryService textAgentModelRegistryService;

  public List<TextAgentModelView> listTextAgentModels() {
    return textAgentModelRegistryService.listTextAgentModels().stream().map(this::toView).toList();
  }

  private TextAgentModelView toView(TextAgentModelCatalogItem item) {
    return new TextAgentModelView(item.id(), item.displayName(), item.isDefault());
  }

  public record TextAgentModelView(String id, String displayName, boolean isDefault) {}
}
