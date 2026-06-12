package app.aoki.quarkuscrud.usecase;

import app.aoki.quarkuscrud.entity.Event;
import app.aoki.quarkuscrud.entity.EventAttendee;
import app.aoki.quarkuscrud.entity.EventInvitationCode;
import app.aoki.quarkuscrud.entity.EventUserData;
import app.aoki.quarkuscrud.entity.Friendship;
import app.aoki.quarkuscrud.entity.User;
import app.aoki.quarkuscrud.entity.UserProfile;
import app.aoki.quarkuscrud.generated.model.UserMeta;
import app.aoki.quarkuscrud.mapper.AuthnProviderMapper;
import app.aoki.quarkuscrud.mapper.EventAttendeeMapper;
import app.aoki.quarkuscrud.mapper.EventInvitationCodeMapper;
import app.aoki.quarkuscrud.mapper.EventMapper;
import app.aoki.quarkuscrud.mapper.EventUserDataMapper;
import app.aoki.quarkuscrud.mapper.FriendshipMapper;
import app.aoki.quarkuscrud.mapper.UserMapper;
import app.aoki.quarkuscrud.mapper.UserProfileMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.LocalDateTime;
import org.jboss.logging.Logger;

/**
 * Use case for metadata operations across all entities.
 *
 * <p>Handles reading and writing usermeta for entities with proper authorization checks.
 *
 * <p>Architecture Note: This UseCase uses UserMeta from the generated API models (presentation
 * layer). While this creates coupling between layers, UserMeta is essentially a simple DTO wrapper
 * around JSON data with no business logic. For a future refactoring, consider creating a separate
 * domain model (e.g., UsermetaData) to maintain strict separation of concerns.
 *
 * <p>Authorization rules: - users: 本人のみRW - events: attendeeのみRW - event_attendees: attendee全体RW -
 * friendships: senderのみRW(mutualなfriendshipが形成されるならばもう片方のrelationが張られるはずなので無問題) - user_profiles:
 * 本人のみRW - event_user_data: R: attendee, W: 本人のみ - rooms: attendee全体RW - authn_providers:
 * user本人のみRW - event_invitation_codes: event initiatorのみRW
 */
@ApplicationScoped
public class UsermetaUseCase {

  private static final Logger LOG = Logger.getLogger(UsermetaUseCase.class);

  @Inject UserMapper userMapper;
  @Inject EventMapper eventMapper;
  @Inject EventAttendeeMapper eventAttendeeMapper;
  @Inject FriendshipMapper friendshipMapper;
  @Inject UserProfileMapper userProfileMapper;
  @Inject EventUserDataMapper eventUserDataMapper;
  @Inject AuthnProviderMapper authnProviderMapper;
  @Inject EventInvitationCodeMapper eventInvitationCodeMapper;
  @Inject ObjectMapper objectMapper;

  // ==================== User Meta ====================

  public UserMeta getUserMeta(Long userId, Long requestingUserId) {
    if (!userId.equals(requestingUserId)) {
      throw new SecurityException("You can only access your own metadata");
    }

    User user =
        userMapper
            .findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("User not found"));
    return parseMetaData(user.getUsermeta());
  }

  @Transactional
  public UserMeta updateUserMeta(Long userId, Long requestingUserId, UserMeta metaData) {
    if (!userId.equals(requestingUserId)) {
      throw new SecurityException("You can only update your own metadata");
    }

    User user =
        userMapper
            .findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("User not found"));
    user.setUsermeta(serializeMetaData(metaData));
    user.setUpdatedAt(LocalDateTime.now());
    userMapper.update(user);
    return metaData;
  }

  // ==================== Event Meta ====================

  public UserMeta getEventMeta(Long eventId, Long requestingUserId) {
    Event event =
        eventMapper
            .findById(eventId)
            .orElseThrow(() -> new IllegalArgumentException("Event not found"));

    if (!isEventAttendee(eventId, requestingUserId)) {
      throw new SecurityException("Only event attendees can access event metadata");
    }

    return parseMetaData(event.getUsermeta());
  }

  @Transactional
  public UserMeta updateEventMeta(Long eventId, Long requestingUserId, UserMeta metaData) {
    Event event =
        eventMapper
            .findById(eventId)
            .orElseThrow(() -> new IllegalArgumentException("Event not found"));

    if (!isEventAttendee(eventId, requestingUserId)) {
      throw new SecurityException("Only event attendees can update event metadata");
    }

    event.setUsermeta(serializeMetaData(metaData));
    event.setUpdatedAt(LocalDateTime.now());
    eventMapper.update(event);
    return metaData;
  }

  // ==================== Friendship Meta ====================

  public UserMeta getFriendshipMeta(Long requestingUserId, Long otherUserId) {
    Friendship friendship =
        friendshipMapper
            .findBySenderAndRecipient(requestingUserId, otherUserId)
            .orElseThrow(() -> new IllegalArgumentException("Friendship not found"));

    return parseMetaData(friendship.getUsermeta());
  }

  @Transactional
  public UserMeta updateFriendshipMeta(Long requestingUserId, Long otherUserId, UserMeta metaData) {
    Friendship friendship =
        friendshipMapper
            .findBySenderAndRecipient(requestingUserId, otherUserId)
            .orElseThrow(() -> new IllegalArgumentException("Friendship not found"));

    friendship.setUsermeta(serializeMetaData(metaData));
    friendship.setUpdatedAt(LocalDateTime.now());
    friendshipMapper.updateMeta(friendship);
    return metaData;
  }

  // ==================== User Profile Meta ====================

  public UserMeta getUserProfileMeta(Long userId, Long requestingUserId) {
    if (!userId.equals(requestingUserId)) {
      throw new SecurityException("You can only access your own profile metadata");
    }

    UserProfile profile =
        userProfileMapper
            .findLatestByUserId(userId)
            .orElseThrow(() -> new IllegalArgumentException("User profile not found"));
    return parseMetaData(profile.getUsermeta());
  }

  @Transactional
  public UserMeta updateUserProfileMeta(Long userId, Long requestingUserId, UserMeta metaData) {
    if (!userId.equals(requestingUserId)) {
      throw new SecurityException("You can only update your own profile metadata");
    }

    UserProfile profile =
        userProfileMapper
            .findLatestByUserId(userId)
            .orElseThrow(() -> new IllegalArgumentException("User profile not found"));
    profile.setUsermeta(serializeMetaData(metaData));
    profile.setUpdatedAt(LocalDateTime.now());
    userProfileMapper.updateRevisionMeta(profile);
    return metaData;
  }

  // ==================== Event User Data Meta ====================

  public UserMeta getEventUserDataMeta(Long eventId, Long userId, Long requestingUserId) {
    Event event =
        eventMapper
            .findById(eventId)
            .orElseThrow(() -> new IllegalArgumentException("Event not found"));

    if (!isEventAttendee(eventId, requestingUserId)) {
      throw new SecurityException("Only event attendees can access event user data metadata");
    }

    EventUserData eventUserData =
        eventUserDataMapper
            .findLatestByEventIdAndUserId(eventId, userId)
            .orElseThrow(() -> new IllegalArgumentException("Event user data not found"));
    return parseMetaData(eventUserData.getUsermeta());
  }

  @Transactional
  public UserMeta updateEventUserDataMeta(
      Long eventId, Long userId, Long requestingUserId, UserMeta metaData) {
    if (!userId.equals(requestingUserId)) {
      throw new SecurityException("You can only update your own event user data metadata");
    }

    EventUserData eventUserData =
        eventUserDataMapper
            .findLatestByEventIdAndUserId(eventId, userId)
            .orElseThrow(() -> new IllegalArgumentException("Event user data not found"));
    eventUserData.setUsermeta(serializeMetaData(metaData));
    eventUserData.setUpdatedAt(LocalDateTime.now());
    eventUserDataMapper.updateRevisionMeta(eventUserData);
    return metaData;
  }

  // ==================== Event Attendee Meta ====================

  public UserMeta getEventAttendeeMeta(Long eventId, Long attendeeUserId, Long requestingUserId) {
    if (!isEventAttendee(eventId, requestingUserId)) {
      throw new SecurityException("Only event attendees can access attendee metadata");
    }

    EventAttendee attendee =
        eventAttendeeMapper
            .findByEventAndAttendee(eventId, attendeeUserId)
            .orElseThrow(() -> new IllegalArgumentException("Event attendee not found"));
    return parseMetaData(attendee.getUsermeta());
  }

  @Transactional
  public UserMeta updateEventAttendeeMeta(
      Long eventId, Long attendeeUserId, Long requestingUserId, UserMeta metaData) {
    if (!isEventAttendee(eventId, requestingUserId)) {
      throw new SecurityException("Only event attendees can update attendee metadata");
    }

    EventAttendee attendee =
        eventAttendeeMapper
            .findByEventAndAttendee(eventId, attendeeUserId)
            .orElseThrow(() -> new IllegalArgumentException("Event attendee not found"));
    attendee.setUsermeta(serializeMetaData(metaData));
    attendee.setUpdatedAt(LocalDateTime.now());
    eventAttendeeMapper.update(attendee);
    return metaData;
  }

  // ==================== Room Meta ====================
  // TODO: Implement room meta when RoomMapper is available

  // ==================== Authn Provider Meta ====================

  public UserMeta getAuthnProviderMeta(Long providerId, Long requestingUserId) {
    var provider =
        authnProviderMapper
            .findById(providerId)
            .orElseThrow(() -> new IllegalArgumentException("Authentication provider not found"));

    if (!provider.getUserId().equals(requestingUserId)) {
      throw new SecurityException("You can only access your own authentication provider metadata");
    }

    return parseMetaData(provider.getUsermeta());
  }

  @Transactional
  public UserMeta updateAuthnProviderMeta(
      Long providerId, Long requestingUserId, UserMeta metaData) {
    var provider =
        authnProviderMapper
            .findById(providerId)
            .orElseThrow(() -> new IllegalArgumentException("Authentication provider not found"));

    if (!provider.getUserId().equals(requestingUserId)) {
      throw new SecurityException("You can only update your own authentication provider metadata");
    }

    provider.setUsermeta(serializeMetaData(metaData));
    provider.setUpdatedAt(LocalDateTime.now());
    authnProviderMapper.update(provider);
    return metaData;
  }

  // ==================== Event Invitation Code Meta ====================

  public UserMeta getEventInvitationCodeMeta(Long codeId, Long requestingUserId) {
    EventInvitationCode code =
        eventInvitationCodeMapper
            .findById(codeId)
            .orElseThrow(() -> new IllegalArgumentException("Event invitation code not found"));

    Event event =
        eventMapper
            .findById(code.getEventId())
            .orElseThrow(() -> new IllegalArgumentException("Event not found"));

    if (!event.getInitiatorId().equals(requestingUserId)) {
      throw new SecurityException("Only event initiator can access invitation code metadata");
    }

    return parseMetaData(code.getUsermeta());
  }

  @Transactional
  public UserMeta updateEventInvitationCodeMeta(
      Long codeId, Long requestingUserId, UserMeta metaData) {
    EventInvitationCode code =
        eventInvitationCodeMapper
            .findById(codeId)
            .orElseThrow(() -> new IllegalArgumentException("Event invitation code not found"));

    Event event =
        eventMapper
            .findById(code.getEventId())
            .orElseThrow(() -> new IllegalArgumentException("Event not found"));

    if (!event.getInitiatorId().equals(requestingUserId)) {
      throw new SecurityException("Only event initiator can update invitation code metadata");
    }

    code.setUsermeta(serializeMetaData(metaData));
    code.setUpdatedAt(LocalDateTime.now());
    eventInvitationCodeMapper.update(code);
    return metaData;
  }

  // ==================== Helper Methods ====================

  private boolean isEventAttendee(Long eventId, Long userId) {
    // Check if user is the event initiator
    Event event = eventMapper.findById(eventId).orElse(null);
    if (event != null && event.getInitiatorId().equals(userId)) {
      return true;
    }
    // Check if user is an attendee
    return eventAttendeeMapper.findByEventAndAttendee(eventId, userId).isPresent();
  }

  private UserMeta parseMetaData(String json) {
    UserMeta metaData = new UserMeta();
    try {
      if (json != null && !json.isBlank()) {
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> parsed = objectMapper.readValue(json, java.util.Map.class);
        metaData.setUsermeta(parsed);
      } else {
        metaData.setUsermeta(null);
      }
    } catch (Exception e) {
      LOG.warnf("Failed to parse usermeta: %s", e.getMessage());
      metaData.setUsermeta(null);
    }
    return metaData;
  }

  private String serializeMetaData(UserMeta metaData) {
    try {
      return metaData.getUsermeta() != null
          ? objectMapper.writeValueAsString(metaData.getUsermeta())
          : null;
    } catch (Exception e) {
      LOG.errorf("Failed to serialize usermeta: %s", e.getMessage());
      throw new IllegalArgumentException("Invalid metadata format");
    }
  }
}
