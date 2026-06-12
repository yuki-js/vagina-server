package app.vagina.server.service;

import app.vagina.server.entity.AccountLifecycle;
import app.vagina.server.entity.AuthMethod;
import app.vagina.server.entity.AuthnProvider;
import app.vagina.server.entity.User;
import app.vagina.server.mapper.AuthnProviderMapper;
import app.vagina.server.mapper.UserMapper;
import app.vagina.server.service.model.OidcUserInfo;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class UserService {

  @Inject UserMapper userMapper;
  @Inject AuthnProviderMapper authnProviderMapper;

  @Transactional
  public User getOrCreateOidcUser(String providerKey, OidcUserInfo oidcUserInfo) {
    Optional<AuthnProvider> existingProvider =
        authnProviderMapper.findByProviderKeyAndExternalSubject(
            providerKey, oidcUserInfo.subject());

    LocalDateTime now = LocalDateTime.now();
    if (existingProvider.isPresent()) {
      return updateExistingProviderAndReturnUser(existingProvider.get(), oidcUserInfo, now);
    }

    User user = createUser(now);

    try {
      AuthnProvider authnProvider = new AuthnProvider();
      authnProvider.setUserId(user.getId());
      authnProvider.setAuthMethod(AuthMethod.OIDC);
      authnProvider.setProviderKey(providerKey);
      authnProvider.setAuthIdentifier(UUID.randomUUID().toString());
      authnProvider.setExternalSubject(oidcUserInfo.subject());
      authnProvider.setProviderLogin(oidcUserInfo.providerLogin());
      authnProvider.setDisplayName(oidcUserInfo.displayName());
      authnProvider.setAvatarUrl(oidcUserInfo.avatarUrl());
      authnProvider.setEmail(oidcUserInfo.email());
      authnProvider.setEmailVerified(oidcUserInfo.emailVerified());
      authnProvider.setUsermeta(null);
      authnProvider.setSysmeta(oidcUserInfo.rawProfileJson());
      authnProvider.setCreatedAt(now);
      authnProvider.setUpdatedAt(now);
      authnProviderMapper.insert(authnProvider);
      return user;
    } catch (RuntimeException e) {
      if (!isUniqueConstraintViolation(e)) {
        throw e;
      }

      userMapper.deleteById(user.getId());
      AuthnProvider winner =
          authnProviderMapper
              .findByProviderKeyAndExternalSubject(providerKey, oidcUserInfo.subject())
              .orElseThrow(() -> e);
      return updateExistingProviderAndReturnUser(winner, oidcUserInfo, now);
    }
  }

  public Optional<User> findById(Long id) {
    return userMapper.findById(id);
  }

  public Optional<AuthnProvider> findPrimaryAuthnProvider(Long userId) {
    return authnProviderMapper.findByUserId(userId).stream().findFirst();
  }

  private User createUser(LocalDateTime now) {
    User user = new User();
    user.setAccountLifecycle(AccountLifecycle.ACTIVE);
    user.setUsermeta(null);
    user.setSysmeta(null);
    user.setCreatedAt(now);
    user.setUpdatedAt(now);
    userMapper.insert(user);
    return user;
  }

  private User updateExistingProviderAndReturnUser(
      AuthnProvider authnProvider, OidcUserInfo oidcUserInfo, LocalDateTime now) {
    authnProvider.setProviderLogin(oidcUserInfo.providerLogin());
    authnProvider.setDisplayName(oidcUserInfo.displayName());
    authnProvider.setAvatarUrl(oidcUserInfo.avatarUrl());
    authnProvider.setEmail(oidcUserInfo.email());
    authnProvider.setEmailVerified(oidcUserInfo.emailVerified());
    authnProvider.setSysmeta(oidcUserInfo.rawProfileJson());
    authnProvider.setUpdatedAt(now);
    authnProviderMapper.update(authnProvider);
    return userMapper.findById(authnProvider.getUserId()).orElseThrow();
  }

  private boolean isUniqueConstraintViolation(Throwable throwable) {
    Throwable current = throwable;
    while (current != null) {
      if (current instanceof SQLException sqlException) {
        if ("23505".equals(sqlException.getSQLState())) {
          return true;
        }
      }
      current = current.getCause();
    }
    return false;
  }
}
