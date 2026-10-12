package com.example.ragbot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class AgentToolContractTest {
  @TempDir Path root;

  @Test
  void standaloneToolUsesRegistryValidationUserScopeAndOrderedCitations() throws Exception {
    var mapper = new JsonMapper();
    var props = AgentTestSupport.properties(root);
    var executed = new AtomicInteger();
    try (var store = new MemoryStore(props, mapper, new MockFinanceService())) {
      var registry = new ToolRegistry(mapper, props, store, mock(ContextManager.class),
          mock(FinanceApiClient.class), mock(RagChatService.class), new BusinessTools("classpath:tools.yaml"));
      AgentTool tool = new AgentTool() {
        public String name() { return "standalone_tool"; }
        public String description() { return "Independent tool implementation"; }
        public Map<String, Object> parameters() {
          return Map.of("type", "object", "properties", Map.of("id", Map.of("type", "string")),
              "required", List.of("id"), "additionalProperties", false);
        }
        public Result execute(JsonNode args, Scope scope) {
          executed.incrementAndGet();
          assertThat(scope.user().username()).isEqualTo("alice");
          assertThat(scope.sourceOffset()).isZero();
          var sources = List.of(new RagSource("B1", "Report", "Report", "/reports/r1", 1));
          return new Result(mapper.valueToTree(Map.of("id", args.path("id").asString(), "sources", sources)), sources);
        }
      };
      registry.register(tool);
      assertThat(tool.readOnly()).isFalse();
      var scope = new AgentTool.Scope(store.user("user-001"), store.createSession("user-001"), 3, new RunControl(30));
      assertThatThrownBy(() -> registry.execute(tool.name(), mapper.valueToTree(Map.of("id", "r1", "username", "bob")), scope))
          .isInstanceOf(ApiRequestException.class).hasMessageContaining("Unexpected");
      assertThat(executed.get()).isZero();
      var result = registry.execute(tool.name(), mapper.valueToTree(Map.of("id", "r1")), scope);
      assertThat(executed.get()).isEqualTo(1);
      assertThat(result.sources()).extracting(RagSource::citation).containsExactly("B4");
      assertThat(result.output().path("sources").get(0).path("citation").asString()).isEqualTo("B4");
      assertThat(OpenAiClient.toolDefinition(tool)).containsEntry("strict", true).containsEntry("parameters", tool.parameters());
    }
  }

  @Test
  void oversizedResultsPersistFinalCitationsBeforeReturningAPayloadReference() throws Exception {
    var mapper = new JsonMapper();
    var props = AgentTestSupport.properties(root);
    try (var store = new MemoryStore(props, mapper, new MockFinanceService())) {
      var registry = new ToolRegistry(mapper, props, store, mock(ContextManager.class),
          mock(FinanceApiClient.class), mock(RagChatService.class), new BusinessTools("classpath:tools.yaml"));
      registry.register("large_report", "Read large report", Map.of(), true, (args, scope) -> {
        var sources = List.of(new RagSource("B1", "Report", "Report", "/reports/r1", 1));
        return new AgentTool.Result(mapper.valueToTree(Map.of("rows", "data ".repeat(6000),
            "excerpts", "Report [B1]", "sources", sources)), sources);
      });
      String session = store.createSession("user-001");
      var result = registry.execute("large_report", mapper.valueToTree(Map.of()),
          new AgentTool.Scope(store.user("user-001"), session, 4, new RunControl(30)));
      assertThat(result.output().path("sources").get(0).path("citation").asString()).isEqualTo("B5");
      assertThat(result.output().path("payloadRef").asString()).endsWith(".json");
      String payload = java.nio.file.Files.readString(root.resolve("users/user-001/sessions/" + session + "/payloads/"
          + result.output().path("payloadRef").asString()));
      assertThat(payload).contains("[B5]", "\"citation\":\"B5\"").doesNotContain("[B1]");
    }
  }
}
