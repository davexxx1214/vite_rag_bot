import { renderDoc } from '../../vendor/answer-me-with-html/src/render.js';
import { useTemml } from '../../vendor/answer-me-with-html/src/math.js';
import { TRUSTED_RUNTIME, TRUSTED_LICENSES } from '../../vendor/answer-me-with-html/src/assets.js';
import temml from 'temml';

useTemml(temml);
export { TRUSTED_RUNTIME, TRUSTED_LICENSES };
export function renderAnswer(draft) {
  if (draft.length > 64_000) throw new Error('This answer is too long for a single page.');
  return renderDoc(draft, { style: 'off', mode: 'light', template: 'sheet', cols: 2 }).html
    .replace(/<script>\s*<\/script>/g, '');
}

export function renderInlineBlock(kind, text, args = '') {
  if (!['flow', 'sequence', 'er', 'tree', 'timeline', 'limits', 'kv', 'callout', 'annot'].includes(kind)
      || text.length > 16_000 || /[\r\n]/.test(args)) throw new Error('Unsupported visual block');
  // A longer delimiter keeps embedded backticks inside the one component.
  const fence = '`'.repeat(Math.max(3, ...[...text.matchAll(/`+/g)].map(match => match[0].length + 1)));
  return renderAnswer(`---\ntheme: shadcn\n---\n## 图表\n${fence}${kind} ${args}\n${text}\n${fence}`);
}
