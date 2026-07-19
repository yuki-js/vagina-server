package app.vagina.server.textagent;

import app.vagina.server.support.Util;
import app.vagina.server.textagent.OpenAiTextAgentHttpClient.PostJsonResult;
import app.vagina.server.textagent.OpenAiTextAgentHttpClient.ProviderFailure;
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
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class OpenAiResponsesTextAgentAdapter implements TextAgentAdapter {
  static final String PROVIDER_STATE_PREVIOUS_RESPONSE_ID = "previous_response_id";
  static final String PROVIDER_STATE_LAST_REQUEST_ID = "last_request_id";
  private static final String SYNTHETIC_TOOL_FAILURE_OUTPUT =
      "{\"success\":false,\"error\":\"Tool continuation was rejected by the AI provider.\"}";

  private final OpenAiTextAgentHttpClient http;

  OpenAiResponsesTextAgentAdapter(ObjectMapper objectMapper) {
    this.http = new OpenAiTextAgentHttpClient(objectMapper);
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
    PostJsonResult<ResponsesResponse> httpResult = postRequest(context, requestBody(context));
    if (httpResult.rejectedByProvider()) {
      ProviderFailure failure = httpResult.providerFailure();
      return QueryResult.failed(failure.code(), failure.message());
    }
    ResponsesResponse response = httpResult.body();
    QueryResult result = parseResponse(response);
    if (result.status() == TextAgentRuntimeModels.QueryStatus.COMPLETED
        || result.status() == TextAgentRuntimeModels.QueryStatus.REQUIRES_TOOL) {
      rememberPreviousResponseId(context, response.id());
      providerState(context).put(PROVIDER_STATE_LAST_REQUEST_ID, context.command().requestId());
    }
    return result;
  }

  @Override
  public boolean recoverTerminalToolFailure(ProviderContext context) {
    List<FunctionCallOutputInput> syntheticOutputs =
        context.sessionState().acceptedToolResults().stream()
            .map(
                toolResult ->
                    new FunctionCallOutputInput(
                        "function_call_output",
                        toolResult.toolCallId(),
                        SYNTHETIC_TOOL_FAILURE_OUTPUT))
            .toList();
    ResponsesRequest recoveryRequest =
        new ResponsesRequest(
            boundModelName(context),
            null,
            previousResponseId(context),
            syntheticOutputs,
            null,
            null,
            reasoning(context));
    try {
      PostJsonResult<ResponsesResponse> httpResult = postRequest(context, recoveryRequest);
      if (httpResult.rejectedByProvider()) {
        return false;
      }
      ResponsesResponse response = httpResult.body();
      QueryResult result = parseResponse(response);
      if (result.status() != TextAgentRuntimeModels.QueryStatus.COMPLETED) {
        return false;
      }
      rememberPreviousResponseId(context, response.id());
      return true;
    } catch (RuntimeException e) {
      return false;
    }
  }

  private PostJsonResult<ResponsesResponse> postRequest(
      ProviderContext context, ResponsesRequest request) {
    return http.postJson(
        context,
        responsesUri(context),
        request,
        ResponsesResponse.class,
        "Responses text agent request failed");
  }

  private ResponsesRequest requestBody(ProviderContext context) {
    String previousResponseId = previousResponseId(context);
    String instructions = previousResponseId == null ? context.textAgent().prompt() : null;
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
        tools.isEmpty() ? null : "auto",
        reasoning(context));
  }

  private ResponsesReasoning reasoning(ProviderContext context) {
    String effort = context.binding().reasoningEffort();
    String mode = context.binding().reasoningMode();
    return effort == null && mode == null ? null : new ResponsesReasoning(effort, mode);
  }

  private QueryResult parseResponse(ResponsesResponse response) {
    if (response.error() != null) {
      return QueryResult.failed(
          Optional.ofNullable(response.error().code())
              .filter(code -> !code.isBlank())
              .orElse("provider_error"),
          response.error().message());
    }
    List<ResponsesOutputItem> output = response.output() == null ? List.of() : response.output();
    Set<String> resolvedCallIds = new HashSet<>();
    for (ResponsesOutputItem item : output) {
      if ("function_call_output".equals(item.type())
          && item.callId() != null
          && !item.callId().isBlank()) {
        resolvedCallIds.add(item.callId());
      }
    }

    List<ToolCall> pendingToolCalls = new ArrayList<>();
    List<String> textParts = new ArrayList<>();
    for (ResponsesOutputItem item : output) {
      if ("function_call".equals(item.type()) && !resolvedCallIds.contains(item.callId())) {
        pendingToolCalls.add(
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
    if (!pendingToolCalls.isEmpty()) {
      return QueryResult.requiresTool(pendingToolCalls);
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

  private URI responsesUri(ProviderContext context) {
    return Util.resolveUriWithPathSuffix(context.binding().baseUri(), "/responses");
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
    return http.writeJson(requestBody(context), "Failed to encode Responses text agent request");
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

  @RegisterForReflection
  @JsonInclude(JsonInclude.Include.NON_NULL)
  private record ResponsesRequest(
      String model,
      String instructions,
      @JsonProperty("previous_response_id") String previousResponseId,
      Object input,
      List<OpenAiTextAgentToolSchemas.ResponsesTool> tools,
      @JsonProperty("tool_choice") String toolChoice,
      ResponsesReasoning reasoning) {}

  @RegisterForReflection
  @JsonInclude(JsonInclude.Include.NON_NULL)
  private record ResponsesReasoning(String effort, String mode) {}

  @RegisterForReflection
  private record FunctionCallOutputInput(
      String type, @JsonProperty("call_id") String callId, String output) {}

  @RegisterForReflection
  private record ResponsesMessageInput(String type, String role, List<Object> content) {}

  @RegisterForReflection
  @JsonInclude(JsonInclude.Include.NON_NULL)
  private record ResponsesInputContentPart(
      String type, String text, @JsonProperty("image_url") String imageUrl, String detail) {}

  @RegisterForReflection
  @JsonIgnoreProperties(ignoreUnknown = true)
  private record ResponsesResponse(
      String id, String status, List<ResponsesOutputItem> output, ProviderError error) {}

  @RegisterForReflection
  private record ResponsesOutputItem(
      String id,
      String type,
      String status,
      String name,
      String arguments,
      @JsonProperty("call_id") String callId,
      String output,
      String role,
      String phase,
      List<?> summary,
      List<ResponsesContentPart> content) {}

  @RegisterForReflection
  private record ResponsesContentPart(
      String type, String text, Object annotations, Object logprobs) {}

  @RegisterForReflection
  private record ProviderError(String code, String message, String type, String param) {}
}
