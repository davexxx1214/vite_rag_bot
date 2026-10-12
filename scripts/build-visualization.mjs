import { build } from 'esbuild';
import { readFile, mkdir } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { composeRuntime, composeRtlRuntime } from '../vendor/answer-me-with-html/src/runtime/compose.js';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const upstream = resolve(root, 'vendor/answer-me-with-html/src');
const require = createRequire(import.meta.url);
const read = file => readFile(resolve(upstream, file), 'utf8');
const runtimeFiles = ['page.js', 'math-text.js', 'reply-text.js', 'reply.js', 'remark-text.js',
  'remark.js', 'layout-plan.js', 'layout-dom.js', 'rtl-letter.js', 'reply-view.js'];
const runtime = Object.fromEntries(await Promise.all(runtimeFiles.map(async file => [file, await read(`runtime/${file}`)])));
const cssFiles = { BASE_CSS: 'base.css', DIFF_CSS: 'diff.css', DELTA_CSS: 'delta.css',
  RTL_CSS: 'rtl.css', UPDATE_CSS: 'update.css', VIDEO_CSS: 'video.css' };
const assets = Object.fromEntries(await Promise.all(Object.entries(cssFiles).map(async ([key, file]) => [key, await read(`themes/${file}`)])));
const dagreRequire = createRequire(require.resolve('@dagrejs/dagre'));
const licensePaths = [resolve(upstream, '../LICENSE'),
  ...['@dagrejs/dagre', 'marked', 'temml'].map(pkg => resolve(dirname(require.resolve(pkg)), '../LICENSE')),
  resolve(dirname(dagreRequire.resolve('@dagrejs/graphlib')), '../LICENSE')];
const notices = (await Promise.all(licensePaths.map(file => readFile(file, 'utf8')))).join('\n\n');
assets.TRUSTED_LICENSES = notices;
const font = (await readFile(require.resolve('temml/dist/Temml.woff2'))).toString('base64');
assets.MATH_CSS = (await readFile(require.resolve('temml/dist/Temml-Local.css'), 'utf8'))
  .replace("url('Temml.woff2')", `url('data:font/woff2;base64,${font}')`) + await read('themes/math.css');
// Upstream comments contain placeholders such as themes/<name>.js. DOMPurify
// correctly treats tag-shaped text in a style element as a mutation hazard.
// Normalize comments at build time while keeping the stylesheet unchanged.
for (const key of Object.keys(assets).filter(key => key.endsWith('_CSS'))) {
  assets[key] = assets[key].replace(/\/\*[\s\S]*?\*\//g, comment => comment.replace(/[<>]/g, ''));
}
// The UI adds this fixed runtime only after removing answer-supplied scripts.
assets.VERSION = '0.5.2';
for (const key of ['RUNTIME_JS', 'RTL_JS', 'DELTA_JS', 'UPDATE_JS', 'VIDEO_JS', 'VIDEO_EXPORT_JS', 'VIDEO_MUX_JS']) assets[key] = '';
assets.TRUSTED_RUNTIME = composeRuntime(file => runtime[file]) + composeRtlRuntime(file => runtime[file]) + await read('runtime/delta.js');

const codeAdapter = `
import { esc } from '../svg/text.js';
import { parseAttrs } from '../parse.js';
export class CodeError extends Error {}
export function renderCode(block) {
  const attrs = parseAttrs(block.args);
  if (attrs.src !== undefined) throw new CodeError('File references are unavailable in a browser preview. Paste the code instead.');
  const title = attrs.title || block.lang || 'Code';
  const lines = block.text.split('\\n').map(line => '<span class="am-ln">' + esc(line) + '</span>').join('\\n');
  return { html: '<figure class="am-codeblock"><figcaption>' + esc(String(title)) + '</figcaption><pre class="am-code"><code>' + lines + '</code></pre></figure>', warnings: [] };
}`;

await mkdir(resolve(root, 'src/visualization'), { recursive: true });
await build({
  absWorkingDir: root, entryPoints: ['src/visualization/browser-entry.js'],
  outfile: 'src/visualization/renderer.generated.js', bundle: true,
  platform: 'browser', format: 'esm', target: 'es2022', minify: true, legalComments: 'inline',
  banner: { js: `/*! Third-party notices\n${notices.replace(/\*\//g, '* /')}\n*/` },
  plugins: [{ name: 'answer-me-browser-adapters', setup(build) {
    build.onLoad({ filter: /vendor[/\\]answer-me-with-html[/\\]src[/\\]assets\.js$/ }, () => ({
      contents: Object.entries(assets).map(([key, value]) => `export const ${key} = ${JSON.stringify(value)};`).join('\n'), loader: 'js',
    }));
    build.onLoad({ filter: /vendor[/\\]answer-me-with-html[/\\]src[/\\]code\.js$/ }, () => ({
      contents: codeAdapter, resolveDir: resolve(upstream, 'components'), loader: 'js',
    }));
    build.onLoad({ filter: /vendor[/\\]answer-me-with-html[/\\]src[/\\]images\.js$/ }, () => ({
      contents: `export class ImageError extends Error {}\nexport const IMAGE_EXAMPLE = '';\nexport const inlineImages = html => html;`, loader: 'js',
    }));
    build.onLoad({ filter: /vendor[/\\]answer-me-with-html[/\\]src[/\\]themes[/\\]user\.js$/ }, () => ({
      contents: `export const readUserThemes = () => ({themes: [], broken: new Map()});\nexport const readThemeFile = () => {throw new Error('Only bundled themes are available');};`, loader: 'js',
    }));
  }}],
});
console.log('Built lazy Answer me with HTML browser renderer (0.5.2).');
