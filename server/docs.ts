import { readdir, readFile } from "node:fs/promises";
import { resolve } from "node:path";
import YAML from "yaml";

export type HelpDoc = {
  slug: string;
  title: string;
  summary: string;
  source: string;
  order: number;
  content: string;
};

function parseMarkdownFile(raw: string, fallbackSlug: string): HelpDoc {
  const frontmatter = raw.match(/^---\s*\r?\n([\s\S]*?)\r?\n---\s*\r?\n?/);
  const metadata = frontmatter ? (YAML.parse(frontmatter[1]) as Record<string, unknown>) : {};
  const content = frontmatter ? raw.slice(frontmatter[0].length).trim() : raw.trim();

  return {
    slug: String(metadata.slug ?? fallbackSlug),
    title: String(metadata.title ?? fallbackSlug),
    summary: String(metadata.summary ?? ""),
    source: String(metadata.source ?? ""),
    order: Number(metadata.order ?? 999),
    content,
  };
}

export async function loadHelpDocs(projectRoot: string): Promise<HelpDoc[]> {
  const docsDirectory = resolve(projectRoot, "docs");
  const filenames = (await readdir(docsDirectory)).filter((name) => name.endsWith(".md"));
  const docs = await Promise.all(
    filenames.map(async (filename) => {
      const raw = await readFile(resolve(docsDirectory, filename), "utf8");
      return parseMarkdownFile(raw, filename.replace(/\.md$/, ""));
    }),
  );

  return docs.sort((left, right) => left.order - right.order);
}
