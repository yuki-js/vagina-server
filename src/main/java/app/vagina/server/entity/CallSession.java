package app.vagina.server.entity;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.time.LocalDateTime;
import java.util.UUID;

@RegisterForReflection
public class CallSession {
  private Long id;
  private Long userId;
  private UUID callSessionId;
  private String vhrpSessionId;
  private String vhrpThreadId;
  private String speedDialId;
  private String voiceAgentId;
  private LocalDateTime startedAt;
  private LocalDateTime endedAt;
  private SessionThreadData thread;
  private LocalDateTime deletedAt;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;

  public CallSession(
      Long id,
      Long userId,
      UUID callSessionId,
      String vhrpSessionId,
      String vhrpThreadId,
      String speedDialId,
      String voiceAgentId,
      LocalDateTime startedAt,
      LocalDateTime endedAt,
      SessionThreadData thread,
      LocalDateTime deletedAt,
      LocalDateTime createdAt,
      LocalDateTime updatedAt) {
    this.id = id;
    this.userId = userId;
    this.callSessionId = callSessionId;
    this.vhrpSessionId = vhrpSessionId;
    this.vhrpThreadId = vhrpThreadId;
    this.speedDialId = speedDialId;
    this.voiceAgentId = voiceAgentId;
    this.startedAt = startedAt;
    this.endedAt = endedAt;
    this.thread = thread;
    this.deletedAt = deletedAt;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public CallSession attachThread(SessionThreadData thread) {
    this.thread = thread;
    return this;
  }

  public boolean isVisibleInHistory() {
    return deletedAt == null;
  }

  public Long getId() {
    return id;
  }

  public Long getUserId() {
    return userId;
  }

  public UUID getCallSessionId() {
    return callSessionId;
  }

  public String getVhrpSessionId() {
    return vhrpSessionId;
  }

  public String getVhrpThreadId() {
    return vhrpThreadId;
  }

  public String getSpeedDialId() {
    return speedDialId;
  }

  public String getVoiceAgentId() {
    return voiceAgentId;
  }

  public LocalDateTime getStartedAt() {
    return startedAt;
  }

  public LocalDateTime getEndedAt() {
    return endedAt;
  }

  public SessionThreadData getThread() {
    return thread;
  }

  public LocalDateTime getDeletedAt() {
    return deletedAt;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public LocalDateTime getUpdatedAt() {
    return updatedAt;
  }
}
