package app.vagina.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class VfsFileServiceConcurrencyTest {
  @Test
  void sameUserConcurrentWritesDoNotLoseUpdates() throws Exception {
    BlockingStorage storage = new BlockingStorage();
    VfsFileService service = service(storage);
    storage.blockFirstRead = true;

    AtomicReference<Throwable> firstFailure = new AtomicReference<>();
    Thread first = new Thread(() -> run(() -> service.write(7L, "/first", "one"), firstFailure));
    Thread second =
        new Thread(() -> run(() -> service.write(7L, "/second", "two"), new AtomicReference<>()));
    first.start();
    assertTrue(storage.firstReadEntered.await(2, TimeUnit.SECONDS));
    second.start();
    assertFalse(storage.secondReadEntered.await(100, TimeUnit.MILLISECONDS));
    storage.releaseFirstRead.countDown();
    first.join(2_000);
    second.join(2_000);

    assertEquals(null, firstFailure.get());
    assertEquals("one", service.read(7L, "/first").orElseThrow().getContent());
    assertEquals("two", service.read(7L, "/second").orElseThrow().getContent());
  }

  @Test
  void lockIsReleasedAfterStorageFailure() {
    BlockingStorage storage = new BlockingStorage();
    storage.failNextSave = true;
    VfsFileService service = service(storage);

    try {
      service.write(7L, "/failed", "value");
    } catch (IllegalStateException expected) {
      assertEquals("save failed", expected.getMessage());
    }

    service.write(7L, "/recovered", "ok");
    assertEquals("ok", service.read(7L, "/recovered").orElseThrow().getContent());
  }

  @Test
  void differentUsersUseIndependentSnapshots() throws Exception {
    BlockingStorage storage = new BlockingStorage();
    VfsFileService service = service(storage);
    CountDownLatch start = new CountDownLatch(1);
    Thread first = new Thread(() -> awaitAndWrite(start, service, 7L, "/same", "seven"));
    Thread second = new Thread(() -> awaitAndWrite(start, service, 8L, "/same", "eight"));
    first.start();
    second.start();
    start.countDown();
    first.join(2_000);
    second.join(2_000);

    assertEquals("seven", service.read(7L, "/same").orElseThrow().getContent());
    assertEquals("eight", service.read(8L, "/same").orElseThrow().getContent());
  }

  private VfsFileService service(BlockingStorage storage) {
    VfsFileService service = new VfsFileService();
    service.objectStorageService = storage;
    service.objectMapper = new ObjectMapper();
    return service;
  }

  private void awaitAndWrite(
      CountDownLatch start, VfsFileService service, long userId, String path, String content) {
    try {
      start.await();
      service.write(userId, path, content);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new AssertionError(error);
    }
  }

  private void run(Runnable action, AtomicReference<Throwable> failure) {
    try {
      action.run();
    } catch (Throwable error) {
      failure.set(error);
    }
  }

  private static final class BlockingStorage extends ObjectStorageService {
    private final Map<String, byte[]> values = new ConcurrentHashMap<>();
    private final AtomicInteger reads = new AtomicInteger();
    private final CountDownLatch firstReadEntered = new CountDownLatch(1);
    private final CountDownLatch secondReadEntered = new CountDownLatch(1);
    private final CountDownLatch releaseFirstRead = new CountDownLatch(1);
    private volatile boolean blockFirstRead;
    private volatile boolean failNextSave;

    @Override
    public Optional<byte[]> read(String key) {
      int count = reads.incrementAndGet();
      if (count == 1 && blockFirstRead) {
        firstReadEntered.countDown();
        try {
          if (!releaseFirstRead.await(2, TimeUnit.SECONDS)) {
            throw new AssertionError("Timed out waiting to release first read");
          }
        } catch (InterruptedException error) {
          Thread.currentThread().interrupt();
          throw new AssertionError(error);
        }
      } else if (count == 2 && blockFirstRead) {
        secondReadEntered.countDown();
      }
      byte[] value = values.get(key);
      return value == null ? Optional.empty() : Optional.of(value.clone());
    }

    @Override
    public void save(String key, byte[] payload, String contentType) {
      if (failNextSave) {
        failNextSave = false;
        throw new IllegalStateException("save failed");
      }
      values.put(key, payload.clone());
    }
  }
}
