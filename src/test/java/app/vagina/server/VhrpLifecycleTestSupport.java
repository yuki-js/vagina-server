package app.vagina.server;

import app.vagina.server.realtime.ConfigRealtimeAdapterFactory;
import app.vagina.server.realtime.RealtimeAdapter;
import app.vagina.server.realtime.RealtimeAdapterFactory;
import app.vagina.server.realtime.model.RealtimeAdapterModels;
import app.vagina.server.realtime.model.RealtimeThread;
import io.quarkus.test.junit.QuarkusMock;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

final class VhrpLifecycleTestSupport {

  private VhrpLifecycleTestSupport() {}

  static void installSuccessfulAdapterFactory() {
    QuarkusMock.installMockForType(
        new TestAdapterFactory(modelId -> new SuccessfulAdapter()),
        ConfigRealtimeAdapterFactory.class);
  }

  static FailingConnectAdapter installFailingConnectAdapterFactory() {
    FailingConnectAdapter adapter = new FailingConnectAdapter();
    QuarkusMock.installMockForType(
        new TestAdapterFactory(modelId -> adapter), ConfigRealtimeAdapterFactory.class);
    return adapter;
  }

  private static final class TestAdapterFactory extends ConfigRealtimeAdapterFactory {
    private final RealtimeAdapterFactory delegate;

    TestAdapterFactory(RealtimeAdapterFactory delegate) {
      this.delegate = delegate;
    }

    @Override
    public RealtimeAdapter create(String modelId)
        throws RealtimeAdapterFactory.UnknownModelException {
      return delegate.create(modelId);
    }
  }

  static final class FailingConnectAdapter extends BaseAdapter {
    final AtomicInteger connectCalls = new AtomicInteger();
    final AtomicInteger disposeCalls = new AtomicInteger();

    @Override
    public Uni<Void> connect(String voice, String instructions) {
      connectCalls.incrementAndGet();
      return Uni.createFrom().failure(new IllegalStateException("test adapter connect failure"));
    }

    @Override
    public Uni<Void> dispose() {
      disposeCalls.incrementAndGet();
      return Uni.createFrom().voidItem();
    }
  }

  private static final class SuccessfulAdapter extends BaseAdapter {
    @Override
    public Uni<Void> connect(String voice, String instructions) {
      return Uni.createFrom().voidItem();
    }
  }

  private abstract static class BaseAdapter implements RealtimeAdapter {
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
      return new RealtimeThread("test-thread");
    }

    @Override
    public Multi<RealtimeAdapterModels.ThreadPatchOps> threadPatches() {
      return Multi.createFrom().empty();
    }

    @Override
    public String conversationId() {
      return "test-conversation";
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
      return Uni.createFrom().item("test-audio-item");
    }

    @Override
    public Uni<String> sendText(String text) {
      return Uni.createFrom().item("test-text-item");
    }

    @Override
    public Uni<String> sendImage(byte[] imageBytes) {
      return Uni.createFrom().item("test-image-item");
    }

    @Override
    public Uni<String> sendFunctionOutput(
        String callId,
        String output,
        RealtimeAdapterModels.ToolOutputDisposition disposition,
        String errorMessage) {
      return Uni.createFrom().item("test-tool-output-item");
    }

    @Override
    public Uni<Void> interrupt() {
      return Uni.createFrom().voidItem();
    }
  }
}
