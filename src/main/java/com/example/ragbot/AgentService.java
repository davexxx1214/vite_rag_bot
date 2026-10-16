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
    run(userId, sessionId, message, null, eventSink);
  }

  public void run(String userId, String sessionId, String message, EditingWorkflow.InputAnswer inputAnswer, EventSink eventSink)
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
    boolean[] released = {false};
    Runnable releaseRun = () -> {
      if (released[0]) return;
      timer.cancel(false); workTimer.cancel(false);
      active.remove(userId, activeRun); lock.unlock(); released[0] = true;
    };
    var listener = new NdjsonAgentListener(eventSink, tools, mapper);
    var progress = new AgentProgress(listener);
    var state = new AgentRun();
    var history = new AgentHistoryRecorder(store, userId, sessionId, message, rag.chatModel(), state);
    var input = new AgentContext(properties, store, context, tools, soul, user, sessionId, progress);
    try {
      String workMessage = message;
      EditingWorkflow.Resume resume = null;
      if (inputAnswer != null) tools.editing().validateAnswer(new AgentTool.Scope(user, sessionId, 0, control), inputAnswer);
      else tools.editing().start(userId, sessionId, message);
      long started = progress.start("memory-load", "正在读取用户记忆与会话历史…", "");
      history.begin();
      if (inputAnswer != null) resume = tools.editing().answer(new AgentTool.Scope(user, sessionId, 0, control, progress::emit), inputAnswer);
      if (resume != null) {
        workMessage = resume.message();
        if (resume.result() != null) {
          var result = resume.result();
          AgentEvent.EditResult view = state.captureEdit(result);
          if (view != null) progress.emit(view);
          state.successfulResults.add(result);
          state.transcript.add(mapper.valueToTree(Map.of("role", "assistant", "content", "Edit workflow result: " + result)));
          if (result.path("status").asString().equals("waiting_for_user")) {
            state.inputRequest = result.path("inputRequest"); state.stopReason = "waiting_for_user";
            progress.emit(new AgentEvent.UserInputRequired(state.inputRequest));
          }
          String text = editAnswer(result);
          progress.emit(new AgentEvent.Delta(text));
          history.save(new AgentRun.Answer(text, !result.has("error")));
          progress.end("memory-load", started, true);
          releaseRun.run();
          progress.emit(new AgentEvent.Done(result.has("error"), state.stopReason, state.toolCalls));
          return;
        }
      }
      input.load(control);
      progress.end("memory-load", started, true);
      var answer = new AgentLoop(properties, rag, model, tools, mapper)
          .run(workMessage, user, sessionId, input, state, progress, control, enterFinal);
      history.save(answer);
      if (state.inputRequest == null) input.afterAnswer(answer.completed(), control);
      releaseRun.run();
      progress.emit(new AgentEvent.Done(!answer.completed() && state.inputRequest == null, state.stopReason, state.toolCalls));
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
          releaseRun.run();
          progress.emit(new AgentEvent.Done(true, reason, state.toolCalls));
          return;
        }
      }
      history.failed(error);
      throw error;
    } finally {
      releaseRun.run();
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

  tools.jackson.databind.JsonNode inputRequest(String userId, String sessionId) throws IOException {
    return tools.editing().publicQuestion(userId, sessionId);
  }

  private static String editAnswer(tools.jackson.databind.JsonNode result) {
    String status = result.path("status").asString();
    if (status.equals("waiting_for_user")) return result.path("inputRequest").path("question").asString();
    if (status.equals("cancelled")) return "已取消本次修改。";
    if (status.equals("verification_deferred")) return "已暂停核对。此前的修改可能已经生效，取消等待不会撤销修改；可以继续请求核对结果。";
    if (status.equals("no_change")) return "名称没有变化，无需修改。";
    if (status.equals("applied")) return "已将 " + result.path("portfolio").path("id").asString()
        + " 的名称从“" + result.path("changes").path("name").path("before").asString()
        + "”改为“" + result.path("portfolio").path("name").asString() + "”（版本 "
        + result.path("portfolio").path("version").asLong() + "）。";
    return "修改未完成：" + result.path("error").asString("请核对修改结果。");
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
