package app.vagina.server.realtime.oai;

import app.vagina.server.realtime.model.RealtimeAdapterModels;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Owns mutable OpenAI Realtime session policy and renders it to the provider wire shape.
 *
 * <p>Keeping session policy here prevents the adapter facade from mixing configuration mutation
 * with response lifecycle and event projection. This object performs no I/O; callers decide when a
 * changed configuration must be sent.
 */
final class OaiRealtimeSessionConfig {
  static final String EXT_INPUT_NOISE_REDUCTION = "session.input_noise_reduction_selection";
  static final String EXT_REASONING_EFFORT = "session.reasoning_effort_selection";
  static final String EXT_TOOL_CHOICE_REQUIRED = "session.tool_choice_required";

  private static final String EXT_SELECTION_KEY = "selection";
  private static final String EXT_REQUIRED_KEY = "required";

  private enum NoiseReduction {
    OFF,
    NEAR_FIELD,
    FAR_FIELD;

    static NoiseReduction fromPayload(Object value) {
      return switch (String.valueOf(value)) {
        case "off" -> OFF;
        case "nearField" -> NEAR_FIELD;
        case "farField" -> FAR_FIELD;
        default -> null;
      };
    }
  }

  /** Result of applying one provider extension. */
  record ExtensionResult(boolean supported, boolean changed) {}

  private List<RealtimeAdapterModels.ToolDefinition> tools = List.of();
  private NoiseReduction noiseReduction = NoiseReduction.NEAR_FIELD;
  private String reasoningEffort;
  private boolean toolChoiceRequired;
  private String voice;
  private String serverInstructions;
  private String clientInstructions = "";
  private String transcriptionModel;
  private RealtimeAdapterModels.AudioTurnMode audioTurnMode =
      RealtimeAdapterModels.AudioTurnMode.VOICE_ACTIVITY;

  Set<String> supportedExtensions() {
    return Set.of(EXT_INPUT_NOISE_REDUCTION, EXT_REASONING_EFFORT, EXT_TOOL_CHOICE_REQUIRED);
  }

  void initialize(String voice, String instructions, String transcriptionModel) {
    this.voice = voice;
    this.serverInstructions = instructions;
    this.clientInstructions = "";
    this.transcriptionModel = transcriptionModel;
  }

  boolean setAudioTurnMode(RealtimeAdapterModels.AudioTurnMode mode) {
    if (audioTurnMode == mode) {
      return false;
    }
    audioTurnMode = mode;
    return true;
  }

  RealtimeAdapterModels.AudioTurnMode audioTurnMode() {
    return audioTurnMode;
  }

  boolean setTools(List<RealtimeAdapterModels.ToolDefinition> nextTools) {
    List<RealtimeAdapterModels.ToolDefinition> next = List.copyOf(nextTools);
    if (tools.equals(next)) {
      return false;
    }
    tools = next;
    return true;
  }

  boolean setClientInstructions(String instructions) {
    String normalized = instructions == null ? "" : instructions.trim();
    if (Objects.equals(clientInstructions, normalized)) {
      return false;
    }
    clientInstructions = normalized;
    return true;
  }

  ExtensionResult applyExtension(String extensionType, Map<String, Object> payload) {
    return switch (extensionType) {
      case EXT_INPUT_NOISE_REDUCTION -> {
        NoiseReduction selection = NoiseReduction.fromPayload(payload.get(EXT_SELECTION_KEY));
        if (selection == null) {
          throw new IllegalArgumentException("Unsupported input noise reduction selection");
        }
        boolean changed = noiseReduction != selection;
        noiseReduction = selection;
        yield new ExtensionResult(true, changed);
      }
      case EXT_REASONING_EFFORT -> {
        Object selection = payload.get(EXT_SELECTION_KEY);
        if (selection != null && !(selection instanceof String)) {
          throw new IllegalArgumentException("Reasoning effort selection must be a string or null");
        }
        String value = (String) selection;
        boolean changed = !Objects.equals(reasoningEffort, value);
        reasoningEffort = value;
        yield new ExtensionResult(true, changed);
      }
      case EXT_TOOL_CHOICE_REQUIRED -> {
        Object required = payload.get(EXT_REQUIRED_KEY);
        if (!(required instanceof Boolean value)) {
          throw new IllegalArgumentException("Tool choice required flag must be a bool");
        }
        boolean changed = toolChoiceRequired != value;
        toolChoiceRequired = value;
        yield new ExtensionResult(true, changed);
      }
      default -> new ExtensionResult(false, false);
    };
  }

  Map<String, Object> toWireMap() {
    Map<String, Object> inputFormat = new LinkedHashMap<>();
    inputFormat.put("type", "audio/pcm");
    inputFormat.put("rate", 24000);

    Map<String, Object> input = new LinkedHashMap<>();
    input.put("format", inputFormat);
    Map<String, Object> noiseReductionConfig = noiseReductionConfig();
    if (noiseReductionConfig != null) {
      input.put("noise_reduction", noiseReductionConfig);
    }
    input.put("transcription", Map.of("model", transcriptionModel));
    Map<String, Object> turnDetection = turnDetectionConfig();
    if (turnDetection != null) {
      input.put("turn_detection", turnDetection);
    }

    Map<String, Object> outputFormat = new LinkedHashMap<>();
    outputFormat.put("type", "audio/pcm");
    outputFormat.put("rate", 24000);
    Map<String, Object> output = new LinkedHashMap<>();
    output.put("format", outputFormat);
    if (voice != null) {
      output.put("voice", voice);
    }

    Map<String, Object> session = new LinkedHashMap<>();
    session.put("type", "realtime");
    session.put("audio", Map.of("input", input, "output", output));
    if (reasoningEffort != null) {
      session.put("reasoning", Map.of("effort", reasoningEffort));
    }
    session.put("instructions", composedInstructions());
    session.put("output_modalities", List.of("audio"));

    List<Map<String, Object>> toolMaps = new ArrayList<>();
    for (RealtimeAdapterModels.ToolDefinition tool : tools) {
      Map<String, Object> toolMap = new LinkedHashMap<>();
      toolMap.put("type", "function");
      toolMap.put("name", tool.name());
      if (tool.description() != null) {
        toolMap.put("description", tool.description());
      }
      toolMap.put("parameters", normalizeParametersSchema(tool.parameters()));
      toolMaps.add(toolMap);
    }
    session.put("tools", toolMaps);
    session.put(
        "tool_choice", tools.isEmpty() ? "none" : (toolChoiceRequired ? "required" : "auto"));
    return session;
  }

  private String composedInstructions() {
    if (serverInstructions == null || serverInstructions.isEmpty()) {
      return clientInstructions == null ? "" : clientInstructions;
    }
    if (clientInstructions == null || clientInstructions.isEmpty()) {
      return serverInstructions;
    }
    return serverInstructions + "\n\n" + clientInstructions;
  }

  private Map<String, Object> turnDetectionConfig() {
    return switch (audioTurnMode) {
      case VOICE_ACTIVITY -> {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("type", "semantic_vad");
        config.put("eagerness", "low");
        config.put("create_response", true);
        config.put("interrupt_response", true);
        yield config;
      }
      case MANUAL -> null;
    };
  }

  private Map<String, Object> noiseReductionConfig() {
    return switch (noiseReduction) {
      case OFF -> null;
      case NEAR_FIELD -> Map.of("type", "near_field");
      case FAR_FIELD -> Map.of("type", "far_field");
    };
  }

  /** Adds only missing JSON Schema structure; existing tool schemas remain otherwise untouched. */
  private static Map<String, Object> normalizeParametersSchema(Map<String, Object> parameters) {
    if (parameters == null || parameters.isEmpty()) {
      return Map.of("type", "object", "properties", Map.of());
    }
    Object type = parameters.get("type");
    if (type == null) {
      Map<String, Object> result = new LinkedHashMap<>(parameters);
      result.put("type", "object");
      result.putIfAbsent("properties", Map.of());
      return result;
    }
    if ("object".equals(type) && !parameters.containsKey("properties")) {
      Map<String, Object> result = new LinkedHashMap<>(parameters);
      result.put("properties", Map.of());
      return result;
    }
    return parameters;
  }
}
