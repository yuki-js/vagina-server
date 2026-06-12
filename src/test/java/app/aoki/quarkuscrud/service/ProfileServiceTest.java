package app.aoki.quarkuscrud.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.aoki.quarkuscrud.entity.User;
import app.aoki.quarkuscrud.entity.UserProfile;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.Optional;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Unit tests for ProfileService.
 *
 * <p>Tests profile creation, retrieval, and versioning.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ProfileServiceTest {

  @Inject ProfileService profileService;
  @Inject UserService userService;

  private static Long testUserId;
  private static Long firstProfileId;

  @Test
  @Order(1)
  @Transactional
  public void setup() {
    // Create a test user
    User user = userService.createAnonymousUser();
    testUserId = user.getId();
  }

  @Test
  @Order(2)
  public void testFindLatestByUserIdNotFound() {
    Optional<UserProfile> profile = profileService.findLatestByUserId(testUserId);
    assertTrue(profile.isEmpty());
  }

  @Test
  @Order(3)
  @Transactional
  public void testCreateProfileRevision() {
    String profileData = "{\"displayName\":\"Test User\",\"bio\":\"Test bio\"}";
    UserProfile profile = profileService.createProfileRevision(testUserId, profileData, null);

    assertNotNull(profile);
    assertNotNull(profile.getId());
    assertEquals(testUserId, profile.getUserId());
    assertEquals(profileData, profile.getProfileData());
    assertNull(profile.getRevisionMeta());
    assertNotNull(profile.getCreatedAt());
    assertNotNull(profile.getUpdatedAt());

    firstProfileId = profile.getId();

    // Verify it's now retrievable as latest
    Optional<UserProfile> latestProfile = profileService.findLatestByUserId(testUserId);
    assertTrue(latestProfile.isPresent());
    assertEquals(firstProfileId, latestProfile.get().getId());
  }

  @Test
  @Order(4)
  @Transactional
  public void testCreateProfileRevisionWithMeta() {
    String profileData = "{\"displayName\":\"Updated User\",\"bio\":\"Updated bio\"}";
    String revisionMeta = "{\"updateReason\":\"User requested update\"}";
    UserProfile profile =
        profileService.createProfileRevision(testUserId, profileData, revisionMeta);

    assertNotNull(profile);
    assertNotNull(profile.getId());
    assertNotEquals(firstProfileId, profile.getId());
    assertEquals(testUserId, profile.getUserId());
    assertEquals(profileData, profile.getProfileData());
    assertEquals(revisionMeta, profile.getRevisionMeta());

    // Verify it's now the latest profile
    Optional<UserProfile> latestProfile = profileService.findLatestByUserId(testUserId);
    assertTrue(latestProfile.isPresent());
    assertNotEquals(firstProfileId, latestProfile.get().getId());
    assertTrue(latestProfile.get().getProfileData().contains("Updated User"));
  }

  @Test
  @Order(5)
  @Transactional
  public void testCreateMultipleRevisions() {
    for (int i = 0; i < 3; i++) {
      String profileData = "{\"displayName\":\"User " + i + "\"}";
      UserProfile profile = profileService.createProfileRevision(testUserId, profileData, null);
      assertNotNull(profile);
    }

    Optional<UserProfile> latestProfile = profileService.findLatestByUserId(testUserId);
    assertTrue(latestProfile.isPresent());
    assertTrue(latestProfile.get().getProfileData().contains("User 2"));
  }
}
