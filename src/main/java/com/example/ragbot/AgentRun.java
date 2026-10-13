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

  record Answer(String text, boolean completed) {}
}
