package app.vagina.server.realtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.vagina.server.realtime.model.RealtimeAdapterModels;
import app.vagina.server.realtime.model.RealtimeThread;
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
  void toolsSetRetainsValidatedCatalogSnapshotForTextAgents() {
    CapturingAdapter adapter = new CapturingAdapter();
    VhrpSession session = session(adapter, Set.of("document_read", "calculator"));

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
  void emptyToolsSetClearsTextAgentCatalogSnapshot() {
    CapturingAdapter adapter = new CapturingAdapter();
    VhrpSession session = session(adapter, Set.of("document_read"));
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
  void rejectedToolsSetDoesNotReplaceTextAgentCatalogSnapshot() {
    CapturingAdapter adapter = new CapturingAdapter();
    VhrpSession session = session(adapter, Set.of("document_read"));
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

  private VhrpSession session(CapturingAdapter adapter, Set<String> allowedToolNames) {
    return new VhrpSession(
        "s_test",
        "t_test",
        new VhrpCborCodec(),
        adapter,
        7L,
        LocalDateTime.now(),
        "speed_dial",
        "voice_agent",
        allowedToolNames);
  }

  private static final class CapturingAdapter implements RealtimeAdapter {
    private final List<List<RealtimeAdapterModels.ToolDefinition>> registeredTools =
        new ArrayList<>();

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
      return Uni.createFrom().item("audio_item");
    }

    @Override
    public Uni<String> sendText(String text) {
      return Uni.createFrom().item("text_item");
    }

    @Override
    public Uni<String> sendImage(byte[] imageBytes) {
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
