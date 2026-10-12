package com.example.ragbot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class RunControlTest {
  @TempDir Path root;

  @Test
  void cancellationStopsWaitingForHeaders() throws Exception { stalledHttp(false, false, 0); }

  @Test
  void queryBudgetStopsReadingResponseBody() throws Exception { stalledHttp(true, true, 0); }

  @Test
  void summaryCancellationClosesNonStreamingBody() throws Exception { stalledHttp(true, false, 1); }

  @Test
  void knowledgeEmbeddingCancellationStopsWaitingForHeaders() throws Exception { stalledHttp(false, true, 2); }

  @Test
  void financeCancellationClosesBody() throws Exception { stalledHttp(true, false, 3); }

  @Test
  void modelStreamCancellationClosesBody() throws Exception { stalledHttp(true, true, 4); }

  @Test
  void cancellationAtHeaderHandoffClosesUnclaimedResponse() throws Exception {
    var control = new RunControl(30);
    var client = org.mockito.Mockito.mock(HttpClient.class);
    @SuppressWarnings("unchecked")
    var response = (java.net.http.HttpResponse<java.io.InputStream>) org.mockito.Mockito.mock(java.net.http.HttpResponse.class);
    var body = org.mockito.Mockito.mock(java.io.InputStream.class);
    org.mockito.Mockito.when(response.body()).thenReturn(body);
    org.mockito.Mockito.when(client.sendAsync(org.mockito.ArgumentMatchers.any(HttpRequest.class),
        org.mockito.ArgumentMatchers.<java.net.http.HttpResponse.BodyHandler<java.io.InputStream>>any()))
        .thenAnswer(invocation -> {
          control.finishWork();
          return java.util.concurrent.CompletableFuture.completedFuture(response);
        });
    assertThatThrownBy(() -> control.send(client, HttpRequest.newBuilder(URI.create("http://localhost/")).build()))
        .isInstanceOf(IOException.class).hasMessageContaining("Query time budget");
    org.mockito.Mockito.verify(body).close();
    assertThat(Thread.currentThread().isInterrupted()).isFalse();
    control.beginFinal();
    control.check();
  }

  @Test
  void cancellationClosesAllConcurrentBodiesAndPendingHeaders() throws Exception { parallelNetworkRequests(false); }

  @Test
  void queryDeadlineClosesAllConcurrentRequestsAndAllowsFinalNetworkWork() throws Exception { parallelNetworkRequests(true); }

  private void parallelNetworkRequests(boolean queryDeadline) throws Exception {
    var entered = new CountDownLatch(2);
    var reading = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var control = new RunControl(30);
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    try (var pool = Executors.newVirtualThreadPerTaskExecutor(); var client = HttpClient.newHttpClient()) {
      server.setExecutor(pool);
      server.createContext("/", exchange -> {
        try {
          if (exchange.getRequestURI().getPath().equals("/ready")) {
            exchange.sendResponseHeaders(200, 2); exchange.getResponseBody().write(new byte[] {1, 2}); return;
          }
          if (exchange.getRequestURI().getPath().equals("/body")) {
            exchange.sendResponseHeaders(200, 0); exchange.getResponseBody().flush();
          }
          entered.countDown();
          release.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        finally { exchange.close(); }
      });
      server.start();
      String base = "http://127.0.0.1:" + server.getAddress().getPort();
      var futures = new java.util.ArrayList<java.util.concurrent.Future<IOException>>();
      for (String path : java.util.List.of("/body", "/headers")) futures.add(pool.submit(() -> {
        try {
          var response = control.send(client, HttpRequest.newBuilder(URI.create(base + path)).GET().build());
          try (var body = response.body()) { reading.countDown(); body.readAllBytes(); control.check(); }
          finally { control.detach(); }
          throw new AssertionError("Cancelled network work must stop");
        } catch (IOException error) {
          assertThat(Thread.currentThread().isInterrupted()).isFalse(); return error;
        }
      }));
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(reading.await(5, TimeUnit.SECONDS)).isTrue();
      // Detaching an unrelated completed request must not unregister either blocked worker.
      var ready = control.send(client, HttpRequest.newBuilder(URI.create(base + "/ready")).GET().build());
      try (var body = ready.body()) { assertThat(body.readAllBytes()).hasSize(2); }
      finally { control.detach(); }
      if (queryDeadline) control.finishWork(); else control.cancel();
      for (var future : futures) assertThat(future.get(3, TimeUnit.SECONDS)).isInstanceOf(IOException.class);
      assertThat(release.getCount()).isEqualTo(1);
      if (queryDeadline) {
        control.beginFinal();
        var finalResponse = control.send(client, HttpRequest.newBuilder(URI.create(base + "/ready")).GET().build());
        try (var body = finalResponse.body()) { assertThat(body.readAllBytes()).hasSize(2); }
        finally { control.detach(); }
      }
      release.countDown();
    } finally { release.countDown(); server.stop(0); }
  }

  private void stalledHttp(boolean headers, boolean workBudget, int kind) throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var control = new RunControl(30);
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    try (var pool = Executors.newVirtualThreadPerTaskExecutor(); var client = HttpClient.newHttpClient()) {
      server.setExecutor(pool);
      server.createContext("/", exchange -> {
        try {
          if (headers) { exchange.sendResponseHeaders(200, 0); exchange.getResponseBody().flush(); }
          entered.countDown();
          release.await(10, TimeUnit.SECONDS);
          if (!headers) exchange.sendResponseHeaders(200, 0);
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        finally { exchange.close(); }
      });
      server.start();
      URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
      var props = AgentTestSupport.properties(root);
      var rag = new RagProperties("test-key", "gpt-6-luna", "test-embedding", base,
          Path.of("docs"), Path.of("data/rag-index.json"), false);
      var model = new OpenAiClient(rag, props, new JsonMapper());
      var worker = pool.submit(() -> {
        try {
          if (kind == 1) model.summarize("Summarize", "Data", 100, control);
          else if (kind == 2) model.embed("Question", "test-embedding", control);
          else if (kind == 3) {
            var finance = new FinanceApiClient(props, new JsonMapper());
            var web = org.mockito.Mockito.mock(org.springframework.boot.web.server.WebServer.class);
            org.mockito.Mockito.when(web.getPort()).thenReturn(server.getAddress().getPort());
            var event = org.mockito.Mockito.mock(org.springframework.boot.web.server.context.WebServerInitializedEvent.class);
            org.mockito.Mockito.when(event.getWebServer()).thenReturn(web);
            finance.serverStarted(event);
            finance.get("/portfolios", new MockFinanceService().users().getFirst(), control);
          } else if (kind == 4) model.respond("Test", java.util.List.of(), java.util.List.of(), false, text -> {}, control);
          else {
            var response = control.send(client, HttpRequest.newBuilder(base).GET().build());
            try (var body = response.body()) { body.readAllBytes(); control.check(); }
            finally { control.detach(); }
          }
          throw new AssertionError("Cancelled request must stop");
        } catch (IOException error) {
          assertThat(Thread.currentThread().isInterrupted()).isFalse();
          return error;
        }
      });
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
      if (workBudget) control.finishWork(); else control.cancel();
      assertThat(worker.get(3, TimeUnit.SECONDS)).isInstanceOf(IOException.class);
      release.countDown();
      if (workBudget) {
        control.beginFinal();
        control.check();
        assertThat(control.cancelled()).isFalse();
      } else assertThatThrownBy(control::beginFinal).isInstanceOf(IOException.class);
    } finally { release.countDown(); server.stop(0); }
  }

  @Test
  void cancellationDuringFileWriteLeavesChannelAndStoredMemoryIntact() throws Exception {
    var mapper = new JsonMapper();
    String session;
    try (var store = new MemoryStore(AgentTestSupport.properties(root), mapper, new MockFinanceService());
        var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      session = store.createSession("user-001");
      var writing = new CountDownLatch(1);
      var cancelled = new CountDownLatch(1);
      var worker = pool.submit(() -> {
        var control = new RunControl(30);
        pool.submit(() -> {
          writing.await(); control.finishWork(); control.cancel(); cancelled.countDown(); return null;
        });
        Path file = root.resolve("write-in-progress.txt");
        try (var channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
          channel.write(ByteBuffer.wrap(new byte[] {1}));
          writing.countDown();
          assertThat(cancelled.await(3, TimeUnit.SECONDS)).isTrue();
          assertThat(Thread.currentThread().isInterrupted()).isFalse();
          channel.write(ByteBuffer.wrap(new byte[] {2, 3}));
          channel.force(true);
          assertThat(channel.size()).isEqualTo(3);
          store.coreMemory("user-001", new MemoryStore.CoreMemory("## language\nChinese", "## reporting\nUSD"));
          store.append("user-001", session, AgentTestSupport.turn(mapper, 1, "Question", "Saved answer"));
        }
        assertThatThrownBy(control::check).isInstanceOf(IOException.class);
        return true;
      });
      assertThat(worker.get(5, TimeUnit.SECONDS)).isTrue();
    }
    try (var restarted = new MemoryStore(AgentTestSupport.properties(root), mapper, new MockFinanceService())) {
      assertThat(restarted.coreMemory("user-001").user()).contains("Chinese");
      assertThat(restarted.coreMemory("user-001").notes()).contains("USD");
      assertThat(restarted.session("user-001", session).turns().getFirst().answer()).isEqualTo("Saved answer");
    }
  }
}
