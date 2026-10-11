package com.example.ragbot;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Shared bounded summarization; callers retain their own memory/context rules. */
final class TextSummaries {
  private final OpenAiClient model;
  private final AgentProperties properties;
  TextSummaries(OpenAiClient model, AgentProperties properties) { this.model = model; this.properties = properties; }
  String summarize(String instructions, String input, int budget, RunControl control)
      throws IOException, InterruptedException {
    String text = model.summarize(instructions + "\nOutput at most " + (budget * 2)
        + " UTF-8 bytes. Output only the summary.", input,
        Math.min(properties.maxOutputTokens(), Math.max(2_048, budget + 2_048)), control).strip();
    if (text.isBlank() || TokenBudget.estimate(text) > budget) {
      throw new IOException("Summary was empty or exceeded its budget; original files preserved");
    }
    return text;
  }

  static List<String> chunks(String text, int budget) {
    List<String> result = new ArrayList<>();
    StringBuilder chunk = new StringBuilder();
    int size = 0;
    for (int offset = 0; offset < text.length();) {
      int cp = text.codePointAt(offset);
      String character = new String(Character.toChars(cp));
      int cost = TokenBudget.estimate(character);
      if (size + cost > budget && !chunk.isEmpty()) {
        result.add(chunk.toString());
        chunk.setLength(0);
        size = 0;
      }
      chunk.append(character);
      size += cost;
      offset += Character.charCount(cp);
    }
    if (!chunk.isEmpty()) result.add(chunk.toString());
    return result;
  }
}
