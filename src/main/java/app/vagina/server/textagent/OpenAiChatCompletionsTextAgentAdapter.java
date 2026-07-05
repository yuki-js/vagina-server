package app.vagina.server.textagent;

import app.vagina.server.support.Util;
import app.vagina.server.textagent.TextAgentRuntimeModels.ProviderContext;
import app.vagina.server.textagent.TextAgentRuntimeModels.ProviderStateMode;
import app.vagina.server.textagent.TextAgentRuntimeModels.QueryImageInput;
import app.vagina.server.textagent.TextAgentRuntimeModels.QueryResult;
import app.vagina.server.textagent.TextAgentRuntimeModels.ToolCall;
import app.vagina.server.textagent.TextAgentRuntimeModels.ToolResultSubmission;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class OpenAiChatCompletionsTextAgentAdapter implements TextAgentAdapter {
  static final String PROVIDER_STATE_MESSAGES = "messages";

  private final OpenAiTextAgentHttpClient http;

  OpenAiChatCompletionsTextAgentAdapter(ObjectMapper objectMapper) {
    this.http = new OpenAiTextAgentHttpClient(objectMapper);
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
        http.postJson(
            context,
            chatCompletionsUri(context),
            requestBody(context, messages),
            ChatCompletionResponse.class,
            "Chat Completions text agent request failed");
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
      messages.add(
          ChatCompletionMessage.user(context.command().prompt(), context.command().images()));
      return;
    }
    for (ToolResultSubmission toolResult : context.sessionState().acceptedToolResults()) {
      messages.add(ChatCompletionMessage.toolOutput(toolResult.toolCallId(), toolResult.output()));
    }
  }

  private ChatCompletionRequest requestBody(
      ProviderContext context, List<ChatCompletionMessage> messages) {
    List<OpenAiTextAgentToolSchemas.ChatCompletionsTool> tools =
        OpenAiTextAgentToolSchemas.chatCompletionsTools(context.toolCatalog());
    return new ChatCompletionRequest(
        boundModelName(context),
        messages,
        false,
        tools.isEmpty() ? null : tools,
        tools.isEmpty() ? null : "auto");
  }

  private QueryResult parseResponse(ChatCompletionResponse response) {
    if (response.error() != null) {
      return QueryResult.failed(
          Optional.ofNullable(response.error().code())
              .filter(code -> !code.isBlank())
              .orElse("provider_error"),
          response.error().message());
    }
    ChatCompletionChoice choice =
        response.choices() == null ? null : response.choices().stream().findFirst().orElse(null);
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
                  Optional.ofNullable(toolCall.function().arguments())
                      .filter(arguments -> !arguments.isBlank())
                      .orElse("{}")));
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

  private URI chatCompletionsUri(ProviderContext context) {
    return Util.resolveUriWithPathSuffix(
        context.binding().baseUri().orElseThrow(), "/chat/completions");
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
    return http.writeJson(
        requestBody(context, mutableMessages(context)),
        "Failed to encode Chat Completions text agent request");
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  private record ChatCompletionRequest(
      String model,
      List<ChatCompletionMessage> messages,
      boolean stream,
      List<OpenAiTextAgentToolSchemas.ChatCompletionsTool> tools,
      @JsonProperty("tool_choice") String toolChoice) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  private record ChatCompletionMessage(
      String role,
      Object content,
      @JsonProperty("tool_call_id") String toolCallId,
      @JsonProperty("tool_calls") List<ChatCompletionToolCall> toolCalls) {
    static ChatCompletionMessage system(String content) {
      return new ChatCompletionMessage("system", content, null, null);
    }

    static ChatCompletionMessage user(String content, List<QueryImageInput> images) {
      if (images == null || images.isEmpty()) {
        return new ChatCompletionMessage("user", content, null, null);
      }
      List<Object> parts = new ArrayList<>();
      parts.add(Map.of("type", "text", "text", content));
      for (QueryImageInput image : images) {
        parts.add(
            Map.of(
                "type",
                "image_url",
                "image_url",
                Map.of("url", image.dataUri(), "detail", image.detail())));
      }
      return new ChatCompletionMessage("user", parts, null, null);
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

  private record ChatCompletionToolCall(String id, String type, ChatCompletionFunction function) {}

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
