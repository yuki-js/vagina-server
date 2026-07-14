package app.vagina.server.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import app.vagina.server.textagent.RetryableTextAgentProviderException;
import app.vagina.server.textagent.TextAgentAdapter;
import app.vagina.server.textagent.TextAgentAdapterFactory;
import app.vagina.server.textagent.TextAgentRuntimeModels.ProviderContext;
import app.vagina.server.textagent.TextAgentRuntimeModels.ProviderSessionState;
import app.vagina.server.textagent.TextAgentRuntimeModels.ProviderStateMode;
import app.vagina.server.textagent.TextAgentRuntimeModels.QueryCommand;
import app.vagina.server.textagent.TextAgentRuntimeModels.QueryResult;
import app.vagina.server.textagent.TextAgentRuntimeModels.QueryStatus;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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
  void promptWithSameRequestIdSupersedesPendingRequest() {
    RecordingAdapter adapter =
        new RecordingAdapter(
            QueryResult.requiresTool(List.of(toolCall("tc_old"))),
            QueryResult.requiresTool(List.of(toolCall("tc_new"))));
    Fixture fixture = fixture(adapter);

    fixture.usecase.queryTextAgent(7L, "ta_contract", promptCommand("req_1"));
    QueryResult replacement =
        fixture.usecase.queryTextAgent(7L, "ta_contract", promptCommand("req_1"));

    assertEquals(QueryResult.requiresTool(List.of(toolCall("tc_new"))), replacement);
    assertEquals(Optional.of("req_1"), fixture.sessionState.activeRequestId());
    assertFalse(fixture.sessionState.hasPendingToolCall("tc_old"));
    assertTrue(fixture.sessionState.hasPendingToolCall("tc_new"));
    assertTrue(fixture.sessionState.acceptedToolResults().isEmpty());
    assertEquals(List.of("req_1", "req_1"), adapter.requestIds());
    assertEquals(2, adapter.executionCount());
  }

  @Test
  void promptWithDifferentRequestIdSupersedesPendingRequest() {
    RecordingAdapter adapter =
        new RecordingAdapter(
            QueryResult.requiresTool(List.of(toolCall("tc_old"))),
            QueryResult.requiresTool(List.of(toolCall("tc_new"))));
    Fixture fixture = fixture(adapter);

    fixture.usecase.queryTextAgent(7L, "ta_contract", promptCommand("req_old"));
    QueryResult replacement =
        fixture.usecase.queryTextAgent(7L, "ta_contract", promptCommand("req_new"));

    assertEquals(QueryResult.requiresTool(List.of(toolCall("tc_new"))), replacement);
    assertEquals(Optional.of("req_new"), fixture.sessionState.activeRequestId());
    assertFalse(fixture.sessionState.hasPendingToolCall("tc_old"));
    assertTrue(fixture.sessionState.hasPendingToolCall("tc_new"));
    assertTrue(fixture.sessionState.acceptedToolResults().isEmpty());
    assertEquals(List.of("req_old", "req_new"), adapter.requestIds());
    assertEquals(2, adapter.executionCount());
  }

  @Test
  void lateToolResultFromSupersededRequestCannotMutateReplacementRequest() {
    RecordingAdapter adapter =
        new RecordingAdapter(
            QueryResult.requiresTool(List.of(toolCall("tc_old"))),
            QueryResult.requiresTool(List.of(toolCall("tc_new"))));
    Fixture fixture = fixture(adapter);

    fixture.usecase.queryTextAgent(7L, "ta_contract", promptCommand("req_old"));
    fixture.usecase.queryTextAgent(7L, "ta_contract", promptCommand("req_new"));

    ConflictException error =
        assertThrows(
            ConflictException.class,
            () ->
                fixture.usecase.queryTextAgent(
                    7L, "ta_contract", toolResultCommand("req_old", "tc_old")));

    assertTrue(error.getMessage().contains("request id"));
    assertEquals(Optional.of("req_new"), fixture.sessionState.activeRequestId());
    assertFalse(fixture.sessionState.hasPendingToolCall("tc_old"));
    assertTrue(fixture.sessionState.hasPendingToolCall("tc_new"));
    assertTrue(fixture.sessionState.acceptedToolResults().isEmpty());
    assertEquals(2, adapter.executionCount());
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

  @Test
  void promptAdapterExceptionClearsActiveRequestAndAllowsRetry() {
    RecordingAdapter adapter =
        new RecordingAdapter(
            new IllegalStateException("provider unavailable"), QueryResult.completed("retried"));
    Fixture fixture = fixture(adapter);

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () -> fixture.usecase.queryTextAgent(7L, "ta_contract", promptCommand("req_fail")));
    assertEquals("provider unavailable", error.getMessage());
    assertEquals(Optional.empty(), fixture.sessionState.activeRequestId());
    assertTrue(fixture.sessionState.pendingToolCalls().isEmpty());

    QueryResult retried =
        fixture.usecase.queryTextAgent(7L, "ta_contract", promptCommand("req_retry"));

    assertEquals(QueryResult.completed("retried"), retried);
    assertEquals(Optional.empty(), fixture.sessionState.activeRequestId());
    assertEquals(2, adapter.executionCount());
  }

  @Test
  void finalToolResultAdapterExceptionClearsActiveRequestAndAllowsRetry() {
    RecordingAdapter adapter =
        new RecordingAdapter(
            QueryResult.requiresTool(List.of(toolCall("tc_1"))),
            new IllegalStateException("continuation unavailable"),
            QueryResult.completed("retried"));
    Fixture fixture = fixture(adapter);

    fixture.usecase.queryTextAgent(7L, "ta_contract", promptCommand("req_1"));
    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () ->
                fixture.usecase.queryTextAgent(
                    7L, "ta_contract", toolResultCommand("req_1", "tc_1")));
    assertEquals("continuation unavailable", error.getMessage());
    assertEquals(Optional.empty(), fixture.sessionState.activeRequestId());
    assertTrue(fixture.sessionState.pendingToolCalls().isEmpty());

    QueryResult retried =
        fixture.usecase.queryTextAgent(7L, "ta_contract", promptCommand("req_retry"));

    assertEquals(QueryResult.completed("retried"), retried);
    assertEquals(Optional.empty(), fixture.sessionState.activeRequestId());
    assertEquals(3, adapter.executionCount());
  }

  @Test
  void retryableFinalToolContinuationKeepsStateAndExactDuplicateRetriesProviderOnly() {
    RecordingAdapter adapter =
        new RecordingAdapter(
            QueryResult.requiresTool(List.of(toolCall("tc_1"))),
            new RetryableTextAgentProviderException("temporarily unavailable"),
            QueryResult.completed("retried"));
    Fixture fixture = fixture(adapter);

    fixture.usecase.queryTextAgent(7L, "ta_contract", promptCommand("req_1"));
    assertThrows(
        RetryableTextAgentProviderException.class,
        () ->
            fixture.usecase.queryTextAgent(7L, "ta_contract", toolResultCommand("req_1", "tc_1")));

    assertEquals(Optional.of("req_1"), fixture.sessionState.activeRequestId());
    assertFalse(fixture.sessionState.hasPendingToolCalls());
    assertEquals(List.of("tc_1"), acceptedToolResultIds(fixture.sessionState));

    QueryResult retried =
        fixture.usecase.queryTextAgent(7L, "ta_contract", toolResultCommand("req_1", "tc_1"));

    assertEquals(QueryResult.completed("retried"), retried);
    assertEquals(3, adapter.executionCount());
    assertEquals(Optional.empty(), fixture.sessionState.activeRequestId());
  }

  @Test
  void retryableFinalToolContinuationRejectsDuplicateWithDifferentOutput() {
    RecordingAdapter adapter =
        new RecordingAdapter(
            QueryResult.requiresTool(List.of(toolCall("tc_1"))),
            new RetryableTextAgentProviderException("temporarily unavailable"));
    Fixture fixture = fixture(adapter);

    fixture.usecase.queryTextAgent(7L, "ta_contract", promptCommand("req_1"));
    assertThrows(
        RetryableTextAgentProviderException.class,
        () ->
            fixture.usecase.queryTextAgent(7L, "ta_contract", toolResultCommand("req_1", "tc_1")));

    QueryCommand changedOutput =
        new QueryCommand(
            "s_voice",
            "req_1",
            null,
            List.of(),
            new ToolResultSubmission("tc_1", "changed", false),
            List.of());
    assertThrows(
        ConflictException.class,
        () -> fixture.usecase.queryTextAgent(7L, "ta_contract", changedOutput));
    assertEquals(2, adapter.executionCount());
  }

  @Test
  void concurrentQueryFailsFastWithoutMutatingStateAndGateReopensAfterSuccess() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    RecordingAdapter adapter =
        new RecordingAdapter(QueryResult.completed("first"), QueryResult.completed("third"));
    adapter.executeEntered = entered;
    adapter.executeRelease = release;
    Fixture fixture = fixture(adapter);
    AtomicReference<QueryResult> firstResult = new AtomicReference<>();
    AtomicReference<Throwable> firstFailure = new AtomicReference<>();
    Thread first =
        new Thread(
            () -> {
              try {
                firstResult.set(
                    fixture.usecase.queryTextAgent(7L, "ta_contract", promptCommand("req_first")));
              } catch (Throwable error) {
                firstFailure.set(error);
              }
            });
    first.start();
    assertTrue(entered.await(2, TimeUnit.SECONDS));

    ConflictException conflict =
        assertThrows(
            ConflictException.class,
            () -> fixture.usecase.queryTextAgent(7L, "ta_contract", promptCommand("req_conflict")));
    assertTrue(conflict.getMessage().contains("already in progress"));
    assertEquals(Optional.of("req_first"), fixture.sessionState.activeRequestId());
    assertEquals(1, adapter.executionCount());

    release.countDown();
    first.join(2_000);
    assertFalse(first.isAlive());
    assertEquals(null, firstFailure.get());
    assertEquals(QueryResult.completed("first"), firstResult.get());

    QueryResult third =
        fixture.usecase.queryTextAgent(7L, "ta_contract", promptCommand("req_third"));
    assertEquals(QueryResult.completed("third"), third);
    assertEquals(2, adapter.executionCount());
  }

  @Test
  void gateReopensAfterProviderException() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    RecordingAdapter adapter =
        new RecordingAdapter(new IllegalStateException("failed"), QueryResult.completed("retried"));
    adapter.executeEntered = entered;
    adapter.executeRelease = release;
    Fixture fixture = fixture(adapter);
    AtomicReference<Throwable> firstFailure = new AtomicReference<>();
    Thread first =
        new Thread(
            () -> {
              try {
                fixture.usecase.queryTextAgent(7L, "ta_contract", promptCommand("req_fail"));
              } catch (Throwable error) {
                firstFailure.set(error);
              }
            });
    first.start();
    assertTrue(entered.await(2, TimeUnit.SECONDS));
    assertThrows(
        ConflictException.class,
        () -> fixture.usecase.queryTextAgent(7L, "ta_contract", promptCommand("req_conflict")));
    release.countDown();
    first.join(2_000);
    assertNotNull(firstFailure.get());
    assertEquals(Optional.empty(), fixture.sessionState.activeRequestId());

    QueryResult retried =
        fixture.usecase.queryTextAgent(7L, "ta_contract", promptCommand("req_retry"));
    assertEquals(QueryResult.completed("retried"), retried);
  }

  @Test
  void terminalToolFailureAttemptsRecoveryBeforeClearingRequestState() {
    RecordingAdapter adapter =
        new RecordingAdapter(
            QueryResult.requiresTool(List.of(toolCall("tc_1"))),
            QueryResult.failed("provider_request_error", "rejected"));
    adapter.recoveryResult = true;
    Fixture fixture = fixture(adapter);

    fixture.usecase.queryTextAgent(7L, "ta_contract", promptCommand("req_1"));
    QueryResult failed =
        fixture.usecase.queryTextAgent(7L, "ta_contract", toolResultCommand("req_1", "tc_1"));

    assertEquals(QueryStatus.FAILED, failed.status());
    assertEquals(1, adapter.recoveryCount);
    assertEquals(List.of("tc_1"), adapter.acceptedToolResultIdsAtRecovery);
    assertEquals(Optional.empty(), fixture.sessionState.activeRequestId());
  }

  private List<String> acceptedToolResultIds(ProviderSessionState state) {
    return state.acceptedToolResults().stream().map(ToolResultSubmission::toolCallId).toList();
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
            "https://provider.test/v1",
            "test-key",
            "gpt-test",
            null,
            null);
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
                "https://provider.test/v1",
                "test-key",
                "gpt-test",
                null,
                null));

    VhrpSession session = mock(VhrpSession.class);
    when(session.textAgentProviderState("ta_contract", binding)).thenReturn(sessionState);

    VhrpSessionRegistry sessionRegistry = mock(VhrpSessionRegistry.class);
    when(sessionRegistry.getOwnedActiveSession("s_voice", 7L)).thenReturn(session);

    TextAgentAdapterFactory adapterFactory = mock(TextAgentAdapterFactory.class);
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
    private final Queue<Object> results = new ArrayDeque<>();
    private final List<ProviderContext> contexts = new ArrayList<>();
    private final List<List<String>> acceptedToolResultIdsByExecution = new ArrayList<>();
    private boolean recoveryResult;
    private int recoveryCount;
    private List<String> acceptedToolResultIdsAtRecovery = List.of();
    private CountDownLatch executeEntered;
    private CountDownLatch executeRelease;

    RecordingAdapter(Object... results) {
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
      if (executeEntered != null) {
        executeEntered.countDown();
        try {
          if (!executeRelease.await(2, TimeUnit.SECONDS)) {
            throw new AssertionError("Timed out waiting to release adapter execution");
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new AssertionError(e);
        } finally {
          executeEntered = null;
          executeRelease = null;
        }
      }
      acceptedToolResultIdsByExecution.add(
          context.sessionState().acceptedToolResults().stream()
              .map(ToolResultSubmission::toolCallId)
              .toList());
      if (results.isEmpty()) {
        throw new AssertionError("Unexpected text agent adapter execution");
      }
      Object result = results.remove();
      if (result instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      if (result instanceof Error error) {
        throw error;
      }
      return (QueryResult) result;
    }

    @Override
    public boolean recoverTerminalToolFailure(ProviderContext context) {
      recoveryCount++;
      acceptedToolResultIdsAtRecovery =
          context.sessionState().acceptedToolResults().stream()
              .map(ToolResultSubmission::toolCallId)
              .toList();
      return recoveryResult;
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
