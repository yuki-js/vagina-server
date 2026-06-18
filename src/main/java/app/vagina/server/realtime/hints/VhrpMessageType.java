

/**
 * VHRP/1 wire message kinds.
 *
 * <p>Non-domain routing vocabulary for the hosted realtime protocol, so it lives in {@code
 * support/}. It maps the on-wire {@code type} text to a stable Java constant, letting the WebSocket
 * endpoint dispatch frames without interpreting payloads. Both directions are listed because the
 * support layer owns the whole wire vocabulary: client-to-server kinds are matched by the inbound
 * router, while server-to-client kinds are produced by the framing channel.
 */
public enum VhrpMessageType {
  // --- client -> server ---
  SESSION_OPEN("session.open"),
  AUDIO_TURN_MODE_SET("audio.turn.mode.set"),
  SESSION_INSTRUCTIONS_SET("session.instructions.set"),
  LIVE_AUDIO_CHUNK("live.audio.chunk"),
  TURN_AUDIO_SUBMIT("turn.audio.submit"),
  TURN_TEXT_SUBMIT("turn.text.submit"),
  TURN_IMAGE_SUBMIT("turn.image.submit"),
  TOOLS_SET("tools.set"),
  SESSION_EXTENSION_APPLY("session.extension.apply"),
  TOOL_RESULT_SUBMIT("tool.result.submit"),
  ASSISTANT_INTERRUPT("assistant.interrupt"),
  THREAD_SYNC_REQUEST("thread.sync.request"),

  // --- server -> client ---
  SESSION_READY("session.ready"),
  SESSION_RESUMED("session.resumed"),
  ACK("ack"),
  THREAD_SNAPSHOT("thread.snapshot"),
  THREAD_PATCH("thread.patch"),
  ASSISTANT_AUDIO_CHUNK("assistant.audio.chunk"),
  ASSISTANT_AUDIO_DONE("assistant.audio.done"),
  VAD_STATE("vad.state"),
  ERROR("error"),

  // --- sentinel ---
  UNKNOWN("");

  private final String wireValue;

  VhrpMessageType(String wireValue) {
    this.wireValue = wireValue;
  }

  /** The on-wire {@code type} text for this kind. */
  public String wireValue() {
    return wireValue;
  }

  /**
   * Resolves a wire {@code type} string to a known kind.
   *
   * @return the matching kind, or {@link #UNKNOWN} when null, empty, or unrecognized.
   */
  public static VhrpMessageType fromWire(String type) {
    if (type == null || type.isEmpty()) {
      return UNKNOWN;
    }
    for (VhrpMessageType candidate : values()) {
      if (candidate != UNKNOWN && candidate.wireValue.equals(type)) {
        return candidate;
      }
    }
    return UNKNOWN;
  }
}
