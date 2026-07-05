package app.vagina.server.mapper;

import app.vagina.server.mapper.type.UuidTypeHandler;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CallSessionMapper {

  @Insert(
      "INSERT INTO call_sessions (user_id, call_session_id, vhrp_session_id, vhrp_thread_id, "
          + "speed_dial_id, voice_agent_id, started_at, ended_at, thread_blob_key, deleted_at, created_at, updated_at) "
          + "VALUES (#{userId}, #{callSessionId,typeHandler=app.vagina.server.mapper.type.UuidTypeHandler}, #{vhrpSessionId}, #{vhrpThreadId}, "
          + "#{speedDialId}, #{voiceAgentId}, #{startedAt}, #{endedAt}, #{threadBlobKey}, "
          + "#{deletedAt}, #{createdAt}, #{updatedAt}) "
          + "ON CONFLICT (vhrp_session_id) DO NOTHING")
  @Options(useGeneratedKeys = true, keyProperty = "id")
  int insertIdempotently(Row row);

  @Select(
      "SELECT id, user_id, call_session_id, vhrp_session_id, vhrp_thread_id, speed_dial_id, "
          + "voice_agent_id, started_at, ended_at, thread_blob_key, deleted_at, created_at, updated_at "
          + "FROM call_sessions WHERE id = #{id}")
  @Results(
      id = "callSessionRowResultMap",
      value = {
        @Result(property = "id", column = "id", id = true),
        @Result(property = "userId", column = "user_id"),
        @Result(
            property = "callSessionId",
            column = "call_session_id",
            javaType = UUID.class,
            typeHandler = UuidTypeHandler.class),
        @Result(property = "vhrpSessionId", column = "vhrp_session_id"),
        @Result(property = "vhrpThreadId", column = "vhrp_thread_id"),
        @Result(property = "speedDialId", column = "speed_dial_id"),
        @Result(property = "voiceAgentId", column = "voice_agent_id"),
        @Result(property = "startedAt", column = "started_at"),
        @Result(property = "endedAt", column = "ended_at"),
        @Result(property = "threadBlobKey", column = "thread_blob_key"),
        @Result(property = "deletedAt", column = "deleted_at"),
        @Result(property = "createdAt", column = "created_at"),
        @Result(property = "updatedAt", column = "updated_at")
      })
  Optional<Row> findById(@Param("id") Long id);

  @Select(
      "<script>"
          + "SELECT id, user_id, call_session_id, vhrp_session_id, vhrp_thread_id, speed_dial_id, "
          + "voice_agent_id, started_at, ended_at, thread_blob_key, deleted_at, created_at, updated_at "
          + "FROM call_sessions "
          + "WHERE user_id = #{userId} AND deleted_at IS NULL "
          + "<if test='cursorStartedAt != null and cursorId != null'>"
          + "AND (started_at \u0026lt; #{cursorStartedAt} OR (started_at = #{cursorStartedAt} AND id \u0026lt; #{cursorId})) "
          + "</if>"
          + "ORDER BY started_at DESC, id DESC LIMIT #{limit}"
          + "</script>")
  @ResultMap("callSessionRowResultMap")
  List<Row> findActivePageByUserId(
      @Param("userId") Long userId,
      @Param("cursorStartedAt") LocalDateTime cursorStartedAt,
      @Param("cursorId") Long cursorId,
      @Param("limit") int limit);

  @Select(
      "SELECT id, user_id, call_session_id, vhrp_session_id, vhrp_thread_id, speed_dial_id, "
          + "voice_agent_id, started_at, ended_at, thread_blob_key, deleted_at, created_at, updated_at "
          + "FROM call_sessions "
          + "WHERE user_id = #{userId} AND call_session_id = #{callSessionId,typeHandler=app.vagina.server.mapper.type.UuidTypeHandler} AND deleted_at IS NULL")
  @ResultMap("callSessionRowResultMap")
  Optional<Row> findActiveByUserIdAndCallSessionId(
      @Param("userId") Long userId, @Param("callSessionId") UUID callSessionId);

  @Update(
      "UPDATE call_sessions SET deleted_at = #{deletedAt}, updated_at = #{deletedAt} "
          + "WHERE user_id = #{userId} AND call_session_id = #{callSessionId,typeHandler=app.vagina.server.mapper.type.UuidTypeHandler} AND deleted_at IS NULL")
  int softDeleteActiveByUserIdAndCallSessionId(
      @Param("userId") Long userId,
      @Param("callSessionId") UUID callSessionId,
      @Param("deletedAt") LocalDateTime deletedAt);

  @Update(
      "<script>"
          + "UPDATE call_sessions SET deleted_at = #{deletedAt}, updated_at = #{deletedAt} "
          + "WHERE user_id = #{userId} AND deleted_at IS NULL AND call_session_id IN "
          + "<foreach item='callSessionId' collection='callSessionIds' open='(' separator=',' close=')'>"
          + "#{callSessionId,typeHandler=app.vagina.server.mapper.type.UuidTypeHandler}"
          + "</foreach>"
          + "</script>")
  int softDeleteActiveByUserIdAndCallSessionIds(
      @Param("userId") Long userId,
      @Param("callSessionIds") List<UUID> callSessionIds,
      @Param("deletedAt") LocalDateTime deletedAt);

  final class Row {
    private Long id;
    private Long userId;
    private UUID callSessionId;
    private String vhrpSessionId;
    private String vhrpThreadId;
    private String speedDialId;
    private String voiceAgentId;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private String threadBlobKey;
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

    public String getThreadBlobKey() {
      return threadBlobKey;
    }

    public void setThreadBlobKey(String threadBlobKey) {
      this.threadBlobKey = threadBlobKey;
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
}
