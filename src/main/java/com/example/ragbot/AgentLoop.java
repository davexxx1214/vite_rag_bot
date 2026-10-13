package com.example.ragbot;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Model/tool loop; history commits, user locks and HTTP event formatting live outside. */
final class AgentLoop {
  interface Context {
    String instructions() throws IOException;
    List<JsonNode> prepare(String instructions, List<JsonNode> current, int keepFrom,
        boolean usagePressure, int round, RunControl control) throws IOException, InterruptedException;
  }

  private final AgentProperties properties;
  private final RagProperties rag;
  private final Model model;
  private final ToolRegistry tools;
  private final ObjectMapper mapper;

  AgentLoop(AgentProperties properties, RagProperties rag, Model model, ToolRegistry tools, ObjectMapper mapper) {
    this.properties = properties;
    this.rag = rag;
    this.model = model;
    this.tools = tools;
    this.mapper = mapper;
  }

  AgentRun.Answer run(String message, MockFinanceService.User user, String sessionId, Context context,
      AgentRun state, AgentProgress progress, RunControl control, Runnable enterFinal)
      throws IOException, InterruptedException {
    List<JsonNode> current = new ArrayList<>();
    current.add(mapper.valueToTree(java.util.Map.of("role", "user", "content", message)));
    int keepFrom = 1;
    boolean usagePressure = false;
    var executor = new AgentToolExecutor(properties, tools, mapper);
    for (int round = 0; round < properties.maxRounds(); round++) {
      boolean allowTools = round < properties.maxRounds() - 1 && state.toolCalls < properties.maxToolCalls()
          && state.stopReason.isEmpty() && !control.workExpired();
      if (!allowTools) {
        if (state.stopReason.isEmpty()) state.stopReason = control.workExpired() ? "query_time_budget" : "task_budget";
        enterFinal.run();
        control.beginFinal();
        state.finalRequested = true;
        progress.start("budget", "查询预算已用完，正在用已有结果整理回答…",
            "已执行 " + state.toolCalls + " / " + properties.maxToolCalls() + " 次工具调用");
      }
      control.check();
      String instructions = context.instructions();
      var input = context.prepare(instructions, current, keepFrom, usagePressure, round, control);
      progress.emit(new AgentEvent.AnswerReset());
      if (!allowTools) instructions += "\nTool budget reached. Answer from available results and clearly state anything still unresolved.";
      String id = "model-" + round;
      long started = progress.start(id, !allowTools ? "正在整理最终回答…"
          : round == 0 ? "正在分析请求并选择工具…" : "正在分析查询结果…", "第 " + (round + 1) + " 轮");
      boolean[] textStarted = {false};
      var response = model.respond(instructions, input, tools.tools(), allowTools, delta -> {
        control.check();
        if (!textStarted[0]) { progress.update(id, "正在生成回答…"); textStarted[0] = true; }
        progress.emit(new AgentEvent.Delta(delta));
      }, control);
      progress.end(id, started, true);
      keepFrom = current.size();
      // Preserve opaque reasoning and all provider output items in the active tool loop.
      current.addAll(response.output());
      state.transcript.addAll(response.output());
      usagePressure = response.inputTokens() + response.outputTokens() >= properties.compactThreshold(rag.chatModel());
      var calls = response.calls();
      if (calls.isEmpty()) {
        if (response.text().isBlank()) throw new IOException("The model returned an empty answer");
        String notice = state.stopReason.isEmpty() ? "" : AgentRecovery.partialNotice(message);
        if (!notice.isEmpty()) progress.emit(new AgentEvent.Delta(notice));
        return new AgentRun.Answer(response.text() + notice, state.stopReason.isEmpty());
      }
      if (!allowTools || calls.size() > 128) throw new IOException("Model returned an invalid final response");
      progress.emit(new AgentEvent.AnswerReset());
      executor.execute(calls, user, sessionId, current, state, progress, control);
      if (state.inputRequest != null) {
        String text = state.inputRequest.path("question").asString();
        progress.emit(new AgentEvent.Delta(text));
        return new AgentRun.Answer(text, true);
      }
    }
    throw new IOException("Agent exhausted its model-round budget");
  }
}
