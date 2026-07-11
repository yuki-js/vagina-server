package app.vagina.server.textagent;

import app.vagina.server.textagent.TextAgentRuntimeModels.ToolCatalogEntry;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.List;
import java.util.Map;

final class OpenAiTextAgentToolSchemas {
  private static final String FUNCTION_TYPE = "function";

  private OpenAiTextAgentToolSchemas() {}

  static List<ChatCompletionsTool> chatCompletionsTools(List<ToolCatalogEntry> toolCatalog) {
    return toolCatalog.stream()
        .map(
            tool ->
                new ChatCompletionsTool(
                    FUNCTION_TYPE,
                    new ChatCompletionsFunction(
                        tool.name(), tool.description(), tool.parameters())))
        .toList();
  }

  static List<ResponsesTool> responsesTools(List<ToolCatalogEntry> toolCatalog) {
    return toolCatalog.stream()
        .map(
            tool ->
                new ResponsesTool(
                    FUNCTION_TYPE, tool.name(), tool.description(), tool.parameters()))
        .toList();
  }

  @RegisterForReflection
  @JsonInclude(JsonInclude.Include.NON_NULL)
  record ChatCompletionsTool(String type, ChatCompletionsFunction function) {}

  @RegisterForReflection
  @JsonInclude(JsonInclude.Include.NON_NULL)
  record ChatCompletionsFunction(String name, String description, Map<String, Object> parameters) {}

  @RegisterForReflection
  @JsonInclude(JsonInclude.Include.NON_NULL)
  record ResponsesTool(
      String type,
      String name,
      String description,
      @JsonProperty("parameters") Map<String, Object> parameters) {}
}
