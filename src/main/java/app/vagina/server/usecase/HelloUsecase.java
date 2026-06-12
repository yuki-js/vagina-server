package app.vagina.server.usecase;

import app.vagina.server.service.HelloService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class HelloUsecase {

  @Inject HelloService helloService;

  public String getHello() {
    return helloService.getHelloMessage();
  }
}
