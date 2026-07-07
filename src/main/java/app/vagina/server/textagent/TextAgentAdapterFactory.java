package app.vagina.server.textagent;

import app.vagina.server.domain.error.ProviderNotImplementedException;
import app.vagina.server.textagent.TextAgentRuntimeModels.ProviderStateMode;
import app.vagina.server.textagent.TextAgentRuntimeModels.TextAgentModelBinding;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;

@ApplicationScoped
public class TextAgentAdapterFactory {
  public static final String PROVIDER_OPENAI_CHAT_COMPLETIONS = "oai_chat";
  public static final String PROVIDER_OPENAI_RESPONSES = "oai_responses";

  @Inject ObjectMapper objectMapper;

  public TextAgentAdapter create(TextAgentModelBinding binding) {
    if (binding == null) {
      throw new IllegalArgumentException("Text agent model binding is required");
    }
    return create(binding.provider());
  }

  public TextAgentAdapter create(String provider) {
    if (provider == null || provider.isBlank()) {
      throw new IllegalArgumentException("Text agent provider is required");
    }
    return switch (provider) {
      case PROVIDER_OPENAI_CHAT_COMPLETIONS -> buildOpenAiChatCompletionsAdapter();
      case PROVIDER_OPENAI_RESPONSES -> buildOpenAiResponsesAdapter();
      default ->
          throw new ProviderNotImplementedException(
              "Text agent provider not implemented: " + provider);
    };
  }

  public List<String> supportedProviders() {
    return List.of(PROVIDER_OPENAI_CHAT_COMPLETIONS, PROVIDER_OPENAI_RESPONSES);
  }

  public ProviderStateMode stateMode(String provider) {
    return create(provider).stateMode();
  }

  private TextAgentAdapter buildOpenAiChatCompletionsAdapter() {
    return new OpenAiChatCompletionsTextAgentAdapter(objectMapper);
  }

  private TextAgentAdapter buildOpenAiResponsesAdapter() {
    return new OpenAiResponsesTextAgentAdapter(objectMapper);
  }
}
