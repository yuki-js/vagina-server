package app.vagina.server.textagent;

import app.vagina.server.textagent.TextAgentRuntimeModels.ProviderContext;
import app.vagina.server.textagent.TextAgentRuntimeModels.ProviderStateMode;
import app.vagina.server.textagent.TextAgentRuntimeModels.QueryResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;

public final class OpenAiResponsesTextAgentAdapter implements TextAgentAdapter {
  static final String PROVIDER_STATE_PREVIOUS_RESPONSE_ID = "previous_response_id";
  static final String PROVIDER_STATE_LAST_REQUEST_ID = "last_request_id";

  private final ObjectMapper objectMapper;

  OpenAiResponsesTextAgentAdapter(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @Override
  public String providerKey() {
    return TextAgentAdapterFactory.PROVIDER_OPENAI_RESPONSES;
  }

  @Override
  public ProviderStateMode stateMode() {
    return ProviderStateMode.STATEFUL_CONTINUATION;
  }

  @Override
  public QueryResult execute(ProviderContext context) {
    if (context.binding().baseUri().isEmpty()) {
      return failedProviderConfiguration("OpenAI Responses base URL is required");
    }
    providerState(context).put(PROVIDER_STATE_LAST_REQUEST_ID, context.command().requestId());
    return notImplementedYet("HTTP execution will be added after VhrpSession wiring");
  }

  public void rememberPreviousResponseId(ProviderContext context, String responseId) {
    if (responseId != null && !responseId.isBlank()) {
      providerState(context).put(PROVIDER_STATE_PREVIOUS_RESPONSE_ID, responseId);
    }
  }

  public String previousResponseId(ProviderContext context) {
    Object value = providerState(context).get(PROVIDER_STATE_PREVIOUS_RESPONSE_ID);
    return value instanceof String text && !text.isBlank() ? text : null;
  }

  String previewRequestBody(ProviderContext context) {
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("model", boundModelName(context));
    request.put("instructions", context.textAgent().getPrompt());
    if (previousResponseId(context) != null) {
      request.put("previous_response_id", previousResponseId(context));
    }
    if (context.command().isPromptStep()) {
      request.put("input", context.command().prompt());
    } else {
      request.put("input", Map.of("type", "function_call_output", "call_id", context.command().toolResult().toolCallId(), "output", context.command().toolResult().output()));
    }
    try {
      return objectMapper.writeValueAsString(request);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to encode Responses text agent request", e);
    }
  }
}
