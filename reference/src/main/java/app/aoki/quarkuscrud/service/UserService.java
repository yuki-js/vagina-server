package app.aoki.quarkuscrud.service;

import app.aoki.quarkuscrud.entity.AccountLifecycle;
import app.aoki.quarkuscrud.entity.AuthMethod;
import app.aoki.quarkuscrud.entity.AuthnProvider;
import app.aoki.quarkuscrud.entity.User;
import app.aoki.quarkuscrud.mapper.AuthnProviderMapper;
import app.aoki.quarkuscrud.mapper.UserMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * Service for managing users across all authentication providers.
 *
 * <p>This service handles user lifecycle operations. Authentication information is managed
 * separately in the AuthnProvider table.
 */
@ApplicationScoped
public class UserService {

  private static final Logger LOG = Logger.getLogger(UserService.class);

  @Inject UserMapper userMapper;
  @Inject AuthnProviderMapper authnProviderMapper;
  @Inject MeterRegistry meterRegistry;

  /**
   * Creates a new user with anonymous authentication.
   *
   * <p>Generates a unique authentication identifier (UUID) for the user. Creates both the User
   * entity and the associated AuthnProvider record.
   *
   * @return the created user
   */
  @Transactional
  public User createAnonymousUser() {
    LOG.infof("Creating new anonymous user");
    Timer.Sample sample = Timer.start(meterRegistry);

    try {
      // Create user entity
      User user = new User();
      user.setAccountLifecycle(AccountLifecycle.CREATED);
      user.setUsermeta(null);
      user.setSysmeta(null);
      user.setCreatedAt(LocalDateTime.now());
      user.setUpdatedAt(LocalDateTime.now());
      userMapper.insert(user);

      // Create authentication provider
      AuthnProvider authnProvider = new AuthnProvider();
      authnProvider.setUserId(user.getId());
      authnProvider.setAuthMethod(AuthMethod.ANONYMOUS);
      authnProvider.setAuthIdentifier(UUID.randomUUID().toString());
      authnProvider.setExternalSubject(null);
      authnProvider.setCreatedAt(LocalDateTime.now());
      authnProvider.setUpdatedAt(LocalDateTime.now());
      authnProviderMapper.insert(authnProvider);

      // Record metrics
      meterRegistry.counter("users.created", "auth_method", "anonymous").increment();

      LOG.infof("Successfully created anonymous user with ID: %d", user.getId());
      return user;
    } finally {
      sample.stop(meterRegistry.timer("users.creation.time", "auth_method", "anonymous"));
    }
  }

  /**
   * Creates or retrieves a user from an external authentication provider.
   *
   * <p>If a user with the given provider and external subject already exists, returns that user.
   * Otherwise, creates a new user.
   *
   * @param authMethod the authentication method
   * @param externalSubject the subject from the external provider
   * @return the user (existing or newly created)
   */
  @Transactional
  public User getOrCreateExternalUser(AuthMethod authMethod, String externalSubject) {
    if (authMethod == AuthMethod.ANONYMOUS) {
      throw new IllegalArgumentException("Use createAnonymousUser() for anonymous authentication");
    }

    LOG.infof(
        "Getting or creating external user with method: %s, subject: %s",
        authMethod, externalSubject);
    Timer.Sample sample = Timer.start(meterRegistry);

    try {
      // Check if authentication provider already exists
      Optional<AuthnProvider> existingAuthnProvider =
          authnProviderMapper.findByMethodAndExternalSubject(authMethod, externalSubject);
      if (existingAuthnProvider.isPresent()) {
        // Return the associated user
        LOG.infof("Found existing user for method: %s, subject: %s", authMethod, externalSubject);
        Counter.builder("users.lookup")
            .description("Number of user lookups")
            .tag("auth_method", authMethod.name().toLowerCase())
            .tag("result", "existing")
            .register(meterRegistry)
            .increment();
        return userMapper.findById(existingAuthnProvider.get().getUserId()).orElseThrow();
      }

      // Create new user
      User user = new User();
      user.setAccountLifecycle(AccountLifecycle.CREATED);
      user.setUsermeta(null);
      user.setSysmeta(null);
      user.setCreatedAt(LocalDateTime.now());
      user.setUpdatedAt(LocalDateTime.now());
      userMapper.insert(user);

      // Create authentication provider
      AuthnProvider authnProvider = new AuthnProvider();
      authnProvider.setUserId(user.getId());
      authnProvider.setAuthMethod(authMethod);
      authnProvider.setAuthIdentifier(
          UUID.randomUUID().toString()); // Internal reference for tracking
      authnProvider.setExternalSubject(externalSubject);
      authnProvider.setCreatedAt(LocalDateTime.now());
      authnProvider.setUpdatedAt(LocalDateTime.now());
      authnProviderMapper.insert(authnProvider);

      // Record metrics
      Counter.builder("users.created")
          .description("Number of users created")
          .tag("auth_method", authMethod.name().toLowerCase())
          .register(meterRegistry)
          .increment();

      LOG.infof(
          "Successfully created external user with ID: %d for method: %s",
          user.getId(), authMethod);
      return user;
    } finally {
      sample.stop(
          Timer.builder("users.getorcreate.time")
              .description("Time taken to get or create a user")
              .tag("auth_method", authMethod.name().toLowerCase())
              .register(meterRegistry));
    }
  }

  /**
   * Finds a user by ID.
   *
   * @param id the user ID
   * @return an Optional containing the user if found
   */
  public Optional<User> findById(Long id) {
    LOG.debugf("Finding user by ID: %d", id);
    Timer.Sample sample = Timer.start(meterRegistry);

    try {
      Optional<User> result = userMapper.findById(id);
      Counter.builder("users.lookup")
          .description("Number of user lookups")
          .tag("method", "by_id")
          .tag("result", result.isPresent() ? "found" : "not_found")
          .register(meterRegistry)
          .increment();
      return result;
    } finally {
      sample.stop(meterRegistry.timer("users.lookup.time", "method", "by_id"));
    }
  }

  /**
   * Finds a user by their internal authentication identifier.
   *
   * @param authIdentifier the authentication identifier
   * @return an Optional containing the user if found
   */
  public Optional<User> findByAuthIdentifier(String authIdentifier) {
    LOG.debugf("Finding user by auth identifier: %s", authIdentifier);
    Timer.Sample sample = Timer.start(meterRegistry);

    try {
      Optional<AuthnProvider> authnProvider =
          authnProviderMapper.findByAuthIdentifier(authIdentifier);
      if (authnProvider.isPresent()) {
        Optional<User> result = userMapper.findById(authnProvider.get().getUserId());
        Counter.builder("users.lookup")
            .description("Number of user lookups")
            .tag("method", "by_auth_identifier")
            .tag("result", result.isPresent() ? "found" : "not_found")
            .register(meterRegistry)
            .increment();
        return result;
      }
      Counter.builder("users.lookup")
          .description("Number of user lookups")
          .tag("method", "by_auth_identifier")
          .tag("result", "not_found")
          .register(meterRegistry)
          .increment();
      return Optional.empty();
    } finally {
      sample.stop(meterRegistry.timer("users.lookup.time", "method", "by_auth_identifier"));
    }
  }

  /**
   * Finds a user by their external provider and subject.
   *
   * @param authMethod the authentication method
   * @param externalSubject the external subject identifier
   * @return an Optional containing the user if found
   */
  public Optional<User> findByMethodAndExternalSubject(
      AuthMethod authMethod, String externalSubject) {
    LOG.debugf("Finding user by method: %s and external subject: %s", authMethod, externalSubject);
    Optional<AuthnProvider> authnProvider =
        authnProviderMapper.findByMethodAndExternalSubject(authMethod, externalSubject);
    if (authnProvider.isPresent()) {
      return userMapper.findById(authnProvider.get().getUserId());
    }
    return Optional.empty();
  }

  /**
   * Updates an existing user.
   *
   * @param user the user to update
   */
  @Transactional
  public void updateUser(User user) {
    LOG.infof("Updating user with ID: %d", user.getId());
    Timer.Sample sample = Timer.start(meterRegistry);

    try {
      user.setUpdatedAt(LocalDateTime.now());
      userMapper.update(user);

      meterRegistry.counter("users.updated").increment();

      LOG.infof("Successfully updated user with ID: %d", user.getId());
    } finally {
      sample.stop(meterRegistry.timer("users.update.time"));
    }
  }

  /**
   * Deletes a user by ID.
   *
   * <p>Note: All related entities (authentication providers, profiles, etc.) will be automatically
   * deleted due to ON DELETE CASCADE constraints.
   *
   * @param id the user ID
   */
  @Transactional
  public void deleteUser(Long id) {
    LOG.infof("Deleting user with ID: %d", id);
    Timer.Sample sample = Timer.start(meterRegistry);

    try {
      userMapper.deleteById(id);

      meterRegistry.counter("users.deleted").increment();

      LOG.infof("Successfully deleted user with ID: %d", id);
    } finally {
      sample.stop(meterRegistry.timer("users.delete.time"));
    }
  }
}
