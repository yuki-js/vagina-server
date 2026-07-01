package app.vagina.server.textagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.vagina.server.entity.TextAgentDefinition;
import app.vagina.server.textagent.TextAgentRuntimeModels.ProviderContext;
import app.vagina.server.textagent.TextAgentRuntimeModels.ProviderSessionState;
import app.vagina.server.textagent.TextAgentRuntimeModels.ProviderStateMode;
import app.vagina.server.textagent.TextAgentRuntimeModels.QueryCommand;
import app.vagina.server.textagent.TextAgentRuntimeModels.QueryResult;
import app.vagina.server.textagent.TextAgentRuntimeModels.QueryStatus;
import app.vagina.server.textagent.TextAgentRuntimeModels.TextAgentModelBinding;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TextAgentAdapterFactoryTest {

  @Test
  void factorySelectsBothOpenAiProviderShapes() {
    TextAgentAdapterFactory factory = factory();

    TextAgentAdapter chatAdapter = factory.create(TextAgentAdapterFactory.PROVIDER_OPENAI_CHAT_COMPLETIONS);
    TextAgentAdapter responsesAdapter = factory.create(TextAgentAdapterFactory.PROVIDER_OPENAI_RESPONSES);

    assertInstanceOf(OpenAiChatCompletionsTextAgentAdapter.class, chatAdapter);
    assertInstanceOf(OpenAiResponsesTextAgentAdapter.class, responsesAdapter);
    assertEquals(ProviderStateMode.STATELESS_CONTINUATION, chatAdapter.stateMode());
    assertEquals(ProviderStateMode.STATEFUL_CONTINUATION, responsesAdapter.stateMode());
    assertEquals(
        List.of(
            TextAgentAdapterFactory.PROVIDER_OPENAI_CHAT_COMPLETIONS,
            TextAgentAdapterFactory.PROVIDER_OPENAI_RESPONSES),
        factory.supportedProviders());
  }

  @Test
  void chatCompletionsSkeletonKeepsProviderVisibleMessagesInProviderState() {
    TextAgentAdapterFactory factory = factory();
    TextAgentAdapter adapter = factory.create(TextAgentAdapterFactory.PROVIDER_OPENAI_CHAT_COMPLETIONS);
    ProviderContext context =
        context(
            TextAgentAdapterFactory.PROVIDER_OPENAI_CHAT_COMPLETIONS,
            new QueryCommand("s_voice", "req_prompt", "summarize", null));

    QueryResult result = adapter.execute(context);

    assertEquals(QueryStatus.FAILED, result.status());
    assertEquals("provider_not_implemented", result.error().code());
    assertTrue(context.sessionState().providerState().containsKey("messages"));
    Object rawMessages = context.sessionState().providerState().get("messages");
    assertInstanceOf(List.class, rawMessages);
    assertFalse(((List<?>) rawMessages).isEmpty());
  }

  @Test
  void responsesSkeletonKeepsContinuationStateSeparateFromChatMessages() {
    OpenAiResponsesTextAgentAdapter adapter =
        new OpenAiResponsesTextAgentAdapter(new ObjectMapper());
    ProviderContext context =
        context(
            TextAgentAdapterFactory.PROVIDER_OPENAI_RESPONSES,
            new QueryCommand("s_voice", "req_prompt", "summarize", null));

    QueryResult result = adapter.execute(context);
    adapter.rememberPreviousResponseId(context, "resp_123");

    assertEquals(QueryStatus.FAILED, result.status());
    assertEquals("provider_not_implemented", result.error().code());
    assertEquals("req_prompt", context.sessionState().providerState().get("last_request_id"));
    assertEquals("resp_123", adapter.previousResponseId(context));
    assertFalse(context.sessionState().providerState().containsKey("messages"));
  }

  @Test
  void sessionStateTracksPendingToolCallsForConflictValidationLayer() {
    ProviderSessionState state =
        new ProviderSessionState("ta_test", binding(TextAgentAdapterFactory.PROVIDER_OPENAI_RESPONSES));

    state.replacePendingToolCalls(
        List.of(new TextAgentRuntimeModels.ToolCall("tc_1", "document_read", "{}")));

    assertTrue(state.hasPendingToolCalls());
    assertTrue(state.hasPendingToolCall("tc_1"));
    assertFalse(state.hasPendingToolCall("tc_missing"));
    state.clearPendingToolCalls();
    assertFalse(state.hasPendingToolCalls());
  }

  private TextAgentAdapterFactory factory() {
    TextAgentAdapterFactory factory = new TextAgentAdapterFactory();
    factory.objectMapper = new ObjectMapper();
    return factory;
  }

  private ProviderContext context(String provider, QueryCommand command) {
    TextAgentDefinition definition = new TextAgentDefinition();
    definition.setTextAgentId("ta_test");
    definition.setTextModelId("text-agent-test");
    definition.setPrompt("You are a test text agent.");
    ProviderSessionState state = new ProviderSessionState("ta_test", binding(provider));
    return new ProviderContext(definition, command, state);
  }

  private TextAgentModelBinding binding(String provider) {
    return new TextAgentModelBinding(
        "text-agent-test",
        provider,
        Optional.of("https://api.openai.test/v1"),
        Optional.of("test-key"),
        Optional.of("gpt-test"));
  }

  @SuppressWarnings("unused")
  private Map<String, Object> stateMap(ProviderContext context) {
    return context.sessionState().providerState();
  }
}
