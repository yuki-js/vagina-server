




/**
 * VHRP/1 common envelope: one decoded CBOR map = one application message.
 *
 * <p>Non-domain wire representation, so it lives in {@code support/} beside {@link ErrorResponse}
 * and the codec. It mirrors the wire fields exactly ({@code type}, {@code messageId}, {@code
 * streamSeq}, {@code replyTo}, {@code body}) and carries no behavior. The {@code body} is left as an
 * opaque map on purpose: turning it into typed values is {@link VhrpInboundDecoder}'s job, and the
 * domain layers never see this class.
 *
 * <p>The two stream-continuity request shapes ({@link VhrpResumeRequest}, {@link VhrpSyncRequest})
 * are nested here rather than living in their own one-line files: they are tiny, they are extracted
 * from envelope bodies, and they are pure wire/protocol concerns — exactly this envelope's domain.
 * Keeping them nested follows the same "no trivially small standalone file" rule used for {@code
 * RealtimeEntities}.
 */
@RegisterForReflection
public final class VhrpEnvelope {

  private String type;
  private String messageId;
  private Long streamSeq;
  private String replyTo;
  private Map<String, Object> body;

  public VhrpEnvelope() {}

  public VhrpEnvelope(
      String type, String messageId, Long streamSeq, String replyTo, Map<String, Object> body) {
    this.type = type;
    this.messageId = messageId;
    this.streamSeq = streamSeq;
    this.replyTo = replyTo;
    this.body = body;
  }

  /** Wire message kind text. Required on every message. */
  public String getType() {
    return type;
  }

  public void setType(String type) {
    this.type = type;
  }

  /** Request/response correlation key; present only when correlation is required. */
  public String getMessageId() {
    return messageId;
  }

  public void setMessageId(String messageId) {
    this.messageId = messageId;
  }

  /** Server-to-client monotonic send sequence; present only on stateful server messages. */
  public Long getStreamSeq() {
    return streamSeq;
  }

  public void setStreamSeq(Long streamSeq) {
    this.streamSeq = streamSeq;
  }

  /** For direct responses: the {@code messageId} this message replies to. */
  public String getReplyTo() {
    return replyTo;
  }

  public void setReplyTo(String replyTo) {
    this.replyTo = replyTo;
  }

  /** Per-type opaque payload map; shape interpretation belongs to the decoder. */
  public Map<String, Object> getBody() {
    return body;
  }

  public void setBody(Map<String, Object> body) {
    this.body = body;
  }

  /**
   * Resume handle from {@code session.open.resume}, used to recover a detached session's stream
   * position. Pure protocol-state: it never reaches the domain usecase.
   *
   * @param sessionId the session to resume
   * @param afterStreamSeq last stream sequence the client applied
   * @param knownThreadRevision last thread revision the client holds
   * @param mode resume strategy hint ({@code resume_if_possible} / {@code snapshot_only}); nullable
   */
  public record VhrpResumeRequest(
      String sessionId, long afterStreamSeq, long knownThreadRevision, String mode) {}

  /**
   * Re-sync request from {@code thread.sync.request}, used after a gap or revision mismatch. Pure
   * protocol-state: it never reaches the domain usecase.
   *
   * @param afterStreamSeq last stream sequence the client applied
   * @param knownThreadRevision last thread revision the client holds
   * @param mode {@code delta_or_snapshot} / {@code snapshot_only}; nullable
   * @param reason optional diagnostic reason; nullable
   */
  public record VhrpSyncRequest(
      long afterStreamSeq, long knownThreadRevision, String mode, String reason) {}
}
