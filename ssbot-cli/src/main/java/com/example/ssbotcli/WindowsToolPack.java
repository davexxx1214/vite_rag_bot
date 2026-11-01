package com.example.ssbotcli;

import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class WindowsToolPack {
  private final ServiceCatalog catalog;
  private final ObjectMapper json;
  private final PowerShellRunner shell;
  public WindowsToolPack(ServiceCatalog catalog, ObjectMapper json) { this(catalog, json, new PowerShellRunner(json)); }
  WindowsToolPack(ServiceCatalog catalog, ObjectMapper json, PowerShellRunner shell) {
    this.catalog = catalog; this.json = json; this.shell = shell;
  }
  @FunctionalInterface private interface Handler { Object run(JsonNode args, RunControl control) throws Exception; }
  private static Map<String, Object> string(String description) { return Map.of("type", "string", "description", description); }
  private static Map<String, Object> integer(int min, int max) { return Map.of("type", "integer", "minimum", min, "maximum", max); }
  public List<AgentTool> tools() {
    Map<String, Object> id = Map.of("service_id", string("Registered service ID from list_services; @id is also accepted"));
    return List.of(
        tool("list_services", "List registered local deployments, service IDs, paths and log indexes from services.yaml.", Map.of(), (a,c) -> summaries()),
        tool("discover_windows_services", "Discover up to 200 Windows services on this host with executable paths. Does not change services.", Map.of(),
            (a,c) -> shell.run(HostScripts.DISCOVER, Map.of(), c)),
        tool("inspect_service", "Inspect a registered Windows service or IIS site, process, expected TCP listeners and deployment directory metadata.", id,
            (a,c) -> inspect(service(a), c)),
        tool("read_service_logs", "Tail the newest matching registered log file at log_index. Bounded to 64 KiB and requested lines; common credentials redacted.",
            Map.of("service_id", id.get("service_id"), "log_index", integer(0,31), "lines", integer(1,200),
                "encoding", Map.of("type", "string", "enum", List.of("utf-8", "gb18030", "utf-16le"))),
            (a,c) -> {
              var s = service(a); int index = a.path("log_index").asInt();
              if (index >= s.logs().size()) return Map.of("error", "Log index not configured; use list_services", "code", "invalid_log_index");
              return DiagnosticFiles.tail(s.logs().get(index), a.path("lines").asInt(), a.path("encoding").asString(), c);
            }),
        tool("read_service_events", "Read recent System/Application warnings and errors filtered by registered providers. These are candidate events, not proven service causes.",
            Map.of("service_id", id.get("service_id"), "minutes", integer(1,1440), "limit", integer(1,50)),
            (a,c) -> shell.run(HostScripts.EVENTS, Map.of("providers", service(a).eventProviders(), "minutes", a.path("minutes").asInt(), "limit", a.path("limit").asInt()), c)),
        tool("check_service_endpoints", "Check only registered HTTP(S) health and TCP/HTTP dependencies. No redirects, credentials or response bodies; endpoint failures reported individually.", id,
            (a,c) -> {
              var s = service(a); List<String> endpoints = new ArrayList<>();
              if (s.health() != null) endpoints.add(s.health().toString());
              s.dependencies().forEach(u -> endpoints.add(u.toString()));
              return endpoints.isEmpty() ? Map.of("configured", false) : shell.run(ENDPOINTS, Map.of("endpoints", endpoints), c);
            }),
        tool("inspect_host", "Read OS, boot time, memory, local disk space, Java/dotnet executable locations and common reboot indicators.", Map.of(),
            (a,c) -> shell.run(HostScripts.HOST, Map.of(), c)));
  }
  public List<Map<String, Object>> summaries() {
    return catalog.services().stream().map(s -> {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("id", s.id()); m.put("type", s.type()); m.put("service_name", s.serviceName()); m.put("site_name", s.siteName());
      m.put("install_dir", s.installDir() == null ? "" : s.installDir().toString());
      m.put("logs", s.logs().stream().map(l -> l.directory().resolve(".").normalize() + "/" + l.pattern()).toList());
      m.put("ports", s.ports()); m.put("health", s.health() == null ? "" : s.health().toString());
      m.put("depends_on", s.dependencies().stream().map(Object::toString).toList());
      return m;
    }).toList();
  }
  private ServiceCatalog.Service service(JsonNode a) { return catalog.require(a.path("service_id").asString()); }
  private Map<String, Object> inspect(ServiceCatalog.Service s, RunControl c) throws Exception {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", s.id()); result.put("observed_at", Instant.now().toString());
    result.put("runtime", shell.run(s.type().equals("windows-service") ? HostScripts.SERVICE : HostScripts.IIS,
        Map.of("name", s.serviceName(), "site", s.siteName(), "pool", s.appPool()), c));
    if (!s.ports().isEmpty()) result.put("ports", shell.run(HostScripts.PORTS, Map.of("ports", s.ports()), c));
    try { result.put("deployment", DiagnosticFiles.deployment(s.installDir(), c)); }
    catch (java.io.IOException e) { c.check(); result.put("deployment", Map.of("error", "Deployment directory unavailable", "code", e.getClass().getSimpleName())); }
    return result;
  }
  private AgentTool tool(String name, String description, Map<String, Object> fields, Handler handler) {
    return new AgentTool() {
      public String name() { return name; }
      public String description() { return description; }
      public Map<String, Object> parameters() {
        return Map.of("type", "object", "properties", fields, "required", List.copyOf(fields.keySet()), "additionalProperties", false);
      }
      public JsonNode execute(JsonNode args, RunControl c) throws Exception {
        c.check();
        try {
          validate(args, fields);
          return sanitize(json.valueToTree(handler.run(args, c)));
        } catch (IllegalArgumentException e) {
          return json.valueToTree(Map.of("error", "Invalid arguments or unknown service; use list_services and the tool schema", "code", "invalid_arguments"));
        } catch (java.io.IOException e) {
          c.check();
          String code = e instanceof AccessDeniedException ? "access_denied" : e instanceof NoSuchFileException ? "path_not_found" : "query_failed";
          return json.valueToTree(Map.of("error", "Read-only query failed; check path, permissions and timeout", "code", code));
        }
      }
    };
  }
  private JsonNode sanitize(JsonNode node) {
    if (node.isString()) return json.valueToTree(DiagnosticFiles.redact(node.asString()));
    if (node.isArray()) { List<JsonNode> values = new ArrayList<>(); node.forEach(v -> values.add(sanitize(v))); return json.valueToTree(values); }
    if (node.isObject()) {
      Map<String, JsonNode> values = new LinkedHashMap<>(); node.properties().forEach(e -> values.put(e.getKey(), sanitize(e.getValue())));
      return json.valueToTree(values);
    }
    return node;
  }
  private static void validate(JsonNode args, Map<String, Object> fields) {
    if (args == null || !args.isObject() || args.size() != fields.size()) throw new IllegalArgumentException();
    for (var entry : fields.entrySet()) {
      @SuppressWarnings("unchecked") var schema = (Map<String, Object>) entry.getValue();
      JsonNode value = args.path(entry.getKey());
      if (schema.get("type").equals("string")) {
        if (!value.isString() || value.asString().length() > 256
            || (schema.containsKey("enum") && !((List<?>)schema.get("enum")).contains(value.asString()))) throw new IllegalArgumentException();
      } else if (!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() < (int)schema.get("minimum") || value.asInt() > (int)schema.get("maximum")) {
        throw new IllegalArgumentException();
      }
    }
  }
  private static final String ENDPOINTS = """
      Add-Type -AssemblyName System.Net.Http
      $results=@()
      foreach ($endpoint in $a.endpoints) {
        $u=[uri]$endpoint; $watch=[Diagnostics.Stopwatch]::StartNew()
        if ($u.Scheme -eq 'tcp') {
          $tcp=New-Object Net.Sockets.TcpClient
          try {
            $pending=$tcp.ConnectAsync($u.Host,$u.Port)
            $ok=$pending.Wait(1500) -and $tcp.Connected
            $results+=@{endpoint=$endpoint; reachable=$ok; elapsed_ms=$watch.ElapsedMilliseconds}
          } catch { $results+=@{endpoint=$endpoint; reachable=$false; error='TCP connect failed'} }
          finally { $tcp.Dispose() }
        } else {
          $handler=New-Object Net.Http.HttpClientHandler; $handler.AllowAutoRedirect=$false
          $client=New-Object Net.Http.HttpClient($handler); $client.Timeout=[TimeSpan]::FromMilliseconds(1500)
          $request=New-Object Net.Http.HttpRequestMessage([Net.Http.HttpMethod]::Get,$u)
          try {
            $pending=$client.SendAsync($request,[Net.Http.HttpCompletionOption]::ResponseHeadersRead)
            $response=$pending.GetAwaiter().GetResult()
            try { $results+=@{endpoint=$endpoint; reachable=$true; status=[int]$response.StatusCode;
              healthy=$response.IsSuccessStatusCode; elapsed_ms=$watch.ElapsedMilliseconds} } finally { $response.Dispose() }
          } catch { $results+=@{endpoint=$endpoint; reachable=$false; healthy=$false; error='HTTP request failed'} }
          finally { $request.Dispose(); $client.Dispose(); $handler.Dispose() }
        }
      }
      @{checks=$results}
      """;
}
