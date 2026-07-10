package app.vagina.server.realtime;

/**
 * Resolves a server-owned voice-agent id to a {@link RealtimeAdapter} instance (judgment 7).
 *
 * <p>This is the server-side mirror of the Dart {@code RealtimeAdapterFactory}: the same warp
 * interface, selected by id. The crucial difference is that the client sends only a Speed Dial id;
 * the resolved voice-agent id and backing vendor stay entirely server-side. A new provider is added
 * by implementing {@link RealtimeAdapter} and registering it here; {@link VhrpEndpoint} and the
 * wire are untouched.
 *
 * <p>Each call returns a fresh adapter dedicated to one session (one downstream vendor connection),
 * mirroring how the Dart {@code RealtimeAdapterFactory.create} yields a new adapter per call.
 */
public interface RealtimeAdapterFactory {

  /**
   * Creates a new adapter for {@code voiceAgentId}.
   *
   * @throws UnknownModelException if no adapter is configured for {@code voiceAgentId}
   */
  RealtimeAdapter create(String voiceAgentId) throws UnknownModelException;

  /**
   * Signals that a voice-agent id maps to no configured adapter. Nested here (judgment 8) rather
   * than living as a sub-15-line standalone file; {@link VhrpSessionRegistry} turns it into a fatal
   * {@code error(session.unknown_model)}.
   */
  final class UnknownModelException extends Exception {

    private final String voiceAgentId;

    public UnknownModelException(String voiceAgentId) {
      super("No realtime driver configured for voice agent id: " + voiceAgentId);
      this.voiceAgentId = voiceAgentId;
    }

    public String modelId() {
      return voiceAgentId;
    }
  }
}
