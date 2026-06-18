



/**
 * Domain entities for the hosted realtime (VHRP/1) conversation, grouped into a single namespace.
 *
 * <p>These are the protocol-agnostic, data-only values the realtime usecase works with. They carry
 * what a conversation means — a session's configuration, a user turn, a tool definition, a tool
 * result — and deliberately know nothing about CBOR, WebSocket framing, {@code streamSeq}, or any
 * wire detail. Wire-to-domain translation happens in {@code support} (the inbound decoder); by the
 * time these objects exist, the transport is already behind us.
 *
 * <p>They are intentionally collected as nested types under {@code RealtimeEntities} rather than a
 * {@code entity.realtime} subpackage, so callers reference them as {@code
 * RealtimeEntities.RealtimeXxx} from the flat {@code entity} package. Each type is a {@code record}
 * because entities here are pure data (fields only), consistent with how this codebase models small
 * immutable value objects.
 */
public final class RealtimeEntities {

  private RealtimeEntities() {}

  /** How user audio turns are delimited for a session. */
  public enum RealtimeTurnMode {
    /** Server-side voice-activity detection drives turn boundaries. */
    VOICE_ACTIVITY,
    /** The client submits one completed audio turn at a time. */
    MANUAL
  }

  /** Whether a tool result represents success or an error. */
  public enum RealtimeToolDisposition {
    SUCCESS,
    ERROR;

    /**
     * Maps the wire disposition token to the domain value.
     *
     * @param value {@code "success"}, {@code "error"}, or {@code null}
     * @return the matching disposition; defaults to {@link #SUCCESS} when {@code null} or absent
     */
    public static RealtimeToolDisposition fromValue(String value) {
      if (value == null) {
        return SUCCESS;
      }
      return switch (value) {
        case "success" -> SUCCESS;
        case "error" -> ERROR;
        default -> throw new IllegalStateException("Unknown tool disposition: " + value);
      };
    }
  }

  /**
   * Session-level configuration for a realtime conversation.
   *
   * @param token the application JWT the usecase authenticates the session with
   * @param modelId the selected model
   * @param voice optional assistant voice; {@code null} when unset
   * @param instructions optional system instructions; {@code null} when unset
   * @param turnMode optional initial turn mode; {@code null} when unset
   */
  public record RealtimeSessionConfig(
      String token,
      String modelId,
      String voice,
      String instructions,
      RealtimeTurnMode turnMode) {}

  /**
   * One chunk of streaming microphone PCM.
   *
   * @param pcm 24kHz mono 16-bit little-endian samples
   * @param sequence monotonically increasing chunk ordinal
   */
  public record RealtimeLiveAudioChunk(byte[] pcm, long sequence) {}

  /**
   * One completed user audio turn.
   *
   * @param clientItemId client-assigned thread item id
   * @param pcm the captured audio samples
   * @param sampleRate samples per second
   * @param channels channel count
   * @param bitDepth bits per sample
   */
  public record RealtimeAudioTurn(
      String clientItemId, byte[] pcm, long sampleRate, long channels, long bitDepth) {}

  /**
   * One user text turn.
   *
   * @param clientItemId client-assigned thread item id
   * @param text the message text
   */
  public record RealtimeTextTurn(String clientItemId, String text) {}

  /**
   * One user image turn.
   *
   * @param clientItemId client-assigned thread item id
   * @param imageBytes raw image bytes; the backend sniffs the content type
   */
  public record RealtimeImageTurn(String clientItemId, byte[] imageBytes) {}

  /**
   * A tool the model may call during the session.
   *
   * @param name tool name
   * @param description optional human description; {@code null} when unset
   * @param parameters JSON-Schema-compatible parameter definition
   */
  public record RealtimeToolDefinition(
      String name, String description, Map<String, Object> parameters) {}

  /**
   * A session-scoped provider-extension request.
   *
   * @param extensionType opaque extension identifier
   * @param payload extension-specific values
   */
  public record RealtimeExtensionRequest(String extensionType, Map<String, Object> payload) {}

  /**
   * The result of a function/tool call the model requested.
   *
   * @param clientItemId client-assigned output thread item id
   * @param callId provider-assigned correlation id from the originating call
   * @param output opaque UTF-8 output string
   * @param disposition optional success/error disposition; {@code null} when unset
   * @param errorMessage optional error detail; {@code null} when unset
   */
  public record RealtimeToolResult(
      String clientItemId,
      String callId,
      String output,
      RealtimeToolDisposition disposition,
      String errorMessage) {}

  /**
   * A request to interrupt the current assistant response.
   *
   * @param reason optional barge-in reason; {@code null} when unset
   */
  public record RealtimeInterruptReason(String reason) {}
}
