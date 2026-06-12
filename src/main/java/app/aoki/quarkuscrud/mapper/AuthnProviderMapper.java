package app.aoki.quarkuscrud.mapper;

import app.aoki.quarkuscrud.entity.AuthMethod;
import app.aoki.quarkuscrud.entity.AuthnProvider;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Delete;
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

  @Insert("INSERT INTO authn_providers (user_id, auth_method, auth_identifier, external_subject, usermeta, sysmeta, created_at, updated_at) VALUES (#{userId}, #{authMethod, typeHandler=app.aoki.quarkuscrud.mapper.AuthMethodTypeHandler}, #{authIdentifier}, #{externalSubject}, #{usermeta}::jsonb, #{sysmeta}::jsonb, #{createdAt}, #{updatedAt})")
  @Options(useGeneratedKeys = true, keyProperty = "id")
  void insert(AuthnProvider authnProvider);

  @Select("SELECT id, user_id, auth_method, auth_identifier, external_subject, usermeta::text as usermeta, sysmeta::text as sysmeta, created_at, updated_at FROM authn_providers WHERE id = #{id}")
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
        @Result(property = "authIdentifier", column = "auth_identifier"),
        @Result(property = "externalSubject", column = "external_subject"),
        @Result(property = "usermeta", column = "usermeta"),
        @Result(property = "sysmeta", column = "sysmeta"),
        @Result(property = "createdAt", column = "created_at"),
        @Result(property = "updatedAt", column = "updated_at")
      })
  Optional<AuthnProvider> findById(@Param("id") Long id);

  @Select("SELECT id, user_id, auth_method, auth_identifier, external_subject, usermeta::text as usermeta, sysmeta::text as sysmeta, created_at, updated_at FROM authn_providers WHERE user_id = #{userId}")
  @ResultMap("authnProviderResultMap")
  List<AuthnProvider> findByUserId(@Param("userId") Long userId);

  @Select("SELECT id, user_id, auth_method, auth_identifier, external_subject, usermeta::text as usermeta, sysmeta::text as sysmeta, created_at, updated_at FROM authn_providers WHERE auth_identifier = #{authIdentifier}")
  @ResultMap("authnProviderResultMap")
  Optional<AuthnProvider> findByAuthIdentifier(@Param("authIdentifier") String authIdentifier);

  @Select("SELECT id, user_id, auth_method, auth_identifier, external_subject, usermeta::text as usermeta, sysmeta::text as sysmeta, created_at, updated_at FROM authn_providers WHERE auth_method = #{authMethod, typeHandler=app.aoki.quarkuscrud.mapper.AuthMethodTypeHandler} AND external_subject = #{externalSubject}")
  @ResultMap("authnProviderResultMap")
  Optional<AuthnProvider> findByMethodAndExternalSubject(
      @Param("authMethod") AuthMethod authMethod, @Param("externalSubject") String externalSubject);

  @Update("UPDATE authn_providers SET user_id = #{userId}, auth_method = #{authMethod, typeHandler=app.aoki.quarkuscrud.mapper.AuthMethodTypeHandler}, auth_identifier = #{authIdentifier}, external_subject = #{externalSubject}, usermeta = #{usermeta}::jsonb, sysmeta = #{sysmeta}::jsonb, updated_at = #{updatedAt} WHERE id = #{id}")
  void update(AuthnProvider authnProvider);

  @Delete("DELETE FROM authn_providers WHERE id = #{id}")
  void deleteById(@Param("id") Long id);
}
