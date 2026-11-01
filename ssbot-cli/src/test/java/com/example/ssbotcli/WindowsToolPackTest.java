package com.example.ssbotcli;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class WindowsToolPackTest {
  @TempDir Path temp;
  final ObjectMapper json = new ObjectMapper();
  ServiceCatalog catalog(String fields) throws Exception {
    Path file = temp.resolve("services.yaml");
    Files.writeString(file, "services:\n  - id: app\n    type: windows-service\n    service_name: EventLog\n" + fields);
    return ServiceCatalog.load(file, true);
  }
  JsonNode call(WindowsToolPack pack, String name, Map<String, Object> args) throws Exception {
    return pack.tools().stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow()
        .execute(json.valueToTree(args), new RunControl(60));
  }

  @Test void parsesRelativePathsWildcardsAndEndpoints() throws Exception {
    var c = catalog("    install_dir: app\n    logs: [logs/*.log]\n    ports: [8080]\n    health: http://localhost:8080/health\n    depends_on: [tcp://localhost:1433]\n");
    var service = c.require("@app");
    assertEquals(temp.resolve("app"), service.installDir());
    assertEquals(temp.resolve("logs"), service.logs().getFirst().directory());
    assertEquals("*.log", service.logs().getFirst().pattern());
    assertEquals(1433, service.dependencies().getFirst().getPort());
    assertThrows(IllegalArgumentException.class, () -> c.require("missing"));
    assertEquals(0, ServiceCatalog.load(temp.resolve("missing.yaml"), false).services().size());
    assertThrows(java.io.IOException.class, () -> ServiceCatalog.load(temp.resolve("missing.yaml"), true));
  }

  @Test void rejectsAmbiguousAndExecutableCatalogConfiguration() throws Exception {
    for (String invalid : List.of("    ports: [0]\n", "    ports: [65536]\n", "    ports: [3.5]\n",
        "    logs: [logs/*/app.log]\n", "    health: http://user:secret@localhost/\n", "    health: file:///c:/secret\n",
        "    health: http://localhost/?token=secret\n", "    command: Stop-Service\n", "    id: duplicate\n"))
      assertThrows(IllegalArgumentException.class, () -> catalog(invalid), invalid);
    Path file = temp.resolve("evil.yaml");
    Files.writeString(file, "!!java.lang.ProcessBuilder {}\n");
    assertThrows(IllegalArgumentException.class, () -> ServiceCatalog.load(file, true));
    Files.writeString(file, "services:\n  - &s {id: app, type: windows-service, service_name: EventLog}\n  - *s\n");
    assertThrows(IllegalArgumentException.class, () -> ServiceCatalog.load(file, true));
  }

  @Test void argumentFailuresNeverReachPowerShell() throws Exception {
    AtomicInteger executed = new AtomicInteger();
    var pack = new WindowsToolPack(catalog(""), json, new PowerShellRunner(json) {
      public JsonNode run(String script, Map<String, ?> args, RunControl c) { executed.incrementAndGet(); return json.valueToTree(Map.of()); }
    });
    for (Map<String, Object> args : List.of(Map.<String,Object>of("service_id", "unknown"),
        Map.<String,Object>of("service_id", "app", "command", "x")))
      assertEquals("invalid_arguments", call(pack, "inspect_service", args).path("code").asString());
    assertTrue(call(pack, "read_service_events", Map.of("service_id", "app", "minutes", -1, "limit", 5)).has("error"));
    assertTrue(call(pack, "read_service_logs", Map.of("service_id", "app", "log_index", 0, "lines", 0, "encoding", "utf-8")).has("error"));
    assertEquals(0, executed.get());
  }

  @Test void logTailSelectsNewestAndBoundsReadWhileRedactingCredentials() throws Exception {
    Files.createDirectories(temp.resolve("logs"));
    Files.writeString(temp.resolve("logs/old.log"), "old\n");
    Files.setLastModifiedTime(temp.resolve("logs/old.log"), java.nio.file.attribute.FileTime.fromMillis(1));
    Files.writeString(temp.resolve("logs/new.log"), "x".repeat(100000) + "\nline two\npassword=secret\nAuthorization: Bearer xyz\n", Charset.forName("GB18030"));
    var pack = new WindowsToolPack(catalog("    logs: [logs/*.log]\n"), json);
    var result = call(pack, "read_service_logs", Map.of("service_id", "app", "log_index", 0, "lines", 2, "encoding", "gb18030"));
    assertTrue(result.path("file").asString().endsWith("new.log"));
    assertTrue(result.path("truncated").asBoolean());
    assertEquals(2, result.path("lines").size());
    assertFalse(result.toString().contains("secret")); assertFalse(result.toString().contains("xyz"));
    assertTrue(result.toString().contains("REDACTED"));
    assertEquals("invalid_log_index", call(pack, "read_service_logs", Map.of("service_id", "app", "log_index", 5, "lines", 2, "encoding", "utf-8")).path("code").asString());
  }

  @Test void logTailSupportsChineseAndUtf16Alignment() throws Exception {
    Files.writeString(temp.resolve("app.log"), "start\n状态正常\n", java.nio.charset.StandardCharsets.UTF_16LE);
    var pack = new WindowsToolPack(catalog("    logs: [app.log]\n"), json);
    var result = call(pack, "read_service_logs", Map.of("service_id", "app", "log_index", 0, "lines", 1, "encoding", "utf-16le"));
    assertEquals("状态正常", result.path("lines").get(0).asString());
  }

  @Test void inspectionPreservesPartialFactsAndRedactsHostValues() throws Exception {
    Files.createDirectory(temp.resolve("deploy")); Files.writeString(temp.resolve("deploy/version.txt"), "1.0");
    var pack = new WindowsToolPack(catalog("    install_dir: deploy\n    ports: [8080]\n"), json, new PowerShellRunner(json) {
      public JsonNode run(String script, Map<String, ?> args, RunControl c) {
        return json.valueToTree(Map.of("command", "api_key=topsecret", "value", "observed"));
      }
    });
    var result = call(pack, "inspect_service", Map.of("service_id", "app"));
    assertTrue(result.path("deployment").path("exists").asBoolean());
    assertEquals(1, result.path("deployment").path("files").size());
    assertFalse(result.toString().contains("topsecret")); assertTrue(result.has("ports"));
    var items = call(pack, "list_services", Map.of());
    assertEquals("app", items.get(0).path("id").asString());
  }

  @Test void catalogueExamplesParseOnWindows() throws Exception {
    assertEquals(1, ServiceCatalog.load(Path.of("services.yaml"), true).services().size());
    assertEquals(2, ServiceCatalog.load(Path.of("services.yaml.example"), true).services().size());
  }

  @Test void redactsQuotedSecretsAndUrlCredentials() {
    String text = DiagnosticFiles.redact("password=\"two words\" api_key='another secret' https://user:pass@server/path");
    assertFalse(text.contains("two words")); assertFalse(text.contains("another secret")); assertFalse(text.contains("user:pass"));
  }

  @Test void doesNotFollowIndividualLogSymlinks() throws Exception {
    Files.createDirectory(temp.resolve("logs")); Files.writeString(temp.resolve("outside.log"), "do not read");
    try { Files.createSymbolicLink(temp.resolve("logs/link.log"), temp.resolve("outside.log")); }
    catch (java.io.IOException | UnsupportedOperationException e) { org.junit.jupiter.api.Assumptions.abort("Symlink creation unavailable"); }
    var pack = new WindowsToolPack(catalog("    logs: [logs/*.log]\n"), json);
    var result = call(pack, "read_service_logs", Map.of("service_id", "app", "log_index", 0, "lines", 10, "encoding", "utf-8"));
    assertTrue(result.has("error")); assertFalse(result.toString().contains("do not read"));
  }
}
