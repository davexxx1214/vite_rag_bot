package com.example.ragbot;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Safe bounded query descriptions shared by progress and partial-answer notices. */
final class ToolLabels {
  private ToolLabels() {}

  static String detail(ObjectMapper mapper, String name, String arguments) {
    if (arguments.length() > 8_000) return "";
    try {
      JsonNode args = mapper.readTree(arguments);
      List<String> details = new ArrayList<>();
      for (String key : List.of("query", "id", "leftId", "rightId", "key", "aroundTurn")) {
        JsonNode value = args.path(key);
        if (value.isString() || value.isNumber()) {
          String text = value.asString("").replaceAll("[\\p{Cntrl}\\s]+", " ").strip();
          if (!text.isBlank()) details.add(key + ": " + (text.length() > 160 ? text.substring(0, 160) + "…" : text));
        }
      }
      if (name.equals("search_history") && args.hasNonNull("payloadRef")) return "读取历史数据片段";
      return String.join(" · ", details);
    } catch (RuntimeException error) { return ""; }
  }
}
