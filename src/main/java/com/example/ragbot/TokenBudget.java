package com.example.ragbot;

import java.nio.charset.StandardCharsets;
import tools.jackson.databind.JsonNode;

/** Conservative UTF-8 estimate for English and CJK; provider usage is checked separately. */
final class TokenBudget {
  private TokenBudget() {}

  static int estimate(String text) {
    return text == null || text.isEmpty() ? 0 : (text.getBytes(StandardCharsets.UTF_8).length + 1) / 2;
  }

  static int estimate(JsonNode node) {
    if (node == null || node.isNull()) return 1;
    if (node.isObject()) {
      int count = 8;
      for (var field : node.properties()) {
        // Opaque reasoning is accounted for by API usage, not its base64 transport length.
        if (!field.getKey().equals("encrypted_content")) {
          count += estimate(field.getKey()) + estimate(field.getValue());
        }
      }
      return count;
    }
    if (node.isArray()) {
      int count = 4;
      for (JsonNode item : node) count += estimate(item);
      return count;
    }
    return estimate(node.isString() ? node.asString() : node.toString()) + 2;
  }
}
