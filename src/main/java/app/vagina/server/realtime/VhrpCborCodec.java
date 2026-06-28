package app.vagina.server.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import io.vertx.core.buffer.Buffer;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Translates VHRP/1 binary frames to/from the typed {@link VhrpMessage} set.
 *
 * <p>On the wire every application message is exactly one CBOR map with the common envelope {@code
 * { type, [messageId], [replyTo], body }} (see {@code
 * client/docs/hosted_realtime/02_vhrp_wire_protocol.md}). This codec is the only place that touches
 * that raw shape; everything above it works with records. Binary payloads ride as CBOR {@code bstr}
 * and surface as {@code byte[]}, so base64 never appears server-side. There is no {@code streamSeq}
 * or thread revision on the envelope: a {@code thread.patch} is a fire-and-forget live delta and
 * the single recovery for any gap is reconnect + a fresh full {@code thread.snapshot}.
 *
 * <p>Decoding is strict on the envelope (missing {@code type}/{@code body} is a {@link
 * VhrpException.ProtocolBadMessage}) and on the set of client types (unknown type is a {@link
 * VhrpException.ProtocolUnsupportedMessageType}). It is lenient on unknown {@code body} fields, per
 * the protocol's forward-compatibility rule.
 */
@ApplicationScoped
public class VhrpCborCodec {

  private final ObjectMapper cbor = new ObjectMapper(new CBORFactory());

  // ---------------------------------------------------------------------------
  // Decode: Buffer -> VhrpMessage (client -> server)
  // ---------------------------------------------------------------------------

  /**
   * Decodes one inbound binary frame into a typed message.
   *
   * <p>Throws an unchecked {@link VhrpException} so the failure flows through the endpoint's single
   * {@code @OnError} funnel; the codec never decides whether the connection closes (that is the
   * endpoint's contextual call). A malformed envelope is a {@link
   * VhrpException.ProtocolBadMessage}; an unknown {@code type} is a {@link
   * VhrpException.ProtocolUnsupportedMessageType}.
   */
  public VhrpMessage.C2S decode(Buffer frame) {
    JsonNode root;
    try {
      root = cbor.readTree(frame.getBytes());
    } catch (IOException e) {
      throw new VhrpException.ProtocolBadMessage("Frame is not valid CBOR", e);
    }
    if (root == null || !root.isObject()) {
      throw new VhrpException.ProtocolBadMessage("VHRP frame must be a CBOR map");
    }

    JsonNode typeNode = root.get("type");
    if (typeNode == null || !typeNode.isTextual()) {
      throw new VhrpException.ProtocolBadMessage("VHRP envelope is missing a textual 'type'");
    }
    JsonNode body = root.get("body");
    if (body == null || !body.isObject()) {
      throw new VhrpException.ProtocolBadMessage("VHRP envelope is missing an object 'body'");
    }
    String messageId = text(root, "messageId");

    String type = typeNode.asText();
    return switch (type) {
      case "session.open" -> decodeSessionOpen(messageId, body);
      case "audio.turn.mode.set" -> new VhrpMessage.AudioTurnModeSet(text(body, "mode"));
      case "session.instructions.set" ->
          new VhrpMessage.SessionInstructionsSet(messageId, text(body, "instructions"));
      case "live.audio.chunk" ->
          new VhrpMessage.LiveAudioChunk(bytes(body, "pcm"), longValue(body, "sequence", 0L));
      case "turn.audio.submit" ->
          new VhrpMessage.TurnAudioSubmit(
              messageId,
              text(body, "clientItemId"),
              bytes(body, "pcm"),
              intValue(body, "sampleRate", 24000),
              intValue(body, "channels", 1),
              intValue(body, "bitDepth", 16));
      case "turn.text.submit" ->
          new VhrpMessage.TurnTextSubmit(messageId, text(body, "clientItemId"), text(body, "text"));
      case "turn.image.submit" ->
          new VhrpMessage.TurnImageSubmit(
              messageId, text(body, "clientItemId"), bytes(body, "imageBytes"));
      case "tools.set" -> new VhrpMessage.ToolsSet(messageId, decodeToolSpecs(body.get("tools")));
      case "session.extension.apply" ->
          new VhrpMessage.SessionExtensionApply(
              messageId, text(body, "extensionType"), toMap(body.get("payload")));
      case "tool.result.submit" ->
          new VhrpMessage.ToolResultSubmit(
              messageId,
              text(body, "clientItemId"),
              text(body, "callId"),
              text(body, "output"),
              text(body, "disposition"),
              text(body, "errorMessage"));
      case "assistant.interrupt" -> new VhrpMessage.AssistantInterrupt(text(body, "reason"));
      case "session.end" -> new VhrpMessage.SessionEnd();
      case "thread.sync.request" ->
          new VhrpMessage.ThreadSyncRequest(messageId, text(body, "reason"));
      default ->
          throw new VhrpException.ProtocolUnsupportedMessageType(
              "Unsupported VHRP message type: " + type);
    };
  }

  private VhrpMessage.SessionOpen decodeSessionOpen(String messageId, JsonNode body) {
    JsonNode resumeNode = body.get("resume");
    VhrpMessage.ResumeRequest resume =
        resumeNode != null && resumeNode.isObject()
            ? new VhrpMessage.ResumeRequest(text(resumeNode, "sessionId"))
            : null;
    return new VhrpMessage.SessionOpen(
        messageId,
        text(body, "token"),
        text(body, "modelId"),
        text(body, "voice"),
        text(body, "instructions"),
        text(body, "audioTurnMode"),
        decodeAudioFormat(body.get("inputAudio")),
        decodeAudioFormat(body.get("outputAudio")),
        resume,
        toMap(body.get("client")));
  }

  private VhrpMessage.AudioFormat decodeAudioFormat(JsonNode node) {
    if (node == null || !node.isObject()) {
      return null;
    }
    return new VhrpMessage.AudioFormat(
        text(node, "encoding"), intValue(node, "sampleRate", 24000), intValue(node, "channels", 1));
  }

  private List<VhrpMessage.ToolSpec> decodeToolSpecs(JsonNode toolsNode) {
    List<VhrpMessage.ToolSpec> tools = new ArrayList<>();
    if (toolsNode != null && toolsNode.isArray()) {
      for (JsonNode tool : toolsNode) {
        tools.add(
            new VhrpMessage.ToolSpec(
                text(tool, "name"), text(tool, "description"), toMap(tool.get("parameters"))));
      }
    }
    return List.copyOf(tools);
  }

  // ---------------------------------------------------------------------------
  // Encode: VhrpMessage -> Buffer (server -> client)
  // ---------------------------------------------------------------------------

  /** Encodes any server-to-client message into a single CBOR frame. */
  public Buffer encode(VhrpMessage message) {
    ObjectNode root = cbor.createObjectNode();
    root.put("type", message.type());
    ObjectNode body = root.putObject("body");

    switch (message) {
      case VhrpMessage.SessionReady m -> {
        putIfPresent(root, "replyTo", m.replyTo());
        body.put("sessionId", m.sessionId());
        body.put("threadId", m.threadId());
        putIfPresent(body, "conversationId", m.conversationId());
        ObjectNode caps = body.putObject("capabilities");
        putStringArray(caps, "extensions", m.capabilityExtensions());
      }
      case VhrpMessage.SessionResumed m -> {
        putIfPresent(root, "replyTo", m.replyTo());
        body.put("sessionId", m.sessionId());
        body.put("threadId", m.threadId());
        putIfPresent(body, "conversationId", m.conversationId());
      }
      case VhrpMessage.Ack m -> {
        putIfPresent(root, "replyTo", m.replyTo());
        body.put("accepted", m.accepted());
        putIfPresent(body, "clientItemId", m.clientItemId());
        body.put("applied", m.applied());
      }
      case VhrpMessage.ThreadSnapshot m -> {
        body.put("threadId", m.threadId());
        putIfPresent(body, "conversationId", m.conversationId());
        body.set("items", toArrayOfMaps(m.items()));
      }
      case VhrpMessage.ThreadPatch m -> body.set("ops", toArrayOfMaps(m.ops()));
      case VhrpMessage.AssistantAudioChunk m -> {
        body.put("itemId", m.itemId());
        body.put("contentIndex", m.contentIndex());
        body.put("pcm", m.pcm());
      }
      case VhrpMessage.AssistantAudioDone m -> {
        body.put("itemId", m.itemId());
        body.put("contentIndex", m.contentIndex());
      }
      case VhrpMessage.VadState m -> body.put("isSpeaking", m.isSpeaking());
      case VhrpMessage.Error m -> {
        putIfPresent(root, "replyTo", m.replyTo());
        body.put("code", m.code());
        body.put("message", m.message());
        body.put("recoverable", m.recoverable());
      }
        // Client-to-server messages are never encoded by the server. Receiving one here is a server
        // bug, not a wire condition, so fail loudly rather than emitting a malformed frame.
      default ->
          throw new IllegalArgumentException(
              "Refusing to encode non-S2C VHRP message: " + message.type());
    }

    try {
      return Buffer.buffer(cbor.writeValueAsBytes(root));
    } catch (IOException e) {
      // The tree is built entirely from in-memory values, so this should not happen; surface it as
      // an unchecked fault rather than forcing every caller to handle a checked exception.
      throw new IllegalStateException("Failed to CBOR-encode VHRP message " + message.type(), e);
    }
  }

  // ---------------------------------------------------------------------------
  // Envelope helpers
  // ---------------------------------------------------------------------------

  private static void putIfPresent(ObjectNode node, String field, String value) {
    if (value != null) {
      node.put(field, value);
    }
  }

  private static void putStringArray(ObjectNode node, String field, List<String> values) {
    ArrayNode array = node.putArray(field);
    if (values != null) {
      values.forEach(array::add);
    }
  }

  private ArrayNode toArrayOfMaps(List<Map<String, Object>> maps) {
    ArrayNode array = cbor.createArrayNode();
    if (maps != null) {
      for (Map<String, Object> map : maps) {
        array.add(cbor.valueToTree(map));
      }
    }
    return array;
  }

  // ---------------------------------------------------------------------------
  // Body field readers
  // ---------------------------------------------------------------------------

  private static String text(JsonNode parent, String field) {
    JsonNode node = parent.get(field);
    return node != null && node.isTextual() ? node.asText() : null;
  }

  private static long longValue(JsonNode parent, String field, long fallback) {
    JsonNode node = parent.get(field);
    return node != null && node.isNumber() ? node.asLong() : fallback;
  }

  private static int intValue(JsonNode parent, String field, int fallback) {
    JsonNode node = parent.get(field);
    return node != null && node.isNumber() ? node.asInt() : fallback;
  }

  private static byte[] bytes(JsonNode parent, String field) {
    JsonNode node = parent.get(field);
    if (node == null || !node.isBinary()) {
      // CBOR bstr decodes to a binary node; anything else means the client sent the wrong shape.
      return new byte[0];
    }
    try {
      return node.binaryValue();
    } catch (IOException e) {
      return new byte[0];
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> toMap(JsonNode node) {
    if (node == null || !node.isObject()) {
      return new LinkedHashMap<>();
    }
    return cbor.convertValue(node, Map.class);
  }
}
