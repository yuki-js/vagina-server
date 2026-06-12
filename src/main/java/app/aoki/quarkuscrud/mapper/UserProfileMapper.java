package app.aoki.quarkuscrud.mapper;

import app.aoki.quarkuscrud.entity.UserProfile;
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
public interface UserProfileMapper {

  @Insert("INSERT INTO user_profiles (user_id, profile_data, usermeta, sysmeta, created_at, updated_at) VALUES (#{userId}, #{profileData}::jsonb, #{usermeta}::jsonb, #{sysmeta}::jsonb, #{createdAt}, #{updatedAt})")
  @Options(useGeneratedKeys = true, keyProperty = "id")
  void insert(UserProfile userProfile);

  @Select("SELECT id, user_id, profile_data::text as profile_data, usermeta::text as usermeta, sysmeta::text as sysmeta, created_at, updated_at FROM user_profiles WHERE id = #{id}")
  @Results(
      id = "userProfileResultMap",
      value = {
        @Result(property = "id", column = "id", id = true),
        @Result(property = "userId", column = "user_id"),
        @Result(property = "profileData", column = "profile_data"),
        @Result(property = "usermeta", column = "usermeta"),
        @Result(property = "sysmeta", column = "sysmeta"),
        @Result(property = "createdAt", column = "created_at"),
        @Result(property = "updatedAt", column = "updated_at")
      })
  Optional<UserProfile> findById(@Param("id") Long id);

  @Select("SELECT id, user_id, profile_data::text as profile_data, usermeta::text as usermeta, sysmeta::text as sysmeta, created_at, updated_at FROM user_profiles WHERE user_id = #{userId} ORDER BY created_at DESC")
  @ResultMap("userProfileResultMap")
  List<UserProfile> findByUserId(@Param("userId") Long userId);

  @Select("SELECT id, user_id, profile_data::text as profile_data, usermeta::text as usermeta, sysmeta::text as sysmeta, created_at, updated_at FROM user_profiles WHERE user_id = #{userId} ORDER BY created_at DESC LIMIT 1")
  @ResultMap("userProfileResultMap")
  Optional<UserProfile> findLatestByUserId(@Param("userId") Long userId);

  @Update("UPDATE user_profiles SET usermeta = #{usermeta}::jsonb, sysmeta = #{sysmeta}::jsonb, updated_at = #{updatedAt} WHERE id = #{id}")
  void updateRevisionMeta(UserProfile userProfile);

  @Delete("DELETE FROM user_profiles WHERE id = #{id}")
  void deleteById(@Param("id") Long id);
}
