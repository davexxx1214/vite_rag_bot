package com.example.ragbot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Real HTTP writes, model loop, file history, correction cards and immutable snapshots. No paid API. */
class EditingWorkflowTest {
  @TempDir Path root;
  private final JsonMapper mapper = new JsonMapper();

  @Test void duplicatePausesReleasesLockDefersSiblingWritesAndResumesExactlyOnce() throws Exception {
    try (var h = new Harness()) {
      h.model.turns.add(new Model.Turn(List.of(h.call("update_portfolio", "edit", edit("Hong Kong Income", 1)),
          h.call("remember", "later", Map.of("key", "unauthorized", "content", "should not execute"))), "", 100, 10));
      List<Map<String, Object>> events = h.run("将 pf-001 改名为 Hong Kong Income");
      assertThat(events.getLast()).containsEntry("reason", "waiting_for_user").containsEntry("partial", false).containsEntry("toolCalls", 1);
      assertThat(events).anySatisfy(event -> assertThat(event).containsEntry("type", "user_input_required"));
      assertThat(h.store.memory("user-001")).isBlank();
      assertThat(h.store.runLock("user-001").tryLock()).isTrue(); h.store.runLock("user-001").unlock();
      JsonNode question = h.tools.editing().publicQuestion("user-001", h.session);
      assertThat(question.path("options").size()).isEqualTo(2);
      assertThat(h.fixtures.portfolio("pf-001", "alice").version()).isEqualTo(1);
      // A replacement registry reads the persistent question without relying on model history.
      var reloaded = h.registry();
      assertThat(reloaded.editing().publicQuestion("user-001", h.session)).isEqualTo(question);
      String requestId = question.path("requestId").asString();
      List<Map<String, Object>> resumed = new ArrayList<>();
      h.agent.run("user-001", h.session, "退休计划", new EditingWorkflow.InputAnswer(requestId, null, "退休计划", false), resumed::add);
      assertThat(h.fixtures.portfolio("pf-001", "alice").name()).isEqualTo("退休计划");
      assertThat(h.fixtures.portfolio("pf-001", "alice").version()).isEqualTo(2);
      assertThat(resumed.getLast()).containsEntry("partial", false);
      assertThat(h.model.calls.get()).isEqualTo(1); // A card correction executes the saved draft directly.
      assertThat(h.store.session("user-001", h.session).turns()).hasSize(2);
      assertThatThrownBy(() -> h.agent.run("user-001", h.session, "重复点击",
          new EditingWorkflow.InputAnswer(requestId, null, "退休计划", false), event -> {})).isInstanceOf(ApiRequestException.class);
      assertThat(h.fixtures.portfolio("pf-001", "alice").version()).isEqualTo(2);
      assertThat(h.store.session("user-001", h.session).turns()).hasSize(2);
    }
  }

  @Test void repeatedBadAnswerCreatesNewQuestionAndOldCardCannotSubmit() throws Exception {
    try (var h = new Harness()) {
      h.tools.editing().start("user-001", h.session, "Rename");
      JsonNode first = h.execute("update_portfolio", edit("Hong Kong Income", 1)).path("inputRequest");
      var bad = h.tools.editing().answer(h.scope(), new EditingWorkflow.InputAnswer(first.path("requestId").asString(), null, "Hong Kong Income", false));
      assertThat(bad.result().path("status").asString()).isEqualTo("waiting_for_user");
      assertThat(bad.result().path("inputRequest").path("requestId")).isNotEqualTo(first.path("requestId"));
      assertThatThrownBy(() -> h.tools.editing().answer(h.scope(), new EditingWorkflow.InputAnswer(first.path("requestId").asString(), "name_1", null, false)))
          .isInstanceOf(ApiRequestException.class);
      JsonNode latest = bad.result().path("inputRequest");
      h.tools.editing().answer(h.scope(), new EditingWorkflow.InputAnswer(latest.path("requestId").asString(), null, null, true));
      assertThat(h.fixtures.portfolio("pf-001", "alice").version()).isEqualTo(1);
      assertThatThrownBy(() -> h.execute("check_edit_operation", Map.of())).isInstanceOf(ApiRequestException.class);
    }
  }

  @Test void versionConflictRequiresExplicitChoiceAndChecksAgainBeforeCommitting() throws Exception {
    try (var h = new Harness()) {
      h.fixtures.updatePortfolio("pf-001", "alice", "other", new MockFinanceService.PortfolioEdit("别人修改", 1L));
      JsonNode first = h.execute("update_portfolio", edit("我的名称", 1)).path("inputRequest");
      assertThat(first.path("reason").asString()).isEqualTo("version_conflict");
      assertThat(first.path("question").asString()).contains("别人修改", "版本 2");
      h.fixtures.updatePortfolio("pf-001", "alice", "other-again", new MockFinanceService.PortfolioEdit("再次修改", 2L));
      var answer = h.tools.editing().answer(h.scope(), new EditingWorkflow.InputAnswer(first.path("requestId").asString(), "retry", null, false));
      assertThat(answer.result().path("status").asString()).isEqualTo("waiting_for_user");
      assertThat(h.fixtures.portfolio("pf-001", "alice").name()).isEqualTo("再次修改");
      JsonNode second = answer.result().path("inputRequest");
      var keep = h.tools.editing().answer(h.scope(), new EditingWorkflow.InputAnswer(second.path("requestId").asString(), "keep", null, false));
      assertThat(keep.result().path("status").asString()).isEqualTo("cancelled");
      assertThat(h.fixtures.portfolio("pf-001", "alice").version()).isEqualTo(3);
    }
  }

  @Test void committedButLostReplyIsResolvedWithoutASecondMutation() throws Exception {
    try (var h = new Harness()) {
      h.dropReply.set(true);
      JsonNode result = h.execute("update_portfolio", edit("Reply Lost", 1));
      assertThat(result.path("inputRequest").path("reason").asString()).isEqualTo("unknown_outcome");
      assertThat(h.fixtures.portfolio("pf-001", "alice").name()).isEqualTo("Reply Lost");
      String request = result.path("inputRequest").path("requestId").asString();
      var resumed = h.tools.editing().answer(h.scope(), new EditingWorkflow.InputAnswer(request, "check", null, false));
      assertThat(resumed.result().path("status").asString()).isEqualTo("applied");
      assertThat(h.patchCalls.get()).isEqualTo(1);
      assertThat(h.fixtures.portfolio("pf-001", "alice").version()).isEqualTo(2);
    }
  }

  @Test void editsRefreshOtherSessionsWhileExplicitOldSnapshotRemainsHistorical() throws Exception {
    try (var h = new Harness()) {
      String other = h.store.createSession("user-001");
      var otherScope = new AgentTool.Scope(h.store.user("user-001"), other, 0, new RunControl(60));
      JsonNode old = h.tools.execute("list_portfolios", mapper.createObjectNode(), otherScope).output().path("data");
      h.execute("update_portfolio", edit("Fresh Name", 1));
      JsonNode fresh = h.tools.execute("query_portfolios", mapper.valueToTree(Map.of("id", "pf-001")), otherScope).output().path("data");
      assertThat(fresh.path("items").get(0).path("name").asString()).isEqualTo("Fresh Name");
      assertThat(fresh.path("resultSetId")).isNotEqualTo(old.path("resultSetId"));
      JsonNode historical = h.tools.execute("query_portfolios", mapper.valueToTree(Map.of("resultSetId", old.path("resultSetId").asString(), "id", "pf-001")), otherScope).output().path("data");
      assertThat(historical.path("items").get(0).path("name").asString()).isEqualTo("US Growth");
      assertThat(historical.path("beforeEdit").asBoolean()).isTrue();
    }
  }

  @Test void clarificationRestoresOriginalIntentAndResourceOptionsAreValidated() throws Exception {
    try (var h = new Harness()) {
      Map<String, Object> args = Map.of("question", "你要修改哪个组合？", "field", "portfolioId", "options",
          List.of(Map.of("id", "one", "label", "US Growth", "value", "pf-001", "description", "USD")));
      h.model.turns.add(new Model.Turn(List.of(h.call("request_user_input", "question", args)), "", 100, 10));
      h.run("把我的组合改名为计划");
      JsonNode question = h.tools.editing().publicQuestion("user-001", h.session);
      h.model.turns.add(new Model.Turn(List.of(h.call("update_portfolio", "rename", edit("计划", 1))), "", 100, 10));
      h.model.turns.add(new Model.Turn(List.of(AgentTestSupport.answer(mapper, "已修改。")), "已修改。", 100, 10));
      h.agent.run("user-001", h.session, "选择 pf-001", new EditingWorkflow.InputAnswer(question.path("requestId").asString(), "one", null, false), event -> {});
      assertThat(h.model.inputs.get(1).toString()).contains("originalRequest", "把我的组合改名为计划", "pf-001");
      assertThat(h.fixtures.portfolio("pf-001", "alice").name()).isEqualTo("计划");
      Map<String, Object> invalid = Map.of("question", "请选择", "field", "portfolioId", "options",
          List.of(Map.of("id", "foreign", "label", "Bob", "value", "pf-003", "description", "foreign")));
      assertThatThrownBy(() -> h.execute("request_user_input", invalid)).isInstanceOf(ApiRequestException.class);
    }
  }

  @Test void expiredForeignAndForgedAnswersAreRejectedWithoutMutation() throws Exception {
    try (var h = new Harness()) {
      JsonNode question = h.execute("update_portfolio", edit("Hong Kong Income", 1)).path("inputRequest");
      String id = question.path("requestId").asString();
      assertThatThrownBy(() -> h.tools.editing().answer(h.scope(), new EditingWorkflow.InputAnswer(id, "forged", null, false)))
          .isInstanceOf(ApiRequestException.class);
      String bobSession = h.store.createSession("user-002");
      var bob = new AgentTool.Scope(h.store.user("user-002"), bobSession, 0, new RunControl(30));
      assertThatThrownBy(() -> h.tools.editing().answer(bob, new EditingWorkflow.InputAnswer(id, "name_1", null, false)))
          .isInstanceOf(ApiRequestException.class);
      Path file = h.store.requireSession("user-001", h.session).resolve("editing-workflow.json");
      var saved = (tools.jackson.databind.node.ObjectNode) mapper.readTree(Files.readString(file));
      ((tools.jackson.databind.node.ObjectNode) saved.path("question")).put("expiresAt", "2000-01-01T00:00:00Z");
      Files.writeString(file, mapper.writeValueAsString(saved));
      assertThat(h.tools.editing().publicQuestion("user-001", h.session).path("status").asString()).isEqualTo("expired");
      assertThatThrownBy(() -> h.tools.editing().answer(h.scope(), new EditingWorkflow.InputAnswer(id, "name_1", null, false)))
          .isInstanceOf(ApiRequestException.class);
      assertThat(h.fixtures.portfolio("pf-001", "alice").version()).isEqualTo(1);
    }
  }

  @Test void uncommittedLostRequestReplaysTheOriginalKeyAndCancellationDoesNotClaimRollback() throws Exception {
    try (var h = new Harness()) {
      h.dropBeforeWrite.set(true);
      JsonNode result = h.execute("update_portfolio", edit("Recovered", 1));
      assertThat(result.path("status").asString()).isEqualTo("waiting_for_user");
      assertThat(h.fixtures.portfolio("pf-001", "alice").version()).isEqualTo(1);
      String request = result.path("inputRequest").path("requestId").asString();
      var cancelled = h.tools.editing().answer(h.scope(), new EditingWorkflow.InputAnswer(request, null, null, true));
      assertThat(cancelled.result().path("status").asString()).isEqualTo("verification_deferred");
      assertThatThrownBy(() -> h.execute("update_portfolio", edit("Different", 1))).isInstanceOf(ApiRequestException.class);
      assertThat(h.execute("check_edit_operation", Map.of()).path("status").asString()).isEqualTo("applied");
      assertThat(h.patchKeys).hasSize(2);
      assertThat(h.patchKeys.get(0)).isEqualTo(h.patchKeys.get(1));
      assertThat(h.fixtures.portfolio("pf-001", "alice").version()).isEqualTo(2);
    }
  }

  @Test void answerEndpointStreamsTheSavedCorrectionAndSessionEndpointRestoresCard() throws Exception {
    try (var h = new Harness()) {
      JsonNode question = h.execute("update_portfolio", edit("Hong Kong Income", 1)).path("inputRequest");
      var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
          new HelpController(mock(HelpDocumentService.class), mock(RagIndex.class), AgentTestSupport.rag(), h.agent, h.store, mapper),
          new AgentController(h.store, h.context, h.agent, h.properties, h.fixtures))
          .setControllerAdvice(new ApiExceptionHandler()).build();
      mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/agent/sessions/" + h.session)
          .header("X-Mock-User-Id", "user-001"))
          .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.inputRequest.status").value("pending"));
      var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
          "/api/agent/sessions/" + h.session + "/input-requests/" + question.path("requestId").asString() + "/answers")
          .header("X-Mock-User-Id", "user-001").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
          .content("{\"optionId\":\"name_1\"}"))
          .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
          .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request().asyncStarted()).andReturn();
      mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(response))
          .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
      assertThat(response.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("已将", "\"type\":\"done\"");
      assertThat(h.fixtures.portfolio("pf-001", "alice").name()).isEqualTo("Hong Kong Income（新）");
      assertThat(h.model.calls.get()).isZero();
    }
  }

  @Test void pendingQuestionSurvivesClosingAndReopeningFileStore() throws Exception {
    try (var h = new Harness()) {
      JsonNode question = h.execute("update_portfolio", edit("Hong Kong Income", 1)).path("inputRequest");
      h.agent.close(); h.store.close();
      try (var reopened = new MemoryStore(h.properties, mapper, h.fixtures)) {
        var context = new ContextManager(h.properties, AgentTestSupport.rag(), reopened, h.model, mapper);
        var registry = new ToolRegistry(mapper, h.properties, reopened, context, h.finance, mock(RagChatService.class), new BusinessTools("classpath:tools.yaml"));
        assertThat(registry.editing().publicQuestion("user-001", h.session)).isEqualTo(question);
        var scope = new AgentTool.Scope(reopened.user("user-001"), h.session, 0, new RunControl(30));
        registry.editing().answer(scope, new EditingWorkflow.InputAnswer(question.path("requestId").asString(), null, "重启后继续", false));
        assertThat(h.fixtures.portfolio("pf-001", "alice").name()).isEqualTo("重启后继续");
      }
    }
  }

  private Map<String, Object> edit(String name, long version) { return Map.of("id", "pf-001", "name", name, "expectedVersion", version); }

  @Test void directEditQueriesAfterPatchAndStoresTheSameInlineResultShownInTheStream() throws Exception {
    try (var h = new Harness()) {
      h.model.turns.add(new Model.Turn(List.of(h.call("update_portfolio", "edit", edit("核验后的名称", 1))), "", 100, 10));
      h.model.turns.add(new Model.Turn(List.of(AgentTestSupport.answer(mapper, "修改成功，已重新查询。")), "修改成功，已重新查询。", 100, 10));
      List<Map<String, Object>> events = h.run("将 pf-001 改名为核验后的名称");
      assertThat(h.requests).containsExactly("GET /portfolios/pf-001", "PATCH /portfolios/pf-001", "GET /portfolios/pf-001");
      assertThat(events).anySatisfy(event -> assertThat(event).containsEntry("type", "activity_start").containsEntry("message", "正在重新查询编辑后的投资组合…"));
      Map<String, Object> view = events.stream().filter(event -> event.get("type").equals("edit_result")).findFirst().orElseThrow();
      String markdown = view.get("markdown").toString();
      assertThat(markdown).contains("| 当前名称 | 核验后的名称 |", "| 版本 | 2 |", "```limits", "1850 / 5000", "3150 / 5000", "2026-10-09T16:00:00Z");
      assertThat(h.store.session("user-001", h.session).turns().getFirst().answer()).contains(markdown);
      // Session display reads the original verified snapshot; it does not issue a new GET on refresh.
      assertThat(h.store.page("user-001", h.session, null, 50, false).turns().getFirst().answer()).contains(markdown);
    }
  }

  @Test void correctionResumeAlsoQueriesAndReturnsAnInlineResult() throws Exception {
    try (var h = new Harness()) {
      JsonNode question = h.execute("update_portfolio", edit("Hong Kong Income", 1)).path("inputRequest");
      var events = new ArrayList<Map<String, Object>>();
      h.agent.run("user-001", h.session, "纠正名称", new EditingWorkflow.InputAnswer(question.path("requestId").asString(), null, "纠正名称", false), events::add);
      assertThat(h.requests.getLast()).isEqualTo("GET /portfolios/pf-001");
      assertThat(events).anySatisfy(event -> {
        assertThat(event).containsEntry("type", "edit_result");
        assertThat(event.get("markdown").toString()).contains("| 当前名称 | 纠正名称 |", "```limits");
      });
      assertThat(h.model.calls.get()).isZero();
    }
  }

  @Test void failedReadbackKeepsTheWriteCommittedAndCheckingRetriesOnlyTheQuery() throws Exception {
    try (var h = new Harness()) {
      h.failReadback.set(true);
      JsonNode result = h.execute("update_portfolio", edit("Saved Despite Read Failure", 1));
      assertThat(result.path("status").asString()).isEqualTo("applied");
      assertThat(result.path("verification").path("status").asString()).isEqualTo("unavailable");
      assertThat(PortfolioEditView.markdown(result)).contains("追加查询未完成").doesNotContain("```limits", "| 现金余额");
      assertThat(h.fixtures.portfolio("pf-001", "alice").version()).isEqualTo(2);
      assertThat(h.tools.editing().publicQuestion("user-001", h.session).isNull()).isTrue();
      h.failReadback.set(false);
      JsonNode checked = h.execute("check_edit_operation", Map.of());
      assertThat(checked.path("verification").path("status").asString()).isEqualTo("confirmed");
      assertThat(h.patchCalls.get()).isEqualTo(1);
    }
  }

  @Test void concurrentChangeAndForeignReadbacksNeverMasqueradeAsVerifiedRequestedValues() throws Exception {
    try (var h = new Harness()) {
      h.changeAfterWrite.set(true);
      JsonNode result = h.execute("update_portfolio", edit("First Name", 1));
      assertThat(result.path("portfolio").path("name").asString()).isEqualTo("First Name");
      assertThat(result.path("verification").path("status").asString()).isEqualTo("changed");
      String view = PortfolioEditView.markdown(result);
      assertThat(view).contains("对象又发生了变化", "| 当前名称 | 后续编辑 |", "| 版本 | 3 |");
      h.foreignReadback.set(true);
      JsonNode checked = h.execute("check_edit_operation", Map.of());
      assertThat(checked.path("verification").path("status").asString()).isEqualTo("unavailable");
      assertThat(checked.path("verification").has("portfolio")).isFalse();
      assertThat(h.patchCalls.get()).isEqualTo(1);
    }
  }

  private final class Harness implements AutoCloseable {
    final MockFinanceService fixtures = new MockFinanceService();
    final AtomicBoolean dropReply = new AtomicBoolean();
    final AtomicBoolean dropBeforeWrite = new AtomicBoolean();
    final AtomicBoolean failReadback = new AtomicBoolean();
    final AtomicBoolean changeAfterWrite = new AtomicBoolean();
    final AtomicBoolean foreignReadback = new AtomicBoolean();
    final List<String> requests = new java.util.concurrent.CopyOnWriteArrayList<>();
    final List<String> patchKeys = new ArrayList<>();
    final AtomicInteger patchCalls = new AtomicInteger();
    final ScriptedModel model = new ScriptedModel();
    final HttpServer api;
    final AgentProperties properties;
    final MemoryStore store;
    final FinanceApiClient finance;
    final ContextManager context;
    final ToolRegistry tools;
    final AgentService agent;
    final String session;
    Harness() throws Exception {
      api = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      api.createContext("/api/mock/", exchange -> {
        try (exchange) {
          String path = exchange.getRequestURI().getPath().substring("/api/mock".length());
          requests.add(exchange.getRequestMethod() + " " + path);
          String username = URLDecoder.decode(exchange.getRequestURI().getRawQuery().substring("username=".length()), StandardCharsets.UTF_8);
          Object response;
          int status = 200;
          try {
            if (exchange.getRequestMethod().equals("PATCH")) {
              patchCalls.incrementAndGet();
              patchKeys.add(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
              JsonNode body = mapper.readTree(exchange.getRequestBody());
              response = dropBeforeWrite.getAndSet(false) ? "broken" : fixtures.updatePortfolio(path.substring("/portfolios/".length()), username,
                  exchange.getRequestHeaders().getFirst("Idempotency-Key"), new MockFinanceService.PortfolioEdit(body.path("name").asString(), body.path("expectedVersion").asLong()));
              if (dropReply.getAndSet(false)) response = "broken";
            } else if (path.startsWith("/operations/")) response = fixtures.operation(path.substring("/operations/".length()), username);
            else if (path.equals("/portfolios")) response = fixtures.portfolios(null, username);
            else {
              String id = path.substring("/portfolios/".length());
              if (patchCalls.get() > 0 && failReadback.get()) throw new ApiRequestException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "Readback temporarily unavailable");
              if (patchCalls.get() > 0 && changeAfterWrite.getAndSet(false))
                fixtures.updatePortfolio(id, username, "external-change", new MockFinanceService.PortfolioEdit("后续编辑", fixtures.portfolio(id, username).version()));
              response = fixtures.portfolio(id, username);
              if (patchCalls.get() > 0 && foreignReadback.get()) {
                var foreign = (tools.jackson.databind.node.ObjectNode) mapper.valueToTree(response); foreign.put("username", "bob"); response = foreign;
              }
            }
          } catch (ApiRequestException error) { status = error.status().value(); response = error.body(); }
          byte[] bytes = response.equals("broken") ? "{".getBytes(StandardCharsets.UTF_8) : mapper.writeValueAsBytes(response);
          exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes);
        }
      });
      api.start();
      properties = new AgentProperties(root, URI.create("http://127.0.0.1:" + api.getAddress().getPort() + "/api/mock"),
          32000, 25000, 12000, 6000, 1800, 8000, 0, 6, 8, 3000, 120, 3, "low");
      store = new MemoryStore(properties, mapper, fixtures);
      finance = new FinanceApiClient(properties, mapper);
      context = new ContextManager(properties, AgentTestSupport.rag(), store, model, mapper);
      tools = registry();
      agent = new AgentService(properties, AgentTestSupport.rag(), store, context, tools, model, mapper, new SoulInstructions("classpath:SOUL.md"));
      session = store.createSession("user-001");
    }
    ToolRegistry registry() throws Exception {
      return new ToolRegistry(mapper, properties, store, context, finance, mock(RagChatService.class), new BusinessTools("classpath:tools.yaml"));
    }
    AgentTool.Scope scope() { return new AgentTool.Scope(store.user("user-001"), session, 0, new RunControl(60)); }
    JsonNode execute(String name, Map<String, Object> args) throws Exception { return tools.execute(name, mapper.valueToTree(args), scope()).output(); }
    JsonNode call(String name, String id, Map<String, Object> args) { return mapper.valueToTree(Map.of("type", "function_call", "call_id", id, "name", name, "arguments", mapper.writeValueAsString(args))); }
    List<Map<String, Object>> run(String message) throws Exception {
      var events = new ArrayList<Map<String, Object>>(); agent.run("user-001", session, message, events::add); return events;
    }
    public void close() throws IOException { agent.close(); store.close(); api.stop(0); }
  }

  private final class ScriptedModel implements Model {
    final List<Turn> turns = new ArrayList<>();
    final AtomicInteger calls = new AtomicInteger();
    final List<List<JsonNode>> inputs = new ArrayList<>();
    public Turn respond(String instructions, List<JsonNode> input, List<AgentTool> tools, boolean allowed, TextSink sink, RunControl control) throws IOException {
      inputs.add(List.copyOf(input)); Turn turn = turns.get(calls.getAndIncrement());
      if (!turn.text().isBlank()) sink.accept(turn.text()); return turn;
    }
    public String summarize(String instructions, String input, int maxTokens, RunControl control) { return "{\"memories\":[]}"; }
  }
}
