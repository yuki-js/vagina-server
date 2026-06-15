package app.vagina.server.support;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.util.Map;

public class HarigataOidcMockServerResource implements QuarkusTestResourceLifecycleManager {

  public static final String PROVIDER_KEY = "harigata";
  public static final String DEFAULT_CLIENT_ID = "harigata-client-id";
  public static final String DEFAULT_CLIENT_SECRET = "harigata-client-secret";
  public static final String DEFAULT_AUTHORIZATION_CODE = "harigata-auth-code";
  public static final String DEFAULT_ACCESS_TOKEN = "harigata-access-token";
  public static final String DEFAULT_ID_TOKEN = "harigata-id-token";
  public static final String DEFAULT_SUBJECT = "harigata-user-123";
  public static final String DEFAULT_LOGIN = "harigata-user";
  public static final String DEFAULT_DISPLAY_NAME = "Harigata Test User";
  public static final String DEFAULT_AVATAR_URL = "https://harigata.example.test/avatar.png";
  public static final String DEFAULT_EMAIL = "harigata@example.test";

  private WireMockServer wireMockServer;

  @Override
  public Map<String, String> start() {
    wireMockServer =
        new WireMockServer(WireMockConfiguration.options().dynamicPort().globalTemplating(true));
    wireMockServer.start();
    setupDefaultStubs();

    String issuer = issuerBaseUrl();
    return Map.of(
        "vagina.auth.oidc.harigata.issuer", issuer,
        "vagina.auth.oidc.harigata.configuration-url",
        issuer + "/.well-known/openid-configuration",
        "vagina.auth.oidc.harigata.client-id", DEFAULT_CLIENT_ID,
        "vagina.auth.oidc.harigata.client-secret", DEFAULT_CLIENT_SECRET,
        "vagina.auth.oidc.harigata.authorization-endpoint", issuer + "/authorize",
        "vagina.auth.oidc.harigata.token-endpoint", issuer + "/token",
        "vagina.auth.oidc.harigata.userinfo-endpoint", issuer + "/userinfo",
        "vagina.auth.oidc.harigata.jwks-url", issuer + "/jwks");
  }

  @Override
  public void stop() {
    if (wireMockServer != null) {
      wireMockServer.stop();
    }
  }

  @Override
  public void inject(Object testInstance) {
    if (testInstance instanceof HarigataOidcMockServerAware aware) {
      aware.setWireMockServer(wireMockServer);
    }
  }

  private void setupDefaultStubs() {
    wireMockServer.stubFor(
        get(urlPathEqualTo("/.well-known/openid-configuration"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(discoveryJson())));

    wireMockServer.stubFor(
        get(urlPathEqualTo("/jwks"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"keys\":[]}")));

    wireMockServer.stubFor(
        get(urlPathEqualTo("/authorize"))
            .withQueryParam("redirect_uri", matching(".+"))
            .withQueryParam("state", matching(".+"))
            .willReturn(
                aResponse()
                    .withStatus(302)
                    .withHeader(
                        "Location",
                        "{{request.query.redirect_uri}}?code="
                            + DEFAULT_AUTHORIZATION_CODE
                            + "&state={{request.query.state}}")));

    wireMockServer.stubFor(
        post(urlPathEqualTo("/token"))
            .withHeader("Content-Type", containing("application/x-www-form-urlencoded"))
            .withRequestBody(containing("grant_type=authorization_code"))
            .withRequestBody(containing("code=" + DEFAULT_AUTHORIZATION_CODE))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(tokenJson())));

    wireMockServer.stubFor(
        get(urlPathEqualTo("/userinfo"))
            .withHeader("Authorization", equalTo("Bearer " + DEFAULT_ACCESS_TOKEN))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(userInfoJson())));
  }

  private String issuerBaseUrl() {
    return "http://localhost:" + wireMockServer.port();
  }

  private String discoveryJson() {
    String issuer = issuerBaseUrl();
    return """
        {
          "issuer": "%s",
          "authorization_endpoint": "%s/authorize",
          "token_endpoint": "%s/token",
          "userinfo_endpoint": "%s/userinfo",
          "jwks_uri": "%s/jwks",
          "response_types_supported": ["code"],
          "subject_types_supported": ["public"],
          "id_token_signing_alg_values_supported": ["RS256"],
          "token_endpoint_auth_methods_supported": ["client_secret_post"],
          "scopes_supported": ["openid", "profile", "email"]
        }
        """
        .formatted(issuer, issuer, issuer, issuer, issuer);
  }

  private String tokenJson() {
    return """
        {
          "access_token": "%s",
          "id_token": "%s",
          "token_type": "Bearer",
          "expires_in": 3600,
          "scope": "openid profile email"
        }
        """
        .formatted(DEFAULT_ACCESS_TOKEN, DEFAULT_ID_TOKEN);
  }

  private String userInfoJson() {
    return """
        {
          "sub": "%s",
          "preferred_username": "%s",
          "name": "%s",
          "picture": "%s",
          "email": "%s",
          "email_verified": true
        }
        """
        .formatted(
            DEFAULT_SUBJECT,
            DEFAULT_LOGIN,
            DEFAULT_DISPLAY_NAME,
            DEFAULT_AVATAR_URL,
            DEFAULT_EMAIL);
  }

  public interface HarigataOidcMockServerAware {
    void setWireMockServer(WireMockServer wireMockServer);
  }
}
