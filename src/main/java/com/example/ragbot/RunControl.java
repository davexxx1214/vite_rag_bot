package com.example.ragbot;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;

/** One owner thread per run; closing the live stream also interrupts blocked SSE reads. */
final class RunControl {
  private final Thread owner = Thread.currentThread();
  private final long deadline;
  private volatile boolean cancelled;
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

  void check() throws IOException {
    if (System.nanoTime() >= deadline) throw new IOException("Agent request timed out");
    if (cancelled || Thread.currentThread().isInterrupted()) throw new IOException("Agent request cancelled");
  }

  Duration timeout() throws IOException {
    check();
    return Duration.ofNanos(Math.max(1, Math.min(Duration.ofSeconds(40).toNanos(), deadline - System.nanoTime())));
  }
}
