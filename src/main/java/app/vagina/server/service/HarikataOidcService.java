package app.vagina.server.service;

import app.vagina.server.service.model.OidcTokenSet;
import app.vagina.server.service.model.OidcUserInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class HarigataOidcService {

  @ConfigProperty(name = "vagina.auth.oidc.harigata.client-id", defaultValue = "")
  String clientId;

  @ConfigProperty(name = "vagina.auth.oidc.harigata.client-secret", defaultValue = "")
  String clientSecret;

  @ConfigProperty(name = "vagina.auth.oidc.harigata.authorize-url", defaultValue = "")
  String authorizeUrl;

  @ConfigProperty(name = "vagina.auth.oidc.harigata.token-url", defaultValue = "")
  String tokenUrl;

  @ConfigProperty(name = "vagina.auth.oidc.harigata.userinfo-url", defaultValue = "")
  String userInfoUrl;

  @Inject
  ObjectMapper objectMapper;

  private final HttpClient httpClient = HttpClient.newHttpClient();

  public String buildAuthorizationUrl(
      String redirectUri, String state, String codeChallenge, String codeChallengeMethod) {
    requireConfigured(authorizeUrl, "authorize-url");
    requireConfigured(clientId, "client-id");

    Map<String, String> queryParams = new LinkedHashMap<>();
    queryParams.put("response_type", "code");
    queryParams.put("client_id", clientId);
    queryParams.put("redirect_uri", redirectUri);
    queryParams.put("scope", "openid profile email");
    queryParams.put("state", state);
    queryParams.put("code_challenge", codeChallenge);
    queryParams.put("code_challenge_method", codeChallengeMethod);

    return authorizeUrl + "?" + formEncode(queryParams);
  }

  public OidcTokenSet exchangeAuthorizationCode(
      String code, String redirectUri, String codeVerifier) {
    requireConfigured(tokenUrl, "token-url");
    requireConfigured(clientId, "client-id");
    requireConfigured(clientSecret, "client-secret");

    Map<String, String> form = new LinkedHashMap<>();
    form.put("grant_type", "authorization_code");
    form.put("client_id", clientId);
    form.put("client_secret", clientSecret);
    form.put("code", code);
    form.put("redirect_uri", redirectUri);
    form.put("code_verifier", codeVerifier);

    HttpRequest request = HttpRequest.newBuilder(URI.create(tokenUrl))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString(formEncode(form)))
        .build();

    try {
      HttpResponse<String> response = httpClient.send(request,
          HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() != 200) {
        throw new IllegalStateException(
            "OIDC token endpoint returned status " + response.statusCode());
      }

      JsonNode json = objectMapper.readTree(response.body());
      String accessToken = requiredText(json, "access_token");
      String idToken = requiredText(json, "id_token");
      long expiresIn = json.path("expires_in").asLong(3600L);
      return new OidcTokenSet(accessToken, idToken, expiresIn);
    } catch (IOException | InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Failed to exchange OIDC authorization code", e);
    }
  }

  public OidcUserInfo fetchUserInfo(String accessToken) {
    requireConfigured(userInfoUrl, "userinfo-url");

    HttpRequest request = HttpRequest.newBuilder(URI.create(userInfoUrl))
        .header("Authorization", "Bearer " + accessToken)
        .GET()
        .build();

    try {
      HttpResponse<String> response = httpClient.send(request,
          HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() != 200) {
        throw new IllegalStateException(
            "OIDC userinfo endpoint returned status " + response.statusCode());
      }

      JsonNode json = objectMapper.readTree(response.body());
      return new OidcUserInfo(
          requiredText(json, "sub"),
          optionalText(json, "preferred_username"),
          optionalText(json, "name"),
          optionalText(json, "picture"),
          optionalText(json, "email"),
          json.path("email_verified").asBoolean(false),
          response.body());
    } catch (IOException | InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Failed to fetch OIDC userinfo", e);
    }
  }

  private String formEncode(Map<String, String> values) {
    StringJoiner joiner = new StringJoiner("&");
    values.forEach(
        (key, value) -> joiner.add(
            URLEncoder.encode(key, StandardCharsets.UTF_8)
                + "="
                + URLEncoder.encode(value, StandardCharsets.UTF_8)));
    return joiner.toString();
  }

  private String requiredText(JsonNode node, String fieldName) {
    String value = optionalText(node, fieldName);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("Missing required OIDC field: " + fieldName);
    }
    return value;
  }

  private String optionalText(JsonNode node, String fieldName) {
    JsonNode child = node.get(fieldName);
    if (child == null || child.isNull()) {
      return null;
    }
    String value = child.asText();
    return value == null || value.isBlank() ? null : value;
  }

  private void requireConfigured(String value, String key) {
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("Missing Harigata OIDC configuration: " + key);
    }
  }
}
