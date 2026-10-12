package com.example.ragbot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class PortfolioToolsTest {
  @TempDir Path root;
  private final JsonMapper mapper = new JsonMapper();
  private final MockFinanceService fixtures = new MockFinanceService();
  private final AtomicInteger fetches = new AtomicInteger();
  private final MutableClock clock = new MutableClock();
  private HttpServer api;
  private MemoryStore store;
  private FinanceApiClient finance;
  private ToolRegistry registry;
  private PortfolioTools portfolios;
  private AgentProperties agent;
  private AgentTool.Scope scope;
  private byte[] response;

  @BeforeEach
  void setup() throws Exception {
    response = mapper.writeValueAsBytes(fixtures.portfolios(null, "alice"));
    api = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    api.createContext("/api/mock/portfolios", exchange -> {
      try (exchange) {
        fetches.incrementAndGet();
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
      }
    });
    api.start();
    agent = new AgentProperties(root.resolve("memory"), URI.create("http://127.0.0.1:" + api.getAddress().getPort() + "/api/mock"),
        64_000, 48_000, 32_000, 16_000, 1800, 8000, 0, 16, 8, 3000, 240, 3, "low");
    finance = new FinanceApiClient(agent, mapper);
    store = new MemoryStore(agent, mapper, fixtures);
    Path config = root.resolve("tools.yaml");
    Files.writeString(config, """
        tools:
          - name: get_report
            description: Test report
            title: Report
            status: 查询
            method: GET
            path: /report
        """);
    registry = new ToolRegistry(mapper, agent, store, mock(ContextManager.class), finance,
        mock(RagChatService.class), new BusinessTools(config.toUri().toString()));
    portfolios = new PortfolioTools(mapper, store, finance, agent, ResultSetProperties.defaults(), clock);
    portfolios.register(registry, new BusinessTools("classpath:tools.yaml").definitions().getFirst());
    scope = new AgentTool.Scope(store.user("user-001"), store.createSession("user-001"), 0, new RunControl(60));
  }

  @AfterEach
  void cleanup() throws Exception {
    if (api != null) api.stop(0);
    if (store != null) store.close();
  }

  @Test
  void repeatedListsAndFollowupsReuseAll3000RecordsWithExactTotals() throws Exception {
    JsonNode first = execute("list_portfolios", Map.of());
    assertThat(response.length).isGreaterThan(131_072);
    assertThat(first.path("total").asInt()).isEqualTo(3000);
    assertThat(first.path("complete").asBoolean()).isTrue();
    assertThat(first.path("items").size()).isBetween(1, 10);
    assertThat(first.path("hasMore").asBoolean()).isTrue();
    assertThat(first.path("reused").asBoolean()).isFalse();
    JsonNode again = execute("list_portfolios", Map.of());
    assertThat(again.path("resultSetId")).isEqualTo(first.path("resultSetId"));
    assertThat(again.path("reused").asBoolean()).isTrue();
    JsonNode usd = execute("query_portfolios", Map.of("currency", "USD"));
    JsonNode top = execute("query_portfolios", Map.of("sortBy", "totalValue", "descending", true, "limit", 5));
    assertThat(top.path("view").path("currency").asString()).isEqualTo("USD");
    assertThat(top.path("items").size()).isEqualTo(5);
    assertThat(new BigDecimal(top.path("items").get(0).path("totalValue").asString()))
        .isGreaterThan(new BigDecimal(top.path("items").get(4).path("totalValue").asString()));
    JsonNode summary = execute("summarize_portfolios", Map.of());
    var expected = fixtures.portfolios(null, "alice").stream().filter(row -> row.currency().equals("USD")).toList();
    assertThat(summary.path("matched").asInt()).isEqualTo(expected.size());
    assertThat(usd.path("matched").asInt()).isEqualTo(expected.size());
    JsonNode group = summary.path("byCurrency").get(0);
    assertThat(group.path("currency").asString()).isEqualTo("USD");
    assertThat(new BigDecimal(group.path("totalValue").asString())).isEqualByComparingTo(expected.stream()
        .map(MockFinanceService.Portfolio::totalValue).reduce(BigDecimal.ZERO, BigDecimal::add));
    assertThat(group.path("selectedCount").asInt()).isEqualTo(5);
    BigDecimal selected = BigDecimal.ZERO;
    for (JsonNode row : top.path("items")) selected = selected.add(new BigDecimal(row.path("totalValue").asString()));
    assertThat(new BigDecimal(group.path("selectedTotalValue").asString())).isEqualByComparingTo(selected);
    assertThat(group.path("selectedShareOfFilteredTotalPct").asDouble()).isBetween(0.0, 100.0);
    assertThat(fetches.get()).isEqualTo(1);
  }

  @Test
  void pagesAndProjectsRecordsAndRejectsUnboundedOrCrossCurrencyQueries() throws Exception {
    JsonNode first = execute("query_portfolios", Map.of("limit", 20, "fields", "id,name,currency,totalValue"));
    int next = first.path("nextOffset").asInt();
    JsonNode second = execute("query_portfolios", Map.of("offset", next, "limit", 20));
    assertThat(second.path("offset").asInt()).isEqualTo(next);
    assertThat(second.path("items").get(0).path("id")).isNotEqualTo(first.path("items").get(0).path("id"));
    JsonNode reset = execute("query_portfolios", Map.of("currency", "HKD", "name", "Demo", "id", "pf-3002"));
    assertThat(reset.path("matched").asInt()).isEqualTo(1);
    assertThat(reset.path("items").get(0).size()).isEqualTo(4);
    assertThat(execute("query_portfolios", Map.of("resetView", true)).path("matched").asInt()).isEqualTo(3000);
    assertThatThrownBy(() -> execute("query_portfolios", Map.of("limit", 21))).hasMessageContaining("Invalid tool argument");
    assertThatThrownBy(() -> execute("query_portfolios", Map.of("offset", -1))).hasMessageContaining("Invalid tool argument");
    assertThatThrownBy(() -> execute("query_portfolios", Map.of("sortBy", "totalValue"))).hasMessageContaining("one currency");
    assertThatThrownBy(() -> execute("query_portfolios", Map.of("fields", "secret"))).hasMessageContaining("fields");
    assertThat(execute("query_portfolios", Map.of("id", "missing")).path("matched").asInt()).isZero();
    assertThat(fetches.get()).isEqualTo(1);
  }

  @Test
  void concurrentIdenticalLoadsFetchOnce() throws Exception {
    try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      var tasks = java.util.stream.IntStream.range(0, 8)
          .mapToObj(ignored -> workers.submit(() -> execute("list_portfolios", Map.of()).path("resultSetId").asString())).toList();
      var ids = new java.util.HashSet<String>();
      for (var task : tasks) ids.add(task.get());
      assertThat(ids).hasSize(1);
      assertThat(fetches.get()).isEqualTo(1);
    }
  }

  @Test
  void snapshotAndViewSurviveRestartAndRemainAvailableOutsideConversationHistory() throws Exception {
    String id = execute("list_portfolios", Map.of()).path("resultSetId").asString();
    execute("query_portfolios", Map.of("currency", "USD", "limit", 5));
    store.close();
    store = new MemoryStore(agent, mapper, fixtures);
    portfolios = new PortfolioTools(mapper, store, finance, agent, ResultSetProperties.defaults(), clock);
    String hint = portfolios.contextHint(store.user("user-001"), scope.sessionId());
    assertThat(hint).contains(id, "USD", "selectedIds", "available").doesNotContain("rows");
    var sets = new PortfolioResultSets(store, mapper, finance, ResultSetProperties.defaults(), clock);
    assertThat(sets.acquire(scope, false).rows().size()).isEqualTo(3000);
    assertThat(sets.view(scope, id).path("selectedIds").size()).isEqualTo(5);
    assertThat(fetches.get()).isEqualTo(1);
  }

  @Test
  void explicitRefreshKeepsOldSnapshotAndViewAndExpiryRequiresNewSnapshot() throws Exception {
    String old = execute("list_portfolios", Map.of()).path("resultSetId").asString();
    execute("query_portfolios", Map.of("currency", "USD", "limit", 5));
    String refreshed = execute("list_portfolios", Map.of("refresh", true)).path("resultSetId").asString();
    assertThat(refreshed).isNotEqualTo(old);
    assertThat(execute("summarize_portfolios", Map.of("resultSetId", old)).path("byCurrency").get(0)
        .path("selectedCount").asInt()).isEqualTo(5);
    assertThat(execute("summarize_portfolios", Map.of()).path("resultSetId").asString()).isEqualTo(old);
    assertThat(portfolios.contextHint(scope.user(), scope.sessionId())).contains(old);
    assertThat(fetches.get()).isEqualTo(2);
    clock.now = clock.now.plusSeconds(1801);
    assertThatThrownBy(() -> execute("query_portfolios", Map.of("resultSetId", old))).hasMessageContaining("expired");
    assertThat(fetches.get()).isEqualTo(2);
    String renewed = execute("list_portfolios", Map.of()).path("resultSetId").asString();
    assertThat(renewed).isNotEqualTo(refreshed);
    assertThat(fetches.get()).isEqualTo(3);
    assertThat(Files.exists(root.resolve("memory/users/user-001/sessions/" + scope.sessionId()
        + "/portfolio-resultsets/" + old + ".json"))).isFalse();
  }

  @Test
  void rejectsOtherUsersAndSessionsAndCannotTraversePaths() throws Exception {
    String id = execute("list_portfolios", Map.of()).path("resultSetId").asString();
    for (String user : List.of("user-001", "user-002")) {
      var other = new AgentTool.Scope(store.user(user), store.createSession(user), 0, new RunControl(60));
      assertThatThrownBy(() -> registry.execute("query_portfolios", mapper.valueToTree(Map.of("resultSetId", id)), other))
          .hasMessageContaining("not found");
    }
    assertThatThrownBy(() -> execute("query_portfolios", Map.of("resultSetId", "../active"))).hasMessageContaining("Invalid");
    assertThatThrownBy(() -> execute("list_portfolios", Map.of("username", "bob"))).hasMessageContaining("Unexpected");
    store.deleteSession("user-001", scope.sessionId());
    assertThatThrownBy(() -> execute("query_portfolios", Map.of("resultSetId", id))).hasMessageContaining("Conversation not found");
    assertThat(fetches.get()).isEqualTo(1);
  }

  @Test
  void manyCurrencyGroupsArePagedWithinBudgetAndDecimalsRemainExact() throws Exception {
    var rows = mapper.createArrayNode();
    var source = fixtures.portfolios(null, "alice");
    for (int i = 0; i < 24; i++) {
      var row = (tools.jackson.databind.node.ObjectNode) mapper.valueToTree(source.get(i));
      row.put("currency", "AA" + (char) ('A' + i));
      row.put("cashBalance", new BigDecimal("0.10")); row.put("marketValue", new BigDecimal("0.20"));
      row.put("totalValue", new BigDecimal("0.30")); rows.add(row);
    }
    response = mapper.writeValueAsBytes(rows);
    execute("list_portfolios", Map.of());
    int offset = 0, count = 0;
    BigDecimal total = BigDecimal.ZERO;
    do {
      JsonNode page = execute("summarize_portfolios", Map.of("currencyOffset", offset));
      assertThat(page.path("matched").asInt()).isEqualTo(24);
      assertThat(page.path("currencyGroups").asInt()).isEqualTo(24);
      assertThat(page.path("groupsReturned").asInt()).isBetween(1, 23);
      count += page.path("groupsReturned").asInt();
      for (JsonNode group : page.path("byCurrency")) total = total.add(new BigDecimal(group.path("totalValue").asString()));
      if (page.path("nextCurrencyOffset").isNull()) break;
      offset = page.path("nextCurrencyOffset").asInt();
    } while (offset < 24);
    assertThat(count).isEqualTo(24);
    assertThat(total).isEqualByComparingTo("7.20");
    assertThat(fetches.get()).isEqualTo(1);
  }

  @Test
  void resultSetsRespectUserStorageQuotaAndCancelledRequestsDoNotFetch() throws Exception {
    scope.control().cancel();
    assertThatThrownBy(() -> execute("list_portfolios", Map.of())).hasMessageContaining("cancelled");
    assertThat(fetches.get()).isZero();
    store.close();
    var limits = new HistoryProperties(30, 365, true, 128 * 1024, 8192, 50, 3600);
    store = new MemoryStore(agent, limits, mapper, fixtures);
    var sets = new PortfolioResultSets(store, mapper, finance, ResultSetProperties.defaults(), clock);
    var uncancelled = new AgentTool.Scope(store.user("user-001"), scope.sessionId(), 0, new RunControl(60));
    assertThatThrownBy(() -> sets.acquire(uncancelled, false)).hasMessageContaining("storage quota");
    assertThat(sets.state(uncancelled).has("resultSetId")).isFalse();
  }

  @Test
  void invalidIncompleteOrOversizedResponsesNeverReplaceValidSnapshot() throws Exception {
    String id = execute("list_portfolios", Map.of()).path("resultSetId").asString();
    byte[] valid = response;
    for (byte[] bad : List.of("[".getBytes(StandardCharsets.UTF_8),
        mapper.writeValueAsBytes(fixtures.portfolios(null, "bob")),
        mapper.writeValueAsBytes(List.of(fixtures.portfolio("pf-001", "alice"), fixtures.portfolio("pf-001", "alice"))),
        "[] {}".getBytes(StandardCharsets.UTF_8), new byte[8 * 1024 * 1024 + 1])) {
      response = bad;
      assertThatThrownBy(() -> execute("list_portfolios", Map.of("refresh", true))).isInstanceOf(IOException.class);
      assertThat(execute("list_portfolios", Map.of()).path("resultSetId").asString()).isEqualTo(id);
    }
    response = valid;
    var limited = new PortfolioResultSets(store, mapper, finance, new ResultSetProperties(8 * 1024 * 1024, 100, 1800), clock);
    assertThatThrownBy(() -> limited.acquire(scope, true)).hasMessageContaining("record limit");
    assertThat(execute("list_portfolios", Map.of()).path("resultSetId").asString()).isEqualTo(id);
    assertThatThrownBy(() -> finance.get("/portfolios", scope.user(), scope.control())).hasMessageContaining("too large");
  }

  private JsonNode execute(String name, Map<String, Object> args) throws Exception {
    var output = registry.execute(name, mapper.valueToTree(args), scope).output();
    assertThat(TokenBudget.estimate(output)).isLessThanOrEqualTo(agent.toolResultTokens());
    assertThat(output.has("payloadRef")).isFalse();
    return output.path("data");
  }

  private static final class MutableClock extends Clock {
    private Instant now = Instant.parse("2026-10-12T12:00:00Z");
    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return now; }
  }
}
