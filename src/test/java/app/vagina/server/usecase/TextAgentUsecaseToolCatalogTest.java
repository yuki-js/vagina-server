package app.vagina.server.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import app.vagina.server.entity.TextAgentDefinition;
import app.vagina.server.realtime.VhrpSession;
import app.vagina.server.realtime.VhrpSessionRegistry;
import app.vagina.server.service.TextAgentModelRegistryService;
import app.vagina.server.service.TextAgentService;
import app.vagina.server.textagent.TextAgentAdapter;
import app.vagina.server.textagent.TextAgentAdapterFactory;
import app.vagina.server.textagent.TextAgentRuntimeModels.ProviderContext;
import app.vagina.server.textagent.TextAgentRuntimeModels.ProviderSessionState;
import app.vagina.server.textagent.TextAgentRuntimeModels.QueryCommand;
import app.vagina.server.textagent.TextAgentRuntimeModels.QueryResult;
import app.vagina.server.textagent.TextAgentRuntimeModels.TextAgentModelBinding;
import app.vagina.server.textagent.TextAgentRuntimeModels.ToolCatalogEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TextAgentUsecaseToolCatalogTest {

  @Test
  void queryTextAgentCanUseToolEvenWhenVoiceAgentSpeedDialDoesNotExposeIt() {
    Fixture fixture =
        fixture("{\"document_read\":true,\"calculator\":false}", List.of("calculator"));

    fixture.usecase.queryTextAgent(7L, "ta_contract", command());

    assertEquals(List.of("document_read", "list_available_agents"), fixture.capturedToolNames());
    verify(fixture.session(), never()).textAgentToolCatalogSnapshot();
  }

  @Test
  void queryTextAgentCanDisableToolEvenWhenVoiceAgentSpeedDialExposesIt() {
    Fixture fixture =
        fixture("{\"document_read\":false,\"calculator\":true}", List.of("document_read"));

    fixture.usecase.queryTextAgent(7L, "ta_contract", command());

    assertEquals(List.of("calculator", "list_available_agents"), fixture.capturedToolNames());
  }

  @Test
  void emptyEnabledToolsEnablesAllTextAgentCatalogToolsExceptRecursionDeniedTool() {
    Fixture fixture = fixture("{}", List.of());

    fixture.usecase.queryTextAgent(7L, "ta_contract", command());

    assertEquals(
        List.of("document_read", "calculator", "list_available_agents"),
        fixture.capturedToolNames());
  }

  @Test
  void absentEnabledToolsKeyEnablesCatalogTool() {
    Fixture fixture = fixture("{\"calculator\":false}", List.of());

    fixture.usecase.queryTextAgent(7L, "ta_contract", command());

    assertEquals(List.of("document_read", "list_available_agents"), fixture.capturedToolNames());
  }

  @Test
  void explicitFalseDisablesCatalogTool() {
    Fixture fixture = fixture("{\"document_read\":false}", List.of());

    fixture.usecase.queryTextAgent(7L, "ta_contract", command());

    assertEquals(List.of("calculator", "list_available_agents"), fixture.capturedToolNames());
  }

  @Test
  void explicitTrueEnablesCatalogTool() {
    Fixture fixture = fixture("{\"document_read\":true,\"calculator\":false}", List.of());

    fixture.usecase.queryTextAgent(7L, "ta_contract", command());

    assertEquals(List.of("document_read", "list_available_agents"), fixture.capturedToolNames());
  }

  @Test
  void malformedEnabledToolsFailsClosedToEmptyCatalog() {
    Fixture fixture = fixture("not json", List.of("document_read", "calculator"));

    fixture.usecase.queryTextAgent(7L, "ta_contract", command());

    assertEquals(List.of(), fixture.capturedToolNames());
  }

  @Test
  void queryTextAgentIsExcludedFromProviderCatalogForRecursionSafety() {
    Fixture fixture = fixture("{\"query_text_agent\":true}", List.of());

    fixture.usecase.queryTextAgent(7L, "ta_contract", command());

    assertEquals(
        List.of("document_read", "calculator", "list_available_agents"),
        fixture.capturedToolNames());
  }

  @Test
  void endCallIsExcludedFromProviderCatalogByDefaultBecauseItIsVoiceAgentAuthority() {
    Fixture fixture = fixture("{}", List.of());

    fixture.usecase.queryTextAgent(7L, "ta_contract", command());

    assertEquals(
        List.of("document_read", "calculator", "list_available_agents"),
        fixture.capturedToolNames());
  }

  @Test
  void endCallIsExcludedFromProviderCatalogEvenWhenExplicitlyEnabled() {
    Fixture fixture = fixture("{\"end_call\":true}", List.of());

    fixture.usecase.queryTextAgent(7L, "ta_contract", command());

    assertEquals(
        List.of("document_read", "calculator", "list_available_agents"),
        fixture.capturedToolNames());
  }

  @Test
  void listAvailableAgentsRemainsAvailableUnderSparseSemantics() {
    Fixture fixture = fixture("{}", List.of());

    fixture.usecase.queryTextAgent(7L, "ta_contract", command());

    assertEquals(true, fixture.capturedToolNames().contains("list_available_agents"));
  }

  private Fixture fixture(String enabledToolsJson, List<String> voiceAgentToolNames) {
    TextAgentDefinition definition =
        new TextAgentDefinition(
            null,
            7L,
            "ta_contract",
            "Contract text agent",
            "You are a test text agent.",
            null,
            "text-agent-test",
            enabledToolsJson,
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
    when(session.textAgentToolCatalogSnapshot()).thenReturn(entries(voiceAgentToolNames));

    VhrpSessionRegistry sessionRegistry = mock(VhrpSessionRegistry.class);
    when(sessionRegistry.getOwnedActiveSession("s_voice", 7L)).thenReturn(session);

    TextAgentAdapter adapter = mock(TextAgentAdapter.class);
    ArgumentCaptor<ProviderContext> contextCaptor = ArgumentCaptor.forClass(ProviderContext.class);
    when(adapter.execute(contextCaptor.capture())).thenReturn(QueryResult.completed("ok"));

    TextAgentAdapterFactory adapterFactory = mock(TextAgentAdapterFactory.class);
    when(adapterFactory.binding(
            "text-agent-test",
            TextAgentAdapterFactory.PROVIDER_OPENAI_RESPONSES,
            Optional.of("https://provider.test/v1"),
            Optional.of("test-key"),
            Optional.of("gpt-test")))
        .thenReturn(binding);
    when(adapterFactory.create(any(TextAgentModelBinding.class))).thenReturn(adapter);

    TextAgentUsecase usecase = new TextAgentUsecase();
    usecase.textAgentService = textAgentService;
    usecase.textAgentModelRegistryService = modelRegistry;
    usecase.vhrpSessionRegistry = sessionRegistry;
    usecase.textAgentAdapterFactory = adapterFactory;
    usecase.objectMapper = new ObjectMapper();
    return new Fixture(usecase, contextCaptor, session);
  }

  private List<ToolCatalogEntry> entries(List<String> names) {
    return names.stream()
        .map(name -> new ToolCatalogEntry(name, name + " description.", Map.of()))
        .toList();
  }

  private QueryCommand command() {
    return new QueryCommand(
        "s_voice",
        "req_1",
        "Use an enabled tool.",
        List.of(),
        null,
        entries(
            List.of(
                "document_read",
                "calculator",
                "end_call",
                "list_available_agents",
                "query_text_agent")));
  }

  private record Fixture(
      TextAgentUsecase usecase,
      ArgumentCaptor<ProviderContext> contextCaptor,
      VhrpSession session) {
    ProviderContext capturedContext() {
      return contextCaptor.getValue();
    }

    List<String> capturedToolNames() {
      return capturedContext().toolCatalog().stream().map(ToolCatalogEntry::name).toList();
    }
  }
}
