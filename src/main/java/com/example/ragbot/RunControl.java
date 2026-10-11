package com.example.ragbot;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;

/** One owner thread per run; closing the live stream also interrupts blocked SSE reads. */
final class RunControl {
  private final Thread owner = Thread.currentThread();
  private final long deadline;
  private volatile boolean cancelled;
  private volatile boolean workExpired;
  private boolean finalPhase;
  private InputStream stream;

  RunControl(int timeoutSeconds) {
    deadline = System.nanoTime() + Duration.ofSeconds(timeoutSeconds).toNanos();
  }

  synchronized void attach(InputStream input) throws IOException {
    check();
    stream = input;
  }

  synchronized void detach() {
    stream = null;
  }

  synchronized void cancel() {
    cancelled = true;
    owner.interrupt();
    if (stream != null) {
      try {
        stream.close();
      } catch (IOException ignored) {
        // A cancelled stream may already have disconnected.
      }
    }
  }

  synchronized void finishWork() {
    if (finalPhase || cancelled) return;
    workExpired = true;
    owner.interrupt();
    if (stream != null) {
      try { stream.close(); } catch (IOException ignored) { /* Already disconnected. */ }
    }
  }

  synchronized void beginFinal() throws IOException {
    finalPhase = true;
    workExpired = false;
    if (!cancelled) Thread.interrupted();
    check();
  }

  boolean workExpired() { return workExpired; }
  boolean cancelled() { return cancelled; }
  boolean timedOut() { return System.nanoTime() >= deadline; }

  void check() throws IOException {
    if (System.nanoTime() >= deadline) throw new IOException("Agent request timed out");
    if (cancelled || Thread.currentThread().isInterrupted()) throw new IOException("Agent request cancelled");
    if (workExpired) throw new IOException("Query time budget reached; final answer reserved");
  }

  Duration timeout() throws IOException {
    check();
    return Duration.ofNanos(Math.max(1, Math.min(Duration.ofSeconds(40).toNanos(), deadline - System.nanoTime())));
  }
}
