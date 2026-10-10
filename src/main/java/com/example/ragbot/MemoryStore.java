package com.example.ragbot;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name = "rag.build-index", havingValue = "false", matchIfMissing = true)
public final class MemoryStore implements AutoCloseable {
  private static final Pattern SESSION_ID = Pattern.compile("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}");
  private final Path root;
  private final ObjectMapper mapper;
  private final MockFinanceService users;
  private final AgentProperties agentProperties;
  private final HistoryProperties historyProperties;
  private final FileHistory history;
  private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();
  private final FileChannel lockChannel;
  private final FileLock processLock;
  private final ScheduledExecutorService maintenance = Executors.newSingleThreadScheduledExecutor(
      Thread.ofPlatform().daemon(true).name("agent-history-maintenance").factory());

  public MemoryStore(AgentProperties properties, ObjectMapper mapper, MockFinanceService users) throws IOException {
    this(properties, HistoryProperties.defaults(), mapper, users);
  }

  @Autowired
  public MemoryStore(AgentProperties properties, HistoryProperties historyProperties,
      ObjectMapper mapper, MockFinanceService users) throws IOException {
    root = properties.memoryDirectory().toAbsolutePath().normalize();
    this.mapper = mapper; this.users = users; this.agentProperties = properties;
    this.historyProperties = historyProperties;
    history = new FileHistory(mapper, historyProperties);
    Files.createDirectories(root);
    lockChannel = FileChannel.open(root.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    try {
      processLock = lockChannel.tryLock();
      if (processLock == null) throw new IOException("Agent memory directory is already used by another server");
    } catch (IOException | RuntimeException error) { lockChannel.close(); throw error; }
  }

  @PostConstruct
  void startMaintenance() {
    maintenance.scheduleWithFixedDelay(() -> {
      for (var user : users.users()) {
        ReentrantLock lock = userLock(user.id());
        if (!lock.tryLock()) continue;
        try { maintain(user.id(), Instant.now()); }
        catch (IOException | RuntimeException error) {
          org.slf4j.LoggerFactory.getLogger(MemoryStore.class).warn("History maintenance deferred ({})", error.getClass().getSimpleName());
        } finally { lock.unlock(); }
      }
    }, 1, historyProperties.maintenanceIntervalSeconds(), TimeUnit.SECONDS);
  }

  public record StoredTurn(int index, String timestamp, String message, List<JsonNode> output,
      String answer, List<RagSource> sources, boolean completed, String model) {}
  public record SessionData(String id, String summary, int summarizedThrough, List<StoredTurn> turns, int turnCount) {}
  public record CoreMemory(String user, String notes) {
    public String combined() { return (user.strip() + "\n\n" + notes.strip()).strip(); }
  }

  public MockFinanceService.User user(String id) {
    return users.users().stream().filter(user -> user.id().equals(id)).findFirst()
        .orElseThrow(() -> new ApiRequestException(HttpStatus.BAD_REQUEST, "Select a valid demo user"));
  }

  ReentrantLock userLock(String id) { user(id); return locks.computeIfAbsent(id, ignored -> new ReentrantLock()); }

  public String createSession(String userId) throws IOException {
    ReentrantLock lock = userLock(userId); lock.lock();
    try {
      maintain(userId, Instant.now());
      if (FileHistory.diskBytes(userDirectory(userId)) + 8192 >= historyProperties.userQuotaBytes()) FileHistory.quotaExceeded();
      String id = UUID.randomUUID().toString();
      Path directory = sessionDirectory(userId, id); Files.createDirectories(directory);
      writeAtomic(directory.resolve("session.json"), mapper.writeValueAsString(Map.of("id", id, "createdAt", Instant.now().toString())));
      return id;
    } finally { lock.unlock(); }
  }

  public int turnCount(String userId, String id) throws IOException {
    ReentrantLock lock = userLock(userId); lock.lock();
    try { return history.info(requireSession(userId, id)).turnCount(); } finally { lock.unlock(); }
  }

  /** Bounded compatibility view; callers needing older messages use page(). */
  public SessionData session(String userId, String id) throws IOException {
    ReentrantLock lock = userLock(userId); lock.lock();
    try {
      Path directory = requireSession(userId, id);
      var meta = history.info(directory); var summary = history.summary(directory);
      return new SessionData(id, summary.text(), summary.through(),
          history.page(directory, Math.max(0, meta.turnCount() - 100), 100, false).turns(), meta.turnCount());
    } finally { lock.unlock(); }
  }

  public SessionData activeSession(String userId, String id) throws IOException {
    ReentrantLock lock = userLock(userId); lock.lock();
    try {
      Path directory = requireSession(userId, id);
      var meta = history.info(directory); var summary = history.summary(directory);
      if (summary.through() > meta.turnCount()) throw new IOException("Session summary points beyond its transcript");
      return new SessionData(id, summary.text(), summary.through(), history.unsummarized(directory), meta.turnCount());
    } finally { lock.unlock(); }
  }

  public FileHistory.Page page(String userId, String id, Integer offset, int limit, boolean hydrate) throws IOException {
    ReentrantLock lock = userLock(userId); lock.lock();
    try {
      Path directory = requireSession(userId, id);
      int start = offset == null ? Math.max(0, history.info(directory).turnCount() - limit) : offset;
      return history.page(directory, start, limit, hydrate);
    } finally { lock.unlock(); }
  }

  public List<Map<String, Object>> sessions(String userId) throws IOException {
    return sessions(userId, 0, historyProperties.pageSize());
  }

  public List<Map<String, Object>> sessions(String userId, int offset, int limit) throws IOException {
    if (offset < 0 || limit < 1 || limit > 100) throw new ApiRequestException(HttpStatus.BAD_REQUEST, "Invalid session page");
    ReentrantLock lock = userLock(userId); lock.lock();
    try {
      var result = new java.util.ArrayList<Map<String, Object>>();
      for (Path directory : sessionDirectories(userId)) {
        var meta = history.info(directory);
        result.add(Map.of("id", meta.id(), "title", meta.title(), "updatedAt", meta.updatedAt(),
            "archived", meta.archived(), "turnCount", meta.turnCount()));
      }
      result.sort(Comparator.comparing(item -> item.get("updatedAt").toString(), Comparator.reverseOrder()));
      return result.stream().skip(offset).limit(limit).toList();
    } finally { lock.unlock(); }
  }

  public void append(String userId, String id, StoredTurn turn) throws IOException {
    ReentrantLock lock = userLock(userId); lock.lock();
    try { history.append(requireSession(userId, id), turn, FileHistory.diskBytes(userDirectory(userId))); }
    finally { lock.unlock(); }
  }

  public void summary(String userId, String id, int through, String text) throws IOException {
    ReentrantLock lock = userLock(userId); lock.lock();
    try {
      if (through < 0 || text.isBlank() || through > turnCount(userId, id)) throw new IOException("Invalid or empty summary");
      Path directory = requireSession(userId, id);
      writeAtomic(directory.resolve("summary.md"), "<!-- through-turn: " + through + " -->\n" + text.strip() + "\n");
      history.refreshSummary(directory);
    } finally { lock.unlock(); }
  }

  public CoreMemory coreMemory(String userId) throws IOException {
    ReentrantLock lock = userLock(userId); lock.lock();
    try {
      Path directory = userDirectory(userId);
      Path pending = directory.resolve("core-pending.json");
      if (Files.exists(pending)) finishCoreWrite(directory, mapper.readValue(Files.readString(pending), CoreMemory.class));
      if (!Files.exists(directory.resolve("USER.md"))) {
        // memory.md and MEMORY.md are the same path on Windows: move legacy content to USER first.
        writeCore(directory, new CoreMemory(read(directory.resolve("memory.md")), ""));
      }
      return new CoreMemory(read(directory.resolve("USER.md")), read(directory.resolve("MEMORY.md")));
    } finally { lock.unlock(); }
  }

  public String memory(String userId) throws IOException { return coreMemory(userId).combined(); }
  public void memory(String userId, String content) throws IOException { coreMemory(userId, new CoreMemory(content, "")); }

  public void coreMemory(String userId, CoreMemory content) throws IOException {
    if (content == null || content.user() == null || content.notes() == null || content.combined().length() > 64000)
      throw new ApiRequestException(HttpStatus.BAD_REQUEST, "Memory must be 64,000 characters or fewer");
    if (TokenBudget.estimate(content.combined()) > agentProperties.memoryBudgetTokens())
      throw new ApiRequestException(HttpStatus.BAD_REQUEST, "Core memory exceeds its combined token budget");
    ReentrantLock lock = userLock(userId); lock.lock();
    try {
      CoreMemory previous = coreMemory(userId);
      CoreMemory normalized = new CoreMemory(content.user().strip(), content.notes().strip());
      if (previous.user().strip().equals(normalized.user()) && previous.notes().strip().equals(normalized.notes())) return;
      long bytes = FileHistory.diskBytes(userDirectory(userId));
      if (bytes + previous.combined().getBytes(java.nio.charset.StandardCharsets.UTF_8).length
          + normalized.combined().getBytes(java.nio.charset.StandardCharsets.UTF_8).length * 2L + 4096
          > historyProperties.userQuotaBytes()) FileHistory.quotaExceeded();
      if (!previous.combined().isBlank()) archiveMemory(userId, previous.combined());
      writeCore(userDirectory(userId), normalized);
    } finally { lock.unlock(); }
  }

  private void writeCore(Path directory, CoreMemory memory) throws IOException {
    writeAtomic(directory.resolve("core-pending.json"), mapper.writeValueAsString(memory));
    finishCoreWrite(directory, memory);
  }

  private static void finishCoreWrite(Path directory, CoreMemory memory) throws IOException {
    writeAtomic(directory.resolve("USER.md"), memory.user().isBlank() ? "" : memory.user().strip() + "\n");
    writeAtomic(directory.resolve("MEMORY.md"), memory.notes().isBlank() ? "" : memory.notes().strip() + "\n");
    Path legacy = directory.resolve("memory.md"), notes = directory.resolve("MEMORY.md");
    if (Files.exists(legacy) && !Files.isSameFile(legacy, notes)) Files.delete(legacy);
    Files.deleteIfExists(directory.resolve("core-pending.json"));
  }

  public void archiveMemory(String userId, String content) throws IOException {
    if (FileHistory.diskBytes(userDirectory(userId)) + content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 4096
        > historyProperties.userQuotaBytes()) FileHistory.quotaExceeded();
    writeAtomic(userDirectory(userId).resolve("archive").resolve("memory-" + UUID.randomUUID() + ".md"), content);
  }

  public int memoryCursor(String userId, String id) throws IOException {
    String text = read(requireSession(userId, id).resolve("memory-cursor.txt")).trim();
    return text.isEmpty() ? 0 : Integer.parseInt(text);
  }

  public void memoryCursor(String userId, String id, int through) throws IOException {
    writeAtomic(requireSession(userId, id).resolve("memory-cursor.txt"), Integer.toString(through));
  }

  public void skipOldMemoryCandidates(String userId) throws IOException {
    for (Path directory : sessionDirectories(userId))
      memoryCursor(userId, directory.getFileName().toString(), history.info(directory).turnCount());
  }

  public List<Map<String, Object>> searchHistory(String userId, String query) throws IOException {
    return searchHistory(userId, query, 0, 6).results();
  }

  public FileHistory.SearchPage searchHistory(String userId, String query, int offset, int limit) throws IOException {
    ReentrantLock lock = userLock(userId); lock.lock();
    try { return history.search(sessionDirectories(userId), query, offset, limit); }
    finally { lock.unlock(); }
  }

  public Map<String, Object> historyWindow(String userId, String id, int around, int window) throws IOException {
    if (around < 1 || window < 0 || window > 10) throw new ApiRequestException(HttpStatus.BAD_REQUEST, "Invalid history window");
    var page = page(userId, id, Math.max(0, around - window - 1), window * 2 + 1, true);
    List<Map<String, Object>> turns = page.turns().stream().map(turn -> Map.<String, Object>of(
        "turn", turn.index(), "timestamp", turn.timestamp(), "message", excerpt(turn.message(), 2000),
        "answer", excerpt(turn.answer(), 2000), "tools", excerpt(turn.output().toString(), 2000))).toList();
    Map<String, Object> result = new java.util.LinkedHashMap<>();
    result.put("sessionId", id); result.put("aroundTurn", around); result.put("turns", turns); result.put("offset", page.offset());
    result.put("nextOffset", page.nextOffset()); result.put("total", page.total()); result.put("historical", true);
    return result;
  }

  public Map<String, Object> spillToolResult(String userId, String id, String content) throws IOException {
    ReentrantLock lock = userLock(userId); lock.lock();
    try { return history.spill(requireSession(userId, id), content, FileHistory.diskBytes(userDirectory(userId))); }
    finally { lock.unlock(); }
  }

  public Map<String, Object> readPayload(String userId, String id, String ref, int offset) throws IOException {
    ReentrantLock lock = userLock(userId); lock.lock();
    try { return history.payload(requireSession(userId, id), ref, offset); }
    finally { lock.unlock(); }
  }

  public String sessionSummary(String userId, String id) throws IOException {
    ReentrantLock lock = userLock(userId); lock.lock();
    try { return history.summary(requireSession(userId, id)).text(); }
    finally { lock.unlock(); }
  }

  public Map<String, Object> maintain(String userId, Instant now) throws IOException {
    ReentrantLock lock = userLock(userId); lock.lock();
    try { return history.maintain(userDirectory(userId), sessionDirectories(userId), now); }
    finally { lock.unlock(); }
  }

  public Map<String, Object> storage(String userId) throws IOException {
    long bytes = FileHistory.diskBytes(userDirectory(userId));
    return Map.of("bytes", bytes, "quotaBytes", historyProperties.userQuotaBytes(),
        "overQuota", bytes >= historyProperties.userQuotaBytes(), "archiveAfterDays", historyProperties.archiveAfterDays(),
        "retentionDays", historyProperties.retentionDays(), "deleteExpired", historyProperties.deleteExpired());
  }

  public void deleteSession(String userId, String id) throws IOException {
    ReentrantLock lock = userLock(userId); lock.lock();
    try { FileHistory.deleteTree(requireSession(userId, id)); } finally { lock.unlock(); }
  }

  public void exportSession(String userId, String id, java.io.OutputStream output) throws IOException {
    ReentrantLock lock = userLock(userId); lock.lock();
    try { history.export(requireSession(userId, id), output); } finally { lock.unlock(); }
  }

  public void requireExistingSession(String userId, String id) throws IOException { requireSession(userId, id); }

  private List<Path> sessionDirectories(String userId) throws IOException {
    Path directory = userDirectory(userId).resolve("sessions");
    if (!Files.isDirectory(directory)) return List.of();
    try (var paths = Files.list(directory)) {
      return paths.filter(path -> Files.isDirectory(path) && !Files.isSymbolicLink(path)
          && SESSION_ID.matcher(path.getFileName().toString()).matches()).toList();
    }
  }

  private Path userDirectory(String userId) {
    user(userId);
    Path directory = root.resolve("users").resolve(userId);
    if (Files.isSymbolicLink(directory)) throw new ApiRequestException(HttpStatus.BAD_REQUEST, "Invalid user storage directory");
    return directory;
  }

  private Path sessionDirectory(String userId, String id) {
    if (id == null || !SESSION_ID.matcher(id).matches()) throw new ApiRequestException(HttpStatus.BAD_REQUEST, "Invalid session ID");
    Path directory = userDirectory(userId).resolve("sessions").resolve(id);
    if (Files.isSymbolicLink(directory)) throw new ApiRequestException(HttpStatus.BAD_REQUEST, "Invalid session storage directory");
    return directory;
  }

  private Path requireSession(String userId, String id) {
    Path directory = sessionDirectory(userId, id);
    if (!Files.isRegularFile(directory.resolve("session.json")))
      throw new ApiRequestException(HttpStatus.NOT_FOUND, "Conversation not found for this user");
    return directory;
  }

  private static String read(Path path) throws IOException { return Files.exists(path) ? Files.readString(path) : ""; }

  static String excerpt(String text, int maxChars) {
    if (text.length() <= maxChars) return text;
    int end = maxChars;
    if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
    return text.substring(0, end) + "…";
  }

  static void writeAtomic(Path file, String content) throws IOException {
    Files.createDirectories(file.getParent());
    Path temporary = Files.createTempFile(file.getParent(), ".write-", ".tmp");
    try {
      Files.writeString(temporary, content);
      try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
      catch (AtomicMoveNotSupportedException error) { throw new IOException("Agent memory storage requires atomic file replacement", error); }
    } finally { Files.deleteIfExists(temporary); }
  }

  @Override @PreDestroy
  public void close() throws IOException {
    maintenance.shutdownNow();
    try {
      if (!maintenance.awaitTermination(30, TimeUnit.SECONDS)) throw new IOException("History maintenance has not stopped; storage lock retained");
    } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException("Interrupted while closing history storage", error); }
    if (processLock.isValid()) processLock.release(); lockChannel.close();
  }
}
