

/**
 * Normalized model output for a hosted realtime session.
 *
 * <p>This is a distinct domain model from {@link RealtimeEntities}: those describe what the
 * <i>client sends in</i> (decoded user commands), whereas {@code RealtimeModelEvent} describes what
 * the <i>model produces back</i>, already normalized away from any vendor's native event shape. Even
 * where fields overlap, the two are different domain concepts, so this lives in its own entity
 * rather than being folded into the inbound command types.
 *
 * <p>It is pure data with no behavior. The model driver (a service) produces a stream of these, and
 * the use case consumes them; neither vendor wire formats nor VHRP framing appear here. Variants are
 * a sealed set of nested records, mirroring how the codebase groups a closed family of small value
 * types in one aggregate.
 *
 * <p>Most variants carry a {@code generation} (the interrupt epoch in effect when the model produced
 * the event) so the use case can drop output from a superseded turn. The two user-item events do not
 * pertain to an assistant generation; they correct an existing user item by its id.
 */
public sealed interface RealtimeModelEvent
    permits RealtimeModelEvent.UserTranscript,
        RealtimeModelEvent.UserImageStored,
        RealtimeModelEvent.AssistantTurnStarted,
        RealtimeModelEvent.AssistantTextDelta,
        RealtimeModelEvent.AssistantTranscriptDelta,
        RealtimeModelEvent.AssistantAudioChunk,
        RealtimeModelEvent.AssistantAudioDone,
        RealtimeModelEvent.ToolCallRequested,
        RealtimeModelEvent.AssistantTurnCompleted,
        RealtimeModelEvent.ModelProblem {

  /** Transcription of a previously submitted user audio item. */
  record UserTranscript(String userItemId, String transcript) implements RealtimeModelEvent {}

  /** A submitted user image was stored; carries the resolved asset URL and detail hint. */
  record UserImageStored(String userItemId, String imageUrl, String detail)
      implements RealtimeModelEvent {}

  /** A new assistant turn began for the given generation. */
  record AssistantTurnStarted(long generation) implements RealtimeModelEvent {}

  /** Incremental assistant text. */
  record AssistantTextDelta(long generation, String delta) implements RealtimeModelEvent {}

  /** Incremental transcript for the assistant's spoken audio. */
  record AssistantTranscriptDelta(long generation, String delta) implements RealtimeModelEvent {}

  /** A chunk of assistant PCM audio. */
  record AssistantAudioChunk(long generation, byte[] pcm) implements RealtimeModelEvent {}

  /** The assistant audio for the current turn is complete. */
  record AssistantAudioDone(long generation) implements RealtimeModelEvent {}

  /** The model requested a tool/function call. */
  record ToolCallRequested(long generation, String callId, String name, String arguments)
      implements RealtimeModelEvent {}

  /** The assistant turn completed. */
  record AssistantTurnCompleted(long generation) implements RealtimeModelEvent {}

  /** The model raised a problem for the given generation. */
  record ModelProblem(long generation, String code, String message, boolean recoverable)
      implements RealtimeModelEvent {}
}
