





/**
 * VHRP/1 CBOR codec.
 *
 * <p>Owns a dedicated CBOR {@link ObjectMapper} kept entirely separate from the REST JSON mapper, as
 * the backend design requires. Its only job is to translate between raw WebSocket binary frames
 * ({@code byte[]}) and the wire {@link VhrpEnvelope}. This is pure transport (de)serialization: it
 * does not validate per-type body shape, enforce ordering, or interpret semantics — those are the
 * decoder's and usecase's concerns.
 *
 * <p>Following {@link Util}'s precedent for non-domain support code, malformed input surfaces as a
 * plain {@link IllegalStateException}; there is no bespoke codec-exception type, and the endpoint
 * already routes any {@link RuntimeException} from decoding into its malformed-frame path.
 */
@ApplicationScoped
public class VhrpCodec {

  private final ObjectMapper cborMapper = new ObjectMapper(new CBORFactory());

  /**
   * Decode one inbound binary frame into a wire envelope.
   *
   * @param frame the raw CBOR bytes of a single WebSocket binary message
   * @return the decoded envelope
   * @throws IllegalStateException when the frame is null, empty, or not a valid CBOR envelope
   */
  public VhrpEnvelope decode(byte[] frame) {
    if (frame == null || frame.length == 0) {
      throw new IllegalStateException("Empty VHRP frame");
    }
    try {
      return cborMapper.readValue(frame, VhrpEnvelope.class);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to decode VHRP CBOR frame", e);
    }
  }

  /**
   * Encode an outbound wire envelope into a CBOR binary frame.
   *
   * @param envelope the envelope to serialize
   * @return the CBOR bytes for a single WebSocket binary message
   * @throws IllegalStateException when the envelope cannot be encoded
   */
  public byte[] encode(VhrpEnvelope envelope) {
    try {
      return cborMapper.writeValueAsBytes(envelope);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to encode VHRP CBOR frame", e);
    }
  }
}
