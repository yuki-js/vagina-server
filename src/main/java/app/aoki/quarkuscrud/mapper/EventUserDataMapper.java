package app.aoki.quarkuscrud.mapper;

import app.aoki.quarkuscrud.entity.EventUserData;
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
public interface EventUserDataMapper {

  @Insert(
      "INSERT INTO event_user_data (event_id, user_id, user_data, usermeta, sysmeta, created_at, updated_at) "
          + "VALUES (#{eventId}, #{userId}, #{userData}::jsonb, #{usermeta}::jsonb, #{sysmeta}::jsonb, #{createdAt}, #{updatedAt})")
  @Options(useGeneratedKeys = true, keyProperty = "id")
  void insert(EventUserData eventUserData);

  @Select(
      "SELECT id, event_id, user_id, user_data::text as user_data, "
          + "usermeta::text as usermeta, sysmeta::text as sysmeta, created_at, updated_at "
          + "FROM event_user_data WHERE id = #{id}")
  @Results(
      id = "eventUserDataResultMap",
      value = {
        @Result(property = "id", column = "id", id = true),
        @Result(property = "eventId", column = "event_id"),
        @Result(property = "userId", column = "user_id"),
        @Result(property = "userData", column = "user_data"),
        @Result(property = "usermeta", column = "usermeta"),
        @Result(property = "sysmeta", column = "sysmeta"),
        @Result(property = "createdAt", column = "created_at"),
        @Result(property = "updatedAt", column = "updated_at")
      })
  Optional<EventUserData> findById(@Param("id") Long id);

  @Select(
      "SELECT id, event_id, user_id, user_data::text as user_data, "
          + "usermeta::text as usermeta, sysmeta::text as sysmeta, created_at, updated_at "
          + "FROM event_user_data WHERE event_id = #{eventId} AND user_id = #{userId} "
          + "ORDER BY created_at DESC")
  @ResultMap("eventUserDataResultMap")
  List<EventUserData> findByEventIdAndUserId(
      @Param("eventId") Long eventId, @Param("userId") Long userId);

  @Select(
      "SELECT id, event_id, user_id, user_data::text as user_data, "
          + "usermeta::text as usermeta, sysmeta::text as sysmeta, created_at, updated_at "
          + "FROM event_user_data WHERE event_id = #{eventId} AND user_id = #{userId} "
          + "ORDER BY created_at DESC LIMIT 1")
  @ResultMap("eventUserDataResultMap")
  Optional<EventUserData> findLatestByEventIdAndUserId(
      @Param("eventId") Long eventId, @Param("userId") Long userId);

  @Select(
      "SELECT id, event_id, user_id, user_data::text as user_data, "
          + "usermeta::text as usermeta, sysmeta::text as sysmeta, created_at, updated_at "
          + "FROM event_user_data WHERE event_id = #{eventId} "
          + "ORDER BY created_at DESC")
  @ResultMap("eventUserDataResultMap")
  List<EventUserData> findByEventId(@Param("eventId") Long eventId);

  @Update(
      "UPDATE event_user_data SET usermeta = #{usermeta}::jsonb, sysmeta = #{sysmeta}::jsonb, updated_at = #{updatedAt} WHERE id = #{id}")
  void updateRevisionMeta(EventUserData eventUserData);

  @Delete("DELETE FROM event_user_data WHERE id = #{id}")
  void deleteById(@Param("id") Long id);
}
