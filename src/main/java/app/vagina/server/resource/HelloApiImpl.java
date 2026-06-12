package app.vagina.server.resource;

import app.vagina.server.generated.api.HelloApi;
import app.vagina.server.generated.model.GetHello200Response;
import app.vagina.server.usecase.HelloUsecase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@ApplicationScoped
@Path("/")
public class HelloApiImpl implements HelloApi {

  @Inject HelloUsecase helloUsecase;

  @Override
  @GET
  @Path("/hello")
  @Produces(MediaType.APPLICATION_JSON)
  public Response getHello() {
    String message = helloUsecase.getHello();
    GetHello200Response response = new GetHello200Response();
    response.setMessage(message);
    return Response.ok(response).build();
  }
}
