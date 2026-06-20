package app.vagina.server.realtime;

/**
 * VHRP/1 application errors as a sealed type hierarchy: one concrete exception per wire error code.
 *
 * <p>Instead of tagging a single exception with an enum, the <em>code is the type</em>. Each
 * permitted subclass corresponds to exactly one {@code error.body.code} from {@code
 * 02_vhrp_wire_protocol.md} and bakes that wire string into itself via {@link #wireCode()}. This
 * lets call sites name the precise failure ({@code new ProtocolBadMessage(...)}) and lets handlers
 * pattern-match on it, with no enum to keep in sync. All subclasses live here in one file (judgment
 * 8) rather than as a swarm of sub-10-line files.
 *
 * <h2>Close is contextual, not per-type</h2>
 *
 * <p>Whether the connection closes is deliberately <em>not</em> encoded on these types. Auditing the
 * wire spec shows the only failures that close the socket are those raised while a session is still
 * being bootstrapped from {@code session.open}; once a session exists every failure is reported
 * in-band and the connection is kept. So {@link VhrpEndpoint} decides close-vs-keep by "was a
 * session bound yet?", and these exceptions carry neither a recoverable flag nor a close reason.
 *
 * <p>Unchecked so the codec and adapters can throw from non-blocking paths; the endpoint's {@code
 * @OnError} is the single funnel that turns any of them into an {@code error} frame.
 */
public abstract sealed class VhrpException extends RuntimeException
    permits VhrpException.AuthInvalidJwt,
        VhrpException.SessionUnknownModel,
        VhrpException.ProtocolBadMessage,
        VhrpException.ProtocolUnsupportedMessageType,
        VhrpException.MediaAudioFormatMismatch,
        VhrpException.MediaUnsupportedImage,
        VhrpException.ToolCallNotFound,
        VhrpException.ExtensionUnsupported,
        VhrpException.GenerationInterrupted,
        VhrpException.ResumeNotAvailable,
        VhrpException.StateOutOfSync {

  protected VhrpException(String message) {
    super(message);
  }

  protected VhrpException(String message, Throwable cause) {
    super(message, cause);
  }

  /** The on-wire {@code error.body.code} string for this error kind. */
  public abstract String wireCode();

  // ---------------------------------------------------------------------------
  // One concrete type per wire code. Each bakes in its wire string.
  // ---------------------------------------------------------------------------

  /** {@code auth.invalid_jwt}: session.open token missing, malformed, or unresolvable. */
  public static final class AuthInvalidJwt extends VhrpException {
    public AuthInvalidJwt(String message) {
      super(message);
    }

    @Override
    public String wireCode() {
      return "auth.invalid_jwt";
    }
  }

  /** {@code session.unknown_model}: session.open modelId resolves to no driver. */
  public static final class SessionUnknownModel extends VhrpException {
    public SessionUnknownModel(String message) {
      super(message);
    }

    @Override
    public String wireCode() {
      return "session.unknown_model";
    }
  }

  /** {@code protocol.bad_message}: bytes are not a well-formed VHRP envelope. */
  public static final class ProtocolBadMessage extends VhrpException {
    public ProtocolBadMessage(String message) {
      super(message);
    }

    public ProtocolBadMessage(String message, Throwable cause) {
      super(message, cause);
    }

    @Override
    public String wireCode() {
      return "protocol.bad_message";
    }
  }

  /** {@code protocol.unsupported_message_type}: well-formed envelope with an unknown {@code type}. */
  public static final class ProtocolUnsupportedMessageType extends VhrpException {
    public ProtocolUnsupportedMessageType(String message) {
      super(message);
    }

    @Override
    public String wireCode() {
      return "protocol.unsupported_message_type";
    }
  }

  /** {@code media.audio_format_mismatch}: submitted audio does not match the negotiated format. */
  public static final class MediaAudioFormatMismatch extends VhrpException {
    public MediaAudioFormatMismatch(String message) {
      super(message);
    }

    @Override
    public String wireCode() {
      return "media.audio_format_mismatch";
    }
  }

  /** {@code media.unsupported_image}: submitted image could not be decoded to a supported format. */
  public static final class MediaUnsupportedImage extends VhrpException {
    public MediaUnsupportedImage(String message) {
      super(message);
    }

    @Override
    public String wireCode() {
      return "media.unsupported_image";
    }
  }

  /** {@code tool.call_not_found}: tool.result.submit callId matches no pending call. */
  public static final class ToolCallNotFound extends VhrpException {
    public ToolCallNotFound(String message) {
      super(message);
    }

    @Override
    public String wireCode() {
      return "tool.call_not_found";
    }
  }

  /** {@code extension.unsupported}: session.extension.apply type not supported by the driver. */
  public static final class ExtensionUnsupported extends VhrpException {
    public ExtensionUnsupported(String message) {
      super(message);
    }

    @Override
    public String wireCode() {
      return "extension.unsupported";
    }
  }

  /** {@code generation.interrupted}: a response was interrupted before completion. */
  public static final class GenerationInterrupted extends VhrpException {
    public GenerationInterrupted(String message) {
      super(message);
    }

    @Override
    public String wireCode() {
      return "generation.interrupted";
    }
  }

  /** {@code resume.not_available}: requested session/log is no longer retained. */
  public static final class ResumeNotAvailable extends VhrpException {
    public ResumeNotAvailable(String message) {
      super(message);
    }

    @Override
    public String wireCode() {
      return "resume.not_available";
    }
  }

  /** {@code state.out_of_sync}: local thread revision/streamSeq diverged from canonical. */
  public static final class StateOutOfSync extends VhrpException {
    public StateOutOfSync(String message) {
      super(message);
    }

    @Override
    public String wireCode() {
      return "state.out_of_sync";
    }
  }
}
