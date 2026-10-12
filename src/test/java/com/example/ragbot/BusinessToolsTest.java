package com.example.ragbot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class BusinessToolsTest {
  @TempDir Path root;

  @Test
  void yamlAddsATypedHttpToolWithoutJavaChangesAndInjectsTheCurrentUser() throws Exception {
    var mapper = new JsonMapper();
    List<String> targets = new CopyOnWriteArrayList<>();
    var api = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    api.createContext("/api/mock/report", exchange -> {
      try (exchange) {
        targets.add(exchange.getRequestURI().toString());
        byte[] body = "{\"total\":42}".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body);
      }
    });
    api.start();
    Path config = root.resolve("tools.yaml");
    Files.writeString(config, """
        tools:
          - name: get_report
            description: Read a user report
            title: Report
            status: 正在查询报告…
            method: GET
            path: /report/{id}
            parameters:
              id: {type: string, description: Report ID}
              limit: {type: integer, description: Page size, optional: true, query: limit}
              includeDetails: {type: boolean, description: Include details, query: details}
              query: {type: string, description: Search text, query: q}
        """);
    var props = new AgentProperties(root.resolve("memory"), URI.create("http://127.0.0.1:" + api.getAddress().getPort() + "/api/mock"),
        64_000, 48_000, 32_000, 16_000, 1_800, 8_000, 0, 16, 8, 3_000, 240, 3, "low");
    try (var store = new MemoryStore(props, mapper, new MockFinanceService())) {
      var registry = new ToolRegistry(mapper, props, store, mock(ContextManager.class),
          new FinanceApiClient(props, mapper), mock(RagChatService.class), new BusinessTools(config.toUri().toString()));
      var args = mapper.valueToTree(Map.of("id", "report-1", "limit", 3, "includeDetails", true, "query", "cash & bonds"));
      for (String user : List.of("user-001", "user-002")) {
        var result = registry.execute("get_report", args,
            new AgentTool.Scope(store.user(user), store.createSession(user), 0, new RunControl(30)));
        assertThat(result.output().path("data").path("total").asInt()).isEqualTo(42);
      }
      assertThat(targets.get(0)).contains("username=alice", "limit=3", "details=true", "q=cash+%26+bonds");
      assertThat(targets.get(1)).contains("username=bob");
      assertThat(registry.status("get_report")).isEqualTo("正在查询报告…");
      var scope = new AgentTool.Scope(store.user("user-001"), store.createSession("user-001"), 0, new RunControl(30));
      var invalid = args.deepCopy(); ((tools.jackson.databind.node.ObjectNode) invalid).put("limit", "3");
      assertThatThrownBy(() -> registry.execute("get_report", invalid, scope)).isInstanceOf(ApiRequestException.class);
      ((tools.jackson.databind.node.ObjectNode) invalid).put("id", "../other");
      ((tools.jackson.databind.node.ObjectNode) invalid).put("limit", 3);
      assertThatThrownBy(() -> registry.execute("get_report", invalid, scope)).isInstanceOf(ApiRequestException.class);
      assertThat(targets).hasSize(2);
    } finally { api.stop(0); }
  }

  @Test
  void rejectsIdentityParametersAndUnsafeUrlsAtStartup() {
    assertThatThrownBy(() -> new BusinessTools.Definition("get_test", "Test", "Test", "查询", "GET", "/accounts",
        Map.of("identity", new BusinessTools.Parameter("string", "User", false, "username"))))
        .hasMessageContaining("identity");
    assertThatThrownBy(() -> new BusinessTools.Definition("get_test", "Test", "Test", "查询", "GET", "https://example.com", Map.of()))
        .hasMessageContaining("Invalid business tool");
    assertThatThrownBy(() -> new BusinessTools.Definition("get_test", "Test", "Test", "查询", "POST", "/accounts", Map.of()))
        .hasMessageContaining("Invalid business tool");
    assertThatThrownBy(() -> new BusinessTools.Definition("get_test", "Test", "Test", "查询", "GET", "/accounts/{id}", Map.of()))
        .hasMessageContaining("Undefined path");
  }

  @Test
  void bundlesDefaultsAndFailsOnExplicitMissingConfiguration() throws Exception {
    assertThat(new BusinessTools("optional:file:" + root.resolve("missing.yaml")).definitions()).hasSize(7);
    assertThatThrownBy(() -> new BusinessTools("file:" + root.resolve("missing.yaml")))
        .isInstanceOf(java.io.IOException.class).hasMessageContaining("not found");
  }
}
