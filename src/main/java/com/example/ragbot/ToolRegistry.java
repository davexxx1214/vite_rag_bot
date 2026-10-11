package com.example.ragbot;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name = "rag.build-index", havingValue = "false", matchIfMissing = true)
public final class ToolRegistry {
  @FunctionalInterface
  public interface Handler {
    Result execute(JsonNode arguments, Scope scope) throws IOException, InterruptedException;
  }

  public record Scope(MockFinanceService.User user, String sessionId, int sourceOffset, RunControl control) {}
  public record Result(JsonNode output, List<RagSource> sources) {}
  private record Tool(Map<String, Object> definition, Map<String, Object> fields, Handler handler) {}
  private final Map<String, Tool> tools = new LinkedHashMap<>();
  private final Map<String, String> statuses = new LinkedHashMap<>();
  private final ObjectMapper mapper;
  private final AgentProperties properties;
  private final MemoryStore store;
  private final ContextManager context;
  private final FinanceApiClient finance;

  public ToolRegistry(ObjectMapper mapper, AgentProperties properties, MemoryStore store,
      ContextManager context, FinanceApiClient finance, RagChatService knowledge, BusinessTools business) {
    this.mapper = mapper;
    this.properties = properties;
    this.store = store;
    this.context = context;
    this.finance = finance;
    register("search_docs", "Search the local help knowledge base for terminology, rules or API documentation. Cite returned sources.",
        Map.of("query", textSchema("A standalone knowledge search query")), (args, scope) -> {
          RagRetrieval retrieval = knowledge.retrieve(text(args, "query"), scope.control());
          String excerpts = retrieval.context();
          List<RagSource> sources = new ArrayList<>();
          for (int i = retrieval.sources().size() - 1; i >= 0; i--) {
            RagSource original = retrieval.sources().get(i);
            String citation = "S" + (scope.sourceOffset() + i + 1);
            excerpts = excerpts.replace("[" + original.citation() + "]", "[" + citation + "]");
            sources.add(0, new RagSource(citation, original.title(), original.section(), original.source(), original.score()));
          }
          return result(Map.of("excerpts", excerpts, "sources", sources), sources);
        });
    for (var tool : business.definitions()) {
      register(tool.name(), tool.description(), tool.fields(), (args, scope) -> {
        String path = tool.resolvedPath(args);
        Map<String, String> query = tool.query(args);
        JsonNode data = finance.get(path, query, scope.user(), scope.control());
        List<RagSource> sources = List.of(new RagSource("B" + (scope.sourceOffset() + 1), tool.title(), path,
            finance.sourceUrl(path, query, scope.user()), 1));
        return result(Map.of("data", data, "sources", sources, "mock", true), sources);
      });
      statuses.put(tool.name(), tool.status());
    }
    register("compare_portfolios", "Compare two of the current user's portfolios. Java calculates differences and allocation percentages. Different currencies are not converted.",
        Map.of("leftId", textSchema("First portfolio ID"), "rightId", textSchema("Second portfolio ID")), (args, scope) -> {
          String left = FinanceApiClient.segment(text(args, "leftId"));
          String right = FinanceApiClient.segment(text(args, "rightId"));
          JsonNode data = finance.compare(left, right, scope.user(), scope.control());
          List<RagSource> sources = new ArrayList<>();
          for (String path : List.of("/portfolios/" + left, "/portfolios/" + right,
              "/portfolios/" + left + "/positions", "/portfolios/" + right + "/positions")) {
            sources.add(source(path, "Portfolio comparison", scope, sources.size()));
          }
          return result(Map.of("data", data, "sources", sources, "mock", true), sources);
        });
    register("search_history", "Search this user's historical records by relevance with offset/limit pagination. For surrounding messages supply sessionId and aroundTurn instead of query. For saved large tool outputs supply sessionId, payloadRef, payloadOffset. Financial records are snapshots: re-query business tools for current facts.",
        Map.of("query", optionalText("Keywords, phrase or resource ID; null when reading a window"),
            "offset", optionalInteger("Search result offset; default 0", 0, 10000),
            "limit", optionalInteger("Search page size; default 5", 1, 20),
            "sessionId", optionalText("Session ID from a search hit; null for discovery"),
            "aroundTurn", optionalInteger("Anchor turn from a hit; null for discovery", 1, Integer.MAX_VALUE),
            "window", optionalInteger("Turns before and after the anchor; default 1", 0, 10),
            "payloadRef", optionalText("Saved payload reference from a tool result; null otherwise"),
            "payloadOffset", optionalInteger("Payload character offset; default 0. Follow returned nextOffset for more.", 0, 10000000)), (args, scope) -> {
          if (present(args, "payloadRef")) return result(store.readPayload(scope.user().id(),
              text(args, "sessionId"), text(args, "payloadRef"), integer(args, "payloadOffset", 0)), List.of());
          if (present(args, "sessionId")) return result(store.historyWindow(scope.user().id(),
              text(args, "sessionId"), integer(args, "aroundTurn", 1), integer(args, "window", 1)), List.of());
          return result(store.searchHistory(scope.user().id(), text(args, "query"),
              integer(args, "offset", 0), integer(args, "limit", 5)), List.of());
        });
    register("remember", "Save or update an explicit stable user preference/fact. Use a stable key, reuse keys for corrections. Never save prices, holdings, secrets or inferred preferences.",
        Map.of("key", textSchema("2-48 lowercase letters/digits/underscores/hyphens"),
            "content", textSchema("Explicit stable user fact or preference, at most 1500 characters"),
            "target", Map.of("type", List.of("string", "null"), "enum", java.util.Arrays.asList("user", "memory", null),
                "description", "user for explicit preferences/profile (default); memory for stable business background")), (args, scope) -> {
          context.remember(scope.user().id(), scope.sessionId(), text(args, "key"), text(args, "content"),
              present(args, "target") ? text(args, "target") : "user", scope.control());
          return result(Map.of("saved", true, "memory", store.memory(scope.user().id())), List.of());
        });
    register("forget_memory", "Remove a user memory entry when asked. Use its existing ## entry_key heading.",
        Map.of("key", textSchema("Existing memory entry key")), (args, scope) -> {
          context.forget(scope.user().id(), text(args, "key"));
          store.memoryCursor(scope.user().id(), scope.sessionId(),
              store.turnCount(scope.user().id(), scope.sessionId()) + 1);
          return result(Map.of("forgotten", true, "memory", store.memory(scope.user().id())), List.of());
        });
  }

  /** Add a named schema and handler here; the agent loop needs no endpoint-specific changes. */
  public void register(String name, String description, Map<String, Object> fields, Handler handler) {
    Map<String, Object> parameters = Map.of("type", "object", "properties", fields,
        "required", List.copyOf(fields.keySet()), "additionalProperties", false);
    Map<String, Object> definition = Map.of("type", "function", "name", name,
        "description", description, "parameters", parameters, "strict", true);
    if (tools.putIfAbsent(name, new Tool(definition, fields, handler)) != null) {
      throw new IllegalArgumentException("Duplicate agent tool " + name);
    }
  }

  public String status(String name) { return statuses.get(name); }

  public List<Map<String, Object>> definitions() {
    return tools.values().stream().map(Tool::definition).toList();
  }

  public Result execute(String name, JsonNode arguments, Scope scope) throws IOException, InterruptedException {
    scope.control().check();
    Tool tool = tools.get(name);
    if (tool == null) throw new ApiRequestException(HttpStatus.BAD_REQUEST, "Unknown agent tool");
    if (!arguments.isObject()) throw new ApiRequestException(HttpStatus.BAD_REQUEST, "Tool arguments must be an object");
    for (var field : arguments.properties()) {
      if (!tool.fields().containsKey(field.getKey())) {
        throw new ApiRequestException(HttpStatus.BAD_REQUEST, "Unexpected tool argument: " + field.getKey());
      }
    }
    for (var field : tool.fields().entrySet()) {
      @SuppressWarnings("unchecked") Map<String, Object> schema = (Map<String, Object>) field.getValue();
      Object rawType = schema.get("type");
      List<?> types = rawType instanceof List<?> list ? list : List.of(rawType);
      JsonNode value = arguments.path(field.getKey());
      if (!present(arguments, field.getKey()) && types.contains("null")) continue;
      if (types.contains("integer")) {
        if (!value.isIntegralNumber() || (schema.get("minimum") instanceof Number min && value.asLong() < min.longValue())
            || (schema.get("maximum") instanceof Number max && value.asLong() > max.longValue()))
          throw new ApiRequestException(HttpStatus.BAD_REQUEST, "Invalid tool argument: " + field.getKey());
      } else if (types.contains("boolean")) {
        if (!value.isBoolean()) throw new ApiRequestException(HttpStatus.BAD_REQUEST, "Invalid tool argument: " + field.getKey());
      } else text(arguments, field.getKey());
      if (schema.get("enum") instanceof List<?> allowed && !allowed.contains(value.asString()))
        throw new ApiRequestException(HttpStatus.BAD_REQUEST, "Invalid tool argument: " + field.getKey());
    }
    Result result = tool.handler().execute(arguments, scope);
    if (TokenBudget.estimate(result.output()) > properties.toolResultTokens()) {
      if (name.equals("search_docs")) {
        String excerpts = result.output().path("excerpts").asString("");
        Result shortened = result;
        while (TokenBudget.estimate(shortened.output()) > properties.toolResultTokens() && !excerpts.isEmpty()) {
          excerpts = excerpts.substring(0, excerpts.offsetByCodePoints(0, excerpts.codePointCount(0, excerpts.length()) / 2));
          shortened = result(Map.of("excerpts", excerpts + "…", "sources", result.sources(), "truncated", true), result.sources());
        }
        if (TokenBudget.estimate(shortened.output()) <= properties.toolResultTokens()) return shortened;
      }
      if (name.equals("search_history")) return boundedHistory(result);
      if (!name.equals("remember") && !name.equals("forget_memory")) {
        var saved = store.spillToolResult(scope.user().id(), scope.sessionId(), mapper.writeValueAsString(result.output()));
        var pointer = new LinkedHashMap<>(saved); pointer.put("sessionId", scope.sessionId()); pointer.put("sources", result.sources());
        Result bounded = result(pointer, result.sources());
        if (TokenBudget.estimate(bounded.output()) <= properties.toolResultTokens()) return bounded;
      }
      throw new ApiRequestException(HttpStatus.BAD_REQUEST, "Tool result exceeds its budget; request a more specific resource");
    }
    return result;
  }

  private RagSource source(String path, String title, Scope scope, int offset) {
    return new RagSource("B" + (scope.sourceOffset() + offset + 1), title, path,
        finance.sourceUrl(path, scope.user()), 1);
  }

  private Result result(Object output, List<RagSource> sources) {
    return new Result(mapper.valueToTree(output), List.copyOf(sources));
  }

  private static Map<String, Object> textSchema(String description) {
    return Map.of("type", "string", "description", description, "minLength", 1, "maxLength", 2_000);
  }

  private Result boundedHistory(Result result) {
    var node = (tools.jackson.databind.node.ObjectNode) result.output().deepCopy();
    String field = node.has("results") ? "results" : "turns";
    var list = (tools.jackson.databind.node.ArrayNode) node.path(field);
    int offset = node.path("offset").asInt();
    while (TokenBudget.estimate(node) > properties.toolResultTokens() && list.size() > 1) {
      if (field.equals("turns")) {
        int anchor = node.path("aroundTurn").asInt();
        int first = list.get(0).path("turn").asInt(), last = list.get(list.size() - 1).path("turn").asInt();
        list.remove(Math.abs(anchor - first) > Math.abs(last - anchor) ? 0 : list.size() - 1);
        offset = list.get(0).path("turn").asInt() - 1; node.put("offset", offset);
      } else list.remove(list.size() - 1);
      node.put("nextOffset", offset + list.size()); node.put("truncated", true);
    }
    if (TokenBudget.estimate(node) > properties.toolResultTokens() && list.size() == 1) {
      var item = (tools.jackson.databind.node.ObjectNode) list.get(0);
      int chars = Math.max(100, properties.toolResultTokens() / 3);
      while (TokenBudget.estimate(node) > properties.toolResultTokens() && chars >= 50) {
        for (String key : List.of("message", "answer", "tools", "excerpt")) if (item.has(key))
          item.put(key, MemoryStore.excerpt(item.path(key).asString(), chars));
        chars /= 2;
      }
      node.put("truncated", true);
    }
    if (TokenBudget.estimate(node) > properties.toolResultTokens())
      throw new ApiRequestException(HttpStatus.BAD_REQUEST, "History result exceeds its budget; use a smaller window");
    return new Result(node, result.sources());
  }

  private static boolean present(JsonNode arguments, String key) {
    return arguments.has(key) && !arguments.path(key).isNull();
  }

  private static int integer(JsonNode arguments, String key, int defaultValue) {
    return present(arguments, key) ? arguments.path(key).asInt() : defaultValue;
  }

  private static Map<String, Object> optionalText(String description) {
    return Map.of("type", List.of("string", "null"), "description", description, "minLength", 1, "maxLength", 2000);
  }

  private static Map<String, Object> optionalInteger(String description, int min, int max) {
    return Map.of("type", List.of("integer", "null"), "description", description, "minimum", min, "maximum", max);
  }


  private static String text(JsonNode args, String field) {
    JsonNode value = args.path(field);
    if (!value.isString() || value.asString().isBlank() || value.asString().length() > 2_000) {
      throw new ApiRequestException(HttpStatus.BAD_REQUEST, "Invalid tool argument: " + field);
    }
    return value.asString().strip();
  }
}
