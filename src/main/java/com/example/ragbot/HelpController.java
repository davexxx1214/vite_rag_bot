package com.example.ragbot;

import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.OutputStream;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api")
@ConditionalOnProperty(name = "rag.build-index", havingValue = "false", matchIfMissing = true)
public class HelpController {
  private static final Logger LOGGER = LoggerFactory.getLogger(HelpController.class);
  private final HelpDocumentService helpDocuments;
  private final RagIndex ragIndex;
  private final RagProperties properties;
  private final RagChatService chatService;
  private final ObjectMapper objectMapper;

  public HelpController(
      HelpDocumentService helpDocuments,
      RagIndex ragIndex,
      RagProperties properties,
      RagChatService chatService,
      ObjectMapper objectMapper
  ) {
    this.helpDocuments = helpDocuments;
    this.ragIndex = ragIndex;
    this.properties = properties;
    this.chatService = chatService;
    this.objectMapper = objectMapper;
  }

  @GetMapping("/health")
  public Map<String, Object> health() {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("ok", true);
    payload.put("runtime", "java-" + Runtime.version().feature());
    payload.put("framework", "spring-boot-" + SpringBootVersion.getVersion());
    payload.put("documents", helpDocuments.documents().size());
    payload.put("chunks", ragIndex.chunks().size());
    payload.put("embeddingModel", ragIndex.embeddingModel());
    payload.put("chatModel", properties.chatModel());
    return payload;
  }

  @GetMapping("/docs")
  public List<HelpDoc> docs() {
    return helpDocuments.documents();
  }

  @PostMapping(value = "/chat", produces = MediaType.APPLICATION_NDJSON_VALUE)
  public ResponseEntity<StreamingResponseBody> chat(@RequestBody ChatRequest request) {
    String message = normalizeMessage(request == null ? null : request.message());
    List<ChatMessage> history = normalizeHistory(request == null ? null : request.history());
    StreamingResponseBody stream = output -> streamChat(output, message, history);

    HttpHeaders headers = new HttpHeaders();
    headers.setCacheControl(CacheControl.noCache());
    headers.set("X-Accel-Buffering", "no");
    headers.set("X-Content-Type-Options", "nosniff");
    return ResponseEntity.ok().headers(headers).body(stream);
  }

  private void streamChat(OutputStream output, String message, List<ChatMessage> history) {
    long startedAt = System.currentTimeMillis();
    NdjsonWriter writer = new NdjsonWriter(output, objectMapper);
    try {
      writer.write(Map.of("type", "status", "message", "Searching the help documents…"));
      RagRetrieval retrieval = chatService.retrieve(message);
      writer.write(Map.of("type", "sources", "sources", retrieval.sources()));
      writer.write(Map.of("type", "status", "message", "Generating an answer…"));
      chatService.streamAnswer(
          retrieval,
          history,
          message,
          delta -> writer.write(Map.of("type", "delta", "text", delta))
      );
      writer.write(Map.of("type", "done"));
      LOGGER.info("Chat response completed in {}ms", System.currentTimeMillis() - startedAt);
    } catch (Exception error) {
      String publicMessage = hasCause(error, HttpTimeoutException.class)
          ? "The AI service timed out. Please try again."
          : "The AI request failed. Please try again or check the server configuration.";
      LOGGER.error(
          "Chat request failed after {}ms: {}",
          System.currentTimeMillis() - startedAt,
          error.getMessage()
      );
      try {
        writer.write(Map.of("type", "error", "error", publicMessage));
      } catch (IOException ignored) {
        LOGGER.debug("The client disconnected before the chat error could be written");
      }
    }
  }

  private static String normalizeMessage(String rawMessage) {
    String message = rawMessage == null ? "" : rawMessage.trim();
    if (message.isEmpty()) throw new ApiRequestException(HttpStatus.BAD_REQUEST, "Message is required");
    if (message.length() > 2_000) {
      throw new ApiRequestException(
          HttpStatus.BAD_REQUEST,
          "Message must be 2,000 characters or fewer"
      );
    }
    return message;
  }

  private static List<ChatMessage> normalizeHistory(List<ChatMessage> rawHistory) {
    if (rawHistory == null || rawHistory.isEmpty()) return List.of();
    List<ChatMessage> result = new ArrayList<>();
    int start = Math.max(0, rawHistory.size() - 6);
    for (int index = start; index < rawHistory.size(); index++) {
      ChatMessage item = rawHistory.get(index);
      if (item == null || item.role() == null || item.content() == null) continue;
      if (!item.role().equals("user") && !item.role().equals("assistant")) continue;
      result.add(new ChatMessage(
          item.role(),
          item.content().substring(0, Math.min(item.content().length(), 2_000))
      ));
    }
    return List.copyOf(result);
  }

  private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
    for (Throwable current = error; current != null; current = current.getCause()) {
      if (type.isInstance(current)) return true;
    }
    return false;
  }

  public record ChatRequest(String message, List<ChatMessage> history) {}

  private static final class NdjsonWriter {
    private final OutputStream output;
    private final ObjectMapper objectMapper;

    private NdjsonWriter(OutputStream output, ObjectMapper objectMapper) {
      this.output = output;
      this.objectMapper = objectMapper;
    }

    private void write(Object event) throws IOException {
      output.write(objectMapper.writeValueAsBytes(event));
      output.write('\n');
      output.flush();
    }
  }
}
