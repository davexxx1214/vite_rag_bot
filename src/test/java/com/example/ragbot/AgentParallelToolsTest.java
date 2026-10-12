package com.example.ragbot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Real agent/context/history, an alternative Model implementation and coordinated read workers. */
class AgentParallelToolsTest {
  @TempDir Path root;
  private final JsonMapper mapper = new JsonMapper();

  @Test
  void readsOverlapFinishOutOfOrderAndKeepReasoningResultOrderAndCitations() throws Exception {
    try (var h = new Harness()) {
      var entered = new CountDownLatch(2);
      var secondFinished = new CountDownLatch(1);
      h.tools.register("first_read", "Read two business sources", Map.of(), true, (args, scope) -> {
        entered.countDown();
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(secondFinished.await(5, TimeUnit.SECONDS)).isTrue();
        return sourced("first", "B", 2);
      });
      h.tools.register("second_read", "Read two documentation sources", Map.of(), true, (args, scope) -> {
        entered.countDown();
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        secondFinished.countDown();
        return sourced("second", "S", 2);
      });
      JsonNode reasoning = mapper.valueToTree(Map.of("type", "reasoning", "id", "rs_parallel",
          "summary", List.of(), "encrypted_content", "opaque-parallel-reasoning"));
      h.model.turns.add(turn(List.of(reasoning, call("first_read", "first"), call("second_read", "second"))));
      h.answer("Combined findings [B1] [S3].");
      var events = h.run();
      assertThat(events.getLast()).containsEntry("type", "done").containsEntry("toolCalls", 2).containsEntry("partial", false);
      var input = h.model.requests.get(1);
      assertThat(input.toString()).contains("opaque-parallel-reasoning");
      assertThat(input.stream().filter(n -> n.path("type").asString().equals("function_call_output"))
          .map(n -> n.path("call_id").asString()).toList()).containsExactly("first", "second");
      assertThat(output(input, "first").path("excerpts").asString()).contains("[B1]", "[B2]");
      assertThat(output(input, "second").path("excerpts").asString()).contains("[S3]", "[S4]").doesNotContain("[S1]", "[S2]");
      assertThat(h.store.session("user-001", h.session).turns().getFirst().sources())
          .extracting(RagSource::citation).containsExactly("B1", "B2", "S3", "S4");
      assertThat(events.stream().filter(e -> e.get("type").equals("tool_start"))).hasSize(2);
      assertThat(events.stream().filter(e -> e.get("type").equals("tool_end"))).hasSize(2);
    }
  }

  @Test
  void memoryWritesFormBarriersAndLaterReadsObserveRememberThenForget() throws Exception {
    try (var h = new Harness()) {
      var entered = new CountDownLatch(2);
      var finished = new AtomicInteger();
      for (String name : List.of("before_one", "before_two")) {
        h.tools.register(name, "Read before writes", Map.of(), true, (args, scope) -> {
          entered.countDown();
          assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
          assertThat(h.store.memory("user-001")).isBlank();
          finished.incrementAndGet();
          return plain("before");
        });
      }
      h.tools.register("after_remember", "Observe memory write", Map.of(), true, (args, scope) -> {
        assertThat(finished.get()).isEqualTo(2);
        assertThat(h.store.memory("user-001")).contains("Chinese");
        return plain("remember observed");
      });
      h.tools.register("after_forget", "Observe memory deletion", Map.of(), true, (args, scope) -> {
        assertThat(h.store.memory("user-001")).doesNotContain("Chinese");
        return plain("forget observed");
      });
      h.model.turns.add(turn(List.of(call("before_one", "b1"), call("before_two", "b2"),
          call("remember", "save", Map.of("key", "language", "content", "Chinese", "target", "user")),
          call("after_remember", "r1"), call("forget_memory", "forget", Map.of("key", "language")),
          call("after_forget", "r2"))));
      h.answer("Memory updated and cleared.");
      assertThat(h.run().getLast()).containsEntry("partial", false).containsEntry("toolCalls", 6);
      var input = h.model.requests.get(1);
      assertThat(output(input, "save").path("saved").asBoolean()).isTrue();
      assertThat(output(input, "forget").path("forgotten").asBoolean()).isTrue();
      assertThat(h.store.memory("user-001")).doesNotContain("Chinese");
      assertThat(h.tools.tool("remember").readOnly()).isFalse();
      assertThat(h.tools.tool("forget_memory").readOnly()).isFalse();
    }
  }

  @Test
  void failedReadDoesNotDiscardItsSuccessfulSiblingOrReserveCitationNumbers() throws Exception {
    try (var h = new Harness()) {
      h.tools.register("failed_read", "Unavailable query", Map.of(), true, (args, scope) -> {
        throw new IOException("private-backend-details");
      });
      h.tools.register("good_read", "Available query", Map.of(), true, (args, scope) -> sourced("good", "B", 1));
      h.model.turns.add(turn(List.of(call("failed_read", "bad"), call("good_read", "good"))));
      h.answer("Available result [B1].");
      var events = h.run();
      assertThat(events.getLast()).containsEntry("partial", false);
      assertThat(events.toString()).doesNotContain("private-backend-details");
      var input = h.model.requests.get(1);
      assertThat(output(input, "bad").has("error")).isTrue();
      assertThat(output(input, "good").path("sources").get(0).path("citation").asString()).isEqualTo("B1");
    }
  }

  @Test
  void parallelBatchEnforcesBudgetBeforeStartingWorkersAndPairsSkippedCalls() throws Exception {
    try (var h = new Harness()) {
      var executed = new AtomicInteger();
      h.tools.register("budget_read", "Count actual calls", Map.of(), true, (args, scope) -> {
        executed.incrementAndGet(); return plain("data");
      });
      List<JsonNode> calls = new ArrayList<>();
      for (int i = 0; i < 10; i++) calls.add(call("budget_read", "c" + i));
      h.model.turns.add(turn(calls));
      h.answer("Results within the budget.");
      var events = h.run();
      assertThat(executed.get()).isEqualTo(8);
      assertThat(events.getLast()).containsEntry("toolCalls", 8).containsEntry("partial", true);
      assertThat(h.model.allowTools).containsExactly(true, false);
      var input = h.model.requests.get(1);
      assertThat(output(input, "c7").path("value").asString()).isEqualTo("data");
      assertThat(output(input, "c8").path("status").asInt()).isEqualTo(429);
      assertThat(output(input, "c9").path("status").asInt()).isEqualTo(429);
    }
  }

  @Test
  void queryDeadlineClosesBlockedReadAndPreservesAFasterSiblingForFinalSynthesis() throws Exception {
    try (var h = new Harness(); var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      var attached = new CountDownLatch(1);
      var fastFinished = new CountDownLatch(1);
      h.tools.register("blocked_read", "Blocked network work", Map.of(), true, (args, scope) -> {
        var closed = new CountDownLatch(1);
        try (var stream = new java.io.ByteArrayInputStream(new byte[0]) {
          @Override public void close() { closed.countDown(); }
        }) {
          scope.control().attach(stream);
          attached.countDown();
          workers.submit(() -> {
            assertThat(fastFinished.await(5, TimeUnit.SECONDS)).isTrue();
            scope.control().finishWork(); return null;
          });
          try {
            assertThat(closed.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(Thread.currentThread().isInterrupted()).isFalse();
            scope.control().check();
            throw new AssertionError("Expired worker must stop");
          } finally { scope.control().detach(); }
        }
      });
      h.tools.register("fast_read", "Successful sibling", Map.of(), true, (args, scope) -> {
        assertThat(attached.await(5, TimeUnit.SECONDS)).isTrue();
        fastFinished.countDown(); return sourced("pf-fast", "B", 1);
      });
      h.model.turns.add(turn(List.of(call("blocked_read", "blocked"), call("fast_read", "fast"))));
      h.answer("Retrieved pf-fast [B1]; further work was stopped.");
      var events = h.run();
      assertThat(events.getLast()).containsEntry("partial", true).containsEntry("reason", "query_time_budget");
      assertThat(h.model.allowTools).containsExactly(true, false);
      assertThat(h.model.requests.get(1).toString()).contains("pf-fast").doesNotContain("function_call");
      var saved = h.store.session("user-001", h.session).turns().getFirst();
      assertThat(saved.completed()).isFalse();
      assertThat(saved.output().toString()).contains("pf-fast", "fast");
      assertThat(saved.sources()).extracting(RagSource::citation).containsExactly("B1");
      h.answer("Retry works.");
      assertThat(h.run().getLast()).containsEntry("partial", false);
    }
  }

  @Test
  void callerInterruptionClosesNetworkButLetsAllWorkerFileWritesFinishBeforeUnlocking() throws Exception {
    try (var h = new Harness(); var callers = Executors.newVirtualThreadPerTaskExecutor()) {
      var entered = new CountDownLatch(2);
      var caller = new java.util.concurrent.atomic.AtomicReference<Thread>();
      for (String name : List.of("logging_one", "logging_two")) {
        h.tools.register(name, "Read with a local request log", Map.of(), true, (args, scope) -> {
          var closed = new CountDownLatch(1);
          try (var stream = new java.io.ByteArrayInputStream(new byte[0]) {
            @Override public void close() { closed.countDown(); }
          }; var file = java.nio.channels.FileChannel.open(root.resolve(name + ".log"),
              java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE)) {
            scope.control().attach(stream);
            file.write(java.nio.ByteBuffer.wrap(new byte[] {1}));
            entered.countDown();
            try {
              assertThat(closed.await(5, TimeUnit.SECONDS)).isTrue();
              assertThat(Thread.currentThread().isInterrupted()).isFalse();
              file.write(java.nio.ByteBuffer.wrap(new byte[] {2, 3}));
              file.force(true);
              return plain(name);
            } finally { scope.control().detach(); }
          }
        });
      }
      h.model.turns.add(turn(List.of(call("logging_one", "one"), call("logging_two", "two"))));
      var pending = callers.submit(() -> {
        caller.set(Thread.currentThread());
        try { h.run(); throw new AssertionError("Interrupted caller must stop"); }
        catch (InterruptedException error) { return error; }
      });
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
      caller.get().interrupt();
      assertThat(pending.get(5, TimeUnit.SECONDS)).isInstanceOf(InterruptedException.class);
      for (String name : List.of("logging_one", "logging_two"))
        assertThat(java.nio.file.Files.readAllBytes(root.resolve(name + ".log"))).containsExactly(1, 2, 3);
      assertThat(h.store.session("user-001", h.session).turns().getFirst().output().toString())
          .contains("logging_one", "logging_two", "function_call_output");
      h.answer("Retry works.");
      assertThat(h.run().getLast()).containsEntry("partial", false);
    }
  }

  @Test
  void disconnectDoesNotLoseCompletedSiblingResultsOrStartAnotherModelRequest() throws Exception {
    try (var h = new Harness()) {
      for (String name : List.of("read_one", "read_two"))
        h.tools.register(name, "Read", Map.of(), true, (args, scope) -> sourced(name, "B", 1));
      h.model.turns.add(turn(List.of(call("read_one", "one"), call("read_two", "two"))));
      assertThatThrownBy(() -> h.agent.run("user-001", h.session, "Read", event -> {
        if (event.get("type").equals("sources")) throw new IOException("Disconnected");
      })).isInstanceOf(IOException.class).hasMessageContaining("Disconnected");
      assertThat(h.model.requests).hasSize(1);
      var saved = h.store.session("user-001", h.session).turns().getFirst();
      assertThat(saved.completed()).isFalse();
      assertThat(saved.output().toString()).contains("read_one", "read_two", "function_call_output");
      assertThat(saved.sources()).extracting(RagSource::citation).containsExactly("B1", "B2");
    }
  }

  private JsonNode call(String name, String id) { return call(name, id, Map.of()); }
  private JsonNode call(String name, String id, Map<String, Object> args) {
    return mapper.valueToTree(Map.of("type", "function_call", "name", name, "call_id", id,
        "arguments", mapper.writeValueAsString(args)));
  }
  private Model.Turn turn(List<JsonNode> output) { return new Model.Turn(output, "", 100, 10); }
  private AgentTool.Result plain(String value) { return new AgentTool.Result(mapper.valueToTree(Map.of("value", value)), List.of()); }
  private AgentTool.Result sourced(String value, String prefix, int count) {
    var sources = new ArrayList<RagSource>();
    String excerpts = value;
    for (int i = 1; i <= count; i++) {
      sources.add(new RagSource(prefix + i, value, "Test", "/" + value + "/" + i, 1));
      excerpts += " [" + prefix + i + "]";
    }
    return new AgentTool.Result(mapper.valueToTree(Map.of("value", value, "excerpts", excerpts, "sources", sources)), sources);
  }
  private JsonNode output(List<JsonNode> input, String id) {
    return mapper.readTree(input.stream().filter(n -> n.path("type").asString().equals("function_call_output")
        && n.path("call_id").asString().equals(id)).findFirst().orElseThrow().path("output").asString());
  }

  private final class Harness implements AutoCloseable {
    final ScriptedModel model = new ScriptedModel();
    final MemoryStore store;
    final ToolRegistry tools;
    final AgentService agent;
    final String session;

    Harness() throws Exception {
      var props = AgentTestSupport.properties(root);
      store = new MemoryStore(props, mapper, new MockFinanceService());
      var context = new ContextManager(props, AgentTestSupport.rag(), store, model, mapper);
      tools = new ToolRegistry(mapper, props, store, context, mock(FinanceApiClient.class),
          mock(RagChatService.class), new BusinessTools("classpath:tools.yaml"));
      agent = new AgentService(props, AgentTestSupport.rag(), store, context, tools, model, mapper, new SoulInstructions("classpath:SOUL.md"));
      session = store.createSession("user-001");
    }
    void answer(String text) { model.turns.add(new Model.Turn(List.of(AgentTestSupport.answer(mapper, text)), text, 100, 10)); }
    List<Map<String, Object>> run() throws Exception {
      List<Map<String, Object>> events = new ArrayList<>();
      agent.run("user-001", session, "Read financial data", events::add);
      return events;
    }
    public void close() throws IOException { agent.close(); store.close(); }
  }

  private static final class ScriptedModel implements Model {
    final List<Turn> turns = new ArrayList<>();
    final List<List<JsonNode>> requests = new ArrayList<>();
    final List<Boolean> allowTools = new ArrayList<>();

    public Turn respond(String instructions, List<JsonNode> input, List<AgentTool> tools,
        boolean allowed, TextSink sink, RunControl control) throws IOException {
      control.check();
      requests.add(List.copyOf(input));
      allowTools.add(allowed);
      Turn turn = turns.get(requests.size() - 1);
      if (!turn.text().isBlank()) sink.accept(turn.text());
      return turn;
    }
    public String summarize(String instructions, String input, int maxTokens, RunControl control) {
      return "{\"memories\":[]}";
    }
  }
}
