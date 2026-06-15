package app.vagina.server.service.oidcprovider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.ext.web.client.HttpResponse;
import io.vertx.mutiny.ext.web.client.WebClient;
import app.vagina.server.support.Util;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.Optional;

public abstract class OidcProviderBase {

  @Inject protected Vertx vertx;

  @Inject protected ObjectMapper objectMapper;

  /**
   * Represents a set of tokens returned by the OIDC provider.
   */
  public record OidcTokenSet(String accessToken, String idToken, long expiresIn) {}

  /**
   * Represents user information returned by the OIDC provider.
   */
  public record OidcUserInfo(
      String subject,
      String providerLogin,
      String displayName,
      String avatarUrl,
      String email,
      boolean emailVerified,
      String rawProfileJson) {}

  public record OidcProviderInfo(
      String providerKey,
      String clientId,
      String clientSecret,
      Optional<String> configurationUrl,
      Optional<String> jwksUrl,
      Optional<String> issuer,
      Optional<String> authorizationEndpoint,
      Optional<String> tokenEndpoint,
      Optional<String> userinfoEndpoint) {}

  protected record ConfiguredOidcProviderInfo(
      String providerKey,
      String clientId,
      String clientSecret,
      String jwksUrl,
      String issuer,
      String authorizationEndpoint,
      String tokenEndpoint,
      Optional<String> userinfoEndpoint) {}

  /** Returns the unique key identifying this provider (e.g. {@code "github"}, {@code "harigata"}). */
  public abstract String getProviderKey();

  public abstract OidcProviderInfo getProviderConfiguration();

  protected ConfiguredOidcProviderInfo configureProvider() {
    OidcProviderInfo info = getProviderConfiguration();

    if (info.configurationUrl().isPresent()
        && info.jwksUrl().isEmpty()
        && info.issuer().isEmpty()
        && info.authorizationEndpoint().isEmpty()
        && info.tokenEndpoint().isEmpty()) {
      // Auto-configure via OIDC discovery document
      String discoveryUrl = info.configurationUrl().get();

      WebClient client = WebClient.create(vertx);
      HttpResponse<io.vertx.mutiny.core.buffer.Buffer> response =
          client
              .getAbs(discoveryUrl)
              .putHeader("Accept", "application/json")
              .send()
              .await()
              .indefinitely();

      if (response.statusCode() != 200) {
        throw new IllegalStateException(
            "Failed to fetch OIDC discovery document from "
                + discoveryUrl
                + ": HTTP "
                + response.statusCode());
      }

      JsonNode doc;
      try {
        doc = objectMapper.readTree(response.bodyAsString());
      } catch (JsonProcessingException e) {
        throw new IllegalStateException(
            "Failed to parse OIDC discovery document from " + discoveryUrl, e);
      }

      String sourceDesc = "OIDC discovery document from " + discoveryUrl;
      String jwksUrl = Util.requireJsonField(doc, "jwks_uri", sourceDesc);
      String issuer = Util.requireJsonField(doc, "issuer", sourceDesc);
      String authorizationEndpoint = Util.requireJsonField(doc, "authorization_endpoint", sourceDesc);
      String tokenEndpoint = Util.requireJsonField(doc, "token_endpoint", sourceDesc);
      Optional<String> userinfoEndpoint =
          doc.has("userinfo_endpoint") && !doc.get("userinfo_endpoint").isNull()
              ? Optional.of(doc.get("userinfo_endpoint").asText())
              : Optional.empty();

      return new ConfiguredOidcProviderInfo(
          info.providerKey(),
          info.clientId(),
          info.clientSecret(),
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
                      new IllegalArgumentException(
                          "jwksUrl is required when configurationUrl is not provided"));
      String issuer =
          info.issuer()
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          "issuer is required when configurationUrl is not provided"));
      String authorizationEndpoint =
          info.authorizationEndpoint()
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          "authorizationEndpoint is required when configurationUrl is not provided"));
      String tokenEndpoint =
          info.tokenEndpoint()
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          "tokenEndpoint is required when configurationUrl is not provided"));
      return new ConfiguredOidcProviderInfo(
          info.providerKey(),
          info.clientId(),
          info.clientSecret(),
          jwksUrl,
          issuer,
          authorizationEndpoint,
          tokenEndpoint,
          info.userinfoEndpoint());
    }
  }

  // ── Abstract interface ───────────────────────────────────────────────────────

  /**
   * Builds the authorization URL.
   */
  public abstract String buildAuthorizationUrl(
      String redirectUri, String state, String codeChallenge, String codeChallengeMethod);

  /**
   * Exchanges the authorization code for tokens.
   */
  public abstract OidcTokenSet exchangeAuthorizationCode(
      String code, String redirectUri, String codeVerifier);

  /**
   * Fetches user information from the OIDC provider. When the provider does not support a userinfo
   * endpoint, this method must either unpack the user info from the ID token or throw {@link
   * UnsupportedOperationException}.
   */
  public abstract OidcUserInfo fetchUserInfo(String accessToken)
      throws UnsupportedOperationException;
}
