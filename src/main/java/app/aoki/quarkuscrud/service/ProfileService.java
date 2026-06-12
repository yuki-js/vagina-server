package app.aoki.quarkuscrud.service;

import app.aoki.quarkuscrud.entity.UserProfile;
import app.aoki.quarkuscrud.mapper.UserProfileMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Service for managing user profiles.
 *
 * <p>This service handles profile creation, retrieval, and updates. User profiles are versioned,
 * with each update creating a new revision. The latest revision is always retrieved using ORDER BY
 * created_at DESC LIMIT 1, without relying on a cached pointer field.
 */
@ApplicationScoped
public class ProfileService {

  @Inject UserProfileMapper userProfileMapper;

  /**
   * Finds the latest profile for a user.
   *
   * @param userId the user ID
   * @return an Optional containing the profile if found
   */
  public Optional<UserProfile> findLatestByUserId(Long userId) {
    return userProfileMapper.findLatestByUserId(userId);
  }

  /**
   * Creates a new profile revision for a user.
   *
   * @param userId the user ID
   * @param profileData JSON profile data
   * @param revisionMeta optional JSON metadata about the revision
   * @return the created profile
   */
  @Transactional
  public UserProfile createProfileRevision(Long userId, String profileData, String revisionMeta) {
    UserProfile newProfile = new UserProfile();
    newProfile.setUserId(userId);
    newProfile.setProfileData(profileData);
    newProfile.setRevisionMeta(revisionMeta);
    LocalDateTime now = LocalDateTime.now();
    newProfile.setCreatedAt(now);
    newProfile.setUpdatedAt(now);

    userProfileMapper.insert(newProfile);

    return newProfile;
  }
}
