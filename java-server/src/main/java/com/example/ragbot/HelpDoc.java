package com.example.ragbot;

import java.util.LinkedHashMap;
import java.util.Map;

record HelpDoc(
    String slug,
    String title,
    String summary,
    String source,
    int order,
    String content
) {
  Map<String, Object> toMap() {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("slug", slug);
    result.put("title", title);
    result.put("summary", summary);
    result.put("source", source);
    result.put("order", order);
    result.put("content", content);
    return result;
  }
}
