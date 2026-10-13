package com.example.ragbot;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Registration, server-side argument validation, citation assignment and bounded results. */
@Component
@ConditionalOnProperty(name = "rag.build-index", havingValue = "false", matchIfMissing = true)
public final class ToolRegistry {
  @FunctionalInterface
  public interface Handler {
    AgentTool.Result execute(JsonNode arguments, AgentTool.Scope scope) throws IOException, InterruptedException;
  }

  private final Map<String, AgentTool> tools = new LinkedHashMap<>();
  private final Map<String, String> statuses = new LinkedHashMap<>();
  private final ObjectMapper mapper;
  private final AgentProperties properties;
  private final MemoryStore store;
  private final PortfolioTools portfolios;
  private final EditingWorkflow editing;

  public ToolRegistry(ObjectMapper mapper, AgentProperties properties, MemoryStore store,
      ContextManager context, FinanceApiClient finance, RagChatService knowledge, BusinessTools business) {
    this(mapper, properties, store, context, finance, knowledge, business, ResultSetProperties.defaults());
  }

  @Autowired
  public ToolRegistry(ObjectMapper mapper, AgentProperties properties, MemoryStore store,
      ContextManager context, FinanceApiClient finance, RagChatService knowledge, BusinessTools business,
      ResultSetProperties resultSets) {
    this.mapper = mapper;
    this.properties = properties;
    this.store = store;
    this.portfolios = new PortfolioTools(mapper, store, finance, properties, resultSets);
    DefaultAgentTools.register(this, mapper, store, context, finance, knowledge, business, portfolios);
    editing = new EditingWorkflow(store, mapper, finance, portfolios);
    editing.register(this);
  }

  EditingWorkflow editing() { return editing; }

  @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
  public void resetMockSnapshots() throws IOException {
    // Mock edits reset on restart, so persistent current snapshots must fetch again.
    if (properties.financeBaseUrl() == null) {
      for (String userId : List.of("user-001", "user-002", "user-003")) portfolios.invalidate(userId);
    }
  }

  String portfolioContext(MockFinanceService.User user, String sessionId) throws IOException {
    return tool("query_portfolios") == null ? "" : portfolios.contextHint(user, sessionId);
  }

  public void register(String name, String description, Map<String, Object> fields, Handler handler) {
    register(name, description, fields, false, handler);
  }

  public void register(String name, String description, Map<String, Object> fields, boolean readOnly, Handler handler) {
    Map<String, Object> parameters = Map.of("type", "object", "properties", fields,
        "required", List.copyOf(fields.keySet()), "additionalProperties", false);
    register(new AgentTool() {
      public String name() { return name; }
      public String description() { return description; }
      public Map<String, Object> parameters() { return parameters; }
      public boolean readOnly() { return readOnly; }
      public Result execute(JsonNode arguments, Scope scope) throws IOException, InterruptedException {
        return handler.execute(arguments, scope);
      }
    });
  }

  public void register(AgentTool tool) {
    if (tools.putIfAbsent(tool.name(), tool) != null)
      throw new IllegalArgumentException("Duplicate agent tool " + tool.name());
  }

  public AgentTool tool(String name) { return tools.get(name); }
  public List<AgentTool> tools() { return List.copyOf(tools.values()); }
  public String status(String name) { return statuses.get(name); }
  void status(String name, String status) { statuses.put(name, status); }

  public List<Map<String, Object>> definitions() {
    return tools.values().stream().map(tool -> Map.<String, Object>of("name", tool.name(),
        "description", tool.description(), "parameters", tool.parameters(), "type", "function", "strict", true)).toList();
  }

  public AgentTool.Result execute(String name, JsonNode arguments, AgentTool.Scope scope)
      throws IOException, InterruptedException {
    return complete(name, executeRaw(name, arguments, scope), scope);
  }

  AgentTool.Result executeRaw(String name, JsonNode arguments, AgentTool.Scope scope)
      throws IOException, InterruptedException {
    scope.control().check();
    AgentTool tool = tools.get(name);
    if (tool == null) throw new ApiRequestException(HttpStatus.BAD_REQUEST, "Unknown agent tool");
    @SuppressWarnings("unchecked")
    var fields = (Map<String, Object>) tool.parameters().getOrDefault("properties", Map.of());
    if (!arguments.isObject()) throw new ApiRequestException(HttpStatus.BAD_REQUEST, "Tool arguments must be an object");
    for (var field : arguments.properties()) {
      if (!fields.containsKey(field.getKey())) {
        throw new ApiRequestException(HttpStatus.BAD_REQUEST, "Unexpected tool argument: " + field.getKey());
      }
    }
    for (var field : fields.entrySet()) {
      @SuppressWarnings("unchecked") Map<String, Object> schema = (Map<String, Object>) field.getValue();
      Object rawType = schema.get("type");
      List<?> types = rawType instanceof List<?> list ? list : List.of(rawType);
      JsonNode value = arguments.path(field.getKey());
      if (!present(arguments, field.getKey()) && types.contains("null")) continue;
      if (types.contains("array")) {
        validateStructured(value, schema, field.getKey());
      } else if (types.contains("integer")) {
        if (!value.isIntegralNumber() || (schema.get("minimum") instanceof Number min && value.asLong() < min.longValue())
            || (schema.get("maximum") instanceof Number max && value.asLong() > max.longValue()))
          throw new ApiRequestException(HttpStatus.BAD_REQUEST, "Invalid tool argument: " + field.getKey());
      } else if (types.contains("boolean")) {
        if (!value.isBoolean()) throw new ApiRequestException(HttpStatus.BAD_REQUEST, "Invalid tool argument: " + field.getKey());
      } else {
        String content = text(arguments, field.getKey());
        if (schema.get("maxLength") instanceof Number max && content.length() > max.intValue())
          throw new ApiRequestException(HttpStatus.BAD_REQUEST, "Invalid tool argument: " + field.getKey());
      }
      if (schema.get("enum") instanceof List<?> allowed && !allowed.contains(value.asString()))
        throw new ApiRequestException(HttpStatus.BAD_REQUEST, "Invalid tool argument: " + field.getKey());
    }
    return tool.execute(arguments, new AgentTool.Scope(scope.user(), scope.sessionId(), 0, scope.control()));
  }

  @SuppressWarnings("unchecked")
  private void validateStructured(JsonNode value, Map<String, Object> schema, String path) {
    String type = schema.get("type").toString();
    boolean valid = switch (type) {
      case "array" -> value.isArray();
      case "object" -> value.isObject();
      case "string" -> value.isString();
      default -> false;
    };
    if (!valid) throw new ApiRequestException(HttpStatus.BAD_REQUEST, "Invalid tool argument: " + path);
    if (type.equals("array")) {
      if (schema.get("maxItems") instanceof Number max && value.size() > max.intValue())
        throw new ApiRequestException(HttpStatus.BAD_REQUEST, "Too many options");
      for (JsonNode item : value) validateStructured(item, (Map<String, Object>) schema.get("items"), path + "[]");
    } else if (type.equals("object")) {
      var properties = (Map<String, Object>) schema.get("properties");
      for (var field : value.properties()) if (!properties.containsKey(field.getKey()))
        throw new ApiRequestException(HttpStatus.BAD_REQUEST, "Unexpected option field");
      for (var field : properties.entrySet()) validateStructured(value.path(field.getKey()), (Map<String, Object>) field.getValue(), path + "." + field.getKey());
    } else {
      String text = value.asString();
      if ((schema.get("maxLength") instanceof Number max && text.length() > max.intValue())
          || (schema.get("minLength") instanceof Number min && text.strip().length() < min.intValue()))
        throw new ApiRequestException(HttpStatus.BAD_REQUEST, "Invalid tool argument: " + path);
    }
  }

  /** Called in model order, before truncation or payload persistence. */
  AgentTool.Result complete(String name, AgentTool.Result raw, AgentTool.Scope scope) throws IOException {
    AgentTool.Result result = citations(raw, scope.sourceOffset());
    if (TokenBudget.estimate(result.output()) > properties.toolResultTokens()) {
      if (name.equals("search_docs")) {
        String excerpts = result.output().path("excerpts").asString("");
        AgentTool.Result shortened = result;
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
        AgentTool.Result bounded = result(pointer, result.sources());
        if (TokenBudget.estimate(bounded.output()) <= properties.toolResultTokens()) return bounded;
      }
      throw new ApiRequestException(HttpStatus.BAD_REQUEST, "Tool result exceeds its budget; request a more specific resource");
    }
    return result;
  }

  private AgentTool.Result citations(AgentTool.Result result, int offset) {
    if (result.sources().isEmpty()) return result;
    var mapping = new LinkedHashMap<String, String>();
    var sources = new ArrayList<RagSource>();
    for (var original : result.sources()) {
      String citation = original.citation().substring(0, 1) + (offset + sources.size() + 1);
      mapping.put(original.citation(), citation);
      sources.add(new RagSource(citation, original.title(), original.section(), original.source(), original.score()));
    }
    JsonNode output = result.output().deepCopy();
    if (output instanceof tools.jackson.databind.node.ObjectNode object) {
      if (object.has("sources")) object.set("sources", mapper.valueToTree(sources));
      if (object.has("excerpts")) {
        String excerpts = object.path("excerpts").asString("");
        object.put("excerpts", Pattern.compile("\\[([SB]\\d+)]").matcher(excerpts)
          .replaceAll(match -> "[" + mapping.getOrDefault(match.group(1), match.group(1)) + "]"));
      }
    }
    return new AgentTool.Result(output, List.copyOf(sources));
  }

  private AgentTool.Result result(Object output, List<RagSource> sources) {
    return new AgentTool.Result(mapper.valueToTree(output), List.copyOf(sources));
  }

  private AgentTool.Result boundedHistory(AgentTool.Result result) {
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
    return new AgentTool.Result(node, result.sources());
  }

  private static boolean present(JsonNode arguments, String key) {
    return arguments.has(key) && !arguments.path(key).isNull();
  }

  private static String text(JsonNode args, String field) {
    JsonNode value = args.path(field);
    if (!value.isString() || value.asString().isBlank() || value.asString().length() > 2_000) {
      throw new ApiRequestException(HttpStatus.BAD_REQUEST, "Invalid tool argument: " + field);
    }
    return value.asString().strip();
  }
}
