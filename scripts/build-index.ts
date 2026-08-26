import { mkdir, writeFile } from "node:fs/promises";
import { resolve } from "node:path";
import OpenAI from "openai";
import { loadConfig } from "../server/config.js";
import { loadHelpDocs, type HelpDoc } from "../server/docs.js";

type ChunkDraft = {
  id: string;
  slug: string;
  title: string;
  section: string;
  source: string;
  content: string;
};

const projectRoot = resolve(import.meta.dirname, "..");
const config = loadConfig(projectRoot);
const openai = new OpenAI({ apiKey: config.apiKey });
const docs = await loadHelpDocs(projectRoot);

function splitLongText(text: string, maxCharacters = 1800): string[] {
  if (text.length <= maxCharacters) return [text];

  const paragraphs = text.split(/\n{2,}/);
  const chunks: string[] = [];
  let current = "";

  for (const paragraph of paragraphs) {
    if (current && current.length + paragraph.length + 2 > maxCharacters) {
      chunks.push(current.trim());
      current = "";
    }

    if (paragraph.length > maxCharacters) {
      for (let start = 0; start < paragraph.length; start += maxCharacters) {
        const part = paragraph.slice(start, start + maxCharacters).trim();
        if (part) chunks.push(part);
      }
    } else {
      current += `${current ? "\n\n" : ""}${paragraph}`;
    }
  }

  if (current.trim()) chunks.push(current.trim());
  return chunks;
}

function chunkDocument(doc: HelpDoc): ChunkDraft[] {
  const sections: Array<{ heading: string; content: string }> = [];
  let heading = "Overview";
  let buffer: string[] = [];

  const flush = () => {
    const content = buffer.join("\n").trim();
    if (content) sections.push({ heading, content });
    buffer = [];
  };

  for (const line of doc.content.split(/\r?\n/)) {
    const match = line.match(/^#{2,3}\s+(.+)$/);
    if (match) {
      flush();
      heading = match[1].trim();
    } else {
      buffer.push(line);
    }
  }
  flush();

  let chunkNumber = 0;
  return sections.flatMap((section) =>
    splitLongText(section.content).map((content) => {
      chunkNumber += 1;
      return {
        id: `${doc.slug}-${String(chunkNumber).padStart(2, "0")}`,
        slug: doc.slug,
        title: doc.title,
        section: section.heading,
        source: doc.source,
        content,
      };
    }),
  );
}

const chunkDrafts = docs.flatMap(chunkDocument);
if (!chunkDrafts.length) throw new Error("No document chunks were generated");

console.log(`Embedding ${chunkDrafts.length} chunks from ${docs.length} documents...`);
const embeddingResponse = await openai.embeddings.create({
  model: config.embeddingModel,
  input: chunkDrafts.map((chunk) => `${chunk.title}\n${chunk.section}\n${chunk.content}`),
  encoding_format: "float",
});

const chunks = chunkDrafts.map((chunk, index) => {
  const embedding = embeddingResponse.data[index]?.embedding;
  if (!embedding) throw new Error(`No embedding returned for chunk ${chunk.id}`);
  return { ...chunk, embedding };
});

const ragIndex = {
  version: 1,
  createdAt: new Date().toISOString(),
  embeddingModel: config.embeddingModel,
  dimensions: chunks[0].embedding.length,
  chunks,
};

const dataDirectory = resolve(projectRoot, "data");
await mkdir(dataDirectory, { recursive: true });
const outputPath = resolve(dataDirectory, "rag-index.json");
await writeFile(outputPath, `${JSON.stringify(ragIndex, null, 2)}\n`, "utf8");
console.log(`Wrote ${outputPath}`);
console.log(`Model: ${ragIndex.embeddingModel}; dimensions: ${ragIndex.dimensions}`);
