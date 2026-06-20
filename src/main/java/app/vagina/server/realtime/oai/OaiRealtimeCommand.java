package app.vagina.server.realtime.oai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Base64;
import java.util.Map;

/**
 * Outbound OpenAI Realtime commands and their JSON encoder, aggregated into one file (judgment 8).
 * Mirrors the Dart {@code realtime_command.dart} + {@code realtime_command_encoder.dart}.
 *
 * <p>Each permitted record is one {@code type} the client sends to OpenAI. {@link Encoder} renders a
 * command to the {@link ObjectNode} the transport writes as a single text frame. Audio bytes are
 * base64-encoded here (OpenAI carries PCM as base64 strings in JSON); the VHRP {@code bstr} →
 * {@code byte[]} path means no base64 ever appears on the VHRP side, only on this downstream leg.
 */
public sealed interface OaiRealtimeCommand {

  /** The OpenAI command {@code type}, e.g. {@code "response.create"}. */
  String type();

  /** {@code session.update}: applies the full session config map. */
  record SessionUpdate(Map<String, Object> session) implements OaiRealtimeCommand {
    @Override
    public String type() {
      return "session.update";
    }
  }

  /** {@code input_audio_buffer.append}: appends one PCM chunk to the server-side input buffer. */
  record InputAudioBufferAppend(byte[] audioBytes) implements OaiRealtimeCommand {
    @Override
    public String type() {
      return "input_audio_buffer.append";
    }
  }

  /** {@code input_audio_buffer.commit}: commits the buffered audio as one input turn. */
  record InputAudioBufferCommit() implements OaiRealtimeCommand {
    @Override
    public String type() {
      return "input_audio_buffer.commit";
    }
  }

  /** {@code input_audio_buffer.clear}: discards any buffered, uncommitted input audio. */
  record InputAudioBufferClear() implements OaiRealtimeCommand {
    @Override
    public String type() {
      return "input_audio_buffer.clear";
    }
  }

  /** {@code conversation.item.create}: inserts a user/text/image/tool-output item. */
  record ConversationItemCreate(String previousItemId, Map<String, Object> item)
      implements OaiRealtimeCommand {
    @Override
    public String type() {
      return "conversation.item.create";
    }
  }

  /** {@code response.create}: triggers a model response, optionally with overrides. */
  record ResponseCreate(Map<String, Object> response) implements OaiRealtimeCommand {
    @Override
    public String type() {
      return "response.create";
    }
  }

  /** {@code response.cancel}: cancels the in-flight response (interrupt). */
  record ResponseCancel() implements OaiRealtimeCommand {
    @Override
    public String type() {
      return "response.cancel";
    }
  }

  /**
   * Renders a command to the JSON frame OpenAI expects. The single {@link ObjectMapper} is supplied
   * by the binding so encoder and parser share one configured CBOR-free JSON mapper.
   */
  final class Encoder {

    private final ObjectMapper json;

    public Encoder(ObjectMapper json) {
      this.json = json;
    }

    public ObjectNode encode(OaiRealtimeCommand command) {
      ObjectNode node = json.createObjectNode();
      node.put("type", command.type());
      switch (command) {
        case SessionUpdate c -> node.set("session", json.valueToTree(c.session()));
        case InputAudioBufferAppend c ->
            node.put("audio", Base64.getEncoder().encodeToString(c.audioBytes()));
        case InputAudioBufferCommit ignored -> {
          // type only
        }
        case InputAudioBufferClear ignored -> {
          // type only
        }
        case ConversationItemCreate c -> {
          if (c.previousItemId() != null) {
            node.put("previous_item_id", c.previousItemId());
          }
          node.set("item", json.valueToTree(c.item()));
        }
        case ResponseCreate c -> {
          if (c.response() != null) {
            node.set("response", json.valueToTree(c.response()));
          }
        }
        case ResponseCancel ignored -> {
          // type only
        }
      }
      return node;
    }

    /** Reads a scalar/string field defensively from an inbound node; {@code null} when absent. */
    public static String text(JsonNode node, String field) {
      JsonNode value = node.get(field);
      return value == null || value.isNull() ? null : value.asText();
    }
  }
}
