package com.example.ragbot;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/** Business tool boundary; only explicitly declared reads may run concurrently. */
public interface AgentTool {
  record Scope(MockFinanceService.User user, String sessionId, int sourceOffset, RunControl control, AgentListener listener) {
    public Scope(MockFinanceService.User user, String sessionId, int sourceOffset, RunControl control) {
      this(user, sessionId, sourceOffset, control, null);
    }
    void emit(AgentEvent event) throws IOException { if (listener != null) listener.on(event); }
  }
  record Result(JsonNode output, List<RagSource> sources) {}

  String name();
  String description();
  Map<String, Object> parameters();
  default boolean readOnly() { return false; }

  /** Return call-local citations; the coordinator assigns final citations before budgeting/storage. */
  Result execute(JsonNode arguments, Scope scope) throws IOException, InterruptedException;
}
