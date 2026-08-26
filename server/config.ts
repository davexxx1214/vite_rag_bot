import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import YAML from "yaml";

export type AppConfig = {
  apiKey: string;
  chatModel: string;
  embeddingModel: string;
  port: number;
};

export function loadConfig(projectRoot: string): AppConfig {
  const configPath = resolve(projectRoot, "config.yaml");
  let fileConfig: Record<string, unknown> = {};

  try {
    fileConfig = YAML.parse(readFileSync(configPath, "utf8")) ?? {};
  } catch (error) {
    if (!process.env.OPENAI_API_KEY) {
      throw new Error(
        `Could not read config.yaml and OPENAI_API_KEY is not set: ${error instanceof Error ? error.message : String(error)}`,
      );
    }
  }

  const apiKey = String(process.env.OPENAI_API_KEY ?? fileConfig.api_key ?? "").trim();
  if (!apiKey) {
    throw new Error("Missing OpenAI API key. Set api_key in config.yaml or OPENAI_API_KEY.");
  }

  const configuredPort = Number(process.env.PORT ?? fileConfig.port ?? 3000);

  return {
    apiKey,
    chatModel: String(fileConfig.chat_model ?? "gpt-5-mini"),
    embeddingModel: String(fileConfig.embedding_model ?? "text-embedding-3-small"),
    port: Number.isFinite(configuredPort) ? configuredPort : 3000,
  };
}
