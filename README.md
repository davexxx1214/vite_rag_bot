# Local RAG Help Center

A minimal React + MUI + Vite + TypeScript + Node.js demonstration. Help documents are stored locally as Markdown, embedded into a generated JSON file, searched in Node memory, and supplied to an OpenAI model as grounded context.

Chat responses use an NDJSON stream: the browser receives retrieval status and sources first, then renders model text as it arrives. Assistant messages support GitHub-Flavored Markdown and are sanitized with DOMPurify before rendering. OpenAI requests have bounded server-side timeouts, and the browser cancels requests that exceed 45 seconds or when the chat panel is closed.

## Run locally

1. Copy `config.yaml.example` to `config.yaml`, then set `api_key: ...` in the local file.
2. Install dependencies with `pnpm install`.
3. Generate the vector index with `pnpm build:index`.
4. Start development mode with `pnpm dev`.
5. Open `http://localhost:3000`.

The key stays in the Node process. `config.yaml` is ignored by Git and is never served by Vite.

## Production build

```sh
pnpm build:all
NODE_ENV=production pnpm start
```

`build:all` regenerates `data/rag-index.json`, builds the Vite client, and compiles the Node server. Use `pnpm build` when the documents and existing vector index have not changed, avoiding an unnecessary embedding request.

## Project layout

- `docs/`: local Markdown knowledge base.
- `scripts/build-index.ts`: section-aware chunking and embedding generation.
- `data/rag-index.json`: generated local vector index.
- `server/`: Help document API, in-memory vector retrieval, and AI response endpoint.
- `src/`: React + MUI Help Center and Markdown-enabled chat UI.

The bundled Alpaca pages are concise adaptations for demonstration and link to their original documentation.
