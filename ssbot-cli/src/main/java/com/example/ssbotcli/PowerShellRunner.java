package com.example.ssbotcli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Application-owned scripts only; all dynamic arguments are encoded JSON data. */
public class PowerShellRunner {
  private final ObjectMapper json;
  public PowerShellRunner(ObjectMapper json) { this.json = json; }
  public JsonNode run(String script, Map<String, ?> args, RunControl control) throws Exception {
    control.check();
    if (!System.getProperty("os.name").startsWith("Windows"))
      return json.valueToTree(Map.of("error", "Windows required", "code", "windows_required"));
    String data = Base64.getEncoder().encodeToString(json.writeValueAsBytes(args));
    String prefix = "$ErrorActionPreference='Stop'; $ProgressPreference='SilentlyContinue'; "
        + "[Console]::OutputEncoding=[System.Text.UTF8Encoding]::new($false); "
        + "$a=([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('" + data + "')) | ConvertFrom-Json); ";
    String body = prefix + "try { & { " + script + " } | ConvertTo-Json -Depth 8 -Compress } "
        + "catch { @{error='Windows query failed'; code=$_.CategoryInfo.Category.ToString()} | ConvertTo-Json -Compress }";
    String executable = Path.of(System.getenv("SystemRoot"), "System32", "WindowsPowerShell", "v1.0", "powershell.exe").toString();
    Process p = new ProcessBuilder(executable, "-NoProfile", "-NonInteractive", "-EncodedCommand",
        Base64.getEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_16LE))).start();
    try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var stdout = executor.submit(() -> p.getInputStream().readNBytes(131073));
      var stderr = executor.submit(() -> p.getErrorStream().readNBytes(131073));
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
      try {
        while (!p.waitFor(100, TimeUnit.MILLISECONDS)) {
          control.check();
          if (System.nanoTime() > deadline) throw new IOException("Windows query timed out");
          if ((stdout.isDone() && stdout.get().length > 131072) || (stderr.isDone() && stderr.get().length > 131072))
            throw new IOException("Windows query output exceeded 128 KiB");
        }
        control.check();
        if (p.exitValue() != 0) throw new IOException("Windows query process failed");
        byte[] bytes = stdout.get(2, TimeUnit.SECONDS);
        if (bytes.length > 131072) throw new IOException("Windows query output exceeded 128 KiB");
        String text = new String(bytes, StandardCharsets.UTF_8).strip();
        return text.isEmpty() ? json.valueToTree(Map.of("error", "Windows query returned no data")) : json.readTree(text);
      } finally {
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        if (p.isAlive()) p.destroyForcibly();
        p.getInputStream().close(); p.getErrorStream().close();
      }
    }
  }
}
