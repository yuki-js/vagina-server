package app.vagina.server.service;

import app.vagina.server.entity.AccountLifecycle;
import app.vagina.server.entity.AuthMethod;
import app.vagina.server.entity.AuthnProvider;
import app.vagina.server.entity.User;
import app.vagina.server.mapper.AuthnProviderMapper;
import app.vagina.server.mapper.UserMapper;
import app.vagina.server.service.oidcprovider.OidcProviderBase.OidcUserInfo;
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
        authnProviderMapper
            .findByProviderKeyAndExternalSubject(providerKey, oidcUserInfo.subject())
            .map(this::toAuthnProviderDomain);

    LocalDateTime now = LocalDateTime.now();
    if (existingProvider.isPresent()) {
      return updateExistingProviderAndReturnUser(existingProvider.get(), oidcUserInfo, now);
    }

    User user = createUser(now);

    try {
      AuthnProvider authnProvider =
          new AuthnProvider(
              null,
              user.getId(),
              AuthMethod.OIDC,
              providerKey,
              UUID.randomUUID().toString(),
              oidcUserInfo.subject(),
              oidcUserInfo.providerLogin(),
              oidcUserInfo.displayName(),
              oidcUserInfo.avatarUrl(),
              oidcUserInfo.email(),
              oidcUserInfo.emailVerified(),
              now,
              now);
      AuthnProviderMapper.Row authnProviderRow = toAuthnProviderRow(authnProvider);
      authnProviderMapper.insert(authnProviderRow);
      authnProvider.setGeneratedId(authnProviderRow.getId());
      return user;
    } catch (RuntimeException e) {
      if (!isUniqueConstraintViolation(e)) {
        throw e;
      }

      userMapper.deleteById(user.getId());
      AuthnProvider winner =
          authnProviderMapper
              .findByProviderKeyAndExternalSubject(providerKey, oidcUserInfo.subject())
              .map(this::toAuthnProviderDomain)
              .orElseThrow(() -> e);
      return updateExistingProviderAndReturnUser(winner, oidcUserInfo, now);
    }
  }

  public Optional<User> findById(Long id) {
    return userMapper.findById(id).map(this::toUserDomain);
  }

  public Optional<AuthnProvider> findPrimaryAuthnProvider(Long userId) {
    return authnProviderMapper.findByUserId(userId).stream()
        .findFirst()
        .map(this::toAuthnProviderDomain);
  }

  private User createUser(LocalDateTime now) {
    User user = new User(null, AccountLifecycle.ACTIVE, now, now);
    UserMapper.Row row = toUserRow(user);
    userMapper.insert(row);
    user.setGeneratedId(row.getId());
    return user;
  }

  private User updateExistingProviderAndReturnUser(
      AuthnProvider authnProvider, OidcUserInfo oidcUserInfo, LocalDateTime now) {
    authnProvider.syncProviderProfile(
        oidcUserInfo.providerLogin(),
        oidcUserInfo.displayName(),
        oidcUserInfo.avatarUrl(),
        oidcUserInfo.email(),
        oidcUserInfo.emailVerified(),
        now);
    authnProviderMapper.update(toAuthnProviderRow(authnProvider));
    return userMapper.findById(authnProvider.getUserId()).map(this::toUserDomain).orElseThrow();
  }

  private User toUserDomain(UserMapper.Row row) {
    return new User(row.getId(), row.getAccountLifecycle(), row.getCreatedAt(), row.getUpdatedAt());
  }

  private UserMapper.Row toUserRow(User user) {
    UserMapper.Row row = new UserMapper.Row();
    row.setId(user.getId());
    row.setAccountLifecycle(user.getAccountLifecycle());
    row.setCreatedAt(user.getCreatedAt());
    row.setUpdatedAt(user.getUpdatedAt());
    return row;
  }

  private AuthnProvider toAuthnProviderDomain(AuthnProviderMapper.Row row) {
    return new AuthnProvider(
        row.getId(),
        row.getUserId(),
        row.getAuthMethod(),
        row.getProviderKey(),
        row.getAuthIdentifier(),
        row.getExternalSubject(),
        row.getProviderLogin(),
        row.getDisplayName(),
        row.getAvatarUrl(),
        row.getEmail(),
        row.getEmailVerified(),
        row.getCreatedAt(),
        row.getUpdatedAt());
  }

  private AuthnProviderMapper.Row toAuthnProviderRow(AuthnProvider authnProvider) {
    AuthnProviderMapper.Row row = new AuthnProviderMapper.Row();
    row.setId(authnProvider.getId());
    row.setUserId(authnProvider.getUserId());
    row.setAuthMethod(authnProvider.getAuthMethod());
    row.setProviderKey(authnProvider.getProviderKey());
    row.setAuthIdentifier(authnProvider.getAuthIdentifier());
    row.setExternalSubject(authnProvider.getExternalSubject());
    row.setProviderLogin(authnProvider.getProviderLogin());
    row.setDisplayName(authnProvider.getDisplayName());
    row.setAvatarUrl(authnProvider.getAvatarUrl());
    row.setEmail(authnProvider.getEmail());
    row.setEmailVerified(authnProvider.getEmailVerified());
    row.setCreatedAt(authnProvider.getCreatedAt());
    row.setUpdatedAt(authnProvider.getUpdatedAt());
    return row;
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
