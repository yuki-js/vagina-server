package app.aoki.quarkuscrud.mapper;

import app.aoki.quarkuscrud.entity.AccountLifecycle;
import app.aoki.quarkuscrud.entity.User;
import app.aoki.quarkuscrud.mapper.type.AccountLifecycleTypeHandler;
import java.util.Optional;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UserMapper {

  @Insert(
      "INSERT INTO users (account_lifecycle, usermeta, sysmeta, created_at, updated_at) "
          + "VALUES (#{accountLifecycle, typeHandler=app.aoki.quarkuscrud.mapper.type.AccountLifecycleTypeHandler}, #{usermeta}::jsonb, #{sysmeta}::jsonb, #{createdAt}, #{updatedAt})")
  @Options(useGeneratedKeys = true, keyProperty = "id")
  void insert(User user);

  @Select(
      "SELECT id, account_lifecycle, usermeta::text as usermeta, sysmeta::text as sysmeta, created_at, updated_at FROM users WHERE id = #{id}")
  @Results(
      id = "userResultMap",
      value = {
        @Result(property = "id", column = "id", id = true),
        @Result(
            property = "accountLifecycle",
            column = "account_lifecycle",
            javaType = AccountLifecycle.class,
            typeHandler = AccountLifecycleTypeHandler.class),
        @Result(property = "usermeta", column = "usermeta"),
        @Result(property = "sysmeta", column = "sysmeta"),
        @Result(property = "createdAt", column = "created_at"),
        @Result(property = "updatedAt", column = "updated_at")
      })
  Optional<User> findById(@Param("id") Long id);

  @Update(
      "UPDATE users SET "
          + "account_lifecycle = "
          + "#{accountLifecycle, typeHandler=app.aoki.quarkuscrud.mapper.type.AccountLifecycleTypeHandler}, "
          + "usermeta = #{usermeta}::jsonb, sysmeta = #{sysmeta}::jsonb, updated_at = #{updatedAt} WHERE id = #{id}")
  void update(User user);

  @Delete("DELETE FROM users WHERE id = #{id}")
  void deleteById(@Param("id") Long id);
}
