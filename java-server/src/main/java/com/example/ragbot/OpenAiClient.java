package com.example.ragbot;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class OpenAiClient {
  private static final int EMBEDDING_BATCH_SIZE = 64;

  @FunctionalInterface
  interface TextSink {
    void accept(String text) throws IOException;
  }

  private final AppConfig config;
  private final HttpClient http;

  OpenAiClient(AppConfig config) {
    this.config = config;
    this.http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
  }

  double[] embed(String input, String model) throws IOException, InterruptedException {
    List<double[]> embeddings = embedTexts(List.of(input), model);
    if (embeddings.isEmpty()) throw new IOException("The embedding API returned no vector");
    return embeddings.getFirst();
  }

  List<double[]> embedTexts(List<String> inputs, String model) throws IOException, InterruptedException {
    List<double[]> result = new ArrayList<>();
    for (int start = 0; start < inputs.size(); start += EMBEDDING_BATCH_SIZE) {
      int end = Math.min(start + EMBEDDING_BATCH_SIZE, inputs.size());
      result.addAll(embedBatch(inputs.subList(start, end), model));
    }
    return List.copyOf(result);
  }

  private List<double[]> embedBatch(List<String> inputs, String model)
      throws IOException, InterruptedException {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("model", model);
    body.put("input", inputs.size() == 1 ? inputs.getFirst() : inputs);
    body.put("encoding_format", "float");

    HttpRequest request = request("/embeddings")
        .timeout(Duration.ofSeconds(30))
        .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(body), StandardCharsets.UTF_8))
        .build();
    HttpResponse<String> response = http.send(
        request,
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
    );
    ensureSuccess(response.statusCode(), response.body());

    Map<String, Object> payload = Json.object(Json.parse(response.body()));
    List<Object> data = Json.array(payload.get("data"));
    List<double[]> result = new ArrayList<>();
    for (int index = 0; index < data.size(); index++) result.add(null);
    for (Object item : data) {
      Map<String, Object> embeddingItem = Json.object(item);
      int index = (int) Json.number(embeddingItem.get("index"));
      List<Object> values = Json.array(embeddingItem.get("embedding"));
      double[] vector = new double[values.size()];
      for (int coordinate = 0; coordinate < values.size(); coordinate++) {
        vector[coordinate] = Json.number(values.get(coordinate));
      }
      if (index < 0 || index >= result.size()) throw new IOException("Invalid embedding index: " + index);
      result.set(index, vector);
    }
    if (result.stream().anyMatch(value -> value == null)) {
      throw new IOException("The embedding API returned an incomplete vector list");
    }
    return List.copyOf(result);
  }

  void streamAnswer(
      String instructions,
      String input,
      TextSink sink
  ) throws IOException, InterruptedException {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("model", config.chatModel());
    body.put("store", false);
    body.put("stream", true);
    body.put("stream_options", Map.of("include_obfuscation", false));
    body.put("max_output_tokens", 1_200);
    body.put("reasoning", Map.of("effort", "minimal"));
    body.put("instructions", instructions);
    body.put("input", input);

    HttpRequest request = request("/responses")
        .timeout(Duration.ofSeconds(40))
        .header("Accept", "text/event-stream")
        .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(body), StandardCharsets.UTF_8))
        .build();
    HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      try (InputStream inputStream = response.body()) {
        ensureSuccess(response.statusCode(), new String(inputStream.readAllBytes(), StandardCharsets.UTF_8));
      }
    }

    StreamState state = new StreamState();
    try (
        InputStream inputStream = response.body();
        BufferedReader reader = new BufferedReader(
            new InputStreamReader(inputStream, StandardCharsets.UTF_8)
        )
    ) {
      StringBuilder data = new StringBuilder();
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isEmpty()) {
          dispatchEvent(data.toString(), state, sink);
          data.setLength(0);
        } else if (line.startsWith("data:")) {
          if (!data.isEmpty()) data.append('\n');
          data.append(line.substring(5).stripLeading());
        }
      }
      if (!data.isEmpty()) dispatchEvent(data.toString(), state, sink);
    }

    if (!state.receivedText && state.completedText != null && !state.completedText.isBlank()) {
      sink.accept(state.completedText);
      state.receivedText = true;
    }
    if (!state.receivedText) throw new IOException("The model returned no answer text");
  }

  private void dispatchEvent(String data, StreamState state, TextSink sink) throws IOException {
    if (data.isBlank() || data.equals("[DONE]")) return;
    Map<String, Object> event;
    try {
      event = Json.object(Json.parse(data));
    } catch (RuntimeException error) {
      throw new IOException("Could not parse an OpenAI stream event", error);
    }

    String type = event.get("type") instanceof String value ? value : "";
    switch (type) {
      case "response.output_text.delta" -> {
        String delta = event.get("delta") instanceof String value ? value : "";
        if (!delta.isEmpty()) {
          state.receivedText = true;
          sink.accept(delta);
        }
      }
      case "response.output_text.done" -> {
        if (event.get("text") instanceof String text) state.completedText = text;
      }
      case "response.failed", "error" -> throw new IOException(extractError(event));
      default -> {
        // Other lifecycle events do not contain user-visible answer text.
      }
    }
  }

  private HttpRequest.Builder request(String path) {
    URI uri = URI.create(config.openAiBaseUrl().toString() + path);
    return HttpRequest.newBuilder(uri)
        .header("Authorization", "Bearer " + config.apiKey())
        .header("Content-Type", "application/json")
        .header("User-Agent", "local-rag-help-java/0.1.0");
  }

  private static void ensureSuccess(int status, String body) throws IOException {
    if (status >= 200 && status < 300) return;
    String detail = "OpenAI API returned HTTP " + status;
    try {
      Map<String, Object> root = Json.object(Json.parse(body));
      Object rawError = root.get("error");
      if (rawError instanceof Map<?, ?>) {
        Object message = Json.object(rawError).get("message");
        if (message instanceof String text && !text.isBlank()) detail += ": " + text;
      }
    } catch (RuntimeException ignored) {
      // Do not include an unstructured remote response in logs or client errors.
    }
    throw new IOException(detail);
  }

  private static String extractError(Map<String, Object> event) {
    Object rawError = event.get("error");
    if (rawError instanceof Map<?, ?>) {
      Object message = Json.object(rawError).get("message");
      if (message instanceof String text && !text.isBlank()) return text;
    }
    Object rawResponse = event.get("response");
    if (rawResponse instanceof Map<?, ?>) {
      Object responseError = Json.object(rawResponse).get("error");
      if (responseError instanceof Map<?, ?>) {
        Object message = Json.object(responseError).get("message");
        if (message instanceof String text && !text.isBlank()) return text;
      }
    }
    return "The OpenAI response stream failed";
  }

  private static final class StreamState {
    private boolean receivedText;
    private String completedText;
  }
}
