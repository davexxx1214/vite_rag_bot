# Local RAG Help Center

A lightweight local RAG demonstration with a React + MUI + Vite frontend and a Spring Boot 4 / Java 21 backend. Help documents are stored as Markdown, embedded into a generated JSON file, searched in Java memory, and supplied to an OpenAI model as grounded context.

The backend is a standard Maven project. Spring Boot provides the HTTP API, configuration, validation, static-file hosting, and an executable production JAR. Vector search stays deliberately small: the generated JSON index is loaded into memory and searched with cosine similarity, without a vector database or a Node.js server runtime.

Chat responses use an NDJSON stream: the browser receives retrieval status and sources first, then renders model text as it arrives. Assistant messages support GitHub-Flavored Markdown and are sanitized with DOMPurify before rendering. The OpenAI API key remains in the Java process and is never exposed to Vite.

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
- `src/main/java/`: Spring Boot API, RAG retrieval, OpenAI client, and index builder.
- `src/main/resources/`: Spring Boot configuration.
- `src/test/java/`: Maven/JUnit backend tests.
- `src/`: React + MUI Help Center and Markdown-enabled chat UI.
- `pom.xml`, `mvnw`, `.mvn/`: Maven build and pinned wrapper.

The bundled Alpaca pages are concise adaptations for demonstration and link to their original documentation.

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
