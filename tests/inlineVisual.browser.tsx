import { createRoot } from 'react-dom/client';
import { flushSync } from 'react-dom';
import { SafeMarkdown } from '../src/SafeMarkdown';

const root = document.getElementById('root')!;
const react = createRoot(root);
const results: string[] = [];
const assert = (condition: unknown, message: string) => { if (!condition) throw new Error(message); };
const render = (content: string, streaming = true, variant: 'doc' | 'message' = 'message') =>
  flushSync(() => react.render(<SafeMarkdown content={content} streaming={streaming} variant={variant} />));
const waitFor = async (ready: () => boolean) => {
  const end = Date.now() + 8000;
  while (!ready()) {
    if (Date.now() > end) throw new Error('Timed out waiting for inline diagram');
    await new Promise(resolve => setTimeout(resolve, 25));
  }
};

try {
  render('余额 **USD 1,850**。[B1]\n\n| 币种 | 市值 |\n|---|---|\n| USD | 5,000 |', false);
  assert(root.querySelector('td')?.textContent === 'USD', 'Lost inline table');
  assert(!performance.getEntriesByType('resource').some(item => /inlineVisuals|renderer.generated/.test(item.name)), 'Loaded renderer for ordinary Markdown');
  results.push('Plain text and tables do not load the visual renderer');

  const intro = '查询步骤如下。[B1]\n\n';
  const flow = '```flow LR\n用户 -> Bot: 提问\nBot -> 查询工具: 用户范围\n查询工具 -> Bot: USD 1,850 [B1]\n```';
  render(intro + flow.slice(0, -3));
  assert(!root.querySelector('[data-inline-kind]'), 'Rendered an unfinished fence');
  results.push('Incomplete streamed fences remain literal code');

  render(intro + flow);
  await waitFor(() => !!root.querySelector('[data-inline-kind]')?.shadowRoot?.querySelector('svg'));
  const host = root.querySelector('[data-inline-kind]')!;
  const svg = host.shadowRoot!.querySelector('svg');
  assert(host.shadowRoot!.textContent?.includes('USD 1,850 [B1]'), 'Lost chart value or citation');
  assert(host.shadowRoot!.querySelector('style')?.textContent?.includes(':host([data-theme="shadcn"])'), 'Lost scoped theme');
  assert(getComputedStyle(host).getPropertyValue('--accent').trim(), 'Theme variables did not apply to the shadow host');
  results.push('Closed flow renders inline with scoped styles, numbers and citations');

  render(intro + flow + '\n\n继续说明：后续文字正常输出。');
  await new Promise(resolve => setTimeout(resolve, 80));
  assert(root.querySelector('[data-inline-kind]') === host, 'Replaced graph host during streaming');
  assert(host.shadowRoot!.querySelector('svg') === svg, 'Rebuilt unchanged SVG during streaming');
  assert(root.textContent?.includes('后续文字正常输出'), 'Blocked following prose');
  results.push('Streaming updates preserve the existing graph and display following prose');

  render(flow + '\n\n```sequence\ninvalid line\n```\n\n有效回答仍在。[S2]', false);
  await waitFor(() => !!root.querySelector('[data-inline-kind="flow"]')?.shadowRoot?.querySelector('svg'));
  await new Promise(resolve => setTimeout(resolve, 80));
  assert(!root.querySelector('[data-inline-kind="sequence"]')?.shadowRoot, 'Rendered malformed component');
  assert(root.querySelector('[data-inline-kind="sequence"] code')?.textContent?.includes('invalid line'), 'Lost literal fallback');
  assert(root.textContent?.includes('有效回答仍在。[S2]'), 'Lost prose after error');
  results.push('One malformed chart retains its source without affecting other charts or prose');

  render('```callout warn 币种提示\n按币种分别比较。[B2]\n<img src=x onerror="window.inlineXss=true">\n```', false);
  await waitFor(() => !!root.querySelector('[data-inline-kind]')?.shadowRoot?.querySelector('.am-callout'));
  const card = root.querySelector('[data-inline-kind]')!.shadowRoot!;
  assert(!card.querySelector('script,iframe,img,[onerror]'), 'Executable markup survived');
  assert(card.textContent?.includes('按币种分别比较。[B2]'), 'Lost card text');
  results.push('Cards render inline and untrusted active markup is removed');

  render(flow, false, 'doc');
  assert(!root.querySelector('[data-inline-kind]'), 'Changed document code rendering');
  render('', false);
  assert(root.textContent === '', 'Answer reset left stale content');
  results.push('Document code and answer resets keep their existing behavior');

  render(intro + flow + '\n\n```limits\n现金 | 1850 / 5000 | USD\n```\n\n```callout info 提示\n所有金额保留原币种。[B1]\n```', false);
  await waitFor(() => [...root.querySelectorAll('[data-inline-kind]')].every(element => !!element.shadowRoot));
  assert(root.scrollWidth <= root.clientWidth + 2, 'A chart overflowed the message width');
  results.push('Multiple visual blocks stay within a narrow chat bubble');
  (window as Window & {inlineVisualChecks?: unknown}).inlineVisualChecks = { passed: results.length, results };
} catch (error) {
  (window as Window & {inlineVisualChecks?: unknown}).inlineVisualChecks = { failed: (error as Error).message, results };
}
document.getElementById('results')!.textContent = JSON.stringify((window as Window & {inlineVisualChecks?: unknown}).inlineVisualChecks, null, 2);
