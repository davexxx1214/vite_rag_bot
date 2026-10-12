import { useEffect, useState } from 'react';
import { Alert, Box, Button, CircularProgress, Dialog, DialogActions, DialogContent, DialogTitle, Typography } from '@mui/material';
import DOMPurify from 'dompurify';
import { visualDraft } from './visualAnswer';
import { renderAnswer, TRUSTED_RUNTIME, TRUSTED_LICENSES } from './visualization/renderer.generated.js';
import type { ChatMessage } from './useAgentChat';

// Remove every answer-supplied script before adding the bundled runtime. The
// iframe receives no same-origin, navigation, form submission or popup grant.
export function previewDocument(html: string): string {
  const clean = DOMPurify.sanitize(html, {
    WHOLE_DOCUMENT: true, USE_PROFILES: { html: true, svg: true, svgFilters: true, mathMl: true },
    ADD_TAGS: ['style'],
    FORBID_TAGS: ['script', 'iframe', 'object', 'embed', 'base', 'meta', 'link', 'form'],
    FORBID_ATTR: ['srcdoc', 'action', 'formaction', 'target'],
  });
  const doc = new DOMParser().parseFromString(clean, 'text/html');
  const nonce = crypto.randomUUID().replace(/-/g, '');
  const csp = doc.createElement('meta');
  csp.httpEquiv = 'Content-Security-Policy';
  csp.content = `default-src 'none'; script-src 'nonce-${nonce}'; style-src 'unsafe-inline'; img-src data:; font-src data:; connect-src 'none'; form-action 'none'; base-uri 'none'`;
  doc.head.prepend(csp);
  const viewport = doc.createElement('meta');
  viewport.name = 'viewport'; viewport.content = 'width=device-width, initial-scale=1';
  doc.head.append(viewport);
  // Skill-specific reply/annotation controls do not submit to this chat.
  doc.querySelectorAll('[data-am="reply"], [data-am="remark"], [data-am="copy"], .am-colophon')
    .forEach(element => element.remove());
  doc.querySelectorAll('a').forEach(anchor => anchor.removeAttribute('href'));
  const script = doc.createElement('script');
  script.setAttribute('nonce', nonce);
  script.textContent = TRUSTED_RUNTIME.replace(/<\/script/gi, '<\\/script');
  doc.body.append(script);
  return '<!doctype html>\n<!-- Third-party notices\n' + TRUSTED_LICENSES.replace(/-->/g, '-- >')
    + '\n-->\n' + doc.documentElement.outerHTML;
}

export default function VisualAnswerDialog({ message, question, onClose }: {
  message: ChatMessage; question: string; onClose: () => void;
}) {
  const [html, setHtml] = useState('');
  const [error, setError] = useState('');
  useEffect(() => {
    try { setError(''); setHtml(previewDocument(renderAnswer(visualDraft(message.content, question)))); }
    catch { setHtml(''); setError('暂时无法生成可视化页面，完整文字回答仍保留在聊天中。'); }
  }, [message.content, question]);

  const download = () => {
    const url = URL.createObjectURL(new Blob([html], { type: 'text/html;charset=utf-8' }));
    const anchor = document.createElement('a'); anchor.href = url; anchor.download = 'ssbot-answer.html'; anchor.click();
    window.setTimeout(() => URL.revokeObjectURL(url), 1000);
  };
  return <Dialog open onClose={onClose} fullWidth maxWidth="xl" aria-labelledby="visual-answer-title">
    <DialogTitle id="visual-answer-title" sx={{ pb: 1 }}>可视化回答</DialogTitle>
    <DialogContent sx={{ px: { xs: 1, sm: 3 }, pb: 1 }}>
      <Typography sx={{ mb: 1.5, color: 'text.secondary', fontSize: 12 }}>同一份回答的图文视图 · 金额、快照日期及来源以原回答为准</Typography>
      {message.partial && <Alert severity="info" sx={{ mb: 1 }}>这是未完成回答的可视化预览。</Alert>}
      {error ? <Alert severity="info">{error}</Alert> : html
        ? <Box component="iframe" title="可视化回答内容" sandbox="allow-scripts" referrerPolicy="no-referrer" srcDoc={html}
          sx={{ display: 'block', width: '100%', height: 'min(72vh, 860px)', minHeight: 340, border: '1px solid #dfe4dd', borderRadius: 1.5, bgcolor: '#fff' }} />
        : <Box sx={{ p: 5, textAlign: 'center' }}><CircularProgress size={24} /></Box>}
      {!!message.sources?.length && <Box sx={{ mt: 1.5, display: 'flex', flexWrap: 'wrap', gap: 1 }}>
        {message.sources.map(source => <Button key={source.citation} size="small" href={source.source} target="_blank" rel="noopener noreferrer">
          [{source.citation}] {source.title}
        </Button>)}
      </Box>}
    </DialogContent>
    <DialogActions><Button onClick={download} disabled={!html}>下载 HTML</Button><Button onClick={onClose}>返回聊天</Button></DialogActions>
  </Dialog>;
}
