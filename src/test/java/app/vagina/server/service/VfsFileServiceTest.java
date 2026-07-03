package app.vagina.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import app.vagina.server.entity.VfsFileEntity;
import app.vagina.server.mapper.VfsFileMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class VfsFileServiceTest {

  @Test
  void writeNormalizesPathBeforePersisting() {
    VfsFileMapper mapper = mock(VfsFileMapper.class);
    when(mapper.findByUserId(7L)).thenReturn(List.of());
    when(mapper.findByUserIdAndPath(7L, "/notes/today.md")).thenReturn(Optional.empty());
    VfsFileService service = service(mapper);

    service.write(7L, "/notes/./draft/../today.md", "hello");

    ArgumentCaptor<VfsFileEntity> entity = ArgumentCaptor.forClass(VfsFileEntity.class);
    verify(mapper).insert(entity.capture());
    assertEquals(7L, entity.getValue().getUserId());
    assertEquals("/notes/today.md", entity.getValue().getPath());
    assertEquals("hello", entity.getValue().getContent());
  }

  @Test
  void writeRejectsReservedPathBeforePersistence() {
    VfsFileMapper mapper = mock(VfsFileMapper.class);
    VfsFileService service = service(mapper);

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> service.write(7L, "/system/config.json", "blocked"));

    assertEquals(VfsFileService.ERROR_RESERVED_PATH, error.getMessage());
  }

  @Test
  void writeRejectsOversizedContentBeforePersistence() {
    VfsFileMapper mapper = mock(VfsFileMapper.class);
    when(mapper.findByUserIdAndPath(7L, "/huge.txt")).thenReturn(Optional.empty());
    VfsFileService service = service(mapper);
    String oversized = "x".repeat(VfsFileService.MAX_FILE_SIZE_BYTES + 1);

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class, () -> service.write(7L, "/huge.txt", oversized));

    assertEquals(
        VfsFileService.ERROR_FILE_TOO_LARGE
            + " (max "
            + VfsFileService.MAX_FILE_SIZE_BYTES
            + " bytes)",
        error.getMessage());
  }

  @Test
  void moveRejectsExistingDestinationWithConflictSemantic() {
    VfsFileMapper mapper = mock(VfsFileMapper.class);
    VfsFileEntity source = new VfsFileEntity();
    source.setId(11L);
    source.setUserId(7L);
    source.setPath("/from.txt");
    source.setContent("from");
    VfsFileEntity destination = new VfsFileEntity();
    destination.setId(12L);
    destination.setUserId(7L);
    destination.setPath("/to.txt");
    destination.setContent("to");
    when(mapper.findByUserIdAndPath(7L, "/from.txt")).thenReturn(Optional.of(source));
    when(mapper.findByUserIdAndPath(7L, "/to.txt")).thenReturn(Optional.of(destination));
    VfsFileService service = service(mapper);

    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> service.move(7L, "/from.txt", "/to.txt"));

    assertEquals(VfsFileService.ERROR_DESTINATION_ALREADY_EXISTS, error.getMessage());
  }

  @Test
  void listReturnsImmediateChildrenFromNormalizedDirectory() {
    VfsFileMapper mapper = mock(VfsFileMapper.class);
    when(mapper.findByUserId(7L))
        .thenReturn(
            List.of(
                file("/notes/a.md"),
                file("/notes/sub/b.md"),
                file("/notes/sub/c.md"),
                file("/other/d.md")));
    VfsFileService service = service(mapper);

    assertEquals(List.of("a.md", "sub/"), service.list(7L, "/notes/./", false));
  }

  private VfsFileService service(VfsFileMapper mapper) {
    VfsFileService service = new VfsFileService();
    service.vfsFileMapper = mapper;
    return service;
  }

  private VfsFileEntity file(String path) {
    VfsFileEntity entity = new VfsFileEntity();
    entity.setUserId(7L);
    entity.setPath(path);
    entity.setContent("");
    return entity;
  }
}
