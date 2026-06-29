package app.vagina.server.service;

import app.vagina.server.entity.CallSession;
import app.vagina.server.mapper.CallSessionMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class CallSessionService {
  @Inject CallSessionMapper callSessionMapper;

  public List<CallSession> findActivePage(
      Long userId, LocalDateTime cursorStartedAt, Long cursorId, int limit) {
    return callSessionMapper.findActivePageByUserId(userId, cursorStartedAt, cursorId, limit);
  }

  public Optional<CallSession> findActiveByCallSessionId(Long userId, UUID callSessionId) {
    return callSessionMapper.findActiveByUserIdAndCallSessionId(userId, callSessionId);
  }

  @Transactional
  public boolean softDelete(Long userId, UUID callSessionId) {
    return callSessionMapper.softDeleteActiveByUserIdAndCallSessionId(
            userId, callSessionId, LocalDateTime.now())
        > 0;
  }

  @Transactional
  public int softDeleteBulk(Long userId, List<UUID> callSessionIds) {
    if (callSessionIds.isEmpty()) {
      return 0;
    }
    return callSessionMapper.softDeleteActiveByUserIdAndCallSessionIds(
        userId, callSessionIds, LocalDateTime.now());
  }

  @Transactional
  public boolean insertIdempotently(CallSession callSession) {
    LocalDateTime now = LocalDateTime.now();
    if (callSession.getCreatedAt() == null) {
      callSession.setCreatedAt(now);
    }
    if (callSession.getUpdatedAt() == null) {
      callSession.setUpdatedAt(now);
    }
    if (callSession.getCallSessionId() == null) {
      callSession.setCallSessionId(UUID.randomUUID());
    }
    return callSessionMapper.insertIdempotently(callSession) > 0;
  }
}
