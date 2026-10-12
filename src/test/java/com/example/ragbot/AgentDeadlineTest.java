package com.example.ragbot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class AgentDeadlineTest {
  @TempDir Path root;

  @Test
  void queryDeadlineClosesBlockedWorkAndKeepsTimeForTheFinalAnswer() throws Exception {
    exerciseDeadline(false);
  }

  @Test
  void hardDeadlineReturnsQueryListWhenTheFinalModelCallAlsoBlocks() throws Exception {
    exerciseDeadline(true);
  }

  @Test
  void modelRoundCapForcesSynthesisEvenWhenToolCallsRemain() throws Exception {
    exerciseRoundCap(false);
  }

  @Test
  void finalModelToolCallReturnsShortFallbackWithoutExecutingIt() throws Exception {
    exerciseRoundCap(true);
  }

  private void exerciseRoundCap(boolean invalidFinal) throws Exception {
    var mapper = new JsonMapper();
    var props = new AgentProperties(root, null, 64_000, 48_000, 32_000, 16_000, 1_800, 8_000,
        0, 2, 8, 3_000, 30, 3, "low", 12, 5);
    var model = mock(OpenAiClient.class);
    AtomicInteger requests = new AtomicInteger();
    when(model.respond(anyString(), anyList(), anyList(), anyBoolean(), any(), any())).thenAnswer(invocation -> {
      if (requests.getAndIncrement() == 0) return new OpenAiClient.ModelResponse(List.of(mapper.valueToTree(Map.of(
          "type", "function_call", "name", "round_lookup", "call_id", "known", "arguments", "{}"))), "", 100, 10);
      assertThat((boolean) invocation.getArgument(3)).isFalse();
      if (invalidFinal) return new OpenAiClient.ModelResponse(List.of(mapper.valueToTree(Map.of(
          "type", "function_call", "name", "round_lookup", "call_id", "ignored", "arguments", "{}"))), "", 100, 10);
      ((OpenAiClient.TextSink) invocation.getArgument(4)).accept("Known portfolio data.");
      return new OpenAiClient.ModelResponse(List.of(AgentTestSupport.answer(mapper, "Known portfolio data.")),
          "Known portfolio data.", 100, 10);
    });
    try (var store = new MemoryStore(props, mapper, new MockFinanceService())) {
      var context = new ContextManager(props, AgentTestSupport.rag(), store, model, mapper);
      var tools = new ToolRegistry(mapper, props, store, context, mock(FinanceApiClient.class),
          mock(RagChatService.class), new BusinessTools("classpath:tools.yaml"));
      tools.register("round_lookup", "Known result", Map.of(), (args, scope) ->
          new ToolRegistry.Result(mapper.valueToTree(Map.of("id", "pf-round")), List.of()));
      var agent = new AgentService(props, AgentTestSupport.rag(), store, context, tools, model, mapper,
          new SoulInstructions("classpath:SOUL.md"));
      String session = store.createSession("user-001");
      List<Map<String, Object>> events = new ArrayList<>();
      try { agent.run("user-001", session, "Compare data", events::add); }
      finally { agent.close(); }
      assertThat(requests.get()).isEqualTo(2);
      assertThat(events.getLast()).containsEntry("partial", true).containsEntry("toolCalls", 1);
      assertThat(store.session("user-001", session).turns().getFirst().completed()).isFalse();
      if (invalidFinal) {
        var saved = store.session("user-001", session).turns().getFirst();
        assertThat(saved.answer()).contains("round_lookup").doesNotContain("pf-round", "```json");
        assertThat(saved.output().toString()).contains("pf-round");
      }
    }
  }

  @Test
  void disconnectAfterQueryStillPersistsTheResultAndDoesNotStartSynthesis() throws Exception {
    var mapper = new JsonMapper(); var props = AgentTestSupport.properties(root);
    var model = mock(OpenAiClient.class);
    AtomicInteger requests = new AtomicInteger();
    when(model.respond(anyString(), anyList(), anyList(), anyBoolean(), any(), any())).thenAnswer(invocation -> {
      requests.incrementAndGet();
      return new OpenAiClient.ModelResponse(List.of(mapper.valueToTree(Map.of("type", "function_call",
          "name", "disconnect_lookup", "call_id", "known", "arguments", "{}"))), "", 100, 10);
    });
    try (var store = new MemoryStore(props, mapper, new MockFinanceService())) {
      var context = new ContextManager(props, AgentTestSupport.rag(), store, model, mapper);
      var tools = new ToolRegistry(mapper, props, store, context, mock(FinanceApiClient.class),
          mock(RagChatService.class), new BusinessTools("classpath:tools.yaml"));
      tools.register("disconnect_lookup", "Known result", Map.of(), (args, scope) -> new ToolRegistry.Result(
          mapper.valueToTree(Map.of("id", "pf-disconnect")), List.of(new RagSource("B1", "Test", "Test", "/test", 1))));
      var agent = new AgentService(props, AgentTestSupport.rag(), store, context, tools, model, mapper,
          new SoulInstructions("classpath:SOUL.md"));
      String session = store.createSession("user-001");
      try {
        assertThatThrownBy(() -> agent.run("user-001", session, "Query", event -> {
          if (event.get("type").equals("sources")) throw new java.io.IOException("Disconnected");
        })).isInstanceOf(java.io.IOException.class).hasMessageContaining("Disconnected");
      } finally { agent.close(); }
      assertThat(requests.get()).isEqualTo(1);
      assertThat(store.session("user-001", session).turns().getFirst().output().toString()).contains("pf-disconnect");
      assertThat(store.session("user-001", session).turns().getFirst().completed()).isFalse();
    }
  }

  private void exerciseDeadline(boolean blockFinal) throws Exception {
    var mapper = new JsonMapper();
    var props = new AgentProperties(root, null, 64_000, 48_000, 32_000, 16_000, 1_800, 8_000,
        0, 16, 8, 3_000, 5, 3, "low", 12, 1);
    var model = mock(OpenAiClient.class);
    AtomicInteger requests = new AtomicInteger();
    when(model.respond(anyString(), anyList(), anyList(), anyBoolean(), any(), any())).thenAnswer(invocation -> {
      int round = requests.getAndIncrement();
      if (round == 0) return new OpenAiClient.ModelResponse(List.of(mapper.valueToTree(Map.of(
          "type", "function_call", "name", "deadline_lookup", "call_id", "known", "arguments", "{}"))), "", 100, 10);
      if (round == 1 || blockFinal) {
        RunControl control = invocation.getArgument(5);
        var closed = new CountDownLatch(1);
        try (var stream = new java.io.ByteArrayInputStream(new byte[0]) {
          @Override public void close() { closed.countDown(); }
        }) {
          control.attach(stream);
          try {
            assertThat(closed.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(Thread.currentThread().isInterrupted()).isFalse();
            control.check();
            throw new AssertionError("Cancelled model work must stop");
          } finally { control.detach(); }
        }
      }
      assertThat((boolean) invocation.getArgument(3)).isFalse();
      String text = "Retrieved pf-deadline; remaining work was stopped.";
      ((OpenAiClient.TextSink) invocation.getArgument(4)).accept(text);
      return new OpenAiClient.ModelResponse(List.of(AgentTestSupport.answer(mapper, text)), text, 100, 10);
    });
    try (var store = new MemoryStore(props, mapper, new MockFinanceService())) {
      var context = new ContextManager(props, AgentTestSupport.rag(), store, model, mapper);
      var tools = new ToolRegistry(mapper, props, store, context, mock(FinanceApiClient.class),
          mock(RagChatService.class), new BusinessTools("classpath:tools.yaml"));
      tools.register("deadline_lookup", "Known result", Map.of(), (args, scope) ->
          new ToolRegistry.Result(mapper.valueToTree(Map.of("id", "pf-deadline", "mock", true)), List.of()));
      var agent = new AgentService(props, AgentTestSupport.rag(), store, context, tools, model, mapper,
          new SoulInstructions("classpath:SOUL.md"));
      String session = store.createSession("user-001");
      List<Map<String, Object>> events = new ArrayList<>();
      long started = System.nanoTime();
      try { agent.run("user-001", session, "查询数据", events::add); }
      finally { agent.close(); }
      long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
      assertThat(elapsed).isBetween(3_800L, 6_500L);
      assertThat(requests.get()).isEqualTo(3);
      assertThat(events.getLast()).containsEntry("type", "done").containsEntry("partial", true);
      var saved = store.session("user-001", session).turns().getFirst();
      if (blockFinal) assertThat(saved.answer()).contains("deadline_lookup").doesNotContain("pf-deadline", "```json");
      else assertThat(saved.answer()).contains("pf-deadline");
      assertThat(saved.output().toString()).contains("pf-deadline");
      assertThat(store.session("user-001", session).turns().getFirst().completed()).isFalse();
      assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }
  }
}
