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

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public Long getUserId() {
    return userId;
  }

  public void setUserId(Long userId) {
    this.userId = userId;
  }

  public UUID getCallSessionId() {
    return callSessionId;
  }

  public void setCallSessionId(UUID callSessionId) {
    this.callSessionId = callSessionId;
  }

  public String getVhrpSessionId() {
    return vhrpSessionId;
  }

  public void setVhrpSessionId(String vhrpSessionId) {
    this.vhrpSessionId = vhrpSessionId;
  }

  public String getVhrpThreadId() {
    return vhrpThreadId;
  }

  public void setVhrpThreadId(String vhrpThreadId) {
    this.vhrpThreadId = vhrpThreadId;
  }

  public String getSpeedDialId() {
    return speedDialId;
  }

  public void setSpeedDialId(String speedDialId) {
    this.speedDialId = speedDialId;
  }

  public String getVoiceAgentId() {
    return voiceAgentId;
  }

  public void setVoiceAgentId(String voiceAgentId) {
    this.voiceAgentId = voiceAgentId;
  }

  public LocalDateTime getStartedAt() {
    return startedAt;
  }

  public void setStartedAt(LocalDateTime startedAt) {
    this.startedAt = startedAt;
  }

  public LocalDateTime getEndedAt() {
    return endedAt;
  }

  public void setEndedAt(LocalDateTime endedAt) {
    this.endedAt = endedAt;
  }

  public SessionThreadData getThread() {
    return thread;
  }

  public void setThread(SessionThreadData thread) {
    this.thread = thread;
  }

  public LocalDateTime getDeletedAt() {
    return deletedAt;
  }

  public void setDeletedAt(LocalDateTime deletedAt) {
    this.deletedAt = deletedAt;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime createdAt) {
    this.createdAt = createdAt;
  }

  public LocalDateTime getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(LocalDateTime updatedAt) {
    this.updatedAt = updatedAt;
  }
}
