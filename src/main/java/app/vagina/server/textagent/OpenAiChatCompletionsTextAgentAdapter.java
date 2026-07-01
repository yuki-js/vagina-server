package app.vagina.server.textagent;

import app.vagina.server.textagent.TextAgentRuntimeModels.ProviderContext;
import app.vagina.server.textagent.TextAgentRuntimeModels.ProviderStateMode;
import app.vagina.server.textagent.TextAgentRuntimeModels.QueryResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class OpenAiChatCompletionsTextAgentAdapter implements TextAgentAdapter {
  static final String PROVIDER_STATE_MESSAGES = "messages";

  private final ObjectMapper objectMapper;

  OpenAiChatCompletionsTextAgentAdapter(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @Override
  public String providerKey() {
    return TextAgentAdapterFactory.PROVIDER_OPENAI_CHAT_COMPLETIONS;
  }

  @Override
  public ProviderStateMode stateMode() {
    return ProviderStateMode.STATELESS_CONTINUATION;
  }

  @Override
  public QueryResult execute(ProviderContext context) {
    if (context.binding().baseUri().isEmpty()) {
      return failedProviderConfiguration("OpenAI Chat Completions base URL is required");
    }
    List<Map<String, Object>> messages = mutableMessages(context);
    if (messages.isEmpty() && context.textAgent().getPrompt() != null) {
      messages.add(Map.of("role", "system", "content", context.textAgent().getPrompt()));
    }
    if (context.command().isPromptStep()) {
      messages.add(Map.of("role", "user", "content", context.command().prompt()));
    } else {
      messages.add(
          Map.of(
              "role",
              "tool",
              "tool_call_id",
              context.command().toolResult().toolCallId(),
              "content",
              context.command().toolResult().output()));
    }
    return notImplementedYet("HTTP execution will be added after VhrpSession wiring");
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> mutableMessages(ProviderContext context) {
    Object existing = providerState(context).get(PROVIDER_STATE_MESSAGES);
    if (existing instanceof List<?>) {
      return (List<Map<String, Object>>) existing;
    }
    List<Map<String, Object>> messages = new ArrayList<>();
    providerState(context).put(PROVIDER_STATE_MESSAGES, messages);
    return messages;
  }

  String previewRequestBody(ProviderContext context) {
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("model", boundModelName(context));
    request.put("messages", mutableMessages(context));
    request.put("stream", false);
    try {
      return objectMapper.writeValueAsString(request);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to encode Chat Completions text agent request", e);
    }
  }
}
