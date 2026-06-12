package app.aoki.quarkuscrud.resource;

import app.aoki.quarkuscrud.entity.User;
import app.aoki.quarkuscrud.generated.api.EventsApi;
import app.aoki.quarkuscrud.generated.model.Event;
import app.aoki.quarkuscrud.generated.model.EventAttendee;
import app.aoki.quarkuscrud.generated.model.EventCreateRequest;
import app.aoki.quarkuscrud.generated.model.EventJoinByCodeRequest;
import app.aoki.quarkuscrud.generated.model.EventUpdateRequest;
import app.aoki.quarkuscrud.generated.model.EventUserData;
import app.aoki.quarkuscrud.generated.model.EventUserDataUpdateRequest;
import app.aoki.quarkuscrud.generated.model.UserMeta;
import app.aoki.quarkuscrud.support.Authenticated;
import app.aoki.quarkuscrud.support.AuthenticatedUser;
import app.aoki.quarkuscrud.support.ErrorResponse;
import app.aoki.quarkuscrud.usecase.EventUseCase;
import app.aoki.quarkuscrud.usecase.UsermetaUseCase;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import org.jboss.logging.Logger;

@ApplicationScoped
@Path("/api")
public class EventsApiImpl implements EventsApi {

  private static final Logger LOG = Logger.getLogger(EventsApiImpl.class);

  @Inject EventUseCase eventUseCase;
  @Inject UsermetaUseCase usermetaUseCase;
  @Inject AuthenticatedUser authenticatedUser;
  @Inject MeterRegistry meterRegistry;

  @Override
  @Authenticated
  @POST
  @Path("/events")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response createEvent(EventCreateRequest createEventRequest) {
    User user = authenticatedUser.get();
    LOG.infof("Creating event for user ID: %d", user.getId());
    Timer.Sample sample = Timer.start(meterRegistry);

    try {
      Event event = eventUseCase.createEvent(user.getId(), createEventRequest);
      meterRegistry.counter("events.created").increment();
      LOG.infof("Successfully created event");
      return Response.status(Response.Status.CREATED).entity(event).build();
    } catch (Exception e) {
      LOG.errorf(e, "Failed to create event for user ID: %d", user.getId());
      meterRegistry.counter("events.errors", "operation", "create").increment();
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
          .entity(new ErrorResponse("Failed to create event: " + e.getMessage()))
          .build();
    } finally {
      sample.stop(meterRegistry.timer("events.creation.time"));
    }
  }

  @Override
  @Authenticated
  @GET
  @Path("/events/{eventId}")
  @Produces(MediaType.APPLICATION_JSON)
  public Response getEventById(@PathParam("eventId") Long eventId) {
    User user = authenticatedUser.get();
    LOG.debugf("Fetching event ID: %d for user ID: %d", eventId, user.getId());
    Timer.Sample sample = Timer.start(meterRegistry);

    try {
      return eventUseCase
          .getEventById(eventId, user.getId())
          .map(
              event -> {
                meterRegistry.counter("events.read", "result", "found").increment();
                return Response.ok(event).build();
              })
          .orElseGet(
              () -> {
                LOG.warnf("Event not found with ID: %d", eventId);
                meterRegistry.counter("events.read", "result", "not_found").increment();
                return Response.status(Response.Status.NOT_FOUND)
                    .entity(new ErrorResponse("Event not found"))
                    .build();
              });
    } finally {
      sample.stop(meterRegistry.timer("events.read.time"));
    }
  }

  @Override
  @Authenticated
  @DELETE
  @Path("/events/{eventId}")
  public Response deleteEvent(@PathParam("eventId") Long eventId) {
    User user = authenticatedUser.get();
    LOG.infof("User %d attempting to delete event ID: %d", user.getId(), eventId);
    Timer.Sample sample = Timer.start(meterRegistry);

    try {
      eventUseCase.deleteEvent(eventId, user.getId());
      meterRegistry.counter("events.deleted").increment();
      LOG.infof("Successfully deleted event ID: %d", eventId);
      return Response.noContent().build();
    } catch (IllegalArgumentException e) {
      LOG.warnf("Event not found with ID: %d", eventId);
      meterRegistry.counter("events.delete", "result", "not_found").increment();
      return Response.status(Response.Status.NOT_FOUND)
          .entity(new ErrorResponse(e.getMessage()))
          .build();
    } catch (SecurityException e) {
      LOG.warnf("User %d not authorized to delete event ID: %d", user.getId(), eventId);
      meterRegistry.counter("events.delete", "result", "forbidden").increment();
      return Response.status(Response.Status.FORBIDDEN)
          .entity(new ErrorResponse(e.getMessage()))
          .build();
    } catch (Exception e) {
      LOG.errorf(e, "Failed to delete event ID: %d", eventId);
      meterRegistry.counter("events.errors", "operation", "delete").increment();
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
          .entity(new ErrorResponse("Failed to delete event: " + e.getMessage()))
          .build();
    } finally {
      sample.stop(meterRegistry.timer("events.delete.time"));
    }
  }

  @Override
  @Authenticated
  @PUT
  @Path("/events/{eventId}")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response updateEvent(
      @PathParam("eventId") Long eventId, EventUpdateRequest eventUpdateRequest) {
    User user = authenticatedUser.get();
    LOG.infof("User %d attempting to update event ID: %d", user.getId(), eventId);
    Timer.Sample sample = Timer.start(meterRegistry);

    try {
      Event event = eventUseCase.updateEvent(eventId, user.getId(), eventUpdateRequest);
      meterRegistry.counter("events.updated").increment();
      LOG.infof("Successfully updated event ID: %d", eventId);
      return Response.ok(event).build();
    } catch (IllegalArgumentException e) {
      LOG.warnf("Event not found with ID: %d", eventId);
      meterRegistry.counter("events.update", "result", "not_found").increment();
      return Response.status(Response.Status.NOT_FOUND)
          .entity(new ErrorResponse(e.getMessage()))
          .build();
    } catch (SecurityException e) {
      LOG.warnf("User %d not authorized to update event ID: %d", user.getId(), eventId);
      meterRegistry.counter("events.update", "result", "forbidden").increment();
      return Response.status(Response.Status.FORBIDDEN)
          .entity(new ErrorResponse(e.getMessage()))
          .build();
    } catch (Exception e) {
      LOG.errorf(e, "Failed to update event ID: %d", eventId);
      meterRegistry.counter("events.errors", "operation", "update").increment();
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
          .entity(new ErrorResponse("Failed to update event: " + e.getMessage()))
          .build();
    } finally {
      sample.stop(meterRegistry.timer("events.update.time"));
    }
  }

  @Override
  @Authenticated
  @POST
  @Path("/events/join-by-code")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response joinEventByCode(EventJoinByCodeRequest joinEventByCodeRequest) {
    User user = authenticatedUser.get();
    LOG.infof("User %d attempting to join event by code", user.getId());

    try {
      EventAttendee attendee = eventUseCase.joinEventByCode(user.getId(), joinEventByCodeRequest);
      meterRegistry.counter("events.join", "result", "success").increment();
      LOG.infof("User %d successfully joined event", user.getId());
      return Response.status(Response.Status.CREATED).entity(attendee).build();
    } catch (IllegalArgumentException e) {
      LOG.warnf("Invalid join request from user %d: %s", user.getId(), e.getMessage());
      meterRegistry.counter("events.join", "result", "invalid").increment();
      return Response.status(Response.Status.BAD_REQUEST)
          .entity(new ErrorResponse(e.getMessage()))
          .build();
    } catch (IllegalStateException e) {
      LOG.warnf("Join conflict for user %d: %s", user.getId(), e.getMessage());
      meterRegistry.counter("events.join", "result", "conflict").increment();
      return Response.status(Response.Status.CONFLICT)
          .entity(new ErrorResponse(e.getMessage()))
          .build();
    } catch (Exception e) {
      LOG.errorf(e, "Failed to join event for user %d", user.getId());
      meterRegistry.counter("events.errors", "operation", "join").increment();
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
          .entity(new ErrorResponse("Failed to join event: " + e.getMessage()))
          .build();
    }
  }

  @Override
  @Authenticated
  @GET
  @Path("/events/{eventId}/attendees")
  @Produces(MediaType.APPLICATION_JSON)
  public Response listEventAttendees(@PathParam("eventId") Long eventId) {
    User user = authenticatedUser.get();
    try {
      List<EventAttendee> attendees = eventUseCase.listEventAttendees(eventId, user.getId());
      return Response.ok(attendees).build();
    } catch (IllegalArgumentException e) {
      return Response.status(Response.Status.NOT_FOUND)
          .entity(new ErrorResponse(e.getMessage()))
          .build();
    } catch (SecurityException e) {
      return Response.status(Response.Status.FORBIDDEN)
          .entity(new ErrorResponse(e.getMessage()))
          .build();
    }
  }

  @Override
  @Authenticated
  @GET
  @Path("/users/{userId}/events")
  @Produces(MediaType.APPLICATION_JSON)
  public Response listEventsByUser(@PathParam("userId") Long userId) {
    User user = authenticatedUser.get();
    try {
      List<Event> events = eventUseCase.listEventsByUser(userId, user.getId());
      return Response.ok(events).build();
    } catch (IllegalArgumentException e) {
      return Response.status(Response.Status.NOT_FOUND)
          .entity(new ErrorResponse(e.getMessage()))
          .build();
    }
  }

  @Override
  @Authenticated
  @GET
  @Path("/me/attended-events")
  @Produces(MediaType.APPLICATION_JSON)
  public Response listMyAttendedEvents() {
    User user = authenticatedUser.get();

    try {
      List<Event> events = eventUseCase.listAttendedEventsByUser(user.getId(), user.getId());
      return Response.ok(events).build();
    } catch (IllegalArgumentException e) {
      return Response.status(Response.Status.NOT_FOUND)
          .entity(new ErrorResponse(e.getMessage()))
          .build();
    }
  }

  @Override
  @Authenticated
  @GET
  @Path("/events/{eventId}/live")
  @Produces("text/event-stream")
  public Response streamEventLive(@PathParam("eventId") Long eventId) {
    if (!eventUseCase.eventExists(eventId)) {
      return Response.status(Response.Status.NOT_FOUND)
          .entity(new ErrorResponse("Event not found"))
          .build();
    }

    // SSE streaming implementation would go here
    // For now, return not implemented
    return Response.status(Response.Status.NOT_IMPLEMENTED)
        .entity(new ErrorResponse("Live streaming not yet implemented"))
        .build();
  }

  @Override
  @Authenticated
  @GET
  @Path("/events/{eventId}/users/{userId}")
  @Produces(MediaType.APPLICATION_JSON)
  public Response getEventUserData(
      @PathParam("eventId") Long eventId, @PathParam("userId") Long userId) {
    User currentUser = authenticatedUser.get();
    LOG.debugf(
        "User %d requesting event user data for event %d, user %d",
        currentUser.getId(), eventId, userId);

    // Check if event exists
    if (!eventUseCase.eventExists(eventId)) {
      return Response.status(Response.Status.NOT_FOUND)
          .entity(new ErrorResponse("Event not found"))
          .build();
    }

    // Check if requester is an attendee of the event
    if (!eventUseCase.isUserAttendee(eventId, currentUser.getId())) {
      return Response.status(Response.Status.FORBIDDEN)
          .entity(new ErrorResponse("Access denied. You are not an attendee of this event."))
          .build();
    }

    // Get user data
    return eventUseCase
        .getEventUserData(eventId, userId)
        .map(data -> Response.ok(data).build())
        .orElse(
            Response.status(Response.Status.NOT_FOUND)
                .entity(new ErrorResponse("User data not found"))
                .build());
  }

  @Override
  @Authenticated
  @PUT
  @Path("/events/{eventId}/users/{userId}")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response updateEventUserData(
      @PathParam("eventId") Long eventId,
      @PathParam("userId") Long userId,
      EventUserDataUpdateRequest updateRequest) {
    User currentUser = authenticatedUser.get();
    LOG.infof(
        "User %d updating event user data for event %d, user %d",
        currentUser.getId(), eventId, userId);

    // Check if event exists
    if (!eventUseCase.eventExists(eventId)) {
      return Response.status(Response.Status.NOT_FOUND)
          .entity(new ErrorResponse("Event not found"))
          .build();
    }

    // Check if user can only update their own data
    if (!currentUser.getId().equals(userId)) {
      return Response.status(Response.Status.FORBIDDEN)
          .entity(new ErrorResponse("Access denied. You can only update your own data."))
          .build();
    }

    // Check if user is an attendee of the event
    if (!eventUseCase.isUserAttendee(eventId, userId)) {
      return Response.status(Response.Status.FORBIDDEN)
          .entity(new ErrorResponse("Access denied. You are not an attendee of this event."))
          .build();
    }

    try {
      EventUserData data = eventUseCase.updateEventUserData(eventId, userId, updateRequest);
      return Response.ok(data).build();
    } catch (Exception e) {
      LOG.errorf(e, "Failed to update event user data for event %d, user %d", eventId, userId);
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
          .entity(new ErrorResponse("Failed to update user data: " + e.getMessage()))
          .build();
    }
  }

  @Override
  @Authenticated
  @GET
  @Path("/events/{eventId}/meta")
  @Produces(MediaType.APPLICATION_JSON)
  public Response getEventMeta(@PathParam("eventId") Long eventId) {
    User user = authenticatedUser.get();
    try {
      UserMeta metaData = usermetaUseCase.getEventMeta(eventId, user.getId());
      return Response.ok(metaData).build();
    } catch (SecurityException e) {
      return Response.status(Response.Status.FORBIDDEN)
          .entity(new ErrorResponse(e.getMessage()))
          .build();
    } catch (IllegalArgumentException e) {
      return Response.status(Response.Status.NOT_FOUND)
          .entity(new ErrorResponse(e.getMessage()))
          .build();
    }
  }

  @Override
  @Authenticated
  @PUT
  @Path("/events/{eventId}/meta")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response updateEventMeta(@PathParam("eventId") Long eventId, UserMeta userMeta) {
    User user = authenticatedUser.get();
    try {
      UserMeta requestData = new UserMeta();
      requestData.setUsermeta(userMeta.getUsermeta());
      UserMeta metaData = usermetaUseCase.updateEventMeta(eventId, user.getId(), requestData);
      return Response.ok(metaData).build();
    } catch (SecurityException e) {
      return Response.status(Response.Status.FORBIDDEN)
          .entity(new ErrorResponse(e.getMessage()))
          .build();
    } catch (IllegalArgumentException e) {
      return Response.status(Response.Status.NOT_FOUND)
          .entity(new ErrorResponse(e.getMessage()))
          .build();
    }
  }

  @Override
  @Authenticated
  @GET
  @Path("/events/{eventId}/users/{userId}/meta")
  @Produces(MediaType.APPLICATION_JSON)
  public Response getEventUserDataMeta(
      @PathParam("eventId") Long eventId, @PathParam("userId") Long userId) {
    User user = authenticatedUser.get();
    try {
      UserMeta metaData = usermetaUseCase.getEventUserDataMeta(eventId, userId, user.getId());
      return Response.ok(metaData).build();
    } catch (SecurityException e) {
      return Response.status(Response.Status.FORBIDDEN)
          .entity(new ErrorResponse(e.getMessage()))
          .build();
    } catch (IllegalArgumentException e) {
      return Response.status(Response.Status.NOT_FOUND)
          .entity(new ErrorResponse(e.getMessage()))
          .build();
    }
  }

  @Override
  @Authenticated
  @PUT
  @Path("/events/{eventId}/users/{userId}/meta")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response updateEventUserDataMeta(
      @PathParam("eventId") Long eventId, @PathParam("userId") Long userId, UserMeta userMeta) {
    User user = authenticatedUser.get();
    try {
      UserMeta metaData =
          usermetaUseCase.updateEventUserDataMeta(eventId, userId, user.getId(), userMeta);
      return Response.ok(metaData).build();
    } catch (SecurityException e) {
      return Response.status(Response.Status.FORBIDDEN)
          .entity(new ErrorResponse(e.getMessage()))
          .build();
    } catch (IllegalArgumentException e) {
      return Response.status(Response.Status.NOT_FOUND)
          .entity(new ErrorResponse(e.getMessage()))
          .build();
    }
  }

  @Override
  @Authenticated
  @GET
  @Path("/events/{eventId}/attendees/{attendeeUserId}/meta")
  @Produces(MediaType.APPLICATION_JSON)
  public Response getEventAttendeeMeta(
      @PathParam("eventId") Long eventId, @PathParam("attendeeUserId") Long attendeeUserId) {
    User user = authenticatedUser.get();
    try {
      UserMeta metaData =
          usermetaUseCase.getEventAttendeeMeta(eventId, attendeeUserId, user.getId());
      return Response.ok(metaData).build();
    } catch (SecurityException e) {
      return Response.status(Response.Status.FORBIDDEN)
          .entity(new ErrorResponse(e.getMessage()))
          .build();
    } catch (IllegalArgumentException e) {
      return Response.status(Response.Status.NOT_FOUND)
          .entity(new ErrorResponse(e.getMessage()))
          .build();
    }
  }

  @Override
  @Authenticated
  @PUT
  @Path("/events/{eventId}/attendees/{attendeeUserId}/meta")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response updateEventAttendeeMeta(
      @PathParam("eventId") Long eventId,
      @PathParam("attendeeUserId") Long attendeeUserId,
      UserMeta userMeta) {
    User user = authenticatedUser.get();
    try {
      UserMeta metaData =
          usermetaUseCase.updateEventAttendeeMeta(eventId, attendeeUserId, user.getId(), userMeta);
      return Response.ok(metaData).build();
    } catch (SecurityException e) {
      return Response.status(Response.Status.FORBIDDEN)
          .entity(new ErrorResponse(e.getMessage()))
          .build();
    } catch (IllegalArgumentException e) {
      return Response.status(Response.Status.NOT_FOUND)
          .entity(new ErrorResponse(e.getMessage()))
          .build();
    }
  }
}
