package com.example.ragbot;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

/** One committed history turn per request, including failed/partial requests. */
final class AgentHistoryRecorder {
  private final MemoryStore store;
  private final String userId;
  private final String sessionId;
  private final String message;
  private final String model;
  private final AgentRun run;
  private int index;
  private boolean stored;

  AgentHistoryRecorder(MemoryStore store, String userId, String sessionId, String message, String model, AgentRun run) {
    this.store = store;
    this.userId = userId;
    this.sessionId = sessionId;
    this.message = message;
    this.model = model;
    this.run = run;
  }

  void begin() throws IOException { index = store.turnCount(userId, sessionId) + 1; }
  boolean started() { return index > 0; }
  boolean stored() { return stored; }

  void save(AgentRun.Answer answer) throws IOException {
    store.append(userId, sessionId, new MemoryStore.StoredTurn(index, Instant.now().toString(), message,
        List.copyOf(run.transcript), answer.text(), List.copyOf(run.sources), answer.completed(), model));
    stored = true;
  }

  void failed(Exception error) {
    if (stored || !started()) return;
    try { save(new AgentRun.Answer("Request cancelled or failed. Please retry.", false)); }
    catch (IOException saveError) { error.addSuppressed(saveError); }
  }
}
