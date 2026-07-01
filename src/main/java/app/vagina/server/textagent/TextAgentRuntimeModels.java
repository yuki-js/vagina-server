package app.vagina.server.textagent;

import app.vagina.server.entity.TextAgentDefinition;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class TextAgentRuntimeModels {
  private TextAgentRuntimeModels() {}

  public enum ProviderStateMode {
    STATELESS_CONTINUATION,
    STATEFUL_CONTINUATION
  }

  public enum QueryStatus {
    COMPLETED,
    REQUIRES_TOOL,
    FAILED
  }

  public record TextAgentModelBinding(
      String textModelId, String provider, Optional<String> baseUrl, Optional<String> apiKey,
      Optional<String> model) {
    public TextAgentModelBinding {
      if (textModelId == null || textModelId.isBlank()) {
        throw new IllegalArgumentException("Text model id is required");
      }
      if (provider == null || provider.isBlank()) {
        throw new IllegalArgumentException("Text agent provider is required");
      }
      baseUrl = baseUrl == null ? Optional.empty() : baseUrl;
      apiKey = apiKey == null ? Optional.empty() : apiKey;
      model = model == null ? Optional.empty() : model;
    }

    public Optional<URI> baseUri() {
      return baseUrl.filter(value -> !value.isBlank()).map(URI::create);
    }

    public String providerModelName() {
      return model.filter(value -> !value.isBlank()).orElse(textModelId);
    }
  }

  public record QueryCommand(
      String voiceSessionId, String requestId, String prompt, ToolResultSubmission toolResult) {
    public boolean isPromptStep() {
      return prompt != null;
    }

    public boolean isToolResultStep() {
      return toolResult != null;
    }

    public void requireValidShape() {
      if (voiceSessionId == null || voiceSessionId.isBlank()) {
        throw new IllegalArgumentException("Voice session id is required");
      }
      if (requestId == null || requestId.isBlank()) {
        throw new IllegalArgumentException("Request id is required");
      }
      if (isPromptStep() == isToolResultStep()) {
        throw new IllegalArgumentException("Exactly one of prompt or toolResult is required");
      }
      if (isPromptStep() && prompt.isBlank()) {
        throw new IllegalArgumentException("Prompt must not be blank");
      }
      if (isToolResultStep()) {
        toolResult.requireValidShape();
      }
    }
  }

  public record ToolResultSubmission(String toolCallId, String output, boolean isError) {
    public void requireValidShape() {
      if (toolCallId == null || toolCallId.isBlank()) {
        throw new IllegalArgumentException("Tool call id is required");
      }
      if (output == null) {
        throw new IllegalArgumentException("Tool output is required");
      }
    }
  }

  public record ToolCall(String id, String name, String arguments) {
    public ToolCall {
      if (id == null || id.isBlank()) {
        throw new IllegalArgumentException("Tool call id is required");
      }
      if (name == null || name.isBlank()) {
        throw new IllegalArgumentException("Tool call name is required");
      }
      if (arguments == null || arguments.isBlank()) {
        arguments = "{}";
      }
    }
  }

  public record QueryError(String code, String message) {
    public QueryError {
      if (code == null || code.isBlank()) {
        code = "text_agent_error";
      }
      if (message == null || message.isBlank()) {
        message = "Text agent query failed";
      }
    }
  }

  public record QueryResult(QueryStatus status, String text, List<ToolCall> toolCalls, QueryError error) {
    public QueryResult {
      if (status == null) {
        status = QueryStatus.FAILED;
      }
      toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
      if (status == QueryStatus.FAILED && error == null) {
        error = new QueryError("text_agent_error", "Text agent query failed");
      }
    }

    public static QueryResult completed(String text) {
      return new QueryResult(QueryStatus.COMPLETED, text == null ? "" : text, List.of(), null);
    }

    public static QueryResult requiresTool(List<ToolCall> toolCalls) {
      return new QueryResult(QueryStatus.REQUIRES_TOOL, null, toolCalls, null);
    }

    public static QueryResult failed(String code, String message) {
      return new QueryResult(QueryStatus.FAILED, null, List.of(), new QueryError(code, message));
    }
  }

  public static final class ProviderSessionState {
    private final String textAgentId;
    private final TextAgentModelBinding binding;
    private final Map<String, Object> providerState = new LinkedHashMap<>();
    private final List<ToolCall> pendingToolCalls = new ArrayList<>();

    public ProviderSessionState(String textAgentId, TextAgentModelBinding binding) {
      if (textAgentId == null || textAgentId.isBlank()) {
        throw new IllegalArgumentException("Text agent id is required");
      }
      this.textAgentId = textAgentId;
      this.binding = binding;
    }

    public String textAgentId() {
      return textAgentId;
    }

    public TextAgentModelBinding binding() {
      return binding;
    }

    public Map<String, Object> providerState() {
      return providerState;
    }

    public List<ToolCall> pendingToolCalls() {
      return List.copyOf(pendingToolCalls);
    }

    public boolean hasPendingToolCall(String toolCallId) {
      return pendingToolCalls.stream().anyMatch(toolCall -> toolCall.id().equals(toolCallId));
    }

    public boolean hasPendingToolCalls() {
      return !pendingToolCalls.isEmpty();
    }

    public void replacePendingToolCalls(List<ToolCall> toolCalls) {
      pendingToolCalls.clear();
      if (toolCalls != null) {
        pendingToolCalls.addAll(toolCalls);
      }
    }

    public void clearPendingToolCalls() {
      pendingToolCalls.clear();
    }
  }

  public record ProviderContext(
      TextAgentDefinition textAgent, QueryCommand command, ProviderSessionState sessionState) {
    public ProviderContext {
      if (textAgent == null) {
        throw new IllegalArgumentException("Text agent definition is required");
      }
      if (command == null) {
        throw new IllegalArgumentException("Text agent query command is required");
      }
      if (sessionState == null) {
        throw new IllegalArgumentException("Text agent provider session state is required");
      }
      command.requireValidShape();
    }

    public TextAgentModelBinding binding() {
      return sessionState.binding();
    }
  }
}
