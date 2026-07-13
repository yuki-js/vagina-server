package app.vagina.server.mapper;

import app.vagina.server.entity.AuthMethod;
import app.vagina.server.mapper.type.AuthMethodTypeHandler;
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
public interface AuthnProviderMapper {

  @Insert(
      "INSERT INTO authn_providers (user_id, auth_method, provider_key, auth_identifier, external_subject, "
          + "provider_login, display_name, avatar_url, email, email_verified, created_at, updated_at) "
          + "VALUES (#{userId}, #{authMethod, typeHandler=app.vagina.server.mapper.type.AuthMethodTypeHandler}, "
          + "#{providerKey}, #{authIdentifier}, #{externalSubject}, #{providerLogin}, #{displayName}, #{avatarUrl}, #{email}, #{emailVerified}, "
          + "#{createdAt}, #{updatedAt})")
  @Options(useGeneratedKeys = true, keyProperty = "id")
  void insert(Row row);

  @Select(
      "SELECT id, user_id, auth_method, provider_key, auth_identifier, external_subject, provider_login, display_name, "
          + "avatar_url, email, email_verified, "
          + "created_at, updated_at FROM authn_providers WHERE id = #{id}")
  @Results(
      id = "authnProviderRowResultMap",
      value = {
        @Result(property = "id", column = "id", id = true),
        @Result(property = "userId", column = "user_id"),
        @Result(
            property = "authMethod",
            column = "auth_method",
            javaType = AuthMethod.class,
            typeHandler = AuthMethodTypeHandler.class),
        @Result(property = "providerKey", column = "provider_key"),
        @Result(property = "authIdentifier", column = "auth_identifier"),
        @Result(property = "externalSubject", column = "external_subject"),
        @Result(property = "providerLogin", column = "provider_login"),
        @Result(property = "displayName", column = "display_name"),
        @Result(property = "avatarUrl", column = "avatar_url"),
        @Result(property = "email", column = "email"),
        @Result(property = "emailVerified", column = "email_verified"),
        @Result(property = "createdAt", column = "created_at"),
        @Result(property = "updatedAt", column = "updated_at")
      })
  Optional<Row> findById(@Param("id") Long id);

  @Select(
      "SELECT id, user_id, auth_method, provider_key, auth_identifier, external_subject, provider_login, display_name, "
          + "avatar_url, email, email_verified, "
          + "created_at, updated_at FROM authn_providers WHERE user_id = #{userId} ORDER BY created_at ASC")
  @ResultMap("authnProviderRowResultMap")
  List<Row> findByUserId(@Param("userId") Long userId);

  @Select(
      "SELECT id, user_id, auth_method, provider_key, auth_identifier, external_subject, provider_login, display_name, "
          + "avatar_url, email, email_verified, "
          + "created_at, updated_at FROM authn_providers WHERE provider_key = #{providerKey} AND external_subject = #{externalSubject}")
  @ResultMap("authnProviderRowResultMap")
  Optional<Row> findByProviderKeyAndExternalSubject(
      @Param("providerKey") String providerKey, @Param("externalSubject") String externalSubject);

  @Update(
      "UPDATE authn_providers SET user_id = #{userId}, auth_method = "
          + "#{authMethod, typeHandler=app.vagina.server.mapper.type.AuthMethodTypeHandler}, provider_key = #{providerKey}, "
          + "auth_identifier = #{authIdentifier}, external_subject = #{externalSubject}, provider_login = #{providerLogin}, "
          + "display_name = #{displayName}, avatar_url = #{avatarUrl}, email = #{email}, email_verified = #{emailVerified}, "
          + "updated_at = #{updatedAt} WHERE id = #{id}")
  void update(Row row);

  final class Row {
    private Long id;
    private Long userId;
    private AuthMethod authMethod;
    private String providerKey;
    private String authIdentifier;
    private String externalSubject;
    private String providerLogin;
    private String displayName;
    private String avatarUrl;
    private String email;
    private Boolean emailVerified;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public AuthMethod getAuthMethod() { return authMethod; }
    public void setAuthMethod(AuthMethod authMethod) { this.authMethod = authMethod; }
    public String getProviderKey() { return providerKey; }
    public void setProviderKey(String providerKey) { this.providerKey = providerKey; }
    public String getAuthIdentifier() { return authIdentifier; }
    public void setAuthIdentifier(String authIdentifier) { this.authIdentifier = authIdentifier; }
    public String getExternalSubject() { return externalSubject; }
    public void setExternalSubject(String externalSubject) { this.externalSubject = externalSubject; }
    public String getProviderLogin() { return providerLogin; }
    public void setProviderLogin(String providerLogin) { this.providerLogin = providerLogin; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getAvatarUrl() { return avatarUrl; }
    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public Boolean getEmailVerified() { return emailVerified; }
    public void setEmailVerified(Boolean emailVerified) { this.emailVerified = emailVerified; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
  }
}
