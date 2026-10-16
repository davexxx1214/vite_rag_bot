package com.example.ragbot;

import java.io.IOException;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/** Keeps the existing UI protocol and bounded, non-sensitive tool labels in one place. */
final class NdjsonAgentListener implements AgentListener {
  private final AgentService.EventSink sink;
  private final ToolRegistry tools;
  private final ObjectMapper mapper;
  private boolean disconnected;

  NdjsonAgentListener(AgentService.EventSink sink, ToolRegistry tools, ObjectMapper mapper) {
    this.sink = sink;
    this.tools = tools;
    this.mapper = mapper;
  }

  boolean disconnected() { return disconnected; }

  @Override
  public void on(AgentEvent event) throws IOException {
    Map<String, Object> wire = switch (event) {
      case AgentEvent.ActivityStart e -> Map.of("type", "activity_start", "id", e.id(), "message", e.message(), "detail", e.detail());
      case AgentEvent.ActivityUpdate e -> Map.of("type", "activity_update", "id", e.id(), "message", e.message());
      case AgentEvent.ActivityEnd e -> Map.of("type", "activity_end", "id", e.id(), "ok", e.ok(), "elapsedMs", e.elapsedMs());
      case AgentEvent.AnswerReset e -> Map.of("type", "answer_reset");
      case AgentEvent.Delta e -> Map.of("type", "delta", "text", e.text());
      case AgentEvent.ToolStart e -> Map.of("type", "tool_start", "id", "tool-" + e.callId(), "name", e.name(),
          "message", status(e.name()), "detail", ToolLabels.detail(mapper, e.name(), e.arguments()));
      case AgentEvent.ToolEnd e -> Map.of("type", "tool_end", "id", "tool-" + e.callId(), "name", e.name(),
          "ok", e.ok(), "elapsedMs", e.elapsedMs());
      case AgentEvent.Sources e -> Map.of("type", "sources", "sources", e.sources());
      case AgentEvent.UserInputRequired e -> Map.of("type", "user_input_required", "inputRequest", e.inputRequest());
      case AgentEvent.EditResult e -> Map.of("type", "edit_result", "operationId", e.operationId(), "markdown", e.markdown());
      case AgentEvent.ContextCompacted e -> Map.of("type", "context_compacted", "message", "Earlier conversation summarized; original history retained.");
      case AgentEvent.Done e -> Map.of("type", "done", "partial", e.partial(), "reason", e.reason(), "toolCalls", e.toolCalls());
    };
    try { sink.write(wire); }
    catch (IOException error) { disconnected = true; throw error; }
  }

  private String status(String name) {
    String configured = tools.status(name);
    if (configured != null) return configured;
    return switch (name) {
      case "search_docs" -> "正在搜索本地知识库…";
      case "compare_portfolios" -> "正在比较投资组合…";
      case "remember" -> "正在保存长期记忆…";
      case "forget_memory" -> "正在删除指定记忆…";
      case "search_history" -> "正在搜索历史记忆…";
      default -> "正在调用工具…";
    };
  }

}
