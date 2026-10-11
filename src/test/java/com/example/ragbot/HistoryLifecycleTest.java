package com.example.ragbot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class HistoryLifecycleTest {
  @TempDir Path root;
  private final JsonMapper mapper = new JsonMapper();

  private MemoryStore.StoredTurn turn(int index, Instant at, String text) {
    return new MemoryStore.StoredTurn(index, at.toString(), text,
        List.of(AgentTestSupport.answer(mapper, "Historical " + text)), "Historical " + text,
        List.of(), true, "test-model");
  }

  @Test
  void creatingAChatDoesNotArchiveOrDeleteExistingSessions() throws Exception {
    try (var store = new MemoryStore(AgentTestSupport.properties(root), mapper, new MockFinanceService())) {
      String expired = store.createSession("user-001");
      store.append("user-001", expired, turn(1, Instant.now().minus(400, ChronoUnit.DAYS), "old query"));
      store.createSession("user-001");
      assertThat(store.turnCount("user-001", expired)).isEqualTo(1);
      assertThat(root.resolve("users/user-001/sessions/" + expired + "/messages.jsonl")).exists();
      assertThat(store.maintain("user-001", Instant.now()).get("deleted")).isEqualTo(1);
    }
  }

  @Test
  void incrementalQuotaAccountingIncludesNestedWritesAndPartialFailures() throws Exception {
    Path user = root.resolve("user"), session = user.resolve("sessions/one"), archive = user.resolve("archive");
    Files.createDirectories(session); Files.createDirectories(archive);
    Files.writeString(session.resolve("messages.jsonl"), "12345");
    Files.writeString(archive.resolve("old.md"), "123");
    var usage = new HistoryMaintenance.Usage();
    assertThat(usage.bytes(user)).isEqualTo(8);
    usage.track(user, session, () -> {
      Files.writeString(session.resolve("messages.jsonl"), "12345678");
      Files.writeString(session.resolve("payload.json"), "1234"); return null;
    });
    assertThat(usage.bytes(user)).isEqualTo(15);
    usage.track(user, user, () -> {
      Files.writeString(user.resolve("USER.md"), "123");
      usage.track(user, archive, () -> { Files.writeString(archive.resolve("new.md"), "12"); return null; });
      return null;
    });
    assertThat(usage.bytes(user)).isEqualTo(20);
    assertThatThrownBy(() -> usage.track(user, session, () -> {
      Files.writeString(session.resolve("partial.json"), "1234"); throw new java.io.IOException("Interrupted write");
    })).isInstanceOf(java.io.IOException.class);
    assertThat(usage.bytes(user)).isEqualTo(24);
    assertThat(usage.bytes(user)).isEqualTo(FileHistory.diskBytes(user));
  }

  @Test
  void appendPreservesExistingBytesAndPagingNeverLoadsTheEntireTranscript() throws Exception {
    try (var store = new MemoryStore(AgentTestSupport.properties(root), mapper, new MockFinanceService())) {
      String id = store.createSession("user-001");
      store.append("user-001", id, turn(1, Instant.now(), "original"));
      Path file = root.resolve("users/user-001/sessions/" + id + "/messages.jsonl");
      String prefix = Files.readString(file);
      Object fileKey = Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class).fileKey();
      for (int i = 2; i <= 130; i++) store.append("user-001", id, turn(i, Instant.now(), "turn " + i));
      assertThat(Files.readString(file)).startsWith(prefix);
      assertThat(Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class).fileKey()).isEqualTo(fileKey);
      assertThat(store.page("user-001", id, 50, 10, false).turns()).hasSize(10);
      assertThat(store.page("user-001", id, 50, 10, false).nextOffset()).isEqualTo(60);
      assertThat(store.page("user-001", id, null, 10, false).offset()).isEqualTo(120);
      assertThat(store.activeSession("user-001", id).turns()).hasSize(100);
      assertThat(store.turnCount("user-001", id)).isEqualTo(130);
    }
  }

  @Test
  void restartRecoversCommittedTurnsAndRemovesOnlyAnUncommittedTail() throws Exception {
    String id;
    try (var store = new MemoryStore(AgentTestSupport.properties(root), mapper, new MockFinanceService())) {
      id = store.createSession("user-001"); store.append("user-001", id, turn(1, Instant.now(), "first"));
    }
    Path file = root.resolve("users/user-001/sessions/" + id + "/messages.jsonl");
    Files.writeString(file, mapper.writeValueAsString(turn(2, Instant.now(), "committed without metadata"))
        + "\n{\"index\":3,", StandardOpenOption.APPEND);
    try (var store = new MemoryStore(AgentTestSupport.properties(root), mapper, new MockFinanceService())) {
      assertThat(store.turnCount("user-001", id)).isEqualTo(2);
      assertThat(Files.readString(file)).endsWith("\n").doesNotContain("\"index\":3");
      assertThat(store.searchHistory("user-001", "committed")).hasSize(1);
      store.append("user-001", id, turn(3, Instant.now(), "third"));
      assertThat(store.turnCount("user-001", id)).isEqualTo(3);
    }
  }

  @Test
  void archivesPayloadsAndSearchesReadsExportsAndResumesWithoutLosingOriginalData() throws Exception {
    Instant now = Instant.now();
    var limits = new HistoryProperties(30, 365, true, 500L * 1024 * 1024, 256, 50, 3600);
    try (var store = new MemoryStore(AgentTestSupport.properties(root), limits, mapper, new MockFinanceService())) {
      String id = store.createSession("user-001");
      String payload = mapper.writeValueAsString(Map.of("portfolio", "pf-001", "history", "Financial snapshot ".repeat(200)));
      var output = mapper.valueToTree(Map.of("type", "function_call_output", "call_id", "business", "output", payload));
      store.append("user-001", id, new MemoryStore.StoredTurn(1, now.minus(45, ChronoUnit.DAYS).toString(),
          "Compare portfolio pf-001", List.of(output), "Historical USD snapshot", List.of(), true, "test"));
      Path directory = root.resolve("users/user-001/sessions/" + id);
      assertThat(Files.readString(directory.resolve("messages.jsonl"))).contains("payloadRef").doesNotContain("Financial snapshot ".repeat(100));
      assertThat(store.maintain("user-001", now).get("archived")).isEqualTo(1);
      assertThat(directory.resolve("messages.jsonl.gz")).exists();
      assertThat(directory.resolve("messages.jsonl")).doesNotExist();
      assertThat(store.searchHistory("user-001", "pf-001").getFirst().get("archived")).isEqualTo(true);
      assertThat(store.page("user-001", id, 0, 1, true).turns().getFirst().output().getFirst().path("output").asString()).isEqualTo(payload);
      var export = new java.io.ByteArrayOutputStream(); store.exportSession("user-001", id, export);
      assertThat(mapper.readTree(export.toString(java.nio.charset.StandardCharsets.UTF_8)).path("output").get(0).path("output").asString()).isEqualTo(payload);
      assertThatThrownBy(() -> store.page("user-002", id, 0, 1, true)).isInstanceOf(ApiRequestException.class);
      assertThatThrownBy(() -> store.deleteSession("user-002", id)).isInstanceOf(ApiRequestException.class);
      store.append("user-001", id, turn(2, now, "resumed"));
      assertThat(store.turnCount("user-001", id)).isEqualTo(2);
      assertThat(directory.resolve("messages.jsonl")).exists();
      assertThat(store.page("user-001", id, 0, 1, true).turns().getFirst().output().getFirst().path("output").asString()).isEqualTo(payload);
      store.deleteSession("user-001", id);
      assertThat(directory).doesNotExist(); assertThat(store.searchHistory("user-001", "pf-001")).isEmpty();
    }
  }

  @Test
  void retentionDeletesOnlyExpiredDataAndCanBeDisabled() throws Exception {
    Instant now = Instant.now();
    try (var store = new MemoryStore(AgentTestSupport.properties(root), mapper, new MockFinanceService())) {
      String recent = store.createSession("user-001"), expired = store.createSession("user-001");
      store.append("user-001", recent, turn(1, now.minus(20, ChronoUnit.DAYS), "retain"));
      store.append("user-001", expired, turn(1, now.minus(400, ChronoUnit.DAYS), "expire"));
      var result = store.maintain("user-001", now);
      assertThat(result.get("deleted")).isEqualTo(1);
      assertThat(store.turnCount("user-001", recent)).isEqualTo(1);
      assertThatThrownBy(() -> store.turnCount("user-001", expired)).isInstanceOf(ApiRequestException.class);
    }
    var disabled = new HistoryProperties(30, 365, false, 500L * 1024 * 1024, 8192, 50, 3600);
    try (var store = new MemoryStore(AgentTestSupport.properties(root), disabled, mapper, new MockFinanceService())) {
      String id = store.createSession("user-002"); store.append("user-002", id, turn(1, now.minus(400, ChronoUnit.DAYS), "keep forever"));
      assertThat(store.maintain("user-002", now).get("deleted")).isEqualTo(0);
      assertThat(store.page("user-002", id, 0, 1, false).turns()).hasSize(1);
    }
  }

  @Test
  void quotaRejectsNewWritesWithoutDeletingUnexpiredHistory() throws Exception {
    var limits = new HistoryProperties(30, 365, true, 20000, 8192, 50, 3600);
    try (var store = new MemoryStore(AgentTestSupport.properties(root), limits, mapper, new MockFinanceService())) {
      String id = store.createSession("user-001"); store.append("user-001", id, turn(1, Instant.now(), "keep"));
      assertThatThrownBy(() -> store.append("user-001", id, turn(2, Instant.now(), "huge ".repeat(10000))))
          .isInstanceOf(ApiRequestException.class).hasMessageContaining("quota");
      assertThat(store.turnCount("user-001", id)).isEqualTo(1);
      assertThat(store.maintain("user-001", Instant.now()).get("deleted")).isEqualTo(0);
      assertThat(store.page("user-001", id, 0, 1, false).turns().getFirst().message()).isEqualTo("keep");
    }
  }

  @Test
  void relevancePaginationCjkAndSurroundingMessagesAreUserScoped() throws Exception {
    try (var store = new MemoryStore(AgentTestSupport.properties(root), mapper, new MockFinanceService())) {
      String id = store.createSession("user-001");
      store.append("user-001", id, turn(1, Instant.now(), "portfolio"));
      store.append("user-001", id, turn(2, Instant.now(), "portfolio pf-001 现金比例比较"));
      store.append("user-001", id, turn(3, Instant.now(), "portfolio continuation"));
      var first = store.searchHistory("user-001", "portfolio pf-001", 0, 1);
      assertThat(first.results().getFirst().get("turn")).isEqualTo(2);
      assertThat(first.nextOffset()).isEqualTo(1);
      assertThat(store.searchHistory("user-001", "portfolio pf-001", 1, 1).results().getFirst().get("turn")).isNotEqualTo(2);
      assertThat(store.searchHistory("user-001", "现金比例")).hasSize(1);
      assertThat((List<?>) store.historyWindow("user-001", id, 2, 1).get("turns")).hasSize(3);
      assertThat(store.searchHistory("user-002", "portfolio")).isEmpty();
    }
  }

  @Test
  void migratesLegacyMemoryAndReplaysInterruptedTwoFileWrites() throws Exception {
    Path directory = root.resolve("users/user-001"); Files.createDirectories(directory);
    Files.writeString(directory.resolve("memory.md"), "## language\n中文\n");
    try (var store = new MemoryStore(AgentTestSupport.properties(root), mapper, new MockFinanceService())) {
      assertThat(store.coreMemory("user-001").user()).contains("中文");
      assertThat(store.coreMemory("user-001").notes()).isEmpty();
    }
    Files.writeString(directory.resolve("core-pending.json"), mapper.writeValueAsString(new MemoryStore.CoreMemory(
        "## language\nEnglish", "## base_currency\nUser explicitly uses USD for reporting.")));
    try (var store = new MemoryStore(AgentTestSupport.properties(root), mapper, new MockFinanceService())) {
      assertThat(store.coreMemory("user-001").user()).contains("English");
      assertThat(store.coreMemory("user-001").notes()).contains("USD");
      assertThat(directory.resolve("core-pending.json")).doesNotExist();
      assertThat(store.memory("user-002")).isEmpty();
    }
  }

  @Test
  void exportStreamsLargePayloadsWithExactEscapingAndUnicodeAcrossChunks() throws Exception {
    try (var store = new MemoryStore(AgentTestSupport.properties(root), mapper, new MockFinanceService())) {
      String id = store.createSession("user-001");
      String original = mapper.writeValueAsString(Map.of("text", "中文😀\"\\\n".repeat(6000)));
      var saved = store.spillToolResult("user-001", id, original);
      var output = mapper.valueToTree(Map.of("type", "function_call_output", "call_id", "large",
          "output", mapper.writeValueAsString(saved)));
      store.append("user-001", id, new MemoryStore.StoredTurn(1, Instant.now().toString(), "large query", List.of(output),
          "Historical snapshot", List.of(), true, "test"));
      var export = new java.io.ByteArrayOutputStream(); store.exportSession("user-001", id, export);
      assertThat(mapper.readTree(export.toString(java.nio.charset.StandardCharsets.UTF_8)).path("output").get(0).path("output").asString()).isEqualTo(original);
      var page = store.readPayload("user-001", id, saved.get("payloadRef").toString(), 0);
      assertThat(page.get("content").toString()).hasSizeLessThanOrEqualTo(1000);
      assertThat(page.get("nextOffset")).isNotNull();
      assertThatThrownBy(() -> store.readPayload("user-002", id, saved.get("payloadRef").toString(), 0)).isInstanceOf(ApiRequestException.class);
    }
  }

  @Test
  void missingKeywordIndexIsRebuiltWithoutAProcessRestart() throws Exception {
    try (var store = new MemoryStore(AgentTestSupport.properties(root), mapper, new MockFinanceService())) {
      String id = store.createSession("user-001");
      store.append("user-001", id, turn(1, Instant.now(), "pf-rebuild"));
      Path index = root.resolve("users/user-001/sessions/" + id + "/history-index.jsonl");
      Files.delete(index);
      assertThat(store.searchHistory("user-001", "pf-rebuild")).hasSize(1);
      assertThat(index).exists();
      assertThat(store.page("user-001", id, 0, 1, false).turns().getFirst().message()).isEqualTo("pf-rebuild");
    }
  }
}
