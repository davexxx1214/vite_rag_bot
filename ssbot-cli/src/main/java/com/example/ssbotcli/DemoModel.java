package com.example.ssbotcli;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Offline fixture; all observations are explicitly labelled simulated. */
public final class DemoModel implements Model {
  private final ObjectMapper json;
  public DemoModel(ObjectMapper json) { this.json = json; }
  public Turn respond(String instructions, List<JsonNode> input, List<AgentTool> tools, boolean allowTools,
                      TextSink sink, RunControl control) throws IOException, InterruptedException {
    String text = "[DEMO - simulated, no API or host tools executed]\n"
        + "SSBot CLI is streaming a response. Use /help, /status, /tools or /new.\n"
        + "Production mode can query local Windows services with windows_service_status.\n";
    for (String token : text.split("(?<=\\s)")) {
      control.check(); Thread.sleep(35); sink.accept(token);
    }
    return new Turn(List.of(json.valueToTree(Map.of("type", "message", "role", "assistant",
        "content", List.of(Map.of("type", "output_text", "text", text))))), text, 0, 0);
  }
  public String summarize(String instructions, String input, int maxTokens, RunControl control) { return input; }
}
