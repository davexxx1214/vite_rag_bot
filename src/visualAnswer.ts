// This module stays light; the renderer loads only when the reader clicks.
// Draft preparation never changes the stored Markdown answer.
export function canVisualize(content: string, pending = false): boolean {
  if (pending || content.length > 60_000 || !content.trim()) return false;
  return content.length >= 240 || /^\s*\|.+\|\s*$/m.test(content)
    || /^```(?:flow|sequence|tree|timeline|limits|kv|callout|er)\b/m.test(content);
}

export function visualDraft(content: string, question: string): string {
  // An answer's initial --- may be a horizontal rule, never configuration.
  const title = question.replace(/[\r\n\t]+/g, ' ').replace(/["\\]/g, '').trim().slice(0, 90) || '回答概览';
  let inFence: string | null = null;
  let headingLevel = 7;
  // Models often start at ###. Promote only their outermost headings to panels.
  for (const line of content.replace(/\r\n?/g, '\n').split('\n')) {
    const fence = line.match(/^\s*(`{3,}|~{3,})/);
    if (fence) {
      if (!inFence) inFence = fence[1];
      else if (fence[1][0] === inFence[0] && fence[1].length >= inFence.length) inFence = null;
    } else if (!inFence) {
      const heading = line.match(/^(#{1,6}) /);
      if (heading) headingLevel = Math.min(headingLevel, heading[1].length);
    }
  }
  inFence = null;
  let panels = 0;
  const lines = content.replace(/\r\n?/g, '\n').split('\n').map(line => {
    const fence = line.match(/^\s*(`{3,}|~{3,})/);
    if (fence) {
      if (!inFence) inFence = fence[1];
      else if (fence[1][0] === inFence[0] && fence[1].length >= inFence.length) inFence = null;
      return line;
    }
    const heading = !inFence && line.match(/^(#{1,6}) /);
    if (heading && heading[1].length === headingLevel) {
      panels++;
      return line.replace(/^#{1,6} /, '## ');
    }
    if (!inFence && /^\*\*[^*]+\*\*[:：]?\s*$/.test(line.trim())) {
      panels++;
      return '## ' + line.trim().replace(/^\*\*|\*\*[:：]?\s*$/g, '');
    }
    return line;
  });
  if (!panels) lines.unshift('## 回答');
  else if (!/^## /.test(lines.find(line => line.trim()) || '')) lines.unshift('## 概览');
  return `---\ntitle: "${title}"\ntheme: shadcn\n---\n${lines.join('\n')}`;
}
