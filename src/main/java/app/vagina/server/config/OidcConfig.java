package app.vagina.server.config;

import app.vagina.server.service.oidcprovider.OidcProviderBase.OidcProviderInfo;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.util.Map;
import java.util.Optional;

@ConfigMapping(prefix = "vagina.auth.oidc")
public interface OidcConfig {

  RedirectUriConfig redirectUri();

  Map<String, ProviderConfig> providers();

  interface RedirectUriConfig {
    @WithDefault("")
    String web();

    @WithDefault("")
    String mobile();

    @WithDefault("")
    String desktop();
  }

  interface ProviderConfig extends OidcProviderInfo {
    String displayName();

    @Override
    Optional<String> clientId();

    @Override
    Optional<String> clientSecret();

    @Override
    Optional<String> configurationUrl();

    @Override
    Optional<String> jwksUrl();

    @Override
    Optional<String> issuer();

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
}
