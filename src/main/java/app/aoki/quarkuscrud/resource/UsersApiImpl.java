package app.aoki.quarkuscrud.resource;

import app.aoki.quarkuscrud.entity.User;
import app.aoki.quarkuscrud.generated.api.UsersApi;
import app.aoki.quarkuscrud.generated.model.UserMeta;
import app.aoki.quarkuscrud.generated.model.UserPublic;
import app.aoki.quarkuscrud.service.UserService;
import app.aoki.quarkuscrud.support.Authenticated;
import app.aoki.quarkuscrud.support.AuthenticatedUser;
import app.aoki.quarkuscrud.support.ErrorResponse;
import app.aoki.quarkuscrud.usecase.UsermetaUseCase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.ZoneOffset;

@ApplicationScoped
@Path("/api")
public class UsersApiImpl implements UsersApi {

  @Inject UserService userService;
  @Inject UsermetaUseCase usermetaUseCase;
  @Inject AuthenticatedUser authenticatedUser;

  @Override
  @Authenticated
  @GET
  @Path("/users/{userId}")
  @Produces(MediaType.APPLICATION_JSON)
  public Response getUserById(@PathParam("userId") Long userId) {
    return userService
        .findById(userId)
        .map(user -> Response.ok(toUserPublicResponse(user)).build())
        .orElse(
            Response.status(Response.Status.NOT_FOUND)
                .entity(new ErrorResponse("User not found"))
                .build());
  }

  @Override
  @Authenticated
  @GET
  @Path("/users/{userId}/meta")
  @Produces(MediaType.APPLICATION_JSON)
  public Response getUserMeta(@PathParam("userId") Long userId) {
    User user = authenticatedUser.get();
    try {
      UserMeta metaData = usermetaUseCase.getUserMeta(userId, user.getId());
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
  @Path("/users/{userId}/meta")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response updateUserMeta(@PathParam("userId") Long userId, UserMeta userMeta) {
    User user = authenticatedUser.get();
    try {
      UserMeta requestData = new UserMeta();
      requestData.setUsermeta(userMeta.getUsermeta());
      UserMeta metaData = usermetaUseCase.updateUserMeta(userId, user.getId(), requestData);
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

  private UserPublic toUserPublicResponse(User user) {
    UserPublic response = new UserPublic();
    response.setId(user.getId());
    response.setCreatedAt(user.getCreatedAt().atOffset(ZoneOffset.UTC));
    if (user.getAccountLifecycle() != null) {
      response.setAccountLifecycle(
          UserPublic.AccountLifecycleEnum.fromValue(user.getAccountLifecycle().getValue()));
    }
    // TODO: Add profile summary if needed
    return response;
  }
}
