# Local Finance Agent

A lightweight financial assistant with a React + MUI + Vite frontend and a Spring Boot 4 / Java 21 backend. The agent searches local vectorized documents, calls financial mock APIs, and saves per-user memory and conversations as readable server files. Production still runs as a single Java JAR.

The backend is a standard Maven project. Spring Boot provides the HTTP API, configuration, validation, static-file hosting, and an executable production JAR. Vector search stays deliberately small: the generated JSON index is loaded into memory and searched with cosine similarity, without a vector database or a Node.js server runtime.

Chat responses use an NDJSON stream for tool activity, citations, context compression and answer text. Assistant messages support GitHub-Flavored Markdown and are sanitized with DOMPurify before rendering. The OpenAI API key remains in the Java process and is never exposed to Vite.

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
pnpm build:server    # Test and package the executable Spring Boot JAR
pnpm server          # Build and run only the Java service
pnpm build:index     # Rebuild the local vector index through Java
pnpm build           # Build frontend and Java service
```

## Project layout

- `docs/`: local Markdown knowledge base.
- `data/rag-index.json`: generated local vector index.
- `src/main/java/`: Spring Boot API, agent loop, tools, file memory, RAG retrieval, OpenAI client and index builder.
- `src/main/resources/`: Spring Boot configuration.
- `src/test/java/`: Maven/JUnit backend tests.
- `src/`: React + MUI Help Center and Markdown-enabled chat UI.
- `pom.xml`, `mvnw`, `.mvn/`: Maven build and pinned wrapper.

The bundled Alpaca pages are concise adaptations for demonstration and link to their original documentation.

## Agent v1

Open **Ask AI**, choose a demo user, and try:

- `列出我的 portfolios，并比较 pf-001 和 pf-002。` (Alice)
- `比较我持仓中的 iss-001 和 iss-004。` (Bob; issuer metadata only)
- `查询订单相关的帮助文档。`
- `记住我偏好用中文、简短地回答。`
- `我们上次讨论 pf-001 时说了什么？`

**New chat** creates a separate conversation while retaining that user's long-term memory.
The conversation selector restores server history, including after browser refresh or server restart.
**Memory** opens the user's Markdown memory for editing or clearing. **Stop response** cancels
the backend request and releases the user's write lock. Clearing memory removes the active
preferences; original conversation records and archived memory remain on disk.

The design follows a small Pi-style loop implemented directly in Java:

1. Load the current user's memory and the selected session's summary/recent history.
2. Send the available tool schemas to the OpenAI Responses API.
3. Execute the model's named calls, return each result with its `call_id`, and repeat.
4. Stream the final answer and atomically save the full turn, including tool records.

There are no subprocess agents, shell tools, extra Node service or database dependencies.
`AgentService` owns the loop; `ToolRegistry` defines schemas and handlers; `ContextManager`
owns compression/extraction; `MemoryStore` owns files. `FinanceApiClient` makes real HTTP
requests to this JAR's mock endpoints, automatically using its actual listening port.

| Tool | Purpose |
| --- | --- |
| `search_docs` | Embed a search query and retrieve excerpts from the local JSON vector index |
| `list_portfolios`, `get_portfolio` | Find/read the current user's portfolios |
| `get_positions`, `get_transactions`, `get_accounts` | Read holdings, trades and accounts |
| `list_issuers`, `get_issuer` | Read issuers visible through that user's holdings |
| `compare_portfolios` | Calculate monetary differences and allocation percentages in Java |
| `search_history` | Rank keyword/ID matches, page results, read surrounding turns or saved payload chunks |
| `remember`, `forget_memory` | Update a keyed, explicit durable preference |

All business tool requests inject the current user's username on the server. The model cannot
select another username or an arbitrary URL. Cross-user detail requests return tool errors.
Different currencies are compared separately; no FX rate or cross-currency difference is invented.
Issuer responses contain metadata, not financial statements or credit ratings.

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

Memory, metadata, summaries and completed archive files use atomic replacement. Requests for one user
are serialized; a competing request gets a conflict. A process lock prevents two JAR instances
from writing the same memory directory. This version is designed for one server instance and
modest conversation history. Keyword discovery streams compact indexes (including CJK bigrams);
it is local keyword retrieval, not semantic/vector memory search. At much larger scale a dedicated
index may be appropriate. Gzip history is streamed on demand; active raw history uses byte offsets.
Back up the entire memory directory, and keep it on a persistent volume when deploying.

Memory extraction runs after every three successful, unprocessed turns. It only considers explicit
user statements and saves stable facts/preferences. Financial snapshots stay in history and are
queried again when current data is needed. Explicit `remember` requests save immediately.

History maintenance runs hourly and before creating a conversation; busy users are skipped by
the scheduled job. Defaults are 30 days since last activity before archiving, 365 days retention,
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
Built-in window settings are 1,050,000 and 400,000 tokens respectively. The agent intentionally
uses a smaller working budget to control latency and cost:

| Setting | Default |
| --- | --- |
| Maximum estimated input | 32,000 tokens |
| Start summarizing history | 25,000 tokens |
| Compression target / recent history allowance | 12,000 / 6,000 tokens |
| Active long-term memory | 1,800 tokens |
| One tool result | 3,000 tokens |
| Maximum model output, including reasoning | 8,000 tokens |
| Model rounds / tool calls per round | 6 / 8 |
| Total request deadline | 240 seconds |

Input accounting includes instructions, memory, schemas and messages. It uses a conservative
UTF-8 byte estimate, not an exact tokenizer; actual Responses usage also triggers compression.
The effective input cap is the lesser of the configured working budget and the model window
minus output space and a 4,000-token reserve. Oversized active tool work is summarized only
between complete call/result groups. Encrypted reasoning is replayed within the active loop;
transport bytes are not counted as model tokens. Incomplete model responses are recorded as
failed turns, never as successful answers. The final round disables further tools.

The chat shows live execution steps, including the tool name, bounded query/resource details,
running/success/failure states and elapsed time. These are server execution events, not model
reasoning. Completed steps remain expandable in the current page; restored chats retain answers
and sources rather than replaying transient progress. Spring streaming and the browser allow
270 seconds so the agent's 240-second deadline can return its final response/error first.

Override `agent.context-window-tokens` when an API proxy exposes a smaller window. Other
settings are in `src/main/resources/application.yaml` and examples in `config.yaml.example`.
The provider must support Responses streaming, function calling, reasoning and encrypted
reasoning replay; query embeddings use the index's recorded embedding model.

Configuration is loaded at server startup. Restart after changing `api_key`, `chat_model`
or `openai_base_url`. `/api/health` checks the running application and local index; it does
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
NDJSON event types are `status`, `tool_start`, `tool_end`, `context_compacted`, `sources`,
`answer_reset`, `delta`, `error` and `done`. Intermediate text is reset when another tool round
starts so the UI displays the final answer. Only `done` marks a successful stream.

History offsets count **turns**, each with a user message and assistant reply. Message pages return
`offset`, `nextOffset`, `total`; session lists remain arrays. Search pages return `results`, `offset`,
`nextOffset`, `total`. The agent's `search_history` accepts nullable optional fields: `query`, `offset`,
`limit`, `sessionId`, `aroundTurn`, `window`, `payloadRef`, `payloadOffset`. Supply a query for
discovery, sessionId/aroundTurn for surrounding messages, or sessionId/payloadRef for payload
chunks. Follow returned offsets for more; the server enforces the tool token budget and retains
the requested anchor when trimming a window. Legacy memory PUT `{ "content": "..." }` replaces
USER.md and clears MEMORY.md; structured PUT preserves the separate editable sections.

### Adding business APIs and testing

Add a named tool with a strict schema and a handler in `ToolRegistry`. Extend `FinanceApiClient`
for the fixed API path if needed; keep identity injection in Java. No changes to the agent loop
or frontend are required. New result types should enforce budgets and provide citations.
The optional `agent.finance-base-url` / `FINANCE_API_BASE_URL` overrides the `/api/mock` base;
external implementations must honor the same current-user scope and response shapes.

`pnpm test:server` includes file restart/isolation, atomic writes, memory correction, context
failure recovery, plus HTTP integration tests that boot the actual Java service. A local simulated
OpenAI server exercises streaming, encrypted reasoning replay, tool errors, API scope, portfolio
math, Chinese excerpt limits, memory extraction/archiving, compression, cancellation and round
limits. These tests make no paid model or embedding requests.

## Financial mock API

The Java service provides read-only JSON endpoints under `/api/mock` for integration tests.
Fixtures are fixed, entirely fictional, and make no OpenAI calls or database requests.
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
