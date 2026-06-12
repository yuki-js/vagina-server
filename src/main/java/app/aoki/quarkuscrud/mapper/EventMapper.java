package app.aoki.quarkuscrud.mapper;

import app.aoki.quarkuscrud.entity.Event;
import app.aoki.quarkuscrud.entity.EventStatus;
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
import org.apache.ibatis.type.EnumTypeHandler;

@Mapper
public interface EventMapper {

  @Update("SET TRANSACTION ISOLATION LEVEL SERIALIZABLE")
  void ensureSerializableIsolationLevel();

  @Insert("INSERT INTO events (initiator_id, status, usermeta, sysmeta, expires_at, created_at, updated_at) VALUES (#{initiatorId}, #{status, typeHandler=org.apache.ibatis.type.EnumTypeHandler}, #{usermeta}::jsonb, #{sysmeta}::jsonb, #{expiresAt}, #{createdAt}, #{updatedAt})")
  @Options(useGeneratedKeys = true, keyProperty = "id")
  void insert(Event event);

  @Select("SELECT id, initiator_id, status, usermeta::text as usermeta, sysmeta::text as sysmeta, expires_at, created_at, updated_at FROM events WHERE id = #{id}")
  @Results(
      id = "eventResultMap",
      value = {
        @Result(property = "id", column = "id", id = true),
        @Result(property = "initiatorId", column = "initiator_id"),
        @Result(
            property = "status",
            column = "status",
            javaType = EventStatus.class,
            typeHandler = EnumTypeHandler.class),
        @Result(property = "usermeta", column = "usermeta"),
        @Result(property = "sysmeta", column = "sysmeta"),
        @Result(property = "expiresAt", column = "expires_at"),
        @Result(property = "createdAt", column = "created_at"),
        @Result(property = "updatedAt", column = "updated_at")
      })
  Optional<Event> findById(@Param("id") Long id);

  @Select("SELECT id, initiator_id, status, usermeta::text as usermeta, sysmeta::text as sysmeta, expires_at, created_at, updated_at FROM events WHERE initiator_id = #{initiatorId}")
  @ResultMap("eventResultMap")
  List<Event> findByInitiatorId(@Param("initiatorId") Long initiatorId);

  @Select("SELECT id, initiator_id, status, usermeta::text as usermeta, sysmeta::text as sysmeta, expires_at, created_at, updated_at FROM events WHERE status = #{status, typeHandler=org.apache.ibatis.type.EnumTypeHandler}")
  @ResultMap("eventResultMap")
  List<Event> findByStatus(@Param("status") EventStatus status);

  @Select("SELECT id, initiator_id, status, usermeta::text as usermeta, sysmeta::text as sysmeta, expires_at, created_at, updated_at FROM events ORDER BY created_at DESC")
  @ResultMap("eventResultMap")
  List<Event> findAll();

  @Update("UPDATE events SET initiator_id = #{initiatorId}, status = #{status, typeHandler=org.apache.ibatis.type.EnumTypeHandler}, usermeta = #{usermeta}::jsonb, sysmeta = #{sysmeta}::jsonb, expires_at = #{expiresAt}, updated_at = #{updatedAt} WHERE id = #{id}")
  void update(Event event);

  @Delete("DELETE FROM events WHERE id = #{id}")
  void deleteById(@Param("id") Long id);
}
