package com.example.ragbot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ContextManagerTest {
  @TempDir Path root;

  @Test
  void compactionPersistsSummaryAndLeavesFullHistoryRecoverable() throws Exception {
    var mapper = new JsonMapper();
    var props = AgentTestSupport.properties(root);
    var model = mock(Model.class);
    when(model.summarize(anyString(), anyString(), anyInt(), any())).thenReturn("Compare pf-001 and pf-002; USD versus HKD. User requested cash allocation.");
    try (var store = new MemoryStore(props, mapper, new MockFinanceService())) {
      String id = store.createSession("user-001");
      store.append("user-001", id, AgentTestSupport.turn(mapper, 1, "Compare pf-001 and pf-002", "Different currencies."));
      var context = new ContextManager(props, AgentTestSupport.rag(), store, model, mapper);
      var prepared = context.prepare("user-001", id, "System rules", List.of(),
          List.of(context.message("user", "What about cash?")), true, new RunControl(30));
      assertThat(prepared.compacted()).isTrue();
      assertThat(prepared.input().toString()).contains("pf-001", "USD", "What about cash?");
      assertThat(store.session("user-001", id).summarizedThrough()).isEqualTo(1);
      assertThat(store.searchHistory("user-001", "pf-002")).hasSize(1);
      assertThat(Files.readString(root.resolve("users/user-001/sessions/" + id + "/messages.jsonl"))).contains("Different currencies");
    }
  }

  @Test
  void failedSummarizationDoesNotAdvanceTheBoundaryOrEraseMemory() throws Exception {
    var mapper = new JsonMapper();
    var props = AgentTestSupport.properties(root);
    var model = mock(Model.class);
    when(model.summarize(anyString(), anyString(), anyInt(), any())).thenReturn("");
    try (var store = new MemoryStore(props, mapper, new MockFinanceService())) {
      String id = store.createSession("user-001");
      store.append("user-001", id, AgentTestSupport.turn(mapper, 1, "pf-001", "Original answer"));
      store.memory("user-001", "Original preference");
      var context = new ContextManager(props, AgentTestSupport.rag(), store, model, mapper);
      assertThatThrownBy(() -> context.prepare("user-001", id, "System", List.of(), List.of(), true, new RunControl(30)))
          .hasMessageContaining("original files preserved");
      assertThat(store.session("user-001", id).summarizedThrough()).isZero();
      assertThatThrownBy(() -> context.replaceMemory("user-001", "Very long memory ".repeat(1_000), new RunControl(30)))
          .hasMessageContaining("original files preserved");
      assertThat(store.memory("user-001")).contains("Original preference");
    }
  }

  @Test
  void memoryCorrectionsReplaceEntriesAndForgettingDoesNotReplayOldCandidates() throws Exception {
    var mapper = new JsonMapper();
    var props = AgentTestSupport.properties(root);
    try (var store = new MemoryStore(props, mapper, new MockFinanceService())) {
      String id = store.createSession("user-001");
      var context = new ContextManager(props, AgentTestSupport.rag(), store, mock(Model.class), mapper);
      context.remember("user-001", id, "language", "Use English", new RunControl(30));
      context.remember("user-001", id, "language", "使用中文", new RunControl(30));
      assertThat(store.memory("user-001")).contains("使用中文").doesNotContain("Use English");
      store.append("user-001", id, AgentTestSupport.turn(mapper, 1, "Remember English", "Saved"));
      context.forget("user-001", "language");
      assertThat(store.memory("user-001")).isEmpty();
      assertThat(store.memoryCursor("user-001", id)).isEqualTo(1);
      assertThat(store.memory("user-002")).isEmpty();
    }
  }

  @Test
  void activeCompactionKeepsTheLatestToolCallAndResultTogether() throws Exception {
    var mapper = new JsonMapper();
    var props = AgentTestSupport.properties(root);
    var model = mock(Model.class);
    when(model.summarize(anyString(), anyString(), anyInt(), any())).thenReturn("Earlier portfolio results were read. Exact IDs: pf-001 and pf-002.");
    try (var store = new MemoryStore(props, mapper, new MockFinanceService())) {
      var context = new ContextManager(props, AgentTestSupport.rag(), store, model, mapper);
      List<JsonNode> current = new ArrayList<>();
      current.add(context.message("user", "Compare portfolios"));
      current.add(mapper.valueToTree(Map.of("type", "function_call", "call_id", "old", "name", "get_portfolio", "arguments", "{}")));
      current.add(mapper.valueToTree(Map.of("type", "function_call_output", "call_id", "old", "output", "Data ".repeat(11_000))));
      current.add(mapper.valueToTree(Map.of("type", "function_call", "call_id", "latest", "name", "get_positions", "arguments", "{}")));
      current.add(mapper.valueToTree(Map.of("type", "function_call_output", "call_id", "latest", "output", "Latest holdings")));
      assertThat(context.compactActive("System", List.of(), current, 3, new RunControl(30))).isTrue();
      assertThat(current).hasSize(4);
      assertThat(current.get(2).path("call_id").asString()).isEqualTo("latest");
      assertThat(current.get(3).path("call_id").asString()).isEqualTo("latest");
      assertThat(current.get(1).toString()).contains("pf-001");
    }
  }

  @Test
  void budgetsHandleCjkAndSmallerProviderWindows() {
    assertThat(TokenBudget.estimate("中文测试")).isGreaterThanOrEqualTo(4);
    var configured = new AgentProperties(root, null, 32_000, 25_000, 12_000, 6_000, 1_800, 8_000,
        20_000, 6, 8, 3_000, 120, 3, "low");
    assertThat(configured.inputLimit("gpt-6-luna")).isEqualTo(8_000);
    assertThat(configured.compactThreshold("gpt-6-luna")).isEqualTo(6_000);
    assertThat(configured.compactTarget("gpt-6-luna")).isEqualTo(4_000);
    assertThat(configured.recentLimit("gpt-6-luna")).isEqualTo(2_000);
  }

  @Test
  void compressesAtSeventyFivePercentOfTheWorkingBudget() throws Exception {
    var mapper = new JsonMapper();
    var props = new AgentProperties(root, null, 64_000, 48_000, 32_000, 16_000, 1_800, 8_000,
        0, 16, 8, 3_000, 240, 3, "low");
    assertThat(props.inputLimit("gpt-5-mini")).isEqualTo(64_000);
    assertThat(props.inputLimit("gpt-6-luna")).isEqualTo(64_000);
    var model = mock(Model.class);
    when(model.summarize(anyString(), anyString(), anyInt(), any())).thenReturn("Historical portfolio discussion.");
    try (var store = new MemoryStore(props, mapper, new MockFinanceService())) {
      String id = store.createSession("user-001");
      store.append("user-001", id, AgentTestSupport.turn(mapper, 1, "pf-001", "x".repeat(96_000)));
      var context = new ContextManager(props, AgentTestSupport.rag(), store, model, mapper);
      assertThat(context.prepare("user-001", id, "System", List.of(), List.of(), false, new RunControl(30)).compacted()).isTrue();
      assertThat(store.session("user-001", id).summarizedThrough()).isEqualTo(1);
    }
  }

  @Test
  void keyedUpdatesAcrossTargetsDoNotRewriteUnrelatedEntries() throws Exception {
    var mapper = new JsonMapper(); var props = AgentTestSupport.properties(root);
    var model = mock(Model.class);
    try (var store = new MemoryStore(props, mapper, new MockFinanceService())) {
      String id = store.createSession("user-001");
      var context = new ContextManager(props, AgentTestSupport.rag(), store, model, mapper);
      context.remember("user-001", id, "language", "Use Chinese", "user", new RunControl(30));
      context.remember("user-001", id, "base_currency", "Reporting currency is USD", "memory", new RunControl(30));
      String notes = store.coreMemory("user-001").notes();
      context.remember("user-001", id, "language", "Use English", "user", new RunControl(30));
      assertThat(store.coreMemory("user-001").user()).contains("Use English").doesNotContain("Use Chinese");
      assertThat(store.coreMemory("user-001").notes()).isEqualTo(notes);
      assertThat(TokenBudget.estimate(store.memory("user-001"))).isLessThanOrEqualTo(1800);
      verify(model, never()).summarize(anyString(), anyString(), anyInt(), any());
    }
  }

  @Test
  void overBudgetConsolidatesARelatedPairPreservingKeysAndUnrelatedFacts() throws Exception {
    var mapper = new JsonMapper(); var props = AgentTestSupport.properties(root);
    var model = mock(Model.class);
    when(model.summarize(anyString(), anyString(), anyInt(), any())).thenReturn(
        "{\"entries\":[{\"key\":\"reply_language\",\"content\":\"Chinese replies\"},{\"key\":\"document_language\",\"content\":\"Chinese documents\"}]}");
    try (var store = new MemoryStore(props, mapper, new MockFinanceService())) {
      var context = new ContextManager(props, AgentTestSupport.rag(), store, model, mapper);
      String unrelated = "## base_currency\nUSD reporting.\n";
      context.replaceCoreMemory("user-001", new MemoryStore.CoreMemory(
          "## reply_language\n" + "Chinese language replies ".repeat(100)
              + "\n## document_language\n" + "Chinese language documents ".repeat(100), unrelated), new RunControl(30));
      assertThat(store.coreMemory("user-001").user()).contains("## reply_language", "## document_language", "Chinese replies");
      assertThat(store.coreMemory("user-001").notes().strip()).isEqualTo(unrelated.strip());
      var input = ArgumentCaptor.forClass(String.class);
      verify(model).summarize(anyString(), input.capture(), anyInt(), any());
      assertThat(input.getValue()).doesNotContain("base_currency");
    }
  }

  @Test
  void compactsBoundedBatchesUntilLongLegacyHistoryCanBeSafelyLoaded() throws Exception {
    var mapper = new JsonMapper(); var props = AgentTestSupport.properties(root);
    var model = mock(Model.class);
    when(model.summarize(anyString(), anyString(), anyInt(), any())).thenReturn("Historical portfolio discussion; recover details with search_history.");
    try (var store = new MemoryStore(props, mapper, new MockFinanceService())) {
      String id = store.createSession("user-001");
      for (int i = 1; i <= 130; i++) store.append("user-001", id, AgentTestSupport.turn(mapper, i, "pf-001 turn " + i, "Historical snapshot"));
      var context = new ContextManager(props, AgentTestSupport.rag(), store, model, mapper);
      context.prepare("user-001", id, "System", List.of(), List.of(), true, new RunControl(30));
      assertThat(store.activeSession("user-001", id).summarizedThrough()).isEqualTo(130);
      assertThat(store.activeSession("user-001", id).turns()).isEmpty();
      assertThat(store.searchHistory("user-001", "pf-001", 0, 5).total()).isEqualTo(130);
    }
  }
}
