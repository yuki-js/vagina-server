package app.vagina.server.resource;

import app.vagina.server.entity.CallSession;
import app.vagina.server.generated.api.SessionsApi;
import app.vagina.server.generated.model.BulkDeleteSessions200Response;
import app.vagina.server.generated.model.BulkDeleteSessionsRequest;
import app.vagina.server.generated.model.GetSession200Response;
import app.vagina.server.generated.model.ListSessions200Response;
import app.vagina.server.generated.model.ListSessions200ResponseItemsInner;
import app.vagina.server.generated.model.SessionThread;
import app.vagina.server.support.Authenticated;
import app.vagina.server.support.AuthenticatedUser;
import app.vagina.server.usecase.CallSessionUsecase;
import app.vagina.server.usecase.CallSessionUsecase.BulkDeleteResult;
import app.vagina.server.usecase.CallSessionUsecase.SessionPage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.OffsetDateTime;

@ApplicationScoped
@Path("/sessions")
@Authenticated
public class SessionsApiImpl implements SessionsApi {
  private static final TypeReference<Map<String, Object>> THREAD_TYPE = new TypeReference<>() {};
  private static final TypeReference<List<Map<String, Object>>> THREAD_ITEMS_TYPE =
      new TypeReference<>() {};

  @Inject CallSessionUsecase callSessionUsecase;
  @Inject AuthenticatedUser authenticatedUser;
  @Inject ObjectMapper objectMapper;

  @Override
  public Response listSessions(Integer limit, String cursor) {
    Long userId = authenticatedUser.get().getId();
    SessionPage page = callSessionUsecase.listSessions(userId, limit, cursor);
    ListSessions200Response response =
        new ListSessions200Response()
            .items(page.items().stream().map(this::toListItem).toList())
            .nextCursor(page.nextCursor());
    return Response.ok(response).build();
  }

  @Override
  public Response getSession(UUID sessionId) {
    Long userId = authenticatedUser.get().getId();
    CallSession callSession = callSessionUsecase.getSession(userId, sessionId);
    return Response.ok(toDetailResponse(callSession)).build();
  }

  @Override
  public Response deleteSession(UUID sessionId) {
    Long userId = authenticatedUser.get().getId();
    callSessionUsecase.deleteSession(userId, sessionId);
    return Response.noContent().build();
  }

  @Override
  public Response bulkDeleteSessions(BulkDeleteSessionsRequest bulkDeleteSessionsRequest) {
    Long userId = authenticatedUser.get().getId();
    BulkDeleteResult result =
        callSessionUsecase.bulkDeleteSessions(userId, bulkDeleteSessionsRequest.getIds());
    return Response.ok(new BulkDeleteSessions200Response().deletedCount(result.deletedCount()))
        .build();
  }

  private ListSessions200ResponseItemsInner toListItem(CallSession callSession) {
    return new ListSessions200ResponseItemsInner()
        .id(callSession.getCallSessionId())
        .startedAt(toOffsetDateTime(callSession.getStartedAt()))
        .endedAt(toOffsetDateTime(callSession.getEndedAt()));
  }

  private GetSession200Response toDetailResponse(CallSession callSession) {
    return new GetSession200Response()
        .id(callSession.getCallSessionId())
        .startedAt(toOffsetDateTime(callSession.getStartedAt()))
        .endedAt(toOffsetDateTime(callSession.getEndedAt()))
        .speedDialId(callSession.getSpeedDialId())
        .voiceAgentId(callSession.getVoiceAgentId())
        .thread(toThread(callSession.getThread()));
  }

  private SessionThread toThread(String threadJson) {
    try {
      Map<String, Object> rawThread = objectMapper.readValue(threadJson, THREAD_TYPE);
      SessionThread thread = new SessionThread();
      thread.setId((String) rawThread.get("id"));
      thread.setConversationId((String) rawThread.get("conversationId"));
      thread.setItems(objectMapper.convertValue(rawThread.get("items"), THREAD_ITEMS_TYPE));
      return thread;
    } catch (IllegalArgumentException | JsonProcessingException e) {
      throw new IllegalStateException("Failed to deserialize saved session thread", e);
    }
  }

  private OffsetDateTime toOffsetDateTime(LocalDateTime value) {
    return value == null ? null : value.atOffset(ZoneOffset.UTC);
  }
}
