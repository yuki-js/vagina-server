package app.vagina.server.service.oidcprovider;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.mutiny.core.Vertx;

public final class ConfiguredOidcProvider extends OidcProviderBase {
  private final String providerKey;
  private final OidcProviderInfo providerConfiguration;

  public ConfiguredOidcProvider(
      String providerKey,
      OidcProviderInfo providerConfiguration,
      Vertx vertx,
      ObjectMapper objectMapper) {
    this.providerKey = providerKey;
    this.providerConfiguration = providerConfiguration;
    this.vertx = vertx;
    this.objectMapper = objectMapper;
    init();
  }

  @Override
  public String getProviderKey() {
    return providerKey;
  }

  @Override
  public OidcProviderInfo getProviderConfiguration() {
    return providerConfiguration;
  }
}
