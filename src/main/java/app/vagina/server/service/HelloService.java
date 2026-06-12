package app.vagina.server.service;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class HelloService {

  public String getHelloMessage() {
    return "Hello, World!";
  }
}
