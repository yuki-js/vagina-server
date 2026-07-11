package app.vagina.server.realtime.oai_cc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class OaiCcEventParserTest {
  private final OaiCcEvent.Parser parser = new OaiCcEvent.Parser(new ObjectMapper());

  @Test
  void parsesContentDelta() {
    OaiCcEvent event =
        parser.parseLine("data: {\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}").getFirst();

    OaiCcEvent.ContentDelta content = assertInstanceOf(OaiCcEvent.ContentDelta.class, event);
    assertEquals("hello", content.content());
  }

  @Test
  void parsesToolCallDelta() {
    OaiCcEvent event =
        parser
            .parseLine(
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":2,\"id\":\"call_1\",\"function\":{\"name\":\"search\",\"arguments\":\"{\\\"q\\\"\"}}]}}]}")
            .getFirst();

    OaiCcEvent.ToolCallDelta tool = assertInstanceOf(OaiCcEvent.ToolCallDelta.class, event);
    assertEquals(2, tool.index());
    assertEquals("call_1", tool.id());
    assertEquals("search", tool.name());
    assertEquals("{\"q\"", tool.arguments());
  }

  @Test
  void parsesEveryToolCallDeltaInOneChunkInProviderOrder() {
    List<OaiCcEvent> events =
        parser.parseLine(
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":["
                + "{\"index\":0,\"id\":\"call_1\",\"function\":{\"name\":\"first\",\"arguments\":\"{}\"}},"
                + "{\"index\":1,\"id\":\"call_2\",\"function\":{\"name\":\"second\",\"arguments\":\"{\\\"x\\\":1}\"}}]}}]}");

    assertEquals(2, events.size());
    OaiCcEvent.ToolCallDelta first =
        assertInstanceOf(OaiCcEvent.ToolCallDelta.class, events.get(0));
    OaiCcEvent.ToolCallDelta second =
        assertInstanceOf(OaiCcEvent.ToolCallDelta.class, events.get(1));
    assertEquals(0, first.index());
    assertEquals("call_1", first.id());
    assertEquals("first", first.name());
    assertEquals(1, second.index());
    assertEquals("call_2", second.id());
    assertEquals("second", second.name());
    assertEquals("{\"x\":1}", second.arguments());
  }

  @Test
  void parsesAudioDelta() {
    OaiCcEvent event =
        parser
            .parseLine(
                "data: {\"choices\":[{\"delta\":{\"audio\":{\"id\":\"aud_1\",\"data\":\"AQI=\",\"transcript\":\"hi\"}}}]}")
            .getFirst();

    OaiCcEvent.AudioDelta audio = assertInstanceOf(OaiCcEvent.AudioDelta.class, event);
    assertEquals("aud_1", audio.audioId());
    assertEquals("AQI=", audio.audioBase64());
    assertEquals("hi", audio.transcript());
  }

  @Test
  void parsesFinishedEventsAndIgnoresNonDataLines() {
    assertInstanceOf(OaiCcEvent.Finished.class, parser.parseLine("data: [DONE]").getFirst());
    assertTrue(parser.parseLine(":").isEmpty());
    assertTrue(parser.parseLine("").isEmpty());
  }
}
