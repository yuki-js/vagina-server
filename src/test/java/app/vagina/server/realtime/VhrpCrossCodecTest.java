package app.vagina.server.realtime;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import io.vertx.core.buffer.Buffer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.function.Consumer;

/**
 * VHRP cross-codec interoperability test — Layer 1, Java side.
 *
 * <p>This test class verifies byte-level interoperability between the Dart {@code cbor} package
 * (client) and Java Jackson CBOR (server) by exchanging fixtures stored in {@code
 * .private.local/vhrp_fixtures/}. Failure here means a real WebSocket client would receive or send
 * frames that the other side cannot decode correctly.
 *
 * <p>Two axes are tested:
 *
 * <ol>
 *   <li><b>C2S decode</b> — Dart-generated {@code c2s/*.cbor} are decoded by {@link VhrpCborCodec}
 *       and every field is asserted against the paired {@code *.json} expectation file.
 *   <li><b>S2C generate</b> — Java-constructed {@link VhrpMessage.S2C} records are encoded and
 *       written to {@code s2c/*.cbor} + {@code s2c/*.json} for Dart-side verification.
 * </ol>
 *
 * <p>{@link VhrpCborCodec} has no DI constructor dependencies; {@code new VhrpCborCodec()} works
 * without a Quarkus container. No {@code @QuarkusTest} annotation is used here.
 */
class VhrpCrossCodecTest {

  /** Codec under test — constructed directly, no container required. */
  private static final VhrpCborCodec CODEC = new VhrpCborCodec();

  /** Plain JSON mapper for reading/writing {@code .json} fixture files. */
  private static final ObjectMapper JSON = new ObjectMapper();

  /** CBOR mapper for ad-hoc C2S frames not covered by checked-in Dart fixtures yet. */
  private static final ObjectMapper CBOR = new ObjectMapper(new CBORFactory());

  /** Hex formatter matching the Dart fixture convention (lower-case, no prefix). */
  private static final HexFormat HEX = HexFormat.of();

  private static Path C2S_DIR;
  private static Path S2C_DIR;

  @BeforeAll
  static void setUpFixtureDirs() throws IOException {
    // The server/ sub-project is the Gradle working directory when tests run.
    // Resolving "../.private.local/..." reaches the repository-root-level gitignored store.
    Path base = Paths.get("../.private.local/vhrp_fixtures");
    C2S_DIR = base.resolve("c2s");
    S2C_DIR = base.resolve("s2c");
    Files.createDirectories(S2C_DIR);
  }

  // =========================================================================
  // C2S Decode Tests — Dart → Java
  //
  // Contract: If any assertion fails the Dart codec and the Java codec produce
  // different representations for the same wire bytes. This will manifest as
  // silently wrong session state (wrong modelId, truncated audio bytes, etc.)
  // when a real Flutter client connects.
  // =========================================================================

  // --- session.open (no resume) ---

  /**
   * Contract: session.open envelope fields (messageId, token, speedDialId, audioTurnMode, client)
   * round-trip. Model, voice, prompt, and audio formats are Speed Dial/server-owned.
   */
  @Test
  @DisplayName("C2S decode: session_open__no_resume")
  void decodeSessionOpenNoResume() throws IOException {
    JsonNode expected = readJson("session_open__no_resume");
    VhrpMessage.C2S msg = decodeFixture("session_open__no_resume");

    assertInstanceOf(VhrpMessage.SessionOpen.class, msg);
    VhrpMessage.SessionOpen open = (VhrpMessage.SessionOpen) msg;

    assertEquals(expected.get("messageId").asText(), open.messageId());
    JsonNode body = expected.get("body");
    assertEquals(body.get("token").asText(), open.token());
    assertEquals(body.get("speedDialId").asText(), open.speedDialId());
    assertEquals(body.get("audioTurnMode").asText(), open.audioTurnMode());
    assertNull(open.resume(), "no resume field → null ResumeRequest");
    assertFalse(body.has("modelId"), "model is derived from server-side Speed Dial");
    assertFalse(body.has("voice"), "voice is derived from server-side Speed Dial");
    assertFalse(body.has("instructions"), "initial prompt is derived from server-side Speed Dial");
    assertFalse(body.has("inputAudio"), "audio format is server-owned");
    assertFalse(body.has("outputAudio"), "audio format is server-owned");

    // client map
    assertNotNull(open.client());
    assertEquals("flutter", open.client().get("platform"));
    assertEquals("1.0.0", open.client().get("appVersion"));
  }

  // --- session.open (with resume) ---

  /**
   * Contract: resume.sessionId must be preserved exactly. Failure → server tries to bind to the
   * wrong (or null) session, causing a spurious new-session creation.
   */
  @Test
  @DisplayName("C2S decode: session_open__with_resume")
  void decodeSessionOpenWithResume() throws IOException {
    JsonNode expected = readJson("session_open__with_resume");
    VhrpMessage.C2S msg = decodeFixture("session_open__with_resume");

    assertInstanceOf(VhrpMessage.SessionOpen.class, msg);
    VhrpMessage.SessionOpen open = (VhrpMessage.SessionOpen) msg;

    assertEquals(expected.get("messageId").asText(), open.messageId());
    assertNotNull(open.resume(), "resume object must be present");
    assertEquals(
        expected.get("body").get("resume").get("sessionId").asText(), open.resume().sessionId());
  }

  // --- session.end ---

  /**
   * Contract: session.end is a terminal one-way command. It has no messageId and no ack payload;
   * the server decodes it solely as an explicit close intent for the already-bound session.
   */
  @Test
  @DisplayName("C2S decode: session_end")
  void decodeSessionEnd() throws IOException {
    VhrpCborCodec codec = new VhrpCborCodec();
    VhrpMessage.C2S msg = codec.decode(cborEnvelope("session.end", null, Map.of()));

    assertInstanceOf(VhrpMessage.SessionEnd.class, msg);
  }

  // --- audio.turn.mode.set (voice_activity) ---

  /**
   * Contract: mode string determines VAD pipeline activation. Failure → server starts wrong audio
   * processing branch.
   */
  @Test
  @DisplayName("C2S decode: audio_turn_mode_set__voice_activity")
  void decodeAudioTurnModeSetVoiceActivity() throws IOException {
    JsonNode expected = readJson("audio_turn_mode_set__voice_activity");
    VhrpMessage.C2S msg = decodeFixture("audio_turn_mode_set__voice_activity");

    assertInstanceOf(VhrpMessage.AudioTurnModeSet.class, msg);
    assertEquals(
        expected.get("body").get("mode").asText(), ((VhrpMessage.AudioTurnModeSet) msg).mode());
  }

  // --- audio.turn.mode.set (manual) ---

  @Test
  @DisplayName("C2S decode: audio_turn_mode_set__manual")
  void decodeAudioTurnModeSetManual() throws IOException {
    JsonNode expected = readJson("audio_turn_mode_set__manual");
    VhrpMessage.C2S msg = decodeFixture("audio_turn_mode_set__manual");

    assertInstanceOf(VhrpMessage.AudioTurnModeSet.class, msg);
    assertEquals(
        expected.get("body").get("mode").asText(), ((VhrpMessage.AudioTurnModeSet) msg).mode());
  }

  // --- session.instructions.set (basic) ---

  @Test
  @DisplayName("C2S decode: session_instructions_set__basic")
  void decodeSessionInstructionsSetBasic() throws IOException {
    JsonNode expected = readJson("session_instructions_set__basic");
    VhrpMessage.C2S msg = decodeFixture("session_instructions_set__basic");

    assertInstanceOf(VhrpMessage.SessionInstructionsSet.class, msg);
    VhrpMessage.SessionInstructionsSet set = (VhrpMessage.SessionInstructionsSet) msg;
    assertEquals(expected.get("messageId").asText(), set.messageId());
    assertEquals(expected.get("body").get("instructions").asText(), set.instructions());
  }

  // --- session.instructions.set (null instructions) ---

  /**
   * Contract: When the Dart side omits the {@code instructions} key entirely (absent, not null),
   * Java must decode it as {@code null} — not throw, not return an empty string. Failure → server
   * may use stale instructions or crash on null check.
   */
  @Test
  @DisplayName("C2S decode: session_instructions_set__null_instructions (key absent)")
  void decodeSessionInstructionsSetNullInstructions() throws IOException {
    // The JSON fixture has body:{} — no instructions key at all
    JsonNode expected = readJson("session_instructions_set__null_instructions");
    VhrpMessage.C2S msg = decodeFixture("session_instructions_set__null_instructions");

    assertInstanceOf(VhrpMessage.SessionInstructionsSet.class, msg);
    VhrpMessage.SessionInstructionsSet set = (VhrpMessage.SessionInstructionsSet) msg;

    assertEquals(expected.get("messageId").asText(), set.messageId());
    assertFalse(
        expected.get("body").has("instructions"), "Fixture JSON must not contain instructions key");
    assertNull(set.instructions(), "instructions absent in CBOR → Java null");
  }

  // --- live.audio.chunk (with pcm bstr) ---

  /**
   * Contract: PCM bytes must travel as CBOR bstr (not text, not base64). If Jackson CBOR decodes
   * the Dart bstr as a binary node, {@code bytes()} returns the raw bytes. Failure → pcm is
   * zero-length or corrupted, real-time audio broken.
   */
  @Test
  @DisplayName("C2S decode: live_audio_chunk__with_pcm (bstr bytes)")
  void decodeLiveAudioChunkWithPcm() throws IOException {
    JsonNode expected = readJson("live_audio_chunk__with_pcm");
    VhrpMessage.C2S msg = decodeFixture("live_audio_chunk__with_pcm");

    assertInstanceOf(VhrpMessage.LiveAudioChunk.class, msg);
    VhrpMessage.LiveAudioChunk chunk = (VhrpMessage.LiveAudioChunk) msg;

    byte[] expectedPcm = HEX.parseHex(expected.get("body").get("pcm").asText());
    assertArrayEquals(
        expectedPcm, chunk.pcm(), "PCM bytes from CBOR bstr must match hex fixture exactly");
    assertEquals(expected.get("body").get("sequence").asLong(), chunk.sequence());
  }

  // --- live.audio.chunk (large sequence — exceeds int32) ---

  /**
   * Contract: sequence is {@code long} on both sides. Dart encodes 2147483648 (= Integer.MAX_VALUE
   * + 1) as a CBOR major-type-0 uint. Java must decode it as {@code asLong()}, not truncate to int.
   * Failure → sequence wraps to negative, server audio ordering broken.
   */
  @Test
  @DisplayName("C2S decode: live_audio_chunk__large_sequence (sequence > Integer.MAX_VALUE)")
  void decodeLiveAudioChunkLargeSequence() throws IOException {
    JsonNode expected = readJson("live_audio_chunk__large_sequence");
    VhrpMessage.C2S msg = decodeFixture("live_audio_chunk__large_sequence");

    assertInstanceOf(VhrpMessage.LiveAudioChunk.class, msg);
    VhrpMessage.LiveAudioChunk chunk = (VhrpMessage.LiveAudioChunk) msg;

    long expectedSeq = expected.get("body").get("sequence").asLong();
    assertEquals(2147483648L, expectedSeq, "fixture must encode the large value");
    assertEquals(expectedSeq, chunk.sequence(), "sequence must be decoded as long, not int");
  }

  // --- turn.audio.submit (with pcm bstr) ---

  /**
   * Contract: Same bstr guarantee as live.audio.chunk, plus sampleRate/channels/bitDepth defaults.
   * Failure → audio submit with wrong encoding parameters, model receives undecodable audio.
   */
  @Test
  @DisplayName("C2S decode: turn_audio_submit__with_pcm")
  void decodeTurnAudioSubmitWithPcm() throws IOException {
    JsonNode expected = readJson("turn_audio_submit__with_pcm");
    VhrpMessage.C2S msg = decodeFixture("turn_audio_submit__with_pcm");

    assertInstanceOf(VhrpMessage.TurnAudioSubmit.class, msg);
    VhrpMessage.TurnAudioSubmit submit = (VhrpMessage.TurnAudioSubmit) msg;

    JsonNode body = expected.get("body");
    assertEquals(expected.get("messageId").asText(), submit.messageId());
    assertEquals(body.get("clientItemId").asText(), submit.clientItemId());

    byte[] expectedPcm = HEX.parseHex(body.get("pcm").asText());
    assertArrayEquals(expectedPcm, submit.pcm(), "PCM bstr bytes must match");
  }

  // --- turn.text.submit (basic) ---

  @Test
  @DisplayName("C2S decode: turn_text_submit__basic")
  void decodeTurnTextSubmitBasic() throws IOException {
    JsonNode expected = readJson("turn_text_submit__basic");
    VhrpMessage.C2S msg = decodeFixture("turn_text_submit__basic");

    assertInstanceOf(VhrpMessage.TurnTextSubmit.class, msg);
    VhrpMessage.TurnTextSubmit submit = (VhrpMessage.TurnTextSubmit) msg;

    JsonNode body = expected.get("body");
    assertEquals(expected.get("messageId").asText(), submit.messageId());
    assertEquals(body.get("clientItemId").asText(), submit.clientItemId());
    assertEquals(body.get("text").asText(), submit.text());
  }

  // --- turn.text.submit (Japanese + emoji) ---

  /**
   * Contract: CBOR text type carries UTF-8 directly; multi-byte sequences (Japanese, emoji) must
   * not be truncated or mojibaked. Failure → user text turns arrive garbled.
   */
  @Test
  @DisplayName("C2S decode: turn_text_submit__japanese (UTF-8 multibyte + emoji)")
  void decodeTurnTextSubmitJapanese() throws IOException {
    JsonNode expected = readJson("turn_text_submit__japanese");
    VhrpMessage.C2S msg = decodeFixture("turn_text_submit__japanese");

    assertInstanceOf(VhrpMessage.TurnTextSubmit.class, msg);
    VhrpMessage.TurnTextSubmit submit = (VhrpMessage.TurnTextSubmit) msg;

    String expectedText = expected.get("body").get("text").asText();
    assertEquals(
        expectedText,
        submit.text(),
        "Japanese text + emoji must survive CBOR text encoding unchanged");
  }

  // --- turn.image.submit (with JPEG bstr) ---

  /**
   * Contract: imageBytes is CBOR bstr; magic bytes 0xFF 0xD8 must be present at offset 0. Failure →
   * image upload delivers corrupted bytes, MIME detection fails.
   */
  @Test
  @DisplayName("C2S decode: turn_image_submit__with_jpeg (bstr JPEG magic bytes)")
  void decodeTurnImageSubmitWithJpeg() throws IOException {
    JsonNode expected = readJson("turn_image_submit__with_jpeg");
    VhrpMessage.C2S msg = decodeFixture("turn_image_submit__with_jpeg");

    assertInstanceOf(VhrpMessage.TurnImageSubmit.class, msg);
    VhrpMessage.TurnImageSubmit submit = (VhrpMessage.TurnImageSubmit) msg;

    byte[] expectedBytes = HEX.parseHex(expected.get("body").get("imageBytes").asText());
    assertArrayEquals(expectedBytes, submit.imageBytes(), "imageBytes bstr must match");
    // JPEG magic bytes check: FF D8
    assertTrue(submit.imageBytes().length >= 2, "imageBytes must not be empty");
    assertEquals((byte) 0xFF, submit.imageBytes()[0], "JPEG magic byte 0 = 0xFF");
    assertEquals((byte) 0xD8, submit.imageBytes()[1], "JPEG magic byte 1 = 0xD8");
  }

  // --- tools.set (empty) ---

  /**
   * Contract: Empty tools array disables tool calling. Failure → server leaves stale tools
   * registered.
   */
  @Test
  @DisplayName("C2S decode: tools_set__empty (empty tools list)")
  void decodeToolsSetEmpty() throws IOException {
    VhrpMessage.C2S msg = decodeFixture("tools_set__empty");
    assertInstanceOf(VhrpMessage.ToolsSet.class, msg);
    VhrpMessage.ToolsSet set = (VhrpMessage.ToolsSet) msg;
    assertNotNull(set.tools());
    assertTrue(set.tools().isEmpty(), "tools list must be empty");
  }

  // --- tools.set (multi-tool with nested parameters map + Japanese description) ---

  /**
   * Contract: Nested JSON-Schema parameters map must survive as a Map&lt;String,Object&gt; with
   * identical key/value structure. Japanese description text must be preserved. Failure → tool
   * schema corrupted, AI model cannot call tools correctly.
   */
  @Test
  @DisplayName("C2S decode: tools_set__multi_tool (nested parameters map + Japanese)")
  void decodeToolsSetMultiTool() throws IOException {
    JsonNode expected = readJson("tools_set__multi_tool");
    VhrpMessage.C2S msg = decodeFixture("tools_set__multi_tool");

    assertInstanceOf(VhrpMessage.ToolsSet.class, msg);
    VhrpMessage.ToolsSet set = (VhrpMessage.ToolsSet) msg;

    assertEquals(expected.get("messageId").asText(), set.messageId());
    assertEquals(2, set.tools().size(), "should have 2 tools");

    VhrpMessage.ToolSpec weatherTool = set.tools().get(0);
    assertEquals("get_weather", weatherTool.name());
    assertEquals("現在の天気を取得する", weatherTool.description(), "Japanese description must be preserved");
    assertNotNull(weatherTool.parameters());
    assertEquals("object", weatherTool.parameters().get("type"));

    @SuppressWarnings("unchecked")
    Map<String, Object> props = (Map<String, Object>) weatherTool.parameters().get("properties");
    assertNotNull(props, "properties map must not be null");
    assertTrue(props.containsKey("city"), "city property must exist");

    VhrpMessage.ToolSpec searchTool = set.tools().get(1);
    assertEquals("search_docs", searchTool.name());
  }

  // --- tools.set (no-args tool — empty properties map must not be string "{}") ---

  /**
   * Regression contract: a tool with {@code parameters: {type:object, properties:{}}} must survive
   * the CBOR round-trip with {@code properties} as an empty {@code Map<String,Object>}, NOT as the
   * string literal {@code "{}"}. The bug was that Dart's {@code _dartToCbor} fell through to {@code
   * value.toString()} for const maps whose runtime type is {@code _ConstMap<dynamic,dynamic>},
   * producing the string {@code "{}"} on the wire. Failure → provider rejects tool with "invalid
   * schema for function".
   */
  @Test
  @DisplayName("C2S decode: tools_set__no_args_tool (empty properties map, not string)")
  void decodeToolsSetNoArgsTool() throws IOException {
    VhrpMessage.C2S msg = decodeFixture("tools_set__no_args_tool");

    assertInstanceOf(VhrpMessage.ToolsSet.class, msg);
    VhrpMessage.ToolsSet set = (VhrpMessage.ToolsSet) msg;

    assertEquals(1, set.tools().size(), "should have 1 tool");

    VhrpMessage.ToolSpec tool = set.tools().get(0);
    assertEquals("fs_active_files", tool.name());
    assertNotNull(tool.parameters(), "parameters must not be null");
    assertEquals("object", tool.parameters().get("type"), "type must be 'object'");

    // The critical assertion: properties must be a Map, NOT the string "{}"
    Object props = tool.parameters().get("properties");
    assertNotNull(props, "properties key must be present");
    assertInstanceOf(
        Map.class,
        props,
        "properties must be a Map<String,Object>, not the string \"{}\" — "
            + "actual type: "
            + props.getClass().getName()
            + ", value: "
            + props);

    @SuppressWarnings("unchecked")
    Map<String, Object> propsMap = (Map<String, Object>) props;
    assertTrue(propsMap.isEmpty(), "properties must be an empty map (no-arg tool)");
  }

  // --- session.extension.apply (basic) ---

  @Test
  @DisplayName("C2S decode: session_extension_apply__basic")
  void decodeSessionExtensionApplyBasic() throws IOException {
    JsonNode expected = readJson("session_extension_apply__basic");
    VhrpMessage.C2S msg = decodeFixture("session_extension_apply__basic");

    assertInstanceOf(VhrpMessage.SessionExtensionApply.class, msg);
    VhrpMessage.SessionExtensionApply apply = (VhrpMessage.SessionExtensionApply) msg;

    assertEquals(expected.get("messageId").asText(), apply.messageId());
    assertEquals(expected.get("body").get("extensionType").asText(), apply.extensionType());
    assertNotNull(apply.payload());
  }

  // --- tool.result.submit (success — errorMessage absent) ---

  /**
   * Contract: errorMessage is optional; when absent from CBOR, Java must return null — not throw.
   * Failure → null pointer in result handling or spurious error reported.
   */
  @Test
  @DisplayName("C2S decode: tool_result_submit__success (errorMessage key absent)")
  void decodeToolResultSubmitSuccess() throws IOException {
    JsonNode expected = readJson("tool_result_submit__success");
    VhrpMessage.C2S msg = decodeFixture("tool_result_submit__success");

    assertInstanceOf(VhrpMessage.ToolResultSubmit.class, msg);
    VhrpMessage.ToolResultSubmit result = (VhrpMessage.ToolResultSubmit) msg;

    JsonNode body = expected.get("body");
    assertEquals(expected.get("messageId").asText(), result.messageId());
    assertEquals(body.get("clientItemId").asText(), result.clientItemId());
    assertEquals(body.get("callId").asText(), result.callId());
    assertEquals(body.get("output").asText(), result.output());
    assertEquals(body.get("disposition").asText(), result.disposition());

    assertFalse(body.has("errorMessage"), "fixture must not have errorMessage key");
    assertNull(result.errorMessage(), "errorMessage absent in CBOR → Java null");
  }

  // --- tool.result.submit (error) ---

  @Test
  @DisplayName("C2S decode: tool_result_submit__error")
  void decodeToolResultSubmitError() throws IOException {
    JsonNode expected = readJson("tool_result_submit__error");
    VhrpMessage.C2S msg = decodeFixture("tool_result_submit__error");

    assertInstanceOf(VhrpMessage.ToolResultSubmit.class, msg);
    VhrpMessage.ToolResultSubmit result = (VhrpMessage.ToolResultSubmit) msg;

    JsonNode body = expected.get("body");
    assertEquals(body.get("disposition").asText(), result.disposition());
    if (body.has("errorMessage")) {
      assertEquals(body.get("errorMessage").asText(), result.errorMessage());
    }
  }

  // --- assistant.interrupt (barge-in, no messageId) ---

  /**
   * Contract: messageId is optional on the envelope; when absent the codec must return null for the
   * field (which is not exposed on AssistantInterrupt). The reason field must decode. Failure →
   * interrupt silently dropped or misrouted.
   */
  @Test
  @DisplayName("C2S decode: assistant_interrupt__barge_in (no messageId on envelope)")
  void decodeAssistantInterruptBargeIn() throws IOException {
    JsonNode expected = readJson("assistant_interrupt__barge_in");
    VhrpMessage.C2S msg = decodeFixture("assistant_interrupt__barge_in");

    assertInstanceOf(VhrpMessage.AssistantInterrupt.class, msg);
    VhrpMessage.AssistantInterrupt interrupt = (VhrpMessage.AssistantInterrupt) msg;

    assertFalse(expected.has("messageId"), "fixture must not have messageId");
    assertEquals(expected.get("body").get("reason").asText(), interrupt.reason());
  }

  // --- thread.sync.request (basic) ---

  @Test
  @DisplayName("C2S decode: thread_sync_request__basic")
  void decodeThreadSyncRequestBasic() throws IOException {
    JsonNode expected = readJson("thread_sync_request__basic");
    VhrpMessage.C2S msg = decodeFixture("thread_sync_request__basic");

    assertInstanceOf(VhrpMessage.ThreadSyncRequest.class, msg);
    VhrpMessage.ThreadSyncRequest req = (VhrpMessage.ThreadSyncRequest) msg;

    assertEquals(expected.get("messageId").asText(), req.messageId());
    assertEquals(expected.get("body").get("reason").asText(), req.reason());
  }

  // =========================================================================
  // S2C Fixture Generation — Java → Dart
  //
  // Each test builds a Java VhrpMessage.S2C record, encodes it to CBOR via
  // VhrpCborCodec.encode() → Buffer → byte[], and writes:
  //   s2c/<name>.cbor  — raw CBOR bytes Dart will decode
  //   s2c/<name>.json  — expected value file in the Dart fixture convention
  //
  // Contract: The .json file is the source of truth Dart assertions use.
  // bstr fields are written as lower-case hex (no 0x prefix).
  // bool fields are written as JSON booleans (never 0/1).
  // =========================================================================

  /**
   * Contract: session.ready full — all optional fields present. Dart client must reconstruct
   * sessionId, threadId, conversationId, replyTo, capabilities.extensions[]. Failure → client
   * cannot initialise session state.
   */
  @Test
  @DisplayName("S2C generate: session_ready__full")
  void generateSessionReadyFull() throws IOException {
    VhrpMessage.SessionReady msg =
        new VhrpMessage.SessionReady(
            "open-001", "sess_abc123", "thread_xyz789", "conv_def456", List.of("vad", "barge_in"));

    byte[] cbor = encode(msg);
    writeFixture(
        "session_ready__full",
        cbor,
        buildS2cJson(
            "session.ready",
            null, // no bstr fields
            b -> {
              b.put("replyTo", "open-001");
              ObjectNode body = b.putObject("body");
              body.put("sessionId", "sess_abc123");
              body.put("threadId", "thread_xyz789");
              body.put("conversationId", "conv_def456");
              ObjectNode caps = body.putObject("capabilities");
              ArrayNode ext = caps.putArray("extensions");
              ext.add("vad");
              ext.add("barge_in");
            }));
  }

  /**
   * Contract: session.ready minimal — conversationId absent, empty extensions. Dart must not fail
   * when optional fields are missing.
   */
  @Test
  @DisplayName("S2C generate: session_ready__minimal")
  void generateSessionReadyMinimal() throws IOException {
    VhrpMessage.SessionReady msg =
        new VhrpMessage.SessionReady("open-002", "sess_min01", "thread_min01", null, List.of());

    byte[] cbor = encode(msg);
    writeFixture(
        "session_ready__minimal",
        cbor,
        buildS2cJson(
            "session.ready",
            null,
            b -> {
              b.put("replyTo", "open-002");
              ObjectNode body = b.putObject("body");
              body.put("sessionId", "sess_min01");
              body.put("threadId", "thread_min01");
              ObjectNode caps = body.putObject("capabilities");
              caps.putArray("extensions");
            }));
  }

  /** Contract: session.resumed — confirms session rebind; Dart sends thread.sync.request next. */
  @Test
  @DisplayName("S2C generate: session_resumed__basic")
  void generateSessionResumedBasic() throws IOException {
    VhrpMessage.SessionResumed msg =
        new VhrpMessage.SessionResumed("open-002", "sess_resumed01", "thread_resumed01", null);

    byte[] cbor = encode(msg);
    writeFixture(
        "session_resumed__basic",
        cbor,
        buildS2cJson(
            "session.resumed",
            null,
            b -> {
              b.put("replyTo", "open-002");
              ObjectNode body = b.putObject("body");
              body.put("sessionId", "sess_resumed01");
              body.put("threadId", "thread_resumed01");
            }));
  }

  /**
   * Contract: ack accepted — accepted and applied must be CBOR bool (major type 7), not int 0/1.
   * Failure → Dart decodes an integer as truthy but type-checking fails in strict decoders.
   */
  @Test
  @DisplayName("S2C generate: ack__accepted (bool must be CBOR bool, not int)")
  void generateAckAccepted() throws IOException {
    VhrpMessage.Ack msg = new VhrpMessage.Ack("tools-001", true, "ci-tools-001", true);

    byte[] cbor = encode(msg);
    writeFixture(
        "ack__accepted",
        cbor,
        buildS2cJson(
            "ack",
            null,
            b -> {
              b.put("replyTo", "tools-001");
              ObjectNode body = b.putObject("body");
              body.put("accepted", true);
              body.put("clientItemId", "ci-tools-001");
              body.put("applied", true);
            }));
  }

  /** Contract: ack rejected — accepted=false must be CBOR false, not 0. */
  @Test
  @DisplayName("S2C generate: ack__rejected (accepted=false CBOR bool)")
  void generateAckRejected() throws IOException {
    VhrpMessage.Ack msg = new VhrpMessage.Ack("tools-002", false, "ci-tools-002", false);

    byte[] cbor = encode(msg);
    writeFixture(
        "ack__rejected",
        cbor,
        buildS2cJson(
            "ack",
            null,
            b -> {
              b.put("replyTo", "tools-002");
              ObjectNode body = b.putObject("body");
              body.put("accepted", false);
              body.put("clientItemId", "ci-tools-002");
              body.put("applied", false);
            }));
  }

  /** Contract: thread.snapshot carries full thread state; items[] is a list of opaque maps. */
  @Test
  @DisplayName("S2C generate: thread_snapshot__with_items")
  void generateThreadSnapshotWithItems() throws IOException {
    Map<String, Object> item1 = new LinkedHashMap<>();
    item1.put("id", "item_001");
    item1.put("role", "user");
    item1.put("content", "Hello");

    Map<String, Object> item2 = new LinkedHashMap<>();
    item2.put("id", "item_002");
    item2.put("role", "assistant");
    item2.put("content", "Hi there!");

    VhrpMessage.ThreadSnapshot msg =
        new VhrpMessage.ThreadSnapshot("thread_snap01", "conv_snap01", List.of(item1, item2));

    byte[] cbor = encode(msg);
    writeFixture(
        "thread_snapshot__with_items",
        cbor,
        buildS2cJson(
            "thread.snapshot",
            null,
            b -> {
              ObjectNode body = b.putObject("body");
              body.put("threadId", "thread_snap01");
              body.put("conversationId", "conv_snap01");
              ArrayNode items = body.putArray("items");
              ObjectNode i1 = items.addObject();
              i1.put("id", "item_001");
              i1.put("role", "user");
              i1.put("content", "Hello");
              ObjectNode i2 = items.addObject();
              i2.put("id", "item_002");
              i2.put("role", "assistant");
              i2.put("content", "Hi there!");
            }));
  }

  /**
   * Contract: thread.patch ops[] carries add_item + append_text ops. Dart applies them sequentially
   * to the projected thread; op type and fields must survive opaque-map encoding.
   */
  @Test
  @DisplayName("S2C generate: thread_patch__add_item_and_append_text")
  void generateThreadPatchAddItemAndAppendText() throws IOException {
    Map<String, Object> op1 = new LinkedHashMap<>();
    op1.put("op", "add_item");
    op1.put("itemId", "item_patch01");
    op1.put("role", "assistant");

    Map<String, Object> op2 = new LinkedHashMap<>();
    op2.put("op", "append_text");
    op2.put("itemId", "item_patch01");
    op2.put("contentIndex", 0);
    op2.put("delta", "Hello");

    VhrpMessage.ThreadPatch msg = new VhrpMessage.ThreadPatch(List.of(op1, op2));

    byte[] cbor = encode(msg);
    writeFixture(
        "thread_patch__add_item_and_append_text",
        cbor,
        buildS2cJson(
            "thread.patch",
            null,
            b -> {
              ObjectNode body = b.putObject("body");
              ArrayNode ops = body.putArray("ops");
              ObjectNode o1 = ops.addObject();
              o1.put("op", "add_item");
              o1.put("itemId", "item_patch01");
              o1.put("role", "assistant");
              ObjectNode o2 = ops.addObject();
              o2.put("op", "append_text");
              o2.put("itemId", "item_patch01");
              o2.put("contentIndex", 0);
              o2.put("delta", "Hello");
            }));
  }

  /**
   * Contract: append_text delta with Japanese text must survive CBOR text encoding. Failure →
   * streaming text to client arrives garbled.
   */
  @Test
  @DisplayName("S2C generate: thread_patch__japanese_text")
  void generateThreadPatchJapaneseText() throws IOException {
    Map<String, Object> op = new LinkedHashMap<>();
    op.put("op", "append_text");
    op.put("itemId", "item_jp01");
    op.put("contentIndex", 0);
    op.put("delta", "こんにちは、世界！🌍");

    VhrpMessage.ThreadPatch msg = new VhrpMessage.ThreadPatch(List.of(op));

    byte[] cbor = encode(msg);
    writeFixture(
        "thread_patch__japanese_text",
        cbor,
        buildS2cJson(
            "thread.patch",
            null,
            b -> {
              ObjectNode body = b.putObject("body");
              ArrayNode ops = body.putArray("ops");
              ObjectNode o = ops.addObject();
              o.put("op", "append_text");
              o.put("itemId", "item_jp01");
              o.put("contentIndex", 0);
              o.put("delta", "こんにちは、世界！🌍");
            }));
  }

  /**
   * Contract: assistant.audio.chunk pcm must be CBOR bstr. Jackson serialises byte[] in an
   * ObjectNode as a binary node, which CBORFactory encodes as major type 2 bstr. Dart must decode
   * it as Uint8List. Failure → audio streamed to client is corrupted or treated as text.
   */
  @Test
  @DisplayName("S2C generate: assistant_audio_chunk__with_pcm (pcm = CBOR bstr)")
  void generateAssistantAudioChunkWithPcm() throws IOException {
    byte[] pcmBytes = {0x00, 0x01, (byte) 0x80, (byte) 0xFF, 0x7F};
    VhrpMessage.AssistantAudioChunk msg =
        new VhrpMessage.AssistantAudioChunk("item_aud01", 0, pcmBytes);

    byte[] cbor = encode(msg);
    String pcmHex = HEX.formatHex(pcmBytes);

    writeFixture(
        "assistant_audio_chunk__with_pcm",
        cbor,
        buildS2cJson(
            "assistant.audio.chunk",
            List.of("body.pcm"),
            b -> {
              ObjectNode body = b.putObject("body");
              body.put("itemId", "item_aud01");
              body.put("contentIndex", 0);
              body.put("pcm", pcmHex);
            }));
  }

  /**
   * Contract: assistant.audio.done signals end of audio stream for a part; Dart finalises playback
   * buffer.
   */
  @Test
  @DisplayName("S2C generate: assistant_audio_done__basic")
  void generateAssistantAudioDoneBasic() throws IOException {
    VhrpMessage.AssistantAudioDone msg = new VhrpMessage.AssistantAudioDone("item_aud01", 0);

    byte[] cbor = encode(msg);
    writeFixture(
        "assistant_audio_done__basic",
        cbor,
        buildS2cJson(
            "assistant.audio.done",
            null,
            b -> {
              ObjectNode body = b.putObject("body");
              body.put("itemId", "item_aud01");
              body.put("contentIndex", 0);
            }));
  }

  /**
   * Contract: vad.state isSpeaking=true must be CBOR bool true (major type 7, value 21), not
   * integer 1. Failure → Dart strict bool decoders reject the frame, VAD UI state never updates.
   */
  @Test
  @DisplayName("S2C generate: vad_state__speaking (isSpeaking = CBOR true bool)")
  void generateVadStateSpeaking() throws IOException {
    VhrpMessage.VadState msg = new VhrpMessage.VadState(true);

    byte[] cbor = encode(msg);
    writeFixture(
        "vad_state__speaking",
        cbor,
        buildS2cJson(
            "vad.state",
            null,
            b -> {
              ObjectNode body = b.putObject("body");
              body.put("isSpeaking", true);
            }));
  }

  /** Contract: vad.state isSpeaking=false must be CBOR bool false. Same concern as above. */
  @Test
  @DisplayName("S2C generate: vad_state__silent (isSpeaking = CBOR false bool)")
  void generateVadStateSilent() throws IOException {
    VhrpMessage.VadState msg = new VhrpMessage.VadState(false);

    byte[] cbor = encode(msg);
    writeFixture(
        "vad_state__silent",
        cbor,
        buildS2cJson(
            "vad.state",
            null,
            b -> {
              ObjectNode body = b.putObject("body");
              body.put("isSpeaking", false);
            }));
  }

  /**
   * Contract: error.recoverable=true must be CBOR bool. Dart must distinguish recoverable vs
   * unrecoverable to decide whether to reconnect or show a fatal error screen.
   */
  @Test
  @DisplayName("S2C generate: error__recoverable (recoverable = CBOR true bool)")
  void generateErrorRecoverable() throws IOException {
    VhrpMessage.Error msg =
        new VhrpMessage.Error("inst-001", "RATE_LIMIT", "Rate limit exceeded", true);

    byte[] cbor = encode(msg);
    writeFixture(
        "error__recoverable",
        cbor,
        buildS2cJson(
            "error",
            null,
            b -> {
              b.put("replyTo", "inst-001");
              ObjectNode body = b.putObject("body");
              body.put("code", "RATE_LIMIT");
              body.put("message", "Rate limit exceeded");
              body.put("recoverable", true);
            }));
  }

  /**
   * Contract: error.recoverable=false (unrecoverable), replyTo absent. Dart closes the connection
   * and shows fatal error.
   */
  @Test
  @DisplayName("S2C generate: error__unrecoverable (recoverable = CBOR false, no replyTo)")
  void generateErrorUnrecoverable() throws IOException {
    VhrpMessage.Error msg = VhrpMessage.Error.of("AUTH_FAILED", "Token is invalid", false);

    byte[] cbor = encode(msg);
    writeFixture(
        "error__unrecoverable",
        cbor,
        buildS2cJson(
            "error",
            null,
            b -> {
              ObjectNode body = b.putObject("body");
              body.put("code", "AUTH_FAILED");
              body.put("message", "Token is invalid");
              body.put("recoverable", false);
            }));
  }

  // =========================================================================
  // Helper utilities
  // =========================================================================

  /** Reads the {@code .cbor} fixture and decodes it via {@link VhrpCborCodec}. */
  private VhrpMessage.C2S decodeFixture(String name) throws IOException {
    Path cborPath = C2S_DIR.resolve(name + ".cbor");
    byte[] cborBytes = Files.readAllBytes(cborPath);
    // decode() takes io.vertx.core.buffer.Buffer; Buffer.buffer(byte[]) wraps without copy.
    return CODEC.decode(Buffer.buffer(cborBytes));
  }

  /** Reads the paired {@code .json} fixture expectation file. */
  private JsonNode readJson(String name) throws IOException {
    return JSON.readTree(C2S_DIR.resolve(name + ".json").toFile());
  }

  /** Builds a minimal C2S CBOR envelope for decode-only tests. */
  private static Buffer cborEnvelope(String type, String messageId, Map<String, Object> body)
      throws IOException {
    ObjectNode root = CBOR.createObjectNode();
    root.put("type", type);
    if (messageId != null) {
      root.put("messageId", messageId);
    }
    root.set("body", CBOR.valueToTree(body));
    return Buffer.buffer(CBOR.writeValueAsBytes(root));
  }

  /**
   * Encodes a {@link VhrpMessage} to CBOR bytes.
   *
   * <p>{@link VhrpCborCodec#encode(VhrpMessage)} returns a Vert.x {@link Buffer}; {@link
   * Buffer#getBytes()} extracts the raw byte array.
   */
  private static byte[] encode(VhrpMessage msg) {
    return CODEC.encode(msg).getBytes();
  }

  /**
   * Writes {@code name.cbor} and {@code name.json} to the S2C fixtures directory.
   *
   * @param name fixture stem (no extension)
   * @param cborBytes raw CBOR to write
   * @param jsonNode JSON expectation node
   */
  private void writeFixture(String name, byte[] cborBytes, JsonNode jsonNode) throws IOException {
    Files.write(S2C_DIR.resolve(name + ".cbor"), cborBytes);
    JSON.writerWithDefaultPrettyPrinter()
        .writeValue(S2C_DIR.resolve(name + ".json").toFile(), jsonNode);
  }

  /**
   * Builds the S2C JSON expectation object in the Dart fixture convention.
   *
   * <ul>
   *   <li>{@code _bstr_encoding: "hex"} always present
   *   <li>{@code _bstr_fields: [...]} present only when {@code bstrFields} is non-null/non-empty
   *   <li>{@code type} from {@code messageType}
   *   <li>The {@code bodyPopulator} lambda fills replyTo (if needed) and the body
   * </ul>
   */
  private ObjectNode buildS2cJson(
      String messageType,
      List<String> bstrFields,
      Consumer<ObjectNode> populator) {
    ObjectNode root = JSON.createObjectNode();
    root.put("_bstr_encoding", "hex");
    if (bstrFields != null && !bstrFields.isEmpty()) {
      ArrayNode arr = root.putArray("_bstr_fields");
      bstrFields.forEach(arr::add);
    }
    root.put("type", messageType);
    populator.accept(root);
    return root;
  }
}
