package app.vagina.server.realtime;

import app.vagina.server.realtime.model.RealtimeAdapterModels;
import app.vagina.server.realtime.model.RealtimeThread;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The warp interface: the server-side mirror of the Dart {@code RealtimeAdapter}.
 *
 * <p>This is the one contract the VHRP layer drives. Implementations (the {@code oai/} translation
 * body and any future vendor) live behind it and know nothing about VHRP; conversely this interface
 * uses only {@code model/} types and Mutiny, never a {@code VhrpMessage}. {@link VhrpSession}
 * translates wire ⇄ model at the boundary so the warp stays vendor- and protocol-clean.
 *
 * <p>Async primitives follow judgment 4: Dart {@code Future} → {@link Uni}, Dart {@code Stream} →
 * {@link Multi}.
 *
 * <h2>Beyond the Dart contract</h2>
 *
 * The Dart adapter projects a thread and emits whole-thread updates; the server must instead emit
 * VHRP {@code thread.patch} deltas. So this interface adds projection accessors ({@link
 * #conversationId()}, {@link #threadRevision()}, {@link #supportedExtensions()}) and a delta stream
 * ({@link #threadPatches()}) the session frames as patches, while {@link #thread()} remains the
 * canonical snapshot source. Live mic input is a single-chunk push ({@link #pushLiveAudioChunk},
 * judgment 6) rather than a bound stream.
 */
public interface RealtimeAdapter {

  // ---------------------------------------------------------------------------
  // Lifecycle
  // ---------------------------------------------------------------------------

  /**
   * Opens the downstream vendor connection and applies session-level knobs. {@code voice} and
   * {@code instructions} are nullable; everything else (audio format, VAD, transcription) is the
   * adapter's own default.
   */
  Uni<Void> connect(String voice, String instructions);

  /** Gracefully closes the downstream connection and releases resources. Idempotent. */
  Uni<Void> dispose();

  // ---------------------------------------------------------------------------
  // Connection + error observation
  // ---------------------------------------------------------------------------

  RealtimeAdapterModels.ConnectionState connectionState();

  Multi<RealtimeAdapterModels.ConnectionState> connectionStateUpdates();

  Multi<RealtimeAdapterModels.Error> errors();

  // ---------------------------------------------------------------------------
  // Thread projection (server-side; feeds VHRP thread.snapshot / thread.patch)
  // ---------------------------------------------------------------------------

  /** Canonical thread; the session reads this to build a {@code thread.snapshot}. */
  RealtimeThread thread();

  /**
   * Emits one batch of patch ops per flush point (judgment 5: ops are appended at mutation sites and
   * flushed where the Dart code emitted a whole-thread update). The session wraps each batch in a
   * VHRP {@code thread.patch} with the next streamSeq and a base→target revision step. Ops are kept
   * as generic maps so the wire-shape stays in the session/codec, not in the adapter.
   */
  Multi<List<Map<String, Object>>> threadPatches();

  /** Current canonical thread revision; reported in {@code session.resumed}. */
  long threadRevision();

  /** Conversation id once known; reported in {@code session.ready}/{@code session.resumed}. */
  String conversationId();

  /** Extension keys this driver supports; advertised in {@code session.ready.capabilities}. */
  Set<String> supportedExtensions();

  // ---------------------------------------------------------------------------
  // Audio
  // ---------------------------------------------------------------------------

  /** Pushes one live mic PCM chunk; valid only in voice-activity mode (judgment 6). */
  void pushLiveAudioChunk(byte[] pcm);

  Uni<Void> setAudioTurnMode(RealtimeAdapterModels.AudioTurnMode mode);

  /** Assistant PCM output, framed per item/part so the session can emit {@code assistant.audio.chunk}. */
  Multi<RealtimeAdapterModels.AssistantAudioFrame> assistantAudioStream();

  /** Fires when the current assistant audio response has no more chunks (per item/part boundary). */
  Multi<RealtimeAdapterModels.AssistantAudioFrame> assistantAudioCompleted();

  boolean isUserSpeaking();

  Multi<Boolean> isUserSpeakingUpdates();

  // ---------------------------------------------------------------------------
  // Tool configuration / session mutation
  // ---------------------------------------------------------------------------

  Uni<Void> registerTools(List<RealtimeAdapterModels.ToolDefinition> tools);

  Uni<Void> setInstructions(String instructions);

  /** Applies an opaque provider extension; resolves {@code false} when unsupported by this driver. */
  Uni<Boolean> applyProviderExtension(String extensionType, Map<String, Object> payload);

  // ---------------------------------------------------------------------------
  // User content (each implies "and generate a response"); returns the local item id
  // ---------------------------------------------------------------------------

  Uni<String> sendAudioOneShot(byte[] audioBytes);

  Uni<String> sendText(String text);

  Uni<String> sendImage(byte[] imageBytes);

  Uni<String> sendFunctionOutput(
      String callId,
      String output,
      RealtimeAdapterModels.ToolOutputDisposition disposition,
      String errorMessage);

  /** Marks pending/running function calls locally cancelled (projection concern; no wire). */
  void cancelFunctionCalls(Set<String> itemIds, Set<String> callIds);

  // ---------------------------------------------------------------------------
  // Response control
  // ---------------------------------------------------------------------------

  Uni<Void> interrupt();
}
