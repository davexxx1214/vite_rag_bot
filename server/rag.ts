import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import OpenAI from "openai";

export type RagChunk = {
  id: string;
  slug: string;
  title: string;
  section: string;
  source: string;
  content: string;
  embedding: number[];
};

export type RagIndex = {
  version: number;
  createdAt: string;
  embeddingModel: string;
  dimensions: number;
  chunks: RagChunk[];
};

export type RagMatch = Omit<RagChunk, "embedding"> & {
  score: number;
};

export function loadRagIndex(projectRoot: string): RagIndex {
  const indexPath = resolve(projectRoot, "data", "rag-index.json");

  try {
    const index = JSON.parse(readFileSync(indexPath, "utf8")) as RagIndex;
    if (!index.chunks?.length) throw new Error("index contains no chunks");
    return index;
  } catch (error) {
    throw new Error(
      `Could not load data/rag-index.json. Run npm run build:index first. ${
        error instanceof Error ? error.message : String(error)
      }`,
    );
  }
}

function cosineSimilarity(left: number[], right: number[]): number {
  if (left.length !== right.length) return -1;

  let dot = 0;
  let leftMagnitude = 0;
  let rightMagnitude = 0;

  for (let index = 0; index < left.length; index += 1) {
    dot += left[index] * right[index];
    leftMagnitude += left[index] * left[index];
    rightMagnitude += right[index] * right[index];
  }

  const denominator = Math.sqrt(leftMagnitude) * Math.sqrt(rightMagnitude);
  return denominator === 0 ? -1 : dot / denominator;
}

export async function searchRagIndex(
  openai: OpenAI,
  ragIndex: RagIndex,
  query: string,
  topK = 5,
  signal?: AbortSignal,
): Promise<RagMatch[]> {
  const response = await openai.embeddings.create(
    {
      model: ragIndex.embeddingModel,
      input: query,
      encoding_format: "float",
    },
    {
      signal,
      timeout: 12_000,
      maxRetries: 0,
    },
  );
  const queryEmbedding = response.data[0]?.embedding;
  if (!queryEmbedding) throw new Error("The embedding API returned no vector");

  return ragIndex.chunks
    .map(({ embedding, ...chunk }) => ({
      ...chunk,
      score: cosineSimilarity(queryEmbedding, embedding),
    }))
    .sort((left, right) => right.score - left.score)
    .slice(0, topK);
}
