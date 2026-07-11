package app.vagina.server.service.oidcprovider;

import app.vagina.server.config.OidcConfig;
import app.vagina.server.domain.error.ExternalServiceException;
import app.vagina.server.support.Util;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.core.buffer.Buffer;
import io.vertx.mutiny.ext.web.client.HttpResponse;
import java.util.Iterator;
import java.util.Optional;

public final class GitHubOidcProvider extends OidcProviderBase {
  private final OidcConfig.ProviderConfig providerConfiguration;

  public GitHubOidcProvider(
      OidcConfig.ProviderConfig providerConfiguration, Vertx vertx, ObjectMapper objectMapper) {
    this.providerConfiguration = providerConfiguration;
    this.vertx = vertx;
    this.objectMapper = objectMapper;
    init();
  }

  @Override
  public String getProviderKey() {
    return "github";
  }

  @Override
  public OidcProviderInfo getProviderConfiguration() {
    return providerConfiguration;
  }

  @Override
  public OidcUserInfo fetchUserInfo(String accessToken) throws UnsupportedOperationException {
    try {
      return super.fetchUserInfo(accessToken);
    } catch (UnsupportedOperationException ignored) {
      return fetchUserInfoFromGitHubApi(accessToken);
    }
  }

  private OidcUserInfo fetchUserInfoFromGitHubApi(String accessToken) {
    HttpResponse<Buffer> response;
    try {
      response =
          getWebClient()
              .getAbs(providerConfiguration.userApiEndpoint())
              .putHeader("Authorization", "Bearer " + accessToken)
              .putHeader("Accept", "application/json")
              .send()
              .await()
              .atMost(HTTP_TIMEOUT);
    } catch (RuntimeException e) {
      throw new ExternalServiceException("GitHub user API request failed", e);
    }

    if (response.statusCode() != 200) {
      throw new ExternalServiceException(
          "GitHub user API endpoint returned status " + response.statusCode());
    }

    String rawProfile = response.bodyAsString();
    try {
      JsonNode json = objectMapper.readTree(rawProfile);
      String subject = Util.requiredText(json, "id");
      String providerLogin = Util.optionalText(json, "login");
      String displayName = Util.optionalText(json, "name");
      String avatarUrl = Util.optionalText(json, "avatar_url");
      String email = Util.optionalText(json, "email");
      boolean emailVerified = false;

      Optional<EmailInfo> emailInfo = fetchPrimaryVerifiedEmail(accessToken);
      if (emailInfo.isPresent()) {
        email = emailInfo.get().email();
        emailVerified = emailInfo.get().verified();
      }

      return new OidcUserInfo(
          subject, providerLogin, displayName, avatarUrl, email, emailVerified, rawProfile);
    } catch (Exception e) {
      throw new ExternalServiceException("Failed to parse GitHub user API response", e);
    }
  }

  private Optional<EmailInfo> fetchPrimaryVerifiedEmail(String accessToken) {
    if (providerConfiguration.userEmailsEndpoint().isEmpty()) {
      return Optional.empty();
    }

    HttpResponse<Buffer> response;
    try {
      response =
          getWebClient()
              .getAbs(providerConfiguration.userEmailsEndpoint().get())
              .putHeader("Authorization", "Bearer " + accessToken)
              .putHeader("Accept", "application/json")
              .send()
              .await()
              .atMost(HTTP_TIMEOUT);
    } catch (RuntimeException e) {
      throw new ExternalServiceException("GitHub emails API request failed", e);
    }

    if (response.statusCode() != 200) {
      return Optional.empty();
    }

    try {
      JsonNode json = objectMapper.readTree(response.bodyAsString());
      if (!json.isArray()) {
        return Optional.empty();
      }

      Iterator<JsonNode> entries = json.elements();
      while (entries.hasNext()) {
        JsonNode entry = entries.next();
        if (entry.path("primary").asBoolean(false)) {
          String email = Util.optionalText(entry, "email");
          if (email != null && !email.isBlank()) {
            return Optional.of(new EmailInfo(email, entry.path("verified").asBoolean(false)));
          }
        }
      }

      entries = json.elements();
      while (entries.hasNext()) {
        JsonNode entry = entries.next();
        if (entry.path("verified").asBoolean(false)) {
          String email = Util.optionalText(entry, "email");
          if (email != null && !email.isBlank()) {
            return Optional.of(new EmailInfo(email, true));
          }
        }
      }

      if (!json.isEmpty()) {
        String email = Util.optionalText(json.get(0), "email");
        if (email != null && !email.isBlank()) {
          return Optional.of(new EmailInfo(email, false));
        }
      }

      return Optional.empty();
    } catch (JsonProcessingException e) {
      return Optional.empty();
    }
  }

  private record EmailInfo(String email, boolean verified) {}
}
