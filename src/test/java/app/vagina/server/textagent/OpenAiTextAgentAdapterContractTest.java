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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.vagina.server.domain.error.ExternalServiceException;
import app.vagina.server.entity.TextAgentDefinition;
import app.vagina.server.textagent.TextAgentRuntimeModels.ProviderContext;
import app.vagina.server.textagent.TextAgentRuntimeModels.ProviderSessionState;
import app.vagina.server.textagent.TextAgentRuntimeModels.QueryCommand;
import app.vagina.server.textagent.TextAgentRuntimeModels.QueryImageInput;
import app.vagina.server.textagent.TextAgentRuntimeModels.QueryResult;
import app.vagina.server.textagent.TextAgentRuntimeModels.QueryStatus;
import app.vagina.server.textagent.TextAgentRuntimeModels.TextAgentModelBinding;
import app.vagina.server.textagent.TextAgentRuntimeModels.ToolCatalogEntry;
import app.vagina.server.textagent.TextAgentRuntimeModels.ToolResultSubmission;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.client.WireMock;
import io.quarkiverse.wiremock.devservice.ConnectWireMock;
import io.quarkus.test.junit.QuarkusTest;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@ConnectWireMock
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

  private static final String CHAT_MULTI_TOOL_CALL_RESPONSE =
      """
      {
        "choices": [
          {
            "finish_reason": "tool_calls",
            "index": 0,
            "message": {
              "content": null,
              "role": "assistant",
              "tool_calls": [
                {
                  "function": {
                    "arguments": "{\\\"path\\\":\\\"/contract.md\\\"}",
                    "name": "document_read"
                  },
                  "id": "call_first",
                  "type": "function"
                },
                {
                  "function": {
                    "arguments": "{\\\"expression\\\":\\\"2+2\\\"}",
                    "name": "calculator"
                  },
                  "id": "call_second",
                  "type": "function"
                }
              ]
            }
          }
        ]
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

  private WireMock wireMock;
  private ObjectMapper objectMapper;

  @BeforeEach
  void resetProvider() {
    wireMock.resetToDefaultMappings();
    wireMock.resetRequests();
    objectMapper = new ObjectMapper();
  }

  @Test
  void chatCompletionsCompletedResponseUsesCapturedGpt55Shape() {
    wireMock.register(
        post(urlPathEqualTo("/v1/chat/completions"))
            .withRequestBody(matchingJsonPath("$.model", equalTo("gpt-5.5")))
            .withRequestBody(matchingJsonPath("$.messages[0].role", equalTo("system")))
            .withRequestBody(
                matchingJsonPath(
                    "$.messages[1].content", equalTo("Reply with exactly: live chat ok")))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(CHAT_COMPLETED_RESPONSE)));
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
    wireMock.verifyThat(postRequestedFor(urlPathEqualTo("/v1/chat/completions")));
  }

  @Test
  void chatCompletionsToolCallAndContinuationUseCapturedGpt55Shapes() {
    wireMock.register(
        post(urlPathEqualTo("/v1/chat/completions"))
            .withRequestBody(
                matchingJsonPath(
                    "$.messages[1].content",
                    equalTo("Use the document_read tool for /contract.md.")))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(CHAT_TOOL_CALL_RESPONSE)));
    wireMock.register(
        post(urlPathEqualTo("/v1/chat/completions"))
            .withRequestBody(
                matchingJsonPath(
                    "$.messages[2].tool_calls[0].id", equalTo("call_wtIGCW9WMkDpULFW5or9izIa")))
            .withRequestBody(matchingJsonPath("$.messages[3].role", equalTo("tool")))
            .withRequestBody(
                matchingJsonPath(
                    "$.messages[3].tool_call_id", equalTo("call_wtIGCW9WMkDpULFW5or9izIa")))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(CHAT_TOOL_CONTINUATION_RESPONSE)));
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
  void chatCompletionsContinuationIncludesAllAcceptedToolOutputsBeforeProviderCall()
      throws Exception {
    wireMock.register(
        post(urlPathEqualTo("/v1/chat/completions"))
            .withRequestBody(
                matchingJsonPath(
                    "$.messages[1].content", equalTo("Use multiple tools before answering.")))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(CHAT_MULTI_TOOL_CALL_RESPONSE)));
    wireMock.register(
        post(urlPathEqualTo("/v1/chat/completions"))
            .withRequestBody(
                matchingJsonPath("$.messages[2].tool_calls[0].id", equalTo("call_first")))
            .withRequestBody(
                matchingJsonPath("$.messages[2].tool_calls[1].id", equalTo("call_second")))
            .withRequestBody(matchingJsonPath("$.messages[3].role", equalTo("tool")))
            .withRequestBody(matchingJsonPath("$.messages[3].tool_call_id", equalTo("call_first")))
            .withRequestBody(matchingJsonPath("$.messages[3].content", equalTo("first output")))
            .withRequestBody(matchingJsonPath("$.messages[4].role", equalTo("tool")))
            .withRequestBody(matchingJsonPath("$.messages[4].tool_call_id", equalTo("call_second")))
            .withRequestBody(matchingJsonPath("$.messages[4].content", equalTo("second output")))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(CHAT_TOOL_CONTINUATION_RESPONSE)));
    OpenAiChatCompletionsTextAgentAdapter adapter =
        new OpenAiChatCompletionsTextAgentAdapter(objectMapper);
    ProviderContext promptContext =
        promptContext(
            TextAgentAdapterFactory.PROVIDER_OPENAI_CHAT_COMPLETIONS,
            "Use multiple tools before answering.");

    QueryResult toolRequest = adapter.execute(promptContext);
    adapter.applyResultToSessionState(promptContext, toolRequest);
    promptContext
        .sessionState()
        .acceptPendingToolResult(new ToolResultSubmission("call_first", "first output", false));
    promptContext
        .sessionState()
        .acceptPendingToolResult(new ToolResultSubmission("call_second", "second output", false));
    ProviderContext continuationContext =
        toolResultContext(promptContext.sessionState(), "call_second");
    QueryResult completed = adapter.execute(continuationContext);

    assertEquals(QueryStatus.REQUIRES_TOOL, toolRequest.status());
    assertEquals(
        List.of("call_first", "call_second"),
        toolRequest.toolCalls().stream().map(TextAgentRuntimeModels.ToolCall::id).toList());
    assertEquals(QueryStatus.COMPLETED, completed.status());
    wireMock.verifyThat(2, postRequestedFor(urlPathEqualTo("/v1/chat/completions")));
  }

  @Test
  void responsesCompletedResponseUsesCapturedGpt55OutputTextShape() {
    wireMock.register(
        post(urlPathEqualTo("/v1/responses"))
            .withRequestBody(matchingJsonPath("$.model", equalTo("gpt-5.5")))
            .withRequestBody(
                matchingJsonPath("$.input", equalTo("Reply with exactly: live responses ok")))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(RESPONSES_COMPLETED_RESPONSE)));
    OpenAiResponsesTextAgentAdapter adapter = new OpenAiResponsesTextAgentAdapter(objectMapper);
    ProviderContext context =
        promptContext(
            TextAgentAdapterFactory.PROVIDER_OPENAI_RESPONSES,
            "Reply with exactly: live responses ok");

    QueryResult result = adapter.execute(context);

    assertEquals(QueryStatus.COMPLETED, result.status());
    assertEquals("live responses ok", result.text());
    assertEquals(
        "resp_0657109dfe0338bf006a44b315b21081958cf5c183d326b5d2",
        adapter.previousResponseId(context));
  }

  @Test
  void responsesToolCallAndArrayContinuationUseCapturedGpt55Shapes() {
    wireMock.register(
        post(urlPathEqualTo("/v1/responses"))
            .withRequestBody(
                matchingJsonPath(
                    "$.input", equalTo("Use the document_read tool for /contract.md.")))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(RESPONSES_TOOL_CALL_RESPONSE)));
    wireMock.register(
        post(urlPathEqualTo("/v1/responses"))
            .withRequestBody(
                matchingJsonPath(
                    "$.previous_response_id",
                    equalTo("resp_05c749160a4fb710006a44b317c5788193b7c6d343252fe08e")))
            .withRequestBody(matchingJsonPath("$.input[0].type", equalTo("function_call_output")))
            .withRequestBody(
                matchingJsonPath("$.input[0].call_id", equalTo("call_mAH7OHpzsnpQQ8DfBhdS3L6J")))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(RESPONSES_TOOL_CONTINUATION_RESPONSE)));
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
    assertEquals(
        "resp_05c749160a4fb710006a44b38846d08193b4246c9d0b1a56ef",
        adapter.previousResponseId(continuationContext));
  }

  @Test
  void chatCompletionsRequestIncludesToolCatalogWhenPresent() throws Exception {
    OpenAiChatCompletionsTextAgentAdapter adapter =
        new OpenAiChatCompletionsTextAgentAdapter(objectMapper);
    ProviderContext context =
        promptContext(
            TextAgentAdapterFactory.PROVIDER_OPENAI_CHAT_COMPLETIONS,
            "Use tools when useful.",
            toolCatalog());

    String body = adapter.previewRequestBody(context);

    assertEquals(
        "function", objectMapper.readTree(body).path("tools").path(0).path("type").asText());
    assertEquals(
        "document_read",
        objectMapper.readTree(body).path("tools").path(0).path("function").path("name").asText());
    assertEquals(
        "object",
        objectMapper
            .readTree(body)
            .path("tools")
            .path(0)
            .path("function")
            .path("parameters")
            .path("type")
            .asText());
    assertEquals("auto", objectMapper.readTree(body).path("tool_choice").asText());
  }

  @Test
  void chatCompletionsPromptWithImageUsesMultimodalContentParts() throws Exception {
    wireMock.register(
        post(urlPathEqualTo("/v1/chat/completions"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(CHAT_COMPLETED_RESPONSE)));
    OpenAiChatCompletionsTextAgentAdapter adapter =
        new OpenAiChatCompletionsTextAgentAdapter(objectMapper);
    ProviderSessionState state =
        new ProviderSessionState(
            "ta_contract", binding(TextAgentAdapterFactory.PROVIDER_OPENAI_CHAT_COMPLETIONS));
    TextAgentDefinition definition = testTextAgentDefinition();
    ProviderContext context =
        new ProviderContext(
            definition.toTextAgentProviderView(),
            new QueryCommand(
                "s_voice",
                "req_prompt",
                "Analyze this image.",
                List.of(
                    new QueryImageInput(
                        "data:image/png;base64,iVBORw0KGgo=", "auto", "whiteboard.png")),
                null,
                List.of()),
            state,
            List.of());

    adapter.execute(context);
    String body = wireMock.getServeEvents().getFirst().getRequest().getBodyAsString();

    assertEquals(
        "user", objectMapper.readTree(body).path("messages").path(1).path("role").asText());
    assertEquals(
        "text",
        objectMapper
            .readTree(body)
            .path("messages")
            .path(1)
            .path("content")
            .path(0)
            .path("type")
            .asText());
    assertEquals(
        "image_url",
        objectMapper
            .readTree(body)
            .path("messages")
            .path(1)
            .path("content")
            .path(1)
            .path("type")
            .asText());
    assertEquals(
        "data:image/png;base64,iVBORw0KGgo=",
        objectMapper
            .readTree(body)
            .path("messages")
            .path(1)
            .path("content")
            .path(1)
            .path("image_url")
            .path("url")
            .asText());
  }

  @Test
  void chatCompletionsContinuationUsesErrorToolOutputAsContentOnly() throws Exception {
    wireMock.register(
        post(urlPathEqualTo("/v1/chat/completions"))
            .withRequestBody(
                matchingJsonPath(
                    "$.messages[1].content",
                    equalTo("Use the document_read tool for /contract.md.")))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(CHAT_TOOL_CALL_RESPONSE)));
    wireMock.register(
        post(urlPathEqualTo("/v1/chat/completions"))
            .withRequestBody(matchingJsonPath("$.messages[3].role", equalTo("tool")))
            .withRequestBody(
                matchingJsonPath(
                    "$.messages[3].tool_call_id", equalTo("call_wtIGCW9WMkDpULFW5or9izIa")))
            .withRequestBody(
                matchingJsonPath(
                    "$.messages[3].content",
                    equalTo("{\"success\":false,\"error\":\"read failed\"}")))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(CHAT_TOOL_CONTINUATION_RESPONSE)));
    OpenAiChatCompletionsTextAgentAdapter adapter =
        new OpenAiChatCompletionsTextAgentAdapter(objectMapper);
    ProviderContext promptContext =
        promptContext(
            TextAgentAdapterFactory.PROVIDER_OPENAI_CHAT_COMPLETIONS,
            "Use the document_read tool for /contract.md.");
    QueryResult toolRequest = adapter.execute(promptContext);
    adapter.applyResultToSessionState(promptContext, toolRequest);
    ProviderContext continuationContext =
        toolResultContext(
            promptContext.sessionState(),
            "call_wtIGCW9WMkDpULFW5or9izIa",
            "{\"success\":false,\"error\":\"read failed\"}",
            true,
            List.of());

    QueryResult completed = adapter.execute(continuationContext);
    String body =
        wireMock.getServeEvents().stream()
            .map(event -> event.getRequest().getBodyAsString())
            .filter(requestBody -> requestBody.contains("read failed"))
            .findFirst()
            .orElseThrow();

    assertEquals(QueryStatus.REQUIRES_TOOL, toolRequest.status());
    assertEquals(QueryStatus.COMPLETED, completed.status());
    assertEquals(
        "tool", objectMapper.readTree(body).path("messages").path(3).path("role").asText());
    assertEquals(
        "call_wtIGCW9WMkDpULFW5or9izIa",
        objectMapper.readTree(body).path("messages").path(3).path("tool_call_id").asText());
    assertEquals(
        "{\"success\":false,\"error\":\"read failed\"}",
        objectMapper.readTree(body).path("messages").path(3).path("content").asText());
    assertFalse(objectMapper.readTree(body).path("messages").path(3).has("isError"));
    assertFalse(objectMapper.readTree(body).path("messages").path(3).has("is_error"));
  }

  @Test
  void endpointSpecificReasoningSettingsUseExactProviderJsonShapes() throws Exception {
    ProviderContext chatContext =
        promptContext(
            binding(TextAgentAdapterFactory.PROVIDER_OPENAI_CHAT_COMPLETIONS, "high", null));
    ProviderContext responsesContext =
        promptContext(binding(TextAgentAdapterFactory.PROVIDER_OPENAI_RESPONSES, "xhigh", "pro"));

    String chatBody =
        new OpenAiChatCompletionsTextAgentAdapter(objectMapper).previewRequestBody(chatContext);
    String responsesBody =
        new OpenAiResponsesTextAgentAdapter(objectMapper).previewRequestBody(responsesContext);

    assertEquals("high", objectMapper.readTree(chatBody).path("reasoning_effort").asText());
    assertFalse(objectMapper.readTree(chatBody).has("reasoning"));
    assertEquals(
        "xhigh", objectMapper.readTree(responsesBody).path("reasoning").path("effort").asText());
    assertEquals(
        "pro", objectMapper.readTree(responsesBody).path("reasoning").path("mode").asText());
    assertFalse(objectMapper.readTree(responsesBody).has("reasoning_effort"));
  }

  @Test
  void endpointSpecificReasoningSettingsAreOmittedWhenPresetDoesNotConfigureThem()
      throws Exception {
    ProviderContext chatContext =
        promptContext(binding(TextAgentAdapterFactory.PROVIDER_OPENAI_CHAT_COMPLETIONS));
    ProviderContext responsesContext =
        promptContext(binding(TextAgentAdapterFactory.PROVIDER_OPENAI_RESPONSES));

    String chatBody =
        new OpenAiChatCompletionsTextAgentAdapter(objectMapper).previewRequestBody(chatContext);
    String responsesBody =
        new OpenAiResponsesTextAgentAdapter(objectMapper).previewRequestBody(responsesContext);

    assertFalse(objectMapper.readTree(chatBody).has("reasoning_effort"));
    assertFalse(objectMapper.readTree(responsesBody).has("reasoning"));
  }

  @Test
  void responsesReasoningOmitsUnsetNestedField() throws Exception {
    ProviderContext effortContext =
        promptContext(binding(TextAgentAdapterFactory.PROVIDER_OPENAI_RESPONSES, "minimal", null));
    ProviderContext modeContext =
        promptContext(binding(TextAgentAdapterFactory.PROVIDER_OPENAI_RESPONSES, null, "standard"));

    var effortReasoning =
        objectMapper
            .readTree(
                new OpenAiResponsesTextAgentAdapter(objectMapper).previewRequestBody(effortContext))
            .path("reasoning");
    var modeReasoning =
        objectMapper
            .readTree(
                new OpenAiResponsesTextAgentAdapter(objectMapper).previewRequestBody(modeContext))
            .path("reasoning");

    assertEquals("minimal", effortReasoning.path("effort").asText());
    assertFalse(effortReasoning.has("mode"));
    assertEquals("standard", modeReasoning.path("mode").asText());
    assertFalse(modeReasoning.has("effort"));
  }

  @Test
  void chatCompletionsRequestOmitsToolsWhenCatalogEmpty() throws Exception {
    OpenAiChatCompletionsTextAgentAdapter adapter =
        new OpenAiChatCompletionsTextAgentAdapter(objectMapper);
    ProviderContext context =
        promptContext(TextAgentAdapterFactory.PROVIDER_OPENAI_CHAT_COMPLETIONS, "No tool catalog.");

    String body = adapter.previewRequestBody(context);

    assertFalse(objectMapper.readTree(body).has("tools"));
    assertFalse(objectMapper.readTree(body).has("tool_choice"));
  }

  @Test
  void responsesRequestIncludesToolCatalogOnPromptAndContinuation() throws Exception {
    OpenAiResponsesTextAgentAdapter adapter = new OpenAiResponsesTextAgentAdapter(objectMapper);
    ProviderSessionState state =
        new ProviderSessionState(
            "ta_contract", binding(TextAgentAdapterFactory.PROVIDER_OPENAI_RESPONSES));
    ProviderContext promptContext =
        promptContext(
            TextAgentAdapterFactory.PROVIDER_OPENAI_RESPONSES,
            "Use tools when useful.",
            state,
            toolCatalog());
    adapter.rememberPreviousResponseId(promptContext, "resp_previous");
    ProviderContext continuationContext = toolResultContext(state, "call_document", toolCatalog());

    String promptBody = adapter.previewRequestBody(promptContext);
    String continuationBody = adapter.previewRequestBody(continuationContext);

    assertEquals(
        "function", objectMapper.readTree(promptBody).path("tools").path(0).path("type").asText());
    assertEquals(
        "document_read",
        objectMapper.readTree(promptBody).path("tools").path(0).path("name").asText());
    assertEquals("auto", objectMapper.readTree(promptBody).path("tool_choice").asText());
    assertEquals(
        "document_read",
        objectMapper.readTree(continuationBody).path("tools").path(0).path("name").asText());
    assertEquals("auto", objectMapper.readTree(continuationBody).path("tool_choice").asText());
  }

  @Test
  void responsesPromptWithImageUsesMultimodalInputParts() throws Exception {
    wireMock.register(
        post(urlPathEqualTo("/v1/responses"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(RESPONSES_COMPLETED_RESPONSE)));
    OpenAiResponsesTextAgentAdapter adapter = new OpenAiResponsesTextAgentAdapter(objectMapper);
    ProviderSessionState state =
        new ProviderSessionState(
            "ta_contract", binding(TextAgentAdapterFactory.PROVIDER_OPENAI_RESPONSES));
    TextAgentDefinition definition = testTextAgentDefinition();
    ProviderContext context =
        new ProviderContext(
            definition.toTextAgentProviderView(),
            new QueryCommand(
                "s_voice",
                "req_prompt",
                "Analyze this image.",
                List.of(
                    new QueryImageInput(
                        "data:image/png;base64,iVBORw0KGgo=", "auto", "whiteboard.png")),
                null,
                List.of()),
            state,
            List.of());

    adapter.execute(context);
    String body = wireMock.getServeEvents().getFirst().getRequest().getBodyAsString();

    assertEquals(
        "message", objectMapper.readTree(body).path("input").path(0).path("type").asText());
    assertEquals(
        "input_text",
        objectMapper
            .readTree(body)
            .path("input")
            .path(0)
            .path("content")
            .path(0)
            .path("type")
            .asText());
    assertEquals(
        "input_image",
        objectMapper
            .readTree(body)
            .path("input")
            .path(0)
            .path("content")
            .path(1)
            .path("type")
            .asText());
    assertEquals(
        "data:image/png;base64,iVBORw0KGgo=",
        objectMapper
            .readTree(body)
            .path("input")
            .path(0)
            .path("content")
            .path(1)
            .path("image_url")
            .asText());
  }

  @Test
  void responsesContinuationUsesErrorToolOutputAsFunctionCallOutputOnly() throws Exception {
    OpenAiResponsesTextAgentAdapter adapter = new OpenAiResponsesTextAgentAdapter(objectMapper);
    ProviderSessionState state =
        new ProviderSessionState(
            "ta_contract", binding(TextAgentAdapterFactory.PROVIDER_OPENAI_RESPONSES));
    ProviderContext promptContext =
        promptContext(
            TextAgentAdapterFactory.PROVIDER_OPENAI_RESPONSES,
            "Use the document_read tool for /contract.md.",
            state,
            List.of());
    adapter.rememberPreviousResponseId(promptContext, "resp_previous");
    ProviderContext continuationContext =
        toolResultContext(
            state,
            "call_document",
            "{\"success\":false,\"error\":\"read failed\"}",
            true,
            List.of());

    String body = adapter.previewRequestBody(continuationContext);

    assertEquals(
        "resp_previous", objectMapper.readTree(body).path("previous_response_id").asText());
    assertEquals(
        "function_call_output",
        objectMapper.readTree(body).path("input").path(0).path("type").asText());
    assertEquals(
        "call_document",
        objectMapper.readTree(body).path("input").path(0).path("call_id").asText());
    assertEquals(
        "{\"success\":false,\"error\":\"read failed\"}",
        objectMapper.readTree(body).path("input").path(0).path("output").asText());
    assertFalse(objectMapper.readTree(body).path("input").path(0).has("isError"));
    assertFalse(objectMapper.readTree(body).path("input").path(0).has("is_error"));
  }

  @Test
  void chatCompletionsTerminalRecoveryCommitsSanitizedSyntheticToolOutput() throws Exception {
    wireMock.register(
        post(urlPathEqualTo("/v1/chat/completions"))
            .atPriority(1)
            .withRequestBody(
                matchingJsonPath(
                    "$.messages[3].content",
                    equalTo(
                        "{\"success\":false,\"error\":\"Tool continuation was rejected by the AI provider.\"}")))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(CHAT_TOOL_CONTINUATION_RESPONSE)));
    OpenAiChatCompletionsTextAgentAdapter adapter =
        new OpenAiChatCompletionsTextAgentAdapter(objectMapper);
    ProviderContext promptContext =
        promptContext(
            TextAgentAdapterFactory.PROVIDER_OPENAI_CHAT_COMPLETIONS,
            "Use the document_read tool for /contract.md.");
    seedPendingToolContinuation(adapter, promptContext, "call_wtIGCW9WMkDpULFW5or9izIa");
    ProviderContext continuationContext =
        toolResultContext(promptContext.sessionState(), "call_wtIGCW9WMkDpULFW5or9izIa");

    assertTrue(adapter.recoverTerminalToolFailure(continuationContext));
    assertEquals(5, providerMessages(continuationContext).size());
  }

  @Test
  void responsesTerminalRecoveryCommitsSanitizedSyntheticToolOutput() {
    wireMock.register(
        post(urlPathEqualTo("/v1/responses"))
            .withRequestBody(matchingJsonPath("$.previous_response_id", equalTo("resp_tool_call")))
            .withRequestBody(
                matchingJsonPath(
                    "$.input[0].output",
                    equalTo(
                        "{\"success\":false,\"error\":\"Tool continuation was rejected by the AI provider.\"}")))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(RESPONSES_TOOL_CONTINUATION_RESPONSE)));
    OpenAiResponsesTextAgentAdapter adapter = new OpenAiResponsesTextAgentAdapter(objectMapper);
    ProviderContext promptContext =
        promptContext(
            TextAgentAdapterFactory.PROVIDER_OPENAI_RESPONSES,
            "Use the document_read tool for /contract.md.");
    adapter.rememberPreviousResponseId(promptContext, "resp_tool_call");
    ProviderContext continuationContext =
        toolResultContext(promptContext.sessionState(), "call_mAH7OHpzsnpQQ8DfBhdS3L6J");

    assertTrue(adapter.recoverTerminalToolFailure(continuationContext));
    assertEquals(
        "resp_05c749160a4fb710006a44b38846d08193b4246c9d0b1a56ef",
        adapter.previousResponseId(continuationContext));
  }

  private void seedPendingToolContinuation(
      OpenAiChatCompletionsTextAgentAdapter adapter, ProviderContext context, String toolCallId) {
    wireMock.register(
        post(urlPathEqualTo("/v1/chat/completions"))
            .withRequestBody(matchingJsonPath("$.messages[1].role", equalTo("user")))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(CHAT_TOOL_CALL_RESPONSE)));
    QueryResult result = adapter.execute(context);
    adapter.applyResultToSessionState(context, result);
    assertTrue(context.sessionState().hasPendingToolCall(toolCallId));
  }

  @Test
  void responsesRequestOmitsToolsWhenCatalogEmpty() throws Exception {
    OpenAiResponsesTextAgentAdapter adapter = new OpenAiResponsesTextAgentAdapter(objectMapper);
    ProviderContext context =
        promptContext(TextAgentAdapterFactory.PROVIDER_OPENAI_RESPONSES, "No tool catalog.");

    String body = adapter.previewRequestBody(context);

    assertFalse(objectMapper.readTree(body).has("tools"));
    assertFalse(objectMapper.readTree(body).has("tool_choice"));
  }

  @Test
  void chatCompletionsAuthenticationFailureIsSanitized() {
    wireMock.register(
        post(urlPathEqualTo("/v1/chat/completions"))
            .willReturn(
                aResponse()
                    .withStatus(401)
                    .withHeader("Content-Type", "application/json")
                    .withBody(providerError("invalid_api_key", "Invalid API key: secret-value"))));
    OpenAiChatCompletionsTextAgentAdapter adapter =
        new OpenAiChatCompletionsTextAgentAdapter(objectMapper);

    QueryResult result =
        adapter.execute(
            promptContext(TextAgentAdapterFactory.PROVIDER_OPENAI_CHAT_COMPLETIONS, "Hello"));

    assertEquals(QueryStatus.FAILED, result.status());
    assertEquals("provider_authentication_error", result.error().code());
    assertEquals(
        "This AI model is currently unavailable. Please contact the service administrator.",
        result.error().message());
    assertFalse(result.error().message().contains("secret-value"));
  }

  @Test
  void responsesAuthenticationFailureIsSanitized() {
    wireMock.register(
        post(urlPathEqualTo("/v1/responses"))
            .willReturn(
                aResponse()
                    .withStatus(401)
                    .withHeader("Content-Type", "application/json")
                    .withBody(providerError("invalid_api_key", "Invalid API key: secret-value"))));
    OpenAiResponsesTextAgentAdapter adapter = new OpenAiResponsesTextAgentAdapter(objectMapper);

    QueryResult result =
        adapter.execute(promptContext(TextAgentAdapterFactory.PROVIDER_OPENAI_RESPONSES, "Hello"));

    assertEquals(QueryStatus.FAILED, result.status());
    assertEquals("provider_authentication_error", result.error().code());
    assertFalse(result.error().message().contains("secret-value"));
  }

  @Test
  void chatCompletionsRejectedPromptDoesNotCommitInputHistory() {
    wireMock.register(
        post(urlPathEqualTo("/v1/chat/completions"))
            .willReturn(
                aResponse()
                    .withStatus(401)
                    .withHeader("Content-Type", "application/json")
                    .withBody(providerError("invalid_api_key", "Invalid API key"))));
    OpenAiChatCompletionsTextAgentAdapter adapter =
        new OpenAiChatCompletionsTextAgentAdapter(objectMapper);
    ProviderContext context =
        promptContext(TextAgentAdapterFactory.PROVIDER_OPENAI_CHAT_COMPLETIONS, "Do not commit");

    QueryResult result = adapter.execute(context);

    assertEquals(QueryStatus.FAILED, result.status());
    assertTrue(providerMessages(context).isEmpty());
  }

  @Test
  void chatCompletionsRateLimitIsRetryableAndDoesNotCommitInputHistory() {
    wireMock.register(
        post(urlPathEqualTo("/v1/chat/completions"))
            .willReturn(
                aResponse()
                    .withStatus(429)
                    .withHeader("Content-Type", "application/json")
                    .withBody(providerError("rate_limit_exceeded", "Busy"))));
    OpenAiChatCompletionsTextAgentAdapter adapter =
        new OpenAiChatCompletionsTextAgentAdapter(objectMapper);
    ProviderContext context =
        promptContext(TextAgentAdapterFactory.PROVIDER_OPENAI_CHAT_COMPLETIONS, "Do not commit");

    assertThrows(RetryableTextAgentProviderException.class, () -> adapter.execute(context));
    assertTrue(providerMessages(context).isEmpty());
  }

  @Test
  void responsesSemanticFailureDoesNotAdvancePreviousResponseId() {
    wireMock.register(
        post(urlPathEqualTo("/v1/responses"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"id\":\"resp_rejected\",\"output\":[]}")));
    OpenAiResponsesTextAgentAdapter adapter = new OpenAiResponsesTextAgentAdapter(objectMapper);
    ProviderContext context =
        promptContext(TextAgentAdapterFactory.PROVIDER_OPENAI_RESPONSES, "Do not commit");
    adapter.rememberPreviousResponseId(context, "resp_committed");

    QueryResult result = adapter.execute(context);

    assertEquals(QueryStatus.FAILED, result.status());
    assertEquals("resp_committed", adapter.previousResponseId(context));
  }

  @Test
  void responsesRateLimitIsRetryableAndDoesNotAdvancePreviousResponseId() {
    wireMock.register(
        post(urlPathEqualTo("/v1/responses"))
            .willReturn(
                aResponse()
                    .withStatus(429)
                    .withHeader("Content-Type", "application/json")
                    .withBody(providerError("rate_limit_exceeded", "Busy"))));
    OpenAiResponsesTextAgentAdapter adapter = new OpenAiResponsesTextAgentAdapter(objectMapper);
    ProviderContext context =
        promptContext(TextAgentAdapterFactory.PROVIDER_OPENAI_RESPONSES, "Do not commit");
    adapter.rememberPreviousResponseId(context, "resp_committed");

    assertThrows(RetryableTextAgentProviderException.class, () -> adapter.execute(context));
    assertEquals("resp_committed", adapter.previousResponseId(context));
  }

  @SuppressWarnings("unchecked")
  private List<Object> providerMessages(ProviderContext context) {
    return (List<Object>)
        context
            .sessionState()
            .providerState()
            .get(OpenAiChatCompletionsTextAgentAdapter.PROVIDER_STATE_MESSAGES);
  }

  @Test
  void malformedClientErrorBecomesExternalServiceFailure() {
    wireMock.register(
        post(urlPathEqualTo("/v1/chat/completions"))
            .willReturn(
                aResponse()
                    .withStatus(400)
                    .withHeader("Content-Type", "text/plain")
                    .withBody("not-json")));
    OpenAiChatCompletionsTextAgentAdapter adapter =
        new OpenAiChatCompletionsTextAgentAdapter(objectMapper);

    assertThrows(
        ExternalServiceException.class,
        () ->
            adapter.execute(
                promptContext(TextAgentAdapterFactory.PROVIDER_OPENAI_CHAT_COMPLETIONS, "Hello")));
  }

  @Test
  void providerServerErrorBecomesExternalServiceFailureWithoutParsingBody() {
    wireMock.register(
        post(urlPathEqualTo("/v1/responses"))
            .willReturn(
                aResponse()
                    .withStatus(503)
                    .withHeader("Content-Type", "application/json")
                    .withBody(providerError("server_error", "Temporary provider failure"))));
    OpenAiResponsesTextAgentAdapter adapter = new OpenAiResponsesTextAgentAdapter(objectMapper);

    assertThrows(
        ExternalServiceException.class,
        () ->
            adapter.execute(
                promptContext(TextAgentAdapterFactory.PROVIDER_OPENAI_RESPONSES, "Hello")));
  }

  @Test
  void emptySuccessfulResponseBecomesExternalServiceFailure() {
    wireMock.register(
        post(urlPathEqualTo("/v1/chat/completions"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("")));
    OpenAiChatCompletionsTextAgentAdapter adapter =
        new OpenAiChatCompletionsTextAgentAdapter(objectMapper);

    assertThrows(
        ExternalServiceException.class,
        () ->
            adapter.execute(
                promptContext(TextAgentAdapterFactory.PROVIDER_OPENAI_CHAT_COMPLETIONS, "Hello")));
  }

  private String providerError(String code, String message) {
    return """
        {"error":{"code":"%s","message":"%s","type":"invalid_request_error"}}
        """
        .formatted(code, message);
  }

  private ProviderContext promptContext(String providerKey, String prompt) {
    ProviderSessionState state = new ProviderSessionState("ta_contract", binding(providerKey));
    return promptContext(providerKey, prompt, state, List.of());
  }

  private ProviderContext promptContext(TextAgentModelBinding modelBinding) {
    ProviderSessionState state = new ProviderSessionState("ta_contract", modelBinding);
    TextAgentDefinition definition = testTextAgentDefinition();
    return new ProviderContext(
        definition.toTextAgentProviderView(),
        new QueryCommand("s_voice", "req_prompt", "Hello", List.of(), null, List.of()),
        state,
        List.of());
  }

  private ProviderContext promptContext(
      String providerKey, String prompt, List<ToolCatalogEntry> toolCatalog) {
    ProviderSessionState state = new ProviderSessionState("ta_contract", binding(providerKey));
    return promptContext(providerKey, prompt, state, toolCatalog);
  }

  private ProviderContext promptContext(
      String providerKey,
      String prompt,
      ProviderSessionState state,
      List<ToolCatalogEntry> toolCatalog) {
    TextAgentDefinition definition = testTextAgentDefinition();
    return new ProviderContext(
        definition.toTextAgentProviderView(),
        new QueryCommand("s_voice", "req_prompt", prompt, List.of(), null, List.of()),
        state,
        toolCatalog);
  }

  private ProviderContext toolResultContext(ProviderSessionState state, String toolCallId) {
    return toolResultContext(state, toolCallId, List.of());
  }

  private ProviderContext toolResultContext(
      ProviderSessionState state, String toolCallId, List<ToolCatalogEntry> toolCatalog) {
    return toolResultContext(
        state, toolCallId, "{\"text\":\"The renewal clause is section 8.\"}", false, toolCatalog);
  }

  private ProviderContext toolResultContext(
      ProviderSessionState state,
      String toolCallId,
      String output,
      boolean isError,
      List<ToolCatalogEntry> toolCatalog) {
    TextAgentDefinition definition = testTextAgentDefinition();
    ToolResultSubmission toolResult = new ToolResultSubmission(toolCallId, output, isError);
    if (state.acceptedToolResults().stream()
        .noneMatch(accepted -> accepted.toolCallId().equals(toolCallId))) {
      if (!state.hasPendingToolCall(toolCallId)) {
        state.replacePendingToolCalls(
            List.of(new TextAgentRuntimeModels.ToolCall(toolCallId, "document_read", "{}")));
      }
      state.acceptPendingToolResult(toolResult);
    }
    return new ProviderContext(
        definition.toTextAgentProviderView(),
        new QueryCommand("s_voice", "req_tool", null, List.of(), toolResult, List.of()),
        state,
        toolCatalog);
  }

  private List<ToolCatalogEntry> toolCatalog() {
    return List.of(
        new ToolCatalogEntry(
            "document_read",
            "Read a document by path.",
            Map.of(
                "type",
                "object",
                "properties",
                Map.of("path", Map.of("type", "string")),
                "required",
                List.of("path"))));
  }

  private TextAgentDefinition testTextAgentDefinition() {
    return new TextAgentDefinition(
        null,
        7L,
        "ta_contract",
        "Contract text agent",
        "You are a terse test assistant.",
        null,
        "text-agent-test",
        "{}",
        null,
        null);
  }

  private TextAgentModelBinding binding(String providerKey) {
    return binding(providerKey, null, null);
  }

  private TextAgentModelBinding binding(
      String providerKey, String reasoningEffort, String reasoningMode) {
    return new TextAgentModelBinding(
        "text-agent-test",
        providerKey,
        ConfigProvider.getConfig().getValue("vagina.test.oai-cc.base-url", String.class),
        "test-key",
        "gpt-5.5",
        reasoningEffort,
        reasoningMode);
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
        String.class,
        objectMapper.readTree(invalidObjectError).path("error").path("message").asText());
    assertFalse(invalidObjectError.contains("function_call_output\": {"));
  }
}
