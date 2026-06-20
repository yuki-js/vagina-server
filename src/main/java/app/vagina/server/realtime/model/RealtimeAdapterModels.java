package app.vagina.server.realtime.model;

import java.util.List;
import java.util.Map;

/**
 * Adapter-facing shared value types, aggregated to avoid a swarm of tiny files (judgment 8). This
 * mirrors the Dart {@code realtime_adapter_models.dart} plus the few enums that live alongside the
 * Dart {@code RealtimeAdapter}, kept VHRP-agnostic so the {@code oai/} translation body never sees a
 * wire type.
 */
public final class RealtimeAdapterModels {

  private RealtimeAdapterModels() {}

  /** Connection lifecycle phases, mirroring the Dart {@code RealtimeAdapterConnectionPhase}. */
  public enum ConnectionPhase {
    IDLE,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
    DISCONNECTED,
    FAILED
  }

  /** Current connection lifecycle state. {@code message}/{@code cause} are optional diagnostics. */
  public record ConnectionState(ConnectionPhase phase, String message, Object cause) {

    public static ConnectionState idle() {
      return new ConnectionState(ConnectionPhase.IDLE, null, null);
    }

    public static ConnectionState connecting() {
      return new ConnectionState(ConnectionPhase.CONNECTING, null, null);
    }

    public static ConnectionState connected() {
      return new ConnectionState(ConnectionPhase.CONNECTED, null, null);
    }

    public static ConnectionState disconnected(String message) {
      return new ConnectionState(ConnectionPhase.DISCONNECTED, message, null);
    }

    public static ConnectionState failed(String message, Object cause) {
      return new ConnectionState(ConnectionPhase.FAILED, message, cause);
    }

    public boolean isConnected() {
      return phase == ConnectionPhase.CONNECTED;
    }
  }

  /** Protocol/transport error surfaced on the adapter's error stream. */
  public record Error(String code, String message, Object cause) {}

  /**
   * Turn-taking mode. Mirrors the Dart top-level {@code RealtimeAudioTurnMode}; {@link
   * #fromWire(String)} maps the VHRP {@code audio.turn.mode.set.mode} token at the session boundary.
   */
  public enum AudioTurnMode {
    VOICE_ACTIVITY,
    MANUAL;

    public static AudioTurnMode fromWire(String wire) {
      return "manual".equals(wire) ? MANUAL : VOICE_ACTIVITY;
    }
  }

  /** Tool-output disposition; {@link #fromWire(String)} maps the VHRP {@code disposition} token. */
  public enum ToolOutputDisposition {
    SUCCESS,
    ERROR;

    public static ToolOutputDisposition fromWire(String wire) {
      return "error".equals(wire) ? ERROR : SUCCESS;
    }
  }

  /**
   * Vendor-neutral tool definition handed to {@code registerTools}. The session maps each wire
   * {@code tools.set} entry into one of these so the adapter never depends on a VHRP type. {@code
   * parameters} is a JSON-Schema-shaped map.
   */
  public record ToolDefinition(String name, String description, Map<String, Object> parameters) {}

  /**
   * One unit of assistant PCM output, carrying the item/part it belongs to so the session can frame
   * it as a VHRP {@code assistant.audio.chunk}.
   *
   * <p>Unlike the Dart {@code assistantAudioStream} (raw {@code Uint8List}), the server stream is
   * enriched with {@code itemId}/{@code contentIndex} because the wire frames audio per item/part;
   * {@code pcm} is empty for an end-of-audio boundary emitted on the completion stream.
   */
  public record AssistantAudioFrame(String itemId, int contentIndex, byte[] pcm) {}

  /**
   * One flush of thread mutations (judgment 4), as a bare op list.
   *
   * <p>The Dart {@code OaiRealtimeAdapter} emits the <em>whole</em> thread at each {@code
   * _emitThreadUpdate()}; the server instead emits a delta. Each flush of buffered ops becomes one
   * VHRP {@code thread.patch}, which the client applies as a live mutation of its projected thread.
   *
   * <p>There is no revision/sequence on a patch: the single recovery path for any delivery gap is
   * reconnect + a fresh full {@code thread.snapshot}, so a patch needs no version to be validated
   * against. {@code ops} are kept as generic maps so the wire shape stays in the codec, not the
   * adapter.
   */
  public record ThreadPatchOps(List<Map<String, Object>> ops) {}
}
