package app.vagina.server.textagent;

import app.vagina.server.domain.error.ExternalServiceException;
import app.vagina.server.textagent.TextAgentRuntimeModels.ProviderContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.jboss.logging.Logger;

final class OpenAiTextAgentHttpClient {
  private static final Logger LOG = Logger.getLogger(OpenAiTextAgentHttpClient.class);
  private static final int MAX_LOGGED_RESPONSE_BODY_LENGTH = 2048;

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
      String requestBody = objectMapper.writeValueAsString(body);
      HttpRequest.Builder builder =
          HttpRequest.newBuilder(uri)
              .header("Content-Type", "application/json")
              .header("Accept", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(requestBody));
      applyApiKeyHeaders(context, builder);
      HttpResponse<String> response =
          httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
      warnIfNonSuccessStatus(context, uri, response);
      return readResponseBody(context, uri, response, responseType, failureMessage);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOG.warnf(
          e,
          "Text agent upstream HTTP request interrupted: provider=%s textModelId=%s providerModel=%s uri=%s",
          context.binding().provider(),
          context.binding().textModelId(),
          context.binding().providerModelName(),
          uri);
      throw new ExternalServiceException(failureMessage, e);
    } catch (ExternalServiceException e) {
      throw e;
    } catch (Exception e) {
      LOG.warnf(
          e,
          "Text agent upstream HTTP request failed: provider=%s textModelId=%s providerModel=%s uri=%s",
          context.binding().provider(),
          context.binding().textModelId(),
          context.binding().providerModelName(),
          uri);
      throw new ExternalServiceException(failureMessage, e);
    }
  }

  private void warnIfNonSuccessStatus(
      ProviderContext context, URI uri, HttpResponse<String> response) {
    if (response.statusCode() < 400) {
      return;
    }
    LOG.warnf(
        "Text agent upstream HTTP request returned non-success status: provider=%s textModelId=%s providerModel=%s uri=%s status=%d responseBody=%s",
        context.binding().provider(),
        context.binding().textModelId(),
        context.binding().providerModelName(),
        uri,
        response.statusCode(),
        loggedResponseBody(response.body()));
  }

  private <T> T readResponseBody(
      ProviderContext context,
      URI uri,
      HttpResponse<String> response,
      Class<T> responseType,
      String failureMessage) {
    try {
      return objectMapper.readValue(response.body(), responseType);
    } catch (Exception e) {
      LOG.warnf(
          e,
          "Text agent upstream HTTP response parse failed: provider=%s textModelId=%s providerModel=%s uri=%s status=%d responseBody=%s",
          context.binding().provider(),
          context.binding().textModelId(),
          context.binding().providerModelName(),
          uri,
          response.statusCode(),
          loggedResponseBody(response.body()));
      throw new ExternalServiceException(failureMessage, e);
    }
  }

  private String loggedResponseBody(String responseBody) {
    if (responseBody == null) {
      return "<null>";
    }
    if (responseBody.length() <= MAX_LOGGED_RESPONSE_BODY_LENGTH) {
      return responseBody;
    }
    return responseBody.substring(0, MAX_LOGGED_RESPONSE_BODY_LENGTH) + "...<truncated>";
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
