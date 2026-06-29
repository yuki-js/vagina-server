package app.vagina.server.config;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection(
    targets = {
      app.vagina.server.generated.model.AuthTokenResponse.class,
      app.vagina.server.generated.model.ErrorResponse.class,
      app.vagina.server.generated.model.ExchangeOidcLoginRequest.class,
      app.vagina.server.generated.model.RefreshSessionRequest.class,
      app.vagina.server.generated.model.StartOidcLogin200Response.class,
      app.vagina.server.generated.model.StartOidcLoginRequest.class,
      app.vagina.server.generated.model.StartOidcLoginRequest.ClientTypeEnum.class,
      app.vagina.server.generated.model.TextAgent.class,
      app.vagina.server.generated.model.ListTextAgentModels200ResponseInner.class,
      app.vagina.server.generated.model.User.class,
      app.vagina.server.generated.model.User.AccountLifecycleEnum.class
    })
public class NativeImageReflectionConfiguration {}
