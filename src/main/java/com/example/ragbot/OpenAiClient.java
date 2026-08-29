package com.example.ragbot;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
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
import org.springframework.stereotype.Component;

@Component
public class OpenAiClient {
  private static final int EMBEDDING_BATCH_SIZE = 64;

  @FunctionalInterface
  public interface TextSink {
    void accept(String text) throws IOException;
  }

  private final RagProperties properties;
  private final ObjectMapper objectMapper;
  private final HttpClient http;
  private final String baseUrl;

  public OpenAiClient(RagProperties properties, ObjectMapper objectMapper) {
    if (properties.apiKey().isBlank()) {
      throw new IllegalStateException(
          "Missing OpenAI API key. Set api_key in config.yaml or OPENAI_API_KEY."
      );
    }
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
    this.baseUrl = stripTrailingSlash(properties.openaiBaseUrl().toString());
  }

  public double[] embed(String input, String model) throws IOException, InterruptedException {
    List<double[]> embeddings = embedTexts(List.of(input), model);
    if (embeddings.isEmpty()) throw new IOException("The embedding API returned no vector");
    return embeddings.getFirst();
  }

  public List<double[]> embedTexts(List<String> inputs, String model)
      throws IOException, InterruptedException {
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
        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
        .build();
    HttpResponse<String> response = http.send(
        request,
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
    );
    ensureSuccess(response.statusCode(), response.body());

    JsonNode data = objectMapper.readTree(response.body()).path("data");
    if (!data.isArray()) throw new IOException("The embedding API returned invalid data");
    List<double[]> result = new ArrayList<>();
    for (int index = 0; index < data.size(); index++) result.add(null);
    for (JsonNode item : data) {
      int index = item.path("index").asInt(-1);
      JsonNode values = item.path("embedding");
      if (index < 0 || index >= result.size() || !values.isArray()) {
        throw new IOException("The embedding API returned an invalid vector entry");
      }
      double[] vector = new double[values.size()];
      for (int coordinate = 0; coordinate < values.size(); coordinate++) {
        vector[coordinate] = values.get(coordinate).asDouble();
      }
      result.set(index, vector);
    }
    if (result.stream().anyMatch(value -> value == null)) {
      throw new IOException("The embedding API returned an incomplete vector list");
    }
    return List.copyOf(result);
  }

  public void streamAnswer(String instructions, String input, TextSink sink)
      throws IOException, InterruptedException {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("model", properties.chatModel());
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
        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
        .build();
    HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      try (InputStream inputStream = response.body()) {
        ensureSuccess(
            response.statusCode(),
            new String(inputStream.readAllBytes(), StandardCharsets.UTF_8)
        );
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
    JsonNode event;
    try {
      event = objectMapper.readTree(data);
    } catch (RuntimeException error) {
      throw new IOException("Could not parse an OpenAI stream event", error);
    }

    String type = event.path("type").asString("");
    switch (type) {
      case "response.output_text.delta" -> {
        String delta = event.path("delta").asString("");
        if (!delta.isEmpty()) {
          state.receivedText = true;
          sink.accept(delta);
        }
      }
      case "response.output_text.done" -> state.completedText = event.path("text").asString(null);
      case "response.failed", "error" -> throw new IOException(extractError(event));
      default -> {
        // Lifecycle events without user-visible answer text are intentionally ignored.
      }
    }
  }

  private HttpRequest.Builder request(String path) {
    return HttpRequest.newBuilder(URI.create(baseUrl + path))
        .header("Authorization", "Bearer " + properties.apiKey())
        .header("Content-Type", "application/json")
        .header("User-Agent", "spring-rag-help/0.3.0");
  }

  private void ensureSuccess(int status, String body) throws IOException {
    if (status >= 200 && status < 300) return;
    String detail = "OpenAI API returned HTTP " + status;
    try {
      String message = objectMapper.readTree(body).path("error").path("message").asString("");
      if (!message.isBlank()) detail += ": " + message;
    } catch (RuntimeException ignored) {
      // Do not include an unstructured remote response in logs or client errors.
    }
    throw new IOException(detail);
  }

  private static String extractError(JsonNode event) {
    String message = event.path("error").path("message").asString("");
    if (!message.isBlank()) return message;
    message = event.path("response").path("error").path("message").asString("");
    return message.isBlank() ? "The OpenAI response stream failed" : message;
  }

  private static String stripTrailingSlash(String value) {
    String result = value.trim();
    while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
    return result;
  }

  private static final class StreamState {
    private boolean receivedText;
    private String completedText;
  }
}
