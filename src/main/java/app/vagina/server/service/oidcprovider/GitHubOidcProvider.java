package app.vagina.server.service.oidcprovider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class GitHubOidcProvider extends OidcProviderBase {

  @ConfigProperty(name = "vagina.auth.oidc.github.client-id", defaultValue = "UNCONFIGURED")
  String clientId;

  @ConfigProperty(name = "vagina.auth.oidc.github.client-secret", defaultValue = "UNCONFIGURED")
  String clientSecret;

  @ConfigProperty(name = "vagina.auth.oidc.github.authorize-url", defaultValue = "UNCONFIGURED")
  String authorizeUrl;

  @ConfigProperty(name = "vagina.auth.oidc.github.token-url", defaultValue = "UNCONFIGURED")
  String tokenUrl;

  @ConfigProperty(name = "vagina.auth.oidc.github.userinfo-url", defaultValue = "UNCONFIGURED")
  String userInfoUrl;

  @ConfigProperty(name = "vagina.auth.oidc.github.emails-url", defaultValue = "UNCONFIGURED")
  String emailsUrl;

  @Inject
  ObjectMapper objectMapper;

  private final HttpClient httpClient = HttpClient.newHttpClient();

  @Override
  public String getProviderKey() {
    return "github";
  }

  @Override
  public String buildAuthorizationUrl(
      String redirectUri, String state, String codeChallenge, String codeChallengeMethod) {
    requireConfigured(authorizeUrl, "authorize-url");
    requireConfigured(clientId, "client-id");

    Map<String, String> queryParams = new LinkedHashMap<>();
    queryParams.put("client_id", clientId);
    queryParams.put("redirect_uri", redirectUri);
    queryParams.put("scope", "read:user user:email");
    queryParams.put("state", state);

    return authorizeUrl + "?" + formEncode(queryParams);
  }

  @Override
  public OidcTokenSet exchangeAuthorizationCode(
      String code, String redirectUri, String codeVerifier) {
    requireConfigured(tokenUrl, "token-url");
    requireConfigured(clientId, "client-id");
    requireConfigured(clientSecret, "client-secret");

    Map<String, String> form = new LinkedHashMap<>();
    form.put("client_id", clientId);
    form.put("client_secret", clientSecret);
    form.put("code", code);
    form.put("redirect_uri", redirectUri);

    HttpRequest request = HttpRequest.newBuilder(URI.create(tokenUrl))
        .header("Accept", "application/json")
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString(formEncode(form)))
        .build();

    try {
      HttpResponse<String> response = httpClient.send(request,
          HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() != 200) {
        throw new IllegalStateException(
            "GitHub token endpoint returned status " + response.statusCode());
      }

      JsonNode json = objectMapper.readTree(response.body());
      String accessToken = requiredText(json, "access_token");
      long expiresIn = json.path("expires_in").asLong(3600L);
      return new OidcTokenSet(accessToken, null, expiresIn);
    } catch (IOException | InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Failed to exchange GitHub authorization code", e);
    }
  }

  @Override
  public OidcUserInfo fetchUserInfo(String accessToken) {
    requireConfigured(userInfoUrl, "userinfo-url");

    HttpRequest request = HttpRequest.newBuilder(URI.create(userInfoUrl))
        .header("Authorization", "Bearer " + accessToken)
        .header("Accept", "application/json")
        .GET()
        .build();

    try {
      HttpResponse<String> response = httpClient.send(request,
          HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() != 200) {
        throw new IllegalStateException(
            "GitHub userinfo endpoint returned status " + response.statusCode());
      }

      JsonNode json = objectMapper.readTree(response.body());
      String subject = requiredText(json, "id");
      String providerLogin = optionalText(json, "login");
      String displayName = optionalText(json, "name");
      String avatarUrl = optionalText(json, "avatar_url");
      String email = optionalText(json, "email");
      boolean emailVerified = false;

      // GitHub /user endpoint does not always include email and never includes verified status.
      // Fetch from /user/emails if email is missing or we want verified status.
      if (emailsUrl != null && !emailsUrl.isBlank()) {
        EmailInfo emailInfo = fetchPrimaryVerifiedEmail(accessToken);
        if (emailInfo != null) {
          email = emailInfo.email;
          emailVerified = emailInfo.verified;
        }
      }

      return new OidcUserInfo(
          subject,
          providerLogin,
          displayName,
          avatarUrl,
          email,
          emailVerified,
          response.body());
    } catch (IOException | InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Failed to fetch GitHub userinfo", e);
    }
  }

  private EmailInfo fetchPrimaryVerifiedEmail(String accessToken) {
    HttpRequest request = HttpRequest.newBuilder(URI.create(emailsUrl))
        .header("Authorization", "Bearer " + accessToken)
        .header("Accept", "application/json")
        .GET()
        .build();

    try {
      HttpResponse<String> response = httpClient.send(request,
          HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() != 200) {
        return null;
      }

      JsonNode json = objectMapper.readTree(response.body());
      if (!json.isArray()) {
        return null;
      }

      for (JsonNode entry : json) {
        boolean primary = entry.path("primary").asBoolean(false);
        if (primary) {
          String email = optionalText(entry, "email");
          boolean verified = entry.path("verified").asBoolean(false);
          return new EmailInfo(email, verified);
        }
      }

      // Fallback to first verified email if no primary found
      for (JsonNode entry : json) {
        boolean verified = entry.path("verified").asBoolean(false);
        if (verified) {
          String email = optionalText(entry, "email");
          return new EmailInfo(email, true);
        }
      }

      // Fallback to first email at all
      if (!json.isEmpty()) {
        String email = optionalText(json.get(0), "email");
        return new EmailInfo(email, false);
      }

      return null;
    } catch (IOException | InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    }
  }

  private void requireConfigured(String value, String key) {
    if (value == null || value.isBlank() || "UNCONFIGURED".equals(value)) {
      throw new IllegalStateException("Missing GitHub OIDC configuration: " + key);
    }
  }

  private record EmailInfo(String email, boolean verified) {}
}
