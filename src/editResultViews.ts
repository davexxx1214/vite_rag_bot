export type EditResultView = { operationId: string; markdown: string };

export function mergeEditResult(views: EditResultView[], incoming: EditResultView): EditResultView[] {
  const index = views.findIndex(view => view.operationId === incoming.operationId);
  if (index < 0) return [...views, incoming];
  return views.map((view, offset) => offset === index ? incoming : view);
}

// Model rounds may reset streamed prose; verified edit results belong to the
// entire run and remain visible. Persisted history already includes this suffix.
export function withEditResults(content: string, views: EditResultView[]): string {
  if (!views.length) return content;
  return [content, ...views.map(view => view.markdown)].filter(value => value.trim()).join("\n\n");
}
