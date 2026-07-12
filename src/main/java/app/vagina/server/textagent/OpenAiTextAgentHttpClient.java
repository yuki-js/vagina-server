package app.vagina.server.textagent;

import app.vagina.server.domain.error.ExternalServiceException;
import app.vagina.server.support.Constants;
import app.vagina.server.textagent.TextAgentRuntimeModels.ProviderContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.jboss.logging.Logger;

final class OpenAiTextAgentHttpClient {
  private static final Logger LOG = Logger.getLogger(OpenAiTextAgentHttpClient.class);
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

  <T> PostJsonResult<T> postJson(
      ProviderContext context, URI uri, Object body, Class<T> responseType, String failureMessage) {
    String requestBody = writeJson(body, failureMessage);
    try {
      HttpRequest.Builder builder =
          HttpRequest.newBuilder(uri)
              .header("Content-Type", "application/json")
              .header("Accept", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(requestBody));
      applyApiKeyHeaders(context, builder);
      HttpResponse<String> response =
          httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
      return classifyResponse(context, uri, response, responseType, failureMessage);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOG.warnf(
          e,
          "Text agent upstream HTTP request interrupted: provider=%s textModelId=%s providerModel=%s uri=%s",
          context.binding().provider(),
          context.binding().textModelId(),
          context.binding().providerModelName(),
          uri);
      throw new RetryableTextAgentProviderException(failureMessage, e);
    } catch (RetryableTextAgentProviderException e) {
      throw e;
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
      throw new RetryableTextAgentProviderException(failureMessage, e);
    }
  }

  private <T> PostJsonResult<T> classifyResponse(
      ProviderContext context,
      URI uri,
      HttpResponse<String> response,
      Class<T> responseType,
      String failureMessage) {
    int status = response.statusCode();
    if (status >= 200 && status < 300) {
      return PostJsonResult.success(
          readSuccessResponse(context, uri, response, responseType, failureMessage));
    }
    if (status == 429) {
      warnNonSuccessStatus(context, uri, response);
      throw new RetryableTextAgentProviderException(failureMessage);
    }
    if (status >= 400 && status < 500) {
      return PostJsonResult.providerFailure(
          readProviderFailure(context, uri, response, failureMessage));
    }
    warnNonSuccessStatus(context, uri, response);
    if (status >= 500 && status < 600) {
      throw new RetryableTextAgentProviderException(failureMessage);
    }
    throw new ExternalServiceException(failureMessage);
  }

  private <T> T readSuccessResponse(
      ProviderContext context,
      URI uri,
      HttpResponse<String> response,
      Class<T> responseType,
      String failureMessage) {
    try {
      return objectMapper.readValue(response.body(), responseType);
    } catch (Exception e) {
      warnResponseParseFailure(context, uri, response, e);
      throw new ExternalServiceException(failureMessage, e);
    }
  }

  private ProviderFailure readProviderFailure(
      ProviderContext context, URI uri, HttpResponse<String> response, String failureMessage) {
    JsonNode error;
    try {
      JsonNode root = objectMapper.readTree(response.body());
      error = root == null ? null : root.get("error");
      if (error == null || !error.isObject()) {
        throw new IllegalArgumentException("Response does not contain an error object");
      }
    } catch (Exception e) {
      warnResponseParseFailure(context, uri, response, e);
      throw new ExternalServiceException(failureMessage, e);
    }

    String providerCode = text(error, "code");
    String providerType = text(error, "type");
    LOG.warnf(
        "Text agent upstream HTTP request was rejected: provider=%s textModelId=%s providerModel=%s uri=%s status=%d providerErrorCode=%s providerErrorType=%s responseBodyLength=%d",
        context.binding().provider(),
        context.binding().textModelId(),
        context.binding().providerModelName(),
        uri,
        response.statusCode(),
        logValue(providerCode),
        logValue(providerType),
        responseBodyLength(response.body()));
    return sanitizedProviderFailure(response.statusCode());
  }

  private ProviderFailure sanitizedProviderFailure(int status) {
    return switch (status) {
      case 401, 403 ->
          new ProviderFailure(
              "provider_authentication_error",
              "This AI model is currently unavailable. Please contact the service administrator.");
      case 429 ->
          new ProviderFailure(
              "provider_rate_limited",
              "This AI model is temporarily busy. Please try again later.");
      default ->
          new ProviderFailure("provider_request_error", "The AI provider rejected this request.");
    };
  }

  private String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? null : value.asText();
  }

  private String logValue(String value) {
    return value == null || value.isBlank() ? "<none>" : value;
  }

  private void warnNonSuccessStatus(
      ProviderContext context, URI uri, HttpResponse<String> response) {
    LOG.warnf(
        "Text agent upstream HTTP request returned non-success status: provider=%s textModelId=%s providerModel=%s uri=%s status=%d responseBodyLength=%d",
        context.binding().provider(),
        context.binding().textModelId(),
        context.binding().providerModelName(),
        uri,
        response.statusCode(),
        responseBodyLength(response.body()));
  }

  private void warnResponseParseFailure(
      ProviderContext context, URI uri, HttpResponse<String> response, Exception exception) {
    LOG.warnf(
        exception,
        "Text agent upstream HTTP response parse failed: provider=%s textModelId=%s providerModel=%s uri=%s status=%d responseBodyLength=%d",
        context.binding().provider(),
        context.binding().textModelId(),
        context.binding().providerModelName(),
        uri,
        response.statusCode(),
        responseBodyLength(response.body()));
  }

  private int responseBodyLength(String responseBody) {
    return responseBody == null ? 0 : responseBody.length();
  }

  private void applyApiKeyHeaders(ProviderContext context, HttpRequest.Builder builder) {
    String key = context.binding().apiKey();
    if (Constants.NO_AUTH_API_KEY.equals(key)) {
      return;
    }
    builder.header("Authorization", "Bearer " + key);
    builder.header("api-key", key);
  }

  record PostJsonResult<T>(T body, ProviderFailure providerFailure) {
    static <T> PostJsonResult<T> success(T body) {
      return new PostJsonResult<>(body, null);
    }

    static <T> PostJsonResult<T> providerFailure(ProviderFailure providerFailure) {
      return new PostJsonResult<>(null, providerFailure);
    }

    boolean rejectedByProvider() {
      return providerFailure != null;
    }
  }

  record ProviderFailure(String code, String message) {}
}
