package com.example.ragbot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class MemoryStoreTest {
  @TempDir Path root;

  @Test
  void memoryAndHistorySurviveRestartAndStayUserScoped() throws Exception {
    var mapper = new JsonMapper();
    String id;
    try (var store = new MemoryStore(AgentTestSupport.properties(root), mapper, new MockFinanceService())) {
      id = store.createSession("user-001");
      store.memory("user-001", "## language\n使用中文回答。");
      store.append("user-001", id, AgentTestSupport.turn(mapper, 1, "Compare pf-001", "Portfolio snapshot [B1]"));
      store.summary("user-001", id, 1, "Discussed pf-001.");
      assertThat(store.memory("user-002")).isEmpty();
      assertThatThrownBy(() -> store.session("user-002", id)).isInstanceOf(ApiRequestException.class);
      assertThat(store.searchHistory("user-002", "pf-001")).isEmpty();
    }
    try (var restarted = new MemoryStore(AgentTestSupport.properties(root), mapper, new MockFinanceService())) {
      assertThat(restarted.memory("user-001")).contains("使用中文");
      assertThat(restarted.session("user-001", id).summary()).contains("pf-001");
      assertThat(restarted.session("user-001", id).turns()).hasSize(1);
      assertThat(restarted.searchHistory("user-001", "pf-001")).hasSize(1);
      assertThat(Files.readString(root.resolve("users/user-001/sessions/" + id + "/messages.jsonl")))
          .contains("Portfolio snapshot");
    }
  }

  @Test
  void rejectsUnknownUsersTraversalAndStaleWrites() throws Exception {
    var mapper = new JsonMapper();
    try (var store = new MemoryStore(AgentTestSupport.properties(root), mapper, new MockFinanceService())) {
      assertThatThrownBy(() -> store.memory("../user-001")).isInstanceOf(ApiRequestException.class);
      assertThatThrownBy(() -> store.session("user-001", "../../secret")).isInstanceOf(ApiRequestException.class);
      String id = store.createSession("user-001");
      store.append("user-001", id, AgentTestSupport.turn(mapper, 1, "Question", "Answer"));
      assertThatThrownBy(() -> store.append("user-001", id, AgentTestSupport.turn(mapper, 1, "Stale", "Overwrite")))
          .hasMessageContaining("Stale");
      assertThat(store.session("user-001", id).turns().getFirst().message()).isEqualTo("Question");
    }
  }

  @Test
  void competingServerCannotOpenTheSameMemoryDirectory() throws Exception {
    var mapper = new JsonMapper();
    try (var owner = new MemoryStore(AgentTestSupport.properties(root), mapper, new MockFinanceService())) {
      assertThat(owner.memory("user-001")).isEmpty();
      assertThatThrownBy(() -> new MemoryStore(AgentTestSupport.properties(root), mapper, new MockFinanceService()))
          .isInstanceOf(RuntimeException.class);
    }
  }

  @Test
  void concurrentMemoryEditsRemainWholeAndKeepBackups() throws Exception {
    var mapper = new JsonMapper();
    try (var store = new MemoryStore(AgentTestSupport.properties(root), mapper, new MockFinanceService());
        var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      var start = new CountDownLatch(1);
      var finished = new CountDownLatch(2);
      var first = pool.submit(() -> { start.await(); store.memory("user-001", "Alpha".repeat(100)); finished.countDown(); return true; });
      var second = pool.submit(() -> { start.await(); store.memory("user-001", "Beta".repeat(100)); finished.countDown(); return true; });
      start.countDown();
      assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(first.get()).isTrue();
      assertThat(second.get()).isTrue();
      assertThat(store.memory("user-001")).isIn("Alpha".repeat(100), "Beta".repeat(100));
      try (var archives = Files.list(root.resolve("users/user-001/archive"))) {
        assertThat(archives.count()).isEqualTo(1);
      }
    }
  }
}
