package app.vagina.server.textagent;

import app.vagina.server.domain.error.ExternalServiceException;
import app.vagina.server.textagent.TextAgentRuntimeModels.ProviderContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

final class OpenAiTextAgentHttpClient {
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;

  OpenAiTextAgentHttpClient(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
    this.httpClient = HttpClient.newHttpClient();
  }

  String writeJson(Object body, String failureMessage) {
    try {
      return objectMapper.writeValueAsString(body);
    } catch (Exception e) {
      throw new IllegalStateException(failureMessage, e);
    }
  }

  <T> T postJson(
      ProviderContext context, URI uri, Object body, Class<T> responseType, String failureMessage) {
    try {
      HttpRequest.Builder builder =
          HttpRequest.newBuilder(uri)
              .header("Content-Type", "application/json")
              .header("Accept", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
      applyApiKeyHeaders(context, builder);
      HttpResponse<String> response =
          httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
      return objectMapper.readValue(response.body(), responseType);
    } catch (Exception e) {
      throw new ExternalServiceException(failureMessage, e);
    }
  }

  private void applyApiKeyHeaders(ProviderContext context, HttpRequest.Builder builder) {
    context
        .binding()
        .apiKey()
        .filter(key -> !key.isBlank())
        .ifPresent(
            key -> {
              builder.header("Authorization", "Bearer " + key);
              builder.header("api-key", key);
            });
  }
}
