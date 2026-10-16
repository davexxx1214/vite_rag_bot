import { memo, useLayoutEffect, useMemo, useRef } from "react";
import { Box } from "@mui/material";
import DOMPurify from "dompurify";
import { marked } from "marked";
import { updateMarkdown } from "./markdownMotion";
import { inlineVisualSpec } from "./inlineVisual";

marked.setOptions({ gfm: true, breaks: true });

function escapeHtml(text: string): string {
  return text.replace(/&/g, "&amp;").replace(/"/g, "&quot;")
    .replace(/</g, "&lt;").replace(/>/g, "&gt;");
}

export const SafeMarkdown = memo(function SafeMarkdown({ content, variant, streaming = false }:
  { content: string; variant: "doc" | "message"; streaming?: boolean }) {
  const rootRef = useRef<HTMLDivElement>(null);
  const rendered = useRef<string | null>(null);
  const html = useMemo(() => {
    const renderer = new marked.Renderer();
    if (variant === "message") {
      const table = renderer.table.bind(renderer);
      renderer.table = token => `<div class="markdown-table-scroll" tabindex="0" role="region" aria-label="表格">${table(token)}</div>`;
    }
    const code = renderer.code.bind(renderer);
    if (variant === "message") renderer.code = token => {
      const spec = inlineVisualSpec(token.raw, token.lang || "");
      if (!spec) {
        const lang = escapeHtml((token.lang || "").trim().split(/\s+/)[0] || "code").toLowerCase();
        return `<div class="cds-codeblock"><div class="cds-codeblock-header"><span class="cds-codeblock-lang"><span class="cds-codeblock-glyph" aria-hidden="true">&lt;/&gt;</span><span>${lang}</span></span><button type="button" class="cds-code-copy" data-copy-code="1" aria-label="复制代码">Copy code</button></div>${code(token)}</div>`;
      }
      // Arguments are emitted as attribute text, never executable markup.
      const args = escapeHtml(spec.args);
      return `<div class="inline-visual" data-inline-kind="${spec.kind}" data-inline-args="${args}" data-theme="shadcn" data-mode="light">${code(token)}</div>`;
    };
    return DOMPurify.sanitize(marked.parse(content, { async: false, renderer }) as string,
      { USE_PROFILES: { html: true }, ADD_ATTR: ["data-copy-code"] });
  }, [content, variant]);

  useLayoutEffect(() => {
    const root = rootRef.current;
    if (!root) return;
    if (rendered.current !== html) {
      updateMarkdown(root, html, variant === "message" && streaming);
      rendered.current = html;
    }
    if (variant !== "message" || !root.querySelector('[data-inline-kind]')) return;
    let cancelled = false;
    void import('./inlineVisuals').then(module => {
      if (!cancelled) module.renderInlineVisuals(root);
    }).catch(() => { /* Keep the original code if the renderer cannot load. */ });
    return () => { cancelled = true; };
  }, [html, streaming, variant]);

  return <Box ref={rootRef} className={`markdown markdown-${variant}`}
    onClick={event => {
      const target = event.target instanceof HTMLElement ? event.target.closest<HTMLButtonElement>("[data-copy-code]") : null;
      if (!target) return;
      const codeText = target.closest(".cds-codeblock")?.querySelector("pre code")?.textContent ?? "";
      if (!codeText) return;
      void navigator.clipboard?.writeText(codeText).catch(() => undefined);
      target.dataset.copied = "1";
      target.textContent = "✓ Copied";
      window.setTimeout(() => {
        if (!target.isConnected) return;
        delete target.dataset.copied;
        target.textContent = "Copy code";
      }, 2200);
    }}
    data-streaming={streaming || undefined} />;
});
