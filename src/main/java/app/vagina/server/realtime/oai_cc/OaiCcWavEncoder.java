package app.vagina.server.realtime.oai_cc;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;

/** Encodes 24 kHz mono PCM16 into a WAV container for Chat Completions audio input. */
public final class OaiCcWavEncoder {
  private static final int SAMPLE_RATE = 24000;
  private static final int CHANNELS = 1;
  private static final int BITS_PER_SAMPLE = 16;

  private OaiCcWavEncoder() {}

  public static String encodeBase64(byte[] pcm) {
    return Base64.getEncoder().encodeToString(encode(pcm));
  }

  public static byte[] encode(byte[] pcm) {
    byte[] body = pcm == null ? new byte[0] : pcm;
    int byteRate = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8;
    int blockAlign = CHANNELS * BITS_PER_SAMPLE / 8;
    ByteBuffer buffer = ByteBuffer.allocate(44 + body.length).order(ByteOrder.LITTLE_ENDIAN);
    buffer.put(new byte[] {'R', 'I', 'F', 'F'});
    buffer.putInt(36 + body.length);
    buffer.put(new byte[] {'W', 'A', 'V', 'E'});
    buffer.put(new byte[] {'f', 'm', 't', ' '});
    buffer.putInt(16);
    buffer.putShort((short) 1);
    buffer.putShort((short) CHANNELS);
    buffer.putInt(SAMPLE_RATE);
    buffer.putInt(byteRate);
    buffer.putShort((short) blockAlign);
    buffer.putShort((short) BITS_PER_SAMPLE);
    buffer.put(new byte[] {'d', 'a', 't', 'a'});
    buffer.putInt(body.length);
    buffer.put(body);
    return buffer.array();
  }
}
