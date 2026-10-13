package com.example.ragbot;

import java.util.List;

/** Run events independent of the HTTP/NDJSON wire representation. */
public sealed interface AgentEvent {
  record ActivityStart(String id, String message, String detail) implements AgentEvent {}
  record ActivityUpdate(String id, String message) implements AgentEvent {}
  record ActivityEnd(String id, boolean ok, long elapsedMs) implements AgentEvent {}
  record AnswerReset() implements AgentEvent {}
  record Delta(String text) implements AgentEvent {}
  record ToolStart(String callId, String name, String arguments) implements AgentEvent {}
  record ToolEnd(String callId, String name, boolean ok, long elapsedMs) implements AgentEvent {}
  record Sources(List<RagSource> sources) implements AgentEvent {}
  record UserInputRequired(tools.jackson.databind.JsonNode inputRequest) implements AgentEvent {}
  record ContextCompacted() implements AgentEvent {}
  record Done(boolean partial, String reason, int toolCalls) implements AgentEvent {}
}
