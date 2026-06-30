package app.vagina.server.usecase;

import app.vagina.server.domain.error.ValidationException;
import app.vagina.server.entity.VfsFileEntity;
import app.vagina.server.service.VfsFileService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class VfsUsecase {
  @Inject VfsFileService vfsFileService;

  public List<String> list(Long userId, String path, Boolean recursive) {
    if (path == null || path.isBlank()) {
      throw new ValidationException("Missing or empty params.path");
    }
    return vfsFileService.list(userId, path, Boolean.TRUE.equals(recursive));
  }

  public Optional<VfsFileEntity> read(Long userId, String path) {
    if (path == null || path.isBlank()) {
      throw new ValidationException("Missing or empty params.path");
    }
    return vfsFileService.read(userId, path);
  }

  public VfsFileEntity write(Long userId, String path, String content) {
    if (path == null || path.isBlank()) {
      throw new ValidationException("Missing or empty params.path");
    }
    if (content == null) {
      throw new ValidationException("Missing params.content");
    }
    return vfsFileService.write(userId, path, content);
  }

  public VfsFileService.MoveResult move(Long userId, String fromPath, String toPath) {
    if (fromPath == null || fromPath.isBlank()) {
      throw new ValidationException("Missing or empty params.fromPath");
    }
    if (toPath == null || toPath.isBlank()) {
      throw new ValidationException("Missing or empty params.toPath");
    }
    return vfsFileService.move(userId, fromPath, toPath);
  }

  public boolean delete(Long userId, String path) {
    if (path == null || path.isBlank()) {
      throw new ValidationException("Missing or empty params.path");
    }
    return vfsFileService.delete(userId, path);
  }
}
