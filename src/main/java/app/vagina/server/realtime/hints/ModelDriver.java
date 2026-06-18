










/**
 * Talks to the realtime model vendor on behalf of a session: the technical "how" of vendor I/O.
 *
 * <p>It owns all upstream interaction — opening the session, streaming user input, applying
 * configuration, brokering tool results, interrupting — and nothing else. Conversation logic (the
 * canonical thread, optimistic items, the tool-output barrier, interrupt epochs) stays in the use
 * case.
 *
 * <p>Model output is delivered as <b>data</b>: {@link #openSession} takes a {@code
 * Consumer<RealtimeModelEvent>} that this driver invokes with normalized events. The use case passes
 * its own private handler, so it consumes events rather than implementing a driver-defined callback
 * interface — dependencies and control both flow downward.
 *
 * <p>This is a concrete {@code @ApplicationScoped} service, not an interface: there is exactly one
 * model vendor today, so a provider-abstraction seam would be speculative (YAGNI). The codebase only
 * abstracts a service when multiple implementations already exist (compare the OIDC providers); if a
 * second model vendor ever appears, this is the point to extract an abstraction then. The opaque
 * per-session handle is the nested {@link Session}.
 *
 * <p>The vendor wiring is intentionally not implemented yet; every method throws until the upstream
 * integration is built. The shape — methods, the event sink, and the session handle — is what the
 * use case already depends on.
 */
@ApplicationScoped
public class ModelDriver {

  /** Opaque handle to one opened upstream model session. Implementation-defined contents. */
  public static final class Session {}

  /**
   * Open an upstream session for an authenticated user and begin delivering normalized events.
   *
   * @param userId authenticated user id (from {@link AuthService#authenticateFromJwt})
   * @param modelId selected model
   * @param voice assistant voice, or {@code null}
   * @param instructions system instructions, or {@code null}
   * @param turnMode initial turn handling mode
   * @param tools initial tool catalog
   * @param events sink this driver pushes normalized model events into
   * @return the opaque session handle
   */
  public Session openSession(
      long userId,
      String modelId,
      String voice,
      String instructions,
      RealtimeTurnMode turnMode,
      List<RealtimeToolDefinition> tools,
      Consumer<RealtimeModelEvent> events) {
    throw notYetImplemented("openSession");
  }

  /** Close the upstream session and release its resources. */
  public void closeSession(Session session) {
    throw notYetImplemented("closeSession");
  }

  /** Switch turn handling for subsequent input. */
  public void updateTurnMode(Session session, RealtimeTurnMode mode) {
    throw notYetImplemented("updateTurnMode");
  }

  /** Replace session instructions; affects subsequent responses only. */
  public void updateInstructions(Session session, String instructions) {
    throw notYetImplemented("updateInstructions");
  }

  /** Replace the tool catalog. */
  public void updateTools(Session session, List<RealtimeToolDefinition> tools) {
    throw notYetImplemented("updateTools");
  }

  /**
   * Apply a provider extension.
   *
   * @return {@code true} if applied, {@code false} if unsupported
   */
  public boolean applyExtension(
      Session session, String extensionType, Map<String, Object> payload) {
    throw notYetImplemented("applyExtension");
  }

  /** Stream a chunk of live microphone PCM (voice-activity mode). */
  public void streamUserAudio(Session session, byte[] pcm, long sequence) {
    throw notYetImplemented("streamUserAudio");
  }

  /** Submit one completed user audio turn for the given interrupt generation. */
  public void submitUserAudio(
      Session session,
      long generation,
      String clientItemId,
      byte[] pcm,
      long sampleRate,
      long channels,
      long bitDepth) {
    throw notYetImplemented("submitUserAudio");
  }

  /** Submit one user text turn for the given interrupt generation. */
  public void submitUserText(Session session, long generation, String clientItemId, String text) {
    throw notYetImplemented("submitUserText");
  }

  /** Submit one user image turn for the given interrupt generation. */
  public void submitUserImage(
      Session session, long generation, String clientItemId, byte[] imageBytes) {
    throw notYetImplemented("submitUserImage");
  }

  /** Provide a tool/function result for a prior call. */
  public void provideToolResult(
      Session session,
      String callId,
      String output,
      RealtimeToolDisposition disposition,
      String errorMessage) {
    throw notYetImplemented("provideToolResult");
  }

  /** Resume assistant generation after the pending tool-result queue has drained. */
  public void resumeAfterToolResult(Session session, long generation) {
    throw notYetImplemented("resumeAfterToolResult");
  }

  /** Interrupt the in-flight assistant generation. */
  public void interruptGeneration(Session session) {
    throw notYetImplemented("interruptGeneration");
  }

  private static UnsupportedOperationException notYetImplemented(String operation) {
    return new UnsupportedOperationException(
        "ModelDriver." + operation + " is not implemented yet.");
  }
}
