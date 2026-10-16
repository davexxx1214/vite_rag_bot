import assert from 'node:assert/strict';
import test from 'node:test';
import { mergeEditResult, withEditResults } from '../src/editResultViews.ts';
import { renderInlineBlock } from '../src/visualization/renderer.generated.js';

test('verified results survive model prose resets and repeated receipts do not duplicate views', () => {
  let views = mergeEditResult([], { operationId: 'op-1', markdown: '| 当前名称 | 退休计划 |' });
  views = mergeEditResult(views, { operationId: 'op-1', markdown: '| 当前名称 | 最新计划 |' });
  assert.equal(views.length, 1);
  assert.equal(withEditResults('', views), '| 当前名称 | 最新计划 |');
  assert.equal(withEditResults('已修改。', views), '已修改。\n\n| 当前名称 | 最新计划 |');
  views = mergeEditResult(views, { operationId: 'op-2', markdown: '第二个组合' });
  assert.match(withEditResults('', views), /最新计划[\s\S]+第二个组合/);
});
test('asset composition renders using the existing inline chart renderer with actual values', () => {
  const chart = renderInlineBlock('limits', '现金余额 | 1850 / 5000 | USD\n持仓市值 | 3150 / 5000 | USD');
  assert.match(chart, /am-lim/);
  for (const value of ['现金余额', '持仓市值', '1850', '3150', 'USD']) assert.ok(chart.includes(value));
});
