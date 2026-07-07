package app.vagina.server.resource;

import app.vagina.server.generated.api.VoiceAgentsApi;
import app.vagina.server.generated.model.VoiceAgent;
import app.vagina.server.support.Authenticated;
import app.vagina.server.usecase.VoiceAgentUsecase;
import app.vagina.server.usecase.VoiceAgentUsecase.VoiceAgentView;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import java.util.List;

@ApplicationScoped
@Path("/voice-agents")
@Authenticated
public class VoiceAgentsApiImpl implements VoiceAgentsApi {

  @Inject VoiceAgentUsecase voiceAgentUsecase;

  @Override
  public Response listVoiceAgents() {
    List<VoiceAgent> voiceAgents =
        voiceAgentUsecase.listVoiceAgents().stream().map(this::toGeneratedModel).toList();
    return Response.ok(voiceAgents).build();
  }

  private VoiceAgent toGeneratedModel(VoiceAgentView view) {
    VoiceAgent voiceAgent = new VoiceAgent();
    voiceAgent.setId(view.id());
    voiceAgent.setDisplayName(view.displayName());
    voiceAgent.setIsDefault(view.isDefault());
    return voiceAgent;
  }
}
