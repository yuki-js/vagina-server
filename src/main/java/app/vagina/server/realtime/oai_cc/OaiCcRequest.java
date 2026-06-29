package app.vagina.server.realtime.oai_cc;

import app.vagina.server.realtime.model.RealtimeAdapterModels;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builder for OpenAI-compatible Chat Completions request payloads. */
public final class OaiCcRequest {
  private final String model;
  private final List<Map<String, Object>> messages;
  private final boolean audio;
  private final String voice;
  private final List<RealtimeAdapterModels.ToolDefinition> tools;
  private final boolean toolChoiceRequired;
  private final String reasoningEffort;

  public OaiCcRequest(
      String model,
      List<Map<String, Object>> messages,
      boolean audio,
      String voice,
      List<RealtimeAdapterModels.ToolDefinition> tools,
      boolean toolChoiceRequired,
      String reasoningEffort) {
    this.model = model;
    this.messages = List.copyOf(messages);
    this.audio = audio;
    this.voice = voice;
    this.tools = tools == null ? List.of() : List.copyOf(tools);
    this.toolChoiceRequired = toolChoiceRequired;
    this.reasoningEffort = reasoningEffort;
  }

  public Map<String, Object> toJsonShape() {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("model", model);
    payload.put("messages", messages);
    payload.put("stream", true);
    if (audio) {
      payload.put("modalities", List.of("text", "audio"));
      payload.put(
          "audio",
          Map.of("voice", voice == null || voice.isBlank() ? "alloy" : voice, "format", "pcm16"));
    }
    if (reasoningEffort != null && !reasoningEffort.isBlank()) {
      payload.put("reasoning_effort", reasoningEffort);
    }
    List<Map<String, Object>> toolShapes = toolShapes();
    if (!toolShapes.isEmpty()) {
      payload.put("tools", toolShapes);
      payload.put("tool_choice", toolChoiceRequired ? "required" : "auto");
    }
    return payload;
  }

  public String toJson(ObjectMapper json) {
    try {
      return json.writeValueAsString(toJsonShape());
    } catch (Exception e) {
      throw new IllegalStateException("Failed to encode Chat Completions request", e);
    }
  }

  private List<Map<String, Object>> toolShapes() {
    List<Map<String, Object>> shapes = new ArrayList<>();
    for (RealtimeAdapterModels.ToolDefinition tool : tools) {
      Map<String, Object> function = new LinkedHashMap<>();
      function.put("name", tool.name());
      if (tool.description() != null) {
        function.put("description", tool.description());
      }
      function.put("parameters", normalizeParametersSchema(tool.parameters()));
      shapes.add(Map.of("type", "function", "function", function));
    }
    return shapes;
  }

  private static Map<String, Object> normalizeParametersSchema(Map<String, Object> params) {
    Map<String, Object> normalized = new LinkedHashMap<>();
    if (params != null) {
      normalized.putAll(params);
    }
    normalized.putIfAbsent("type", "object");
    if ("object".equals(normalized.get("type"))) {
      normalized.putIfAbsent("properties", new LinkedHashMap<String, Object>());
    }
    return normalized;
  }
}
