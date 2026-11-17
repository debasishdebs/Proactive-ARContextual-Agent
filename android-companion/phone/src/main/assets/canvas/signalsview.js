// Signals: the catalogue (read-only here) and the derived signals (GET /api/signals, PUT|DELETE /api/signals/derived/<name>), edited in the boxed expression editor, plus
// "Try it on recent data" (POST /api/signals/try): the unsaved definition over the latest imported recording. Nothing is saved by the try; closing the editor discards it.
import { h, s, clear, btn } from './dom.js';
import { mountExprEditor } from './exprview.js';
import { defaultGraph, realBody, realSummary, stripPath } from './exprgraph.js';

export const NO_TRY = 'Try it on recent data is off: it needs Developer mode and its switch on the phone.';
export const WINDOWS = [[300, '5 minutes'], [600, '10 minutes'], [1800, '30 minutes']];

export async function signalsView(ctx, root) {
  let ed = null;
  const leave = () => { if (ed) { ed.unmount(); ed = null; } };
  ctx.onLeave(leave);
  const fail = (e) => { if (!(e && e.stale)) ctx.showError(e); };
  const list = async () => {
    leave(); clear(root);
    const d = await ctx.api('GET', '/api/signals');
    root.appendChild(h('h2', null, 'Signals'));
    root.appendChild(h('p', { class: 'muted' }, d.signals.map((x) => x.name + (x.enabled ? '' : ' (off)')).join(', ')));
    root.appendChild(btn('New signal', () => edit(d, null), 'btn primary'));
    for (const x of d.derived) root.appendChild(h('section', { class: 'card row' }, h('strong', null, x.ref), btn('Edit', () => edit(d, x))));
  };
  const edit = (d, x) => {
    leave(); clear(root);
    const name = h('input', { type: 'text', value: x ? x.name : '', 'aria-label': 'Signal name', readonly: x ? true : null, maxlength: 12 });
    const host = h('div', null); const out = h('ul', { class: 'expr-try-out', role: 'status' }); const strip = h('div', null);
    ed = mountExprEditor(host, { graph: x ? x.graph : defaultGraph() });
    const done = (p) => p.then(() => list(), fail);
    const win = h('select', { 'aria-label': 'Window' }, ...WINDOWS.map(([v, l]) => h('option', { value: String(v), selected: v === 600 ? true : null }, l)));
    const run = async () => {
      clear(out); clear(strip);
      const say = (ls) => ls.forEach((l) => out.appendChild(h('li', null, l)));
      try {
        const r = await ctx.api('POST', '/api/signals/try', realBody(ed.graph(), Number(win.value)));
        say(realSummary(r));
        if (r.points && r.points.length) strip.appendChild(s('svg', { class: 'expr-strip', viewBox: '0 0 300 40', width: 300, height: 40, role: 'img', 'aria-label': 'Raw expression output over the window' }, s('path', { d: stripPath(r.points, 300, 40), class: 'expr-strip-line', fill: 'none' })));
        say((r.moments || []).map((m) => 'Photo Moment ' + m.id + ' at ' + new Date(m.s * 1000).toLocaleTimeString()));
      } catch (e) { if (!(e && e.stale)) say([(e && e.message) || 'The try failed.']); }
    };
    root.appendChild(h('h2', null, x ? 'Edit ' + x.ref : 'New signal'));
    root.appendChild(h('label', { class: 'field' }, h('span', null, 'Name'), name));
    root.appendChild(host);
    root.appendChild(h('div', { class: 'row wrap' }, btn('Save', () => done(ctx.api('PUT', '/api/signals/derived/' + name.value.trim(), { graph: ed.graph() })), 'btn primary'),
      x ? btn('Delete', () => done(ctx.api('DELETE', '/api/signals/derived/' + x.name))) : null, btn('Back', () => list().catch(fail))));
    root.appendChild(h('section', { class: 'expr-try', 'aria-label': 'Try it on recent data' }, h('h4', null, 'Try it on recent data'),
      d.tryIt && d.tryIt.available ? h('div', { class: 'row wrap' }, win, btn('Run', run)) : h('p', { class: 'muted' }, NO_TRY), out, strip));
  };
  await list();
}
