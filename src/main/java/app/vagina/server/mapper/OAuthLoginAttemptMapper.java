package app.vagina.server.mapper;

import app.vagina.server.entity.AuthMethod;
import app.vagina.server.entity.ClientType;
import app.vagina.server.mapper.type.AuthMethodTypeHandler;
import app.vagina.server.mapper.type.ClientTypeHandler;
import java.time.LocalDateTime;
import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface OAuthLoginAttemptMapper {

  @Insert(
      "INSERT INTO oauth_login_attempts (state_hash, auth_method, provider_key, client_type, redirect_uri, code_challenge, "
          + "code_challenge_method, expires_at, consumed_at, sysmeta, created_at, updated_at) VALUES "
          + "(#{stateHash}, #{authMethod, typeHandler=app.vagina.server.mapper.type.AuthMethodTypeHandler}, #{providerKey}, "
          + "#{clientType, typeHandler=app.vagina.server.mapper.type.ClientTypeHandler}, #{redirectUri}, #{codeChallenge}, "
          + "#{codeChallengeMethod}, #{expiresAt}, #{consumedAt}, #{sysmeta}::jsonb, #{createdAt}, #{updatedAt})")
  @Options(useGeneratedKeys = true, keyProperty = "id")
  void insert(Row row);

  @Select(
      "SELECT id, state_hash, auth_method, provider_key, client_type, redirect_uri, code_challenge, code_challenge_method, "
          + "expires_at, consumed_at, sysmeta::text as sysmeta, created_at, updated_at "
          + "FROM oauth_login_attempts WHERE state_hash = #{stateHash}")
  @Results(
      id = "oauthLoginAttemptRowResultMap",
      value = {
        @Result(property = "id", column = "id", id = true),
        @Result(property = "stateHash", column = "state_hash"),
        @Result(
            property = "authMethod",
            column = "auth_method",
            javaType = AuthMethod.class,
            typeHandler = AuthMethodTypeHandler.class),
        @Result(property = "providerKey", column = "provider_key"),
        @Result(
            property = "clientType",
            column = "client_type",
            javaType = ClientType.class,
            typeHandler = ClientTypeHandler.class),
        @Result(property = "redirectUri", column = "redirect_uri"),
        @Result(property = "codeChallenge", column = "code_challenge"),
        @Result(property = "codeChallengeMethod", column = "code_challenge_method"),
        @Result(property = "expiresAt", column = "expires_at"),
        @Result(property = "consumedAt", column = "consumed_at"),
        @Result(property = "sysmeta", column = "sysmeta"),
        @Result(property = "createdAt", column = "created_at"),
        @Result(property = "updatedAt", column = "updated_at")
      })
  Optional<Row> findByStateHash(@Param("stateHash") String stateHash);

  @Update(
      "UPDATE oauth_login_attempts SET auth_method = "
          + "#{authMethod, typeHandler=app.vagina.server.mapper.type.AuthMethodTypeHandler}, provider_key = #{providerKey}, "
          + "client_type = #{clientType, typeHandler=app.vagina.server.mapper.type.ClientTypeHandler}, redirect_uri = #{redirectUri}, "
          + "code_challenge = #{codeChallenge}, code_challenge_method = #{codeChallengeMethod}, expires_at = #{expiresAt}, "
          + "consumed_at = #{consumedAt}, sysmeta = #{sysmeta}::jsonb, updated_at = #{updatedAt} WHERE id = #{id}")
  void update(Row row);

  @Update(
      "UPDATE oauth_login_attempts SET consumed_at = #{consumedAt}, updated_at = #{updatedAt} "
          + "WHERE id = #{id} AND consumed_at IS NULL")
  int markConsumedIfUnused(
      @Param("id") Long id,
      @Param("consumedAt") LocalDateTime consumedAt,
      @Param("updatedAt") LocalDateTime updatedAt);

  final class Row {
    private Long id;
    private String stateHash;
    private AuthMethod authMethod;
    private String providerKey;
    private ClientType clientType;
    private String redirectUri;
    private String codeChallenge;
    private String codeChallengeMethod;
    private LocalDateTime expiresAt;
    private LocalDateTime consumedAt;
    private String sysmeta;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getStateHash() { return stateHash; }
    public void setStateHash(String stateHash) { this.stateHash = stateHash; }
    public AuthMethod getAuthMethod() { return authMethod; }
    public void setAuthMethod(AuthMethod authMethod) { this.authMethod = authMethod; }
    public String getProviderKey() { return providerKey; }
    public void setProviderKey(String providerKey) { this.providerKey = providerKey; }
    public ClientType getClientType() { return clientType; }
    public void setClientType(ClientType clientType) { this.clientType = clientType; }
    public String getRedirectUri() { return redirectUri; }
    public void setRedirectUri(String redirectUri) { this.redirectUri = redirectUri; }
    public String getCodeChallenge() { return codeChallenge; }
    public void setCodeChallenge(String codeChallenge) { this.codeChallenge = codeChallenge; }
    public String getCodeChallengeMethod() { return codeChallengeMethod; }
    public void setCodeChallengeMethod(String codeChallengeMethod) { this.codeChallengeMethod = codeChallengeMethod; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    public LocalDateTime getConsumedAt() { return consumedAt; }
    public void setConsumedAt(LocalDateTime consumedAt) { this.consumedAt = consumedAt; }
    public String getSysmeta() { return sysmeta; }
    public void setSysmeta(String sysmeta) { this.sysmeta = sysmeta; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
  }
}
