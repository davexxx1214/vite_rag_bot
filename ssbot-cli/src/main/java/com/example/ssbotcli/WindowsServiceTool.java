package com.example.ssbotcli;

import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class WindowsServiceTool implements AgentTool {
  private final PowerShellRunner runner;
  public WindowsServiceTool(ObjectMapper json) { runner = new PowerShellRunner(json); }
  public String name() { return "windows_service_status"; }
  public String description() { return "Read local Windows service state, start mode, executable path and PID by exact service name."; }
  public Map<String, Object> parameters() {
    return Map.of("type", "object", "properties", Map.of("name", Map.of("type", "string", "description", "Exact Windows service name")),
        "required", java.util.List.of("name"), "additionalProperties", false);
  }
  public JsonNode execute(JsonNode arguments, RunControl control) throws Exception {
    if (arguments == null || !arguments.isObject() || arguments.size() != 1 || !arguments.path("name").isString()
        || !arguments.path("name").asString().matches("[A-Za-z0-9_. -]{1,256}"))
      throw new IllegalArgumentException("Expected one exact service name (letters, digits, spaces, _, ., -).");
    return runner.run("$s=Get-CimInstance Win32_Service -Filter (\"Name='\"+$a.name+\"'\"); "
        + "if (!$s) { @{error='Service not found'} } else { $s | Select-Object Name,DisplayName,State,StartMode,"
        + "@{n='PathName';e={($_.PathName -replace '(?i)((?:password|pwd|api_key|token)\\s*[:=]\\s*)[^\\s;]+','$1[REDACTED]')}},ProcessId,ExitCode }",
        Map.of("name", arguments.path("name").asString()), control);
  }
}
