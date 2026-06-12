package app.aoki.quarkuscrud.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.aoki.quarkuscrud.entity.Event;
import app.aoki.quarkuscrud.entity.EventAttendee;
import app.aoki.quarkuscrud.entity.EventInvitationCode;
import app.aoki.quarkuscrud.entity.EventStatus;
import app.aoki.quarkuscrud.mapper.EventInvitationCodeMapper;
import app.aoki.quarkuscrud.mapper.EventMapper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Unit tests for EventService.
 *
 * <p>Tests event creation, retrieval, invitation codes, and attendee management.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class EventServiceTest {

  @Inject EventService eventService;
  @Inject UserService userService;
  @Inject EventMapper eventMapper;
  @Inject EventInvitationCodeMapper eventInvitationCodeMapper;

  private static Long testUserId;
  private static Long testEventId;
  private static String testInvitationCode;

  @Test
  @Order(1)
  public void testCreateEvent() {
    // Create a test user first
    testUserId = userService.createAnonymousUser().getId();

    // Create an event
    LocalDateTime expiresAt = LocalDateTime.now().plusHours(2);
    Event event = eventService.createEvent(testUserId, "{\"title\":\"Test Event\"}", expiresAt);

    assertNotNull(event);
    assertNotNull(event.getId());
    assertEquals(testUserId, event.getInitiatorId());
    assertEquals(EventStatus.CREATED, event.getStatus());
    assertNotNull(event.getUsermeta());
    assertEquals(expiresAt, event.getExpiresAt());
    assertNotNull(event.getCreatedAt());
    assertNotNull(event.getUpdatedAt());

    testEventId = event.getId();
  }

  @Test
  @Order(2)
  public void testGetInvitationCode() {
    Optional<String> invitationCode = eventService.getInvitationCode(testEventId);

    assertTrue(invitationCode.isPresent());
    assertEquals(3, invitationCode.get().length());
    testInvitationCode = invitationCode.get();
  }

  @Test
  @Order(3)
  public void testFindById() {
    Optional<Event> event = eventService.findById(testEventId);

    assertTrue(event.isPresent());
    assertEquals(testEventId, event.get().getId());
    assertEquals(testUserId, event.get().getInitiatorId());
  }

  @Test
  @Order(4)
  public void testFindByIdNotFound() {
    Optional<Event> event = eventService.findById(999999L);
    assertTrue(event.isEmpty());
  }

  @Test
  @Order(5)
  public void testFindActiveEventByInvitationCode() {
    Optional<Event> event = eventService.findActiveEventByInvitationCode(testInvitationCode);

    assertTrue(event.isPresent());
    assertEquals(testEventId, event.get().getId());
  }

  @Test
  @Order(6)
  public void testFindActiveEventByInvitationCodeNotFound() {
    Optional<Event> event = eventService.findActiveEventByInvitationCode("INVALID1");
    assertTrue(event.isEmpty());
  }

  @Test
  @Order(7)
  public void testFindByInitiatorId() {
    List<Event> events = eventService.findByInitiatorId(testUserId);

    assertFalse(events.isEmpty());
    assertTrue(events.stream().anyMatch(e -> e.getId().equals(testEventId)));
  }

  @Test
  @Order(8)
  public void testAddAttendee() {
    // Create another user to be an attendee
    Long attendeeUserId = userService.createAnonymousUser().getId();

    EventAttendee attendee = eventService.addAttendee(testEventId, attendeeUserId, null);

    assertNotNull(attendee);
    assertNotNull(attendee.getId());
    assertEquals(testEventId, attendee.getEventId());
    assertEquals(attendeeUserId, attendee.getAttendeeUserId());
    assertNotNull(attendee.getCreatedAt());
    assertNotNull(attendee.getUpdatedAt());
  }

  @Test
  @Order(9)
  public void testIsUserAttendee() {
    // Create another user
    Long attendeeUserId = userService.createAnonymousUser().getId();

    // Initially, user is not an attendee
    assertFalse(eventService.isUserAttendee(testEventId, attendeeUserId));
  }

  @Test
  @Order(10)
  public void testListAttendees() {
    List<EventAttendee> attendees = eventService.listAttendees(testEventId);

    assertFalse(attendees.isEmpty());
    assertTrue(attendees.stream().anyMatch(a -> a.getEventId().equals(testEventId)));
  }

  @Test
  @Order(11)
  public void testToLocalDateTimeVariants() {
    // Test with OffsetDateTime
    OffsetDateTime odt = OffsetDateTime.now(ZoneOffset.UTC);
    LocalDateTime ldtFromOdt = eventService.toLocalDateTime(odt);
    assertNotNull(ldtFromOdt);
    assertEquals(odt.toLocalDateTime(), ldtFromOdt);

    // Test with String
    String dateTimeString = odt.toString();
    LocalDateTime ldtFromString = eventService.toLocalDateTime(dateTimeString);
    assertNotNull(ldtFromString);

    // Test with null
    LocalDateTime ldtFromNull = eventService.toLocalDateTime(null);
    assertNull(ldtFromNull);

    // Test with invalid string
    LocalDateTime ldtFromInvalid = eventService.toLocalDateTime("invalid-date");
    assertNull(ldtFromInvalid);
  }

  @Test
  @Order(12)
  public void testCreateEventWithNullMeta() {
    Long userId = userService.createAnonymousUser().getId();
    Event event = eventService.createEvent(userId, null, null);

    assertNotNull(event);
    assertNotNull(event.getId());
  }

  @Test
  @Order(13)
  public void testAddAttendeeWithMeta() {
    Long userId = userService.createAnonymousUser().getId();
    Event event = eventService.createEvent(userId, null, null);
    Long attendeeUserId = userService.createAnonymousUser().getId();

    String meta = "{\"role\":\"moderator\"}";
    EventAttendee attendee = eventService.addAttendee(event.getId(), attendeeUserId, meta);

    assertNotNull(attendee);
    assertEquals(meta, attendee.getUsermeta());
  }

  @Test
  @Order(14)
  public void testInvitationCodesAreUniqueForActiveEvents() {
    Long firstUserId = userService.createAnonymousUser().getId();
    Event firstEvent = eventService.createEvent(firstUserId, null, null);
    String firstCode = eventService.getInvitationCode(firstEvent.getId()).orElseThrow();

    Long secondUserId = userService.createAnonymousUser().getId();
    Event secondEvent = eventService.createEvent(secondUserId, null, null);
    String secondCode = eventService.getInvitationCode(secondEvent.getId()).orElseThrow();

    assertNotEquals(
        firstCode, secondCode, "Active events must never share the same invitation code");
  }

  @Test
  @Order(15)
  public void testInvitationCodeCanBeReusedAfterExpiration() {
    Long firstUserId = userService.createAnonymousUser().getId();
    Event firstEvent = eventService.createEvent(firstUserId, null, null);
    EventInvitationCode firstCodeRecord =
        eventInvitationCodeMapper.findByEventId(firstEvent.getId()).get(0);
    String reusableCode = "かかか";
    firstCodeRecord.setInvitationCode(reusableCode);
    firstCodeRecord.setUpdatedAt(LocalDateTime.now());
    eventInvitationCodeMapper.update(firstCodeRecord);

    Long secondUserId = userService.createAnonymousUser().getId();
    Event secondEvent = eventService.createEvent(secondUserId, null, null);
    eventInvitationCodeMapper
        .findByEventId(secondEvent.getId())
        .forEach(code -> eventInvitationCodeMapper.deleteById(code.getId()));

    EventInvitationCode duplicateRequest = new EventInvitationCode();
    duplicateRequest.setEventId(secondEvent.getId());
    duplicateRequest.setInvitationCode(reusableCode);
    duplicateRequest.setUsermeta(null);
    duplicateRequest.setSysmeta(null);
    duplicateRequest.setCreatedAt(LocalDateTime.now());
    duplicateRequest.setUpdatedAt(LocalDateTime.now());

    int insertedWithActiveEvent =
        eventInvitationCodeMapper.insertIfInvitationCodeAvailable(duplicateRequest);
    assertEquals(0, insertedWithActiveEvent, "Active event should block duplicate codes");

    firstEvent.setStatus(EventStatus.EXPIRED);
    firstEvent.setUpdatedAt(LocalDateTime.now());
    eventMapper.update(firstEvent);

    duplicateRequest.setUsermeta(null);
    duplicateRequest.setSysmeta(null);
    duplicateRequest.setCreatedAt(LocalDateTime.now());
    duplicateRequest.setUpdatedAt(LocalDateTime.now());
    int insertedAfterExpiration =
        eventInvitationCodeMapper.insertIfInvitationCodeAvailable(duplicateRequest);
    assertEquals(1, insertedAfterExpiration, "Expired events must free up their codes");
  }

  @Test
  @Order(16)
  public void testInvitationCodeCanBeReusedAfterDeletion() {
    Long firstUserId = userService.createAnonymousUser().getId();
    Event firstEvent = eventService.createEvent(firstUserId, null, null);
    EventInvitationCode firstCodeRecord =
        eventInvitationCodeMapper.findByEventId(firstEvent.getId()).get(0);
    String reusableCode = "さささ";
    firstCodeRecord.setInvitationCode(reusableCode);
    firstCodeRecord.setUpdatedAt(LocalDateTime.now());
    eventInvitationCodeMapper.update(firstCodeRecord);

    Long secondUserId = userService.createAnonymousUser().getId();
    Event secondEvent = eventService.createEvent(secondUserId, null, null);
    eventInvitationCodeMapper
        .findByEventId(secondEvent.getId())
        .forEach(code -> eventInvitationCodeMapper.deleteById(code.getId()));

    EventInvitationCode duplicateRequest = new EventInvitationCode();
    duplicateRequest.setEventId(secondEvent.getId());
    duplicateRequest.setInvitationCode(reusableCode);
    duplicateRequest.setUsermeta(null);
    duplicateRequest.setSysmeta(null);
    duplicateRequest.setCreatedAt(LocalDateTime.now());
    duplicateRequest.setUpdatedAt(LocalDateTime.now());

    int insertedWhileActive =
        eventInvitationCodeMapper.insertIfInvitationCodeAvailable(duplicateRequest);
    assertEquals(0, insertedWhileActive, "Active event should block duplicate codes");

    firstEvent.setStatus(EventStatus.DELETED);
    firstEvent.setUpdatedAt(LocalDateTime.now());
    eventMapper.update(firstEvent);

    duplicateRequest.setUsermeta(null);
    duplicateRequest.setSysmeta(null);
    duplicateRequest.setCreatedAt(LocalDateTime.now());
    duplicateRequest.setUpdatedAt(LocalDateTime.now());
    int insertedAfterDeletion =
        eventInvitationCodeMapper.insertIfInvitationCodeAvailable(duplicateRequest);
    assertEquals(1, insertedAfterDeletion, "Deleted events must free up their codes");
  }
}
