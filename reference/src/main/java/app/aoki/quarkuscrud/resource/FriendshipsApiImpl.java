package app.aoki.quarkuscrud.resource;

import app.aoki.quarkuscrud.entity.User;
import app.aoki.quarkuscrud.generated.api.FriendshipsApi;
import app.aoki.quarkuscrud.generated.model.Friendship;
import app.aoki.quarkuscrud.generated.model.ReceiveFriendshipRequest;
import app.aoki.quarkuscrud.generated.model.UserMeta;
import app.aoki.quarkuscrud.support.Authenticated;
import app.aoki.quarkuscrud.support.AuthenticatedUser;
import app.aoki.quarkuscrud.support.ErrorResponse;
import app.aoki.quarkuscrud.usecase.FriendshipUseCase;
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
import java.util.List;
import org.postgresql.util.PSQLException;

@ApplicationScoped
@Path("/api")
public class FriendshipsApiImpl implements FriendshipsApi {

  @Inject FriendshipUseCase friendshipUseCase;
  @Inject UsermetaUseCase usermetaUseCase;
  @Inject AuthenticatedUser authenticatedUser;

  @Override
  @Authenticated
  public Response getFriendshipByOtherUser(Long otherUserId) {
    User user = authenticatedUser.get();
    try {
      Friendship friendship = friendshipUseCase.getFriendshipByOtherUser(user.getId(), otherUserId);
      return Response.ok(friendship).build();
    } catch (IllegalArgumentException e) {
      return Response.status(Response.Status.NOT_FOUND)
          .entity(new ErrorResponse(e.getMessage()))
          .build();
    }
  }

  @Override
  @Authenticated
  public Response listReceivedFriendships() {
    User user = authenticatedUser.get();
    List<Friendship> friendships = friendshipUseCase.listReceivedFriendships(user.getId());
    return Response.ok(friendships).build();
  }

  @Override
  @Authenticated
  public Response receiveFriendship(Long userId, ReceiveFriendshipRequest request) {
    User sender = authenticatedUser.get();

    try {
      java.util.Map<String, Object> meta =
          request.getMeta() != null ? request.getMeta() : new java.util.HashMap<>();
      Friendship friendship =
          friendshipUseCase.createOrUpdateFriendship(sender.getId(), userId, meta);

      // Return 200 OK for idempotent operation (could be existing or new friendship)
      return Response.ok(friendship).build();
    } catch (IllegalArgumentException e) {
      return Response.status(Response.Status.NOT_FOUND)
          .entity(new ErrorResponse(e.getMessage()))
          .build();
    } catch (Exception e) {
      if (e.getCause() instanceof PSQLException psqlException
          && "23505".equals(psqlException.getSQLState())) {
        return Response.status(Response.Status.CONFLICT)
            .entity(new ErrorResponse("Friendship already exists"))
            .build();
      }
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
          .entity(new ErrorResponse("Failed to create friendship: " + e.getMessage()))
          .build();
    }
  }

  @Override
  @Authenticated
  @GET
  @Path("/friendships/{otherUserId}/meta")
  @Produces(MediaType.APPLICATION_JSON)
  public Response getFriendshipMeta(@PathParam("otherUserId") Long otherUserId) {
    User user = authenticatedUser.get();
    try {
      UserMeta metaData = usermetaUseCase.getFriendshipMeta(user.getId(), otherUserId);
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
  @Path("/friendships/{otherUserId}/meta")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response updateFriendshipMeta(
      @PathParam("otherUserId") Long otherUserId, UserMeta userMeta) {
    User user = authenticatedUser.get();
    try {
      UserMeta requestData = new UserMeta();
      requestData.setUsermeta(userMeta.getUsermeta());
      UserMeta metaData =
          usermetaUseCase.updateFriendshipMeta(user.getId(), otherUserId, requestData);
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
