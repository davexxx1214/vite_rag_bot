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
