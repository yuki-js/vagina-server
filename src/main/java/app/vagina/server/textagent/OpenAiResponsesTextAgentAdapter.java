package app.vagina.server.textagent;

import app.vagina.server.domain.error.ExternalServiceException;
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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class OpenAiResponsesTextAgentAdapter implements TextAgentAdapter {
  static final String PROVIDER_STATE_PREVIOUS_RESPONSE_ID = "previous_response_id";
  static final String PROVIDER_STATE_LAST_REQUEST_ID = "last_request_id";

  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;

  OpenAiResponsesTextAgentAdapter(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
    this.httpClient = HttpClient.newHttpClient();
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
    ResponsesResponse response = postJson(context, responsesUri(context), requestBody(context));
    rememberPreviousResponseId(context, response.id());
    return parseResponse(response);
  }

  private ResponsesRequest requestBody(ProviderContext context) {
    String previousResponseId = previousResponseId(context);
    String instructions = previousResponseId == null ? context.textAgent().getPrompt() : null;
    Object input =
        context.command().isPromptStep()
            ? promptInput(context)
            : context.sessionState().acceptedToolResults().stream()
                .map(this::functionCallOutputInput)
                .toList();
    List<OpenAiTextAgentToolSchemas.ResponsesTool> tools =
        OpenAiTextAgentToolSchemas.responsesTools(context.toolCatalog());
    return new ResponsesRequest(
        boundModelName(context),
        instructions,
        previousResponseId,
        input,
        tools.isEmpty() ? null : tools,
        tools.isEmpty() ? null : "auto");
  }

  private QueryResult parseResponse(ResponsesResponse response) {
    if (response.error() != null) {
      return QueryResult.failed(
          Optional.ofNullable(response.error().code())
              .filter(code -> !code.isBlank())
              .orElse("provider_error"),
          response.error().message());
    }
    List<ToolCall> toolCalls = new ArrayList<>();
    List<String> textParts = new ArrayList<>();
    if (response.output() != null) {
      for (ResponsesOutputItem item : response.output()) {
        if ("function_call".equals(item.type())) {
          toolCalls.add(
              new ToolCall(
                  item.callId(),
                  item.name(),
                  Optional.ofNullable(item.arguments())
                      .filter(arguments -> !arguments.isBlank())
                      .orElse("{}")));
        } else if ("message".equals(item.type())) {
          appendOutputText(textParts, item.content());
        }
      }
    }
    if (!toolCalls.isEmpty()) {
      return QueryResult.requiresTool(toolCalls);
    }
    if (!textParts.isEmpty()) {
      return QueryResult.completed(String.join("", textParts));
    }
    return QueryResult.failed(
        "provider_response_error", "Responses response had no text or tool calls");
  }

  private void appendOutputText(List<String> textParts, List<ResponsesContentPart> content) {
    if (content == null) {
      return;
    }
    for (ResponsesContentPart part : content) {
      if ("output_text".equals(part.type())) {
        textParts.add(part.text());
      }
    }
  }

  private ResponsesResponse postJson(ProviderContext context, URI uri, Object body) {
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
      return objectMapper.readValue(response.body(), ResponsesResponse.class);
    } catch (Exception e) {
      throw new ExternalServiceException("Responses text agent request failed", e);
    }
  }

  private URI responsesUri(ProviderContext context) {
    return Util.resolveUriWithPathSuffix(context.binding().baseUri().orElseThrow(), "/responses");
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
    try {
      return objectMapper.writeValueAsString(requestBody(context));
    } catch (Exception e) {
      throw new IllegalStateException("Failed to encode Responses text agent request", e);
    }
  }

  private Object promptInput(ProviderContext context) {
    if (context.command().images().isEmpty()) {
      return context.command().prompt();
    }
    List<Object> content = new ArrayList<>();
    content.add(
        new ResponsesInputContentPart("input_text", context.command().prompt(), null, null));
    for (QueryImageInput image : context.command().images()) {
      content.add(
          new ResponsesInputContentPart("input_image", null, image.dataUri(), image.detail()));
    }
    return List.of(new ResponsesMessageInput("message", "user", content));
  }

  private FunctionCallOutputInput functionCallOutputInput(ToolResultSubmission toolResult) {
    return new FunctionCallOutputInput(
        "function_call_output", toolResult.toolCallId(), toolResult.output());
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  private record ResponsesRequest(
      String model,
      String instructions,
      @JsonProperty("previous_response_id") String previousResponseId,
      Object input,
      List<OpenAiTextAgentToolSchemas.ResponsesTool> tools,
      @JsonProperty("tool_choice") String toolChoice) {}

  private record FunctionCallOutputInput(
      String type, @JsonProperty("call_id") String callId, String output) {}

  private record ResponsesMessageInput(String type, String role, List<Object> content) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  private record ResponsesInputContentPart(
      String type, String text, @JsonProperty("image_url") String imageUrl, String detail) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record ResponsesResponse(
      String id, List<ResponsesOutputItem> output, ProviderError error) {}

  private record ResponsesOutputItem(
      String id,
      String type,
      String status,
      String name,
      String arguments,
      @JsonProperty("call_id") String callId,
      String role,
      String phase,
      List<?> summary,
      List<ResponsesContentPart> content) {}

  private record ResponsesContentPart(
      String type, String text, Object annotations, Object logprobs) {}

  private record ProviderError(String code, String message, String type, String param) {}
}
