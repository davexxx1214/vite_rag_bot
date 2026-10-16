package com.example.ragbot;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Parallel contiguous reads, serial write barriers, ordered results and exact executed-call budgets. */
final class AgentToolExecutor {
  private static final Logger LOGGER = LoggerFactory.getLogger(AgentToolExecutor.class);
  private final AgentProperties properties;
  private final ToolRegistry tools;
  private final ObjectMapper mapper;

  private record Call(String id, String name, String arguments, boolean admitted, long started) {}
  private record Outcome(AgentTool.Result result, JsonNode error, Exception fatal, long elapsedMs) {}
  private record Batch(List<Outcome> outcomes, InterruptedException interrupted) {}

  AgentToolExecutor(AgentProperties properties, ToolRegistry tools, ObjectMapper mapper) {
    this.properties = properties;
    this.tools = tools;
    this.mapper = mapper;
  }

  void execute(List<JsonNode> calls, MockFinanceService.User user, String sessionId, List<JsonNode> current,
      AgentRun state, AgentProgress progress, RunControl control) throws IOException, InterruptedException {
    int roundCalls = 0;
    for (int offset = 0; offset < calls.size();) {
      control.check();
      int end = offset + 1;
      if (readOnly(calls.get(offset))) while (end < calls.size() && readOnly(calls.get(end))) end++;
      List<Call> batch = new ArrayList<>();
      for (JsonNode node : calls.subList(offset, end)) {
        String id = node.path("call_id").asString("");
        if (id.isBlank() || !state.callIds.add(id)) throw new IOException("Invalid or duplicate model tool-call ID");
        String name = node.path("name").asString("");
        String args = node.path("arguments").asString("");
        boolean admitted = roundCalls < properties.maxToolCallsPerRound() && state.toolCalls < properties.maxToolCalls();
        if (state.inputRequest != null) admitted = false;
        if (admitted) { roundCalls++; state.toolCalls++; }
        else if (state.inputRequest == null) state.stopReason = "tool_call_budget";
        long started = System.nanoTime();
        progress.emit(new AgentEvent.ToolStart(id, name, args));
        batch.add(new Call(id, name, args, admitted, started));
      }
      var scope = new AgentTool.Scope(user, sessionId, 0, control, progress::emit);
      List<Outcome> outcomes = new ArrayList<>();
      Exception fatal = null;
      if (batch.size() == 1) outcomes.add(invoke(batch.getFirst(), scope));
      else {
        var completed = reads(batch, scope);
        outcomes.addAll(completed.outcomes());
        fatal = completed.interrupted();
      }
      List<AgentEvent> events = new ArrayList<>();
      for (int i = 0; i < batch.size(); i++) {
        Call call = batch.get(i);
        Outcome outcome = outcomes.get(i);
        if (outcome.fatal() != null) { if (fatal == null) fatal = outcome.fatal(); continue; }
        long completing = System.nanoTime();
        JsonNode output = outcome.error();
        boolean ok = output == null;
        if (ok) {
          try {
            // Workflow receipts/questions are already bounded and journaled. A committed
            // write must not become a failure because citation/payload postprocessing fails.
            var result = List.of("update_portfolio", "check_edit_operation", "request_user_input").contains(call.name())
                ? outcome.result() : tools.complete(call.name(), outcome.result(),
                    new AgentTool.Scope(user, sessionId, state.sources.size(), control));
            output = result.output();
            if (List.of("update_portfolio", "check_edit_operation").contains(call.name())) {
              AgentEvent.EditResult view = state.captureEdit(output);
              if (view != null) events.add(view);
            }
            if (output.has("error")) ok = false;
            if (output.path("status").asString().equals("waiting_for_user")) {
              state.inputRequest = output.path("inputRequest"); state.stopReason = "waiting_for_user";
              events.add(new AgentEvent.UserInputRequired(state.inputRequest));
            }
            if (ok) state.successfulResults.add(output);
            String detail = ToolLabels.detail(mapper, call.name(), call.arguments());
            if (state.completedQueries.size() < 12) state.completedQueries.add(MemoryStore.excerpt(
                call.name() + (detail.isBlank() ? "" : " (" + detail + ")"), 240));
            state.sources.addAll(result.sources());
            if (!result.sources().isEmpty()) events.add(new AgentEvent.Sources(List.copyOf(state.sources)));
          } catch (ApiRequestException error) { output = requestError(error); ok = false; }
          catch (IOException | RuntimeException error) {
            try { control.check(); output = toolError(call.name()); ok = false; }
            catch (IOException stopped) { if (fatal == null) fatal = stopped; continue; }
          }
        }
        JsonNode item = mapper.valueToTree(Map.of("type", "function_call_output", "call_id", call.id(),
            "output", mapper.writeValueAsString(output)));
        current.add(item);
        state.transcript.add(item);
        events.add(new AgentEvent.ToolEnd(call.id(), call.name(), ok, outcome.elapsedMs() + AgentProgress.elapsed(completing)));
      }
      // Preserve all successful sibling results before a disconnect/deadline escapes to recovery.
      for (var event : events) progress.emit(event);
      if (fatal instanceof InterruptedException interrupted) throw interrupted;
      if (fatal instanceof IOException io) throw io;
      if (fatal != null) throw new IOException("Tool execution stopped", fatal);
      offset = end;
    }
  }

  private boolean readOnly(JsonNode call) {
    AgentTool tool = tools.tool(call.path("name").asString(""));
    return tool != null && tool.readOnly();
  }

  private Batch reads(List<Call> batch, AgentTool.Scope scope) {
    var workers = Executors.newVirtualThreadPerTaskExecutor();
    List<Outcome> outcomes = new ArrayList<>();
    InterruptedException interrupted = null;
    try {
      List<Future<Outcome>> pending = new ArrayList<>();
      for (Call call : batch) pending.add(workers.submit(() -> invoke(call, scope)));
      for (var future : pending) {
        boolean collected = false;
        while (!collected) {
          try { outcomes.add(future.get()); collected = true; }
          catch (ExecutionException error) {
            outcomes.add(new Outcome(null, null, new IOException("Tool worker failed", error.getCause()), 0));
            collected = true;
          } catch (InterruptedException error) {
            interrupted = error;
            scope.control().cancel();
          }
        }
      }
    } finally {
      // ExecutorService.close() may interrupt workers when its caller is interrupted. Instead,
      // stop network work and join without interrupting a worker's payload/file writes.
      workers.shutdown();
      while (!workers.isTerminated()) {
        try { workers.awaitTermination(1, TimeUnit.SECONDS); }
        catch (InterruptedException error) { interrupted = error; scope.control().cancel(); }
      }
    }
    return new Batch(outcomes, interrupted);
  }

  private Outcome invoke(Call call, AgentTool.Scope scope) {
    if (!call.admitted()) return outcome(call, null, requestError(new ApiRequestException(HttpStatus.TOO_MANY_REQUESTS,
        "Call deferred or budget exhausted; this call was not executed.")), null);
    try {
      if (call.arguments().length() > 8_000) throw new ApiRequestException(HttpStatus.BAD_REQUEST, "Tool arguments are too large");
      return outcome(call, tools.executeRaw(call.name(), mapper.readTree(call.arguments()), scope), null, null);
    } catch (ApiRequestException error) { return outcome(call, null, requestError(error), null); }
    catch (InterruptedException error) { return outcome(call, null, null, error); }
    catch (IOException | RuntimeException error) {
      try { scope.control().check(); }
      catch (IOException stopped) { return outcome(call, null, null, stopped); }
      LOGGER.warn("Agent tool {} failed ({})", call.name(), error.getClass().getSimpleName());
      return outcome(call, null, toolError(call.name()), null);
    }
  }

  private Outcome outcome(Call call, AgentTool.Result result, JsonNode error, Exception fatal) {
    return new Outcome(result, error, fatal, AgentProgress.elapsed(call.started()));
  }

  private JsonNode requestError(ApiRequestException error) {
    return mapper.valueToTree(error.body());
  }
  private JsonNode toolError(String name) {
    return mapper.valueToTree(Map.of("error", "Tool failed; retry a narrower or corrected query", "tool", name));
  }
}
