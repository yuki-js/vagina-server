package app.vagina.server.mapper;

import app.vagina.server.entity.VfsFileEntity;
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
public interface VfsFileMapper {

  @Insert(
      "INSERT INTO vfs_files (user_id, path, content, created_at, updated_at) "
          + "VALUES (#{userId}, #{path}, #{content}, #{createdAt}, #{updatedAt})")
  @Options(useGeneratedKeys = true, keyProperty = "id")
  void insert(VfsFileEntity vfsFileEntity);

  @Select(
      "SELECT id, user_id, path, content, created_at, updated_at "
          + "FROM vfs_files WHERE id = #{id}")
  @Results(
      id = "vfsFileEntityResultMap",
      value = {
        @Result(property = "id", column = "id", id = true),
        @Result(property = "userId", column = "user_id"),
        @Result(property = "path", column = "path"),
        @Result(property = "content", column = "content"),
        @Result(property = "createdAt", column = "created_at"),
        @Result(property = "updatedAt", column = "updated_at")
      })
  Optional<VfsFileEntity> findById(@Param("id") Long id);

  @Select(
      "SELECT id, user_id, path, content, created_at, updated_at "
          + "FROM vfs_files WHERE user_id = #{userId} ORDER BY path ASC, id ASC")
  @ResultMap("vfsFileEntityResultMap")
  List<VfsFileEntity> findByUserId(@Param("userId") Long userId);

  @Select(
      "SELECT id, user_id, path, content, created_at, updated_at "
          + "FROM vfs_files WHERE user_id = #{userId} AND path = #{path}")
  @ResultMap("vfsFileEntityResultMap")
  Optional<VfsFileEntity> findByUserIdAndPath(
      @Param("userId") Long userId, @Param("path") String path);

  @Update(
      "UPDATE vfs_files SET path = #{path}, content = #{content}, updated_at = #{updatedAt} "
          + "WHERE id = #{id}")
  void update(VfsFileEntity vfsFileEntity);

  @Delete("DELETE FROM vfs_files WHERE user_id = #{userId} AND path = #{path}")
  int deleteByUserIdAndPath(@Param("userId") Long userId, @Param("path") String path);
}
