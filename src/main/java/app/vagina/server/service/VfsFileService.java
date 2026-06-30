package app.vagina.server.service;

import app.vagina.server.entity.VfsFileEntity;
import app.vagina.server.mapper.VfsFileMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

@ApplicationScoped
public class VfsFileService {
  public static final int MAX_PATH_LENGTH = 512;
  // Bound the raw input before normalization so split()/normalization can't be driven with a
  // multi-megabyte traversal string; the authoritative storage bound is MAX_PATH_LENGTH on the
  // normalized result.
  private static final int MAX_RAW_PATH_LENGTH = 8192;

  public static final String ERROR_PATH_MUST_BE_ABSOLUTE = "Path must be absolute";
  public static final String ERROR_PATH_TOO_LONG = "Path too long";
  public static final String ERROR_PATH_CONTAINS_NULL_BYTE = "Path contains null byte";
  public static final String ERROR_RESERVED_PATH = "Access denied: reserved path";
  public static final String ERROR_PATH_MUST_TARGET_FILE = "Path must target a file";
  public static final String ERROR_SOURCE_FILE_NOT_FOUND = "Source file not found";
  public static final String ERROR_DESTINATION_ALREADY_EXISTS = "Destination already exists";

  @Inject VfsFileMapper vfsFileMapper;

  public List<String> list(Long userId, String path, boolean recursive) {
    String normalizedPath = validatePath(path);
    List<String> paths =
        vfsFileMapper.findByUserId(userId).stream().map(VfsFileEntity::getPath).sorted().toList();

    if (recursive) {
      return listRecursive(paths, normalizedPath);
    }
    return listImmediate(paths, normalizedPath);
  }

  public Optional<VfsFileEntity> read(Long userId, String path) {
    String normalizedPath = validateFilePath(path);
    return vfsFileMapper.findByUserIdAndPath(userId, normalizedPath);
  }

  @Transactional
  public VfsFileEntity write(Long userId, String path, String content) {
    String normalizedPath = validateFilePath(path);
    LocalDateTime now = LocalDateTime.now();

    Optional<VfsFileEntity> existing = vfsFileMapper.findByUserIdAndPath(userId, normalizedPath);
    if (existing.isPresent()) {
      VfsFileEntity entity = existing.get();
      entity.setContent(content);
      entity.setUpdatedAt(now);
      vfsFileMapper.update(entity);
      return entity;
    }

    VfsFileEntity entity = new VfsFileEntity();
    entity.setUserId(userId);
    entity.setPath(normalizedPath);
    entity.setContent(content);
    entity.setCreatedAt(now);
    entity.setUpdatedAt(now);
    vfsFileMapper.insert(entity);
    return entity;
  }

  @Transactional
  public MoveResult move(Long userId, String fromPath, String toPath) {
    String normalizedFromPath = validateFilePath(fromPath);
    String normalizedToPath = validateFilePath(toPath);

    if (normalizedFromPath.equals(normalizedToPath)) {
      return new MoveResult(normalizedFromPath, normalizedToPath);
    }

    VfsFileEntity source =
        vfsFileMapper
            .findByUserIdAndPath(userId, normalizedFromPath)
            .orElseThrow(() -> new IllegalStateException(ERROR_SOURCE_FILE_NOT_FOUND));

    if (vfsFileMapper.findByUserIdAndPath(userId, normalizedToPath).isPresent()) {
      throw new IllegalStateException(ERROR_DESTINATION_ALREADY_EXISTS);
    }

    source.setPath(normalizedToPath);
    source.setUpdatedAt(LocalDateTime.now());
    vfsFileMapper.update(source);
    return new MoveResult(normalizedFromPath, normalizedToPath);
  }

  @Transactional
  public boolean delete(Long userId, String path) {
    String normalizedPath = validateFilePath(path);
    return vfsFileMapper.deleteByUserIdAndPath(userId, normalizedPath) > 0;
  }

  private String validateFilePath(String path) {
    String normalizedPath = validatePath(path);
    if ("/".equals(normalizedPath)) {
      throw new IllegalArgumentException(ERROR_PATH_MUST_TARGET_FILE);
    }
    return normalizedPath;
  }

  private String validatePath(String path) {
    String normalizedPath = normalizeAndValidatePath(path);
    checkReservedPath(normalizedPath);
    return normalizedPath;
  }

  private String normalizeAndValidatePath(String path) {
    if (path == null || !path.startsWith("/")) {
      throw new IllegalArgumentException(ERROR_PATH_MUST_BE_ABSOLUTE);
    }

    if (path.length() > MAX_RAW_PATH_LENGTH) {
      throw new IllegalArgumentException(ERROR_PATH_TOO_LONG);
    }

    if (path.indexOf('\u0000') >= 0) {
      throw new IllegalArgumentException(ERROR_PATH_CONTAINS_NULL_BYTE);
    }

    String normalized = normalizePath(path);

    if (normalized.length() > MAX_PATH_LENGTH) {
      throw new IllegalArgumentException(ERROR_PATH_TOO_LONG);
    }

    return normalized;
  }

  private String normalizePath(String path) {
    String normalizedInput = path;
    if (!"/".equals(normalizedInput) && normalizedInput.endsWith("/")) {
      normalizedInput = normalizedInput.substring(0, normalizedInput.length() - 1);
    }

    String[] parts = normalizedInput.split("/");
    List<String> normalizedParts = new ArrayList<>();
    for (String part : parts) {
      if (part.isEmpty() || ".".equals(part)) {
        continue;
      }
      if ("..".equals(part)) {
        if (!normalizedParts.isEmpty()) {
          normalizedParts.remove(normalizedParts.size() - 1);
        }
        continue;
      }
      normalizedParts.add(part);
    }

    if (normalizedParts.isEmpty()) {
      return "/";
    }
    return "/" + String.join("/", normalizedParts);
  }

  private void checkReservedPath(String path) {
    if ("/system".equals(path)
        || path.startsWith("/system/")
        || "/tmp".equals(path)
        || path.startsWith("/tmp/")) {
      throw new IllegalArgumentException(ERROR_RESERVED_PATH);
    }
  }

  private List<String> listImmediate(List<String> paths, String basePath) {
    TreeSet<String> children = new TreeSet<>();
    for (String filePath : paths) {
      String relative = relativePath(basePath, filePath);
      if (relative == null || relative.isEmpty()) {
        continue;
      }

      int slashIndex = relative.indexOf('/');
      String child = slashIndex == -1 ? relative : relative.substring(0, slashIndex) + "/";
      children.add(child);
    }
    return new ArrayList<>(children);
  }

  private List<String> listRecursive(List<String> paths, String basePath) {
    List<String> descendants = new ArrayList<>();
    for (String filePath : paths) {
      String relative = relativePath(basePath, filePath);
      if (relative == null || relative.isEmpty()) {
        continue;
      }
      descendants.add(relative);
    }
    descendants.sort(String::compareTo);
    return descendants;
  }

  private String relativePath(String basePath, String filePath) {
    if ("/".equals(basePath)) {
      return filePath.startsWith("/") ? filePath.substring(1) : null;
    }

    String prefix = basePath + "/";
    if (!filePath.startsWith(prefix)) {
      return null;
    }
    return filePath.substring(prefix.length());
  }

  public record MoveResult(String fromPath, String toPath) {}
}
