package app.vagina.server.textagent;

import app.vagina.server.textagent.TextAgentRuntimeModels.ProviderContext;
import app.vagina.server.textagent.TextAgentRuntimeModels.ProviderStateMode;
import app.vagina.server.textagent.TextAgentRuntimeModels.QueryResult;
import app.vagina.server.textagent.TextAgentRuntimeModels.ToolCall;
import java.util.List;
import java.util.Map;

public interface TextAgentAdapter {
  String providerKey();

  ProviderStateMode stateMode();

  QueryResult execute(ProviderContext context);

  default void applyResultToSessionState(ProviderContext context, QueryResult result) {
    switch (result.status()) {
      case COMPLETED, FAILED -> context.sessionState().clearRequestState();
      case REQUIRES_TOOL -> context.sessionState().replacePendingToolCalls(result.toolCalls());
    }
  }

  default List<ToolCall> pendingToolCalls(ProviderContext context) {
    return context.sessionState().pendingToolCalls();
  }

  default Map<String, Object> providerState(ProviderContext context) {
    return context.sessionState().providerState();
  }

  default String boundModelName(ProviderContext context) {
    return context.binding().providerModelName();
  }

  default QueryResult failedProviderConfiguration(String message) {
    return QueryResult.failed("provider_configuration_error", message);
  }
}
