package app.vagina.server.service;

import app.vagina.server.entity.VfsFileData;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

@ApplicationScoped
public class VfsFileService {
  public static final int MAX_PATH_LENGTH = 512;
  public static final int MAX_FILE_SIZE_BYTES = 1024 * 1024;
  public static final int MAX_TOTAL_SIZE_BYTES = 100 * 1024 * 1024;
  private static final int MAX_RAW_PATH_LENGTH = 8192;
  private static final int SNAPSHOT_SCHEMA_VERSION = 1;

  public static final String ERROR_PATH_MUST_BE_ABSOLUTE = "Path must be absolute";
  public static final String ERROR_PATH_TOO_LONG = "Path too long";
  public static final String ERROR_PATH_CONTAINS_NULL_BYTE = "Path contains null byte";
  public static final String ERROR_RESERVED_PATH = "Access denied: reserved path";
  public static final String ERROR_PATH_MUST_TARGET_FILE = "Path must target a file";
  public static final String ERROR_SOURCE_FILE_NOT_FOUND = "Source file not found";
  public static final String ERROR_DESTINATION_ALREADY_EXISTS = "Destination already exists";
  public static final String ERROR_FILE_TOO_LARGE = "File too large";
  public static final String ERROR_FILESYSTEM_QUOTA_EXCEEDED = "Filesystem quota exceeded";

  @Inject ObjectStorageService objectStorageService;
  @Inject ObjectMapper objectMapper;

  public List<String> list(Long userId, String path, boolean recursive) {
    String normalizedPath = validatePath(path);
    List<String> paths = loadSnapshot(userId).files().keySet().stream().sorted().toList();

    if (recursive) {
      return listRecursive(paths, normalizedPath);
    }
    return listImmediate(paths, normalizedPath);
  }

  public Optional<VfsFileData> read(Long userId, String path) {
    String normalizedPath = validateFilePath(path);
    String content = loadSnapshot(userId).files().get(normalizedPath);
    return content == null
        ? Optional.empty()
        : Optional.of(new VfsFileData(normalizedPath, content));
  }

  public VfsFileData write(Long userId, String path, String content) {
    String normalizedPath = validateFilePath(path);
    VfsSnapshot snapshot = loadSnapshot(userId);
    validateContentSize(snapshot, normalizedPath, content);
    snapshot.files().put(normalizedPath, content);
    saveSnapshot(userId, snapshot);
    return new VfsFileData(normalizedPath, content);
  }

  public MoveResult move(Long userId, String fromPath, String toPath) {
    String normalizedFromPath = validateFilePath(fromPath);
    String normalizedToPath = validateFilePath(toPath);

    if (normalizedFromPath.equals(normalizedToPath)) {
      return new MoveResult(normalizedFromPath, normalizedToPath);
    }

    VfsSnapshot snapshot = loadSnapshot(userId);
    String sourceContent = snapshot.files().get(normalizedFromPath);
    if (sourceContent == null) {
      throw new IllegalStateException(ERROR_SOURCE_FILE_NOT_FOUND);
    }

    if (snapshot.files().containsKey(normalizedToPath)) {
      throw new IllegalStateException(ERROR_DESTINATION_ALREADY_EXISTS);
    }

    snapshot.files().remove(normalizedFromPath);
    snapshot.files().put(normalizedToPath, sourceContent);
    saveSnapshot(userId, snapshot);
    return new MoveResult(normalizedFromPath, normalizedToPath);
  }

  public boolean delete(Long userId, String path) {
    String normalizedPath = validateFilePath(path);
    VfsSnapshot snapshot = loadSnapshot(userId);
    if (snapshot.files().remove(normalizedPath) == null) {
      return false;
    }
    saveSnapshot(userId, snapshot);
    return true;
  }

  private VfsSnapshot loadSnapshot(Long userId) {
    return objectStorageService
        .read(vfsSnapshotBlobKey(userId))
        .map(this::decodeSnapshot)
        .orElseGet(VfsSnapshot::empty);
  }

  private void saveSnapshot(Long userId, VfsSnapshot snapshot) {
    try {
      objectStorageService.save(
          vfsSnapshotBlobKey(userId), objectMapper.writeValueAsBytes(snapshot), "application/json");
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("VFS snapshot could not be serialized", e);
    }
  }

  private VfsSnapshot decodeSnapshot(byte[] payload) {
    try {
      VfsSnapshot snapshot = objectMapper.readValue(payload, VfsSnapshot.class);
      return snapshot.normalized();
    } catch (IOException e) {
      throw new IllegalStateException("VFS snapshot could not be deserialized", e);
    }
  }

  private String vfsSnapshotBlobKey(Long userId) {
    return "vfs/" + userId + "/snapshot.json";
  }

  private void validateContentSize(VfsSnapshot snapshot, String normalizedPath, String content) {
    int nextFileSize = byteSize(content);
    if (nextFileSize > MAX_FILE_SIZE_BYTES) {
      throw new IllegalArgumentException(
          ERROR_FILE_TOO_LARGE + " (max " + MAX_FILE_SIZE_BYTES + " bytes)");
    }

    int currentFileSize = byteSize(snapshot.files().get(normalizedPath));
    int totalSize = 0;
    for (String fileContent : snapshot.files().values()) {
      totalSize += byteSize(fileContent);
    }

    int nextTotalSize = totalSize - currentFileSize + nextFileSize;
    if (nextTotalSize > MAX_TOTAL_SIZE_BYTES) {
      throw new IllegalArgumentException(
          ERROR_FILESYSTEM_QUOTA_EXCEEDED + " (max " + MAX_TOTAL_SIZE_BYTES + " bytes)");
    }
  }

  private int byteSize(String value) {
    return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
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

  @RegisterForReflection
  public static final class VfsSnapshot {
    private int schemaVersion = SNAPSHOT_SCHEMA_VERSION;
    private Map<String, String> files = new LinkedHashMap<>();

    public static VfsSnapshot empty() {
      return new VfsSnapshot();
    }

    public int getSchemaVersion() {
      return schemaVersion;
    }

    public void setSchemaVersion(int schemaVersion) {
      this.schemaVersion = schemaVersion;
    }

    public Map<String, String> getFiles() {
      return files;
    }

    public void setFiles(Map<String, String> files) {
      this.files = files == null ? new LinkedHashMap<>() : new LinkedHashMap<>(files);
    }

    Map<String, String> files() {
      return files;
    }

    VfsSnapshot normalized() {
      VfsSnapshot normalized = new VfsSnapshot();
      normalized.setSchemaVersion(schemaVersion);
      normalized.setFiles(new TreeMap<>(files));
      return normalized;
    }
  }
}
