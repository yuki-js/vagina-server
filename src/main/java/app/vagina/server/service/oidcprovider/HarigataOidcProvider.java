package app.vagina.server.service.oidcprovider;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigMapping;

@ApplicationScoped
public class HarigataOidcProvider extends OidcProviderBase {
  @ConfigMapping("vagina.auth.oidc.harigata")
  public interface HarigataOidcProviderInfo extends OidcProviderInfo {}

  @Inject
  HarigataOidcProviderInfo harigataOidcProviderInfo;

  @Override
  public OidcProviderInfo getProviderConfiguration() {
    return harigataOidcProviderInfo;
  }
}
