package app.vagina.server.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import app.vagina.server.config.TextAgentModelsConfig;
import app.vagina.server.textagent.TextAgentAdapterFactory;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TextAgentModelRegistryServiceTest {

  @Test
  void acceptsSchemaReasoningEffortsForBothOpenAiProviders() {
    for (String effort :
        new String[] {"none", "minimal", "low", "medium", "high", "xhigh", "max"}) {
      assertDoesNotThrow(
          () ->
              service(
                      TextAgentAdapterFactory.PROVIDER_OPENAI_RESPONSES,
                      Optional.of(effort),
                      Optional.empty())
                  .validateConfiguration());
      assertDoesNotThrow(
          () ->
              service(
                      TextAgentAdapterFactory.PROVIDER_OPENAI_CHAT_COMPLETIONS,
                      Optional.of(effort),
                      Optional.empty())
                  .validateConfiguration());
    }
  }

  @Test
  void acceptsResponsesReasoningModes() {
    for (String mode : new String[] {"standard", "pro"}) {
      assertDoesNotThrow(
          () ->
              service(
                      TextAgentAdapterFactory.PROVIDER_OPENAI_RESPONSES,
                      Optional.empty(),
                      Optional.of(mode))
                  .validateConfiguration());
    }
  }

  @Test
  void rejectsInvalidCanonicalReasoningValues() {
    IllegalStateException invalidEffort =
        assertThrows(
            IllegalStateException.class,
            () ->
                service(
                        TextAgentAdapterFactory.PROVIDER_OPENAI_RESPONSES,
                        Optional.of("HIGH"),
                        Optional.empty())
                    .validateConfiguration());
    IllegalStateException blankMode =
        assertThrows(
            IllegalStateException.class,
            () ->
                service(
                        TextAgentAdapterFactory.PROVIDER_OPENAI_RESPONSES,
                        Optional.empty(),
                        Optional.of(""))
                    .validateConfiguration());

    assertTrue(invalidEffort.getMessage().contains("test-model"));
    assertTrue(invalidEffort.getMessage().contains("reasoning-effort"));
    assertTrue(blankMode.getMessage().contains("reasoning-mode"));
  }

  @Test
  void rejectsReasoningModeForChatCompletions() {
    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () ->
                service(
                        TextAgentAdapterFactory.PROVIDER_OPENAI_CHAT_COMPLETIONS,
                        Optional.empty(),
                        Optional.of("standard"))
                    .validateConfiguration());

    assertTrue(error.getMessage().contains("test-model"));
    assertTrue(error.getMessage().contains("reasoning-mode"));
    assertTrue(
        error.getMessage().contains(TextAgentAdapterFactory.PROVIDER_OPENAI_CHAT_COMPLETIONS));
  }

  private TextAgentModelRegistryService service(
      String provider, Optional<String> reasoningEffort, Optional<String> reasoningMode) {
    TextAgentModelsConfig config = mock(TextAgentModelsConfig.class);
    TextAgentModelsConfig.ModelConfig model = mock(TextAgentModelsConfig.ModelConfig.class);
    when(config.defaultModel()).thenReturn("test-model");
    when(config.models()).thenReturn(Map.of("test-model", model));
    when(model.provider()).thenReturn(provider);
    when(model.reasoningEffort()).thenReturn(reasoningEffort);
    when(model.reasoningMode()).thenReturn(reasoningMode);

    TextAgentModelRegistryService service = new TextAgentModelRegistryService();
    service.modelsConfig = config;
    return service;
  }
}
