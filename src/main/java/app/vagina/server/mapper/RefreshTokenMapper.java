package app.vagina.server.mapper;

import app.vagina.server.entity.RefreshToken;
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
          + "last_used_at, sysmeta, created_at, updated_at) VALUES (#{userId}, #{tokenHash}, #{tokenFamily}, "
          + "#{issuedAt}, #{expiresAt}, #{rotatedAt}, #{revokedAt}, #{lastUsedAt}, #{sysmeta}::jsonb, #{createdAt}, #{updatedAt})")
  @Options(useGeneratedKeys = true, keyProperty = "id")
  void insert(RefreshToken refreshToken);

  @Select(
      "SELECT id, user_id, token_hash, token_family, issued_at, expires_at, rotated_at, revoked_at, "
          + "last_used_at, sysmeta::text as sysmeta, created_at, updated_at "
          + "FROM refresh_tokens WHERE id = #{id}")
  @Results(
      id = "refreshTokenResultMap",
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
        @Result(property = "sysmeta", column = "sysmeta"),
        @Result(property = "createdAt", column = "created_at"),
        @Result(property = "updatedAt", column = "updated_at")
      })
  Optional<RefreshToken> findById(@Param("id") Long id);

  @Select(
      "SELECT id, user_id, token_hash, token_family, issued_at, expires_at, rotated_at, revoked_at, "
          + "last_used_at, sysmeta::text as sysmeta, created_at, updated_at "
          + "FROM refresh_tokens WHERE token_hash = #{tokenHash}")
  @ResultMap("refreshTokenResultMap")
  Optional<RefreshToken> findByTokenHash(@Param("tokenHash") String tokenHash);

  @Select(
      "SELECT id, user_id, token_hash, token_family, issued_at, expires_at, rotated_at, revoked_at, "
          + "last_used_at, sysmeta::text as sysmeta, created_at, updated_at "
          + "FROM refresh_tokens WHERE user_id = #{userId} ORDER BY created_at DESC")
  @ResultMap("refreshTokenResultMap")
  List<RefreshToken> findByUserId(@Param("userId") Long userId);

  @Select(
      "SELECT id, user_id, token_hash, token_family, issued_at, expires_at, rotated_at, revoked_at, "
          + "last_used_at, sysmeta::text as sysmeta, created_at, updated_at "
          + "FROM refresh_tokens WHERE token_family = #{tokenFamily} ORDER BY created_at DESC")
  @ResultMap("refreshTokenResultMap")
  List<RefreshToken> findByTokenFamily(@Param("tokenFamily") String tokenFamily);

  @Update(
      "UPDATE refresh_tokens SET user_id = #{userId}, token_hash = #{tokenHash}, token_family = #{tokenFamily}, "
          + "issued_at = #{issuedAt}, expires_at = #{expiresAt}, rotated_at = #{rotatedAt}, revoked_at = #{revokedAt}, "
          + "last_used_at = #{lastUsedAt}, sysmeta = #{sysmeta}::jsonb, updated_at = #{updatedAt} WHERE id = #{id}")
  void update(RefreshToken refreshToken);
}
