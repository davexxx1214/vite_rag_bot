package com.example.ragbot;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
@ConditionalOnProperty(name = "rag.build-index", havingValue = "false", matchIfMissing = true)
public final class AgentService {
  @FunctionalInterface
  public interface EventSink {
    void write(Map<String, Object> event) throws IOException;
  }

  private record ActiveRun(String sessionId, RunControl control) {}
  private final Map<String, ActiveRun> active = new ConcurrentHashMap<>();
  private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(
      Thread.ofPlatform().daemon(true).name("agent-deadlines").factory());
  private final AgentProperties properties;
  private final RagProperties rag;
  private final MemoryStore store;
  private final ContextManager context;
  private final ToolRegistry tools;
  private final Model model;
  private final ObjectMapper mapper;
  private final SoulInstructions soul;

  public AgentService(AgentProperties properties, RagProperties rag, MemoryStore store,
      ContextManager context, ToolRegistry tools, Model model, ObjectMapper mapper, SoulInstructions soul) {
    this.properties = properties;
    this.rag = rag;
    this.store = store;
    this.context = context;
    this.tools = tools;
    this.model = model;
    this.mapper = mapper;
    this.soul = soul;
  }

  public void run(String userId, String sessionId, String message, EventSink eventSink)
      throws IOException, InterruptedException {
    MockFinanceService.User user = store.user(userId);
    store.requireExistingSession(userId, sessionId);
    ReentrantLock lock = acquire(userId);
    RunControl control = new RunControl(properties.requestTimeoutSeconds());
    ActiveRun activeRun = new ActiveRun(sessionId, control);
    active.put(userId, activeRun);
    var timer = deadlines.schedule(control::cancel, properties.requestTimeoutSeconds(), TimeUnit.SECONDS);
    var workTimer = deadlines.schedule(control::finishWork,
        properties.requestTimeoutSeconds() - properties.finalAnswerReserveSeconds(), TimeUnit.SECONDS);
    Runnable enterFinal = () -> workTimer.cancel(false);
    var listener = new NdjsonAgentListener(eventSink, tools, mapper);
    var progress = new AgentProgress(listener);
    var state = new AgentRun();
    var history = new AgentHistoryRecorder(store, userId, sessionId, message, rag.chatModel(), state);
    var input = new AgentContext(properties, store, context, tools, soul, user, sessionId, progress);
    try {
      long started = progress.start("memory-load", "正在读取用户记忆与会话历史…", "");
      history.begin();
      input.load(control);
      progress.end("memory-load", started, true);
      var answer = new AgentLoop(properties, rag, model, tools, mapper)
          .run(message, user, sessionId, input, state, progress, control, enterFinal);
      history.save(answer);
      input.afterAnswer(answer.completed(), control);
      progress.emit(new AgentEvent.Done(!answer.completed(), state.stopReason, state.toolCalls));
    } catch (IOException | InterruptedException | RuntimeException error) {
      Thread.interrupted();
      if (!history.stored() && history.started() && !state.successfulResults.isEmpty()
          && !(error instanceof ApiRequestException) && (!control.cancelled() || control.timedOut())
          && !listener.disconnected()) {
        String reason = control.workExpired() ? "query_time_budget" : "execution_failure";
        var answer = new AgentRecovery(properties, rag, model, context, tools, mapper)
            .recover(message, input, state, progress, control, enterFinal);
        if (!control.cancelled() || control.timedOut()) {
          history.save(answer);
          progress.emit(new AgentEvent.AnswerReset());
          progress.emit(new AgentEvent.Delta(answer.text()));
          progress.emit(new AgentEvent.Done(true, reason, state.toolCalls));
          return;
        }
      }
      history.failed(error);
      throw error;
    } finally {
      timer.cancel(false);
      workTimer.cancel(false);
      active.remove(userId, activeRun);
      lock.unlock();
      Thread.interrupted();
    }
  }

  public boolean cancel(String userId, String sessionId) throws IOException {
    store.requireExistingSession(userId, sessionId);
    ActiveRun run = active.get(userId);
    if (run == null || !run.sessionId().equals(sessionId)) return false;
    run.control().cancel();
    return true;
  }

  public String compact(String userId, String sessionId) throws IOException, InterruptedException {
    MockFinanceService.User user = store.user(userId);
    store.requireExistingSession(userId, sessionId);
    ReentrantLock lock = acquire(userId);
    try {
      context.prepare(userId, sessionId, instructions(user, sessionId), tools.definitions(), List.of(), true,
          new RunControl(properties.requestTimeoutSeconds()));
      return store.sessionSummary(userId, sessionId);
    } finally {
      lock.unlock();
    }
  }

  private ReentrantLock acquire(String userId) {
    ReentrantLock lock = store.runLock(userId);
    if (!lock.tryLock()) throw new ApiRequestException(HttpStatus.CONFLICT, "Another request is active for this user");
    return lock;
  }

  private String instructions(MockFinanceService.User user, String sessionId) throws IOException {
    return new AgentContext(properties, store, context, tools, soul, user, sessionId, new AgentProgress(event -> {})).instructions();
  }

  @PreDestroy
  public void close() {
    active.values().forEach(run -> run.control().cancel());
    deadlines.shutdownNow();
  }
}
