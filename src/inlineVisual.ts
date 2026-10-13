export const inlineVisualKinds = new Set([
  'flow', 'sequence', 'er', 'tree', 'timeline', 'limits', 'kv', 'callout', 'annot',
]);

// A streamed code token can be syntactically valid Markdown before its closing
// fence arrives. Render only closed, bounded visual blocks; keep ordinary code.
export function inlineVisualSpec(raw: string, language: string): { kind: string; args: string } | null {
  if (raw.length > 16_000) return null;
  const [kind, ...args] = language.trim().split(/\s+/);
  if (!inlineVisualKinds.has(kind)) return null;
  const opening = raw.match(/^ {0,3}(`{3,}|~{3,})[^\n]*\n/);
  const closing = raw.match(/\n {0,3}(`{3,}|~{3,})[ \t]*(?:\r?\n)*$/);
  if (!opening || !closing || opening[1][0] !== closing[1][0]
      || closing[1].length < opening[1].length) return null;
  return { kind, args: args.join(' ') };
}
