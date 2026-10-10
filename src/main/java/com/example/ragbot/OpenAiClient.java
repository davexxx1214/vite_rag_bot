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
  private final AgentProperties agentProperties;

  public OpenAiClient(RagProperties properties, AgentProperties agentProperties, ObjectMapper objectMapper) {
    if (properties.apiKey().isBlank()) {
      throw new IllegalStateException(
          "Missing OpenAI API key. Set api_key in config.yaml or OPENAI_API_KEY."
      );
    }
    this.properties = properties;
    this.agentProperties = agentProperties;
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

  public record ModelResponse(List<JsonNode> output, String text, int inputTokens, int outputTokens) {
    public List<JsonNode> calls() {
      return output.stream().filter(item -> item.path("type").asString("").equals("function_call")).toList();
    }
  }

  public ModelResponse respond(
      String instructions, List<JsonNode> input, List<Map<String, Object>> tools,
      boolean allowTools, TextSink sink, RunControl control
  ) throws IOException, InterruptedException {
    Map<String, Object> body = responseBody(instructions, input, agentProperties.maxOutputTokens());
    body.put("tools", tools);
    body.put("tool_choice", allowTools ? "auto" : "none");
    body.put("parallel_tool_calls", false);
    body.put("stream", true);
    body.put("stream_options", Map.of("include_obfuscation", false));

    HttpRequest request = request("/responses")
        .timeout(control.timeout())
        .header("Accept", "text/event-stream")
        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
        .build();
    HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
    try (InputStream inputStream = response.body()) {
      control.attach(inputStream);
      try {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
          ensureSuccess(response.statusCode(), new String(inputStream.readAllBytes(), StandardCharsets.UTF_8));
        }
        StreamState state = new StreamState();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
          StringBuilder data = new StringBuilder();
          String line;
          while ((line = reader.readLine()) != null) {
            control.check();
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
        if (state.completed == null) throw new IOException("OpenAI stream ended without a completed response");
        ModelResponse result = parseResponse(state.completed);
        if (!state.receivedText && !result.text().isBlank()) sink.accept(result.text());
        return result;
      } finally {
        control.detach();
      }
    }
  }

  public String summarize(String instructions, String input, int maxTokens, RunControl control)
      throws IOException, InterruptedException {
    Map<String, Object> body = responseBody(instructions,
        List.of(objectMapper.valueToTree(Map.of("role", "user", "content", input))), maxTokens);
    body.put("stream", false);
    HttpRequest request = request("/responses").timeout(control.timeout())
        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body))).build();
    HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    ensureSuccess(response.statusCode(), response.body());
    return parseResponse(objectMapper.readTree(response.body())).text();
  }

  private Map<String, Object> responseBody(String instructions, List<JsonNode> input, int maxTokens) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("model", properties.chatModel());
    body.put("store", false);
    body.put("max_output_tokens", maxTokens);
    body.put("reasoning", Map.of("effort", agentProperties.reasoningEffort()));
    body.put("include", List.of("reasoning.encrypted_content"));
    body.put("instructions", instructions);
    body.put("input", input);

    return body;
  }

  private ModelResponse parseResponse(JsonNode response) throws IOException {
    if (!response.path("status").asString("").equals("completed") || !response.path("output").isArray()) {
      throw new IOException("The model response was incomplete or invalid");
    }
    List<JsonNode> output = new ArrayList<>();
    StringBuilder text = new StringBuilder();
    for (JsonNode item : response.path("output")) {
      output.add(item.deepCopy());
      if (item.path("type").asString("").equals("message")) {
        for (JsonNode part : item.path("content")) {
          String type = part.path("type").asString("");
          if (type.equals("output_text")) text.append(part.path("text").asString(""));
          if (type.equals("refusal")) text.append(part.path("refusal").asString(""));
        }
      }
    }
    JsonNode usage = response.path("usage");
    return new ModelResponse(List.copyOf(output), text.toString(),
        usage.path("input_tokens").asInt(0), usage.path("output_tokens").asInt(0));
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
      case "response.completed" -> state.completed = event.path("response");
      case "response.incomplete" -> throw new IOException("The model exhausted its output budget");
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
    String code = "";
    String parameter = "";
    try {
      JsonNode error = objectMapper.readTree(body).path("error");
      code = error.path("code").asString(error.path("type").asString(""));
      parameter = error.path("param").asString("");
    } catch (RuntimeException ignored) {
      // Raw remote bodies may contain credentials or request contents; never expose them.
    }
    throw new ModelApiException(status, code, parameter);
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
    private JsonNode completed;
  }
}
