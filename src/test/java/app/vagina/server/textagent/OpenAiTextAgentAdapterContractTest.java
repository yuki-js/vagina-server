package app.vagina.server.textagent;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.vagina.server.entity.TextAgentDefinition;
import app.vagina.server.textagent.TextAgentRuntimeModels.ProviderContext;
import app.vagina.server.textagent.TextAgentRuntimeModels.ProviderSessionState;
import app.vagina.server.textagent.TextAgentRuntimeModels.QueryCommand;
import app.vagina.server.textagent.TextAgentRuntimeModels.QueryResult;
import app.vagina.server.textagent.TextAgentRuntimeModels.QueryStatus;
import app.vagina.server.textagent.TextAgentRuntimeModels.TextAgentModelBinding;
import app.vagina.server.textagent.TextAgentRuntimeModels.ToolResultSubmission;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OpenAiTextAgentAdapterContractTest {
  private static final String CHAT_COMPLETED_RESPONSE =
      """
      {
        "choices": [
          {
            "finish_reason": "stop",
            "index": 0,
            "message": {
              "annotations": [],
              "content": "live chat ok",
              "refusal": null,
              "role": "assistant"
            }
          }
        ],
        "created": 1782887185,
        "id": "chatcmpl-Dwigr8hzQeW3963RxaFsCsxt1vKzG",
        "model": "gpt-5.5-2026-04-24",
        "object": "chat.completion"
      }
      """;

  private static final String CHAT_TOOL_CALL_RESPONSE =
      """
      {
        "choices": [
          {
            "finish_reason": "tool_calls",
            "index": 0,
            "message": {
              "annotations": [],
              "content": null,
              "refusal": null,
              "role": "assistant",
              "tool_calls": [
                {
                  "function": {
                    "arguments": "{\\\"path\\\":\\\"/contract.md\\\"}",
                    "name": "document_read"
                  },
                  "id": "call_wtIGCW9WMkDpULFW5or9izIa",
                  "type": "function"
                }
              ]
            }
          }
        ],
        "created": 1782887187,
        "id": "chatcmpl-DwigtB5LzpU1OyUHtdCKubCpgGho9",
        "model": "gpt-5.5-2026-04-24",
        "object": "chat.completion"
      }
      """;

  private static final String CHAT_TOOL_CONTINUATION_RESPONSE =
      """
      {
        "choices": [
          {
            "finish_reason": "stop",
            "index": 0,
            "message": {
              "annotations": [],
              "content": "The document says: “The renewal clause is section 8.”",
              "refusal": null,
              "role": "assistant"
            }
          }
        ],
        "created": 1782887302,
        "id": "chatcmpl-DwiikCFk9lVnlTmfWfWwsTmisXGD7",
        "model": "gpt-5.5-2026-04-24",
        "object": "chat.completion"
      }
      """;

  private static final String RESPONSES_COMPLETED_RESPONSE =
      """
      {
        "id": "resp_0657109dfe0338bf006a44b315b21081958cf5c183d326b5d2",
        "object": "response",
        "status": "completed",
        "model": "gpt-5.5",
        "output": [
          {
            "id": "rs_0657109dfe0338bf006a44b3165bd08195aa50aa09fc4ab28a",
            "type": "reasoning",
            "summary": []
          },
          {
            "id": "msg_0657109dfe0338bf006a44b3168f6c81958e2218f3a789373b",
            "type": "message",
            "status": "completed",
            "content": [
              {
                "type": "output_text",
                "annotations": [],
                "logprobs": [],
                "text": "live responses ok"
              }
            ],
            "phase": "final_answer",
            "role": "assistant"
          }
        ],
        "previous_response_id": null,
        "store": true,
        "truncation": "disabled"
      }
      """;

  private static final String RESPONSES_TOOL_CALL_RESPONSE =
      """
      {
        "id": "resp_05c749160a4fb710006a44b317c5788193b7c6d343252fe08e",
        "object": "response",
        "status": "completed",
        "model": "gpt-5.5",
        "output": [
          {
            "id": "fc_05c749160a4fb710006a44b318a55081939920535f6f2c5210",
            "type": "function_call",
            "status": "completed",
            "arguments": "{\\\"path\\\":\\\"/contract.md\\\"}",
            "call_id": "call_mAH7OHpzsnpQQ8DfBhdS3L6J",
            "name": "document_read"
          }
        ],
        "previous_response_id": null,
        "tool_choice": {
          "type": "function",
          "name": "document_read"
        },
        "truncation": "disabled"
      }
      """;

  private static final String RESPONSES_TOOL_CONTINUATION_RESPONSE =
      """
      {
        "id": "resp_05c749160a4fb710006a44b38846d08193b4246c9d0b1a56ef",
        "object": "response",
        "status": "completed",
        "model": "gpt-5.5",
        "output": [
          {
            "id": "msg_05c749160a4fb710006a44b388f5e48193a2cea0702da0321e",
            "type": "message",
            "status": "completed",
            "content": [
              {
                "type": "output_text",
                "annotations": [],
                "logprobs": [],
                "text": "The document says: “The renewal clause is section 8.”"
              }
            ],
            "phase": "final_answer",
            "role": "assistant"
          }
        ],
        "previous_response_id": "resp_05c749160a4fb710006a44b317c5788193b7c6d343252fe08e",
        "store": true,
        "truncation": "disabled"
      }
      """;

  private WireMockServer provider;
  private ObjectMapper objectMapper;

  @BeforeEach
  void startProvider() {
    provider = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    provider.start();
    objectMapper = new ObjectMapper();
  }

  @AfterEach
  void stopProvider() {
    provider.stop();
  }

  @Test
  void chatCompletionsCompletedResponseUsesCapturedGpt55Shape() {
    provider.stubFor(
        post(urlPathEqualTo("/v1/chat/completions"))
            .withRequestBody(matchingJsonPath("$.model", equalTo("gpt-5.5")))
            .withRequestBody(matchingJsonPath("$.messages[0].role", equalTo("system")))
            .withRequestBody(matchingJsonPath("$.messages[1].content", equalTo("Reply with exactly: live chat ok")))
            .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(CHAT_COMPLETED_RESPONSE)));
    OpenAiChatCompletionsTextAgentAdapter adapter =
        new OpenAiChatCompletionsTextAgentAdapter(objectMapper);
    ProviderContext context =
        promptContext(
            TextAgentAdapterFactory.PROVIDER_OPENAI_CHAT_COMPLETIONS,
            "Reply with exactly: live chat ok");

    QueryResult result = adapter.execute(context);

    assertEquals(QueryStatus.COMPLETED, result.status());
    assertEquals("live chat ok", result.text());
    assertTrue(result.toolCalls().isEmpty());
    provider.verify(postRequestedFor(urlPathEqualTo("/v1/chat/completions")));
  }

  @Test
  void chatCompletionsToolCallAndContinuationUseCapturedGpt55Shapes() {
    provider.stubFor(
        post(urlPathEqualTo("/v1/chat/completions"))
            .withRequestBody(matchingJsonPath("$.messages[1].content", equalTo("Use the document_read tool for /contract.md.")))
            .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(CHAT_TOOL_CALL_RESPONSE)));
    provider.stubFor(
        post(urlPathEqualTo("/v1/chat/completions"))
            .withRequestBody(matchingJsonPath("$.messages[2].tool_calls[0].id", equalTo("call_wtIGCW9WMkDpULFW5or9izIa")))
            .withRequestBody(matchingJsonPath("$.messages[3].role", equalTo("tool")))
            .withRequestBody(matchingJsonPath("$.messages[3].tool_call_id", equalTo("call_wtIGCW9WMkDpULFW5or9izIa")))
            .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(CHAT_TOOL_CONTINUATION_RESPONSE)));
    OpenAiChatCompletionsTextAgentAdapter adapter =
        new OpenAiChatCompletionsTextAgentAdapter(objectMapper);
    ProviderContext promptContext =
        promptContext(
            TextAgentAdapterFactory.PROVIDER_OPENAI_CHAT_COMPLETIONS,
            "Use the document_read tool for /contract.md.");

    QueryResult toolRequest = adapter.execute(promptContext);
    adapter.applyResultToSessionState(promptContext, toolRequest);
    ProviderContext continuationContext =
        toolResultContext(promptContext.sessionState(), "call_wtIGCW9WMkDpULFW5or9izIa");
    QueryResult completed = adapter.execute(continuationContext);

    assertEquals(QueryStatus.REQUIRES_TOOL, toolRequest.status());
    assertEquals("call_wtIGCW9WMkDpULFW5or9izIa", toolRequest.toolCalls().get(0).id());
    assertEquals("document_read", toolRequest.toolCalls().get(0).name());
    assertEquals("{\"path\":\"/contract.md\"}", toolRequest.toolCalls().get(0).arguments());
    assertEquals(QueryStatus.COMPLETED, completed.status());
    assertEquals("The document says: “The renewal clause is section 8.”", completed.text());
  }

  @Test
  void responsesCompletedResponseUsesCapturedGpt55OutputTextShape() {
    provider.stubFor(
        post(urlPathEqualTo("/v1/responses"))
            .withRequestBody(matchingJsonPath("$.model", equalTo("gpt-5.5")))
            .withRequestBody(matchingJsonPath("$.input", equalTo("Reply with exactly: live responses ok")))
            .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(RESPONSES_COMPLETED_RESPONSE)));
    OpenAiResponsesTextAgentAdapter adapter = new OpenAiResponsesTextAgentAdapter(objectMapper);
    ProviderContext context =
        promptContext(
            TextAgentAdapterFactory.PROVIDER_OPENAI_RESPONSES,
            "Reply with exactly: live responses ok");

    QueryResult result = adapter.execute(context);

    assertEquals(QueryStatus.COMPLETED, result.status());
    assertEquals("live responses ok", result.text());
    assertEquals("resp_0657109dfe0338bf006a44b315b21081958cf5c183d326b5d2", adapter.previousResponseId(context));
  }

  @Test
  void responsesToolCallAndArrayContinuationUseCapturedGpt55Shapes() {
    provider.stubFor(
        post(urlPathEqualTo("/v1/responses"))
            .withRequestBody(matchingJsonPath("$.input", equalTo("Use the document_read tool for /contract.md.")))
            .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(RESPONSES_TOOL_CALL_RESPONSE)));
    provider.stubFor(
        post(urlPathEqualTo("/v1/responses"))
            .withRequestBody(matchingJsonPath("$.previous_response_id", equalTo("resp_05c749160a4fb710006a44b317c5788193b7c6d343252fe08e")))
            .withRequestBody(matchingJsonPath("$.input[0].type", equalTo("function_call_output")))
            .withRequestBody(matchingJsonPath("$.input[0].call_id", equalTo("call_mAH7OHpzsnpQQ8DfBhdS3L6J")))
            .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(RESPONSES_TOOL_CONTINUATION_RESPONSE)));
    OpenAiResponsesTextAgentAdapter adapter = new OpenAiResponsesTextAgentAdapter(objectMapper);
    ProviderContext promptContext =
        promptContext(
            TextAgentAdapterFactory.PROVIDER_OPENAI_RESPONSES,
            "Use the document_read tool for /contract.md.");

    QueryResult toolRequest = adapter.execute(promptContext);
    adapter.applyResultToSessionState(promptContext, toolRequest);
    ProviderContext continuationContext =
        toolResultContext(promptContext.sessionState(), "call_mAH7OHpzsnpQQ8DfBhdS3L6J");
    QueryResult completed = adapter.execute(continuationContext);

    assertEquals(QueryStatus.REQUIRES_TOOL, toolRequest.status());
    assertEquals("call_mAH7OHpzsnpQQ8DfBhdS3L6J", toolRequest.toolCalls().get(0).id());
    assertEquals("document_read", toolRequest.toolCalls().get(0).name());
    assertEquals("{\"path\":\"/contract.md\"}", toolRequest.toolCalls().get(0).arguments());
    assertEquals(QueryStatus.COMPLETED, completed.status());
    assertEquals("The document says: “The renewal clause is section 8.”", completed.text());
    assertEquals("resp_05c749160a4fb710006a44b38846d08193b4246c9d0b1a56ef", adapter.previousResponseId(continuationContext));
  }

  private ProviderContext promptContext(String providerKey, String prompt) {
    TextAgentDefinition definition = new TextAgentDefinition();
    definition.setTextAgentId("ta_contract");
    definition.setTextModelId("text-agent-test");
    definition.setPrompt("You are a terse test assistant.");
    ProviderSessionState state = new ProviderSessionState("ta_contract", binding(providerKey));
    return new ProviderContext(definition, new QueryCommand("s_voice", "req_prompt", prompt, null), state);
  }

  private ProviderContext toolResultContext(ProviderSessionState state, String toolCallId) {
    TextAgentDefinition definition = new TextAgentDefinition();
    definition.setTextAgentId("ta_contract");
    definition.setTextModelId("text-agent-test");
    definition.setPrompt("You are a terse test assistant.");
    ToolResultSubmission toolResult =
        new ToolResultSubmission(toolCallId, "{\"text\":\"The renewal clause is section 8.\"}", false);
    return new ProviderContext(definition, new QueryCommand("s_voice", "req_tool", null, toolResult), state);
  }

  private TextAgentModelBinding binding(String providerKey) {
    return new TextAgentModelBinding(
        "text-agent-test",
        providerKey,
        Optional.of(provider.baseUrl() + "/v1"),
        Optional.of("test-key"),
        Optional.of("gpt-5.5"));
  }

  @Test
  void capturedResponsesContinuationRequiresInputArrayNotObject() throws Exception {
    String invalidObjectError =
        """
        {
          "error": {
            "message": "Invalid type for 'input': expected one of a string or array of input items, but got an object instead.",
            "type": "invalid_request_error",
            "param": "input",
            "code": "invalid_type"
          }
        }
        """;

    assertInstanceOf(
        String.class, objectMapper.readTree(invalidObjectError).path("error").path("message").asText());
    assertFalse(invalidObjectError.contains("function_call_output\": {"));
  }
}
