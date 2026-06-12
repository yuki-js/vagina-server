package app.aoki.quarkuscrud.mapper;

import app.aoki.quarkuscrud.entity.EventInvitationCode;
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
public interface EventInvitationCodeMapper {

  @Insert("INSERT INTO event_invitation_codes (event_id, invitation_code, usermeta, sysmeta, created_at, updated_at) VALUES (#{eventId}, #{invitationCode}, #{usermeta}::jsonb, #{sysmeta}::jsonb, #{createdAt}, #{updatedAt})")
  @Options(useGeneratedKeys = true, keyProperty = "id")
  void insert(EventInvitationCode eventInvitationCode);

  @Insert("INSERT INTO event_invitation_codes (event_id, invitation_code, usermeta, sysmeta, created_at, updated_at) SELECT #{eventId}, #{invitationCode}, #{usermeta}::jsonb, #{sysmeta}::jsonb, #{createdAt}, #{updatedAt} WHERE NOT EXISTS (SELECT 1 FROM event_invitation_codes c JOIN events e ON e.id = c.event_id WHERE c.invitation_code = #{invitationCode} AND LOWER(e.status) NOT IN ('expired', 'deleted'))")
  int insertIfInvitationCodeAvailable(EventInvitationCode eventInvitationCode);

  @Select("SELECT id, event_id, invitation_code, usermeta::text as usermeta, sysmeta::text as sysmeta, created_at, updated_at FROM event_invitation_codes WHERE id = #{id}")
  @Results(
      id = "eventInvitationCodeResultMap",
      value = {
        @Result(property = "id", column = "id", id = true),
        @Result(property = "eventId", column = "event_id"),
        @Result(property = "invitationCode", column = "invitation_code"),
        @Result(property = "usermeta", column = "usermeta"),
        @Result(property = "sysmeta", column = "sysmeta"),
        @Result(property = "createdAt", column = "created_at"),
        @Result(property = "updatedAt", column = "updated_at")
      })
  Optional<EventInvitationCode> findById(@Param("id") Long id);

  @Select("SELECT id, event_id, invitation_code, usermeta::text as usermeta, sysmeta::text as sysmeta, created_at, updated_at FROM event_invitation_codes WHERE event_id = #{eventId}")
  @ResultMap("eventInvitationCodeResultMap")
  List<EventInvitationCode> findByEventId(@Param("eventId") Long eventId);

  @Select("SELECT id, event_id, invitation_code, usermeta::text as usermeta, sysmeta::text as sysmeta, created_at, updated_at FROM event_invitation_codes WHERE invitation_code = #{invitationCode}")
  @ResultMap("eventInvitationCodeResultMap")
  Optional<EventInvitationCode> findByInvitationCode(
      @Param("invitationCode") String invitationCode);

  @Update("UPDATE event_invitation_codes SET event_id = #{eventId}, invitation_code = #{invitationCode}, usermeta = #{usermeta}::jsonb, sysmeta = #{sysmeta}::jsonb, updated_at = #{updatedAt} WHERE id = #{id}")
  void update(EventInvitationCode eventInvitationCode);

  @Delete("DELETE FROM event_invitation_codes WHERE id = #{id}")
  void deleteById(@Param("id") Long id);
}
