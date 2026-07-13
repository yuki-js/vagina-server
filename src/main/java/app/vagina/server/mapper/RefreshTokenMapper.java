package app.vagina.server.mapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
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
public interface RefreshTokenMapper {

  @Insert(
      "INSERT INTO refresh_tokens (user_id, token_hash, token_family, issued_at, expires_at, rotated_at, revoked_at, "
          + "last_used_at, created_at, updated_at) VALUES (#{userId}, #{tokenHash}, #{tokenFamily}, "
          + "#{issuedAt}, #{expiresAt}, #{rotatedAt}, #{revokedAt}, #{lastUsedAt}, #{createdAt}, #{updatedAt})")
  @Options(useGeneratedKeys = true, keyProperty = "id")
  void insert(Row row);

  @Select(
      "SELECT id, user_id, token_hash, token_family, issued_at, expires_at, rotated_at, revoked_at, "
          + "last_used_at, created_at, updated_at "
          + "FROM refresh_tokens WHERE id = #{id}")
  @Results(
      id = "refreshTokenRowResultMap",
      value = {
        @Result(property = "id", column = "id", id = true),
        @Result(property = "userId", column = "user_id"),
        @Result(property = "tokenHash", column = "token_hash"),
        @Result(property = "tokenFamily", column = "token_family"),
        @Result(property = "issuedAt", column = "issued_at"),
        @Result(property = "expiresAt", column = "expires_at"),
        @Result(property = "rotatedAt", column = "rotated_at"),
        @Result(property = "revokedAt", column = "revoked_at"),
        @Result(property = "lastUsedAt", column = "last_used_at"),
        @Result(property = "createdAt", column = "created_at"),
        @Result(property = "updatedAt", column = "updated_at")
      })
  Optional<Row> findById(@Param("id") Long id);

  @Select(
      "SELECT id, user_id, token_hash, token_family, issued_at, expires_at, rotated_at, revoked_at, "
          + "last_used_at, created_at, updated_at "
          + "FROM refresh_tokens WHERE token_hash = #{tokenHash}")
  @ResultMap("refreshTokenRowResultMap")
  Optional<Row> findByTokenHash(@Param("tokenHash") String tokenHash);

  @Select(
      "SELECT id, user_id, token_hash, token_family, issued_at, expires_at, rotated_at, revoked_at, "
          + "last_used_at, created_at, updated_at "
          + "FROM refresh_tokens WHERE user_id = #{userId} ORDER BY created_at DESC")
  @ResultMap("refreshTokenRowResultMap")
  List<Row> findByUserId(@Param("userId") Long userId);

  @Select(
      "SELECT id, user_id, token_hash, token_family, issued_at, expires_at, rotated_at, revoked_at, "
          + "last_used_at, created_at, updated_at "
          + "FROM refresh_tokens WHERE token_family = #{tokenFamily} ORDER BY created_at DESC")
  @ResultMap("refreshTokenRowResultMap")
  List<Row> findByTokenFamily(@Param("tokenFamily") String tokenFamily);

  @Update(
      "UPDATE refresh_tokens SET user_id = #{userId}, token_hash = #{tokenHash}, token_family = #{tokenFamily}, "
          + "issued_at = #{issuedAt}, expires_at = #{expiresAt}, rotated_at = #{rotatedAt}, revoked_at = #{revokedAt}, "
          + "last_used_at = #{lastUsedAt}, updated_at = #{updatedAt} WHERE id = #{id}")
  void update(Row row);

  @Update(
      "UPDATE refresh_tokens SET rotated_at = #{rotatedAt}, last_used_at = #{lastUsedAt}, updated_at = #{updatedAt} "
          + "WHERE id = #{id} AND rotated_at IS NULL AND revoked_at IS NULL")
  int markRotatedIfActive(
      @Param("id") Long id,
      @Param("rotatedAt") LocalDateTime rotatedAt,
      @Param("lastUsedAt") LocalDateTime lastUsedAt,
      @Param("updatedAt") LocalDateTime updatedAt);

  final class Row {
    private Long id;
    private Long userId;
    private String tokenHash;
    private String tokenFamily;
    private LocalDateTime issuedAt;
    private LocalDateTime expiresAt;
    private LocalDateTime rotatedAt;
    private LocalDateTime revokedAt;
    private LocalDateTime lastUsedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }
    public String getTokenFamily() { return tokenFamily; }
    public void setTokenFamily(String tokenFamily) { this.tokenFamily = tokenFamily; }
    public LocalDateTime getIssuedAt() { return issuedAt; }
    public void setIssuedAt(LocalDateTime issuedAt) { this.issuedAt = issuedAt; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    public LocalDateTime getRotatedAt() { return rotatedAt; }
    public void setRotatedAt(LocalDateTime rotatedAt) { this.rotatedAt = rotatedAt; }
    public LocalDateTime getRevokedAt() { return revokedAt; }
    public void setRevokedAt(LocalDateTime revokedAt) { this.revokedAt = revokedAt; }
    public LocalDateTime getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(LocalDateTime lastUsedAt) { this.lastUsedAt = lastUsedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
  }
}
