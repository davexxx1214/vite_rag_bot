# SSBot CLI (Java 21 + JLine 3)

An independent terminal prototype copied and adapted from ragbot. It starts no
Spring context, HTTP server or frontend. Build and run inside this directory.

```powershell
.\mvnw.cmd package
.\ssbot-cli.cmd --demo
.\ssbot-cli.cmd exec --demo "Hello"
.\ssbot-cli.cmd exec --demo --json "Hello"
.\ssbot-cli.cmd services
.\ssbot-cli.cmd inspect event-log --json
```

For real model calls, configure an endpoint supporting the **Responses API**:

```powershell
Copy-Item config.properties.example config.properties
# Edit config.properties: api_key, chat_model and openai_base_url.
.\ssbot-cli.cmd
.\ssbot-cli.cmd exec "Check the EventLog Windows service and report its state"
```

Alternatively use `OPENAI_API_KEY`, `OPENAI_MODEL`, `OPENAI_BASE_URL` (these override
the file). `--config FILE` selects a different file. The default file is relative
to the current working directory. Existing ragbot `config.yaml` is not loaded.
No API key is copied from the parent project. `--demo` uses a simulated model and
executes no API calls or host tools.

Interactive commands: `/help`, `/new`, `/status`, `/tools`, `/services`, `/exit`. JLine handles
editable input, input history, completion and terminal width. During a task,
Ctrl+C or Esc cancels; input resumes after cleanup. Ctrl+D exits at the prompt.
This is an inline terminal interface: streamed plain Markdown and tool progress,
with native scrollback, rather than a fullscreen dashboard. Input is available
between tasks; typing while a task runs is discarded.

`/status` reports the actual terminal backend. If an automation shell sets
`TERM=dumb`, JLine respects it and uses plain line input; live editing and
immediate key cancellation are unavailable. In a real Windows console, remove
that override (`Remove-Item Env:TERM`) before launching. Ctrl+C in plain mode may
terminate the process instead of cancelling only the current task.

Each successful turn is saved atomically in
`%LOCALAPPDATA%\SSBotCli\sessions\<id>.json`. Demo sessions are isolated in
`demo-sessions`. Resume with `--resume ID` (interactive or exec). Cancelled or
failed turns do not change the saved context. Tool results and opaque model items
are kept for subsequent turns. Sessions are limited to 160,000 serialized input
characters; use `/new` at the limit. This initial version has no automatic
compaction or long-term memory. Session files contain diagnostic data and inherit
the user's profile directory permissions.

`exec --json` writes NDJSON events (`round`, `delta`, `tool_start`, `tool_result`,
`done`, `session`, `error`) to stdout. `done` ends the model answer; the subsequent
`session` confirms persistence. Errors also go to stderr. Exit codes are 0 for
success, 1 for failure and 130 for cooperative cancellation. OS termination can
have platform-specific exit codes. Text mode sends model text to stdout and
progress to stderr. Piped prompts are accepted by `exec`.

Service registration and Windows tools:

```powershell
Copy-Item services.yaml.example services.local.yaml
# Edit names, paths, ports and endpoints for this server.
.\ssbot-cli.cmd --services services.local.yaml
.\ssbot-cli.cmd exec --services services.local.yaml "Check @order-api after its update"
.\ssbot-cli.cmd services --services services.local.yaml
.\ssbot-cli.cmd inspect order-api --services services.local.yaml --json
.\ssbot-cli.cmd discover --json
```

`services`, `inspect ID` and `discover` run locally without a model or API key.
`--json` uses the same NDJSON `tool_result` event as agent mode. These commands
perform real reads and reject `--demo`. Interactive `/services` only lists the
catalog, including in demo mode. Tab completes registered `@id` references.
The catalog is loaded at startup; restart after changing it. The default
`services.yaml` registers EventLog so local inspection works immediately. A
missing default file gives an empty catalog; an explicit missing file is an
error. `--services FILE` is relative to the shell; paths *inside* YAML are relative
to that YAML file. Supported types are `windows-service` and `iis-site`.

| Tool | Read-only checks |
| --- | --- |
| `list_services` | Registered IDs, deployment paths, log indexes and endpoints |
| `discover_windows_services` | First 200 Windows services, total count and truncation flag |
| `inspect_service` | Service/process/dependencies or IIS site/app pool/bindings; expected TCP listeners and deployment metadata |
| `read_service_logs` | Newest file matching a registered log pattern; `log_index`, `lines` (1-200), `encoding` |
| `read_service_events` | System/Application warning/error/critical events, registered providers, 1-1440 minutes, 1-50 records |
| `check_service_endpoints` | Registered HTTP(S) health URL and up to eight HTTP(S)/TCP dependencies |
| `inspect_host` | OS/boot/memory, disks, runtime executable locations and common pending-reboot indicators |
| `windows_service_status` | Exact service-name lookup without catalog registration |

`install_dir` lists up to 100 immediate entries with sizes and modification times,
plus disk capacity. It does not read configuration contents or recursively scan
deployments. `logs` accepts exact filenames or filename-only `*`/`?` patterns;
directories cannot contain wildcards. Each tail reads at most 64 KiB from one
regular file, rejects individual symlinks, scans at most 10,000 directory entries
and reports truncation. Encodings are `utf-8`, `gb18030` and `utf-16le`.

Event results include UTC time, event ID, record ID, provider and source log.
Provider/time matches are diagnostic candidates, not evidence that a particular
service caused the event; without configured providers the result covers host
warnings/errors. Access errors are separate from an empty event list. IIS needs
the WebAdministration module and appropriate local access. Its absence is
reported explicitly. Existing sites and bindings were not modified.

PowerShell uses fixed application-owned scripts and Base64-encoded JSON argument
data, with a 20-second query limit, 128 KiB output cap and process cleanup on
cancellation. No arbitrary model-generated PowerShell is executed. Endpoint
probes use GET, follow no redirects, send no configured credentials, read no
response bodies and allow about 1.5 seconds per endpoint. HTTP status and TCP
reachability are separate from application health. URLs cannot include userinfo,
query strings or fragments; local paths cannot use UNC or `%ENV%` expansion.

Common password/token/API-key and URL-credential patterns are redacted before
new tool results reach the model or NDJSON output; this is best-effort pattern
matching, not a guarantee that arbitrary diagnostic text has no secrets. All
queries run with the current account's permissions. No elevation is attempted.
Deployment, runtime and port sections may contain independent errors alongside
successful evidence. `inspect_host` locates Java/dotnet executables; it does not
enumerate installed runtime versions or every possible pending-reboot condition.

Source provenance:

- `Model`, `RunControl`, `ModelApiException`, `OpenAiClient`: copied from ragbot,
  moved into `com.example.ssbotcli`, then adapted for standalone configuration.
- `AgentEngine`: adapts ragbot's model/tool loop while retaining opaque reasoning
  and call identifiers. No finance user or portfolio types are present.
- `AgentTool`, `AgentListener`: small domain-neutral boundaries for future tools
  and frontends. All currently registered tools are read-only; there is no shell
  tool, write tool or approval workflow in this prototype.

Tests cover streamed Responses events, reasoning/call identity, tool budgets,
network cancellation, session persistence, configuration and argument validation.
They use a local HTTP stub and need no credentials. Build output is
`target\ssbot-cli.jar`, including JLine's Windows JNI provider; Java 21 is currently
required on the target machine. A bundled JRE / exe installer is a later step.

References: [JLine terminal providers](https://jline.org/versions/3.x/docs/modules/terminal-providers/),
[Responses function calling](https://developers.openai.com/api/docs/guides/function-calling).
