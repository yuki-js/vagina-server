package app.vagina.server.service.oidcprovider;

import app.vagina.server.domain.error.ExternalServiceException;
import app.vagina.server.support.Constants;
import app.vagina.server.support.Util;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.logging.Log;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.core.buffer.Buffer;
import io.vertx.mutiny.ext.web.client.HttpResponse;
import io.vertx.mutiny.ext.web.client.WebClient;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public abstract class OidcProviderBase {

  protected static final Duration HTTP_TIMEOUT = Constants.SERVER_COMMON_HTTP_TIMEOUT;

  @Inject protected Vertx vertx;

  @Inject protected ObjectMapper objectMapper;

  protected WebClient webClient;

  @PostConstruct
  protected void init() {
    webClient = WebClient.create(vertx);
    Log.debugf(
        "Initialized OIDC provider with HTTP timeout of %s seconds (no automatic retries)",
        HTTP_TIMEOUT.getSeconds());
  }

  protected WebClient getWebClient() {
    return webClient;
  }

  /** Represents a set of tokens returned by the OIDC provider. */
  public record OidcTokenSet(String accessToken, String idToken, long expiresIn) {}

  /** Represents user information returned by the OIDC provider. */
  public record OidcUserInfo(
      String subject,
      String providerLogin,
      String displayName,
      String avatarUrl,
      String email,
      boolean emailVerified) {}

  public interface OidcProviderInfo {
    Optional<String> clientId();

    Optional<String> clientSecret();

    Optional<String> configurationUrl();

    Optional<String> jwksUrl();

    Optional<String> issuer();

    Optional<String> authorizationEndpoint();

    Optional<String> tokenEndpoint();

    Optional<String> userinfoEndpoint();
  }

  protected record ConfiguredOidcProviderInfo(
      String clientId,
      String clientSecret,
      String jwksUrl,
      String issuer,
      String authorizationEndpoint,
      String tokenEndpoint,
      Optional<String> userinfoEndpoint) {}

  public abstract String getProviderKey();

  public abstract OidcProviderInfo getProviderConfiguration();

  public boolean isConfigured() {
    OidcProviderInfo info = getProviderConfiguration();
    return info.clientId().filter(value -> !value.isBlank()).isPresent()
        && info.clientSecret().filter(value -> !value.isBlank()).isPresent();
  }

  protected ConfiguredOidcProviderInfo configureProvider() {
    OidcProviderInfo info = getProviderConfiguration();
    String clientId =
        info.clientId()
            .filter(value -> !value.isBlank())
            .orElseThrow(
                () -> new IllegalStateException(getProviderKey() + " OIDC clientId is required"));
    String clientSecret =
        info.clientSecret()
            .filter(value -> !value.isBlank())
            .orElseThrow(
                () ->
                    new IllegalStateException(getProviderKey() + " OIDC clientSecret is required"));

    if (info.configurationUrl().isPresent()
        && info.jwksUrl().isEmpty()
        && info.issuer().isEmpty()
        && info.authorizationEndpoint().isEmpty()
        && info.tokenEndpoint().isEmpty()) {
      // Auto-configure via OIDC discovery document
      String discoveryUrl = info.configurationUrl().get();

      HttpResponse<Buffer> response;
      try {
        response =
            webClient
                .getAbs(discoveryUrl)
                .putHeader("Accept", "application/json")
                .send()
                .await()
                .atMost(HTTP_TIMEOUT);
      } catch (RuntimeException e) {
        throw new ExternalServiceException(
            "Failed to fetch OIDC discovery document from " + discoveryUrl, e);
      }

      if (response.statusCode() != 200) {
        throw new ExternalServiceException(
            "Failed to fetch OIDC discovery document from "
                + discoveryUrl
                + ": HTTP "
                + response.statusCode());
      }

      JsonNode doc;
      try {
        doc = objectMapper.readTree(response.bodyAsString());
      } catch (JsonProcessingException e) {
        throw new ExternalServiceException(
            "Failed to parse OIDC discovery document from " + discoveryUrl, e);
      }

      String sourceDesc = "OIDC discovery document from " + discoveryUrl;
      String jwksUrl = Util.requireJsonField(doc, "jwks_uri", sourceDesc);
      String issuer = Util.requireJsonField(doc, "issuer", sourceDesc);
      String authorizationEndpoint =
          Util.requireJsonField(doc, "authorization_endpoint", sourceDesc);
      String tokenEndpoint = Util.requireJsonField(doc, "token_endpoint", sourceDesc);
      Optional<String> userinfoEndpoint =
          doc.has("userinfo_endpoint") && !doc.get("userinfo_endpoint").isNull()
              ? Optional.of(doc.get("userinfo_endpoint").asText())
              : Optional.empty();

      return new ConfiguredOidcProviderInfo(
          clientId,
          clientSecret,
          jwksUrl,
          issuer,
          authorizationEndpoint,
          tokenEndpoint,
          userinfoEndpoint);
    } else {
      // Use the directly-provided endpoint configuration
      String jwksUrl =
          info.jwksUrl()
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "jwksUrl is required when configurationUrl is not provided"));
      String issuer =
          info.issuer()
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "issuer is required when configurationUrl is not provided"));
      String authorizationEndpoint =
          info.authorizationEndpoint()
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "authorizationEndpoint is required when configurationUrl is not provided"));
      String tokenEndpoint =
          info.tokenEndpoint()
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "tokenEndpoint is required when configurationUrl is not provided"));
      return new ConfiguredOidcProviderInfo(
          clientId,
          clientSecret,
          jwksUrl,
          issuer,
          authorizationEndpoint,
          tokenEndpoint,
          info.userinfoEndpoint());
    }
  }

  // ── Abstract interface ───────────────────────────────────────────────────────

  /** Builds the authorization URL. */
  public String buildAuthorizationUrl(
      String redirectUri, String state, String codeChallenge, String codeChallengeMethod) {
    ConfiguredOidcProviderInfo provider = configureProvider();

    Map<String, String> queryParams = new LinkedHashMap<>();
    queryParams.put("response_type", "code");
    queryParams.put("client_id", provider.clientId());
    queryParams.put("redirect_uri", redirectUri);
    queryParams.put("scope", "openid profile email");
    queryParams.put("state", state);
    if (codeChallenge != null && !codeChallenge.isBlank()) {
      queryParams.put("code_challenge", codeChallenge);
    }
    if (codeChallengeMethod != null && !codeChallengeMethod.isBlank()) {
      queryParams.put("code_challenge_method", codeChallengeMethod);
    }

    return provider.authorizationEndpoint() + "?" + Util.formEncode(queryParams);
  }

  /** Exchanges the authorization code for tokens. */
  public OidcTokenSet exchangeAuthorizationCode(
      String code, String redirectUri, String codeVerifier) {
    ConfiguredOidcProviderInfo provider = configureProvider();

    Map<String, String> form = new LinkedHashMap<>();
    form.put("grant_type", "authorization_code");
    form.put("client_id", provider.clientId());
    form.put("client_secret", provider.clientSecret());
    form.put("code", code);
    form.put("redirect_uri", redirectUri);
    if (codeVerifier != null && !codeVerifier.isBlank()) {
      form.put("code_verifier", codeVerifier);
    }

    HttpResponse<Buffer> response;
    try {
      response =
          webClient
              .postAbs(provider.tokenEndpoint())
              .putHeader("Accept", "application/json")
              .putHeader("Content-Type", "application/x-www-form-urlencoded")
              .sendBuffer(Buffer.buffer(Util.formEncode(form)))
              .await()
              .atMost(HTTP_TIMEOUT);
    } catch (RuntimeException e) {
      throw new ExternalServiceException("OIDC token endpoint request failed", e);
    }

    if (response.statusCode() != 200) {
      throw new ExternalServiceException(
          "OIDC token endpoint returned status " + response.statusCode());
    }

    try {
      JsonNode json = objectMapper.readTree(response.bodyAsString());
      String accessToken = Util.requiredText(json, "access_token");
      String idToken = Util.optionalText(json, "id_token");
      long expiresIn = json.path("expires_in").asLong(3600L);
      return new OidcTokenSet(accessToken, idToken, expiresIn);
    } catch (Exception e) {
      throw new ExternalServiceException("Failed to parse OIDC token response", e);
    }
  }

  /**
   * Fetches user information from the OIDC provider. When the provider does not support a userinfo
   * endpoint, this method must either unpack the user info from the ID token or throw {@link
   * UnsupportedOperationException}.
   */
  public OidcUserInfo fetchUserInfo(String accessToken) throws UnsupportedOperationException {
    ConfiguredOidcProviderInfo provider = configureProvider();
    String userinfoEndpoint =
        provider
            .userinfoEndpoint()
            .orElseThrow(
                () ->
                    new UnsupportedOperationException(
                        "OIDC provider does not expose a userinfo endpoint"));

    HttpResponse<Buffer> response;
    try {
      response =
          webClient
              .getAbs(userinfoEndpoint)
              .putHeader("Authorization", "Bearer " + accessToken)
              .putHeader("Accept", "application/json")
              .send()
              .await()
              .atMost(HTTP_TIMEOUT);
    } catch (RuntimeException e) {
      throw new ExternalServiceException("OIDC userinfo endpoint request failed", e);
    }

    if (response.statusCode() != 200) {
      throw new ExternalServiceException(
          "OIDC userinfo endpoint returned status " + response.statusCode());
    }

    String rawProfile = response.bodyAsString();
    try {
      JsonNode json = objectMapper.readTree(rawProfile);
      return new OidcUserInfo(
          Util.requiredText(json, "sub"),
          Util.optionalText(json, "preferred_username"),
          Util.optionalText(json, "name"),
          Util.optionalText(json, "picture"),
          Util.optionalText(json, "email"),
          json.path("email_verified").asBoolean(false));
    } catch (Exception e) {
      throw new ExternalServiceException("Failed to parse OIDC userinfo response", e);
    }
  }
}
