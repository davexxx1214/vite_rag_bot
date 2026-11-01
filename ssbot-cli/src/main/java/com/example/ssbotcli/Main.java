package com.example.ssbotcli;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.completer.StringsCompleter;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class Main {
  private static final String HELP = "SSBot CLI\n"
      + "  ssbot-cli [--demo] [--config FILE] [--resume ID]\n"
      + "  ssbot-cli exec [--demo] [--json] [--config FILE] [--resume ID] PROMPT\n"
      + "  ssbot-cli services | discover | inspect SERVICE_ID [--json]\n"
      + "  --services FILE: select services.yaml (default: current directory)\n"
      + "  /help /new /status /tools /services /exit\n"
      + "  Tab: completion | Up/Down: input history | Ctrl+C or Esc: cancel task\n"
      + "  --json: NDJSON events on stdout; diagnostics on stderr; exit 130: cancelled\n"
      + "  Exec input may also be piped through stdin. Requires Java 21+.";

  public static void main(String[] args) { System.exit(run(args)); }
  static int run(String[] args) {
    PrintWriter stdout = new PrintWriter(System.out, true, StandardCharsets.UTF_8);
    PrintWriter stderr = new PrintWriter(System.err, true, StandardCharsets.UTF_8);
    ObjectMapper json = new ObjectMapper();
    AtomicReference<RunControl> active = new AtomicReference<>();
    Thread hook = new Thread(() -> { RunControl c = active.get(); if (c != null) c.cancel(); });
    boolean ndjson = List.of(args).contains("--json");
    try {
      List<String> tokens = new ArrayList<>(List.of(args));
      if (tokens.remove("--help") || tokens.remove("-h")) { stdout.println(HELP); return 0; }
      boolean demo = tokens.remove("--demo");
      tokens.remove("--json");
      Path configFile = Path.of(option(tokens, "--config", "config.properties"));
      boolean explicitServices = tokens.contains("--services");
      ServiceCatalog catalog = ServiceCatalog.load(Path.of(option(tokens, "--services", "services.yaml")), explicitServices);
      WindowsToolPack pack = new WindowsToolPack(catalog, json);
      List<AgentTool> tools = new ArrayList<>(pack.tools());
      tools.add(new WindowsServiceTool(json));
      String resume = option(tokens, "--resume", "");
      String localCommand = !tokens.isEmpty() && List.of("services", "discover", "inspect").contains(tokens.getFirst()) ? tokens.removeFirst() : "";
      if (!localCommand.isEmpty()) {
        if (!resume.isEmpty() || demo) throw new IllegalArgumentException("Local commands use real read-only queries; omit --demo and --resume.");
        String toolName = switch (localCommand) { case "services" -> "list_services"; case "discover" -> "discover_windows_services"; default -> "inspect_service"; };
        if (tokens.size() != (localCommand.equals("inspect") ? 1 : 0)) throw new IllegalArgumentException("Use --help for local command syntax.");
        JsonNode toolArgs = json.valueToTree(localCommand.equals("inspect") ? Map.of("service_id", tokens.getFirst()) : Map.of());
        RunControl control = new RunControl(90); active.set(control);
        Runtime.getRuntime().addShutdownHook(hook);
        JsonNode result = tools.stream().filter(t -> t.name().equals(toolName)).findFirst().orElseThrow().execute(toolArgs, control);
        stdout.println(ndjson ? json.writeValueAsString(Map.of("type", "tool_result", "data", Map.of("name", toolName, "result", result)))
            : json.writerWithDefaultPrettyPrinter().writeValueAsString(result));
        return result.has("error") ? 1 : 0;
      }
      boolean exec = !tokens.isEmpty() && tokens.getFirst().equals("exec");
      if (exec) tokens.removeFirst();
      if (tokens.stream().anyMatch(t -> t.startsWith("--"))) throw new IllegalArgumentException("Unknown option. Use --help.");
      if (!exec && (!tokens.isEmpty() || ndjson)) throw new IllegalArgumentException("Prompts and --json require exec mode.");
      Config config = Config.load(configFile, System.getenv());
      Model model = demo ? new DemoModel(json) : new OpenAiClient(config, json);
      AgentEngine engine = new AgentEngine(model, tools, config, json);
      String local = System.getenv().getOrDefault("LOCALAPPDATA", Path.of(System.getProperty("user.home"), ".local", "share").toString());
      Path sessions = Path.of(local, "SSBotCli", demo ? "demo-sessions" : "sessions");
      SessionStore store = new SessionStore(sessions, json);
      String id = resume.isEmpty() ? store.newId() : resume;
      List<JsonNode> history = resume.isEmpty() ? List.of() : store.load(id);
      Runtime.getRuntime().addShutdownHook(hook);
      if (exec) {
        String prompt = String.join(" ", tokens);
        if (prompt.isBlank() && System.console() == null) prompt = new String(System.in.readAllBytes(), StandardCharsets.UTF_8).strip();
        if (prompt.isBlank()) throw new IllegalArgumentException("Provide an exec prompt or pipe stdin.");
        RunControl control = new RunControl(config.timeoutSeconds());
        active.set(control);
        AgentListener listener = ndjson ? (type, data) -> {
          stdout.println(json.writeValueAsString(Map.of("type", type, "data", data)));
        } : textListener(stdout, stderr);
        List<JsonNode> completed = engine.run(prompt, history, listener, control);
        store.save(id, completed);
        if (!ndjson) { stdout.println(); stderr.println("Session: " + id); }
        else stdout.println(json.writeValueAsString(Map.of("type", "session", "data", Map.of("id", id))));
        return 0;
      }
      try (Terminal terminal = TerminalBuilder.builder().system(true).nativeSignals(true).encoding(StandardCharsets.UTF_8).dumb(true).build()) {
        interactive(terminal, engine, config, demo, store, id, history, active, catalog, pack);
      }
      return 0;
    } catch (Exception e) {
      boolean cancelled = active.get() != null && active.get().cancelled();
      String message = cancelled ? "Task cancelled." : e instanceof ModelApiException api ? api.publicMessage()
          : e instanceof IOException || e instanceof IllegalArgumentException || e instanceof IllegalStateException
          ? e.getMessage() : e.getClass().getSimpleName();
      if (ndjson) stdout.println(json.writeValueAsString(Map.of("type", "error", "data", Map.of("message", clean(message)))));
      stderr.println("SSBot CLI: " + clean(message));
      return cancelled ? 130 : 1;
    } finally {
      active.set(null);
      try { Runtime.getRuntime().removeShutdownHook(hook); } catch (IllegalStateException ignored) { }
    }
  }

  private static void interactive(Terminal terminal, AgentEngine engine, Config config, boolean demo,
      SessionStore store, String initialId, List<JsonNode> initialHistory, AtomicReference<RunControl> active,
      ServiceCatalog catalog, WindowsToolPack pack) throws Exception {
    PrintWriter out = terminal.writer();
    List<String> completions = new ArrayList<>(List.of("/help", "/new", "/status", "/tools", "/services", "/exit"));
    catalog.services().forEach(s -> completions.add("@" + s.id()));
    LineReader reader = LineReaderBuilder.builder().terminal(terminal).completer(new StringsCompleter(completions)).build();
    String id = initialId;
    List<JsonNode> history = initialHistory;
    out.println("SSBot CLI | " + (demo ? "DEMO (simulated)" : clean(config.chatModel())) + " | read-only | /help");
    out.println("Session: " + id); out.flush();
    out.println("Registered services: " + catalog.services().size() + " | " + clean(catalog.source().toString())); out.flush();
    try (var worker = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      while (true) {
        String prompt;
        try { prompt = reader.readLine("ssbot> ").strip(); }
        catch (UserInterruptException ignored) { out.println("Use /exit or Ctrl+D to exit."); continue; }
        catch (EndOfFileException ignored) { break; }
        if (prompt.isEmpty()) continue;
        if (prompt.equals("/exit")) break;
        if (prompt.equals("/help")) { out.println(HELP); out.flush(); continue; }
        if (prompt.equals("/services")) {
          if (catalog.services().isEmpty()) out.println("No registered services. Configure services.yaml or --services FILE, then restart.");
          for (var s : pack.summaries()) out.println(clean(s.get("id") + " | " + s.get("type") + " | "
              + s.get("service_name") + s.get("site_name") + " | " + s.get("install_dir")));
          out.flush(); continue;
        }
        if (prompt.equals("/new")) { id = store.newId(); history = List.of(); out.println("Session: " + id); out.flush(); continue; }
        if (prompt.equals("/status")) { out.println("Model: " + clean(config.chatModel()) + " | mode: " + (demo ? "demo" : "live")
            + " | read-only | items: " + history.size() + " | session: " + id
            + " | terminal: " + terminal.getType() + " (" + terminal.getClass().getSimpleName() + ")"); out.flush(); continue; }
        if (prompt.equals("/tools")) { engine.tools().forEach(t -> out.println(t.name() + " - " + t.description())); out.flush(); continue; }
        if (prompt.startsWith("/")) { out.println("Unknown command. Use /help."); out.flush(); continue; }
        RunControl control = new RunControl(config.timeoutSeconds());
        active.set(control);
        Attributes before = terminal.enterRawMode();
        Attributes running = terminal.getAttributes();
        running.setLocalFlag(Attributes.LocalFlag.ISIG, false);
        terminal.setAttributes(running);
        Terminal.SignalHandler previous = terminal.handle(Terminal.Signal.INT, signal -> control.cancel());
        var turnHistory = history;
        var task = worker.submit(() -> engine.run(prompt, turnHistory, textListener(out, out), control));
        try {
          while (!task.isDone()) {
            int key = terminal.reader().read(100);
            if (key == 3 || key == 27 || key == 4) control.cancel();
          }
          List<JsonNode> completed = task.get();
          store.save(id, completed);
          history = completed;
        } catch (ExecutionException e) {
          Throwable cause = e.getCause();
          out.println("\n" + (control.cancelled() ? "Task cancelled." : "Error: " + clean(cause.getMessage())));
        } catch (IOException e) {
          out.println("\nSession could not be saved: " + clean(e.getMessage()));
        } finally {
          control.cancel();
          terminal.setAttributes(before); terminal.handle(Terminal.Signal.INT, previous);
          active.set(null); out.println(); out.flush();
        }
      }
    }
  }

  private static AgentListener textListener(PrintWriter out, PrintWriter status) {
    return (type, data) -> {
      switch (type) {
        case "delta" -> out.print(clean(data.get("text").toString()).replace("\n", System.lineSeparator()));
        case "round" -> status.println("\n[thinking: round " + data.get("round") + "]");
        case "tool_start" -> status.println("\n[tool: " + clean(data.get("name").toString()) + "]");
        case "tool_result" -> status.println("[tool " + (((JsonNode) data.get("result")).has("error") ? "failed: " : "complete: ")
            + clean(data.get("name").toString()) + "]");
        default -> { }
      }
      out.flush(); status.flush();
    };
  }

  // Model and host data must not inject terminal escape sequences.
  static String clean(String value) {
    if (value == null) return "Unknown error";
    return value.replaceAll("[\\p{Cc}\\p{Cf}&&[^\\n\\t]]", "");
  }
  private static String option(List<String> args, String name, String fallback) {
    int index = args.indexOf(name);
    if (index < 0) return fallback;
    if (index + 1 == args.size() || args.get(index + 1).startsWith("--")) throw new IllegalArgumentException("Missing value for " + name);
    args.remove(index); return args.remove(index);
  }
}
