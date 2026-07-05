package app.vagina.server.service;

import app.vagina.server.entity.CallSession;
import app.vagina.server.entity.SessionThreadData;
import app.vagina.server.mapper.CallSessionMapper;
import app.vagina.server.mapper.CallSessionMapper.Row;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class CallSessionService {
  @Inject CallSessionMapper callSessionMapper;
  @Inject ObjectStorageService objectStorageService;
  @Inject ObjectMapper objectMapper;

  public List<CallSession> findActivePage(
      Long userId, LocalDateTime cursorStartedAt, Long cursorId, int limit) {
    return callSessionMapper
        .findActivePageByUserId(userId, cursorStartedAt, cursorId, limit)
        .stream()
        .map(this::toDomainWithoutThread)
        .toList();
  }

  public Optional<CallSession> findActiveByCallSessionId(Long userId, UUID callSessionId) {
    return callSessionMapper
        .findActiveByUserIdAndCallSessionId(userId, callSessionId)
        .map(this::toDomainWithThread);
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
  public boolean insertTerminalSession(
      Long userId,
      String vhrpSessionId,
      String vhrpThreadId,
      String speedDialId,
      String voiceAgentId,
      LocalDateTime startedAt,
      LocalDateTime endedAt,
      SessionThreadData thread) {
    String threadBlobKey = sessionThreadBlobKey(userId, vhrpSessionId);
    objectStorageService.save(
        threadBlobKey,
        writeThreadJson(thread).getBytes(StandardCharsets.UTF_8),
        "application/json");

    LocalDateTime now = LocalDateTime.now();
    Row row = new Row();
    row.setUserId(userId);
    row.setCallSessionId(UUID.randomUUID());
    row.setVhrpSessionId(vhrpSessionId);
    row.setVhrpThreadId(vhrpThreadId);
    row.setSpeedDialId(speedDialId);
    row.setVoiceAgentId(voiceAgentId);
    row.setStartedAt(startedAt);
    row.setEndedAt(endedAt == null ? now : endedAt);
    row.setThreadBlobKey(threadBlobKey);
    row.setCreatedAt(now);
    row.setUpdatedAt(now);
    return callSessionMapper.insertIdempotently(row) > 0;
  }

  private CallSession toDomainWithThread(Row row) {
    CallSession callSession = toDomainWithoutThread(row);
    callSession.setThread(readThread(row.getThreadBlobKey()));
    return callSession;
  }

  private CallSession toDomainWithoutThread(Row row) {
    CallSession callSession = new CallSession();
    callSession.setId(row.getId());
    callSession.setUserId(row.getUserId());
    callSession.setCallSessionId(row.getCallSessionId());
    callSession.setVhrpSessionId(row.getVhrpSessionId());
    callSession.setVhrpThreadId(row.getVhrpThreadId());
    callSession.setSpeedDialId(row.getSpeedDialId());
    callSession.setVoiceAgentId(row.getVoiceAgentId());
    callSession.setStartedAt(row.getStartedAt());
    callSession.setEndedAt(row.getEndedAt());
    callSession.setDeletedAt(row.getDeletedAt());
    callSession.setCreatedAt(row.getCreatedAt());
    callSession.setUpdatedAt(row.getUpdatedAt());
    return callSession;
  }

  private SessionThreadData readThread(String threadBlobKey) {
    byte[] payload =
        objectStorageService
            .read(threadBlobKey)
            .orElseThrow(() -> new IllegalStateException("Saved session thread blob not found"));
    try {
      return objectMapper.readValue(payload, SessionThreadData.class);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to deserialize saved session thread", e);
    }
  }

  private String writeThreadJson(SessionThreadData thread) {
    try {
      return objectMapper.writeValueAsString(thread);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Session thread could not be serialized", e);
    }
  }

  private String sessionThreadBlobKey(Long userId, String vhrpSessionId) {
    return "sessions/" + userId + "/" + encodeKeyPart(vhrpSessionId) + "/thread.json";
  }

  private String encodeKeyPart(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }
}
