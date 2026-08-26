import { resolve } from "node:path";
import express from "express";
import OpenAI from "openai";
import { loadConfig } from "./config.js";
import { loadHelpDocs } from "./docs.js";
import { loadRagIndex, searchRagIndex } from "./rag.js";

const projectRoot = resolve(import.meta.dirname, "..");
const config = loadConfig(projectRoot);
const openai = new OpenAI({
  apiKey: config.apiKey,
  timeout: 40_000,
  maxRetries: 0,
});
const ragIndex = loadRagIndex(projectRoot);
const helpDocs = await loadHelpDocs(projectRoot);
const app = express();

app.disable("x-powered-by");
app.use(express.json({ limit: "20kb" }));

app.get("/api/health", (_request, response) => {
  response.json({
    ok: true,
    documents: helpDocs.length,
    chunks: ragIndex.chunks.length,
    embeddingModel: ragIndex.embeddingModel,
    chatModel: config.chatModel,
  });
});

app.get("/api/docs", (_request, response) => {
  response.json(helpDocs);
});

app.post("/api/chat", async (request, response) => {
  const startedAt = Date.now();
  const controller = new AbortController();

  request.once("aborted", () => controller.abort());
  response.once("close", () => {
    if (!response.writableEnded) controller.abort();
  });

  const writeEvent = (event: Record<string, unknown>) => {
    if (!response.writableEnded && !response.destroyed) {
      response.write(`${JSON.stringify(event)}\n`);
    }
  };

  try {
    const message = typeof request.body?.message === "string" ? request.body.message.trim() : "";
    if (!message) {
      response.status(400).json({ error: "Message is required" });
      return;
    }
    if (message.length > 2000) {
      response.status(400).json({ error: "Message must be 2,000 characters or fewer" });
      return;
    }

    const history: Array<{ role: "user" | "assistant"; content: string }> = Array.isArray(
      request.body?.history,
    )
      ? request.body.history
          .slice(-6)
          .filter(
            (item: unknown): item is { role: "user" | "assistant"; content: string } =>
              typeof item === "object" &&
              item !== null &&
              (Reflect.get(item, "role") === "user" || Reflect.get(item, "role") === "assistant") &&
              typeof Reflect.get(item, "content") === "string",
          )
          .map((item: { role: "user" | "assistant"; content: string }) => ({
            role: item.role,
            content: item.content.slice(0, 2000),
          }))
      : [];

    response.status(200);
    response.setHeader("Content-Type", "application/x-ndjson; charset=utf-8");
    response.setHeader("Cache-Control", "no-cache, no-transform");
    response.setHeader("Connection", "keep-alive");
    response.setHeader("X-Accel-Buffering", "no");
    response.flushHeaders();
    writeEvent({ type: "status", message: "Searching the help documents…" });

    const retrievalStartedAt = Date.now();
    const matches = await searchRagIndex(openai, ragIndex, message, 5, controller.signal);
    console.info(`[chat] retrieval completed in ${Date.now() - retrievalStartedAt}ms`);
    const context = matches
      .map(
        (match, index) =>
          `[S${index + 1}] ${match.title} — ${match.section}\nSource: ${match.source}\n${match.content}`,
      )
      .join("\n\n---\n\n");
    const conversation = history.length
      ? history.map((item) => `${item.role === "user" ? "User" : "Assistant"}: ${item.content}`).join("\n")
      : "No earlier messages.";

    const sources = matches.map((match, index) => ({
      citation: `S${index + 1}`,
      title: match.title,
      section: match.section,
      source: match.source,
      score: Number(match.score.toFixed(4)),
    }));

    writeEvent({ type: "sources", sources });
    writeEvent({ type: "status", message: "Generating an answer…" });

    const stream = await openai.responses.create(
      {
        model: config.chatModel,
        store: false,
        stream: true,
        max_output_tokens: 1_200,
        reasoning: { effort: "minimal" },
        instructions: `You are a concise help-center assistant. Answer using only the supplied knowledge-base excerpts. Reply in the same language as the user's current question. Use concise GitHub-Flavored Markdown when it improves readability: short headings, bullet or numbered lists, bold text, inline code, and fenced code blocks. Cite supporting excerpts inline as [S1], [S2], and so on, and keep citation markers outside code formatting. If the excerpts do not contain enough information, say that the local help documents do not cover it. Never invent API behavior, URLs, or trading rules.`,
        input: `Knowledge-base excerpts:\n\n${context}\n\nRecent conversation:\n${conversation}\n\nCurrent user question:\n${message}`,
      },
      {
        signal: controller.signal,
        timeout: 35_000,
        maxRetries: 0,
      },
    );

    let receivedText = false;
    let completedText = "";
    for await (const event of stream) {
      if (event.type === "response.output_text.delta") {
        receivedText = true;
        writeEvent({ type: "delta", text: event.delta });
      } else if (event.type === "response.completed") {
        completedText = event.response.output_text;
      }
    }

    if (!receivedText && completedText) {
      receivedText = true;
      writeEvent({ type: "delta", text: completedText });
    }

    if (!receivedText) {
      throw new Error("The model returned no answer text");
    }

    writeEvent({ type: "done" });
    response.end();
    console.info(`[chat] response completed in ${Date.now() - startedAt}ms`);
  } catch (error) {
    const message = error instanceof Error ? error.message : "Unknown server error";
    const isTimeout = error instanceof OpenAI.APIConnectionTimeoutError;
    const publicMessage = isTimeout
      ? "The AI service timed out. Please try again."
      : "The AI request failed. Please try again or check the server configuration.";

    console.error(
      `[chat] request failed after ${Date.now() - startedAt}ms (${error instanceof Error ? error.name : "Error"}):`,
      message,
    );

    if (controller.signal.aborted || response.destroyed) return;

    if (response.headersSent) {
      writeEvent({ type: "error", error: publicMessage });
      response.end();
    } else {
      response.status(isTimeout ? 504 : 500).json({ error: publicMessage });
    }
  }
});

if (process.env.NODE_ENV === "production") {
  const clientDirectory = resolve(projectRoot, "dist");
  app.use(express.static(clientDirectory));
  app.use((request, response, next) => {
    if (request.method === "GET" && !request.path.startsWith("/api/")) {
      response.sendFile(resolve(clientDirectory, "index.html"));
      return;
    }
    next();
  });
} else {
  const { createServer: createViteServer } = await import("vite");
  const vite = await createViteServer({
    root: projectRoot,
    server: { middlewareMode: true },
    appType: "spa",
  });
  app.use(vite.middlewares);
}

app.listen(config.port, () => {
  console.log(`Help Center running at http://localhost:${config.port}`);
  console.log(`Loaded ${helpDocs.length} documents and ${ragIndex.chunks.length} RAG chunks.`);
});
