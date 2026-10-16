package com.example.ragbot;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** Per-request state, also available to recovery and the history recorder after a failure. */
final class AgentRun {
  final List<JsonNode> transcript = new ArrayList<>();
  final List<RagSource> sources = new ArrayList<>();
  final List<JsonNode> successfulResults = new ArrayList<>();
  final List<String> completedQueries = new ArrayList<>();
  final Set<String> callIds = new HashSet<>();
  int toolCalls;
  boolean finalRequested;
  String stopReason = "";
  JsonNode inputRequest;
  final java.util.Map<String, String> editViews = new java.util.LinkedHashMap<>();

  AgentEvent.EditResult captureEdit(JsonNode output) {
    String markdown = PortfolioEditView.markdown(output);
    if (markdown.isBlank()) return null;
    String id = output.path("operationId").asString();
    editViews.put(id, markdown);
    return new AgentEvent.EditResult(id, markdown);
  }

  String withEditViews(String text) {
    return editViews.isEmpty() ? text : text + "\n\n" + String.join("\n\n", editViews.values());
  }

  record Answer(String text, boolean completed) {}
}
