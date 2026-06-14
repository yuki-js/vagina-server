package app.vagina.server.mapper;

import app.vagina.server.entity.SpeedDialPreset;
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
public interface SpeedDialMapper {

  @Insert(
      "INSERT INTO speed_dials (user_id, speed_dial_id, name, system_prompt, description, icon_emoji, voice, enabled_tools, created_at, updated_at) "
          + "VALUES (#{userId}, #{speedDialId}, #{name}, #{systemPrompt}, #{description}, #{iconEmoji}, #{voice}, #{enabledTools}::jsonb, #{createdAt}, #{updatedAt})")
  @Options(useGeneratedKeys = true, keyProperty = "id")
  void insert(SpeedDialPreset speedDialPreset);

  @Select(
      "SELECT id, user_id, speed_dial_id, name, system_prompt, description, icon_emoji, voice, enabled_tools::text as enabled_tools, created_at, updated_at "
          + "FROM speed_dials WHERE id = #{id}")
  @Results(
      id = "speedDialPresetResultMap",
      value = {
        @Result(property = "id", column = "id", id = true),
        @Result(property = "userId", column = "user_id"),
        @Result(property = "speedDialId", column = "speed_dial_id"),
        @Result(property = "name", column = "name"),
        @Result(property = "systemPrompt", column = "system_prompt"),
        @Result(property = "description", column = "description"),
        @Result(property = "iconEmoji", column = "icon_emoji"),
        @Result(property = "voice", column = "voice"),
        @Result(property = "enabledTools", column = "enabled_tools"),
        @Result(property = "createdAt", column = "created_at"),
        @Result(property = "updatedAt", column = "updated_at")
      })
  Optional<SpeedDialPreset> findById(@Param("id") Long id);

  @Select(
      "SELECT id, user_id, speed_dial_id, name, system_prompt, description, icon_emoji, voice, enabled_tools::text as enabled_tools, created_at, updated_at "
          + "FROM speed_dials WHERE user_id = #{userId} ORDER BY created_at ASC, id ASC")
  @ResultMap("speedDialPresetResultMap")
  List<SpeedDialPreset> findByUserId(@Param("userId") Long userId);

  @Select(
      "SELECT id, user_id, speed_dial_id, name, system_prompt, description, icon_emoji, voice, enabled_tools::text as enabled_tools, created_at, updated_at "
          + "FROM speed_dials WHERE user_id = #{userId} AND speed_dial_id = #{speedDialId}")
  @ResultMap("speedDialPresetResultMap")
  Optional<SpeedDialPreset> findByUserIdAndSpeedDialId(
      @Param("userId") Long userId, @Param("speedDialId") String speedDialId);

  @Update(
      "UPDATE speed_dials SET name = #{name}, system_prompt = #{systemPrompt}, description = #{description}, "
          + "icon_emoji = #{iconEmoji}, voice = #{voice}, enabled_tools = #{enabledTools}::jsonb, updated_at = #{updatedAt} "
          + "WHERE id = #{id}")
  void update(SpeedDialPreset speedDialPreset);

  @Delete("DELETE FROM speed_dials WHERE user_id = #{userId} AND speed_dial_id = #{speedDialId}")
  int deleteByUserIdAndSpeedDialId(
      @Param("userId") Long userId, @Param("speedDialId") String speedDialId);
}
