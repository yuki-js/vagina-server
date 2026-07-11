package app.vagina.server.service.oidcprovider;

import app.vagina.server.config.OidcConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.mutiny.core.Vertx;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class OidcProviderRegistry {
  private static final String GITHUB_PROVIDER_KEY = "github";

  public record RegisteredProvider(
      String id, String displayName, OidcProviderBase implementation) {}

  @Inject OidcConfig config;
  @Inject Vertx vertx;
  @Inject ObjectMapper objectMapper;

  private Map<String, RegisteredProvider> providers;

  @PostConstruct
  void init() {
    Map<String, RegisteredProvider> registered = new LinkedHashMap<>();
    config
        .providers()
        .forEach(
            (rawProviderKey, providerConfig) -> {
              String providerKey = requireNonBlank(rawProviderKey, "OIDC provider key");
              String displayName =
                  requireNonBlank(
                      providerConfig.displayName(),
                      "OIDC provider display name for " + providerKey);
              OidcProviderBase provider = createProvider(providerKey, providerConfig);
              registered.put(
                  providerKey, new RegisteredProvider(providerKey, displayName, provider));
            });

    providers = Map.copyOf(registered);
  }

  public RegisteredProvider resolveConfigured(String providerKey) {
    RegisteredProvider provider = providers.get(providerKey);
    if (provider == null || !provider.implementation().isConfigured()) {
      return null;
    }
    return provider;
  }

  public List<RegisteredProvider> listConfigured() {
    List<RegisteredProvider> configured = new ArrayList<>();
    for (RegisteredProvider provider : providers.values()) {
      if (provider.implementation().isConfigured()) {
        configured.add(provider);
      }
    }
    configured.sort(Comparator.comparing(RegisteredProvider::displayName));
    return List.copyOf(configured);
  }

  private OidcProviderBase createProvider(
      String providerKey, OidcConfig.ProviderConfig providerConfig) {
    if (GITHUB_PROVIDER_KEY.equals(providerKey)) {
      return new GitHubOidcProvider(providerConfig, vertx, objectMapper);
    }
    return new ConfiguredOidcProvider(providerKey, providerConfig, vertx, objectMapper);
  }

  private String requireNonBlank(String value, String description) {
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(description + " must not be blank");
    }
    return value.trim();
  }
}
