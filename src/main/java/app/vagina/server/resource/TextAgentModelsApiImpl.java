package app.vagina.server.resource;

import app.vagina.server.generated.api.TextAgentModelsApi;
import app.vagina.server.generated.model.ListTextAgentModels200ResponseInner;
import app.vagina.server.service.TextAgentModelRegistryService.TextAgentModelPresetView;
import app.vagina.server.support.Authenticated;
import app.vagina.server.usecase.TextAgentModelRegistryUsecase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import java.util.List;

@ApplicationScoped
@Path("/text-agents/models")
@Authenticated
public class TextAgentModelsApiImpl implements TextAgentModelsApi {

  @Inject TextAgentModelRegistryUsecase textAgentModelRegistryUsecase;

  @Override
  public Response listTextAgentModels() {
    List<ListTextAgentModels200ResponseInner> models =
        textAgentModelRegistryUsecase.listTextAgentModels().stream()
            .map(this::toGeneratedModel)
            .toList();
    return Response.ok(models).build();
  }

  private ListTextAgentModels200ResponseInner toGeneratedModel(TextAgentModelPresetView view) {
    ListTextAgentModels200ResponseInner model = new ListTextAgentModels200ResponseInner();
    model.setId(view.id());
    model.setDisplayName(view.displayName());
    model.setIsDefault(view.isDefault());
    return model;
  }
}
