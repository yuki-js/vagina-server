package app.vagina.server.realtime;

import java.util.List;
import java.util.Map;

/**
 * The VHRP/1 application message set, as decoded/encoded by {@link VhrpCborCodec}.
 *
 * <p>Each permitted record is one message kind from the wire protocol ({@code
 * client/docs/hosted_realtime/02_vhrp_wire_protocol.md}). On the wire every message is a single
 * CBOR map {@code { type, [messageId], [replyTo], body }}; here the envelope is flattened into
 * typed fields so the rest of the server never touches raw maps. There is no {@code streamSeq} or
 * thread revision on the envelope: recovery is reconnect + full {@code thread.snapshot}, not
 * versioned catch-up.
 *
 * <p>Direction is encoded by the marker sub-interfaces {@link C2S} and {@link S2C}. The endpoint
 * only constructs {@link Error} and pattern-matches {@link SessionOpen}; the codec maps every
 * permitted type, and {@link VhrpSession} dispatches the {@link C2S} set.
 *
 * <p>Binary payloads (PCM, image bytes) are kept as {@code byte[]} so no base64 ever appears
 * server-side; CBOR carries them as {@code bstr}.
 */
public sealed interface VhrpMessage {

  /** The wire {@code type} discriminator, e.g. {@code "session.open"}. */
  String type();

  /** Client-to-server marker. */
  sealed interface C2S extends VhrpMessage
      permits SessionOpen,
          AudioTurnModeSet,
          SessionInstructionsSet,
          LiveAudioChunk,
          TurnAudioSubmit,
          TurnTextSubmit,
          TurnImageSubmit,
          ToolsSet,
          SessionExtensionApply,
          ToolResultSubmit,
          AssistantInterrupt,
          SessionEnd,
          ThreadSyncRequest {}

  /** Server-to-client marker. */
  sealed interface S2C extends VhrpMessage {}

  // ---------------------------------------------------------------------------
  // Shared sub-structures
  // ---------------------------------------------------------------------------

  /**
   * {@code session.open.body.resume}: present only when reconnecting. It merely identifies the
   * retained session to rebind by {@code sessionId}; any catch-up (replay vs snapshot) is driven
   * afterwards by a separate {@code thread.sync.request}, so this no longer carries cursor fields.
   */
  record ResumeRequest(String sessionId) {}

  /** One entry of {@code tools.set.body.tools}; {@code parameters} is a JSON-Schema-shaped map. */
  record ToolSpec(String name, String description, Map<String, Object> parameters) {}

  // ---------------------------------------------------------------------------
  // Client -> Server
  // ---------------------------------------------------------------------------

  /**
   * {@code session.open}: bootstraps (or resumes) a session. {@code token} is the sole
   * application-level credential; fresh opens carry the server-owned {@code speedDialId} authority.
   */
  record SessionOpen(
      String messageId,
      String token,
      String speedDialId,
      String audioTurnMode,
      ResumeRequest resume,
      Map<String, Object> client)
      implements C2S {
    @Override
    public String type() {
      return "session.open";
    }
  }

  /** {@code audio.turn.mode.set}: switches between {@code voice_activity} and {@code manual}. */
  record AudioTurnModeSet(String mode) implements C2S {
    @Override
    public String type() {
      return "audio.turn.mode.set";
    }
  }

  /**
   * {@code session.instructions.set}: mid-session instructions update; {@code instructions}
   * nullable.
   */
  record SessionInstructionsSet(String messageId, String instructions) implements C2S {
    @Override
    public String type() {
      return "session.instructions.set";
    }
  }

  /**
   * {@code live.audio.chunk}: one live mic PCM chunk; valid only in {@code voice_activity} mode.
   */
  record LiveAudioChunk(byte[] pcm, long sequence) implements C2S {
    @Override
    public String type() {
      return "live.audio.chunk";
    }
  }

  /** {@code turn.audio.submit}: one completed manual audio turn. */
  record TurnAudioSubmit(
      String messageId, String clientItemId, byte[] pcm, int sampleRate, int channels, int bitDepth)
      implements C2S {
    @Override
    public String type() {
      return "turn.audio.submit";
    }
  }

  /** {@code turn.text.submit}: one user text turn. */
  record TurnTextSubmit(String messageId, String clientItemId, String text) implements C2S {
    @Override
    public String type() {
      return "turn.text.submit";
    }
  }

  /** {@code turn.image.submit}: one user image turn; MIME is sniffed server-side. */
  record TurnImageSubmit(String messageId, String clientItemId, byte[] imageBytes) implements C2S {
    @Override
    public String type() {
      return "turn.image.submit";
    }
  }

  /** {@code tools.set}: replaces the session tool catalog; empty list disables tools. */
  record ToolsSet(String messageId, List<ToolSpec> tools) implements C2S {
    @Override
    public String type() {
      return "tools.set";
    }
  }

  /** {@code session.extension.apply}: opaque provider-extension update. */
  record SessionExtensionApply(String messageId, String extensionType, Map<String, Object> payload)
      implements C2S {
    @Override
    public String type() {
      return "session.extension.apply";
    }
  }

  /** {@code tool.result.submit}: result of a tool call keyed by {@code callId}. */
  record ToolResultSubmit(
      String messageId,
      String clientItemId,
      String callId,
      String output,
      String disposition,
      String errorMessage)
      implements C2S {
    @Override
    public String type() {
      return "tool.result.submit";
    }
  }

  /** {@code assistant.interrupt}: stop the current generation. */
  record AssistantInterrupt(String reason) implements C2S {
    @Override
    public String type() {
      return "assistant.interrupt";
    }
  }

  /** {@code session.end}: explicitly terminates the hosted session. One-way; no ack. */
  record SessionEnd() implements C2S {
    @Override
    public String type() {
      return "session.end";
    }
  }

  /**
   * {@code thread.sync.request}: asks the server for the current full thread state. In the
   * snapshot-only recovery model it carries no cursor or revision — the reply is always a fresh
   * full {@code thread.snapshot}. {@code reason} is advisory only.
   */
  record ThreadSyncRequest(String messageId, String reason) implements C2S {
    @Override
    public String type() {
      return "thread.sync.request";
    }
  }

  // ---------------------------------------------------------------------------
  // Server -> Client
  // ---------------------------------------------------------------------------

  /** {@code session.ready}: reply to a new {@code session.open}. */
  record SessionReady(
      String replyTo,
      String sessionId,
      String threadId,
      String conversationId,
      List<String> capabilityExtensions)
      implements S2C {
    @Override
    public String type() {
      return "session.ready";
    }
  }

  /**
   * {@code session.resumed}: reply to a {@code session.open} that carried {@code resume}. It only
   * announces the rebind; the client then asks for a fresh full {@code thread.snapshot} via {@code
   * thread.sync.request}. No revision/strategy is carried because the single recovery path is a
   * full snapshot, not a versioned catch-up.
   */
  record SessionResumed(String replyTo, String sessionId, String threadId, String conversationId)
      implements S2C {
    @Override
    public String type() {
      return "session.resumed";
    }
  }

  /** {@code ack}: generic success reply correlated by {@code replyTo}. */
  record Ack(String replyTo, boolean accepted, String clientItemId, boolean applied)
      implements S2C {
    @Override
    public String type() {
      return "ack";
    }
  }

  /**
   * {@code thread.snapshot}: authoritative full thread state. Always the current full projection;
   * the client replaces its local thread wholesale. It is the single recovery primitive — there is
   * no revision or sequence to validate it against.
   */
  record ThreadSnapshot(String threadId, String conversationId, List<Map<String, Object>> items)
      implements S2C {
    @Override
    public String type() {
      return "thread.snapshot";
    }
  }

  /**
   * {@code thread.patch}: a live op list applied to the client's projected thread. Fire-and-forget:
   * it carries no revision/sequence, and a delivery gap is recovered by reconnect + a fresh full
   * {@code thread.snapshot}, never by versioned patch validation. Ops kept opaque to the transport.
   */
  record ThreadPatch(List<Map<String, Object>> ops) implements S2C {
    @Override
    public String type() {
      return "thread.patch";
    }
  }

  /** {@code assistant.audio.chunk}: one assistant PCM chunk for an audio part. */
  record AssistantAudioChunk(String itemId, int contentIndex, byte[] pcm) implements S2C {
    @Override
    public String type() {
      return "assistant.audio.chunk";
    }
  }

  /** {@code assistant.audio.done}: assistant audio boundary for an item/part. */
  record AssistantAudioDone(String itemId, int contentIndex) implements S2C {
    @Override
    public String type() {
      return "assistant.audio.done";
    }
  }

  /** {@code vad.state}: server-side VAD speaking state. */
  record VadState(boolean isSpeaking) implements S2C {
    @Override
    public String type() {
      return "vad.state";
    }
  }

  /**
   * {@code error}: application error frame. The {@code recoverable} wire field tells the client
   * whether the connection survives; the server fills it from context (a bootstrap failure is not
   * recoverable and is followed by close, an established-session failure is recoverable and kept).
   * {@code code} is the wire string from {@link VhrpException#wireCode()}.
   */
  record Error(String replyTo, String code, String message, boolean recoverable) implements S2C {
    @Override
    public String type() {
      return "error";
    }

    /**
     * One-off, connection-level error not tied to a particular request: {@code replyTo} is absent
     * because the endpoint emits this around bootstrap. {@code code} is a {@link
     * VhrpException#wireCode()} string and {@code recoverable} is passed by the caller from
     * context.
     */
    public static Error of(String code, String message, boolean recoverable) {
      return new Error(null, code, message, recoverable);
    }
  }
}
