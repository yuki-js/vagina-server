package app.vagina.server.config;

import app.vagina.server.mapper.AuthnProviderMapper;
import app.vagina.server.mapper.CallSessionMapper;
import app.vagina.server.mapper.OAuthLoginAttemptMapper;
import app.vagina.server.mapper.RefreshTokenMapper;
import app.vagina.server.mapper.SpeedDialMapper;
import app.vagina.server.mapper.TextAgentMapper;
import app.vagina.server.mapper.UserMapper;
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
      app.vagina.server.generated.model.User.AccountLifecycleEnum.class,
      AuthnProviderMapper.Row.class,
      CallSessionMapper.Row.class,
      OAuthLoginAttemptMapper.Row.class,
      RefreshTokenMapper.Row.class,
      SpeedDialMapper.Row.class,
      TextAgentMapper.Row.class,
      UserMapper.Row.class
    })
public class NativeImageReflectionConfiguration {}
