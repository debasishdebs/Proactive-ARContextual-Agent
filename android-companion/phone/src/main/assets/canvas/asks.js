// Ask debug: the list of asks, and one ask's retrieval hits, composed prompt (text only), template check and replay.
import { h, clear, btn } from './dom.js';
import { photoThumb } from './photos.js';
import { when } from './format.js';

const enc = encodeURIComponent;
export const REDACTED_TEXT = 'This ask was redacted, so it has no debug view.';
export const DEADLINE_TEXT = 'The replay took too long and was stopped. Nothing was saved. Try again.';
export const RETRIEVAL_NOTE = 'Re-retrieved as of the ask\'s time; may differ if settings or embeddings changed.';
const score = (n) => (typeof n === 'number' ? n.toFixed(3) : '–');

export async function asksView(ctx, root) {
  const list = await ctx.api('GET', '/api/asks');
  clear(root);
  root.appendChild(h('h2', null, 'Asks'));
  if (!list.length) root.appendChild(h('p', { class: 'muted' }, 'No asks yet.'));
  for (const a of list) {
    root.appendChild(h('section', { class: 'card' },
      h('p', { class: 'muted' }, when(a.ts) + (a.rating ? ' · rating ' + a.rating : '')),
      a.redacted ? h('p', null, '(redacted)') : h('div', null, h('strong', null, a.question), h('p', null, a.answer)),
      h('a', { class: 'btn', href: '#/ask/' + enc(a.id) }, 'Open')));
  }
}

function hitRow(ctx, x) {
  return h('tr', null,
    h('td', null, x.frameId ? photoThumb(ctx, x.frameId, { lazy: true }) : null, h('div', null, x.text)),
    h('td', null, score(x.lexical)), h('td', null, score(x.semantic)), h('td', null, score(x.fused)), h('td', null, score(x.score)),
    h('td', null, x.cited ? h('span', { class: 'tag live' }, 'cited') : null, ' ', x.inPrompt ? h('span', { class: 'tag' }, 'in prompt') : null));
}

export async function askView(ctx, root, id) {
  let a;
  try { a = await ctx.api('GET', '/api/asks/' + enc(id)); } catch (e) {
    if (e.status !== 409) throw e;
    clear(root);
    root.appendChild(h('h2', null, 'Ask'));
    root.appendChild(h('section', { class: 'card' }, h('p', null, REDACTED_TEXT), h('a', { href: '#/asks' }, 'Back to the asks')));
    return;
  }
  clear(root);
  root.appendChild(h('h2', null, 'Ask'));
  root.appendChild(h('p', null, h('a', { href: '#/asks' }, 'All asks')));
  root.appendChild(h('section', { class: 'card' },
    h('p', { class: 'muted' }, when(a.ts) + ' · ' + a.mode + (a.fallback ? ' · fallback' : '') + (a.rating ? ' · rating ' + a.rating : '')),
    h('strong', null, a.question), h('p', null, a.answer)));
  root.appendChild(h('section', { class: 'card' }, h('h3', null, 'Retrieved memories'), h('p', { class: 'muted' }, RETRIEVAL_NOTE),
    h('table', { class: 'hits' }, h('thead', null, h('tr', null, ['Memory', 'Lexical', 'Semantic', 'Fused', 'Score', ''].map((t) => h('th', null, t)))),
      h('tbody', null, (a.hits || []).map((x) => hitRow(ctx, x))))));
  const prompt = h('textarea', { rows: 10, readonly: true, class: 'mono', 'aria-label': 'Composed prompt', autocomplete: 'off', spellcheck: 'false' });
  prompt.value = a.composedPrompt || '';
  root.appendChild(h('section', { class: 'card' }, h('h3', null, 'Composed prompt'), prompt));

  const tpl = h('textarea', { rows: 10, spellcheck: 'false', autocomplete: 'off', class: 'mono', 'aria-label': 'Answer template' });
  tpl.value = a.activeTemplate || '';
  const checkOut = h('div', { role: 'status' });
  const result = h('div', null);
  const check = async () => {
    try {
      const r = await ctx.api('POST', '/api/check-prompt', { nodeType: 'ask.answer', template: tpl.value });
      clear(checkOut);
      checkOut.appendChild((r.problems || []).length ? h('ul', { class: 'problems' }, r.problems.map((p) => h('li', null, p))) : h('p', { class: 'ok' }, 'The template fits.'));
    } catch (e) { ctx.showError(e); }
  };
  const replay = () => ctx.modal('Replay this ask?', h('p', null, 'This makes 1 model call with the template as it is now. Nothing is saved.'), [{ label: 'Replay', cls: 'btn primary', run: async (close) => {
    close();
    try {
      const r = await ctx.api('POST', '/api/asks/' + enc(id) + '/replay', { template: tpl.value });
      clear(result);
      result.appendChild(h('div', { class: 'card' }, h('strong', null, 'Replay'), h('p', null, r.answer),
        h('p', { class: 'muted' }, (r.model || '') + ' · ' + r.latencyMs + ' ms · tokens ' + r.promptTokens + '/' + r.completionTokens + ' · sources ' + (r.sources || []).join(', '))));
    } catch (e) { ctx.showError(e.status === 503 ? { message: DEADLINE_TEXT } : e); }
  } }]);
  root.appendChild(h('section', { class: 'card' }, h('h3', null, 'Answer template (v' + a.activeVersion + ')'), tpl,
    h('div', { class: 'row wrap' }, btn('Check', check), btn('Replay', replay, 'btn primary')), checkOut, result));
}
