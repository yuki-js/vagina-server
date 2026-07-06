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
public interface TextAgentMapper {

  @Insert(
      "INSERT INTO text_agents (user_id, text_agent_id, name, prompt, description, text_model_id, enabled_tools, created_at, updated_at) "
          + "VALUES (#{userId}, #{textAgentId}, #{name}, #{prompt}, #{description}, #{textModelId}, #{enabledTools}::jsonb, #{createdAt}, #{updatedAt})")
  @Options(useGeneratedKeys = true, keyProperty = "id")
  void insert(Row row);

  @Select(
      "SELECT id, user_id, text_agent_id, name, prompt, description, text_model_id, enabled_tools::text as enabled_tools, created_at, updated_at "
          + "FROM text_agents WHERE id = #{id}")
  @Results(
      id = "textAgentRowResultMap",
      value = {
        @Result(property = "id", column = "id", id = true),
        @Result(property = "userId", column = "user_id"),
        @Result(property = "textAgentId", column = "text_agent_id"),
        @Result(property = "name", column = "name"),
        @Result(property = "prompt", column = "prompt"),
        @Result(property = "description", column = "description"),
        @Result(property = "textModelId", column = "text_model_id"),
        @Result(property = "enabledTools", column = "enabled_tools"),
        @Result(property = "createdAt", column = "created_at"),
        @Result(property = "updatedAt", column = "updated_at")
      })
  Optional<Row> findById(@Param("id") Long id);

  @Select(
      "SELECT id, user_id, text_agent_id, name, prompt, description, text_model_id, enabled_tools::text as enabled_tools, created_at, updated_at "
          + "FROM text_agents WHERE user_id = #{userId} ORDER BY created_at ASC, id ASC")
  @ResultMap("textAgentRowResultMap")
  List<Row> findByUserId(@Param("userId") Long userId);

  @Select(
      "SELECT id, user_id, text_agent_id, name, prompt, description, text_model_id, enabled_tools::text as enabled_tools, created_at, updated_at "
          + "FROM text_agents WHERE user_id = #{userId} AND text_agent_id = #{textAgentId}")
  @ResultMap("textAgentRowResultMap")
  Optional<Row> findByUserIdAndTextAgentId(
      @Param("userId") Long userId, @Param("textAgentId") String textAgentId);

  @Update(
      "UPDATE text_agents SET name = #{name}, prompt = #{prompt}, description = #{description}, "
          + "text_model_id = #{textModelId}, enabled_tools = #{enabledTools}::jsonb, updated_at = #{updatedAt} "
          + "WHERE id = #{id}")
  void update(Row row);

  @Delete("DELETE FROM text_agents WHERE user_id = #{userId} AND text_agent_id = #{textAgentId}")
  int deleteByUserIdAndTextAgentId(
      @Param("userId") Long userId, @Param("textAgentId") String textAgentId);

  final class Row {
    private Long id;
    private Long userId;
    private String textAgentId;
    private String name;
    private String prompt;
    private String description;
    private String textModelId;
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

    public String getTextAgentId() {
      return textAgentId;
    }

    public void setTextAgentId(String textAgentId) {
      this.textAgentId = textAgentId;
    }

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public String getPrompt() {
      return prompt;
    }

    public void setPrompt(String prompt) {
      this.prompt = prompt;
    }

    public String getDescription() {
      return description;
    }

    public void setDescription(String description) {
      this.description = description;
    }

    public String getTextModelId() {
      return textModelId;
    }

    public void setTextModelId(String textModelId) {
      this.textModelId = textModelId;
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
