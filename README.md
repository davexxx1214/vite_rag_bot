# SSBot - Local Finance Agent

SSBot is a financial business assistant with a React + MUI + Vite frontend and a Spring Boot 4 / Java 21 backend. It searches a local vector index, queries financial mock APIs, and saves per-user memory and conversations as readable server files. Independent read tools can execute concurrently, while memory writes remain serial. Production runs as a single Java JAR.

The backend is a standard Maven project. Spring Boot provides the HTTP API, configuration, validation, static-file hosting, and an executable production JAR. Vector search stays deliberately small: the generated JSON index is loaded into memory and searched with cosine similarity, without a vector database or a Node.js server runtime.

Chat responses use an NDJSON stream for tool activity, citations, context compression and answer text. Assistant messages support GitHub-Flavored Markdown and are sanitized with DOMPurify before rendering. The OpenAI API key remains in the Java process and is never exposed to Vite.

Tables and supported visual blocks display directly in assistant messages.
Closed `flow`, `sequence`, `er`, `tree`, `timeline`, `limits`, `kv`, `callout` and
`annot` fences load the Answer me with HTML 0.5.2 renderer on demand, without an
extra model request or a preview button. Streaming prose continues immediately;
incomplete fences and invalid components retain their original code. Rendered
blocks survive later text updates, preserve citation markers and use sanitized
HTML/SVG inside independent shadow roots. Styles and diagram IDs stay scoped to
each block. Filesystem references, executable markup, videos and upstream
CLI/skill workflows are not enabled. Production still runs as one Java JAR
without a Node server. Markdown remains the stored conversation format.
Upstream provenance and license are in `vendor/answer-me-with-html/`.

## Requirements

- JDK 21 or newer (`java` on `PATH`)
- pnpm 10.33.2

Maven does not need to be installed globally; the checked-in Maven Wrapper downloads the pinned Maven version on first use.

## Run locally

1. Copy `config.yaml.example` to `config.yaml`, then set `api_key: ...` in the local file.
2. Install frontend dependencies with `pnpm install`.
3. Generate the vector index with `pnpm build:index` when the documents change. The repository already contains a demo index.
4. Start both development services with `pnpm dev`.
5. Open `http://localhost:3000`.

In development, Vite runs on port 3000 and proxies `/api` to the Java service on port 3001. Closing either process stops both. `config.yaml` is ignored by Git and is never served by either server.

## Production build

```sh
pnpm build
pnpm start
```

`pnpm build` builds the Vite frontend and packages it with the Spring Boot service into `target/ragbot.jar`. The executable JAR serves both the API and the embedded frontend on the configured port, which defaults to 3000.

Use `pnpm build:all` to regenerate `data/rag-index.json` before building everything. Use `pnpm build` when the documents and existing vector index have not changed, avoiding an unnecessary embedding request.

## Useful commands

```sh
pnpm typecheck       # Type-check the frontend
pnpm test:server     # Run the Spring Boot/Maven tests
pnpm test:ui         # Check frontend execution-event handling
pnpm build:server    # Test and package the executable Spring Boot JAR
pnpm server          # Build and run only the Java service
pnpm build:index     # Rebuild the local vector index through Java
pnpm build           # Build frontend and Java service
```

## Project layout

- `SOUL.md`: editable SSBot identity, style and financial business instructions.
- `tools.yaml`: definitions for read-only business API tools.
- `config.yaml.example`: local configuration template; copy it to the ignored `config.yaml`.
- `docs/`: local Markdown knowledge base.
- `data/rag-index.json`: generated local vector index.
- `src/main/java/`: Spring Boot API, agent loop, tools, file memory, RAG retrieval, OpenAI client and index builder.
- `src/main/resources/`: Spring Boot configuration.
- `src/test/java/`: Maven/JUnit backend tests.
- `src/`: React + MUI Help Center and Markdown-enabled chat UI.
- `pom.xml`, `mvnw`, `.mvn/`: Maven build and pinned wrapper.

The bundled Alpaca pages are concise adaptations for demonstration and link to their original documentation.

## Using SSBot

Open **Ask AI**, choose a demo user, and try:

- `List my portfolios and compare pf-001 with pf-002.` (Alice)
- `Compare the metadata for issuers iss-001 and iss-004 in my holdings.` (Bob)
- `Find the help documentation about order rules.`
- `Remember that I prefer short replies in English.`
- `What did we discuss about pf-001 last time?`

**New chat** creates a separate conversation while retaining that user's long-term memory.
The conversation selector restores server history, including after browser refresh or server restart.
**Memory** opens the user's Markdown memory for editing or clearing. **Stop response** cancels
the backend request and releases the user's write lock. Clearing memory removes the active
preferences; original conversation records and archived memory remain on disk.

## Agent architecture

The agent uses a model/tool loop implemented directly in Java:

1. Acquire the user's run lock and load memory and the selected conversation.
2. Read `SOUL.md`, prepare the context, and call the model with the available tools.
3. Execute consecutive read tools concurrently, using writes as serial barriers.
4. Assign citations and return results in call order, preserving each `call_id` and opaque reasoning item.
5. Repeat until the model answers or a budget requires a final answer without tools.
6. Commit one complete or incomplete history turn, update eligible memory, and finish the NDJSON stream.

There are no subprocess agents, shell tools, extra Node service or database dependencies.

| Component | Responsibility |
| --- | --- |
| `AgentService` | Own user locks, active requests, deadlines and recovery orchestration |
| `AgentLoop` | Run model/tool rounds and enforce the existing round and call budgets |
| `Model` / `OpenAiClient` | Define model calls and adapt the Responses API, preserving opaque output items |
| `AgentTool` / `DefaultAgentTools` | Define tool metadata and execution; register knowledge, business and memory tools |
| `ToolRegistry` / `AgentToolExecutor` | Validate arguments, execute read batches, assign citations and bound results |
| `AgentContext` / `ContextManager` | Inject current instructions and memory, restore history and compress context |
| `AgentRecovery` | Attempt one final synthesis from successful results, or return a bounded local fallback |
| `AgentHistoryRecorder` / `MemoryStore` / `FileHistory` | Commit full turns and manage per-user memory, transcripts and payloads |
| `AgentEvent` / `AgentListener` / `NdjsonAgentListener` | Separate run events from the frontend's NDJSON wire format |
| `CoreMemoryManager` / `CoreMemoryFiles` | Update durable preferences and recover interrupted two-file memory writes |
| `HistoryMaintenance` | Archive inactive sessions, enforce retention and track storage usage |
| `FinanceApiClient` | Query user-scoped mock APIs over HTTP, using the server's actual listening port |

The model adapter, tool implementations, context preparation, event delivery and history commits
have separate boundaries. The existing storage formats, compression behavior and run limits remain
in place.

### Model adapters

The bundled adapter is `OpenAiClient`. An alternative adapter implements `Model.respond` and
`Model.summarize` and is selected through Spring dependency injection. A `Model.Turn` includes the
complete output items, answer text and token usage. Preserve opaque reasoning and call identifiers
when adapting those items: the loop returns them in subsequent tool rounds. The existing RAG
embedding path continues to use `OpenAiClient` independently of the chat interface.

### Tool catalog

| Tool | Purpose |
| --- | --- |
| `search_docs` | Embed a search query and retrieve excerpts from the local JSON vector index |
| `list_portfolios`, `get_portfolio` | Find/read the current user's portfolios |
| `query_portfolios`, `summarize_portfolios` | Filter/page a saved snapshot and calculate complete-view totals and selected-page shares |
| `get_positions`, `get_transactions`, `get_accounts` | Read holdings, trades and accounts |
| `list_issuers`, `get_issuer` | Read issuers visible through that user's holdings |
| `compare_portfolios` | Calculate monetary differences and allocation percentages in Java |
| `update_portfolio` | Rename an owned portfolio with version checks and a durable operation receipt |
| `check_edit_operation` | Resolve an uncertain edit using its saved operation ID before attempting another edit |
| `request_user_input` | Present one clarification card with choices, free text and cancellation |
| `search_history` | Rank keyword/ID matches, page results, read surrounding turns or saved payload chunks |
| `remember`, `forget_memory` | Update a keyed, explicit durable preference |

All business tool requests inject the current user's username on the server. The model cannot
select another username or an arbitrary URL. Cross-user detail requests return tool errors.
Different currencies are compared separately; no FX rate or cross-currency difference is invented.
Issuer responses contain metadata, not financial statements or credit ratings.

### Large portfolio snapshots and follow-ups

Alice's unpaginated mock endpoint returns 3,000 portfolios (3,002 across all users, retaining
Bob's and Charlie's original fixtures). `list_portfolios` receives the complete response once,
validates ownership, unique IDs, required amounts and timestamps, and saves an immutable
`resultSetId` under the current user's session. The model receives metadata and at most ten
preview rows, bounded by the existing 3,000-token tool budget. Preview rows never represent
the complete list. `sourceAsOfFrom`/`sourceAsOfThrough` describe backend data dates; `fetchedAt`
records when the snapshot was received.

Repeated `list_portfolios` calls in the same session reuse the snapshot. Lookup and fetch
share a user lock, so concurrent identical loads issue one backend request. Set `refresh=true`
for an explicit latest-data request. Refresh creates a new ID and keeps older unexpired IDs
and their views available. Expired IDs fail with a tool error instead of silently changing
the data; calling `list_portfolios` fetches a new snapshot and removes expired snapshot files.
Session deletion removes its result sets, and files count against the user storage quota.

`query_portfolios` filters by currency, name substring or exact ID, sorts, selects fields,
and pages by record offset. The hard page maximum is 20; the token budget may return fewer,
with `nextOffset` identifying the next record. Omitted filters retain the previous view;
`resetView=true` clears it. Monetary rankings require one currency. `summarize_portfolios`
uses Java `BigDecimal` over every matching record, grouping amounts by currency and reporting
the last query page's selected total and percentage of that currency's filtered total.
If many currency groups exceed the output budget, follow `nextCurrencyOffset` using
`currencyOffset` to read the remaining groups.

For example, call `list_portfolios`, then `query_portfolios(currency="USD")`, then
`query_portfolios(sortBy="totalValue", descending=true, limit=5)`, and finally
`summarize_portfolios`. Only the first call fetches the backend. Pass the returned ID, or
omit it to use the active session snapshot. Snapshot files and current conditions/selected
IDs survive restarts; the small active pointer is added to model instructions every round,
so history compaction does not force a new backend fetch.

Configure `agent.result-sets.max-response-bytes` (default 8 MiB), `max-records` (10,000), and
`ttl-seconds` (1,800). These reception limits apply to the portfolio snapshot adapter;
ordinary finance GET tools retain their 128 KiB response cap. Invalid, incomplete or oversized
responses are never published as complete snapshots. The adapter currently requires the
mock portfolio schema, including `username` on each row; adapt that validation when connecting
a backend with a different ownership contract. These GET-based tools have no trade/write
operation to invalidate snapshots; future writes should invalidate affected result sets.

### Parallel read tools

The model can request multiple tools in one response. `AgentToolExecutor` executes consecutive
tools explicitly marked `readOnly` on virtual threads. A memory write or any tool without that
declaration is a serial barrier: earlier reads finish before it runs, and later reads start after
it completes. Dependent queries remain separate model rounds. Existing round/call/context/time
budgets are unchanged; skipped calls receive paired error results without executing.

Results are fed back in the model's call order. The coordinator assigns unique `[S#]` / `[B#]`
citations before truncation or payload storage, preserving chunked access to oversized results.
Cancellation closes every pending HTTP request and active response stream; workers finish their
file writes before the user lock is released. A successful sibling result remains available to
history and partial-answer synthesis if another read fails or exceeds the query deadline.

To add a Java tool, register an `AgentTool`, or use `ToolRegistry.register(name, description,
fields, readOnly, handler)`. The shorter overload defaults to serial execution. Ordinary GET
tools from `tools.yaml`, knowledge search, portfolio comparison and history search are declared
reads; `remember`, `forget_memory` and the managed portfolio snapshot tools stay serial.
Portfolio tools maintain the current view/selection, so later calls observe earlier results
in model order.

### Adding ordinary business tools

Edit `tools.yaml` and restart the JAR to expose an existing read-only backend endpoint. Ordinary
GET tools require no changes to the Java registry or agent loop. Local `tools.yaml` overrides the
bundled defaults; when absent, the JAR uses its embedded copy. Set `agent.tools-config` to an
explicit `file:` or `classpath:` resource to choose another file; a missing explicit file fails startup.
New backend endpoint implementations and calculated tools such as `compare_portfolios` remain Java.

```yaml
tools:
  - name: get_report
    description: Read a report belonging to the current user.
    title: Report
    status: Querying report...
    method: GET
    path: /reports/{id}
    parameters:
      id: {type: string, description: Report ID}
      limit: {type: integer, description: Page size, optional: true, query: limit}
```

Append entries to the existing list to keep the default tools. Parameters support `string`,
`integer` and `boolean`; optional values use nullable strict schemas. Path parameters must be
required strings with valid resource IDs. A `query` field maps a parameter to a query-string key.
The server validates argument types, injects username and bounds tool output as before. Absolute
URLs, identity overrides, undefined placeholders, duplicate names and non-GET methods fail startup.

### Editing SSBot's instructions

Edit the root `SOUL.md` to change SSBot's identity, reply style or business instructions.
The file is written in English and defines SSBot as a financial business assistant. Its style is
to avoid unnecessary complexity, reply concisely and warmly, and use independent judgment.
The instruction language does not force English replies: SSBot still replies in the user's language.
The backend reads the UTF-8 file before each agent model request, so edits take effect on
subsequent requests without rebuilding or restarting. User identity and per-user `USER.md` /
`MEMORY.md` context are appended separately.

The default is `optional:file:./SOUL.md`, relative to the server's working directory. When
absent, the JAR uses its bundled copy. To edit a deployed instance, place `SOUL.md` beside the JAR
and start the server from that directory, or set `agent.soul-config` (or `AGENT_SOUL_CONFIG`) to
an explicit `file:` or `classpath:` resource to choose another file. A missing explicit
resource or an empty file fails startup; invalid edits also fail the affected request.
The file is server configuration shared by all users; edit it on the server filesystem.

### File memory

Default storage is `./data/agent`, relative to the server's working directory:

```text
data/agent/
  .lock
  users/user-001/
    USER.md
    MEMORY.md
    archive/memory-<uuid>.md
    sessions/<session-uuid>/
      session.json
      messages.jsonl
      messages.jsonl.gz          # Replaces raw transcript after inactive-session archival
      history-index.jsonl        # Rebuildable keywords, excerpts and raw byte offsets
      archive-summary.md         # Dated query conditions and historical conclusions
      payloads/<uuid>.json       # Large tool output, stored once; .gz after archival
      summary.md
      memory-cursor.txt
```

`USER.md` holds explicit user preferences/profile; `MEMORY.md` holds stable business background.
They share the 1,800 estimated-token budget. Keyed corrections replace only the matching entry,
including when moving it between files. On overflow the model first consolidates a related pair,
preserving keys, then shortens individual long entries if necessary. Unrelated entries stay intact;
if the result still cannot fit, the write fails and existing memory is preserved. Backups include
previous memory and full oversized candidates. Legacy `memory.md` automatically migrates into
`USER.md`, including on case-insensitive Windows filesystems. A temporary recovery journal makes
an interrupted two-file update recoverable. Direct file edits are checked before the next agent run.

`messages.jsonl` stores one turn per line, including calls/results, sources, timestamps and completion
status. Writes append and flush a newline commit marker; startup recovers committed rows and
truncates only a torn final append. Metadata and keyword/offset indexes are rebuilt when needed.
`summary.md` records the active compression boundary. Context loads unsummarized history in
bounded batches instead of loading the whole conversation. Large tool outputs are stored once in
session-owned payload files, with a bounded preview/reference in the transcript. Oversized business
tool results can be read in chunks using `search_history`; no arbitrary filesystem path is accepted.

Memory, metadata, summaries and completed archive files use atomic replacement. One run gate per user
serializes agent runs, manual compaction, memory editing and deletion; competing mutations get a conflict
(chat streams report a conflict event). File locks are separate and held only for storage operations,
so reading memory/history and creating another chat do not wait for model/tool HTTP requests.
A process lock prevents two JAR instances
from writing the same memory directory. This version is designed for one server instance and
modest conversation history. Keyword discovery streams compact indexes (including CJK bigrams);
it is local keyword retrieval, not semantic/vector memory search. At much larger scale a dedicated
index may be appropriate. Gzip history is streamed on demand; active raw history uses byte offsets.
Back up the entire memory directory, and keep it on a persistent volume when deploying.

Memory extraction runs after every three successful, unprocessed turns. It only considers explicit
user statements and saves stable facts/preferences. Financial snapshots stay in history and are
queried again when current data is needed. Explicit `remember` requests save immediately.

History maintenance runs hourly in its own component; creating a conversation never triggers
archive or retention work. Busy users are skipped by the scheduled job. The file lock is released
between sessions. Quota totals are initialized once and updated by scanning only the affected
session or core files on ordinary writes; maintenance and `/storage` reconcile full totals,
including externally changed files. Defaults are 30 days since last activity before archiving, 365 days retention,
and 500 MiB per user, counting sessions, indexes, payloads and memory backups. Gzip archives are
verified byte-for-byte before the raw file is removed. Archived conversations remain searchable,
readable and exportable; appending to one restores its raw transcript after checking expanded size.
An archive summary records dates, recent queries, tool parameters and historical conclusions.

`agent.history.delete-expired: true` explicitly enables deletion of expired sessions and expired
memory backups; `false` keeps them. Retention uses **last activity**, so resumed sessions get a new
retention period. Core memory is not expired. Quota does not authorize deletion of unexpired data:
new oversized writes fail with HTTP 507 (or a streamed storage error), explaining how to export,
delete old conversations or raise `agent.history.user-quota-bytes`. The UI supports loading earlier
messages, exporting JSONL and explicitly deleting a chat. Deleting a chat removes its transcript,
summary, index and payloads; independently saved core preferences remain until edited/cleared.

### Models and context limits

The default is `gpt-6-luna`; set `chat_model: gpt-5-mini` in `config.yaml` to switch.
`AgentProperties` uses built-in budgeting windows of 1,050,000 and 400,000 tokens for these
model names, and 128,000 tokens for other names. Set `agent.context-window-tokens` for the actual
window exposed by your provider or proxy. The agent uses a smaller working budget to control
latency and cost:

| Setting | Default |
| --- | --- |
| Maximum estimated input | 64,000 tokens |
| Start summarizing history | 48,000 tokens (75% of effective input cap) |
| Compression target / recent history allowance | 32,000 / 16,000 tokens |
| Active long-term memory | 1,800 tokens |
| One tool result | 3,000 tokens |
| Maximum model output, including reasoning | 8,000 tokens |
| Maximum model rounds | 16 |
| Total executed tool calls / defensive per-round cap | 12 / 8 |
| Total request deadline | 240 seconds |
| Time reserved for final synthesis | 40 seconds (query work stops at 200 seconds) |

Input accounting includes instructions, memory, schemas and messages. It uses a conservative
UTF-8 byte estimate, not an exact tokenizer; actual Responses usage also triggers compression.
The effective input cap is the lesser of the configured working budget and the model window
minus output space and a 4,000-token reserve. Smaller model/proxy windows also lower the compression
threshold, target and recent-history allowance. Historical summaries have a proportional cap,
up to 6,000 estimated tokens. Oversized active tool work is summarized only
between complete call/result groups. Encrypted reasoning is replayed within the active loop;
transport bytes are not counted as model tokens. Incomplete model responses are recorded as
failed turns, never as successful answers. Tool attempts count even when they fail or need a retry.
The Responses adapter sends `parallel_tool_calls: true`. The model may return multiple calls;
only consecutive tools declared `readOnly` execute concurrently. Memory writes and undeclared
tools execute serially. Queries that need an earlier result remain separate model rounds.
Budget exhaustion disables further tools and forces an answer from available results. Unexpected
oversized call batches execute only within both caps; skipped calls receive paired budget errors.
The query deadline cancels pending HTTP requests and closes response streams, reserving time for
one synthesis without tools. It does not interrupt the run's thread or file writes.
If synthesis fails or the hard deadline is reached, a short local response lists completed queries.
Original tool results remain in history/payload files and can be retrieved on demand; they are not
copied into the fallback answer or the next turn's context. No extra deadline is granted.
User cancellation and disconnection stop the run without launching recovery model requests.
Partial answers are marked in the stream and stored as incomplete turns, keeping full tool records
available in history. The UI labels them clearly and allows a follow-up query in the same chat.

The chat shows live execution steps, including the tool name, bounded query/resource details,
running/success/failure states and elapsed time. These are server execution events, not model
reasoning. Completed steps remain expandable in the current page; restored chats retain answers
and sources rather than replaying transient progress. Spring streaming and the browser allow
270 seconds so the agent's 240-second deadline can return its final response/error first.

Override `agent.context-window-tokens` when an API proxy exposes a smaller window. Other
settings are in `src/main/resources/application.yaml` and examples in `config.yaml.example`.
The provider must support Responses streaming, function calling, reasoning and encrypted
reasoning replay; query embeddings use the index's recorded embedding model.

YAML configuration is loaded at server startup. Restart after changing `api_key`, `chat_model`,
`openai_base_url` or tool definitions. `SOUL.md` content is reloaded before model requests.
`/api/health` checks the running application and local index; it does
not authenticate the key or make a model request. Chat reports explicit HTTP status/error
codes for invalid keys, denied model access, exhausted quota, rate limits and upstream failures.
Provider error bodies and credentials are excluded from those messages and server logs.

### Agent HTTP API and identity

`X-Mock-User-Id` selects `user-001` (Alice), `user-002` (Bob) or `user-003` (Charlie).
This is a demo selector, **not authentication**. A production login must supply a verified
server-side user identity before this application is exposed to real users. The existing `/api/mock`
endpoints remain public demo data and can still be queried directly with their test filters.

| Endpoint | Purpose |
| --- | --- |
| `GET /api/agent/users` | Public demo user list |
| `GET /api/agent/sessions?offset=0&limit=50` | Page this user's conversation list |
| `POST /api/agent/sessions` | Create a conversation; returns `{ "id": "..." }` |
| `GET /api/agent/sessions/{id}?offset=0&limit=50` | Page display turns; omitted offset loads the latest page |
| `GET /api/agent/sessions/{id}/export` | Stream original JSONL, expanding saved payload references |
| `DELETE /api/agent/sessions/{id}` | Delete this user's conversation and all associated history files |
| `GET /api/agent/history?query=pf-001&offset=0&limit=5` | Ranked historical excerpts with nextOffset |
| `GET /api/agent/storage` | Disk usage, quota and retention configuration |
| `POST /api/agent/sessions/{id}/cancel` | Cancel this user's active conversation request |
| `POST /api/agent/sessions/{id}/compact` | Explicitly summarize earlier turns |
| `GET / PUT / DELETE /api/agent/memory` | Read, replace (`{ "user": "...", "notes": "..." }`) or clear both files |
| `POST /api/chat` | NDJSON stream; body `{ "sessionId": "...", "message": "..." }` |

All routes except the demo user list require the identity header. Client-supplied conversation
history is no longer used: history is read exclusively from the user's owned server session.
The backend emits `activity_start`, `activity_update`, `activity_end`, `tool_start`, `tool_end`,
`context_compacted`, `sources`, `answer_reset`, `delta` and `done`; the HTTP controller emits
`error` when a request fails. The frontend also accepts the legacy `status` event.
Intermediate text is reset when another tool round starts so the UI displays the final answer.
`done` terminates the stream; `partial: true` identifies an incomplete task even when an answer
was returned. Tool events use invocation IDs so repeated calls remain distinct.

History offsets count **turns**, each with a user message and assistant reply. Message pages return
`offset`, `nextOffset`, `total`; session lists remain arrays. Search pages return `results`, `offset`,
`nextOffset`, `total`. The agent's `search_history` accepts nullable optional fields: `query`, `offset`,
`limit`, `sessionId`, `aroundTurn`, `window`, `payloadRef`, `payloadOffset`. Supply a query for
discovery, sessionId/aroundTurn for surrounding messages, or sessionId/payloadRef for payload
chunks. Follow returned offsets for more; the server enforces the tool token budget and retains
the requested anchor when trimming a window. Legacy memory PUT `{ "content": "..." }` replaces
USER.md and clears MEMORY.md; structured PUT preserves the separate editable sections.

### Adding Java tools

Implement `AgentTool` and register it with `ToolRegistry`, or register a named handler using
`register(name, description, fields, readOnly, handler)`. Declare `readOnly` only for tools that can
safely run beside other reads. The shorter overload and the interface default use serial execution.
`DefaultAgentTools` contains the built-in registrations. Extend `FinanceApiClient` for a fixed API
path if needed, keeping identity injection in Java. New tools return call-local sources; the
registry assigns final citations before applying result budgets or storing a payload.
The optional `agent.finance-base-url` / `FINANCE_API_BASE_URL` overrides the `/api/mock` base;
external implementations must honor the same current-user scope and response shapes.

### Validation

```sh
pnpm test:server
pnpm test:ui
pnpm typecheck
pnpm build:server
```

`pnpm test:server` includes file restart/isolation, atomic writes, memory correction, context
failure recovery, plus HTTP integration tests that boot the actual Java service. A local simulated
OpenAI server exercises streaming, encrypted reasoning replay, tool errors, API scope, portfolio
math, Chinese excerpt limits, memory extraction/archiving, compression, cancellation and round
limits. Additional tests use an independent `Model` implementation to exercise parallel reads,
out-of-order completion, unique citations, memory write barriers, exact call budgets, partial-result
recovery and disconnected clients. Network tests cover simultaneous blocked response bodies and
pending headers; worker tests verify that cancellation and caller interruption preserve file writes.
SOUL tests verify bundled fallback, live edits and propagation alongside user memory. Frontend tests
check invocation matching and execution-state updates. These tests make no paid model or embedding requests.

## Financial mock API

The Java service provides JSON endpoints under `/api/mock` for integration tests.
Fixtures are entirely fictional and make no OpenAI calls or database requests. Portfolio names
can be edited in memory; other business fields remain read-only. Names and operation receipts
reset when the Java process restarts. Persistent current portfolio snapshots refresh after edits
and after a local mock server restart; explicit old snapshot IDs retain their historical contents.
Prices and valuations have a fixed `asOf` timestamp (`2026-10-09T16:00:00Z`); amounts are JSON
numbers calculated with `BigDecimal`. They are not live market data. These endpoints are
available through the Vite proxy and in the production JAR.

The existing application startup still validates that an OpenAI API key is configured.
For mock-only testing without a real key, run the built JAR with a dummy value:
`java -jar target/ragbot.jar --rag.api-key=mock-only`. This allows mock requests;
AI chat still requires a valid key.

| GET endpoint | Optional query parameters | Response |
| --- | --- | --- |
| `/api/mock/users` | — | All demo users |
| `/api/mock/portfolios` | `id`, `username` | Matching portfolios |
| `/api/mock/portfolios/{id}` | `username` | One portfolio with cash, market value, total value and unrealized P&L |
| `/api/mock/issuers` | `id`, `username` | Matching issuers |
| `/api/mock/issuers/{id}` | `username` | One issuer with country, sector and symbol |
| `/api/mock/accounts` | `id`, `username` | Matching accounts |
| `/api/mock/accounts/{id}` | `username` | One account with cash, buying power and equity |
| `/api/mock/portfolios/{id}/positions` | `username` | Holdings with issuer IDs, quantities, costs, prices and unrealized P&L |
| `/api/mock/portfolios/{id}/transactions` | `username` | Settled BUY/SELL trades in chronological order |

Without filters, list endpoints return all fixtures. When both `id` and `username` are supplied,
both must match. Usernames are trimmed and case-insensitive; IDs are trimmed and case-sensitive.
Issuer filtering by username returns distinct issuers represented in that user's current holdings;
the same issuer can appear for multiple users. `username` is a test filter, not authentication.

| Username | Portfolio IDs | Account IDs | Issuer IDs in current holdings |
| --- | --- | --- | --- |
| `alice` | `pf-001` (USD), `pf-002` (HKD) | `acc-001`, `acc-002` | `iss-001`, `iss-002`, `iss-003` |
| `bob` | `pf-003` (USD) | `acc-003` | `iss-001`, `iss-004` |
| `charlie` | `pf-004` (USD, cash only) | `acc-004` | None |

Example requests after `pnpm dev` (also usable with `curl.exe` on Windows):

```sh
curl "http://localhost:3000/api/mock/users"
curl "http://localhost:3000/api/mock/portfolios?username=alice"
curl "http://localhost:3000/api/mock/portfolios/pf-001"
curl "http://localhost:3000/api/mock/portfolios?id=pf-002&username=alice"
curl "http://localhost:3000/api/mock/issuers?username=bob"
curl "http://localhost:3000/api/mock/issuers/iss-001?username=alice"
curl "http://localhost:3000/api/mock/accounts?username=bob"
curl "http://localhost:3000/api/mock/portfolios/pf-003/positions"
curl "http://localhost:3000/api/mock/portfolios/pf-003/transactions"
```

Unmatched list filters return `200` with `[]`. Unknown detail IDs or a username mismatch
on detail/holdings/trade endpoints return `404` with `{"error":"Portfolio not found"}`
(or `Issuer` / `Account`). Explicitly blank `id` or `username` filters return `400` with
an `error` message. Charlie's existing portfolio returns empty holdings and trades with `200`.
Buying power equals cash for these fixtures, including the demo margin account; leverage,
fees, bond accrued interest and FX conversion are not modeled.

Fixtures and calculations live in `MockFinanceService.java`; routes live in
`MockFinanceController.java`. Run `pnpm test:server` to verify filtering, error responses,
empty portfolios and financial consistency without making external requests.

### Portfolio editing and correction cards

Try `把 pf-001 改名为退休计划` as Alice. To exercise a duplicate, request
`把 pf-001 改名为 Hong Kong Income` while pf-002 still has that name. The agent reads the
portfolio version and calls the serial `update_portfolio` tool. The backend atomically checks
ownership, version and uniqueness before changing the name. Amounts and valuation `asOf` remain
unchanged; `version` and `updatedAt` track name edits. A name must contain 1–80 characters without
control characters. Within one user, stripped names are unique ignoring case; other users may
use the same name. Submitting an unchanged name returns `no_change` without increasing its version.

`PATCH /api/mock/portfolios/{id}?username=alice` accepts exactly
`{"name":"退休计划","expectedVersion":1}` and requires an `Idempotency-Key` header. Successful
responses contain `operationId`, `status` (`applied` or `no_change`), `portfolio` and a `changes`
map of before/after values. Replaying the same key and request returns its original receipt;
using that key with different arguments returns `IDEMPOTENCY_CONFLICT`.
`GET /api/mock/operations/{operationId}?username=alice` reads the user-scoped receipt, or returns
`status=not_found` when the original request can be replayed with the same key in this mock.

Errors retain the existing `error` string and HTTP `status`, with additional `code`, `field`,
`retryable` and `details` for edit failures. `DUPLICATE_VALUE`, `VALIDATION_ERROR` for names and
`VERSION_CONFLICT` create a persistent correction card. The user chooses a suggestion, enters a
custom name or cancels. Suggestions are not guaranteed to remain available; every submission
checks the backend again. Version conflicts display the latest name/version and require an explicit
choice before retrying. A corrected request receives a new operation ID.

Transport errors leave the operation journal in an `unknown` state. The agent/card checks the
receipt before replaying the exact original request/key. Another edit is blocked until this is
resolved. Cancelling verification does not claim rollback. A committed receipt is saved before
cache, history and event processing so subsequent failures cannot erase the business outcome.

Cards arrive as `user_input_required` NDJSON events. `done.reason=waiting_for_user` ends the
current request with `partial=false` and releases the user's run lock. The completed interaction
is recorded in history while the business task waits in `editing-workflow.json`; it is not a
successful mutation or a saved preference. Refreshing the page restores `inputRequest` from
`GET /api/agent/sessions/{id}`. Cards expire after 24 hours. Old, forged, foreign or already-used
answers are rejected; a duplicate click cannot apply another edit.

Submit a card through
`POST /api/agent/sessions/{sessionId}/input-requests/{requestId}/answers` with one of
`{"optionId":"name_1"}`, `{"text":"自定义名称"}` or `{"cancelled":true}`. The endpoint returns
an NDJSON stream. Name corrections execute the saved draft directly; general clarification
answers start a new model turn with the original intent, question and selected answer. The
question-creation tool returns `waiting_for_user`, not a fabricated user answer or an unmatched
provider tool call. This workflow uses ordinary function calling and needs no provider async-tool support.

`EditingWorkflowTest` exercises real HTTP writes, the agent loop, persistent questions, correction
resumption, lost-response recovery and immutable snapshots with a scripted model; no paid API is used.
