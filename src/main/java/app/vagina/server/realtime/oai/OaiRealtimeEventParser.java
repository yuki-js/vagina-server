package app.vagina.server.realtime.oai;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Maps a raw OpenAI Realtime JSON frame onto one typed {@link OaiRealtimeEvent}, mirroring the Dart
 * {@code realtime_event_parser.dart}.
 *
 * <p>Two deliberate choices follow the Dart binding:
 *
 * <ul>
 *   <li><b>Aliases collapse.</b> OpenAI emits some events under both a short and an {@code output_}
 *       prefixed name ({@code response.text.delta} ≡ {@code response.output_text.delta}); both map
 *       to the one modelled record, exactly as the Dart {@code switch} lists both cases.
 *   <li><b>Unmodelled types are dropped.</b> An event {@code type} with no typed record returns
 *       {@code null}; the binding simply does not surface it, the same way the Dart client has no
 *       stream for it. A missing/blank {@code type} is the only hard parse failure.
 * </ul>
 */
public final class OaiRealtimeEventParser {

  /**
   * Thrown only for a structurally invalid frame (missing {@code type}); unknown types are dropped.
   */
  public static final class ProtocolException extends RuntimeException {
    public ProtocolException(String message) {
      super(message);
    }
  }

  /** Parses one frame; returns {@code null} for a well-formed frame of an unmodelled type. */
  public OaiRealtimeEvent parse(JsonNode payload) {
    if (payload == null || !payload.isObject()) {
      throw new ProtocolException("OpenAI realtime frame must be a JSON object");
    }
    String type = text(payload, "type");
    if (type == null || type.isBlank()) {
      throw new ProtocolException("OpenAI realtime frame is missing a 'type'");
    }

    return switch (type) {
      case "session.created" ->
          new OaiRealtimeEvent.SessionCreated(session(payload.get("session")));
      case "conversation.created" ->
          new OaiRealtimeEvent.ConversationCreated(conversation(payload.get("conversation")));
      case "conversation.item.created", "conversation.item.added", "conversation.item.done" ->
          new OaiRealtimeEvent.ConversationItemCreated(
              text(payload, "previous_item_id"), item(payload.get("item")));
      case "conversation.item.deleted" ->
          new OaiRealtimeEvent.ConversationItemDeleted(text(payload, "item_id"));
      case "conversation.item.input_audio_transcription.delta" ->
          new OaiRealtimeEvent.InputAudioTranscriptionDelta(
              text(payload, "item_id"), integer(payload, "content_index"), text(payload, "delta"));
      case "conversation.item.input_audio_transcription.completed" ->
          new OaiRealtimeEvent.InputAudioTranscriptionCompleted(
              text(payload, "item_id"),
              integer(payload, "content_index"),
              text(payload, "transcript"));
      case "input_audio_buffer.speech_started" ->
          new OaiRealtimeEvent.InputAudioBufferSpeechStarted(text(payload, "item_id"));
      case "input_audio_buffer.speech_stopped" ->
          new OaiRealtimeEvent.InputAudioBufferSpeechStopped(text(payload, "item_id"));
      case "response.output_item.added" ->
          new OaiRealtimeEvent.ResponseOutputItemAdded(
              text(payload, "response_id"),
              integer(payload, "output_index"),
              item(payload.get("item")));
      case "response.output_item.done" ->
          new OaiRealtimeEvent.ResponseOutputItemDone(
              text(payload, "response_id"),
              integer(payload, "output_index"),
              item(payload.get("item")));
      case "response.content_part.added" ->
          new OaiRealtimeEvent.ResponseContentPartAdded(
              text(payload, "item_id"),
              integer(payload, "content_index"),
              contentPart(payload.get("part")));
      case "response.content_part.done" ->
          new OaiRealtimeEvent.ResponseContentPartDone(
              text(payload, "item_id"),
              integer(payload, "content_index"),
              contentPart(payload.get("part")));
      case "response.text.delta", "response.output_text.delta" ->
          new OaiRealtimeEvent.ResponseOutputTextDelta(
              text(payload, "item_id"), integer(payload, "content_index"), text(payload, "delta"));
      case "response.text.done", "response.output_text.done" ->
          new OaiRealtimeEvent.ResponseOutputTextDone(
              text(payload, "item_id"), integer(payload, "content_index"), text(payload, "text"));
      case "response.audio.delta", "response.output_audio.delta" ->
          new OaiRealtimeEvent.ResponseOutputAudioDelta(
              text(payload, "item_id"), integer(payload, "content_index"), text(payload, "delta"));
      case "response.audio.done", "response.output_audio.done" ->
          new OaiRealtimeEvent.ResponseOutputAudioDone(
              text(payload, "item_id"), integer(payload, "content_index"));
      case "response.audio_transcript.delta", "response.output_audio_transcript.delta" ->
          new OaiRealtimeEvent.ResponseOutputAudioTranscriptDelta(
              text(payload, "item_id"), integer(payload, "content_index"), text(payload, "delta"));
      case "response.audio_transcript.done", "response.output_audio_transcript.done" ->
          new OaiRealtimeEvent.ResponseOutputAudioTranscriptDone(
              text(payload, "item_id"),
              integer(payload, "content_index"),
              text(payload, "transcript"));
      case "response.function_call_arguments.delta" ->
          new OaiRealtimeEvent.ResponseFunctionCallArgumentsDelta(
              text(payload, "item_id"), text(payload, "call_id"), text(payload, "delta"));
      case "response.function_call_arguments.done" ->
          new OaiRealtimeEvent.ResponseFunctionCallArgumentsDone(
              text(payload, "item_id"),
              text(payload, "call_id"),
              text(payload, "name"),
              text(payload, "arguments"));
      case "response.created" ->
          new OaiRealtimeEvent.ResponseCreated(responseId(payload.get("response")));
      case "response.done" ->
          new OaiRealtimeEvent.ResponseDone(
              responseId(payload.get("response")), responseStatus(payload.get("response")));
      case "error" -> new OaiRealtimeEvent.ErrorEvent(errorDetail(payload.get("error")));
        // Unmodelled but well-formed: dropped, like the Dart binding having no stream for it.
      default -> null;
    };
  }

  // ---------------------------------------------------------------------------
  // Value-type parsing
  // ---------------------------------------------------------------------------

  private OaiRealtimeEvent.Conversation conversation(JsonNode node) {
    if (node == null) {
      return new OaiRealtimeEvent.Conversation("");
    }
    return new OaiRealtimeEvent.Conversation(Objects.requireNonNullElse(text(node, "id"), ""));
  }

  private OaiRealtimeEvent.Session session(JsonNode node) {
    if (node == null) {
      return new OaiRealtimeEvent.Session("", null, null, null);
    }
    return new OaiRealtimeEvent.Session(
        Objects.requireNonNullElse(text(node, "id"), ""),
        text(node, "model"),
        text(node, "voice"),
        text(node, "instructions"));
  }

  private OaiRealtimeEvent.ErrorDetail errorDetail(JsonNode node) {
    if (node == null) {
      return new OaiRealtimeEvent.ErrorDetail("unknown", null, "Unknown error", null, null);
    }
    String t = text(node, "type");
    return new OaiRealtimeEvent.ErrorDetail(
        t == null ? "unknown" : t,
        text(node, "code"),
        Objects.requireNonNullElse(text(node, "message"), "Unknown error"),
        text(node, "param"),
        text(node, "event_id"));
  }

  private OaiRealtimeEvent.ContentPart contentPart(JsonNode node) {
    if (node == null) {
      return null;
    }
    return new OaiRealtimeEvent.ContentPart(
        Objects.requireNonNullElse(text(node, "type"), ""),
        text(node, "text"),
        text(node, "audio"),
        text(node, "transcript"),
        text(node, "detail"),
        text(node, "image_url"));
  }

  private OaiRealtimeEvent.ConversationItem item(JsonNode node) {
    if (node == null) {
      return null;
    }
    List<OaiRealtimeEvent.ContentPart> content = new ArrayList<>();
    JsonNode contentNode = node.get("content");
    if (contentNode != null && contentNode.isArray()) {
      for (JsonNode entry : contentNode) {
        OaiRealtimeEvent.ContentPart part = contentPart(entry);
        if (part != null) {
          content.add(part);
        }
      }
    }
    String itemType = Objects.requireNonNullElse(text(node, "type"), "");
    return new OaiRealtimeEvent.ConversationItem(
        Objects.requireNonNullElse(text(node, "id"), ""),
        itemType,
        itemStatus(node, itemType),
        text(node, "role"),
        content,
        text(node, "call_id"),
        text(node, "name"),
        text(node, "arguments"),
        text(node, "output"));
  }

  private String itemStatus(JsonNode node, String itemType) {
    String status = text(node, "status");
    if (status != null && !status.isBlank()) {
      return status;
    }
    if ("function_call_output".equals(itemType)) {
      return "completed";
    }
    return "in_progress";
  }

  private static String responseId(JsonNode node) {
    if (node == null) {
      return null;
    }
    return text(node, "id");
  }

  private static String responseStatus(JsonNode node) {
    if (node == null) {
      return null;
    }
    return text(node, "status");
  }

  // ---------------------------------------------------------------------------
  // Field helpers
  // ---------------------------------------------------------------------------

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? null : value.asText();
  }

  private static Integer integer(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || value.isNull() || !value.isNumber() ? null : value.asInt();
  }
}
