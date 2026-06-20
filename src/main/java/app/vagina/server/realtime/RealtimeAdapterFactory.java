package app.vagina.server.realtime;

/**
 * Resolves a {@code session.open.body.modelId} to a {@link RealtimeAdapter} instance (judgment 7).
 *
 * <p>This is the server-side mirror of the Dart {@code RealtimeAdapterFactory}: the same warp
 * interface, selected by id. The crucial difference is that the client sends only a {@code modelId}
 * and never learns which vendor backs it, so provider choice stays entirely server-side. A new
 * provider is added by implementing {@link RealtimeAdapter} and registering it here; {@link
 * VhrpEndpoint} and the wire are untouched.
 *
 * <p>Each call returns a fresh adapter dedicated to one session (one downstream vendor connection),
 * mirroring how the Dart {@code RealtimeAdapterFactory.create} yields a new adapter per call.
 */
public interface RealtimeAdapterFactory {

  /**
   * Creates a new adapter for {@code modelId}.
   *
   * @throws UnknownModelException if no adapter is configured for {@code modelId}
   */
  RealtimeAdapter create(String modelId) throws UnknownModelException;

  /**
   * Signals that a {@code modelId} maps to no configured adapter. Nested here (judgment 8) rather
   * than living as a sub-15-line standalone file; {@link VhrpSessionRegistry} turns it into a fatal
   * {@code error(session.unknown_model)}.
   */
  final class UnknownModelException extends Exception {

    private final String modelId;

    public UnknownModelException(String modelId) {
      super("No realtime driver configured for modelId: " + modelId);
      this.modelId = modelId;
    }

    public String modelId() {
      return modelId;
    }
  }
}
