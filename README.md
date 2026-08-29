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
