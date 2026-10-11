package com.example.ragbot;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;
import java.util.zip.GZIPInputStream;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Scheduled retention and archival; never performed as part of creating a chat. */
@Component
@ConditionalOnProperty(name = "rag.build-index", havingValue = "false", matchIfMissing = true)
public final class HistoryMaintenance {
  private final MemoryStore store;
  private final HistoryProperties properties;
  private final MockFinanceService users;
  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
      Thread.ofPlatform().daemon(true).name("agent-history-maintenance").factory());

  public HistoryMaintenance(MemoryStore store, HistoryProperties properties, MockFinanceService users) {
    this.store = store; this.properties = properties; this.users = users;
  }

  @PostConstruct
  void start() {
    scheduler.scheduleWithFixedDelay(() -> {
      for (var user : users.users()) {
        var gate = store.runLock(user.id());
        if (!gate.tryLock()) continue;
        try { store.maintain(user.id(), Instant.now()); }
        catch (IOException | RuntimeException error) {
          org.slf4j.LoggerFactory.getLogger(HistoryMaintenance.class)
              .warn("History maintenance deferred ({})", error.getClass().getSimpleName());
        } finally { gate.unlock(); }
      }
    }, 1, properties.maintenanceIntervalSeconds(), TimeUnit.SECONDS);
  }

  @PreDestroy
  public void close() throws IOException {
    scheduler.shutdownNow();
    try {
      if (!scheduler.awaitTermination(30, TimeUnit.SECONDS)) throw new IOException("History maintenance has not stopped");
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt(); throw new IOException("Interrupted while closing history maintenance", error);
    }
  }

  static Map<String, Object> maintain(MemoryStore store, String userId, Instant now) throws IOException {
    int archived = 0, deleted = 0;
    // Release the file lock between sessions. The run gate prevents active sessions being expired.
    for (Path directory : store.sessionDirectories(userId)) {
      int action = store.withFiles(userId, directory, () -> {
        FileHistory.Metadata meta = store.history().info(directory);
        Instant updated = Instant.parse(meta.updatedAt());
        if (store.historyLimits().deleteExpired() && updated.isBefore(now.minus(store.historyLimits().retentionDays(), ChronoUnit.DAYS))) {
          FileHistory.deleteTree(directory); store.history().invalidate(directory); return 2;
        }
        if (!meta.archived() && updated.isBefore(now.minus(store.historyLimits().archiveAfterDays(), ChronoUnit.DAYS))) {
          archive(store.history(), directory); return 1;
        }
        return 0;
      });
      if (action == 1) archived++; if (action == 2) deleted++;
    }
    Path backups = store.userDirectory(userId).resolve("archive");
    store.withFiles(userId, backups, () -> {
      if (store.historyLimits().deleteExpired() && Files.isDirectory(backups)) {
        try (var paths = Files.list(backups)) {
          for (Path file : paths.filter(Files::isRegularFile).toList())
            if (Files.getLastModifiedTime(file).toInstant().isBefore(now.minus(store.historyLimits().retentionDays(), ChronoUnit.DAYS))) Files.delete(file);
        }
      }
      return null;
    });
    var storage = store.storage(userId);
    return Map.of("archived", archived, "deleted", deleted, "bytes", storage.get("bytes"),
        "quotaBytes", storage.get("quotaBytes"), "overQuota", storage.get("overQuota"),
        "deleteExpired", store.historyLimits().deleteExpired());
  }

  static void archive(FileHistory history, Path directory) throws IOException {
    FileHistory.Metadata meta = history.info(directory);
    StringBuilder recap = new StringBuilder("# Historical conversation\nTitle: ").append(meta.title())
        .append("\nCreated: ").append(meta.createdAt()).append("\nLast activity: ").append(meta.updatedAt())
        .append("\nTurns: ").append(meta.turnCount()).append("\n\n").append(history.summary(directory).text());
    // No additional model call: retain dated query conditions and a few exact historical conclusions.
    for (var turn : history.page(directory, Math.max(0, meta.turnCount() - 3), 3, false).turns()) {
      recap.append("\n\nTurn ").append(turn.index()).append(" at ").append(turn.timestamp())
          .append("\nQuery: ").append(MemoryStore.excerpt(turn.message(), 500))
          .append("\nHistorical conclusion: ").append(MemoryStore.excerpt(turn.answer(), 500));
      for (JsonNode item : turn.output()) if (item.path("type").asString("").equals("function_call"))
        recap.append("\nTool: ").append(item.path("name").asString()).append(" ")
            .append(MemoryStore.excerpt(item.path("arguments").asString(), 500));
    }
    MemoryStore.writeAtomic(directory.resolve("archive-summary.md"), recap.toString());
    Path raw = directory.resolve("messages.jsonl");
    if (Files.exists(raw)) gzipVerified(raw);
    Path payloads = directory.resolve("payloads");
    if (Files.isDirectory(payloads)) {
      try (var paths = Files.list(payloads)) {
        for (Path file : paths.filter(path -> path.getFileName().toString().endsWith(".json")).toList()) gzipVerified(file);
      }
    }
    history.saveMetadata(directory, new FileHistory.Metadata(meta.id(), meta.createdAt(), meta.updatedAt(), meta.title(), meta.turnCount(), true));
    history.invalidate(directory);
  }

  private static void gzipVerified(Path raw) throws IOException {
    Path zip = raw.resolveSibling(raw.getFileName() + ".gz");
    Path temp = Files.createTempFile(raw.getParent(), ".gzip-", ".tmp");
    try {
      try (var output = new GZIPOutputStream(Files.newOutputStream(temp))) { Files.copy(raw, output); }
      try (var original = Files.newInputStream(raw); var restored = new GZIPInputStream(Files.newInputStream(temp))) {
        byte[] left = new byte[8192], right = new byte[8192];
        int n;
        while ((n = original.read(left)) != -1) {
          if (restored.readNBytes(right, 0, n) != n || !java.util.Arrays.equals(left, 0, n, right, 0, n))
            throw new IOException("Archive verification failed; original retained");
        }
        if (restored.read() != -1) throw new IOException("Archive verification failed");
      }
      Files.move(temp, zip, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      Files.delete(raw);
    } finally { Files.deleteIfExists(temp); }
  }


  /** Incremental totals: only scan the changed session or core files on ordinary writes. */
  static final class Usage {
    private final Map<Path, Long> totals = new ConcurrentHashMap<>();
    private final Map<Path, Integer> depths = new ConcurrentHashMap<>();
    long bytes(Path user) throws IOException {
      Long bytes = totals.get(user);
      if (bytes == null) { bytes = FileHistory.diskBytes(user); totals.put(user, bytes); }
      return bytes;
    }
    long refresh(Path user) throws IOException {
      long bytes = FileHistory.diskBytes(user); totals.put(user, bytes); return bytes;
    }
    <T> T track(Path user, Path changed, MemoryStore.FileOperation<T> operation) throws IOException {
      int depth = depths.getOrDefault(user, 0);
      if (depth > 0) return operation.run();
      bytes(user);
      long before = scopedBytes(user, changed);
      depths.put(user, 1);
      try { return operation.run(); }
      finally {
        depths.remove(user);
        totals.put(user, totals.get(user) + scopedBytes(user, changed) - before);
      }
    }
    private static long scopedBytes(Path user, Path changed) throws IOException {
      if (!user.equals(changed)) return FileHistory.diskBytes(changed);
      if (!Files.isDirectory(user)) return 0;
      long bytes = FileHistory.diskBytes(user.resolve("archive"));
      try (var paths = Files.list(user)) {
        for (Path file : paths.filter(Files::isRegularFile).toList()) bytes += Files.size(file);
      }
      return bytes;
    }
  }
}
