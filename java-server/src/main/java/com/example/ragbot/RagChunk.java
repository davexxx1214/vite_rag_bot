package com.example.ragbot;

import java.util.LinkedHashMap;
import java.util.Map;

record RagChunk(
    String id,
    String slug,
    String title,
    String section,
    String source,
    String content,
    double[] embedding
) {
  Map<String, Object> toMap() {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", id);
    result.put("slug", slug);
    result.put("title", title);
    result.put("section", section);
    result.put("source", source);
    result.put("content", content);
    result.put("embedding", embedding);
    return result;
  }
}
