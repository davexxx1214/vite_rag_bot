package com.example.ragbot;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Structured operations over a complete snapshot; no model-side arithmetic or raw JSON chunks. */
final class PortfolioTools {
  static final List<String> MONEY_FIELDS = List.of("cashBalance", "marketValue", "totalValue", "unrealizedPnl");
  private static final List<String> FIELDS = List.of("id", "username", "name", "currency", "cashBalance",
      "marketValue", "totalValue", "unrealizedPnl", "positionCount", "asOf", "version", "updatedAt");
  private static final String DEFAULT_FIELDS = "id,username,name,currency,totalValue,positionCount,asOf";
  private final ObjectMapper mapper;
  private final MemoryStore store;
  private final FinanceApiClient finance;
  private final AgentProperties agent;
  private final PortfolioResultSets sets;

  PortfolioTools(ObjectMapper mapper, MemoryStore store, FinanceApiClient finance,
      AgentProperties agent, ResultSetProperties properties) {
    this(mapper, store, finance, agent, properties, Clock.systemUTC());
  }

  PortfolioTools(ObjectMapper mapper, MemoryStore store, FinanceApiClient finance,
      AgentProperties agent, ResultSetProperties properties, Clock clock) {
    this.mapper = mapper; this.store = store; this.finance = finance; this.agent = agent;
    this.sets = new PortfolioResultSets(store, mapper, finance, properties, clock);
  }

  void register(ToolRegistry registry, BusinessTools.Definition definition) {
    // These tools update the durable current view, so execute them in model order.
    registry.register("list_portfolios",
        "Fetch/reuse the current user's complete portfolio snapshot. Returns metadata and a small preview, not the full list. "
            + "Repeated calls reuse this session's snapshot until expiry. Set refresh=true only for an explicit latest/refresh request. "
            + "Use query_portfolios and summarize_portfolios for follow-ups.",
        Map.of("refresh", optional("boolean", "Explicitly fetch a new snapshot; default false")), (args, scope) -> {
          var snapshot = sets.acquire(scope, args.path("refresh").asBoolean(false));
          JsonNode data = page(snapshot, mapper.createObjectNode(), 0, 10);
          ((ObjectNode) data).put("previewOnly", true);
          return sourced(data, scope);
        });
    registry.status("list_portfolios", definition.status());
    var fields = new LinkedHashMap<String, Object>();
    fields.put("resultSetId", optional("string", "Snapshot ID; null uses this session's active snapshot"));
    fields.put("resetView", optional("boolean", "Clear previous filters/sort/field selection before applying these arguments; default false"));
    fields.put("currency", optional("string", "Exact currency code, e.g. USD. Null preserves previous filter"));
    fields.put("name", optional("string", "Case-insensitive name substring; null preserves previous filter"));
    fields.put("id", optional("string", "Exact portfolio ID within the snapshot; null preserves previous filter"));
    fields.put("sortBy", optional("string", "id, name, totalValue, cashBalance, marketValue, unrealizedPnl or positionCount. Monetary ranking requires one currency"));
    fields.put("descending", optional("boolean", "Sort descending; null preserves previous direction"));
    fields.put("fields", optional("string", "Comma-separated fields: " + String.join(",", FIELDS) + "; null preserves previous selection"));
    fields.put("offset", Map.of("type", List.of("integer", "null"), "minimum", 0, "maximum", 100_000,
        "description", "Record offset; default 0. Follow nextOffset for another page"));
    fields.put("limit", Map.of("type", List.of("integer", "null"), "minimum", 1, "maximum", 20,
        "description", "Page size; default 10, hard maximum 20; token budget may return fewer"));
    registry.register("query_portfolios",
        "Filter, sort, project and page a saved portfolio snapshot without fetching the backend. "
            + "Omitted filters preserve the last view (for 'among those'). Use resetView=true to start a different analysis. "
            + "Stores the returned page as the selection for 'these five'. Never infer full-set totals from a page.",
        fields, (args, scope) -> query(args, scope));
    registry.status("query_portfolios", "正在筛选已保存的投资组合…");
    registry.register("summarize_portfolios",
        "Calculate exact counts and monetary totals over ALL records matching the saved current view, grouped by currency. "
            + "Also returns the last page's selected totals and share of each currency's filtered total. "
            + "No backend fetch. To change filters first use query_portfolios. Never combine currencies.",
        Map.of("resultSetId", optional("string", "Snapshot ID; null uses this session's active snapshot"),
            "currencyOffset", Map.of("type", List.of("integer", "null"), "minimum", 0, "maximum", 32,
                "description", "Currency group offset; default 0. Follow nextCurrencyOffset when the output budget requires more pages")),
        (args, scope) -> summarize(args, scope));
    registry.status("summarize_portfolios", "正在计算投资组合统计…");
  }

  String contextHint(MockFinanceService.User user, String sessionId) throws IOException {
    return sets.contextHint(new AgentTool.Scope(user, sessionId, 0, new RunControl(30)));
  }

  void invalidate(String userId) throws IOException { sets.invalidate(userId); }

  private AgentTool.Result query(JsonNode args, AgentTool.Scope scope) throws IOException, InterruptedException {
    var lock = store.userLock(scope.user().id()); lock.lockInterruptibly();
    try {
      scope.control().check();
      var snapshot = resolve(args, scope);
      var view = currentView(snapshot, scope);
      if (args.path("resetView").asBoolean(false)) view = mapper.createObjectNode();
      view.remove("selectedIds"); view.remove("offset"); view.remove("returned");
      for (String key : List.of("currency", "name", "id", "sortBy", "descending", "fields"))
        if (args.hasNonNull(key)) view.set(key, args.path(key));
      validateView(view);
      if (view.hasNonNull("fields")) view.put("fields", java.util.Arrays.stream(view.path("fields").asString().split(","))
          .map(String::strip).distinct().collect(java.util.stream.Collectors.joining(",")));
      JsonNode data = page(snapshot, view, integer(args, "offset", 0), integer(args, "limit", 10));
      // IDs are retained even if the requested field projection omitted them.
      var rows = filtered(snapshot, view);
      int offset = data.path("offset").asInt(), returned = data.path("returned").asInt();
      view.set("selectedIds", mapper.valueToTree(rows.stream().skip(offset).limit(returned)
          .map(row -> row.path("id").asString()).toList()));
      view.put("offset", offset); view.put("returned", returned);
      // The output contains conditions; the selection is stored separately so large IDs do not grow each page.
      scope.control().check();
      sets.saveView(scope, snapshot.document(), view);
      return sourced(data, scope);
    } finally { lock.unlock(); }
  }

  private AgentTool.Result summarize(JsonNode args, AgentTool.Scope scope) throws IOException, InterruptedException {
    var lock = store.userLock(scope.user().id()); lock.lockInterruptibly();
    try {
      scope.control().check();
      var snapshot = resolve(args, scope);
      JsonNode view = currentView(snapshot, scope);
      var rows = filtered(snapshot, view);
      List<String> selected = selectedIds(view.path("selectedIds"));
      var groups = new java.util.TreeMap<String, List<JsonNode>>();
      for (JsonNode row : rows) groups.computeIfAbsent(row.path("currency").asString(), ignored -> new ArrayList<>()).add(row);
      var summary = new ArrayList<Map<String, Object>>();
      for (var entry : groups.entrySet()) {
        var values = new LinkedHashMap<String, Object>();
        values.put("currency", entry.getKey()); values.put("count", entry.getValue().size());
        for (String field : MONEY_FIELDS) values.put(field, sum(entry.getValue(), field));
        var selection = entry.getValue().stream().filter(row -> selected.contains(row.path("id").asString())).toList();
        BigDecimal total = sum(entry.getValue(), "totalValue"), selectedTotal = sum(selection, "totalValue");
        values.put("selectedCount", selection.size()); values.put("selectedTotalValue", selectedTotal);
        values.put("selectedShareOfFilteredTotalPct", total.signum() == 0 ? null
            : selectedTotal.multiply(BigDecimal.valueOf(100)).divide(total, 6, RoundingMode.HALF_UP));
        summary.add(values);
      }
      var data = metadata(snapshot);
      data.put("matched", rows.size()); data.put("view", view); data.put("byCurrency", summary);
      data.put("note", "Totals cover all matching records of this snapshot, not just the preview/page. No FX conversion. Selection is the last query page.");
      int offset = integer(args, "currencyOffset", 0);
      data.put("currencyGroups", summary.size()); data.put("currencyOffset", offset);
      var bounded = new ArrayList<>(summary.stream().skip(offset).toList());
      while (true) {
        data.put("byCurrency", bounded); data.put("groupsReturned", bounded.size());
        data.put("nextCurrencyOffset", offset + bounded.size() < summary.size() ? offset + bounded.size() : null);
        if (TokenBudget.estimate(mapper.valueToTree(data)) <= Math.max(100, agent.toolResultTokens() - 400) || bounded.size() <= 1) break;
        bounded.removeLast();
      }
      scope.control().check();
      sets.saveView(scope, snapshot.document(), view);
      return sourced(mapper.valueToTree(data), scope);
    } finally { lock.unlock(); }
  }

  private PortfolioResultSets.Snapshot resolve(JsonNode args, AgentTool.Scope scope) throws IOException, InterruptedException {
    if (args.hasNonNull("resultSetId")) return sets.read(scope, args.path("resultSetId").asString());
    JsonNode state = sets.state(scope);
    return state.hasNonNull("resultSetId") && !sets.outdated(scope, state)
        ? sets.read(scope, state.path("resultSetId").asString()) : sets.acquire(scope, false);
  }

  private ObjectNode currentView(PortfolioResultSets.Snapshot snapshot, AgentTool.Scope scope) throws IOException {
    return (ObjectNode) sets.view(scope, snapshot.id()).deepCopy();
  }

  private JsonNode page(PortfolioResultSets.Snapshot snapshot, JsonNode view, int offset, int limit) {
    var rows = filtered(snapshot, view);
    List<String> fields = List.of(view.path("fields").asString(DEFAULT_FIELDS).split(","));
    var items = new ArrayList<JsonNode>();
    for (JsonNode row : rows.stream().skip(offset).limit(limit).toList()) {
      var item = mapper.createObjectNode();
      for (String field : fields) item.set(field.strip(), row.path(field.strip()));
      items.add(item);
    }
    var data = metadata(snapshot);
    data.put("matched", rows.size()); data.put("offset", offset); data.put("view", view.deepCopy());
    // Reserve space for citations and the enclosing business-tool envelope.
    while (true) {
      data.put("items", items); data.put("returned", items.size());
      data.put("nextOffset", offset + items.size() < rows.size() ? offset + items.size() : null);
      data.put("hasMore", offset + items.size() < rows.size());
      if (TokenBudget.estimate(mapper.valueToTree(data)) <= Math.max(100, agent.toolResultTokens() - 400) || items.size() <= 1) break;
      items.removeLast(); data.put("budgetTruncated", true);
    }
    return mapper.valueToTree(data);
  }

  private List<JsonNode> filtered(PortfolioResultSets.Snapshot snapshot, JsonNode view) {
    var rows = new ArrayList<JsonNode>();
    for (JsonNode row : snapshot.rows()) {
      if (view.hasNonNull("currency") && !row.path("currency").asString().equals(view.path("currency").asString())) continue;
      if (view.hasNonNull("id") && !row.path("id").asString().equals(view.path("id").asString())) continue;
      if (view.hasNonNull("name") && !row.path("name").asString().toLowerCase(Locale.ROOT)
          .contains(view.path("name").asString().toLowerCase(Locale.ROOT))) continue;
      rows.add(row);
    }
    String sort = view.path("sortBy").asString("id");
    if (MONEY_FIELDS.contains(sort) && rows.stream().map(row -> row.path("currency").asString()).distinct().count() > 1)
      throw new ApiRequestException(HttpStatus.BAD_REQUEST, "Filter one currency before ranking monetary values; no FX conversion is available");
    Comparator<JsonNode> comparator = MONEY_FIELDS.contains(sort) || sort.equals("positionCount")
        ? Comparator.comparing(row -> new BigDecimal(row.path(sort).asString()))
        : Comparator.comparing(row -> row.path(sort).asString());
    if (view.path("descending").asBoolean(false)) comparator = comparator.reversed();
    rows.sort(comparator.thenComparing(row -> row.path("id").asString()));
    return rows;
  }

  private void validateView(JsonNode view) {
    if (view.hasNonNull("currency") && !view.path("currency").asString().matches("[A-Z]{3}")) invalid("currency");
    if (view.hasNonNull("id")) FinanceApiClient.segment(view.path("id").asString());
    if (view.hasNonNull("name") && view.path("name").asString().length() > 300) invalid("name");
    if (!List.of("id", "name", "totalValue", "cashBalance", "marketValue", "unrealizedPnl", "positionCount")
        .contains(view.path("sortBy").asString("id"))) invalid("sortBy");
    for (String field : view.path("fields").asString(DEFAULT_FIELDS).split(",", -1))
      if (!FIELDS.contains(field.strip())) invalid("fields");
  }

  private Map<String, Object> metadata(PortfolioResultSets.Snapshot snapshot) {
    var data = new LinkedHashMap<String, Object>();
    for (String key : List.of("resultSetId", "fetchedAt", "expiresAt", "total", "complete"))
      data.put(key, snapshot.document().path(key));
    data.put("reused", snapshot.reused());
    data.put("beforeEdit", snapshot.document().path("beforeEdit").asBoolean(false));
    String earliest = "", latest = "";
    for (JsonNode row : snapshot.rows()) {
      String asOf = java.time.Instant.parse(row.path("asOf").asString()).toString();
      if (earliest.isEmpty() || asOf.compareTo(earliest) < 0) earliest = asOf;
      if (latest.isEmpty() || asOf.compareTo(latest) > 0) latest = asOf;
    }
    data.put("sourceAsOfFrom", earliest); data.put("sourceAsOfThrough", latest);
    return data;
  }

  private AgentTool.Result sourced(JsonNode data, AgentTool.Scope scope) {
    var sources = List.of(new RagSource("B1", "Portfolio snapshot " + data.path("fetchedAt").asString(),
        "/portfolios", finance.sourceUrl("/portfolios", scope.user()), 1));
    return new AgentTool.Result(mapper.valueToTree(Map.of("data", data, "sources", sources, "mock", true)), sources);
  }

  private static List<String> selectedIds(JsonNode node) {
    var ids = new ArrayList<String>();
    if (node.isArray()) for (JsonNode item : node) ids.add(item.isString() ? item.asString() : item.path("id").asString());
    return ids;
  }

  private static BigDecimal sum(List<JsonNode> rows, String field) {
    return rows.stream().map(row -> new BigDecimal(row.path(field).asString())).reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  private static Map<String, Object> optional(String type, String description) {
    return Map.of("type", List.of(type, "null"), "description", description);
  }

  private static int integer(JsonNode args, String field, int fallback) {
    return args.hasNonNull(field) ? args.path(field).asInt() : fallback;
  }

  private static void invalid(String field) { throw new ApiRequestException(HttpStatus.BAD_REQUEST, "Invalid portfolio query " + field); }
}
