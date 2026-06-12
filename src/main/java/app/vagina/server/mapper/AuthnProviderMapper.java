package app.vagina.server.mapper;

import app.vagina.server.entity.AuthMethod;
import app.vagina.server.entity.AuthnProvider;
import app.vagina.server.mapper.type.AuthMethodTypeHandler;
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
          + "provider_login, display_name, avatar_url, email, email_verified, usermeta, sysmeta, created_at, updated_at) "
          + "VALUES (#{userId}, #{authMethod, typeHandler=app.vagina.server.mapper.type.AuthMethodTypeHandler}, "
          + "#{providerKey}, #{authIdentifier}, #{externalSubject}, #{providerLogin}, #{displayName}, #{avatarUrl}, #{email}, #{emailVerified}, "
          + "#{usermeta}::jsonb, #{sysmeta}::jsonb, #{createdAt}, #{updatedAt})")
  @Options(useGeneratedKeys = true, keyProperty = "id")
  void insert(AuthnProvider authnProvider);

  @Select(
      "SELECT id, user_id, auth_method, provider_key, auth_identifier, external_subject, provider_login, display_name, "
          + "avatar_url, email, email_verified, usermeta::text as usermeta, sysmeta::text as sysmeta, "
          + "created_at, updated_at FROM authn_providers WHERE id = #{id}")
  @Results(
      id = "authnProviderResultMap",
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
        @Result(property = "usermeta", column = "usermeta"),
        @Result(property = "sysmeta", column = "sysmeta"),
        @Result(property = "createdAt", column = "created_at"),
        @Result(property = "updatedAt", column = "updated_at")
      })
  Optional<AuthnProvider> findById(@Param("id") Long id);

  @Select(
      "SELECT id, user_id, auth_method, provider_key, auth_identifier, external_subject, provider_login, display_name, "
          + "avatar_url, email, email_verified, usermeta::text as usermeta, sysmeta::text as sysmeta, "
          + "created_at, updated_at FROM authn_providers WHERE user_id = #{userId} ORDER BY created_at ASC")
  @ResultMap("authnProviderResultMap")
  List<AuthnProvider> findByUserId(@Param("userId") Long userId);

  @Select(
      "SELECT id, user_id, auth_method, provider_key, auth_identifier, external_subject, provider_login, display_name, "
          + "avatar_url, email, email_verified, usermeta::text as usermeta, sysmeta::text as sysmeta, "
          + "created_at, updated_at FROM authn_providers WHERE provider_key = #{providerKey} AND external_subject = #{externalSubject}")
  @ResultMap("authnProviderResultMap")
  Optional<AuthnProvider> findByProviderKeyAndExternalSubject(
      @Param("providerKey") String providerKey, @Param("externalSubject") String externalSubject);

  @Update(
      "UPDATE authn_providers SET user_id = #{userId}, auth_method = "
          + "#{authMethod, typeHandler=app.vagina.server.mapper.type.AuthMethodTypeHandler}, provider_key = #{providerKey}, "
          + "auth_identifier = #{authIdentifier}, external_subject = #{externalSubject}, provider_login = #{providerLogin}, "
          + "display_name = #{displayName}, avatar_url = #{avatarUrl}, email = #{email}, email_verified = #{emailVerified}, "
          + "usermeta = #{usermeta}::jsonb, sysmeta = #{sysmeta}::jsonb, updated_at = #{updatedAt} WHERE id = #{id}")
  void update(AuthnProvider authnProvider);
}
