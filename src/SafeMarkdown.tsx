import { memo, useLayoutEffect, useMemo, useRef } from "react";
import { Box } from "@mui/material";
import DOMPurify from "dompurify";
import { marked } from "marked";
import { updateMarkdown } from "./markdownMotion";
import { inlineVisualSpec } from "./inlineVisual";

marked.setOptions({ gfm: true, breaks: true });

export const SafeMarkdown = memo(function SafeMarkdown({ content, variant, streaming = false }:
  { content: string; variant: "doc" | "message"; streaming?: boolean }) {
  const rootRef = useRef<HTMLDivElement>(null);
  const rendered = useRef<string | null>(null);
  const html = useMemo(() => {
    const renderer = new marked.Renderer();
    const code = renderer.code.bind(renderer);
    if (variant === "message") renderer.code = token => {
      const spec = inlineVisualSpec(token.raw, token.lang || "");
      if (!spec) return code(token);
      // Arguments are emitted as attribute text, never executable markup.
      const args = spec.args.replace(/&/g, "&amp;").replace(/"/g, "&quot;")
        .replace(/</g, "&lt;").replace(/>/g, "&gt;");
      return `<div class="inline-visual" data-inline-kind="${spec.kind}" data-inline-args="${args}" data-theme="shadcn" data-mode="light">${code(token)}</div>`;
    };
    return DOMPurify.sanitize(marked.parse(content, { async: false, renderer }) as string,
      { USE_PROFILES: { html: true } });
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
    data-streaming={streaming || undefined} />;
});
