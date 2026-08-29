package com.example.ragbot;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class HelpServer implements AutoCloseable {
  private static final int MAX_REQUEST_BYTES = 20 * 1_024;
  private static final String INSTRUCTIONS = """
      You are a concise help-center assistant. Answer using only the supplied knowledge-base excerpts. Reply in the same language as the user's current question. Use concise GitHub-Flavored Markdown when it improves readability: short headings, bullet or numbered lists, bold text, inline code, and fenced code blocks. Cite supporting excerpts inline as [S1], [S2], and so on, and keep citation markers outside code formatting. If the excerpts do not contain enough information, say that the local help documents do not cover it. Never invent API behavior, URLs, or trading rules.
      """.trim();

  private final Path projectRoot;
  private final AppConfig config;
  private final List<HelpDoc> docs;
  private final RagIndex ragIndex;
  private final OpenAiClient openAi;
  private final HttpServer server;
  private final ExecutorService executor;

  HelpServer(
      Path projectRoot,
      AppConfig config,
      List<HelpDoc> docs,
      RagIndex ragIndex,
      OpenAiClient openAi
  ) throws IOException {
    this.projectRoot = projectRoot;
    this.config = config;
    this.docs = docs;
    this.ragIndex = ragIndex;
    this.openAi = openAi;
    this.server = HttpServer.create(new InetSocketAddress(config.port()), 0);
    this.executor = Executors.newVirtualThreadPerTaskExecutor();
    server.setExecutor(executor);
    server.createContext("/api/health", this::handleHealth);
    server.createContext("/api/docs", this::handleDocs);
    server.createContext("/api/chat", this::handleChat);
    server.createContext("/", this::handleStatic);
  }

  void start() {
    server.start();
    System.out.printf("Help Center Java service running at http://localhost:%d%n", config.port());
    System.out.printf("Loaded %d documents and %d RAG chunks.%n", docs.size(), ragIndex.chunks().size());
  }

  @Override
  public void close() {
    server.stop(1);
    executor.shutdownNow();
  }

  private void handleHealth(HttpExchange exchange) throws IOException {
    if (!requirePath(exchange, "/api/health")) return;
    if (!requireMethod(exchange, "GET")) return;
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("ok", true);
    payload.put("runtime", "java-" + Runtime.version().feature());
    payload.put("documents", docs.size());
    payload.put("chunks", ragIndex.chunks().size());
    payload.put("embeddingModel", ragIndex.embeddingModel());
    payload.put("chatModel", config.chatModel());
    sendJson(exchange, 200, payload);
  }

  private void handleDocs(HttpExchange exchange) throws IOException {
    if (!requirePath(exchange, "/api/docs")) return;
    if (!requireMethod(exchange, "GET")) return;
    sendJson(exchange, 200, docs.stream().map(HelpDoc::toMap).toList());
  }

  private void handleChat(HttpExchange exchange) throws IOException {
    if (!requirePath(exchange, "/api/chat")) return;
    if (!requireMethod(exchange, "POST")) return;

    Map<String, Object> request;
    try {
      request = Json.object(Json.parse(readBody(exchange)));
    } catch (RequestTooLargeException error) {
      sendJson(exchange, 413, Map.of("error", error.getMessage()));
      return;
    } catch (RuntimeException error) {
      sendJson(exchange, 400, Map.of("error", "Request body must be valid JSON"));
      return;
    }

    String message = request.get("message") instanceof String value ? value.trim() : "";
    if (message.isEmpty()) {
      sendJson(exchange, 400, Map.of("error", "Message is required"));
      return;
    }
    if (message.length() > 2_000) {
      sendJson(exchange, 400, Map.of("error", "Message must be 2,000 characters or fewer"));
      return;
    }
    List<HistoryMessage> history = parseHistory(request.get("history"));

    Headers headers = exchange.getResponseHeaders();
    headers.set("Content-Type", "application/x-ndjson; charset=utf-8");
    headers.set("Cache-Control", "no-cache, no-transform");
    headers.set("X-Accel-Buffering", "no");
    headers.set("X-Content-Type-Options", "nosniff");
    exchange.sendResponseHeaders(200, 0);

    long startedAt = System.currentTimeMillis();
    try (OutputStream output = exchange.getResponseBody()) {
      NdjsonWriter writer = new NdjsonWriter(output);
      try {
        writer.write(Map.of("type", "status", "message", "Searching the help documents…"));

      long retrievalStartedAt = System.currentTimeMillis();
      double[] queryEmbedding = openAi.embed(message, ragIndex.embeddingModel());
      List<RagMatch> matches = ragIndex.search(queryEmbedding, 5);
      System.out.printf(
          "[chat] retrieval completed in %dms%n",
          System.currentTimeMillis() - retrievalStartedAt
      );

      List<Map<String, Object>> sources = new ArrayList<>();
      StringBuilder context = new StringBuilder();
      for (int index = 0; index < matches.size(); index++) {
        RagMatch match = matches.get(index);
        RagChunk chunk = match.chunk();
        String citation = "S" + (index + 1);
        if (!context.isEmpty()) context.append("\n\n---\n\n");
        context.append('[').append(citation).append("] ")
            .append(chunk.title()).append(" — ").append(chunk.section()).append('\n')
            .append("Source: ").append(chunk.source()).append('\n')
            .append(chunk.content());

        Map<String, Object> source = new LinkedHashMap<>();
        source.put("citation", citation);
        source.put("title", chunk.title());
        source.put("section", chunk.section());
        source.put("source", chunk.source());
        source.put("score", Math.round(match.score() * 10_000d) / 10_000d);
        sources.add(source);
      }

      writer.write(Map.of("type", "sources", "sources", sources));
      writer.write(Map.of("type", "status", "message", "Generating an answer…"));

      String conversation = history.isEmpty()
          ? "No earlier messages."
          : String.join(
              "\n",
              history.stream()
                  .map(item -> (item.role().equals("user") ? "User: " : "Assistant: ") + item.content())
                  .toList()
          );
      String modelInput = "Knowledge-base excerpts:\n\n" + context
          + "\n\nRecent conversation:\n" + conversation
          + "\n\nCurrent user question:\n" + message;

      openAi.streamAnswer(
          INSTRUCTIONS,
          modelInput,
          delta -> writer.write(Map.of("type", "delta", "text", delta))
      );
      writer.write(Map.of("type", "done"));
        System.out.printf(
            "[chat] response completed in %dms%n",
            System.currentTimeMillis() - startedAt
        );
      } catch (Exception error) {
        String publicMessage = hasCause(error, HttpTimeoutException.class)
            ? "The AI service timed out. Please try again."
            : "The AI request failed. Please try again or check the server configuration.";
        System.err.printf(
            "[chat] request failed after %dms (%s): %s%n",
            System.currentTimeMillis() - startedAt,
            error.getClass().getSimpleName(),
            error.getMessage()
        );
        try {
          writer.write(Map.of("type", "error", "error", publicMessage));
        } catch (IOException ignored) {
          // The browser disconnected, so there is nowhere left to write the error.
        }
      }
    } finally {
      exchange.close();
    }
  }

  private void handleStatic(HttpExchange exchange) throws IOException {
    if (!exchange.getRequestMethod().equals("GET") && !exchange.getRequestMethod().equals("HEAD")) {
      sendJson(exchange, 405, Map.of("error", "Method not allowed"));
      return;
    }
    String requestPath = exchange.getRequestURI().getPath();
    if (requestPath.startsWith("/api/")) {
      sendJson(exchange, 404, Map.of("error", "API route not found"));
      return;
    }

    Path clientRoot = projectRoot.resolve("dist").toAbsolutePath().normalize();
    String relativePath = requestPath.equals("/") ? "index.html" : requestPath.substring(1);
    Path target = clientRoot.resolve(relativePath).normalize();
    if (!target.startsWith(clientRoot)) {
      sendJson(exchange, 403, Map.of("error", "Forbidden"));
      return;
    }
    if (!Files.isRegularFile(target)) {
      if (relativePath.contains(".")) {
        sendJson(exchange, 404, Map.of("error", "File not found"));
        return;
      }
      target = clientRoot.resolve("index.html");
    }
    if (!Files.isRegularFile(target)) {
      sendJson(exchange, 503, Map.of("error", "Frontend build not found. Run pnpm build first."));
      return;
    }

    byte[] bytes = Files.readAllBytes(target);
    Headers headers = exchange.getResponseHeaders();
    headers.set("Content-Type", contentType(target));
    headers.set("X-Content-Type-Options", "nosniff");
    headers.set(
        "Cache-Control",
        target.getFileName().toString().equals("index.html")
            ? "no-cache"
            : "public, max-age=31536000, immutable"
    );
    if (exchange.getRequestMethod().equals("HEAD")) {
      exchange.sendResponseHeaders(200, -1);
    } else {
      exchange.sendResponseHeaders(200, bytes.length);
      try (OutputStream output = exchange.getResponseBody()) {
        output.write(bytes);
      }
    }
    exchange.close();
  }

  private static List<HistoryMessage> parseHistory(Object rawHistory) {
    if (!(rawHistory instanceof List<?> values)) return List.of();
    List<HistoryMessage> result = new ArrayList<>();
    int start = Math.max(0, values.size() - 6);
    for (int index = start; index < values.size(); index++) {
      Object rawItem = values.get(index);
      if (!(rawItem instanceof Map<?, ?>)) continue;
      Map<String, Object> item = Json.object(rawItem);
      Object rawRole = item.get("role");
      Object rawContent = item.get("content");
      if (!(rawRole instanceof String role) || !(rawContent instanceof String content)) continue;
      if (!role.equals("user") && !role.equals("assistant")) continue;
      result.add(new HistoryMessage(role, content.substring(0, Math.min(content.length(), 2_000))));
    }
    return List.copyOf(result);
  }

  private static String readBody(HttpExchange exchange) throws IOException {
    try (InputStream input = exchange.getRequestBody()) {
      byte[] bytes = input.readNBytes(MAX_REQUEST_BYTES + 1);
      if (bytes.length > MAX_REQUEST_BYTES) {
        throw new RequestTooLargeException("Request body must be 20 KB or smaller");
      }
      return new String(bytes, StandardCharsets.UTF_8);
    }
  }

  private static boolean requireMethod(HttpExchange exchange, String expected) throws IOException {
    if (exchange.getRequestMethod().equals(expected)) return true;
    exchange.getResponseHeaders().set("Allow", expected);
    sendJson(exchange, 405, Map.of("error", "Method not allowed"));
    return false;
  }

  private static boolean requirePath(HttpExchange exchange, String expected) throws IOException {
    if (exchange.getRequestURI().getPath().equals(expected)) return true;
    sendJson(exchange, 404, Map.of("error", "API route not found"));
    return false;
  }

  private static void sendJson(HttpExchange exchange, int status, Object payload) throws IOException {
    byte[] bytes = Json.stringify(payload).getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    exchange.getResponseHeaders().set("Cache-Control", "no-store");
    exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream output = exchange.getResponseBody()) {
      output.write(bytes);
    }
    exchange.close();
  }

  private static String contentType(Path path) {
    String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
    if (name.endsWith(".html")) return "text/html; charset=utf-8";
    if (name.endsWith(".js")) return "text/javascript; charset=utf-8";
    if (name.endsWith(".css")) return "text/css; charset=utf-8";
    if (name.endsWith(".json")) return "application/json; charset=utf-8";
    if (name.endsWith(".svg")) return "image/svg+xml";
    if (name.endsWith(".png")) return "image/png";
    if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
    if (name.endsWith(".ico")) return "image/x-icon";
    if (name.endsWith(".woff2")) return "font/woff2";
    return "application/octet-stream";
  }

  private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
    for (Throwable current = error; current != null; current = current.getCause()) {
      if (type.isInstance(current)) return true;
    }
    return false;
  }

  private record HistoryMessage(String role, String content) {}

  private static final class NdjsonWriter {
    private final OutputStream output;

    private NdjsonWriter(OutputStream output) {
      this.output = output;
    }

    private void write(Map<String, Object> event) throws IOException {
      output.write((Json.stringify(event) + "\n").getBytes(StandardCharsets.UTF_8));
      output.flush();
    }
  }

  private static final class RequestTooLargeException extends IOException {
    private static final long serialVersionUID = 1L;

    private RequestTooLargeException(String message) {
      super(message);
    }
  }
}
