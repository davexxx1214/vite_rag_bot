import assert from 'node:assert/strict';
import test from 'node:test';
import { canVisualize, visualDraft } from '../src/visualAnswer.ts';
import { renderAnswer, TRUSTED_RUNTIME } from '../src/visualization/renderer.generated.js';

const body = html => html.replace(/<textarea\b[^>]*>[\s\S]*?<\/textarea>/g, '')
  .replace(/<style\b[^>]*>[\s\S]*?<\/style>/g, '');

test('skip short, empty, pending and oversized answers; offer tables and diagrams', () => {
  assert.equal(canVisualize('余额为 100 美元。'), false);
  assert.equal(canVisualize(''), false);
  assert.equal(canVisualize('x'.repeat(300), true), false);
  assert.equal(canVisualize('x'.repeat(60_001)), false);
  assert.equal(canVisualize('| 币种 | 市值 |\n| --- | --- |\n| USD | 100 |'), true);
  assert.equal(canVisualize('```flow\n用户 -> Bot\n```'), true);
});

test('real renderer preserves money, currency, snapshots, source markers and prose', () => {
  const content = '### 结论\n两个组合按币种分别比较。\n### 明细\n'
    + '| 组合 | USD | HKD |\n| --- | --- | --- |\n| pf-001 | $12,345.67 | — |\n| pf-002 | — | HK$50,000 |\n'
    + '快照：2026-10-09。[B1] [S2]';
  const html = body(renderAnswer(visualDraft(content, '比较我的投资组合')));
  for (const text of ['两个组合按币种分别比较。', '$12,345.67', 'HK$50,000', '2026-10-09', '[B1]', '[S2]'])
    assert.ok(html.includes(text), text);
  assert.equal((html.match(/<section class="am-panel/g) || []).length, 2);
});

test('flow and sequence produce SVG; missing browser files are never read', () => {
  for (const lang of ['flow LR', 'sequence']) {
    const html = body(renderAnswer(visualDraft(`## 流程\n\`\`\`${lang}\n用户 -> Bot: 请求\nBot -> 用户: 回答\n\`\`\``, '流程')));
    assert.match(html, /<figure class="am-diagram/);
    assert.match(html, /<svg/);
  }
  assert.throws(() => renderAnswer(visualDraft('```ts src=config.yaml\n```', '代码')), /File references/);
  const image = renderAnswer(visualDraft('![样例](/server/private.png)', '图片'));
  assert.ok(image.includes('/server/private.png')); // browser adapter does not read or inline it
});

test('draft preparation preserves code and cannot treat an answer as configuration', () => {
  const content = '---\nstyle: strict\n---\n**结论**\n这是一份回答。\n```md\n# Example\n## Not a panel\n```';
  const draft = visualDraft(content, 'Question "with"\nnewline');
  assert.ok(draft.startsWith('---\ntitle: "Question with newline"'));
  assert.ok(draft.includes('## 结论\n'));
  assert.ok(draft.includes('```md\n# Example\n## Not a panel\n```'));
  assert.doesNotThrow(() => renderAnswer(draft));
  assert.equal(body(renderAnswer(visualDraft('A plain paragraph.', 'Question'))).includes('A plain paragraph.'), true);
});

test('bad components fail independently; code remains literal; generated page has no runtime scripts', () => {
  assert.throws(() => renderAnswer(visualDraft('## 流程\n```sequence\ninvalid line\n```', '流程')), /cannot parse/);
  const html = renderAnswer(visualDraft('```js\nconst x = "<script>alert(1)</script>";\n```', '代码'));
  assert.ok(body(html).includes('&lt;script&gt;'));
  assert.ok(!html.includes('<script>'));
  assert.ok(TRUSTED_RUNTIME.includes("select[data-am="));
  assert.doesNotMatch(html.match(/<style>([\s\S]*?)<\/style>/)[1], /<\w+/);
  assert.throws(() => renderAnswer('x'.repeat(64_001)), /too long/);
});
