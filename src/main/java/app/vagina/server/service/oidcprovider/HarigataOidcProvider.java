package app.vagina.server.service.oidcprovider;

import io.smallrye.config.ConfigMapping;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;

@ApplicationScoped
public class HarigataOidcProvider extends OidcProviderBase {
  @ConfigMapping(prefix = "vagina.auth.oidc.harigata")
  public interface HarigataOidcProviderInfo extends OidcProviderInfo {
    @Override
    Optional<String> configurationUrl();

    @Override
    Optional<String> authorizationEndpoint();

    @Override
    Optional<String> tokenEndpoint();

    @Override
    Optional<String> userinfoEndpoint();
  }

  @Inject HarigataOidcProviderInfo harigataOidcProviderInfo;

  @Override
  public String getProviderKey() {
    return "harigata";
  }

  @Override
  public OidcProviderInfo getProviderConfiguration() {
    return harigataOidcProviderInfo;
  }
}
