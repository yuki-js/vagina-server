package app.vagina.server.service.oidcprovider;

import app.vagina.server.domain.error.ExternalServiceException;
import app.vagina.server.support.Util;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.vertx.mutiny.core.buffer.Buffer;
import io.vertx.mutiny.ext.web.client.HttpResponse;
import io.vertx.mutiny.ext.web.client.WebClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Iterator;
import java.util.Optional;

@ApplicationScoped
public class GitHubOidcProvider extends OidcProviderBase {
  @ConfigMapping(prefix = "vagina.auth.oidc.github")
  public interface GitHubOidcProviderInfo extends OidcProviderInfo {
    @Override
    @WithDefault("https://github.com/login/oauth/.well-known/openid-configuration")
    Optional<String> configurationUrl();

    @Override
    Optional<String> authorizationEndpoint();

    @Override
    Optional<String> tokenEndpoint();

    @Override
    Optional<String> userinfoEndpoint();

    @WithDefault("https://api.github.com/user")
    String userApiEndpoint();

    @WithDefault("https://api.github.com/user/emails")
    Optional<String> userEmailsEndpoint();
  }

  @Inject GitHubOidcProviderInfo gitHubOidcProviderInfo;

  @Override
  public String getProviderKey() {
    return "github";
  }

  @Override
  public OidcProviderInfo getProviderConfiguration() {
    return gitHubOidcProviderInfo;
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
    WebClient client = WebClient.create(vertx);
    HttpResponse<Buffer> response;
    try {
      response =
          client
              .getAbs(gitHubOidcProviderInfo.userApiEndpoint())
              .putHeader("Authorization", "Bearer " + accessToken)
              .putHeader("Accept", "application/json")
              .send()
              .await()
              .indefinitely();
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

      Optional<EmailInfo> emailInfo = fetchPrimaryVerifiedEmail(client, accessToken);
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

  private Optional<EmailInfo> fetchPrimaryVerifiedEmail(WebClient client, String accessToken) {
    if (gitHubOidcProviderInfo.userEmailsEndpoint().isEmpty()) {
      return Optional.empty();
    }

    HttpResponse<Buffer> response =
        client
            .getAbs(gitHubOidcProviderInfo.userEmailsEndpoint().get())
            .putHeader("Authorization", "Bearer " + accessToken)
            .putHeader("Accept", "application/json")
            .send()
            .await()
            .indefinitely();

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
