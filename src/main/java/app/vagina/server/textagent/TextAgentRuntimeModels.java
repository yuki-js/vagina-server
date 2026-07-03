package app.vagina.server.textagent;

import app.vagina.server.entity.TextAgentDefinition;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
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
      String textModelId,
      String provider,
      Optional<String> baseUrl,
      Optional<String> apiKey,
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
      String voiceSessionId,
      String requestId,
      String prompt,
      List<QueryImageInput> images,
      ToolResultSubmission toolResult,
      List<ToolCatalogEntry> toolCatalog) {
    public static final int MAX_IMAGE_COUNT = 4;
    public static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;

    public QueryCommand {
      images = images == null ? List.of() : List.copyOf(images);
      toolCatalog = toolCatalog == null ? List.of() : List.copyOf(toolCatalog);
    }

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
      if (isPromptStep()) {
        if (images.size() > MAX_IMAGE_COUNT) {
          throw new IllegalArgumentException("At most " + MAX_IMAGE_COUNT + " images are allowed");
        }
        images.forEach(QueryImageInput::requireValidShape);
      }
      if (isToolResultStep()) {
        if (!images.isEmpty()) {
          throw new IllegalArgumentException("Images are allowed only with prompt steps");
        }
        toolResult.requireValidShape();
      }
    }
  }

  public record QueryImageInput(String dataUri, String detail, String name) {
    public QueryImageInput {
      detail = detail == null || detail.isBlank() ? "auto" : detail;
    }

    public void requireValidShape() {
      if (dataUri == null || dataUri.isBlank()) {
        throw new IllegalArgumentException("Image dataUri is required");
      }
      if (!detail.equals("auto") && !detail.equals("low") && !detail.equals("high")) {
        throw new IllegalArgumentException("Image detail must be auto, low, or high");
      }
      String prefix;
      if (dataUri.startsWith("data:image/png;base64,")) {
        prefix = "data:image/png;base64,";
      } else if (dataUri.startsWith("data:image/jpeg;base64,")) {
        prefix = "data:image/jpeg;base64,";
      } else {
        throw new IllegalArgumentException("Image dataUri must be a PNG or JPEG data URI");
      }
      byte[] decoded;
      try {
        decoded = Base64.getDecoder().decode(dataUri.substring(prefix.length()));
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("Image dataUri base64 is invalid", e);
      }
      if (decoded.length > QueryCommand.MAX_IMAGE_BYTES) {
        throw new IllegalArgumentException("Image dataUri exceeds maximum decoded size");
      }
      if (prefix.contains("png") && !hasPngMagic(decoded)) {
        throw new IllegalArgumentException("PNG image dataUri does not contain PNG bytes");
      }
      if (prefix.contains("jpeg") && !hasJpegMagic(decoded)) {
        throw new IllegalArgumentException("JPEG image dataUri does not contain JPEG bytes");
      }
    }

    private static boolean hasPngMagic(byte[] bytes) {
      return bytes.length >= 8
          && (bytes[0] & 0xFF) == 0x89
          && (bytes[1] & 0xFF) == 0x50
          && (bytes[2] & 0xFF) == 0x4E
          && (bytes[3] & 0xFF) == 0x47
          && (bytes[4] & 0xFF) == 0x0D
          && (bytes[5] & 0xFF) == 0x0A
          && (bytes[6] & 0xFF) == 0x1A
          && (bytes[7] & 0xFF) == 0x0A;
    }

    private static boolean hasJpegMagic(byte[] bytes) {
      return bytes.length >= 3
          && (bytes[0] & 0xFF) == 0xFF
          && (bytes[1] & 0xFF) == 0xD8
          && (bytes[2] & 0xFF) == 0xFF;
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

  public record ToolCatalogEntry(String name, String description, Map<String, Object> parameters) {
    public ToolCatalogEntry {
      if (name == null || name.isBlank()) {
        throw new IllegalArgumentException("Tool catalog entry name is required");
      }
      if (description == null) {
        description = "";
      }
      parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
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

  public record QueryResult(
      QueryStatus status, String text, List<ToolCall> toolCalls, QueryError error) {
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
    private final Map<String, ToolResultSubmission> acceptedToolResults = new LinkedHashMap<>();
    private String activeRequestId;
    private Instant activeRequestStartedAt;

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

    public Optional<String> activeRequestId() {
      return Optional.ofNullable(activeRequestId);
    }

    public boolean hasActiveRequest() {
      return activeRequestId != null;
    }

    public void startRequest(String requestId) {
      if (requestId == null || requestId.isBlank()) {
        throw new IllegalArgumentException("Request id is required");
      }
      activeRequestId = requestId;
      activeRequestStartedAt = Instant.now();
      pendingToolCalls.clear();
      acceptedToolResults.clear();
    }

    public boolean clearExpiredRequest(Duration ttl, Instant now) {
      if (activeRequestId == null || activeRequestStartedAt == null) {
        return false;
      }
      if (activeRequestStartedAt.plus(ttl).isAfter(now)) {
        return false;
      }
      clearRequestState();
      return true;
    }

    public void clearRequestState() {
      activeRequestId = null;
      activeRequestStartedAt = null;
      pendingToolCalls.clear();
      acceptedToolResults.clear();
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

    public boolean hasAcceptedToolResult(String toolCallId) {
      return acceptedToolResults.containsKey(toolCallId);
    }

    public boolean acceptPendingToolResult(ToolResultSubmission toolResult) {
      if (toolResult == null) {
        throw new IllegalArgumentException("Tool result is required");
      }
      String toolCallId = toolResult.toolCallId();
      if (acceptedToolResults.containsKey(toolCallId)) {
        return false;
      }
      boolean removed = pendingToolCalls.removeIf(toolCall -> toolCall.id().equals(toolCallId));
      if (removed) {
        acceptedToolResults.put(toolCallId, toolResult);
      }
      return removed;
    }

    public List<ToolResultSubmission> acceptedToolResults() {
      return List.copyOf(acceptedToolResults.values());
    }

    public void replacePendingToolCalls(List<ToolCall> toolCalls) {
      pendingToolCalls.clear();
      acceptedToolResults.clear();
      if (toolCalls != null) {
        pendingToolCalls.addAll(toolCalls);
      }
    }

    public void clearPendingToolCalls() {
      pendingToolCalls.clear();
      acceptedToolResults.clear();
    }
  }

  public record ProviderContext(
      TextAgentDefinition textAgent,
      QueryCommand command,
      ProviderSessionState sessionState,
      List<ToolCatalogEntry> toolCatalog) {
    public ProviderContext(
        TextAgentDefinition textAgent, QueryCommand command, ProviderSessionState sessionState) {
      this(textAgent, command, sessionState, List.of());
    }

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
      toolCatalog = toolCatalog == null ? List.of() : List.copyOf(toolCatalog);
      command.requireValidShape();
    }

    public TextAgentModelBinding binding() {
      return sessionState.binding();
    }
  }
}
