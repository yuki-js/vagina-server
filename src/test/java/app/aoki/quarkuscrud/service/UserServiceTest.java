package app.aoki.quarkuscrud.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.aoki.quarkuscrud.entity.AccountLifecycle;
import app.aoki.quarkuscrud.entity.AuthMethod;
import app.aoki.quarkuscrud.entity.User;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Unit tests for UserService focusing on multi-provider authentication support. */
@QuarkusTest
public class UserServiceTest {

  @Inject UserService userService;

  @Test
  @Transactional
  public void testCreateAnonymousUser() {
    User user = userService.createAnonymousUser();

    assertNotNull(user.getId());
    assertEquals(AccountLifecycle.CREATED, user.getAccountLifecycle());
    assertNull(user.getUsermeta());
    assertNotNull(user.getCreatedAt());
    assertNotNull(user.getUpdatedAt());
  }

  @Test
  @Transactional
  public void testGetOrCreateExternalUserNewUser() {
    String externalSubject = "google-user-123";
    User user = userService.getOrCreateExternalUser(AuthMethod.OIDC, externalSubject);

    assertNotNull(user.getId());
    assertEquals(AccountLifecycle.CREATED, user.getAccountLifecycle());
    assertNotNull(user.getCreatedAt());
    assertNotNull(user.getUpdatedAt());
  }

  @Test
  @Transactional
  public void testGetOrCreateExternalUserExistingUser() {
    String externalSubject = "google-user-456";

    // Create user first time
    User user1 = userService.getOrCreateExternalUser(AuthMethod.OIDC, externalSubject);
    Long userId1 = user1.getId();

    // Try to create same user again
    User user2 = userService.getOrCreateExternalUser(AuthMethod.OIDC, externalSubject);
    Long userId2 = user2.getId();

    // Should return the same user
    assertEquals(userId1, userId2);
  }

  @Test
  @Transactional
  public void testGetOrCreateExternalUserThrowsForAnonymous() {
    assertThrows(
        IllegalArgumentException.class,
        () -> userService.getOrCreateExternalUser(AuthMethod.ANONYMOUS, "some-id"));
  }

  @Test
  @Transactional
  public void testFindByMethodAndExternalSubject() {
    String externalSubject = "github-user-789";
    User createdUser = userService.getOrCreateExternalUser(AuthMethod.OIDC, externalSubject);

    Optional<User> foundUser =
        userService.findByMethodAndExternalSubject(AuthMethod.OIDC, externalSubject);

    assertTrue(foundUser.isPresent());
    assertEquals(createdUser.getId(), foundUser.get().getId());
  }

  @Test
  @Transactional
  public void testFindByMethodAndExternalSubjectNotFound() {
    Optional<User> foundUser =
        userService.findByMethodAndExternalSubject(AuthMethod.OIDC, "nonexistent-user");

    assertFalse(foundUser.isPresent());
  }

  @Test
  @Transactional
  public void testMultipleAnonymousUsersAreUnique() {
    User user1 = userService.createAnonymousUser();
    User user2 = userService.createAnonymousUser();

    assertNotNull(user1.getId());
    assertNotNull(user2.getId());
    assertNotEquals(user1.getId(), user2.getId());
  }
}
