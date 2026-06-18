



















/**
 * Translates decoded VHRP/1 envelopes into the values the rest of the server consumes.
 *
 * <p>This is a non-domain, wire-coupled component, so it lives in {@code support/} alongside {@link
 * Util} and {@link ErrorResponse}. Its single responsibility is anti-corruption: read the
 * wire-shaped {@code body} map of an inbound {@link VhrpEnvelope} and produce typed values, so that
 * no raw {@code Map}, CBOR detail, or {@code streamSeq} ever leaks past this boundary.
 *
 * <p>Generic, protocol-agnostic field extraction (string/long/bytes/map) is delegated to {@link
 * Util}, beside its existing {@code JsonNode} field helpers; this class keeps only the parts that
 * actually know VHRP ({@link #readTurnMode}, {@link #requireBody}, the per-message field wiring).
 * Following {@link Util}'s precedent, malformed input throws plain {@link IllegalStateException};
 * there is no bespoke decode-exception type, and the endpoint already funnels any {@link
 * RuntimeException} here into its malformed-frame path.
 *
 * <p>Two kinds of output are produced, by intent:
 *
 * <ul>
 *   <li><b>domain</b> values (in {@code entity/}) for everything the conversation cares about
 *       (session config, turns, tools, tool results, …). These are what the usecase receives.
 *   <li><b>support</b> values for VHRP stream-continuity concerns ({@link VhrpResumeRequest}, {@link
 *       VhrpSyncRequest}). These never reach the domain usecase; the resource hands them to the
 *       framing channel instead.
 * </ul>
 *
 * <p><b>Why this is an {@code @Inject} CDI bean and not a class of {@code static} helpers:</b> the
 * decoder enforces VHRP/1 size limits that are configuration, injected here via {@link
 * ConfigProperty}. A {@code static} API could not receive that configuration without a manual
 * global {@code ConfigProvider} lookup (hidden global state, awkward to test). As a bean it is also
 * mockable in endpoint tests and matches how this codebase models technical boundary components.
 * {@link Util} is {@code static} only because it is pure and dependency-free; this decoder has
 * configuration dependencies and a substitutable role, so it must be a managed bean.
 *
 * <p>References the uncreated {@code entity/} domain types and {@code support/} protocol types;
 * those are the next compile-fail boundaries, by the one-layer-at-a-time plan.
 */
@ApplicationScoped
public class VhrpInboundDecoder {

  // TODO(VHRP): these vhrp.limits.* keys exist only as @ConfigProperty defaults; they are not
  // declared/documented in application.properties, so operators can't discover or tune them. Add
  // them (with comments) to application.properties.
  @ConfigProperty(name = "vhrp.limits.text-bytes", defaultValue = "16384")
  int maxTextBytes;

  @ConfigProperty(name = "vhrp.limits.image-bytes", defaultValue = "8388608")
  int maxImageBytes;

  @ConfigProperty(name = "vhrp.limits.audio-turn-bytes", defaultValue = "2097152")
  int maxAudioTurnBytes;

  @ConfigProperty(name = "vhrp.limits.live-chunk-bytes", defaultValue = "16384")
  int maxLiveChunkBytes;

  @ConfigProperty(name = "vhrp.limits.tools", defaultValue = "64")
  int maxTools;

  // ---------------------------------------------------------------------------
  // Domain outputs (consumed by the usecase)
  // ---------------------------------------------------------------------------

  /** {@code session.open}: model/voice/instructions/turn-mode plus the JWT the usecase verifies. */
  public RealtimeSessionConfig readSessionConfig(VhrpEnvelope envelope) {
    Map<String, Object> body = requireBody(envelope);
    return new RealtimeSessionConfig(
        Util.requireString(body, "token"),
        Util.requireString(body, "modelId"),
        Util.optionalString(body, "voice"),
        Util.optionalString(body, "instructions"),
        readTurnMode(Util.optionalString(body, "audioTurnMode")));
  }

  /** {@code audio.turn.mode.set}: VAD vs manual. */
  public RealtimeTurnMode readAudioTurnMode(VhrpEnvelope envelope) {
    Map<String, Object> body = requireBody(envelope);
    return readTurnMode(Util.requireString(body, "mode"));
  }

  /** {@code session.instructions.set}: nullable instructions text. */
  public String readInstructions(VhrpEnvelope envelope) {
    return Util.optionalString(requireBody(envelope), "instructions");
  }

  /** {@code live.audio.chunk}: streaming microphone PCM with its ordering sequence. */
  public RealtimeLiveAudioChunk readLiveAudioChunk(VhrpEnvelope envelope) {
    Map<String, Object> body = requireBody(envelope);
    byte[] pcm = Util.requireBytes(body, "pcm", maxLiveChunkBytes);
    return new RealtimeLiveAudioChunk(pcm, Util.requireLong(body, "sequence"));
  }

  /** {@code turn.audio.submit}: one completed user audio turn. */
  public RealtimeAudioTurn readAudioTurn(VhrpEnvelope envelope) {
    Map<String, Object> body = requireBody(envelope);
    return new RealtimeAudioTurn(
        Util.requireString(body, "clientItemId"),
        Util.requireBytes(body, "pcm", maxAudioTurnBytes),
        Util.requireLong(body, "sampleRate"),
        Util.requireLong(body, "channels"),
        Util.requireLong(body, "bitDepth"));
  }

  /** {@code turn.text.submit}: one user text turn. */
  public RealtimeTextTurn readTextTurn(VhrpEnvelope envelope) {
    Map<String, Object> body = requireBody(envelope);
    String clientItemId = Util.requireString(body, "clientItemId");
    String text = Util.requireString(body, "text");
    if (text.getBytes(StandardCharsets.UTF_8).length > maxTextBytes) {
      throw new IllegalStateException("turn.text.submit.text exceeds limit");
    }
    return new RealtimeTextTurn(clientItemId, text);
  }

  /** {@code turn.image.submit}: one user image turn (raw bytes; backend sniffs the type). */
  public RealtimeImageTurn readImageTurn(VhrpEnvelope envelope) {
    Map<String, Object> body = requireBody(envelope);
    return new RealtimeImageTurn(
        Util.requireString(body, "clientItemId"),
        Util.requireBytes(body, "imageBytes", maxImageBytes));
  }

  /** {@code tools.set}: the session tool catalog (possibly empty). */
  public List<RealtimeToolDefinition> readToolCatalog(VhrpEnvelope envelope) {
    Map<String, Object> body = requireBody(envelope);
    Object raw = body.get("tools");
    if (!(raw instanceof List<?> tools)) {
      throw new IllegalStateException("tools.set.tools must be an array");
    }
    if (tools.size() > maxTools) {
      throw new IllegalStateException("tools.set.tools exceeds limit");
    }
    List<RealtimeToolDefinition> result = new ArrayList<>(tools.size());
    for (Object element : tools) {
      if (!(element instanceof Map<?, ?> tool)) {
        throw new IllegalStateException("tools.set.tools entry must be a map");
      }
      result.add(
          new RealtimeToolDefinition(
              Util.requireString(tool, "name"),
              Util.optionalString(tool, "description"),
              Util.asStringKeyedMap(tool.get("parameters"))));
    }
    return result;
  }

  /** {@code session.extension.apply}: opaque extension type plus its payload map. */
  public RealtimeExtensionRequest readExtension(VhrpEnvelope envelope) {
    Map<String, Object> body = requireBody(envelope);
    return new RealtimeExtensionRequest(
        Util.requireString(body, "extensionType"), Util.asStringKeyedMap(body.get("payload")));
  }

  /** {@code tool.result.submit}: result for a prior function call, keyed by {@code callId}. */
  public RealtimeToolResult readToolResult(VhrpEnvelope envelope) {
    Map<String, Object> body = requireBody(envelope);
    return new RealtimeToolResult(
        Util.requireString(body, "clientItemId"),
        Util.requireString(body, "callId"),
        Util.requireString(body, "output"),
        RealtimeToolDisposition.fromValue(Util.optionalString(body, "disposition")),
        Util.optionalString(body, "errorMessage"));
  }

  /** {@code assistant.interrupt}: barge-in reason. */
  public RealtimeInterruptReason readInterruptReason(VhrpEnvelope envelope) {
    Map<String, Object> body = optionalBody(envelope);
    return new RealtimeInterruptReason(body == null ? null : Util.optionalString(body, "reason"));
  }

  // ---------------------------------------------------------------------------
  // Protocol-state outputs (consumed by the support framing channel, not the usecase)
  // ---------------------------------------------------------------------------

  /** {@code session.open.resume}: optional resume handle; {@code null} for a fresh session. */
  public VhrpEnvelope.VhrpResumeRequest readResumeRequest(VhrpEnvelope envelope) {
    Map<String, Object> body = requireBody(envelope);
    Object raw = body.get("resume");
    if (raw == null) {
      return null;
    }
    if (!(raw instanceof Map<?, ?> resume)) {
      throw new IllegalStateException("session.open.resume must be a map");
    }
    return new VhrpEnvelope.VhrpResumeRequest(
        Util.requireString(resume, "sessionId"),
        Util.requireLong(resume, "afterStreamSeq"),
        Util.requireLong(resume, "knownThreadRevision"),
        Util.optionalString(resume, "mode"));
  }

  /** {@code thread.sync.request}: gap/desync recovery request. */
  public VhrpEnvelope.VhrpSyncRequest readSyncRequest(VhrpEnvelope envelope) {
    Map<String, Object> body = requireBody(envelope);
    return new VhrpEnvelope.VhrpSyncRequest(
        Util.requireLong(body, "afterStreamSeq"),
        Util.requireLong(body, "knownThreadRevision"),
        Util.optionalString(body, "mode"),
        Util.optionalString(body, "reason"));
  }

  // ---------------------------------------------------------------------------
  // VHRP-specific helpers
  // ---------------------------------------------------------------------------

  private RealtimeTurnMode readTurnMode(String wire) {
    if (wire == null) {
      return null;
    }
    return switch (wire) {
      case "voice_activity" -> RealtimeTurnMode.VOICE_ACTIVITY;
      case "manual" -> RealtimeTurnMode.MANUAL;
      default -> throw new IllegalStateException("Unknown audio turn mode: " + wire);
    };
  }

  private Map<String, Object> requireBody(VhrpEnvelope envelope) {
    Map<String, Object> body = optionalBody(envelope);
    if (body == null) {
      throw new IllegalStateException("Missing body for " + envelope.getType());
    }
    return body;
  }

  private Map<String, Object> optionalBody(VhrpEnvelope envelope) {
    return envelope.getBody();
  }
}
