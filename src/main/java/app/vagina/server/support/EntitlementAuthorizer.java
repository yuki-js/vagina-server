package app.vagina.server.support;

import app.vagina.server.domain.error.AuthorizationException;
import app.vagina.server.service.EntitlementService;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import java.util.List;

@RequestScoped
public class EntitlementAuthorizer {

  @Inject AuthenticatedUser authenticatedUser;
  @Inject EntitlementService entitlementService;

  public List<String> activeEntitlementKeys() {
    return entitlementService.listActiveEntitlementKeys(authenticatedUser.get().getId());
  }

  public boolean has(String entitlementKey) {
    return activeEntitlementKeys().contains(entitlementKey);
  }

  public void require(String entitlementKey) {
    if (!has(entitlementKey)) {
      throw new AuthorizationException("Missing required entitlement: " + entitlementKey);
    }
  }
}
