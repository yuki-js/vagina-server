package app.vagina.server.entity;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public final class VfsFileData {
  private String path;
  private String content;

  public VfsFileData() {}

  public VfsFileData(String path, String content) {
    this.path = path;
    this.content = content;
  }

  public String getPath() {
    return path;
  }

  public void setPath(String path) {
    this.path = path;
  }

  public String getContent() {
    return content;
  }

  public void setContent(String content) {
    this.content = content;
  }
}
