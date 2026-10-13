import DOMPurify from 'dompurify';
import { renderInlineBlock } from './visualization/renderer.generated.js';

const mounted = new WeakMap<HTMLElement, string>();
const labels: Record<string, string> = {
  flow: '流程图', sequence: '时序图', er: '关系图', tree: '结构图',
  timeline: '时间线', limits: '数值图表', kv: '信息卡片', callout: '提示卡片', annot: '标注',
};

// Shadow roots survive the streaming Markdown reconciler, which updates only
// light DOM. Each fence owns its styles and SVG IDs; none affect the chat UI.
export function renderInlineVisuals(root: HTMLElement): void {
  for (const host of root.querySelectorAll<HTMLElement>('[data-inline-kind]')) {
    const kind = host.dataset.inlineKind || '';
    const args = host.dataset.inlineArgs || '';
    const text = host.querySelector('code')?.textContent || '';
    const signature = JSON.stringify([kind, args, text]);
    host.setAttribute('aria-label', labels[kind] || '图表');
    if (mounted.get(host) === signature) continue;
    mounted.set(host, signature);
    try {
      const doc = new DOMParser().parseFromString(renderInlineBlock(kind, text, args), 'text/html');
      const body = doc.querySelector('.am-panel-body');
      const css = doc.querySelector('style')?.textContent;
      if (!body || !css) throw new Error('Empty visual block');
      const clean = DOMPurify.sanitize(body.innerHTML, {
        USE_PROFILES: { html: true, svg: true, svgFilters: true, mathMl: true },
        FORBID_TAGS: ['script', 'style', 'iframe', 'object', 'embed', 'form', 'img'],
        FORBID_ATTR: ['srcdoc', 'action', 'formaction'],
      });
      const style = document.createElement('style');
      const scopedCss = css
        .replace(/(?<![\w.-])html((?:\[[^\]]+\]|:lang\([^)]*\))*)/g,
          (_match, attributes: string) => attributes ? `:host(${attributes})` : ':host')
        .replace(/(?<![\w.-])body(?![\w-])|:root\b/g, ':host');
      style.textContent = scopedCss + `
        :host { display:block; min-width:0; max-width:100%; color:#344038; overflow-wrap:normal;
          font:13px/1.55 system-ui, "Microsoft YaHei", sans-serif; }
        .am-panel-body { padding:10px; min-width:0; }
        .am-diagram { margin:0; overflow-x:auto; }
        .am-diagram > svg { display:block; width:auto; max-width:none; height:auto; cursor:default; }
        .am-kv, .am-callout { margin:0; }
        .am-lim-ticks span { white-space:nowrap; }
        .am-lim-ticks span:last-child { transform:translateX(-100%); }
      `;
      const content = document.createElement('div');
      content.className = 'am-panel-body'; content.innerHTML = clean;
      content.querySelectorAll('a').forEach(anchor => {
        anchor.target = '_blank'; anchor.rel = 'noopener noreferrer';
      });
      const shadow = host.shadowRoot || host.attachShadow({ mode: 'open' });
      shadow.replaceChildren(style, content);
    } catch {
      // Restore the literal code if a previously rendered block changes to an
      // invalid draft. Failure affects this block only, never the prose answer.
      const shadow = host.shadowRoot;
      if (shadow) shadow.replaceChildren(document.createElement('slot'));
    }
  }
}
