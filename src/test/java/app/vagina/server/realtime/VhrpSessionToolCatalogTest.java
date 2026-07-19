package app.vagina.server.realtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.vagina.server.realtime.model.RealtimeAdapterModels;
import app.vagina.server.realtime.model.RealtimeThread;
import app.vagina.server.support.EnabledToolsJson.ParseResult;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class VhrpSessionToolCatalogTest {

  @Test
  void toolsSetRetainsCatalogAllowedBySparseSpeedDialOverrides() {
    CapturingAdapter adapter = new CapturingAdapter();
    VhrpSession session = session(adapter, Map.of("calculator", true));

    session
        .dispatch(
            new VhrpMessage.ToolsSet(
                "msg_tools",
                List.of(
                    new VhrpMessage.ToolSpec(
                        "document_read",
                        "Read a document.",
                        Map.of("type", "object", "properties", Map.of())),
                    new VhrpMessage.ToolSpec("calculator", "Calculate.", null))))
        .await()
        .indefinitely();

    assertEquals(List.of("document_read", "calculator"), adapter.registeredToolNames());
    assertEquals(
        List.of("document_read", "calculator"),
        session.textAgentToolCatalogSnapshot().stream().map(tool -> tool.name()).toList());
    assertEquals(Map.of(), session.textAgentToolCatalogSnapshot().get(1).parameters());
  }

  @Test
  void emptySpeedDialOverridesAllowCalculator() {
    CapturingAdapter adapter = new CapturingAdapter();
    VhrpSession session = session(adapter, Map.of());

    session
        .dispatch(
            new VhrpMessage.ToolsSet(
                "msg_tools",
                List.of(new VhrpMessage.ToolSpec("calculator", "Calculate.", Map.of()))))
        .await()
        .indefinitely();

    assertEquals(List.of("calculator"), adapter.registeredToolNames());
  }

  @Test
  void malformedSpeedDialOverridesDenyAllTools() {
    CapturingAdapter adapter = new CapturingAdapter();
    VhrpSession session = session(adapter, ParseResult.invalid(new IllegalArgumentException()));

    assertThrows(
        VhrpException.ProtocolBadMessage.class,
        () ->
            session
                .dispatch(
                    new VhrpMessage.ToolsSet(
                        "msg_tools",
                        List.of(new VhrpMessage.ToolSpec("calculator", "Calculate.", Map.of()))))
                .await()
                .indefinitely());
  }

  @Test
  void emptyToolsSetClearsTextAgentCatalogSnapshot() {
    CapturingAdapter adapter = new CapturingAdapter();
    VhrpSession session = session(adapter, Map.of());
    session
        .dispatch(
            new VhrpMessage.ToolsSet(
                "msg_tools",
                List.of(new VhrpMessage.ToolSpec("document_read", "Read a document.", Map.of()))))
        .await()
        .indefinitely();

    session.dispatch(new VhrpMessage.ToolsSet("msg_clear", List.of())).await().indefinitely();

    assertTrue(session.textAgentToolCatalogSnapshot().isEmpty());
    assertTrue(adapter.registeredTools.getLast().isEmpty());
  }

  @Test
  void explicitlyDisabledToolDoesNotReplaceTextAgentCatalogSnapshot() {
    CapturingAdapter adapter = new CapturingAdapter();
    VhrpSession session = session(adapter, Map.of("forbidden", false));
    session
        .dispatch(
            new VhrpMessage.ToolsSet(
                "msg_tools",
                List.of(new VhrpMessage.ToolSpec("document_read", "Read a document.", Map.of()))))
        .await()
        .indefinitely();

    try {
      session
          .dispatch(
              new VhrpMessage.ToolsSet(
                  "msg_rejected",
                  List.of(new VhrpMessage.ToolSpec("forbidden", "Forbidden.", Map.of()))))
          .await()
          .indefinitely();
    } catch (VhrpException.ProtocolBadMessage expected) {
      // Expected protocol rejection.
    }

    assertEquals(
        List.of("document_read"),
        session.textAgentToolCatalogSnapshot().stream().map(tool -> tool.name()).toList());
  }

  @Test
  void acceptsTurnImageAtEightMibLimit() {
    CapturingAdapter adapter = new CapturingAdapter();
    VhrpSession session = session(adapter, Map.of());
    byte[] maximumSizePng = pngBytes(8 * 1024 * 1024);

    session
        .dispatch(new VhrpMessage.TurnImageSubmit("msg_img", "ci_img", maximumSizePng))
        .await()
        .indefinitely();

    assertEquals(List.of(maximumSizePng), adapter.sentImages);
  }

  @Test
  void rejectsTurnImageAboveEightMibBeforeAdapterReceivesIt() {
    CapturingAdapter adapter = new CapturingAdapter();
    VhrpSession session = session(adapter, Map.of());
    byte[] oversizedPng = pngBytes(8 * 1024 * 1024 + 1);

    assertThrows(
        VhrpException.MediaUnsupportedImage.class,
        () ->
            session
                .dispatch(new VhrpMessage.TurnImageSubmit("msg_img", "ci_img", oversizedPng))
                .await()
                .indefinitely());
    assertTrue(adapter.sentImages.isEmpty());
  }

  @Test
  void rejectsMismatchedManualAudioFormatBeforeAdapterReceivesIt() {
    CapturingAdapter adapter = new CapturingAdapter();
    VhrpSession session = session(adapter, Map.of());

    assertThrows(
        VhrpException.MediaAudioFormatMismatch.class,
        () ->
            session
                .dispatch(
                    new VhrpMessage.TurnAudioSubmit(
                        "msg_audio", "ci_audio", new byte[] {0, 1}, 16000, 1, 16))
                .await()
                .indefinitely());
    assertTrue(adapter.sentAudioTurns.isEmpty());
  }

  @Test
  void rejectsUnsupportedImageBytesBeforeAdapterReceivesIt() {
    CapturingAdapter adapter = new CapturingAdapter();
    VhrpSession session = session(adapter, Map.of());

    assertThrows(
        VhrpException.MediaUnsupportedImage.class,
        () ->
            session
                .dispatch(
                    new VhrpMessage.TurnImageSubmit("msg_img", "ci_img", new byte[] {1, 2, 3}))
                .await()
                .indefinitely());
    assertTrue(adapter.sentImages.isEmpty());
  }

  private static byte[] pngBytes(int size) {
    byte[] bytes = new byte[size];
    bytes[0] = (byte) 0x89;
    bytes[1] = 0x50;
    bytes[2] = 0x4E;
    bytes[3] = 0x47;
    bytes[4] = 0x0D;
    bytes[5] = 0x0A;
    bytes[6] = 0x1A;
    bytes[7] = 0x0A;
    return bytes;
  }

  private VhrpSession session(CapturingAdapter adapter, Map<String, Boolean> toolOverrides) {
    return session(adapter, ParseResult.valid(toolOverrides));
  }

  private VhrpSession session(CapturingAdapter adapter, ParseResult toolOverrides) {
    return new VhrpSession(
        "s_test",
        "t_test",
        new VhrpCborCodec(),
        adapter,
        7L,
        LocalDateTime.now(),
        "speed_dial",
        "voice_agent",
        toolOverrides);
  }

  private static final class CapturingAdapter implements RealtimeAdapter {
    private final List<List<RealtimeAdapterModels.ToolDefinition>> registeredTools =
        new ArrayList<>();
    private final List<byte[]> sentAudioTurns = new ArrayList<>();
    private final List<byte[]> sentImages = new ArrayList<>();

    List<String> registeredToolNames() {
      if (registeredTools.isEmpty()) {
        return List.of();
      }
      return registeredTools.getLast().stream()
          .map(RealtimeAdapterModels.ToolDefinition::name)
          .toList();
    }

    @Override
    public Uni<Void> connect(String voice, String instructions) {
      return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Void> dispose() {
      return Uni.createFrom().voidItem();
    }

    @Override
    public RealtimeAdapterModels.ConnectionState connectionState() {
      return RealtimeAdapterModels.ConnectionState.idle();
    }

    @Override
    public Multi<RealtimeAdapterModels.ConnectionState> connectionStateUpdates() {
      return Multi.createFrom().empty();
    }

    @Override
    public Multi<RealtimeAdapterModels.Error> errors() {
      return Multi.createFrom().empty();
    }

    @Override
    public RealtimeThread thread() {
      return new RealtimeThread("conversation");
    }

    @Override
    public Multi<RealtimeAdapterModels.ThreadPatchOps> threadPatches() {
      return Multi.createFrom().empty();
    }

    @Override
    public String conversationId() {
      return "conversation";
    }

    @Override
    public Set<String> supportedExtensions() {
      return Set.of();
    }

    @Override
    public void pushLiveAudioChunk(byte[] pcm) {}

    @Override
    public Uni<Void> setAudioTurnMode(RealtimeAdapterModels.AudioTurnMode mode) {
      return Uni.createFrom().voidItem();
    }

    @Override
    public Multi<RealtimeAdapterModels.AssistantAudioFrame> assistantAudioStream() {
      return Multi.createFrom().empty();
    }

    @Override
    public Multi<RealtimeAdapterModels.AssistantAudioFrame> assistantAudioCompleted() {
      return Multi.createFrom().empty();
    }

    @Override
    public boolean isUserSpeaking() {
      return false;
    }

    @Override
    public Multi<Boolean> isUserSpeakingUpdates() {
      return Multi.createFrom().empty();
    }

    @Override
    public Uni<Void> registerTools(List<RealtimeAdapterModels.ToolDefinition> tools) {
      registeredTools.add(List.copyOf(tools));
      return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Void> setInstructions(String instructions) {
      return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Boolean> applyProviderExtension(String extensionType, Map<String, Object> payload) {
      return Uni.createFrom().item(true);
    }

    @Override
    public Uni<String> sendAudioOneShot(byte[] audioBytes) {
      sentAudioTurns.add(audioBytes);
      return Uni.createFrom().item("audio_item");
    }

    @Override
    public Uni<String> sendText(String text) {
      return Uni.createFrom().item("text_item");
    }

    @Override
    public Uni<String> sendImage(byte[] imageBytes) {
      sentImages.add(imageBytes);
      return Uni.createFrom().item("image_item");
    }

    @Override
    public Uni<String> sendFunctionOutput(
        String callId,
        String output,
        RealtimeAdapterModels.ToolOutputDisposition disposition,
        String errorMessage) {
      return Uni.createFrom().item("tool_item");
    }

    @Override
    public Uni<Void> interrupt() {
      return Uni.createFrom().voidItem();
    }
  }
}
