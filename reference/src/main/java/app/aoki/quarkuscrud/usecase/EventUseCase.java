package app.aoki.quarkuscrud.usecase;

import app.aoki.quarkuscrud.entity.Event;
import app.aoki.quarkuscrud.entity.EventAttendee;
import app.aoki.quarkuscrud.entity.EventUserData;
import app.aoki.quarkuscrud.generated.model.EventCreateRequest;
import app.aoki.quarkuscrud.generated.model.EventJoinByCodeRequest;
import app.aoki.quarkuscrud.generated.model.EventUserDataUpdateRequest;
import app.aoki.quarkuscrud.service.EventService;
import app.aoki.quarkuscrud.service.UserService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.jboss.logging.Logger;

/**
 * Use case for event-related business flows.
 *
 * <p>This use case orchestrates complete business operations including validation, business logic,
 * and DTO mapping.
 */
@ApplicationScoped
public class EventUseCase {

  private static final Logger LOG = Logger.getLogger(EventUseCase.class);

  @Inject EventService eventService;
  @Inject UserService userService;
  @Inject ObjectMapper objectMapper;

  /**
   * Creates a new event with the given request data.
   *
   * @param userId the ID of the user creating the event
   * @param request the event creation request
   * @return the created event as DTO
   * @throws Exception if event creation fails
   */
  @Transactional
  public app.aoki.quarkuscrud.generated.model.Event createEvent(
      Long userId, EventCreateRequest request) throws Exception {
    String meta = objectMapper.writeValueAsString(request.getMeta());
    Event event =
        eventService.createEvent(
            userId, meta, eventService.toLocalDateTime(request.getExpiresAt()));
    LOG.debugf("Event created with ID: %d, fetching invitation code", event.getId());
    String invitationCode = eventService.getInvitationCode(event.getId()).orElse(null);
    LOG.debugf("Invitation code retrieved: %s", invitationCode);
    return toEventDto(event, invitationCode);
  }

  /**
   * Gets an event by ID for a specific requesting user.
   *
   * <p>The invitation code is only included if the requesting user is the event owner (initiator).
   * This prevents unauthorized users from obtaining the invitation code (CWE-284 fix).
   *
   * @param eventId the event ID
   * @param requestingUserId the ID of the user making the request
   * @return an Optional containing the event DTO if found
   */
  public Optional<app.aoki.quarkuscrud.generated.model.Event> getEventById(
      Long eventId, Long requestingUserId) {
    return eventService
        .findById(eventId)
        .map(
            event -> {
              // Only include invitation code if the requesting user is the event owner
              String invitationCode = null;
              if (requestingUserId != null && requestingUserId.equals(event.getInitiatorId())) {
                invitationCode = eventService.getInvitationCode(eventId).orElse(null);
              }
              return toEventDto(event, invitationCode);
            });
  }

  /**
   * Deletes an event by marking it as DELETED.
   *
   * <p>Only the event initiator can delete the event. This is a security measure to prevent
   * unauthorized deletion (CWE-284).
   *
   * @param eventId the event ID
   * @param requestingUserId the ID of the user making the request
   * @throws IllegalArgumentException if event not found
   * @throws SecurityException if user is not authorized to delete the event
   */
  @Transactional
  public void deleteEvent(Long eventId, Long requestingUserId) {
    Event event =
        eventService
            .findById(eventId)
            .orElseThrow(() -> new IllegalArgumentException("Event not found"));

    // Check if requesting user is the event initiator
    if (!requestingUserId.equals(event.getInitiatorId())) {
      throw new SecurityException("Only the event initiator can delete the event");
    }

    eventService.deleteEvent(eventId);
  }

  /**
   * Updates an event.
   *
   * <p>Only the event initiator can update the event. This is a security measure to prevent
   * unauthorized updates (CWE-284).
   *
   * @param eventId the event ID
   * @param requestingUserId the ID of the user making the request
   * @param request the update request
   * @return the updated event as DTO
   * @throws IllegalArgumentException if event not found
   * @throws SecurityException if user is not authorized to update the event
   * @throws Exception if update fails
   */
  @Transactional
  public app.aoki.quarkuscrud.generated.model.Event updateEvent(
      Long eventId,
      Long requestingUserId,
      app.aoki.quarkuscrud.generated.model.EventUpdateRequest request)
      throws Exception {
    Event event =
        eventService
            .findById(eventId)
            .orElseThrow(() -> new IllegalArgumentException("Event not found"));

    // Check if requesting user is the event initiator
    if (!requestingUserId.equals(event.getInitiatorId())) {
      throw new SecurityException("Only the event initiator can update the event");
    }

    // Convert status enum from DTO to entity if provided
    app.aoki.quarkuscrud.entity.EventStatus newStatus = null;
    if (request.getStatus() != null) {
      newStatus = app.aoki.quarkuscrud.entity.EventStatus.valueOf(request.getStatus().name());
    }

    // Convert meta to JSON string if provided and not empty
    String meta = null;
    if (request.getMeta() != null && !request.getMeta().isEmpty()) {
      meta = objectMapper.writeValueAsString(request.getMeta());
    }

    // Convert expiresAt to LocalDateTime if provided
    LocalDateTime expiresAt = null;
    if (request.getExpiresAt() != null) {
      expiresAt = eventService.toLocalDateTime(request.getExpiresAt());
    }

    Event updatedEvent = eventService.updateEvent(eventId, newStatus, meta, expiresAt);

    // Get invitation code for the response (only owner can see it)
    String invitationCode = eventService.getInvitationCode(eventId).orElse(null);
    return toEventDto(updatedEvent, invitationCode);
  }

  /**
   * Joins an event by invitation code.
   *
   * @param userId the ID of the user joining
   * @param request the join request with invitation code
   * @return the created attendee as DTO
   * @throws IllegalArgumentException if invitation code is invalid
   * @throws IllegalStateException if user already joined
   * @throws Exception if join fails
   */
  @Transactional
  public app.aoki.quarkuscrud.generated.model.EventAttendee joinEventByCode(
      Long userId, EventJoinByCodeRequest request) throws Exception {
    String code = request.getInvitationCode();

    if (code == null || code.isBlank()) {
      throw new IllegalArgumentException("invitationCode is required to join an event");
    }

    Event event =
        eventService
            .findActiveEventByInvitationCode(code)
            .orElseThrow(
                () -> new IllegalArgumentException("No active event matches the invitation code"));

    if (eventService.isUserAttendee(event.getId(), userId)) {
      throw new IllegalStateException("User already joined the event");
    }

    EventAttendee attendee = eventService.addAttendee(event.getId(), userId, null);
    return toAttendeeDto(attendee);
  }

  /**
   * Lists all attendees for an event with access control.
   *
   * <p>Only the event owner or attendees can view the attendee list. This prevents unauthorized
   * users from discovering who is attending an event (CWE-284 fix).
   *
   * @param eventId the event ID
   * @param requestingUserId the ID of the user making the request
   * @return list of attendee DTOs
   * @throws IllegalArgumentException if event not found
   * @throws SecurityException if user is not authorized to view attendees
   */
  public List<app.aoki.quarkuscrud.generated.model.EventAttendee> listEventAttendees(
      Long eventId, Long requestingUserId) {
    Event event =
        eventService
            .findById(eventId)
            .orElseThrow(() -> new IllegalArgumentException("Event not found"));

    // Check if requesting user is the event owner or an attendee
    // Short-circuit: if owner, skip the attendee check to avoid unnecessary DB query
    boolean isOwner = requestingUserId != null && requestingUserId.equals(event.getInitiatorId());
    if (!isOwner) {
      boolean isAttendee =
          requestingUserId != null && eventService.isUserAttendee(eventId, requestingUserId);
      if (!isAttendee) {
        throw new SecurityException("Not authorized to view attendees");
      }
    }

    return eventService.listAttendees(eventId).stream()
        .map(this::toAttendeeDto)
        .collect(Collectors.toList());
  }

  /**
   * Lists all events for a user with access control for invitation codes.
   *
   * <p>The invitation code is only included for each event if the requesting user is the event
   * owner (initiator). This prevents unauthorized users from obtaining invitation codes (CWE-284
   * fix).
   *
   * @param userId the user ID whose events to list
   * @param requestingUserId the ID of the user making the request
   * @return list of event DTOs
   * @throws IllegalArgumentException if user not found
   */
  public List<app.aoki.quarkuscrud.generated.model.Event> listEventsByUser(
      Long userId, Long requestingUserId) {
    if (userService.findById(userId).isEmpty()) {
      throw new IllegalArgumentException("User not found");
    }

    return eventService.findByInitiatorId(userId).stream()
        .map(
            event -> {
              // Only include invitation code if the requesting user is the event owner
              String invitationCode = null;
              if (requestingUserId != null && requestingUserId.equals(event.getInitiatorId())) {
                invitationCode = eventService.getInvitationCode(event.getId()).orElse(null);
              }
              return toEventDto(event, invitationCode);
            })
        .collect(Collectors.toList());
  }

  /**
   * Lists all events attended by a user with access control for invitation codes.
   *
   * <p>The invitation code is only included for each event if the requesting user is the event
   * owner (initiator). This prevents unauthorized users from obtaining invitation codes (CWE-284
   * fix).
   *
   * @param userId the user ID whose attended events to list
   * @param requestingUserId the ID of the user making the request
   * @return list of event DTOs
   * @throws IllegalArgumentException if user not found
   */
  public List<app.aoki.quarkuscrud.generated.model.Event> listAttendedEventsByUser(
      Long userId, Long requestingUserId) {
    if (userService.findById(userId).isEmpty()) {
      throw new IllegalArgumentException("User not found");
    }

    return eventService.findAttendedEventsByUserId(userId).stream()
        .map(
            event -> {
              // Only include invitation code if the requesting user is the event owner
              String invitationCode = null;
              if (requestingUserId != null && requestingUserId.equals(event.getInitiatorId())) {
                invitationCode = eventService.getInvitationCode(event.getId()).orElse(null);
              }
              return toEventDto(event, invitationCode);
            })
        .collect(Collectors.toList());
  }

  /**
   * Checks if an event exists.
   *
   * @param eventId the event ID
   * @return true if event exists
   */
  public boolean eventExists(Long eventId) {
    return eventService.findById(eventId).isPresent();
  }

  /**
   * Checks if a user is an attendee of an event.
   *
   * @param eventId the event ID
   * @param userId the user ID
   * @return true if user is an attendee
   */
  public boolean isUserAttendee(Long eventId, Long userId) {
    return eventService.isUserAttendee(eventId, userId);
  }

  /**
   * Gets the latest user data for an event and user.
   *
   * @param eventId the event ID
   * @param userId the user ID
   * @return an Optional containing the user data DTO if found
   */
  public Optional<app.aoki.quarkuscrud.generated.model.EventUserData> getEventUserData(
      Long eventId, Long userId) {
    return eventService.findLatestUserData(eventId, userId).map(this::toUserDataDto);
  }

  /**
   * Updates the user data for an event and user.
   *
   * @param eventId the event ID
   * @param userId the user ID
   * @param request the update request
   * @return the updated user data as DTO
   * @throws Exception if update fails
   */
  @Transactional
  public app.aoki.quarkuscrud.generated.model.EventUserData updateEventUserData(
      Long eventId, Long userId, EventUserDataUpdateRequest request) throws Exception {
    String userData = objectMapper.writeValueAsString(request.getUserData());
    String revisionMeta = null;
    if (request.getRevisionMeta() != null) {
      revisionMeta = objectMapper.writeValueAsString(request.getRevisionMeta());
    }
    EventUserData newData =
        eventService.createUserDataRevision(eventId, userId, userData, revisionMeta);
    return toUserDataDto(newData);
  }

  private app.aoki.quarkuscrud.generated.model.Event toEventDto(
      Event event, String invitationCode) {
    app.aoki.quarkuscrud.generated.model.Event response =
        new app.aoki.quarkuscrud.generated.model.Event();
    response.setId(event.getId());
    response.setInitiatorId(event.getInitiatorId());
    response.setStatus(
        app.aoki.quarkuscrud.generated.model.Event.StatusEnum.fromValue(
            event.getStatus().getValue()));
    LOG.debugf("Setting invitation code on response: %s", invitationCode);
    if (invitationCode != null) {
      response.setInvitationCode(invitationCode);
    }
    if (event.getExpiresAt() != null) {
      response.setExpiresAt(event.getExpiresAt().atOffset(ZoneOffset.UTC));
    }
    response.setCreatedAt(event.getCreatedAt().atOffset(ZoneOffset.UTC));
    if (event.getUpdatedAt() != null) {
      response.setUpdatedAt(event.getUpdatedAt().atOffset(ZoneOffset.UTC));
    }

    if (event.getUsermeta() != null) {
      try {
        Map<String, Object> meta =
            objectMapper.readValue(event.getUsermeta(), new TypeReference<>() {});
        response.setMeta(meta);
      } catch (Exception e) {
        response.setMeta(new HashMap<>());
      }
    } else {
      response.setMeta(new HashMap<>());
    }

    return response;
  }

  private app.aoki.quarkuscrud.generated.model.EventAttendee toAttendeeDto(EventAttendee attendee) {
    app.aoki.quarkuscrud.generated.model.EventAttendee response =
        new app.aoki.quarkuscrud.generated.model.EventAttendee();
    response.setId(attendee.getId());
    response.setEventId(attendee.getEventId());
    response.setAttendeeUserId(attendee.getAttendeeUserId());
    response.setCreatedAt(attendee.getCreatedAt().atOffset(ZoneOffset.UTC));
    if (attendee.getUpdatedAt() != null) {
      response.setUpdatedAt(attendee.getUpdatedAt().atOffset(ZoneOffset.UTC));
    }

    if (attendee.getUsermeta() != null) {
      try {
        Map<String, Object> meta =
            objectMapper.readValue(attendee.getUsermeta(), new TypeReference<>() {});
        response.setMeta(meta);
      } catch (Exception e) {
        response.setMeta(new HashMap<>());
      }
    } else {
      response.setMeta(new HashMap<>());
    }

    return response;
  }

  private app.aoki.quarkuscrud.generated.model.EventUserData toUserDataDto(EventUserData data) {
    app.aoki.quarkuscrud.generated.model.EventUserData response =
        new app.aoki.quarkuscrud.generated.model.EventUserData();
    response.setId(data.getId());
    response.setEventId(data.getEventId());
    response.setUserId(data.getUserId());
    response.setCreatedAt(data.getCreatedAt().atOffset(ZoneOffset.UTC));
    if (data.getUpdatedAt() != null) {
      response.setUpdatedAt(data.getUpdatedAt().atOffset(ZoneOffset.UTC));
    }

    if (data.getUserData() != null) {
      try {
        Map<String, Object> userData =
            objectMapper.readValue(data.getUserData(), new TypeReference<>() {});
        response.setUserData(userData);
      } catch (Exception e) {
        LOG.warnf(
            e,
            "Failed to parse userData JSON for EventUserData id=%d, eventId=%d, userId=%d",
            data.getId(),
            data.getEventId(),
            data.getUserId());
        response.setUserData(new HashMap<>());
      }
    } else {
      response.setUserData(new HashMap<>());
    }

    if (data.getRevisionMeta() != null) {
      try {
        Map<String, Object> revisionMeta =
            objectMapper.readValue(data.getRevisionMeta(), new TypeReference<>() {});
        response.setRevisionMeta(revisionMeta);
      } catch (Exception e) {
        LOG.warnf(
            e,
            "Failed to parse revisionMeta JSON for EventUserData id=%d, eventId=%d, userId=%d",
            data.getId(),
            data.getEventId(),
            data.getUserId());
        response.setRevisionMeta(new HashMap<>());
      }
    } else {
      response.setRevisionMeta(new HashMap<>());
    }

    return response;
  }
}
