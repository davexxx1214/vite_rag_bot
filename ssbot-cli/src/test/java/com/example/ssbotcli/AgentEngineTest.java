package com.example.ssbotcli;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class AgentEngineTest {
  final ObjectMapper json = new ObjectMapper();
  final Config config = new Config("test", "test", URI.create("http://localhost"), "low", 30, 3, 2);
  @TempDir Path temp;

  abstract static class FakeModel implements Model {
    public String summarize(String i, String input, int max, RunControl c) { return ""; }
  }
  JsonNode item(Map<String, Object> data) { return json.valueToTree(data); }
  Model.Turn answer() {
    return new Model.Turn(List.of(item(Map.of("role", "assistant", "content", "ok"))), "ok", 0, 0);
  }
  AgentTool tool(AtomicInteger executions) {
    return new AgentTool() {
      public String name() { return "test"; }
      public String description() { return "test"; }
      public Map<String, Object> parameters() { return Map.of(); }
      public JsonNode execute(JsonNode a, RunControl c) { executions.incrementAndGet(); return item(Map.of("ok", true)); }
    };
  }

  @Test void preservesOpaqueReasoningAndCallIdentityAcrossToolRounds() throws Exception {
    AtomicInteger rounds = new AtomicInteger(), executions = new AtomicInteger();
    JsonNode reasoning = item(Map.of("type", "reasoning", "encrypted_content", "opaque"));
    Model model = new FakeModel() {
      public Turn respond(String i, List<JsonNode> input, List<AgentTool> t, boolean allow, TextSink s, RunControl c) {
        if (rounds.getAndIncrement() == 0) return new Turn(List.of(reasoning,
            item(Map.of("type", "function_call", "call_id", "call-42", "name", "test", "arguments", "{}"))), "", 0, 0);
        assertEquals(reasoning, input.get(1));
        assertEquals("call-42", input.get(3).path("call_id").asString());
        assertEquals("function_call_output", input.get(3).path("type").asString());
        return answer();
      }
    };
    var result = new AgentEngine(model, List.of(tool(executions)), config, json)
        .run("hello", List.of(), (t, d) -> {}, new RunControl(30));
    assertEquals(1, executions.get()); assertEquals(5, result.size());
  }

  @Test void callBudgetStopsExecutionsAndForcesFinalAnswer() throws Exception {
    AtomicInteger executions = new AtomicInteger();
    Model model = new FakeModel() {
      public Turn respond(String i, List<JsonNode> input, List<AgentTool> t, boolean allow, TextSink s, RunControl c) {
        if (!allow) return answer();
        return new Turn(java.util.stream.IntStream.range(0, 4).mapToObj(n -> item(Map.of("type", "function_call",
            "call_id", "id-" + n, "name", "test", "arguments", "{}"))).toList(), "", 0, 0);
      }
    };
    var result = new AgentEngine(model, List.of(tool(executions)), config, json)
        .run("hello", List.of(), (t, d) -> {}, new RunControl(30));
    assertEquals(2, executions.get());
    assertEquals(4, result.stream().filter(n -> n.path("type").asString("").equals("function_call_output")).count());
  }

  @Test void cancelledRunDoesNotCommitPartialHistory() throws Exception {
    SessionStore store = new SessionStore(temp, json);
    String id = store.newId();
    var history = List.of(item(Map.of("role", "assistant", "content", "earlier")));
    store.save(id, history);
    RunControl control = new RunControl(30);
    Model model = new FakeModel() {
      public Turn respond(String i, List<JsonNode> input, List<AgentTool> t, boolean a, TextSink s, RunControl c) throws IOException {
        c.cancel(); c.check(); return answer();
      }
    };
    assertThrows(IOException.class, () -> new AgentEngine(model, List.of(), config, json)
        .run("hello", history, (t, d) -> {}, control));
    assertEquals(history, store.load(id));
    assertThrows(IllegalArgumentException.class, () -> store.load("../secret"));
  }

  @Test void validatesServiceArgumentsBeforeStartingAnyProcess() {
    var tool = new WindowsServiceTool(json);
    for (String value : List.of("x'; Stop-Service X; #", "$()", "x*"))
      assertThrows(IllegalArgumentException.class, () -> tool.execute(item(Map.of("name", value)), new RunControl(5)));
    assertThrows(IllegalArgumentException.class, () -> tool.execute(item(Map.of("name", "Spooler", "extra", true)), new RunControl(5)));
  }

  @Test void readsRealWindowsEventLogServiceWithoutModifyingIt() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
    JsonNode result = new WindowsServiceTool(json).execute(item(Map.of("name", "EventLog")), new RunControl(20));
    JsonNode service = result.isArray() ? result.get(0) : result;
    assertEquals("EventLog", service.path("Name").asString(), result.toString());
    assertFalse(service.path("State").asString("").isBlank());
  }

  @Test void environmentOverridesConfigAndRejectsInsecureRemoteEndpoints() throws Exception {
    var file = temp.resolve("config.properties");
    java.nio.file.Files.writeString(file, "chat_model=file-model\napi_key=local\n");
    Config c = Config.load(file, Map.of("OPENAI_MODEL", "env-model", "OPENAI_API_KEY", "env-key"));
    assertEquals("env-model", c.chatModel()); assertEquals("env-key", c.apiKey());
    assertThrows(IllegalArgumentException.class, () -> Config.load(file, Map.of("OPENAI_BASE_URL", "http://remote.example/v1")));
  }

  @Test void stripsTerminalControlsButKeepsReadableChinese() {
    assertEquals("中文[31m\n", Main.clean("中文\u001b[31m\r\n"));
  }
}
