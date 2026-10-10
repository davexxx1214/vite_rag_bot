package com.example.ragbot;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class AgentTestSupport {
  private AgentTestSupport() {}

  static AgentProperties properties(Path root) {
    return new AgentProperties(root, null, 32_000, 25_000, 12_000, 6_000, 1_800, 8_000,
        0, 6, 8, 3_000, 120, 3, "low");
  }

  static RagProperties rag() {
    return new RagProperties("test-key", "gpt-6-luna", "test-embedding", URI.create("http://127.0.0.1:1/v1"),
        Path.of("docs"), Path.of("data/rag-index.json"), false);
  }

  static JsonNode answer(ObjectMapper mapper, String text) {
    return mapper.valueToTree(Map.of("type", "message", "role", "assistant", "content",
        List.of(Map.of("type", "output_text", "text", text))));
  }

  static MemoryStore.StoredTurn turn(ObjectMapper mapper, int index, String question, String answer) {
    return new MemoryStore.StoredTurn(index, "2026-10-10T12:00:00Z", question,
        List.of(answer(mapper, answer)), answer, List.of(), true, "gpt-6-luna");
  }
}
