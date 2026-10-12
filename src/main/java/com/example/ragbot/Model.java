package com.example.ragbot;

import java.io.IOException;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** Model boundary. Opaque output items (including reasoning) must survive tool round trips. */
public interface Model {
  @FunctionalInterface
  interface TextSink { void accept(String text) throws IOException; }

  record Turn(List<JsonNode> output, String text, int inputTokens, int outputTokens) {
    public List<JsonNode> calls() {
      return output.stream().filter(item -> item.path("type").asString("").equals("function_call")).toList();
    }
  }

  Turn respond(String instructions, List<JsonNode> input, List<AgentTool> tools,
      boolean allowTools, TextSink sink, RunControl control) throws IOException, InterruptedException;

  String summarize(String instructions, String input, int maxTokens, RunControl control)
      throws IOException, InterruptedException;
}
