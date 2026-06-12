package app.vagina.server.service;

import app.vagina.server.entity.AccountLifecycle;
import app.vagina.server.entity.AuthMethod;
import app.vagina.server.entity.AuthnProvider;
import app.vagina.server.entity.User;
import app.vagina.server.mapper.AuthnProviderMapper;
import app.vagina.server.mapper.UserMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class UserService {

  @Inject UserMapper userMapper;
  @Inject AuthnProviderMapper authnProviderMapper;

  @Transactional
  public User createAnonymousUser() {
    LocalDateTime now = LocalDateTime.now();

    User user = new User();
    user.setAccountLifecycle(AccountLifecycle.CREATED);
    user.setUsermeta(null);
    user.setSysmeta(null);
    user.setCreatedAt(now);
    user.setUpdatedAt(now);
    userMapper.insert(user);

    AuthnProvider authnProvider = new AuthnProvider();
    authnProvider.setUserId(user.getId());
    authnProvider.setAuthMethod(AuthMethod.ANONYMOUS);
    authnProvider.setAuthIdentifier(UUID.randomUUID().toString());
    authnProvider.setExternalSubject(null);
    authnProvider.setProviderLogin(null);
    authnProvider.setDisplayName(null);
    authnProvider.setAvatarUrl(null);
    authnProvider.setEmail(null);
    authnProvider.setEmailVerified(null);
    authnProvider.setUsermeta(null);
    authnProvider.setSysmeta(null);
    authnProvider.setCreatedAt(now);
    authnProvider.setUpdatedAt(now);
    authnProviderMapper.insert(authnProvider);

    return user;
  }

  public Optional<User> findById(Long id) {
    return userMapper.findById(id);
  }

  public Optional<User> findByAuthIdentifier(String authIdentifier) {
    return authnProviderMapper
        .findByAuthIdentifier(authIdentifier)
        .flatMap(provider -> userMapper.findById(provider.getUserId()));
  }

  public Optional<AuthnProvider> findPrimaryAuthnProvider(Long userId) {
    return authnProviderMapper.findByUserId(userId).stream().findFirst();
  }
}
