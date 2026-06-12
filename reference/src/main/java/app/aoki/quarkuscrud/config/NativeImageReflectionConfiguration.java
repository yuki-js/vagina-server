package app.aoki.quarkuscrud.config;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Configuration class to register classes for reflection in native image builds.
 *
 * <p>This is necessary because GraalVM native images don't support full reflection by default.
 * Generated OpenAPI model classes need to be explicitly registered for JSON serialization to work
 * correctly in native mode.
 *
 * <p>This fixes the issue where invitationCode and other fields were missing from API responses in
 * production (native image) but working correctly in local JVM mode.
 */
@RegisterForReflection(
    targets = {
      app.aoki.quarkuscrud.generated.model.Event.class,
      app.aoki.quarkuscrud.generated.model.Event.StatusEnum.class,
      app.aoki.quarkuscrud.generated.model.EventCreateRequest.class,
      app.aoki.quarkuscrud.generated.model.EventAttendee.class,
      app.aoki.quarkuscrud.generated.model.EventJoinByCodeRequest.class,
      app.aoki.quarkuscrud.generated.model.EventLiveEvent.class,
      app.aoki.quarkuscrud.generated.model.EventUserData.class,
      app.aoki.quarkuscrud.generated.model.EventUserDataUpdateRequest.class,
      app.aoki.quarkuscrud.generated.model.User.class,
      app.aoki.quarkuscrud.generated.model.UserPublic.class,
      app.aoki.quarkuscrud.generated.model.UserProfile.class,
      app.aoki.quarkuscrud.generated.model.UserProfileUpdateRequest.class,
      app.aoki.quarkuscrud.generated.model.Friendship.class,
      app.aoki.quarkuscrud.generated.model.ErrorResponse.class,
      app.aoki.quarkuscrud.generated.model.ProfileMissing.class,
      app.aoki.quarkuscrud.generated.model.ProfileMissing.CodeEnum.class
    })
public class NativeImageReflectionConfiguration {}
