package app.vagina.server.usecase;

import app.vagina.server.generated.model.HelloResponse;
import app.vagina.server.service.HelloService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class HelloUsecase {

  @Inject HelloService helloService;

  public HelloResponse getHello() {
    HelloResponse response = new HelloResponse();
    response.setMessage(helloService.getHelloMessage());
    return response;
  }
}
