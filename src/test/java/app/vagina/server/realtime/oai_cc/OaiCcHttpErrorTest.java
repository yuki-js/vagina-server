package app.vagina.server.realtime.oai_cc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class OaiCcHttpErrorTest {
  private final ObjectMapper json = new ObjectMapper();

  @Test
  void classifiesStructuredContextLengthError() {
    OaiCcHttpError error =
        OaiCcHttpError.parse(
            400,
            "{\"error\":{\"code\":\"context_length_exceeded\",\"type\":\"invalid_request_error\",\"param\":\"messages\"}}",
            json);

    assertTrue(error.isContextLengthExceeded());
    assertEquals("context_length_exceeded", error.providerCode());
    assertEquals("invalid_request_error", error.providerType());
    assertEquals("messages", error.providerParam());
  }

  @Test
  void doesNotClassifyWrongStatusMalformedBodyOrUnrelatedCode() {
    assertFalse(
        OaiCcHttpError.parse(429, "{\"error\":{\"code\":\"context_length_exceeded\"}}", json)
            .isContextLengthExceeded());
    assertFalse(OaiCcHttpError.parse(400, "not-json", json).isContextLengthExceeded());
    assertFalse(
        OaiCcHttpError.parse(400, "{\"error\":{\"code\":\"invalid_api_key\"}}", json)
            .isContextLengthExceeded());
  }
}
