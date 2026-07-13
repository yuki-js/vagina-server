package app.vagina.server.mapper;

import java.time.LocalDateTime;
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
      "INSERT INTO speed_dials (user_id, speed_dial_id, name, system_prompt, description, icon_emoji, voice, voice_agent_id, tool_choice_required, enabled_tools, created_at, updated_at) "
          + "VALUES (#{userId}, #{speedDialId}, #{name}, #{systemPrompt}, #{description}, #{iconEmoji}, #{voice}, #{voiceAgentId}, #{toolChoiceRequired}, #{enabledTools}::jsonb, #{createdAt}, #{updatedAt})")
  @Options(useGeneratedKeys = true, keyProperty = "id")
  void insert(Row row);

  @Select(
      "SELECT id, user_id, speed_dial_id, name, system_prompt, description, icon_emoji, voice, voice_agent_id, tool_choice_required, enabled_tools::text as enabled_tools, created_at, updated_at "
          + "FROM speed_dials WHERE id = #{id}")
  @Results(
      id = "speedDialRowResultMap",
      value = {
        @Result(property = "id", column = "id", id = true),
        @Result(property = "userId", column = "user_id"),
        @Result(property = "speedDialId", column = "speed_dial_id"),
        @Result(property = "name", column = "name"),
        @Result(property = "systemPrompt", column = "system_prompt"),
        @Result(property = "description", column = "description"),
        @Result(property = "iconEmoji", column = "icon_emoji"),
        @Result(property = "voice", column = "voice"),
        @Result(property = "voiceAgentId", column = "voice_agent_id"),
        @Result(property = "toolChoiceRequired", column = "tool_choice_required"),
        @Result(property = "enabledTools", column = "enabled_tools"),
        @Result(property = "createdAt", column = "created_at"),
        @Result(property = "updatedAt", column = "updated_at")
      })
  Optional<Row> findById(@Param("id") Long id);

  @Select(
      "SELECT id, user_id, speed_dial_id, name, system_prompt, description, icon_emoji, voice, voice_agent_id, tool_choice_required, enabled_tools::text as enabled_tools, created_at, updated_at "
          + "FROM speed_dials WHERE user_id = #{userId} ORDER BY created_at ASC, id ASC")
  @ResultMap("speedDialRowResultMap")
  List<Row> findByUserId(@Param("userId") Long userId);

  @Select(
      "SELECT id, user_id, speed_dial_id, name, system_prompt, description, icon_emoji, voice, voice_agent_id, tool_choice_required, enabled_tools::text as enabled_tools, created_at, updated_at "
          + "FROM speed_dials WHERE user_id = #{userId} AND speed_dial_id = #{speedDialId}")
  @ResultMap("speedDialRowResultMap")
  Optional<Row> findByUserIdAndSpeedDialId(
      @Param("userId") Long userId, @Param("speedDialId") String speedDialId);

  @Update(
      "UPDATE speed_dials SET name = #{name}, system_prompt = #{systemPrompt}, description = #{description}, "
          + "icon_emoji = #{iconEmoji}, voice = #{voice}, voice_agent_id = #{voiceAgentId}, tool_choice_required = #{toolChoiceRequired}, enabled_tools = #{enabledTools}::jsonb, updated_at = #{updatedAt} "
          + "WHERE id = #{id}")
  void update(Row row);

  @Delete("DELETE FROM speed_dials WHERE user_id = #{userId} AND speed_dial_id = #{speedDialId}")
  int deleteByUserIdAndSpeedDialId(
      @Param("userId") Long userId, @Param("speedDialId") String speedDialId);

  final class Row {
    private Long id;
    private Long userId;
    private String speedDialId;
    private String name;
    private String systemPrompt;
    private String description;
    private String iconEmoji;
    private String voice;
    private String voiceAgentId;
    private boolean toolChoiceRequired;
    private String enabledTools;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() {
      return id;
    }

    public void setId(Long id) {
      this.id = id;
    }

    public Long getUserId() {
      return userId;
    }

    public void setUserId(Long userId) {
      this.userId = userId;
    }

    public String getSpeedDialId() {
      return speedDialId;
    }

    public void setSpeedDialId(String speedDialId) {
      this.speedDialId = speedDialId;
    }

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public String getSystemPrompt() {
      return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
      this.systemPrompt = systemPrompt;
    }

    public String getDescription() {
      return description;
    }

    public void setDescription(String description) {
      this.description = description;
    }

    public String getIconEmoji() {
      return iconEmoji;
    }

    public void setIconEmoji(String iconEmoji) {
      this.iconEmoji = iconEmoji;
    }

    public String getVoice() {
      return voice;
    }

    public void setVoice(String voice) {
      this.voice = voice;
    }

    public String getVoiceAgentId() {
      return voiceAgentId;
    }

    public void setVoiceAgentId(String voiceAgentId) {
      this.voiceAgentId = voiceAgentId;
    }

    public boolean isToolChoiceRequired() {
      return toolChoiceRequired;
    }

    public void setToolChoiceRequired(boolean toolChoiceRequired) {
      this.toolChoiceRequired = toolChoiceRequired;
    }

    public String getEnabledTools() {
      return enabledTools;
    }

    public void setEnabledTools(String enabledTools) {
      this.enabledTools = enabledTools;
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
