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
import app.vagina.server.textagent.TextAgentRuntimeModels.TextAgentModelBinding;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TextAgentAdapterFactoryTest {

  @Test
  void factorySelectsBothOpenAiProviderShapes() {
    TextAgentAdapterFactory factory = factory();

    TextAgentAdapter chatAdapter =
        factory.create(TextAgentAdapterFactory.PROVIDER_OPENAI_CHAT_COMPLETIONS);
    TextAgentAdapter responsesAdapter =
        factory.create(TextAgentAdapterFactory.PROVIDER_OPENAI_RESPONSES);

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
  void chatCompletionsKeepsProviderVisibleMessagesInProviderState() {
    TextAgentAdapterFactory factory = factory();
    TextAgentAdapter adapter =
        factory.create(TextAgentAdapterFactory.PROVIDER_OPENAI_CHAT_COMPLETIONS);
    ProviderContext context =
        context(
            TextAgentAdapterFactory.PROVIDER_OPENAI_CHAT_COMPLETIONS,
            new QueryCommand("s_voice", "req_prompt", "summarize", List.of(), null, List.of()));

    ((OpenAiChatCompletionsTextAgentAdapter) adapter).previewRequestBody(context);

    assertTrue(context.sessionState().providerState().containsKey("messages"));
    Object rawMessages = context.sessionState().providerState().get("messages");
    assertInstanceOf(List.class, rawMessages);
  }

  @Test
  void responsesKeepsContinuationStateSeparateFromChatMessages() {
    OpenAiResponsesTextAgentAdapter adapter =
        new OpenAiResponsesTextAgentAdapter(new ObjectMapper());
    ProviderContext context =
        context(
            TextAgentAdapterFactory.PROVIDER_OPENAI_RESPONSES,
            new QueryCommand("s_voice", "req_prompt", "summarize", List.of(), null, List.of()));

    String body = adapter.previewRequestBody(context);
    adapter.rememberPreviousResponseId(context, "resp_123");

    assertTrue(body.contains("summarize"));
    assertEquals("resp_123", adapter.previousResponseId(context));
    assertFalse(context.sessionState().providerState().containsKey("messages"));
  }

  @Test
  void sessionStateTracksPendingToolCallsForConflictValidationLayer() {
    ProviderSessionState state =
        new ProviderSessionState(
            "ta_test", binding(TextAgentAdapterFactory.PROVIDER_OPENAI_RESPONSES));

    state.replacePendingToolCalls(
        List.of(new TextAgentRuntimeModels.ToolCall("tc_1", "document_read", "{}")));

    assertTrue(state.hasPendingToolCalls());
    assertTrue(state.hasPendingToolCall("tc_1"));
    assertFalse(state.hasPendingToolCall("tc_missing"));
    assertTrue(
        state.acceptPendingToolResult(
            new TextAgentRuntimeModels.ToolResultSubmission("tc_1", "{}", false)));
    assertFalse(state.hasPendingToolCalls());
    assertEquals(1, state.acceptedToolResults().size());
    state.clearPendingToolCalls();
    assertFalse(state.hasPendingToolCalls());
    assertTrue(state.acceptedToolResults().isEmpty());
  }

  private TextAgentAdapterFactory factory() {
    TextAgentAdapterFactory factory = new TextAgentAdapterFactory();
    factory.objectMapper = new ObjectMapper();
    return factory;
  }

  private ProviderContext context(String provider, QueryCommand command) {
    TextAgentDefinition definition =
        new TextAgentDefinition(
            null,
            null,
            "ta_test",
            "Test text agent",
            "You are a test text agent.",
            null,
            "text-agent-test",
            "{}",
            null,
            null);
    ProviderSessionState state = new ProviderSessionState("ta_test", binding(provider));
    return new ProviderContext(definition.toTextAgentProviderView(), command, state);
  }

  private TextAgentModelBinding binding(String provider) {
    return new TextAgentModelBinding(
        "text-agent-test",
        provider,
        "https://api.openai.test/v1",
        "test-key",
        "gpt-test",
        null,
        null);
  }

  @SuppressWarnings("unused")
  private Map<String, Object> stateMap(ProviderContext context) {
    return context.sessionState().providerState();
  }
}
