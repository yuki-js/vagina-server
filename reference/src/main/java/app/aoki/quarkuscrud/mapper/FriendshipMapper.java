package app.aoki.quarkuscrud.mapper;

import app.aoki.quarkuscrud.entity.Friendship;
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
public interface FriendshipMapper {

  @Insert("INSERT INTO friendships (sender_id, recipient_id, usermeta, sysmeta, created_at, updated_at) VALUES (#{senderId}, #{recipientId}, #{usermeta}::jsonb, #{sysmeta}::jsonb, #{createdAt}, #{updatedAt})")
  @Options(useGeneratedKeys = true, keyProperty = "id")
  void insert(Friendship friendship);

  @Select("SELECT id, sender_id, recipient_id, usermeta::text as usermeta, sysmeta::text as sysmeta, created_at, updated_at FROM friendships WHERE id = #{id}")
  @Results(
      id = "friendshipResultMap",
      value = {
        @Result(property = "id", column = "id", id = true),
        @Result(property = "senderId", column = "sender_id"),
        @Result(property = "recipientId", column = "recipient_id"),
        @Result(property = "usermeta", column = "usermeta"),
        @Result(property = "sysmeta", column = "sysmeta"),
        @Result(property = "createdAt", column = "created_at"),
        @Result(property = "updatedAt", column = "updated_at")
      })
  Optional<Friendship> findById(@Param("id") Long id);

  @Select("SELECT id, sender_id, recipient_id, usermeta::text as usermeta, sysmeta::text as sysmeta, created_at, updated_at FROM friendships WHERE sender_id = #{senderId}")
  @ResultMap("friendshipResultMap")
  List<Friendship> findBySenderId(@Param("senderId") Long senderId);

  @Select("SELECT id, sender_id, recipient_id, usermeta::text as usermeta, sysmeta::text as sysmeta, created_at, updated_at FROM friendships WHERE recipient_id = #{recipientId}")
  @ResultMap("friendshipResultMap")
  List<Friendship> findByRecipientId(@Param("recipientId") Long recipientId);

  @Select("SELECT id, sender_id, recipient_id, usermeta::text as usermeta, sysmeta::text as sysmeta, created_at, updated_at FROM friendships WHERE sender_id = #{senderId} AND recipient_id = #{recipientId}")
  @ResultMap("friendshipResultMap")
  Optional<Friendship> findBySenderAndRecipient(
      @Param("senderId") Long senderId, @Param("recipientId") Long recipientId);

  @Select("SELECT id, sender_id, recipient_id, usermeta::text as usermeta, sysmeta::text as sysmeta, created_at, updated_at FROM friendships WHERE (sender_id = #{userId1} AND recipient_id = #{userId2}) OR (sender_id = #{userId2} AND recipient_id = #{userId1}) LIMIT 1")
  @ResultMap("friendshipResultMap")
  Optional<Friendship> findByParticipants(
      @Param("userId1") Long userId1, @Param("userId2") Long userId2);

  @Select("SELECT EXISTS(SELECT 1 FROM friendships WHERE (sender_id = #{userId1} AND recipient_id = #{userId2}) OR (sender_id = #{userId2} AND recipient_id = #{userId1}))")
  boolean existsBetweenUsers(@Param("userId1") Long userId1, @Param("userId2") Long userId2);

  @Update("UPDATE friendships SET usermeta = #{usermeta}::jsonb, sysmeta = #{sysmeta}::jsonb, updated_at = #{updatedAt} WHERE id = #{id}")
  void updateMeta(Friendship friendship);

  @Delete("DELETE FROM friendships WHERE id = #{id}")
  void deleteById(@Param("id") Long id);

  @Delete("DELETE FROM friendships WHERE sender_id = #{senderId} AND recipient_id = #{recipientId}")
  void deleteBySenderAndRecipient(
      @Param("senderId") Long senderId, @Param("recipientId") Long recipientId);
}
