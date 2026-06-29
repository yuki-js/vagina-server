package app.vagina.server.usecase;

import app.vagina.server.domain.error.NotFoundException;
import app.vagina.server.domain.error.ValidationException;
import app.vagina.server.entity.CallSession;
import app.vagina.server.service.CallSessionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class CallSessionUsecase {
  private static final int DEFAULT_LIMIT = 20;
  private static final int MAX_LIMIT = 50;
  private static final int BULK_DELETE_MAX_IDS = 100;
  private static final String CURSOR_VERSION = "v1";
  private static final String CURSOR_SEPARATOR = "~";

  @Inject CallSessionService callSessionService;
  @Inject ObjectMapper objectMapper;

  public SessionPage listSessions(Long userId, Integer limit, String cursor) {
    int effectiveLimit = normalizeLimit(limit);
    CursorKey cursorKey = decodeCursor(cursor);

    List<CallSession> rows =
        callSessionService.findActivePage(
            userId, cursorKey.startedAt(), cursorKey.id(), effectiveLimit + 1);
    boolean hasNext = rows.size() > effectiveLimit;
    List<CallSession> items = hasNext ? new ArrayList<>(rows.subList(0, effectiveLimit)) : rows;
    String nextCursor = hasNext ? encodeCursor(items.get(items.size() - 1)) : null;
    return new SessionPage(items, nextCursor);
  }

  public CallSession getSession(Long userId, UUID callSessionId) {
    return callSessionService
        .findActiveByCallSessionId(userId, callSessionId)
        .orElseThrow(() -> new NotFoundException("Session not found"));
  }

  public void deleteSession(Long userId, UUID callSessionId) {
    boolean deleted = callSessionService.softDelete(userId, callSessionId);
    if (!deleted) {
      throw new NotFoundException("Session not found");
    }
  }

  public BulkDeleteResult bulkDeleteSessions(Long userId, List<UUID> callSessionIds) {
    if (callSessionIds == null) {
      throw new ValidationException("Session ids are required");
    }
    if (callSessionIds.isEmpty()) {
      throw new ValidationException("At least one session id is required");
    }
    if (callSessionIds.size() > BULK_DELETE_MAX_IDS) {
      throw new ValidationException("At most 100 session ids may be deleted at once");
    }

    Set<UUID> uniqueIds = new LinkedHashSet<>(callSessionIds);
    int deletedCount = callSessionService.softDeleteBulk(userId, List.copyOf(uniqueIds));
    return new BulkDeleteResult(deletedCount);
  }

  public boolean saveTerminalSession(TerminalSessionSaveCommand command) {
    if (command == null) {
      throw new ValidationException("Terminal session save command is required");
    }
    if (command.userId() == null) {
      throw new ValidationException("Terminal session user id is required");
    }
    if (isBlank(command.vhrpSessionId())) {
      throw new ValidationException("VHRP session id is required");
    }
    if (isBlank(command.vhrpThreadId())) {
      throw new ValidationException("VHRP thread id is required");
    }
    if (isBlank(command.speedDialId())) {
      throw new ValidationException("Speed dial id is required");
    }
    if (isBlank(command.voiceAgentId())) {
      throw new ValidationException("Voice agent id is required");
    }
    if (command.startedAt() == null) {
      throw new ValidationException("Session start time is required");
    }

    Map<String, Object> thread = normalizeThread(command.thread());
    if (!hasVisibleItem(thread)) {
      return false;
    }

    CallSession callSession = new CallSession();
    callSession.setUserId(command.userId());
    callSession.setVhrpSessionId(command.vhrpSessionId());
    callSession.setVhrpThreadId(command.vhrpThreadId());
    callSession.setSpeedDialId(command.speedDialId());
    callSession.setVoiceAgentId(command.voiceAgentId());
    callSession.setStartedAt(command.startedAt());
    callSession.setEndedAt(command.endedAt() == null ? LocalDateTime.now() : command.endedAt());
    callSession.setThread(writeThreadJson(thread));
    return callSessionService.insertIdempotently(callSession);
  }

  private Map<String, Object> normalizeThread(Map<String, Object> thread) {
    if (thread == null) {
      return Map.of();
    }
    return sanitizeValue(thread);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> sanitizeValue(Map<String, Object> value) {
    Map<String, Object> sanitized = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : value.entrySet()) {
      if ("audioChunks".equals(entry.getKey())) {
        sanitized.put(entry.getKey(), List.of());
        continue;
      }
      Object entryValue = entry.getValue();
      if (entryValue instanceof Map<?, ?> nested) {
        sanitized.put(entry.getKey(), sanitizeValue((Map<String, Object>) nested));
      } else if (entryValue instanceof List<?> nestedList) {
        sanitized.put(entry.getKey(), sanitizeList(nestedList));
      } else if (entryValue instanceof byte[]) {
        sanitized.put(entry.getKey(), List.of());
      } else {
        sanitized.put(entry.getKey(), entryValue);
      }
    }
    return sanitized;
  }

  @SuppressWarnings("unchecked")
  private List<Object> sanitizeList(List<?> values) {
    List<Object> sanitized = new ArrayList<>();
    for (Object value : values) {
      if (value instanceof Map<?, ?> map) {
        sanitized.add(sanitizeValue((Map<String, Object>) map));
      } else if (value instanceof List<?> list) {
        sanitized.add(sanitizeList(list));
      } else if (value instanceof byte[]) {
        sanitized.add(List.of());
      } else {
        sanitized.add(value);
      }
    }
    return sanitized;
  }

  private boolean hasVisibleItem(Map<String, Object> thread) {
    Object itemsValue = thread.get("items");
    if (!(itemsValue instanceof List<?> items)) {
      return false;
    }
    return items.stream()
        .anyMatch(
            item ->
                item instanceof Map<?, ?> itemMap
                    && "visible".equals(String.valueOf(itemMap.get("displayState"))));
  }

  private String writeThreadJson(Map<String, Object> thread) {
    try {
      return objectMapper.writeValueAsString(thread);
    } catch (JsonProcessingException e) {
      throw new ValidationException("Session thread could not be serialized", e);
    }
  }

  private boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private int normalizeLimit(Integer limit) {
    if (limit == null) {
      return DEFAULT_LIMIT;
    }
    if (limit < 1 || limit > MAX_LIMIT) {
      throw new ValidationException("Limit must be between 1 and 50");
    }
    return limit;
  }

  private CursorKey decodeCursor(String cursor) {
    if (cursor == null || cursor.isBlank()) {
      return CursorKey.none();
    }

    try {
      String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
      String[] parts = decoded.split(CURSOR_SEPARATOR, 3);
      if (parts.length != 3 || !CURSOR_VERSION.equals(parts[0])) {
        throw new ValidationException("Invalid session cursor");
      }
      LocalDateTime startedAt =
          LocalDateTime.parse(parts[1], DateTimeFormatter.ISO_LOCAL_DATE_TIME);
      Long id = Long.valueOf(parts[2]);
      return new CursorKey(startedAt, id);
    } catch (IllegalArgumentException | DateTimeParseException e) {
      throw new ValidationException("Invalid session cursor", e);
    }
  }

  private String encodeCursor(CallSession callSession) {
    String raw =
        CURSOR_VERSION
            + CURSOR_SEPARATOR
            + callSession.getStartedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            + CURSOR_SEPARATOR
            + callSession.getId();
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  private record CursorKey(LocalDateTime startedAt, Long id) {
    static CursorKey none() {
      return new CursorKey(null, null);
    }
  }

  public record SessionPage(List<CallSession> items, String nextCursor) {}

  public record BulkDeleteResult(int deletedCount) {}

  public record TerminalSessionSaveCommand(
      Long userId,
      String vhrpSessionId,
      String vhrpThreadId,
      String speedDialId,
      String voiceAgentId,
      LocalDateTime startedAt,
      LocalDateTime endedAt,
      Map<String, Object> thread) {}
}
