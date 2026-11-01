package com.example.ssbotcli;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class OpenAiClientTest {
  final ObjectMapper json = new ObjectMapper();
  @Test void streamsTextAndRetainsCompletedOutputItems() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/responses", exchange -> {
      var request = json.readTree(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      assertTrue(request.path("stream").asBoolean()); assertFalse(request.path("store").asBoolean());
      String body = "data: " + json.writeValueAsString(Map.of("type", "response.output_text.delta", "delta", "你好")) + "\n\n"
          + "data: " + json.writeValueAsString(Map.of("type", "response.completed", "response", Map.of("status", "completed",
          "output", List.of(Map.of("type", "reasoning", "encrypted_content", "opaque"), Map.of("type", "message", "role", "assistant",
          "content", List.of(Map.of("type", "output_text", "text", "你好"))))))) + "\n\n";
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
      exchange.sendResponseHeaders(200, bytes.length);
      exchange.getResponseBody().write(bytes); exchange.close();
    });
    server.start();
    try {
      StringBuilder text = new StringBuilder();
      var turn = client(server).respond("instructions", List.of(), List.of(), false, text::append, new RunControl(10));
      assertEquals("你好", text.toString()); assertEquals("opaque", turn.output().getFirst().path("encrypted_content").asString());
    } finally { server.stop(0); }
  }

  @Test void cancellationReleasesStalledResponseStream() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    CountDownLatch opened = new CountDownLatch(1), release = new CountDownLatch(1);
    server.createContext("/v1/responses", exchange -> {
      exchange.getRequestBody().readAllBytes();
      exchange.sendResponseHeaders(200, 0); exchange.getResponseBody().flush(); opened.countDown();
      try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
      exchange.close();
    });
    server.start();
    RunControl control = new RunControl(10);
    try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var work = executor.submit(() -> client(server).respond("i", List.of(), List.of(), false, t -> {}, control));
      assertTrue(opened.await(3, TimeUnit.SECONDS)); control.cancel();
      assertThrows(java.util.concurrent.ExecutionException.class, () -> work.get(2, TimeUnit.SECONDS));
    } finally { release.countDown(); server.stop(0); }
  }
  OpenAiClient client(HttpServer server) {
    return new OpenAiClient(new Config("test", "test", URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"),
        "low", 10, 3, 2), json);
  }

  @Test void deadlineClosesAStreamEvenWhenServerStopsSendingEvents() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    CountDownLatch release = new CountDownLatch(1);
    server.createContext("/v1/responses", exchange -> {
      exchange.getRequestBody().readAllBytes(); exchange.sendResponseHeaders(200, 0);
      exchange.getResponseBody().flush();
      try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
      exchange.close();
    });
    server.start();
    try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      RunControl control = new RunControl(1);
      var work = executor.submit(() -> client(server).respond("i", List.of(), List.of(), false, t -> {}, control));
      var error = assertThrows(java.util.concurrent.ExecutionException.class, () -> work.get(3, TimeUnit.SECONDS));
      assertTrue(error.getCause().getMessage().contains("timed out"));
    } finally { release.countDown(); server.stop(0); }
  }
}
