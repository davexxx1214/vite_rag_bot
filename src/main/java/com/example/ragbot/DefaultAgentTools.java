package com.example.ragbot;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Registers business/knowledge/memory implementations independently of the agent loop. */
final class DefaultAgentTools {
  private DefaultAgentTools() {}

  static void register(ToolRegistry registry, ObjectMapper mapper, MemoryStore store,
      ContextManager context, FinanceApiClient finance, RagChatService knowledge, BusinessTools business, PortfolioTools portfolios) {
    registry.register("search_docs", "Search the local help knowledge base for terminology, rules or API documentation. Cite returned sources.",
        Map.of("query", textSchema("A standalone knowledge search query")), true, (args, scope) -> {
          RagRetrieval retrieval = knowledge.retrieve(text(args, "query"), scope.control());
          String excerpts = retrieval.context();
          List<RagSource> sources = new ArrayList<>();
          for (int i = retrieval.sources().size() - 1; i >= 0; i--) {
            RagSource original = retrieval.sources().get(i);
            String citation = "S" + (scope.sourceOffset() + i + 1);
            excerpts = excerpts.replace("[" + original.citation() + "]", "[" + citation + "]");
            sources.add(0, new RagSource(citation, original.title(), original.section(), original.source(), original.score()));
          }
          return result(mapper, Map.of("excerpts", excerpts, "sources", sources), sources);
        });
    for (var tool : business.definitions()) {
      if (tool.name().equals("list_portfolios")) {
        if (!tool.path().equals("/portfolios") || !tool.parameters().isEmpty())
          throw new IllegalArgumentException("list_portfolios uses the managed /portfolios snapshot adapter; backend parameters are not supported");
        portfolios.register(registry, tool);
        continue;
      }
      registry.register(tool.name(), tool.description(), tool.fields(), true, (args, scope) -> {
        String path = tool.resolvedPath(args);
        Map<String, String> query = tool.query(args);
        JsonNode data = finance.get(path, query, scope.user(), scope.control());
        List<RagSource> sources = List.of(new RagSource("B" + (scope.sourceOffset() + 1), tool.title(), path,
            finance.sourceUrl(path, query, scope.user()), 1));
        return result(mapper, Map.of("data", data, "sources", sources, "mock", true), sources);
      });
      registry.status(tool.name(), tool.status());
    }
    registry.register("compare_portfolios", "Compare two of the current user's portfolios. Java calculates differences and allocation percentages. Different currencies are not converted.",
        Map.of("leftId", textSchema("First portfolio ID"), "rightId", textSchema("Second portfolio ID")), true, (args, scope) -> {
          String left = FinanceApiClient.segment(text(args, "leftId"));
          String right = FinanceApiClient.segment(text(args, "rightId"));
          JsonNode data = finance.compare(left, right, scope.user(), scope.control());
          List<RagSource> sources = new ArrayList<>();
          for (String path : List.of("/portfolios/" + left, "/portfolios/" + right,
              "/portfolios/" + left + "/positions", "/portfolios/" + right + "/positions")) {
            sources.add(source(path, "Portfolio comparison", scope, sources.size(), finance));
          }
          return result(mapper, Map.of("data", data, "sources", sources, "mock", true), sources);
        });
    registry.register("search_history", "Search this user's historical records by relevance with offset/limit pagination. For surrounding messages supply sessionId and aroundTurn instead of query. For saved large tool outputs supply sessionId, payloadRef, payloadOffset. Financial records are snapshots: re-query business tools for current facts.",
        Map.of("query", optionalText("Keywords, phrase or resource ID; null when reading a window"),
            "offset", optionalInteger("Search result offset; default 0", 0, 10000),
            "limit", optionalInteger("Search page size; default 5", 1, 20),
            "sessionId", optionalText("Session ID from a search hit; null for discovery"),
            "aroundTurn", optionalInteger("Anchor turn from a hit; null for discovery", 1, Integer.MAX_VALUE),
            "window", optionalInteger("Turns before and after the anchor; default 1", 0, 10),
            "payloadRef", optionalText("Saved payload reference from a tool result; null otherwise"),
            "payloadOffset", optionalInteger("Payload character offset; default 0. Follow returned nextOffset for more.", 0, 10000000)), true, (args, scope) -> {
          if (present(args, "payloadRef")) return result(mapper, store.readPayload(scope.user().id(),
              text(args, "sessionId"), text(args, "payloadRef"), integer(args, "payloadOffset", 0)), List.of());
          if (present(args, "sessionId")) return result(mapper, store.historyWindow(scope.user().id(),
              text(args, "sessionId"), integer(args, "aroundTurn", 1), integer(args, "window", 1)), List.of());
          return result(mapper, store.searchHistory(scope.user().id(), text(args, "query"),
              integer(args, "offset", 0), integer(args, "limit", 5)), List.of());
        });
    registry.register("remember", "Save or update an explicit stable user preference/fact. Use a stable key, reuse keys for corrections. Never save prices, holdings, secrets or inferred preferences.",
        Map.of("key", textSchema("2-48 lowercase letters/digits/underscores/hyphens"),
            "content", textSchema("Explicit stable user fact or preference, at most 1500 characters"),
            "target", Map.of("type", List.of("string", "null"), "enum", Arrays.asList("user", "memory", null),
                "description", "user for explicit preferences/profile (default); memory for stable business background")), (args, scope) -> {
          context.remember(scope.user().id(), scope.sessionId(), text(args, "key"), text(args, "content"),
              present(args, "target") ? text(args, "target") : "user", scope.control());
          return result(mapper, Map.of("saved", true, "memory", store.memory(scope.user().id())), List.of());
        });
    registry.register("forget_memory", "Remove a user memory entry when asked. Use its existing ## entry_key heading.",
        Map.of("key", textSchema("Existing memory entry key")), (args, scope) -> {
          context.forget(scope.user().id(), text(args, "key"));
          store.memoryCursor(scope.user().id(), scope.sessionId(),
              store.turnCount(scope.user().id(), scope.sessionId()) + 1);
          return result(mapper, Map.of("forgotten", true, "memory", store.memory(scope.user().id())), List.of());
        });
  }

  private static RagSource source(String path, String title, AgentTool.Scope scope, int offset, FinanceApiClient finance) {
    return new RagSource("B" + (scope.sourceOffset() + offset + 1), title, path,
        finance.sourceUrl(path, scope.user()), 1);
  }

  private static Map<String, Object> textSchema(String description) {
    return Map.of("type", "string", "description", description, "minLength", 1, "maxLength", 2_000);
  }

  private static AgentTool.Result result(ObjectMapper mapper, Object output, List<RagSource> sources) {
    return new AgentTool.Result(mapper.valueToTree(output), List.copyOf(sources));
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
