package com.example.ragbot;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/** Timed run events; listeners own their presentation and delivery. */
final class AgentProgress {
  private final AgentListener listener;

  AgentProgress(AgentListener listener) { this.listener = listener; }
  void emit(AgentEvent event) throws IOException { listener.on(event); }
  long start(String id, String message, String detail) throws IOException {
    emit(new AgentEvent.ActivityStart(id, message, detail));
    return System.nanoTime();
  }
  void update(String id, String message) throws IOException { emit(new AgentEvent.ActivityUpdate(id, message)); }
  void end(String id, long started, boolean ok) throws IOException {
    emit(new AgentEvent.ActivityEnd(id, ok, elapsed(started)));
  }
  static long elapsed(long started) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started); }
}
