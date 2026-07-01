package app.vagina.server.textagent;

import app.vagina.server.domain.error.ExternalServiceException;
import app.vagina.server.textagent.TextAgentRuntimeModels.ProviderContext;
import app.vagina.server.textagent.TextAgentRuntimeModels.ProviderStateMode;
import app.vagina.server.textagent.TextAgentRuntimeModels.QueryResult;
import app.vagina.server.textagent.TextAgentRuntimeModels.ToolCall;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

public final class OpenAiChatCompletionsTextAgentAdapter implements TextAgentAdapter {
  static final String PROVIDER_STATE_MESSAGES = "messages";

  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;

  OpenAiChatCompletionsTextAgentAdapter(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
    this.httpClient = HttpClient.newHttpClient();
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
    List<ChatCompletionMessage> messages = mutableMessages(context);
    ensureSystemMessage(context, messages);
    appendCurrentInput(context, messages);
    ChatCompletionResponse response =
        postJson(context, chatCompletionsUri(context), requestBody(context, messages));
    QueryResult result = parseResponse(response);
    if (result.status() == TextAgentRuntimeModels.QueryStatus.REQUIRES_TOOL) {
      messages.add(ChatCompletionMessage.assistantToolCalls(result.toolCalls()));
    } else if (result.status() == TextAgentRuntimeModels.QueryStatus.COMPLETED) {
      messages.add(ChatCompletionMessage.assistantText(result.text()));
    }
    return result;
  }

  private void ensureSystemMessage(ProviderContext context, List<ChatCompletionMessage> messages) {
    if (messages.isEmpty() && context.textAgent().getPrompt() != null) {
      messages.add(ChatCompletionMessage.system(context.textAgent().getPrompt()));
    }
  }

  private void appendCurrentInput(ProviderContext context, List<ChatCompletionMessage> messages) {
    if (context.command().isPromptStep()) {
      messages.add(ChatCompletionMessage.user(context.command().prompt()));
      return;
    }
    messages.add(
        ChatCompletionMessage.toolOutput(
            context.command().toolResult().toolCallId(), context.command().toolResult().output()));
  }

  private ChatCompletionRequest requestBody(
      ProviderContext context, List<ChatCompletionMessage> messages) {
    return new ChatCompletionRequest(boundModelName(context), messages, false);
  }

  private QueryResult parseResponse(ChatCompletionResponse response) {
    if (response.error() != null) {
      return QueryResult.failed(
          fallback(response.error().code(), "provider_error"), response.error().message());
    }
    ChatCompletionChoice choice = first(response.choices());
    if (choice == null || choice.message() == null) {
      return QueryResult.failed(
          "provider_response_error", "Chat Completions response had no choices");
    }
    ChatCompletionResponseMessage message = choice.message();
    if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
      List<ToolCall> calls = new ArrayList<>();
      for (ChatCompletionResponseToolCall toolCall : message.toolCalls()) {
        if (toolCall.function() != null) {
          calls.add(
              new ToolCall(
                  toolCall.id(),
                  toolCall.function().name(),
                  fallback(toolCall.function().arguments(), "{}")));
        }
      }
      return QueryResult.requiresTool(calls);
    }
    if (message.content() != null) {
      return QueryResult.completed(message.content());
    }
    return QueryResult.failed(
        "provider_response_error", "Chat Completions response had no text or tool calls");
  }

  private ChatCompletionResponse postJson(ProviderContext context, URI uri, Object body) {
    try {
      HttpRequest.Builder builder =
          HttpRequest.newBuilder(uri)
              .header("Content-Type", "application/json")
              .header("Accept", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
      context
          .binding()
          .apiKey()
          .filter(key -> !key.isBlank())
          .ifPresent(
              key -> {
                builder.header("Authorization", "Bearer " + key);
                builder.header("api-key", key);
              });
      HttpResponse<String> response =
          httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
      return objectMapper.readValue(response.body(), ChatCompletionResponse.class);
    } catch (Exception e) {
      throw new ExternalServiceException("Chat Completions text agent request failed", e);
    }
  }

  private URI chatCompletionsUri(ProviderContext context) {
    URI baseUri = context.binding().baseUri().orElseThrow();
    String path = baseUri.getPath();
    String normalizedPath = path == null || path.isBlank() ? "" : path.replaceAll("/+$", "");
    if (!normalizedPath.endsWith("/chat/completions")) {
      normalizedPath = normalizedPath + "/chat/completions";
    }
    String query =
        baseUri.getQuery() == null || baseUri.getQuery().isBlank() ? "" : "?" + baseUri.getQuery();
    return baseUri.resolve(normalizedPath + query);
  }

  @SuppressWarnings("unchecked")
  private List<ChatCompletionMessage> mutableMessages(ProviderContext context) {
    Object existing = providerState(context).get(PROVIDER_STATE_MESSAGES);
    if (existing instanceof List<?>) {
      return (List<ChatCompletionMessage>) existing;
    }
    List<ChatCompletionMessage> messages = new ArrayList<>();
    providerState(context).put(PROVIDER_STATE_MESSAGES, messages);
    return messages;
  }

  String previewRequestBody(ProviderContext context) {
    try {
      return objectMapper.writeValueAsString(requestBody(context, mutableMessages(context)));
    } catch (Exception e) {
      throw new IllegalStateException("Failed to encode Chat Completions text agent request", e);
    }
  }

  private static <T> T first(List<T> values) {
    return values == null || values.isEmpty() ? null : values.get(0);
  }

  private static String fallback(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  private record ChatCompletionRequest(
      String model, List<ChatCompletionMessage> messages, boolean stream) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  private record ChatCompletionMessage(
      String role,
      String content,
      @JsonProperty("tool_call_id") String toolCallId,
      @JsonProperty("tool_calls") List<ChatCompletionToolCall> toolCalls) {
    static ChatCompletionMessage system(String content) {
      return new ChatCompletionMessage("system", content, null, null);
    }

    static ChatCompletionMessage user(String content) {
      return new ChatCompletionMessage("user", content, null, null);
    }

    static ChatCompletionMessage assistantText(String content) {
      return new ChatCompletionMessage("assistant", content, null, null);
    }

    static ChatCompletionMessage toolOutput(String toolCallId, String content) {
      return new ChatCompletionMessage("tool", content, toolCallId, null);
    }

    static ChatCompletionMessage assistantToolCalls(List<ToolCall> toolCalls) {
      return new ChatCompletionMessage(
          "assistant",
          null,
          null,
          toolCalls.stream()
              .map(
                  toolCall ->
                      new ChatCompletionToolCall(
                          toolCall.id(),
                          "function",
                          new ChatCompletionFunction(toolCall.name(), toolCall.arguments())))
              .toList());
    }
  }

  private record ChatCompletionToolCall(
      String id, String type, ChatCompletionFunction function) {}

  private record ChatCompletionFunction(String name, String arguments) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record ChatCompletionResponse(List<ChatCompletionChoice> choices, ProviderError error) {}

  private record ChatCompletionChoice(
      @JsonProperty("finish_reason") String finishReason,
      Integer index,
      ChatCompletionResponseMessage message) {}

  private record ChatCompletionResponseMessage(
      String role,
      String content,
      Object annotations,
      String refusal,
      @JsonProperty("tool_calls") List<ChatCompletionResponseToolCall> toolCalls) {}

  private record ChatCompletionResponseToolCall(
      String id, String type, ChatCompletionResponseFunction function) {}

  private record ChatCompletionResponseFunction(String name, String arguments) {}

  private record ProviderError(String code, String message, String type, String param) {}
}
