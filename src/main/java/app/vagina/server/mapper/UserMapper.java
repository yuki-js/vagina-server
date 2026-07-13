package app.vagina.server.mapper;

import app.vagina.server.entity.AccountLifecycle;
import app.vagina.server.mapper.type.AccountLifecycleTypeHandler;
import java.time.LocalDateTime;
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
      "INSERT INTO users (account_lifecycle, created_at, updated_at) "
          + "VALUES (#{accountLifecycle, typeHandler=app.vagina.server.mapper.type.AccountLifecycleTypeHandler}, "
          + "#{createdAt}, #{updatedAt})")
  @Options(useGeneratedKeys = true, keyProperty = "id")
  void insert(Row row);

  @Select(
      "SELECT id, account_lifecycle, "
          + "created_at, updated_at FROM users WHERE id = #{id}")
  @Results(
      id = "userRowResultMap",
      value = {
        @Result(property = "id", column = "id", id = true),
        @Result(
            property = "accountLifecycle",
            column = "account_lifecycle",
            javaType = AccountLifecycle.class,
            typeHandler = AccountLifecycleTypeHandler.class),
        @Result(property = "createdAt", column = "created_at"),
        @Result(property = "updatedAt", column = "updated_at")
      })
  Optional<Row> findById(@Param("id") Long id);

  @Update(
      "UPDATE users SET account_lifecycle = "
          + "#{accountLifecycle, typeHandler=app.vagina.server.mapper.type.AccountLifecycleTypeHandler}, "
          + "updated_at = #{updatedAt} "
          + "WHERE id = #{id}")
  void update(Row row);

  @Delete("DELETE FROM users WHERE id = #{id}")
  void deleteById(@Param("id") Long id);

  final class Row {
    private Long id;
    private AccountLifecycle accountLifecycle;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() {
      return id;
    }

    public void setId(Long id) {
      this.id = id;
    }

    public AccountLifecycle getAccountLifecycle() {
      return accountLifecycle;
    }

    public void setAccountLifecycle(AccountLifecycle accountLifecycle) {
      this.accountLifecycle = accountLifecycle;
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
