package com.example.ragbot;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Runs real HTTP, Spring controllers, streaming, tools and files; only OpenAI is simulated. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
final class AgentIntegrationTest {
  private static final ObjectMapper JSON = JsonMapper.builder().build();
  private static final BlockingQueue<JsonNode> REPLIES = new LinkedBlockingQueue<>();
  private static final List<JsonNode> REQUESTS = new CopyOnWriteArrayList<>();
  private static final Path ROOT = Path.of("target", "agent-integration-" + UUID.randomUUID());
  private static final HttpServer MODEL = startModel();
  private static volatile CountDownLatch stalled;
  private static volatile CountDownLatch release;
  private final HttpClient http = HttpClient.newHttpClient();
  @Autowired private Environment environment;
  @Autowired private MemoryStore store;
  @Autowired private ToolRegistry toolRegistry;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("rag.api-key", () -> "integration-test-only");
    registry.add("rag.chat-model", () -> "gpt-6-luna");
    registry.add("rag.openai-base-url", () -> "http://127.0.0.1:" + MODEL.getAddress().getPort() + "/v1");
    registry.add("rag.index-path", () -> ROOT.resolve("index.json").toString());
    registry.add("agent.memory-directory", () -> ROOT.resolve("memory").toString());
    registry.add("agent.finance-base-url", () -> "");
  }

  @BeforeEach
  void reset() throws Exception {
    REQUESTS.clear();
    REPLIES.clear();
    stalled = null;
    release = null;
    store.memory("user-001", "");
    store.memory("user-002", "");
    store.skipOldMemoryCandidates("user-001");
    store.skipOldMemoryCandidates("user-002");
  }

  @AfterAll
  static void stopModel() {
    MODEL.stop(0);
  }

  @Test
  void loopsThroughBusinessToolsPreservesReasoningAndRestoresHistory() throws Exception {
    String session = session("user-001");
    REPLIES.add(response(List.of(JSON.valueToTree(Map.of("type", "reasoning", "id", "rs_1",
        "summary", List.of(), "encrypted_content", "opaque-test-reasoning")), call("list_portfolios", Map.of(), "c1"))));
    answer("You have two mock portfolios [B1].");
    List<JsonNode> events = chat("user-001", session, "List my portfolios");
    assertThat(types(events)).contains("tool_start", "tool_end", "sources", "done").doesNotContain("error");
    JsonNode started = events.stream().filter(event -> event.path("type").asString().equals("tool_start")).findFirst().orElseThrow();
    JsonNode ended = events.stream().filter(event -> event.path("type").asString().equals("tool_end")).findFirst().orElseThrow();
    assertThat(started.path("id").asString()).isEqualTo("tool-c1");
    assertThat(started.path("name").asString()).isEqualTo("list_portfolios");
    assertThat(started.path("message").asString()).contains("投资组合");
    assertThat(ended.path("id").asString()).isEqualTo(started.path("id").asString());
    assertThat(ended.path("ok").asBoolean()).isTrue();
    assertThat(ended.path("elapsedMs").asLong()).isGreaterThanOrEqualTo(0);
    assertThat(types(events)).contains("activity_start", "activity_update", "activity_end");
    assertThat(REQUESTS).hasSize(2);
    JsonNode followup = REQUESTS.get(1);
    assertThat(followup.path("input").toString()).contains("opaque-test-reasoning");
    JsonNode output = toolResult(followup, "c1");
    assertThat(output.path("data").size()).isEqualTo(2);
    assertThat(output.path("data").toString()).contains("alice").doesNotContain("bob");
    assertThat(output.path("sources").get(0).path("source").asString()).startsWith("/api/mock/portfolios?username=alice");
    assertThat(followup.path("store").asBoolean()).isFalse();
    assertThat(followup.path("include").toString()).contains("reasoning.encrypted_content");
    JsonNode restored = jsonRequest("GET", "/api/agent/sessions/" + session, "user-001", null);
    assertThat(restored.path("messages").size()).isEqualTo(2);
    assertThat(restored.path("messages").get(1).path("content").asString()).contains("two mock portfolios");
    assertThat(store.session("user-001", session).turns().getFirst().output()).hasSize(4);
  }

  @Test
  void rejectsOtherUsersResourcesAndModelSuppliedIdentityThenRecovers() throws Exception {
    String session = session("user-001");
    REPLIES.add(response(List.of(call("get_portfolio", Map.of("id", "pf-003"), "other"),
        call("get_portfolio", Map.of("id", "pf-001", "username", "bob"), "identity"),
        call("get_portfolio", Map.of("id", "../pf-001"), "path"))));
    answer("Those queries are unavailable.");
    List<JsonNode> events = chat("user-001", session, "Try invalid resources");
    assertThat(types(events)).contains("done");
    assertThat(events.stream().filter(event -> event.path("type").asString().equals("tool_end") && !event.path("ok").asBoolean())).hasSize(3);
    assertThat(toolResult(REQUESTS.get(1), "other").path("status").asInt()).isEqualTo(404);
    assertThat(toolResult(REQUESTS.get(1), "identity").path("status").asInt()).isEqualTo(400);
    assertThat(toolResult(REQUESTS.get(1), "path").path("status").asInt()).isEqualTo(400);
    assertThat(request("GET", "/api/agent/sessions/" + session, "user-002", null).statusCode()).isEqualTo(404);
    assertThat(request("POST", "/api/chat", "user-002", Map.of("sessionId", session, "message", "Hello")).statusCode()).isEqualTo(404);
    assertThat(request("POST", "/api/agent/sessions", "../alice", null).statusCode()).isEqualTo(400);
  }

  @Test
  void comparesCurrenciesWithoutInventingFxAndRetrievesIssuerMetadata() throws Exception {
    String session = session("user-001");
    REPLIES.add(response(List.of(call("compare_portfolios", Map.of("leftId", "pf-001", "rightId", "pf-002"), "compare"),
        call("get_issuer", Map.of("id", "iss-001"), "issuer"))));
    answer("USD and HKD cannot be directly subtracted [B1]. Issuer metadata [B5].");
    List<JsonNode> events = chat("user-001", session, "Compare my portfolios and show issuer");
    assertThat(types(events)).contains("done");
    assertThat(events.stream().filter(event -> event.path("id").asString().equals("tool-compare")
        && event.path("type").asString().equals("tool_start")).findFirst().orElseThrow().path("detail").asString())
        .contains("pf-001", "pf-002");
    JsonNode comparison = toolResult(REQUESTS.get(1), "compare").path("data");
    assertThat(comparison.path("sameCurrency").asBoolean()).isFalse();
    assertThat(comparison.path("monetaryDifferences").size()).isZero();
    assertThat(comparison.path("leftAllocation").path("cashPctOfTotal").asDouble()).isEqualTo(37);
    assertThat(toolResult(REQUESTS.get(1), "issuer").path("data").path("id").asString()).isEqualTo("iss-001");
    REPLIES.add(response(List.of(call("compare_portfolios", Map.of("leftId", "pf-001", "rightId", "pf-001"), "same"))));
    answer("No differences [B1].");
    chat("user-001", session, "Compare the same portfolio");
    assertThat(toolResult(REQUESTS.get(3), "same").path("data").path("monetaryDifferences").path("totalValue").asDouble()).isZero();
  }

  @Test
  void searchesLocalVectorsAndBudgetsChineseExcerpts() throws Exception {
    String session = session("user-001");
    REPLIES.add(response(List.of(call("search_docs", Map.of("query", "订单规则"), "docs"))));
    answer("文档中的说明 [S1]。");
    assertThat(types(chat("user-001", session, "查询订单规则"))).contains("sources", "done");
    JsonNode result = toolResult(REQUESTS.get(1), "docs");
    assertThat(result.path("excerpts").asString()).contains("[S1]", "订单");
    assertThat(result.path("truncated").asBoolean()).isTrue();
    assertThat(TokenBudget.estimate(result)).isLessThanOrEqualTo(3_000);
  }

  @Test
  void remembersAcrossSessionsAndIsolatesUsersThenForgets() throws Exception {
    String first = session("user-001");
    REPLIES.add(response(List.of(call("remember", Map.of("key", "language", "content", "用户明确偏好中文回答。"), "save"))));
    answer("已记住中文偏好。");
    chat("user-001", first, "记住我偏好中文回答");
    assertThat(store.memory("user-001")).contains("## language", "中文");
    assertThat(store.memory("user-002")).isBlank();
    answer("你好。");
    chat("user-001", session("user-001"), "你好");
    assertThat(REQUESTS.get(2).path("instructions").asString()).contains("用户明确偏好中文回答");
    answer("Hello Bob.");
    chat("user-002", session("user-002"), "Hello");
    assertThat(REQUESTS.get(3).path("instructions").asString()).doesNotContain("用户明确偏好中文回答");
    REPLIES.add(response(List.of(call("forget_memory", Map.of("key", "language"), "forget"))));
    answer("已删除偏好。");
    chat("user-001", first, "忘记语言偏好");
    assertThat(store.memory("user-001")).doesNotContain("## language");
  }

  @Test
  void automaticallyExtractsExplicitPreferencesAndExposesMemoryEditor() throws Exception {
    String session = session("user-001");
    answer("Understood.");
    chat("user-001", session, "我偏好简短的回答");
    answer("Okay.");
    chat("user-001", session, "这是一个后续问题");
    answer("Done.");
    answer("{\"memories\":[{\"key\":\"answer_style\",\"content\":\"用户明确偏好简短回答。\"}]}");
    assertThat(types(chat("user-001", session, "第三个问题"))).contains("done");
    assertThat(store.memory("user-001")).contains("## answer_style");
    assertThat(REQUESTS.getLast().path("stream").asBoolean()).isFalse();
    assertThat(store.memoryCursor("user-001", session)).isEqualTo(3);
    jsonRequest("PUT", "/api/agent/memory", "user-001", Map.of("content", "## language\n中文\n"));
    assertThat(jsonRequest("GET", "/api/agent/memory", "user-001", null).path("content").asString()).contains("中文");
    jsonRequest("DELETE", "/api/agent/memory", "user-001", null);
    assertThat(store.memory("user-001")).isBlank();
  }

  @Test
  void automaticallyCompactsLongHistoryWithoutDeletingOriginals() throws Exception {
    String session = session("user-001");
    String longAnswer = "Earlier portfolio snapshot pf-001. ".repeat(4_000);
    store.append("user-001", session, AgentTestSupport.turn(JSON, 1, "Earlier comparison", longAnswer));
    answer("Historical turn 1: discussed pf-001; full details remain in history.");
    answer("Continuing from your earlier comparison.");
    assertThat(types(chat("user-001", session, "Continue"))).contains("context_compacted", "done");
    MemoryStore.SessionData state = store.session("user-001", session);
    assertThat(state.summarizedThrough()).isEqualTo(1);
    assertThat(state.turns().getFirst().answer()).isEqualTo(longAnswer);
    assertThat(REQUESTS.get(1).path("input").toString()).contains("Historical session summary").doesNotContain(longAnswer);
    assertThat(store.searchHistory("user-001", "pf-001")).isNotEmpty();
    assertThat(store.searchHistory("user-002", "pf-001")).isEmpty();
  }

  @Test
  void archivesFullMemoryBeforeCompressingIt() throws Exception {
    String original = "## preferences\n" + "用户明确偏好中文与简短回答。".repeat(200);
    answer("## preferences\n用户明确偏好中文与简短回答。");
    JsonNode updated = jsonRequest("PUT", "/api/agent/memory", "user-001", Map.of("content", original));
    assertThat(TokenBudget.estimate(updated.path("content").asString())).isLessThanOrEqualTo(1_800);
    try (var archives = Files.list(ROOT.resolve("memory/users/user-001/archive"))) {
      boolean found = false;
      for (Path path : archives.toList()) if (Files.readString(path).equals(original)) found = true;
      assertThat(found).isTrue();
    }
  }

  @Test
  void incompleteModelResponseNeverBecomesSuccessfulHistory() throws Exception {
    String session = session("user-001");
    REPLIES.add(JSON.valueToTree(Map.of("status", "incomplete", "output", List.of(AgentTestSupport.answer(JSON, "Partial answer")))));
    List<JsonNode> events = chat("user-001", session, "Hello");
    assertThat(types(events)).contains("error").doesNotContain("done");
    assertThat(store.session("user-001", session).turns().getFirst().completed()).isFalse();
  }

  @Test
  void forcesFinalAnswerWhenRoundBudgetIsReached() throws Exception {
    String session = session("user-001");
    for (int i = 0; i < 12; i++) REPLIES.add(response(List.of(call("get_accounts", Map.of(), "round" + i))));
    answer("Account overview from the available results [B12]. Remaining questions need another query.");
    assertThat(types(chat("user-001", session, "Check accounts"))).contains("done");
    assertThat(REQUESTS).hasSize(13);
    assertThat(REQUESTS.getLast().path("tool_choice").asString()).isEqualTo("none");
    assertThat(store.session("user-001", session).turns().getFirst().completed()).isFalse();
  }

  @Test
  void reportsAuthenticationFailuresWithoutLeakingProviderMessagesAndAllowsRetry() throws Exception {
    String session = session("user-001");
    REPLIES.add(JSON.valueToTree(Map.of("httpStatus", 401, "error",
        Map.of("code", "invalid_api_key", "message", "Incorrect API key provided: secret-upstream-credential"))));
    List<JsonNode> events = chat("user-001", session, "What tools do you have?");
    assertThat(types(events)).contains("error").doesNotContain("done");
    assertThat(events.toString()).contains("API key", "401", "invalid_api_key").doesNotContain("secret-upstream-credential");
    assertThat(store.session("user-001", session).turns().getFirst().completed()).isFalse();
    answer("I can query portfolios and search documents.");
    assertThat(types(chat("user-001", session, "Retry"))).contains("done");
  }

  @Test
  void cancellationStopsBlockedModelStreamAndReleasesUserLock() throws Exception {
    String session = session("user-001");
    stalled = new CountDownLatch(1);
    release = new CountDownLatch(1);
    REPLIES.add(JSON.valueToTree(Map.of("stall", true)));
    var pending = http.sendAsync(builder("POST", "/api/chat", "user-001", Map.of("sessionId", session, "message", "Wait")).build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertThat(stalled.await(10, TimeUnit.SECONDS)).isTrue();
    // Read requests and creating another chat must finish while the model remains blocked.
    for (String path : List.of("/api/agent/memory", "/api/agent/sessions", "/api/agent/sessions/" + session)) {
      assertThat(http.sendAsync(builder("GET", path, "user-001", null).build(), HttpResponse.BodyHandlers.ofString())
          .get(2, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
    }
    assertThat(http.sendAsync(builder("POST", "/api/agent/sessions", "user-001", null).build(), HttpResponse.BodyHandlers.ofString())
        .get(2, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
    assertThat(request("POST", "/api/chat", "user-001", Map.of("sessionId", session, "message", "Competing request")).body())
        .contains("Another request is active", "\"type\":\"error\"");
    assertThat(request("PUT", "/api/agent/memory", "user-001", Map.of("content", "Busy")).statusCode()).isEqualTo(409);
    assertThat(jsonRequest("POST", "/api/agent/sessions/" + session + "/cancel", "user-001", null).path("cancelled").asBoolean()).isTrue();
    release.countDown();
    assertThat(pending.get(10, TimeUnit.SECONDS).body()).contains("\"type\":\"error\"");
    assertThat(store.session("user-001", session).turns().getFirst().completed()).isFalse();
    answer("Retry works.");
    assertThat(types(chat("user-001", session, "Retry"))).contains("done");
  }

  @Test
  void streamsExecutionProgressBeforeModelFinishesAndUsesCoordinatedDeadlines() throws Exception {
    assertThat(environment.getProperty("agent.request-timeout-seconds", Integer.class)).isEqualTo(240);
    assertThat(environment.getProperty("spring.mvc.async.request-timeout")).isEqualTo("270s");
    String session = session("user-001");
    stalled = new CountDownLatch(1);
    release = new CountDownLatch(1);
    REPLIES.add(JSON.valueToTree(Map.of("stall", true)));
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var pending = http.sendAsync(builder("POST", "/api/chat", "user-001", Map.of("sessionId", session, "message", "Wait"))
          .build(), HttpResponse.BodyHandlers.ofInputStream());
      var response = pending.get(5, TimeUnit.SECONDS);
      try (var reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
        assertThat(stalled.await(5, TimeUnit.SECONDS)).isTrue();
        JsonNode live = executor.submit(() -> {
          for (String line; (line = reader.readLine()) != null;) {
            JsonNode event = JSON.readTree(line);
            if (event.path("type").asString().equals("activity_start") && event.path("id").asString().equals("model-0")) return event;
          }
          throw new AssertionError("Missing live model progress");
        }).get(5, TimeUnit.SECONDS);
        assertThat(live.path("message").asString()).contains("分析请求");
        assertThat(release.getCount()).isEqualTo(1); // Progress arrived while the provider is still blocked.
        jsonRequest("POST", "/api/agent/sessions/" + session + "/cancel", "user-001", null);
        release.countDown();
        String terminal = executor.submit(() -> reader.lines().reduce("", (a, b) -> a + b)).get(5, TimeUnit.SECONDS);
        assertThat(terminal).contains("\"type\":\"error\"").doesNotContain("\"type\":\"done\"");
      } finally { release.countDown(); }
    }
  }

  private String session(String userId) throws Exception {
    return jsonRequest("POST", "/api/agent/sessions", userId, null).path("id").asString();
  }

  @Test
  void oversizedCallBatchPairsSkippedCallsAndReturnsPartialAnswer() throws Exception {
    String session = session("user-001");
    var calls = new java.util.ArrayList<JsonNode>();
    for (int i = 0; i < 10; i++) calls.add(call("get_accounts", Map.of(), "batch" + i));
    REPLIES.add(response(calls));
    answer("Retrieved accounts [B1]; remaining queries were skipped.");
    var events = chat("user-001", session, "Check many accounts");
    assertThat(types(events)).contains("done").doesNotContain("error");
    assertThat(toolResult(REQUESTS.getLast(), "batch7").path("data").isArray()).isTrue();
    assertThat(toolResult(REQUESTS.getLast(), "batch8").path("status").asInt()).isEqualTo(429);
    assertThat(toolResult(REQUESTS.getLast(), "batch9").path("status").asInt()).isEqualTo(429);
    assertThat(REQUESTS.getLast().path("tool_choice").asString()).isEqualTo("none");
    assertThat(events.getLast().path("partial").asBoolean()).isTrue();
    assertThat(events.getLast().path("toolCalls").asInt()).isEqualTo(8);
    assertThat(store.session("user-001", session).turns().getFirst().completed()).isFalse();
  }

  @Test
  void totalCallBudgetAppliesAcrossRoundsWithoutDiscardingResults() throws Exception {
    String session = session("user-001");
    for (int round = 0; round < 2; round++) {
      var calls = new java.util.ArrayList<JsonNode>();
      for (int i = 0; i < 8; i++) calls.add(call("get_accounts", Map.of(), "total" + round + "-" + i));
      REPLIES.add(response(calls));
    }
    answer("Available account data [B12]; additional calls were not executed.");
    var events = chat("user-001", session, "Many account checks");
    assertThat(REQUESTS).hasSize(3);
    assertThat(toolResult(REQUESTS.getLast(), "total1-3").has("data")).isTrue();
    assertThat(toolResult(REQUESTS.getLast(), "total1-4").path("status").asInt()).isEqualTo(429);
    assertThat(events.getLast().path("toolCalls").asInt()).isEqualTo(12);
    assertThat(events.getLast().path("partial").asBoolean()).isTrue();
  }

  @Test
  void modelFailureAfterSuccessfulQuerySynthesizesFromFactsWithoutTools() throws Exception {
    String session = session("user-001");
    REPLIES.add(response(List.of(call("get_portfolio", Map.of("id", "pf-001"), "retrieved"))));
    REPLIES.add(JSON.valueToTree(Map.of("httpStatus", 500, "error", Map.of("message", "secret-provider-details"))));
    answer("Partial result: portfolio pf-001 [B1]. The rest needs another query.");
    var events = chat("user-001", session, "Compare portfolio and accounts");
    assertThat(types(events)).contains("done").doesNotContain("error");
    assertThat(events.getLast().path("partial").asBoolean()).isTrue();
    assertThat(events.toString()).doesNotContain("secret-provider-details");
    assertThat(REQUESTS.getLast().path("tool_choice").asString()).isEqualTo("none");
    assertThat(REQUESTS.getLast().path("input").toString()).contains("pf-001").doesNotContain("function_call");
    var saved = store.session("user-001", session).turns().getFirst();
    assertThat(saved.completed()).isFalse();
    assertThat(saved.output().toString()).contains("retrieved", "pf-001");
    answer("Continue querying missing data.");
    assertThat(types(chat("user-001", session, "Continue"))).contains("done");
  }

  @Test
  void failedSynthesisStillReturnsRetrievedDataAndSources() throws Exception {
    String session = session("user-001");
    REPLIES.add(response(List.of(call("get_portfolio", Map.of("id", "pf-001"), "retrieved"))));
    for (int i = 0; i < 2; i++) REPLIES.add(JSON.valueToTree(Map.of("httpStatus", 500,
        "error", Map.of("message", "secret-provider-details"))));
    var events = chat("user-001", session, "查询投资组合和账户");
    assertThat(types(events)).contains("done").doesNotContain("error");
    assertThat(events.toString()).contains("任务未全部完成", "pf-001").doesNotContain("secret-provider-details");
    assertThat(store.session("user-001", session).turns().getFirst().sources()).hasSize(1);
    assertThat(store.session("user-001", session).turns().getFirst().completed()).isFalse();
  }

  @Test
  void queryDeadlineStopsWorkButAllowsOneFinalSynthesis() throws Exception {
    toolRegistry.register("expire_work_test", "Test the query deadline", Map.of(), (arguments, scope) -> {
      scope.control().finishWork(); scope.control().check();
      throw new AssertionError("Expired work must not continue");
    });
    String session = session("user-001");
    REPLIES.add(response(List.of(call("get_portfolio", Map.of("id", "pf-001"), "retrieved"))));
    REPLIES.add(response(List.of(call("expire_work_test", Map.of(), "expire"))));
    answer("Partial portfolio result [B1]; query time was exhausted.");
    var events = chat("user-001", session, "Read portfolio then do more work");
    assertThat(types(events)).contains("done").doesNotContain("error");
    assertThat(events.getLast().path("reason").asString()).isEqualTo("query_time_budget");
    assertThat(REQUESTS.getLast().path("tool_choice").asString()).isEqualTo("none");
  }

  private List<JsonNode> chat(String userId, String session, String message) throws Exception {
    HttpResponse<String> result = request("POST", "/api/chat", userId, Map.of("sessionId", session, "message", message));
    assertThat(result.statusCode()).isEqualTo(200);
    return result.body().lines().filter(line -> !line.isBlank()).map(JSON::readTree).toList();
  }

  private static List<String> types(List<JsonNode> events) {
    return events.stream().map(event -> event.path("type").asString()).toList();
  }

  private JsonNode jsonRequest(String method, String path, String user, Object body) throws Exception {
    HttpResponse<String> result = request(method, path, user, body);
    assertThat(result.statusCode()).isEqualTo(200);
    return JSON.readTree(result.body());
  }

  private HttpResponse<String> request(String method, String path, String user, Object body) throws Exception {
    return http.send(builder(method, path, user, body).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private HttpRequest.Builder builder(String method, String path, String user, Object body) {
    return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + environment.getProperty("local.server.port") + path))
        .timeout(Duration.ofSeconds(20)).header("X-Mock-User-Id", user).header("Content-Type", "application/json")
        .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
  }

  private static void answer(String text) {
    REPLIES.add(response(List.of(AgentTestSupport.answer(JSON, text))));
  }

  private static JsonNode response(List<JsonNode> output) {
    return JSON.valueToTree(Map.of("status", "completed", "output", output, "usage", Map.of("input_tokens", 1_000, "output_tokens", 100)));
  }

  private static JsonNode call(String name, Map<String, ?> args, String id) {
    return JSON.valueToTree(Map.of("type", "function_call", "name", name, "call_id", id, "arguments", JSON.writeValueAsString(args)));
  }

  @Test
  void pagedHistoryToolsKeepTheAnchorBoundedAndRejectForeignSessions() throws Exception {
    String previous = session("user-001");
    for (int i = 1; i <= 20; i++) store.append("user-001", previous,
        AgentTestSupport.turn(JSON, i, "portfolio pf-history " + i, "历史现金比例说明。".repeat(1000)));
    JsonNode page = jsonRequest("GET", "/api/agent/sessions/" + previous + "?offset=5&limit=3", "user-001", null);
    assertThat(page.path("messages").size()).isEqualTo(6);
    assertThat(page.path("nextOffset").asInt()).isEqualTo(8);
    assertThat(page.path("total").asInt()).isEqualTo(20);
    String current = session("user-001");
    REPLIES.add(response(List.of(call("search_history", Map.of("query", "pf-history", "offset", 0, "limit", 2), "find"))));
    REPLIES.add(response(List.of(call("search_history", Map.of("sessionId", previous, "aroundTurn", 10, "window", 10), "window"))));
    answer("Recovered historical context; current figures need a new query.");
    assertThat(types(chat("user-001", current, "Find earlier portfolio discussions"))).contains("done").doesNotContain("error");
    assertThat(toolResult(REQUESTS.get(1), "find").path("results").size()).isEqualTo(2);
    JsonNode window = toolResult(REQUESTS.get(2), "window");
    assertThat(TokenBudget.estimate(window)).isLessThanOrEqualTo(3000);
    assertThat(window.path("turns").get(0).path("turn").asInt()).isEqualTo(10);
    assertThat(request("GET", "/api/agent/sessions/" + previous + "/export", "user-002", null).statusCode()).isEqualTo(404);
    assertThat(request("DELETE", "/api/agent/sessions/" + previous, "user-002", null).statusCode()).isEqualTo(404);
    JsonNode memory = jsonRequest("PUT", "/api/agent/memory", "user-001", Map.of(
        "user", "## language\nChinese", "notes", "## report_currency\nUSD"));
    assertThat(memory.path("user").asString()).contains("Chinese");
    assertThat(memory.path("notes").asString()).contains("USD");
    assertThat(jsonRequest("GET", "/api/agent/storage", "user-001", null).path("quotaBytes").asLong()).isEqualTo(524288000);
  }

  @Test
  void oversizedBusinessResultsAreSavedOnceAndCanBeReadInBudgetedChunks() throws Exception {
    toolRegistry.register("large_finance_test", "Test a large financial response", Map.of(), (arguments, scope) ->
        new ToolRegistry.Result(JSON.valueToTree(Map.of("resourceId", "pf-payload", "asOf", "2026-10-10",
            "rows", "financial snapshot ".repeat(2000))), List.of()));
    String id = session("user-001");
    REPLIES.add(response(List.of(call("large_finance_test", Map.of(), "large"))));
    answer("Large result saved for focused reading.");
    assertThat(types(chat("user-001", id, "Read a large financial result"))).contains("done").doesNotContain("error");
    JsonNode saved = toolResult(REQUESTS.get(1), "large");
    assertThat(saved.path("payloadRef").asString()).endsWith(".json");
    assertThat(TokenBudget.estimate(saved)).isLessThanOrEqualTo(3000);
    assertThat(store.searchHistory("user-001", "pf-payload")).isNotEmpty();
    REPLIES.add(response(List.of(call("search_history", Map.of("sessionId", id,
        "payloadRef", saved.path("payloadRef").asString(), "payloadOffset", 0), "read"))));
    answer("Read the first historical snapshot chunk.");
    assertThat(types(chat("user-001", id, "Read that saved result"))).contains("done").doesNotContain("error");
    JsonNode part = toolResult(REQUESTS.get(3), "read");
    assertThat(part.path("content").asString()).contains("financial snapshot");
    assertThat(part.path("nextOffset").asInt()).isGreaterThan(0);
    assertThat(TokenBudget.estimate(part)).isLessThanOrEqualTo(3000);
    var exported = request("GET", "/api/agent/sessions/" + id + "/export", "user-001", null);
    assertThat(exported.statusCode()).isEqualTo(200);
    JsonNode original = JSON.readTree(exported.body().lines().findFirst().orElseThrow());
    assertThat(original.path("output").toString()).contains("financial snapshot ".repeat(100));
  }

  private static JsonNode toolResult(JsonNode request, String id) {
    for (JsonNode input : request.path("input")) {
      if (input.path("type").asString("").equals("function_call_output") && input.path("call_id").asString().equals(id)) {
        return JSON.readTree(input.path("output").asString());
      }
    }
    throw new AssertionError("Missing tool result " + id);
  }

  private static HttpServer startModel() {
    try {
      Files.createDirectories(ROOT);
      JSON.writeValue(ROOT.resolve("index.json").toFile(), new RagIndexDocument(1, "2026-10-10", "test-embedding", 2,
          List.of(new RagChunk("test", "orders", "Orders", "Rules", "https://example.com/orders",
              "订单规则与账户说明。".repeat(2_000), new double[]{1, 0}))));
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
      server.createContext("/v1/embeddings", exchange -> sendJson(exchange,
          Map.of("data", List.of(Map.of("index", 0, "embedding", List.of(1, 0))))));
      server.createContext("/v1/responses", AgentIntegrationTest::modelResponse);
      server.start();
      return server;
    } catch (IOException error) {
      throw new ExceptionInInitializerError(error);
    }
  }

  private static void modelResponse(HttpExchange exchange) throws IOException {
    try (exchange) {
      JsonNode request = JSON.readTree(exchange.getRequestBody().readAllBytes());
      REQUESTS.add(request);
      JsonNode reply = REPLIES.poll();
      if (reply == null) {
        exchange.sendResponseHeaders(500, -1);
        return;
      }
      if (reply.has("httpStatus")) {
        byte[] bytes = JSON.writeValueAsBytes(Map.of("error", reply.path("error")));
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(reply.path("httpStatus").asInt(), bytes.length);
        exchange.getResponseBody().write(bytes);
        return;
      }
      if (!request.path("stream").asBoolean()) {
        sendJson(exchange, reply);
        return;
      }
      exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
      exchange.sendResponseHeaders(200, 0);
      if (reply.path("stall").asBoolean()) {
        exchange.getResponseBody().write("data: {\"type\":\"response.created\"}\n\n".getBytes(StandardCharsets.UTF_8));
        exchange.getResponseBody().flush();
        stalled.countDown();
        if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("Test stream was not released");
        return;
      }
      if (reply.path("status").asString().equals("completed")) {
        for (JsonNode item : reply.path("output")) {
          if (item.path("type").asString().equals("message")) {
            for (JsonNode content : item.path("content")) {
              event(exchange, Map.of("type", "response.output_text.delta", "delta", content.path("text").asString()));
            }
          }
        }
      }
      event(exchange, Map.of("type", reply.path("status").asString().equals("completed") ? "response.completed" : "response.incomplete", "response", reply));
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IOException(error);
    }
  }

  private static void event(HttpExchange exchange, Object body) throws IOException {
    exchange.getResponseBody().write(("data: " + JSON.writeValueAsString(body) + "\n\n").getBytes(StandardCharsets.UTF_8));
    exchange.getResponseBody().flush();
  }

  private static void sendJson(HttpExchange exchange, Object body) throws IOException {
    byte[] bytes = JSON.writeValueAsBytes(body);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }
}
