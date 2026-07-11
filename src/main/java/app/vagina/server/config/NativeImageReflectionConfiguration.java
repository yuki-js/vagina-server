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
      app.vagina.server.generated.model.BulkDeleteSessions200Response.class,
      app.vagina.server.generated.model.BulkDeleteSessionsRequest.class,
      app.vagina.server.generated.model.ErrorResponse.class,
      app.vagina.server.generated.model.ExchangeOidcLoginRequest.class,
      app.vagina.server.generated.model.GetSession200Response.class,
      app.vagina.server.generated.model.JsonRpcVersion.class,
      app.vagina.server.generated.model.ListOidcProviders200ResponseInner.class,
      app.vagina.server.generated.model.ListSessions200Response.class,
      app.vagina.server.generated.model.ListSessions200ResponseItemsInner.class,
      app.vagina.server.generated.model.ListTextAgentModels200ResponseInner.class,
      app.vagina.server.generated.model.QueryTextAgent200Response.class,
      app.vagina.server.generated.model.QueryTextAgent200Response.StatusEnum.class,
      app.vagina.server.generated.model.QueryTextAgent200ResponseError.class,
      app.vagina.server.generated.model.QueryTextAgentRequest.class,
      app.vagina.server.generated.model.QueryTextAgentRequestImagesInner.class,
      app.vagina.server.generated.model.QueryTextAgentRequestImagesInner.DetailEnum.class,
      app.vagina.server.generated.model.QueryTextAgentRequestToolResult.class,
      app.vagina.server.generated.model.QueryTextAgentRequestToolSchemasInner.class,
      app.vagina.server.generated.model.RefreshSessionRequest.class,
      app.vagina.server.generated.model.SessionThread.class,
      app.vagina.server.generated.model.SpeedDial.class,
      app.vagina.server.generated.model.SpeedDial.ReasoningEffortEnum.class,
      app.vagina.server.generated.model.SpeedDialCreateRequest.class,
      app.vagina.server.generated.model.SpeedDialCreateRequest.ReasoningEffortEnum.class,
      app.vagina.server.generated.model.SpeedDialUpdateRequest.class,
      app.vagina.server.generated.model.SpeedDialUpdateRequest.ReasoningEffortEnum.class,
      app.vagina.server.generated.model.StartOidcLogin200Response.class,
      app.vagina.server.generated.model.StartOidcLoginRequest.class,
      app.vagina.server.generated.model.StartOidcLoginRequest.ClientTypeEnum.class,
      app.vagina.server.generated.model.StartOidcLoginRequest.CodeChallengeMethodEnum.class,
      app.vagina.server.generated.model.TextAgent.class,
      app.vagina.server.generated.model.TextAgentToolCall.class,
      app.vagina.server.generated.model.TextAgentWriteRequest.class,
      app.vagina.server.generated.model.User.class,
      app.vagina.server.generated.model.User.AccountLifecycleEnum.class,
      app.vagina.server.generated.model.VfsFile.class,
      app.vagina.server.generated.model.VfsMethod.class,
      app.vagina.server.generated.model.VfsRpcError.class,
      app.vagina.server.generated.model.VfsRpcParams.class,
      app.vagina.server.generated.model.VfsRpcRequest.class,
      app.vagina.server.generated.model.VfsRpcResponse.class,
      app.vagina.server.generated.model.VfsRpcResult.class,
      app.vagina.server.generated.model.VoiceAgent.class,
      AuthnProviderMapper.Row.class,
      CallSessionMapper.Row.class,
      OAuthLoginAttemptMapper.Row.class,
      RefreshTokenMapper.Row.class,
      SpeedDialMapper.Row.class,
      TextAgentMapper.Row.class,
      UserMapper.Row.class
    })
public class NativeImageReflectionConfiguration {}
