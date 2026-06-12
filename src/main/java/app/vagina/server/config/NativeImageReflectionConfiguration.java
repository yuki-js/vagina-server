package app.vagina.server.config;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection(
    targets = {
      app.vagina.server.generated.model.AuthTokenResponse.class,
      app.vagina.server.generated.model.ErrorResponse.class,
      app.vagina.server.generated.model.GetHello200Response.class,
      app.vagina.server.generated.model.RefreshSessionRequest.class,
      app.vagina.server.generated.model.User.class,
      app.vagina.server.generated.model.User.AccountLifecycleEnum.class
    })
public class NativeImageReflectionConfiguration {}
