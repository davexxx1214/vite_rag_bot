package com.example.ragbot;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.net.http.HttpTimeoutException;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api")
@ConditionalOnProperty(name = "rag.build-index", havingValue = "false", matchIfMissing = true)
public class HelpController {
  private static final Logger LOGGER = LoggerFactory.getLogger(HelpController.class);
  private final HelpDocumentService helpDocuments;
  private final RagIndex ragIndex;
  private final RagProperties properties;
  private final AgentService agent;
  private final MemoryStore store;
  private final ObjectMapper objectMapper;

  public HelpController(HelpDocumentService helpDocuments, RagIndex ragIndex, RagProperties properties,
      AgentService agent, MemoryStore store, ObjectMapper objectMapper) {
    this.helpDocuments = helpDocuments;
    this.ragIndex = ragIndex;
    this.properties = properties;
    this.agent = agent;
    this.store = store;
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
    payload.put("agent", true);
    return payload;
  }

  @GetMapping("/docs")
  public List<HelpDoc> docs() {
    return helpDocuments.documents();
  }

  @PostMapping(value = "/chat", produces = MediaType.APPLICATION_NDJSON_VALUE)
  public ResponseEntity<StreamingResponseBody> chat(
      @RequestHeader("X-Mock-User-Id") String userId, @RequestBody ChatRequest request,
      HttpServletResponse servletResponse
  ) throws IOException {
    String message = request == null || request.message() == null ? "" : request.message().trim();
    if (message.isEmpty() || message.length() > 2_000) {
      throw new ApiRequestException(HttpStatus.BAD_REQUEST, "Message must contain 1-2,000 characters");
    }
    String sessionId = request.sessionId();
    store.requireExistingSession(userId, sessionId);
    StreamingResponseBody stream = output -> streamChat(output, servletResponse, userId, sessionId, message);
    HttpHeaders headers = new HttpHeaders();
    headers.setCacheControl(CacheControl.noStore());
    headers.set("X-Accel-Buffering", "no");
    headers.set("X-Content-Type-Options", "nosniff");
    return ResponseEntity.ok().headers(headers).body(stream);
  }

  private void streamChat(OutputStream output, HttpServletResponse response, String userId, String sessionId, String message) {
    streamChat(output, response, userId, sessionId, message, null);
  }

  @PostMapping(value = "/agent/sessions/{sessionId}/input-requests/{requestId}/answers", produces = MediaType.APPLICATION_NDJSON_VALUE)
  public ResponseEntity<StreamingResponseBody> answer(@RequestHeader("X-Mock-User-Id") String userId,
      @PathVariable String sessionId, @PathVariable String requestId, @RequestBody InputRequestAnswer answer,
      HttpServletResponse response) throws IOException {
    store.requireExistingSession(userId, sessionId);
    boolean cancelled = Boolean.TRUE.equals(answer.cancelled());
    var input = new EditingWorkflow.InputAnswer(requestId, answer.optionId(), answer.text(), cancelled);
    String message = cancelled ? "取消本次修改" : answer.text() != null ? answer.text() : "选择：" + answer.optionId();
    StreamingResponseBody stream = output -> streamChat(output, response, userId, sessionId, message, input);
    return ResponseEntity.ok().header("Cache-Control", "no-store").header("X-Accel-Buffering", "no")
        .header("X-Content-Type-Options", "nosniff").body(stream);
  }

  private void streamChat(OutputStream output, HttpServletResponse response, String userId, String sessionId, String message,
      EditingWorkflow.InputAnswer inputAnswer) {
    long startedAt = System.currentTimeMillis();
    try {
      agent.run(userId, sessionId, message, inputAnswer, event -> write(output, response, event));
      LOGGER.info("Agent request completed in {}ms", System.currentTimeMillis() - startedAt);
    } catch (Exception error) {
      String publicMessage = failureMessage(error);
      LOGGER.warn("Agent request failed after {}ms ({}): {}", System.currentTimeMillis() - startedAt,
          error.getClass().getSimpleName(), publicMessage);
      try {
        write(output, response, Map.of("type", "error", "error", publicMessage));
      } catch (IOException ignored) {
        LOGGER.debug("The client disconnected before the error could be delivered");
      }
    }
  }

  static String failureMessage(Exception error) {
    if (error instanceof ApiRequestException requestError) return requestError.getMessage();
    if (error instanceof ModelApiException modelError) return modelError.publicMessage();
    if (error instanceof HttpTimeoutException || "Agent request timed out".equals(error.getMessage())) {
      return "The model request timed out. Please retry or check the server's connection to the model API.";
    }
    if (error instanceof InterruptedException || "Agent request cancelled".equals(error.getMessage())) {
      return "The request was cancelled.";
    }
    return "The agent could not complete the request. Please retry or check the server configuration.";
  }

  private void write(OutputStream output, HttpServletResponse response, Map<String, Object> event) throws IOException {
    output.write(objectMapper.writeValueAsBytes(event));
    output.write('\n');
    output.flush();
    // Spring 7 may hand StreamingResponseBody a non-flushing stream. Commit each NDJSON event.
    response.flushBuffer();
  }

  public record ChatRequest(String sessionId, String message) {}
  public record InputRequestAnswer(String optionId, String text, Boolean cancelled) {}
}
