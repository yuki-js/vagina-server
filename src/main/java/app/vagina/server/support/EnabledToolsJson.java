package app.vagina.server.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class EnabledToolsJson {
  private static final TypeReference<LinkedHashMap<String, Boolean>> ENABLED_TOOLS_TYPE =
      new TypeReference<>() {};

  private EnabledToolsJson() {}

  public static String serialize(
      ObjectMapper objectMapper,
      Object enabledTools,
      String enabledToolLabel,
      String enabledToolsLabel) {
    try {
      return objectMapper.writeValueAsString(normalize(enabledTools, enabledToolLabel));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to serialize " + enabledToolsLabel, e);
    }
  }

  public static Map<String, Boolean> deserialize(
      ObjectMapper objectMapper, String enabledToolsJson, String enabledToolsLabel) {
    if (enabledToolsJson == null || enabledToolsJson.isBlank()) {
      return Map.of();
    }
    try {
      return readMap(objectMapper, enabledToolsJson);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to deserialize " + enabledToolsLabel, e);
    }
  }

  public static ParseResult parse(ObjectMapper objectMapper, String enabledToolsJson) {
    if (enabledToolsJson == null || enabledToolsJson.isBlank()) {
      return ParseResult.valid(Map.of());
    }
    try {
      return ParseResult.valid(readMap(objectMapper, enabledToolsJson));
    } catch (JsonProcessingException e) {
      return ParseResult.invalid(e);
    }
  }

  public static Set<String> enabledToolNames(Map<String, Boolean> enabledTools) {
    if (enabledTools == null || enabledTools.isEmpty()) {
      return Set.of();
    }
    return enabledTools.entrySet().stream()
        .filter(entry -> Boolean.TRUE.equals(entry.getValue()))
        .map(Map.Entry::getKey)
        .filter(name -> name != null && !name.isBlank())
        .collect(Collectors.toSet());
  }

  private static Map<String, Boolean> normalize(Object enabledTools, String enabledToolLabel) {
    if (enabledTools == null) {
      return Map.of();
    }
    if (!(enabledTools instanceof Map<?, ?> rawEnabledTools)) {
      throw new IllegalArgumentException(enabledToolLabel + "s must be a JSON object");
    }
    Map<String, Boolean> normalized = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : rawEnabledTools.entrySet()) {
      if (!(entry.getKey() instanceof String toolName)) {
        throw new IllegalArgumentException(enabledToolLabel + " names must be strings");
      }
      if (!(entry.getValue() instanceof Boolean enabled)) {
        throw new IllegalArgumentException(enabledToolLabel + " values must be booleans");
      }
      normalized.put(toolName, enabled);
    }
    return normalized;
  }

  private static Map<String, Boolean> readMap(ObjectMapper objectMapper, String enabledToolsJson)
      throws JsonProcessingException {
    Map<String, Boolean> parsed = objectMapper.readValue(enabledToolsJson, ENABLED_TOOLS_TYPE);
    return parsed == null ? Map.of() : new LinkedHashMap<>(parsed);
  }

  public record ParseResult(boolean valid, Map<String, Boolean> overrides, Throwable error) {
    public static ParseResult valid(Map<String, Boolean> overrides) {
      return new ParseResult(true, new LinkedHashMap<>(overrides), null);
    }

    public static ParseResult invalid(Throwable error) {
      return new ParseResult(false, Map.of(), error);
    }
  }
}
