package com.example.ragbot;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** X-Mock-User-Id is explicitly a demo identity selector, not authentication. */
@RestController
@RequestMapping("/api/agent")
@ConditionalOnProperty(name = "rag.build-index", havingValue = "false", matchIfMissing = true)
public class AgentController {
  private final MemoryStore store;
  private final ContextManager context;
  private final AgentService agent;
  private final AgentProperties properties;
  private final MockFinanceService users;

  public AgentController(MemoryStore store, ContextManager context, AgentService agent,
      AgentProperties properties, MockFinanceService users) {
    this.store = store;
    this.context = context;
    this.agent = agent;
    this.properties = properties;
    this.users = users;
  }

  @GetMapping("/users")
  public List<MockFinanceService.User> users() {
    return users.users();
  }

  @GetMapping("/sessions")
  public List<Map<String, Object>> sessions(@RequestHeader("X-Mock-User-Id") String userId,
      @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "50") int limit) throws IOException {
    return store.sessions(userId, offset, limit);
  }

  @PostMapping("/sessions")
  public Map<String, String> createSession(@RequestHeader("X-Mock-User-Id") String userId) throws IOException {
    return Map.of("id", store.createSession(userId));
  }

  @GetMapping("/sessions/{id}")
  public Map<String, Object> session(@RequestHeader("X-Mock-User-Id") String userId, @PathVariable String id,
      @RequestParam(required = false) Integer offset, @RequestParam(defaultValue = "50") int limit)
      throws IOException {
    var page = store.page(userId, id, offset, limit, false);
    List<Map<String, Object>> messages = new ArrayList<>();
    for (MemoryStore.StoredTurn turn : page.turns()) {
      messages.add(Map.of("id", id + "-" + turn.index() + "-user", "role", "user", "content", turn.message()));
      messages.add(Map.of("id", id + "-" + turn.index() + "-assistant", "role", "assistant",
          "content", turn.answer(), "sources", turn.sources(), "partial", !turn.completed()));
    }
    Map<String, Object> result = new java.util.LinkedHashMap<>();
    result.put("id", id); result.put("messages", messages); result.put("summary", store.sessionSummary(userId, id));
    result.put("offset", page.offset()); result.put("nextOffset", page.nextOffset()); result.put("total", page.total());
    return result;
  }

  @PostMapping("/sessions/{id}/cancel")
  public Map<String, Boolean> cancel(@RequestHeader("X-Mock-User-Id") String userId, @PathVariable String id)
      throws IOException {
    return Map.of("cancelled", agent.cancel(userId, id));
  }

  @PostMapping("/sessions/{id}/compact")
  public Map<String, String> compact(@RequestHeader("X-Mock-User-Id") String userId, @PathVariable String id)
      throws IOException, InterruptedException {
    return Map.of("summary", agent.compact(userId, id));
  }

  @GetMapping("/memory")
  public Map<String, Object> memory(@RequestHeader("X-Mock-User-Id") String userId) throws IOException {
    var memory = store.coreMemory(userId);
    return Map.of("content", memory.combined(), "user", memory.user(), "notes", memory.notes(),
        "estimatedTokens", TokenBudget.estimate(memory.combined()), "budgetTokens", properties.memoryBudgetTokens());
  }

  @PutMapping("/memory")
  public Map<String, Object> updateMemory(@RequestHeader("X-Mock-User-Id") String userId,
      @RequestBody MemoryRequest request) throws IOException, InterruptedException {
    ReentrantLock lock = acquire(userId);
    try {
      var candidate = request != null && request.user() != null && request.notes() != null
          ? new MemoryStore.CoreMemory(request.user(), request.notes())
          : new MemoryStore.CoreMemory(request == null ? null : request.content(), "");
      context.replaceCoreMemory(userId, candidate, new RunControl(properties.requestTimeoutSeconds()));
      store.skipOldMemoryCandidates(userId);
      return memory(userId);
    } finally {
      lock.unlock();
    }
  }

  @DeleteMapping("/memory")
  public Map<String, String> clearMemory(@RequestHeader("X-Mock-User-Id") String userId) throws IOException {
    ReentrantLock lock = acquire(userId);
    try {
      store.memory(userId, "");
      store.skipOldMemoryCandidates(userId);
      return Map.of("content", "");
    } finally {
      lock.unlock();
    }
  }

  private ReentrantLock acquire(String userId) {
    ReentrantLock lock = store.runLock(userId);
    if (!lock.tryLock()) throw new ApiRequestException(HttpStatus.CONFLICT, "Another request is active for this user");
    return lock;
  }

  @GetMapping("/history")
  public FileHistory.SearchPage history(@RequestHeader("X-Mock-User-Id") String userId,
      @RequestParam String query, @RequestParam(defaultValue = "0") int offset,
      @RequestParam(defaultValue = "5") int limit) throws IOException {
    return store.searchHistory(userId, query, offset, limit);
  }

  @GetMapping("/storage")
  public Map<String, Object> storage(@RequestHeader("X-Mock-User-Id") String userId) throws IOException {
    return store.storage(userId);
  }

  @GetMapping(value = "/sessions/{id}/export", produces = "application/x-ndjson")
  public org.springframework.http.ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody> export(
      @RequestHeader("X-Mock-User-Id") String userId, @PathVariable String id) throws IOException {
    store.requireExistingSession(userId, id);
    return org.springframework.http.ResponseEntity.ok().header("Content-Disposition", "attachment; filename=\"" + id + ".jsonl\"")
        .header("Cache-Control", "no-store").body(output -> store.exportSession(userId, id, output));
  }

  @DeleteMapping("/sessions/{id}")
  public Map<String, Boolean> delete(@RequestHeader("X-Mock-User-Id") String userId, @PathVariable String id) throws IOException {
    ReentrantLock lock = acquire(userId);
    try { store.deleteSession(userId, id); return Map.of("deleted", true); }
    finally { lock.unlock(); }
  }

  public record MemoryRequest(String content, String user, String notes) {}
}
