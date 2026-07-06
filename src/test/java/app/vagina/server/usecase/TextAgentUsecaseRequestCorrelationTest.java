package app.vagina.server.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import app.vagina.server.domain.error.ConflictException;
import app.vagina.server.entity.TextAgentDefinition;
import app.vagina.server.realtime.VhrpSession;
import app.vagina.server.realtime.VhrpSessionRegistry;
import app.vagina.server.service.TextAgentModelRegistryService;
import app.vagina.server.service.TextAgentService;
import app.vagina.server.textagent.TextAgentAdapter;
import app.vagina.server.textagent.TextAgentAdapterFactory;
import app.vagina.server.textagent.TextAgentRuntimeModels.ProviderContext;
import app.vagina.server.textagent.TextAgentRuntimeModels.ProviderSessionState;
import app.vagina.server.textagent.TextAgentRuntimeModels.ProviderStateMode;
import app.vagina.server.textagent.TextAgentRuntimeModels.QueryCommand;
import app.vagina.server.textagent.TextAgentRuntimeModels.QueryResult;
import app.vagina.server.textagent.TextAgentRuntimeModels.TextAgentModelBinding;
import app.vagina.server.textagent.TextAgentRuntimeModels.ToolCall;
import app.vagina.server.textagent.TextAgentRuntimeModels.ToolResultSubmission;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import org.junit.jupiter.api.Test;

class TextAgentUsecaseRequestCorrelationTest {

  @Test
  void promptStartsActiveRequestAndRequiresToolPreservesRequestAndPendingCalls() {
    RecordingAdapter adapter =
        new RecordingAdapter(QueryResult.requiresTool(List.of(toolCall("tc_1"))));
    Fixture fixture = fixture(adapter);

    fixture.usecase.queryTextAgent(7L, "ta_contract", promptCommand("req_1"));

    assertEquals(Optional.of("req_1"), fixture.sessionState.activeRequestId());
    assertTrue(fixture.sessionState.hasPendingToolCall("tc_1"));
  }

  @Test
  void promptWithSameRequestIdWhilePendingConflicts() {
    RecordingAdapter adapter =
        new RecordingAdapter(QueryResult.requiresTool(List.of(toolCall("tc_1"))));
    Fixture fixture = fixture(adapter);

    fixture.usecase.queryTextAgent(7L, "ta_contract", promptCommand("req_1"));

    ConflictException error =
        assertThrows(
            ConflictException.class,
            () -> fixture.usecase.queryTextAgent(7L, "ta_contract", promptCommand("req_1")));
    assertTrue(error.getMessage().contains("pending tool result"));
    assertEquals(1, adapter.executionCount());
  }

  @Test
  void promptWithDifferentRequestIdWhilePendingConflictsAndDoesNotMutateProviderState() {
    RecordingAdapter adapter =
        new RecordingAdapter(QueryResult.requiresTool(List.of(toolCall("tc_old"))));
    Fixture fixture = fixture(adapter);

    fixture.usecase.queryTextAgent(7L, "ta_contract", promptCommand("req_old"));

    ConflictException error =
        assertThrows(
            ConflictException.class,
            () -> fixture.usecase.queryTextAgent(7L, "ta_contract", promptCommand("req_new")));

    assertTrue(error.getMessage().contains("pending tool results"));
    assertEquals(Optional.of("req_old"), fixture.sessionState.activeRequestId());
    assertTrue(fixture.sessionState.hasPendingToolCall("tc_old"));
    assertEquals(List.of("req_old"), adapter.requestIds());
    assertEquals(1, adapter.executionCount());
  }

  @Test
  void toolResultWithMismatchedRequestIdConflictsBeforeAdapterExecution() {
    RecordingAdapter adapter =
        new RecordingAdapter(QueryResult.requiresTool(List.of(toolCall("tc_1"))));
    Fixture fixture = fixture(adapter);

    fixture.usecase.queryTextAgent(7L, "ta_contract", promptCommand("req_1"));

    ConflictException error =
        assertThrows(
            ConflictException.class,
            () ->
                fixture.usecase.queryTextAgent(
                    7L, "ta_contract", toolResultCommand("req_2", "tc_1")));
    assertTrue(error.getMessage().contains("request id"));
    assertEquals(1, adapter.executionCount());
  }

  @Test
  void duplicatePartialToolResultConflictsBeforeAdapterExecution() {
    RecordingAdapter adapter =
        new RecordingAdapter(QueryResult.requiresTool(List.of(toolCall("tc_1"), toolCall("tc_2"))));
    Fixture fixture = fixture(adapter);

    fixture.usecase.queryTextAgent(7L, "ta_contract", promptCommand("req_1"));
    fixture.usecase.queryTextAgent(7L, "ta_contract", toolResultCommand("req_1", "tc_1"));

    ConflictException error =
        assertThrows(
            ConflictException.class,
            () ->
                fixture.usecase.queryTextAgent(
                    7L, "ta_contract", toolResultCommand("req_1", "tc_1")));
    assertTrue(error.getMessage().contains("duplicate tool result"));
    assertEquals(1, adapter.executionCount());
  }

  @Test
  void providerSessionStateCanClearExpiredPendingRequest() {
    RecordingAdapter adapter =
        new RecordingAdapter(QueryResult.requiresTool(List.of(toolCall("tc_expiring"))));
    Fixture fixture = fixture(adapter);

    fixture.usecase.queryTextAgent(7L, "ta_contract", promptCommand("req_expiring"));

    assertTrue(
        fixture.sessionState.clearExpiredRequest(Duration.ZERO, Instant.now().plusMillis(1)));
    assertEquals(Optional.empty(), fixture.sessionState.activeRequestId());
    assertTrue(fixture.sessionState.pendingToolCalls().isEmpty());
    assertTrue(fixture.sessionState.acceptedToolResults().isEmpty());
  }

  @Test
  void firstOfMultiplePendingToolResultsIsAcceptedWithoutAdapterContinuation() {
    RecordingAdapter adapter =
        new RecordingAdapter(
            QueryResult.requiresTool(List.of(toolCall("tc_first"), toolCall("tc_second"))),
            QueryResult.completed("done"));
    Fixture fixture = fixture(adapter);

    QueryResult firstResult =
        fixture.usecase.queryTextAgent(7L, "ta_contract", promptCommand("req_1"));
    QueryResult waitingResult =
        fixture.usecase.queryTextAgent(7L, "ta_contract", toolResultCommand("req_1", "tc_first"));

    assertEquals(
        QueryResult.requiresTool(List.of(toolCall("tc_first"), toolCall("tc_second"))),
        firstResult);
    assertEquals(QueryResult.requiresTool(List.of(toolCall("tc_second"))), waitingResult);
    assertEquals(Optional.of("req_1"), fixture.sessionState.activeRequestId());
    assertTrue(fixture.sessionState.hasPendingToolCall("tc_second"));
    assertEquals(
        List.of("tc_first"),
        fixture.sessionState.acceptedToolResults().stream()
            .map(ToolResultSubmission::toolCallId)
            .toList());
    assertEquals(1, adapter.executionCount());
  }

  @Test
  void finalOfMultiplePendingToolResultsTriggersAdapterContinuationWithAllAcceptedResults() {
    RecordingAdapter adapter =
        new RecordingAdapter(
            QueryResult.requiresTool(List.of(toolCall("tc_first"), toolCall("tc_second"))),
            QueryResult.completed("done"));
    Fixture fixture = fixture(adapter);

    fixture.usecase.queryTextAgent(7L, "ta_contract", promptCommand("req_1"));
    fixture.usecase.queryTextAgent(7L, "ta_contract", toolResultCommand("req_1", "tc_first"));
    QueryResult completed =
        fixture.usecase.queryTextAgent(7L, "ta_contract", toolResultCommand("req_1", "tc_second"));

    assertEquals(QueryResult.completed("done"), completed);
    assertEquals(2, adapter.executionCount());
    assertEquals(List.of("req_1", "req_1"), adapter.requestIds());
    assertEquals(
        List.of(List.of(), List.of("tc_first", "tc_second")),
        adapter.acceptedToolResultIdsByExecution());
    assertEquals(Optional.empty(), fixture.sessionState.activeRequestId());
    assertTrue(fixture.sessionState.pendingToolCalls().isEmpty());
    assertTrue(fixture.sessionState.acceptedToolResults().isEmpty());
  }

  @Test
  void lateToolResultAfterCompletedRequestConflicts() {
    RecordingAdapter adapter = new RecordingAdapter(QueryResult.completed("done"));
    Fixture fixture = fixture(adapter);

    fixture.usecase.queryTextAgent(7L, "ta_contract", promptCommand("req_1"));

    ConflictException error =
        assertThrows(
            ConflictException.class,
            () ->
                fixture.usecase.queryTextAgent(
                    7L, "ta_contract", toolResultCommand("req_1", "tc_late")));
    assertTrue(error.getMessage().contains("active request"));
    assertEquals(1, adapter.executionCount());
  }

  @Test
  void lateToolResultWithDifferentRequestIdWhilePendingConflicts() {
    RecordingAdapter adapter =
        new RecordingAdapter(QueryResult.requiresTool(List.of(toolCall("tc_old"))));
    Fixture fixture = fixture(adapter);

    fixture.usecase.queryTextAgent(7L, "ta_contract", promptCommand("req_old"));

    ConflictException error =
        assertThrows(
            ConflictException.class,
            () ->
                fixture.usecase.queryTextAgent(
                    7L, "ta_contract", toolResultCommand("req_new", "tc_old")));
    assertTrue(error.getMessage().contains("request id"));
    assertEquals(1, adapter.executionCount());
  }

  @Test
  void failedResultClearsActiveRequestAndPendingCalls() {
    RecordingAdapter adapter =
        new RecordingAdapter(
            QueryResult.requiresTool(List.of(toolCall("tc_1"))),
            QueryResult.failed("provider_failed", "provider failed"));
    Fixture fixture = fixture(adapter);

    fixture.usecase.queryTextAgent(7L, "ta_contract", promptCommand("req_1"));
    fixture.usecase.queryTextAgent(7L, "ta_contract", toolResultCommand("req_1", "tc_1"));

    assertEquals(Optional.empty(), fixture.sessionState.activeRequestId());
    assertTrue(fixture.sessionState.pendingToolCalls().isEmpty());
  }

  private Fixture fixture(RecordingAdapter adapter) {
    TextAgentDefinition definition =
        new TextAgentDefinition(
            null,
            7L,
            "ta_contract",
            "Contract text agent",
            "You are a test text agent.",
            null,
            "text-agent-test",
            "{}",
            null,
            null);

    TextAgentModelBinding binding =
        new TextAgentModelBinding(
            "text-agent-test",
            TextAgentAdapterFactory.PROVIDER_OPENAI_RESPONSES,
            Optional.of("https://provider.test/v1"),
            Optional.of("test-key"),
            Optional.of("gpt-test"));
    ProviderSessionState sessionState = new ProviderSessionState("ta_contract", binding);

    TextAgentService textAgentService = mock(TextAgentService.class);
    when(textAgentService.findByUserIdAndTextAgentId(7L, "ta_contract"))
        .thenReturn(Optional.of(definition));

    TextAgentModelRegistryService modelRegistry = mock(TextAgentModelRegistryService.class);
    when(modelRegistry.getModelConfig("text-agent-test"))
        .thenReturn(
            new TextAgentModelRegistryService.TextAgentModelConfigView(
                "text-agent-test",
                TextAgentAdapterFactory.PROVIDER_OPENAI_RESPONSES,
                Optional.of("https://provider.test/v1"),
                Optional.of("test-key"),
                Optional.of("gpt-test")));

    VhrpSession session = mock(VhrpSession.class);
    when(session.textAgentProviderState("ta_contract", binding)).thenReturn(sessionState);

    VhrpSessionRegistry sessionRegistry = mock(VhrpSessionRegistry.class);
    when(sessionRegistry.getOwnedActiveSession("s_voice", 7L)).thenReturn(session);

    TextAgentAdapterFactory adapterFactory = mock(TextAgentAdapterFactory.class);
    when(adapterFactory.binding(
            "text-agent-test",
            TextAgentAdapterFactory.PROVIDER_OPENAI_RESPONSES,
            Optional.of("https://provider.test/v1"),
            Optional.of("test-key"),
            Optional.of("gpt-test")))
        .thenReturn(binding);
    when(adapterFactory.create(binding)).thenReturn(adapter);

    TextAgentUsecase usecase = new TextAgentUsecase();
    usecase.textAgentService = textAgentService;
    usecase.textAgentModelRegistryService = modelRegistry;
    usecase.vhrpSessionRegistry = sessionRegistry;
    usecase.textAgentAdapterFactory = adapterFactory;
    usecase.objectMapper = new ObjectMapper();
    return new Fixture(usecase, sessionState);
  }

  private QueryCommand promptCommand(String requestId) {
    return new QueryCommand("s_voice", requestId, "Use a tool.", List.of(), null, List.of());
  }

  private QueryCommand toolResultCommand(String requestId, String toolCallId) {
    return new QueryCommand(
        "s_voice",
        requestId,
        null,
        List.of(),
        new ToolResultSubmission(toolCallId, "{}", false),
        List.of());
  }

  private ToolCall toolCall(String id) {
    return new ToolCall(id, "calculator", "{}");
  }

  private record Fixture(TextAgentUsecase usecase, ProviderSessionState sessionState) {}

  private static final class RecordingAdapter implements TextAgentAdapter {
    private final Queue<QueryResult> results = new ArrayDeque<>();
    private final List<ProviderContext> contexts = new ArrayList<>();
    private final List<List<String>> acceptedToolResultIdsByExecution = new ArrayList<>();

    RecordingAdapter(QueryResult... results) {
      this.results.addAll(List.of(results));
    }

    @Override
    public String providerKey() {
      return TextAgentAdapterFactory.PROVIDER_OPENAI_RESPONSES;
    }

    @Override
    public ProviderStateMode stateMode() {
      return ProviderStateMode.STATEFUL_CONTINUATION;
    }

    @Override
    public QueryResult execute(ProviderContext context) {
      contexts.add(context);
      acceptedToolResultIdsByExecution.add(
          context.sessionState().acceptedToolResults().stream()
              .map(ToolResultSubmission::toolCallId)
              .toList());
      if (results.isEmpty()) {
        throw new AssertionError("Unexpected text agent adapter execution");
      }
      return results.remove();
    }

    int executionCount() {
      return contexts.size();
    }

    List<String> requestIds() {
      return contexts.stream().map(context -> context.command().requestId()).toList();
    }

    List<List<String>> acceptedToolResultIdsByExecution() {
      return acceptedToolResultIdsByExecution;
    }
  }
}
