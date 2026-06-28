package app.vagina.server.realtime.oai_cc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class OaiCcEventParserTest {
  private final OaiCcEvent.Parser parser = new OaiCcEvent.Parser(new ObjectMapper());

  @Test
  void parsesContentDelta() {
    OaiCcEvent event =
        parser.parseLine("data: {\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}");

    OaiCcEvent.ContentDelta content = assertInstanceOf(OaiCcEvent.ContentDelta.class, event);
    assertEquals("hello", content.content());
  }

  @Test
  void parsesToolCallDelta() {
    OaiCcEvent event =
        parser.parseLine(
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":2,\"id\":\"call_1\",\"function\":{\"name\":\"search\",\"arguments\":\"{\\\"q\\\"\"}}]}}]}");

    OaiCcEvent.ToolCallDelta tool = assertInstanceOf(OaiCcEvent.ToolCallDelta.class, event);
    assertEquals(2, tool.index());
    assertEquals("call_1", tool.id());
    assertEquals("search", tool.name());
    assertEquals("{\"q\"", tool.arguments());
  }

  @Test
  void parsesAudioDelta() {
    OaiCcEvent event =
        parser.parseLine(
            "data: {\"choices\":[{\"delta\":{\"audio\":{\"id\":\"aud_1\",\"data\":\"AQI=\",\"transcript\":\"hi\"}}}]}");

    OaiCcEvent.AudioDelta audio = assertInstanceOf(OaiCcEvent.AudioDelta.class, event);
    assertEquals("aud_1", audio.audioId());
    assertEquals("AQI=", audio.audioBase64());
    assertEquals("hi", audio.transcript());
  }

  @Test
  void parsesFinishedEventsAndIgnoresNonDataLines() {
    assertInstanceOf(OaiCcEvent.Finished.class, parser.parseLine("data: [DONE]"));
    assertNull(parser.parseLine(":"));
    assertNull(parser.parseLine(""));
  }
}
