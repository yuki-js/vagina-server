package app.vagina.server.realtime.oai_cc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

/** Events emitted by the OpenAI Chat Completions streaming response. */
public sealed interface OaiCcEvent
    permits OaiCcEvent.ContentDelta,
        OaiCcEvent.AudioDelta,
        OaiCcEvent.ToolCallDelta,
        OaiCcEvent.Finished,
        OaiCcEvent.ErrorEvent {

  record ContentDelta(String content) implements OaiCcEvent {}

  record AudioDelta(String audioId, String audioBase64, String transcript) implements OaiCcEvent {}

  record ToolCallDelta(int index, String id, String name, String arguments) implements OaiCcEvent {}

  record Finished(String finishReason) implements OaiCcEvent {}

  record ErrorEvent(String message, Object upstreamError) implements OaiCcEvent {}

  final class Parser {
    private final ObjectMapper json;

    public Parser(ObjectMapper json) {
      this.json = json;
    }

    public List<OaiCcEvent> parseLine(String line) {
      if (line == null) {
        return List.of();
      }
      String trimmed = line.trim();
      if (trimmed.isEmpty() || !trimmed.startsWith("data:")) {
        return List.of();
      }
      String data = trimmed.substring(5).trim();
      if ("[DONE]".equals(data)) {
        return List.of(new Finished(null));
      }
      try {
        JsonNode root = json.readTree(data);
        JsonNode choices = root.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
          return List.of();
        }
        JsonNode choice = choices.get(0);
        String finishReason = text(choice, "finish_reason");
        if (finishReason != null) {
          return List.of(new Finished(finishReason));
        }
        JsonNode delta = choice.get("delta");
        if (delta == null || !delta.isObject()) {
          return List.of();
        }
        JsonNode toolCalls = delta.get("tool_calls");
        if (toolCalls != null && toolCalls.isArray() && !toolCalls.isEmpty()) {
          List<OaiCcEvent> events = new ArrayList<>(toolCalls.size());
          for (JsonNode toolCall : toolCalls) {
            JsonNode function = toolCall.get("function");
            events.add(
                new ToolCallDelta(
                    integer(toolCall, "index", 0),
                    text(toolCall, "id"),
                    function == null ? null : text(function, "name"),
                    function == null ? null : text(function, "arguments")));
          }
          return List.copyOf(events);
        }
        JsonNode audio = delta.get("audio");
        if (audio != null && audio.isObject()) {
          return List.of(
              new AudioDelta(text(audio, "id"), text(audio, "data"), text(audio, "transcript")));
        }
        String content = text(delta, "content");
        if (content != null && !content.isEmpty()) {
          return List.of(new ContentDelta(content));
        }
        return List.of();
      } catch (Exception e) {
        return List.of(
            new ErrorEvent("Failed to parse Chat Completions stream chunk: " + e.getMessage(), e));
      }
    }

    private static String text(JsonNode node, String field) {
      JsonNode value = node == null ? null : node.get(field);
      return value == null || value.isNull() ? null : value.asText();
    }

    private static int integer(JsonNode node, String field, int fallback) {
      JsonNode value = node == null ? null : node.get(field);
      return value == null || !value.canConvertToInt() ? fallback : value.asInt();
    }
  }
}
