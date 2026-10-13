import assert from 'node:assert/strict';
import test from 'node:test';
import { inlineVisualSpec } from '../src/inlineVisual.ts';
import { renderInlineBlock } from '../src/visualization/renderer.generated.js';

test('wait for a matching closing fence and preserve ordinary code', () => {
  assert.deepEqual(inlineVisualSpec('```flow LR\n用户 -> Bot\n```\n', 'flow LR'), {kind:'flow', args:'LR'});
  assert.equal(inlineVisualSpec('```flow\nA -> B', 'flow'), null);
  assert.equal(inlineVisualSpec('````flow\nA -> B\n```', 'flow'), null);
  assert.equal(inlineVisualSpec('```flow\nA -> B\n~~~', 'flow'), null);
  assert.equal(inlineVisualSpec('```js\nconst x=1\n```', 'js'), null);
  assert.equal(inlineVisualSpec('```html\n<script>alert(1)</script>\n```', 'html'), null);
  assert.deepEqual(inlineVisualSpec('~~~sequence\nA -> B: 请求\n~~~', 'sequence'), {kind:'sequence', args:''});
  assert.equal(inlineVisualSpec('```flow\n' + 'x'.repeat(16_000) + '\n```', 'flow'), null);
});

test('inline diagrams use the real renderer and retain numeric and source labels', () => {
  for (const kind of ['flow', 'sequence']) {
    const html = renderInlineBlock(kind, '用户 -> Bot: 查询 [B1]\nBot -> 用户: USD 1,850');
    assert.match(html, /<figure class="am-diagram/);
    assert.match(html, /<svg/);
    assert.ok(html.includes('USD 1,850') && html.includes('[B1]'));
  }
  const card = renderInlineBlock('callout', '按币种分别比较。[B2]', 'warn 注意');
  assert.match(card, /am-callout/);
  assert.ok(card.includes('按币种分别比较。'));
  assert.ok(renderInlineBlock('limits', 'USD | 1850 / 5000 | USD').includes('am-lim'));
});

test('unsupported and malformed blocks cannot escape into executable markup', () => {
  assert.throws(() => renderInlineBlock('html', '<script>alert(1)</script>'), /Unsupported/);
  assert.throws(() => renderInlineBlock('sequence', 'not a relationship'), /cannot parse/);
  assert.throws(() => renderInlineBlock('flow', 'A -> B', 'LR\n## Injected'), /Unsupported/);
  const html = renderInlineBlock('callout', '```html\n<script>alert(1)</script>\n```', 'info Code');
  assert.ok(!html.includes('<script>alert(1)</script>'));
});
