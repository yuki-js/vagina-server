package app.aoki.quarkuscrud.usecase;

import app.aoki.quarkuscrud.entity.UserProfile;
import app.aoki.quarkuscrud.generated.model.UserProfileUpdateRequest;
import app.aoki.quarkuscrud.service.ProfileService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Use case for profile-related business flows.
 *
 * <p>This use case orchestrates complete business operations including validation, business logic,
 * and DTO mapping.
 */
@ApplicationScoped
public class ProfileUseCase {

  @Inject ProfileService profileService;
  @Inject ObjectMapper objectMapper;

  /**
   * Gets the latest profile for a user.
   *
   * @param userId the user ID
   * @return an Optional containing the profile DTO if found
   */
  public Optional<app.aoki.quarkuscrud.generated.model.UserProfile> getLatestProfile(Long userId) {
    return profileService.findLatestByUserId(userId).map(this::toProfileDto);
  }

  /**
   * Updates a user's profile.
   *
   * @param userId the user ID
   * @param request the profile update request
   * @return the updated profile as DTO
   * @throws Exception if profile update fails
   */
  @Transactional
  public app.aoki.quarkuscrud.generated.model.UserProfile updateProfile(
      Long userId, UserProfileUpdateRequest request) throws Exception {
    String profileData = objectMapper.writeValueAsString(request.getProfileData());
    UserProfile newProfile = profileService.createProfileRevision(userId, profileData, null);
    return toProfileDto(newProfile);
  }

  private app.aoki.quarkuscrud.generated.model.UserProfile toProfileDto(UserProfile profile) {
    app.aoki.quarkuscrud.generated.model.UserProfile response =
        new app.aoki.quarkuscrud.generated.model.UserProfile();
    response.setId(profile.getId());
    response.setUserId(profile.getUserId());
    response.setCreatedAt(profile.getCreatedAt().atOffset(ZoneOffset.UTC));
    response.setUpdatedAt(profile.getUpdatedAt().atOffset(ZoneOffset.UTC));

    try {
      Map<String, Object> profileData =
          objectMapper.readValue(profile.getProfileData(), new TypeReference<>() {});
      response.setProfileData(profileData);
    } catch (Exception e) {
      response.setProfileData(new HashMap<>());
    }

    if (profile.getRevisionMeta() != null) {
      try {
        Map<String, Object> revisionMeta =
            objectMapper.readValue(profile.getRevisionMeta(), new TypeReference<>() {});
        response.setRevisionMeta(revisionMeta);
      } catch (Exception e) {
        response.setRevisionMeta(new HashMap<>());
      }
    } else {
      response.setRevisionMeta(new HashMap<>());
    }

    return response;
  }
}
