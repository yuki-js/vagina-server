package app.vagina.server.service.oidcprovider;

@ApplicationScoped
public class GitHubOidcProvider extends OidcProviderBase {
  @ConfigMapping("vagina.auth.oidc.github")
  public interface GitHubOidcProviderInfo extends OidcProviderInfo {}

  @Inject
  GitHubOidcProviderInfo gitHubOidcProviderInfo;

  @Override
  public OidcProviderInfo getProviderConfiguration() {
    return gitHubOidcProviderInfo;
  }
}