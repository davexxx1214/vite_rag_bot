package com.example.ragbot;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Cancels only network work; the run's file writes can finish without a thread interrupt. */
public final class RunControl {
  private final long deadline;
  private volatile boolean cancelled;
  private volatile boolean workExpired;
  private boolean finalPhase;
  private final Map<Thread, InputStream> streams = new HashMap<>();
  private final Set<CompletableFuture<HttpResponse<InputStream>>> requests = new HashSet<>();

  public RunControl(int timeoutSeconds) {
    deadline = System.nanoTime() + Duration.ofSeconds(timeoutSeconds).toNanos();
  }

  public synchronized void attach(InputStream input) throws IOException {
    check();
    streams.put(Thread.currentThread(), input);
  }

  public synchronized void detach() {
    streams.remove(Thread.currentThread());
  }

  public synchronized void cancel() {
    cancelled = true;
    stopNetwork();
  }

  public synchronized void finishWork() {
    if (finalPhase || cancelled) return;
    workExpired = true;
    stopNetwork();
  }

  private void stopNetwork() {
    for (var request : List.copyOf(requests)) request.cancel(true);
    for (var stream : List.copyOf(streams.values())) {
      try { stream.close(); } catch (IOException ignored) { /* Already disconnected. */ }
    }
  }

  public synchronized void beginFinal() throws IOException {
    finalPhase = true;
    workExpired = false;
    check();
  }

  public HttpResponse<InputStream> send(HttpClient client, HttpRequest httpRequest)
      throws IOException, InterruptedException {
    CompletableFuture<HttpResponse<InputStream>> pending;
    synchronized (this) {
      check();
      pending = client.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
      requests.add(pending);
    }
    boolean handedOff = false;
    try {
      HttpResponse<InputStream> response = pending.get(timeout().toNanos(), TimeUnit.NANOSECONDS);
      // Transfer cancellation from the pending request to its response stream without a gap.
      attach(response.body());
      handedOff = true;
      return response;
    } catch (CancellationException error) {
      check();
      throw new IOException("HTTP request cancelled", error);
    } catch (TimeoutException error) {
      throw new IOException("HTTP request timed out", error);
    } catch (ExecutionException error) {
      check();
      if (error.getCause() instanceof IOException io) throw io;
      throw new IOException("HTTP request failed", error.getCause());
    } finally {
      if (!handedOff) pending.whenComplete((received, error) -> {
        if (received != null) try { received.body().close(); } catch (IOException ignored) { /* Already closed. */ }
      });
      if (!handedOff) pending.cancel(true);
      synchronized (this) { requests.remove(pending); }
    }
  }

  public boolean workExpired() { return workExpired; }
  public boolean cancelled() { return cancelled; }
  public boolean timedOut() { return System.nanoTime() >= deadline; }

  public void check() throws IOException {
    if (System.nanoTime() >= deadline) throw new IOException("Agent request timed out");
    if (cancelled || Thread.currentThread().isInterrupted()) throw new IOException("Agent request cancelled");
    if (workExpired) throw new IOException("Query time budget reached; final answer reserved");
  }

  public Duration timeout() throws IOException {
    check();
    return Duration.ofNanos(Math.max(1, Math.min(Duration.ofSeconds(40).toNanos(), deadline - System.nanoTime())));
  }
}
