package app.vagina.server.realtime.oai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.vagina.server.realtime.model.RealtimeAdapterModels;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Direct contracts for the session-policy seam extracted from the adapter facade. */
class OaiRealtimeSessionConfigTest {

  @Test
  void rendersProviderSessionAndNormalizesOnlyMissingToolSchemaStructure() {
    OaiRealtimeSessionConfig config = new OaiRealtimeSessionConfig();
    config.initialize("alloy", "server", "gpt-4o-transcribe");
    config.setClientInstructions("client");
    config.setTools(
        List.of(
            new RealtimeAdapterModels.ToolDefinition(
                "probe", "Probe tool", Map.of("required", List.of("value")))));

    Map<String, Object> session = config.toWireMap();

    assertEquals("server\n\nclient", session.get("instructions"));
    assertEquals("auto", session.get("tool_choice"));
    @SuppressWarnings("unchecked")
    Map<String, Object> tool = ((List<Map<String, Object>>) session.get("tools")).getFirst();
    @SuppressWarnings("unchecked")
    Map<String, Object> parameters = (Map<String, Object>) tool.get("parameters");
    assertEquals("object", parameters.get("type"));
    assertEquals(Map.of(), parameters.get("properties"));
    assertEquals(List.of("value"), parameters.get("required"));
  }

  @Test
  void rendersServerOwnedReasoningEffortOnlyWhenConfigured() {
    OaiRealtimeSessionConfig config = new OaiRealtimeSessionConfig();
    config.initialize("alloy", "server", "gpt-4o-transcribe");

    assertFalse(config.toWireMap().containsKey("reasoning"));

    config.setReasoningEffort("high");

    assertEquals(Map.of("effort", "high"), config.toWireMap().get("reasoning"));
    assertFalse(config.supportedExtensions().contains("session.reasoning_effort_selection"));
  }

  @Test
  void extensionResultSeparatesUnsupportedUnchangedAndChangedMutations() {
    OaiRealtimeSessionConfig config = new OaiRealtimeSessionConfig();

    OaiRealtimeSessionConfig.ExtensionResult unsupported =
        config.applyExtension("session.unknown", Map.of());
    assertFalse(unsupported.supported());

    OaiRealtimeSessionConfig.ExtensionResult unchanged =
        config.applyExtension(
            OaiRealtimeSessionConfig.EXT_INPUT_NOISE_REDUCTION, Map.of("selection", "nearField"));
    assertTrue(unchanged.supported());
    assertFalse(unchanged.changed());

    OaiRealtimeSessionConfig.ExtensionResult changed =
        config.applyExtension(
            OaiRealtimeSessionConfig.EXT_TOOL_CHOICE_REQUIRED, Map.of("required", true));
    assertTrue(changed.supported());
    assertTrue(changed.changed());
  }
}
