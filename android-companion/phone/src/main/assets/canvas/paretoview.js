// The Sweep view (Task 36, core): a parameter sweep over traces and its sortable result table. The Pareto front and chart are later work (LA6, LA14).
// Sorting is the server's (GET /api/sweeps/{id}?sort=&dir=). No frames appear here, so no thumbnails.
import { h, clear, btn, enc } from './dom.js';
import { when } from './format.js';
import { sweepRequest, sweepRows, sweepSummary, COLUMNS, nextSort, sortQuery, arrow, FUSION_TEXT } from './pareto.js';
import { tracePicker } from './replayview.js';

const POLL_MS = 2000;

export function resultTable(result, sort, onSort) {
  const rows = sweepRows(result);
  const head = h('tr', null, COLUMNS.map((c) => h('th', { scope: 'col', 'aria-sort': c.key && sort.sort === c.key ? (sort.dir === 'asc' ? 'ascending' : 'descending') : null },
    c.key ? h('button', { type: 'button', class: 'btn small sortcol', onclick: () => onSort(c.key) }, c.label + arrow(sort, c.key)) : c.label)));
  const body = rows.map((r) => h('tr', null, h('td', null, r.index), h('td', null, r.params), h('td', null, r.captures), h('td', null, r.opens), h('td', null, r.pct), h('td', null, r.hours), h('td', null, r.unobserved)));
  return h('div', { class: 'table-scroll' }, h('table', { class: 'params results' }, h('thead', null, head), h('tbody', null, body)));
}

export async function sweepView(ctx, root) {
  clear(root);
  root.appendChild(h('h2', null, 'Sweep'));
  let traces = [];
  try { traces = await ctx.api('GET', '/api/traces'); } catch (e) {
    if (e && e.stale) throw e;
    root.appendChild(h('p', { class: 'muted' }, e && e.code === 'developer_mode_off' ? 'Developer mode is off. Turn it on to sweep traces.' : 'Traces could not be loaded.'));
    return;
  }
  const picker = tracePicker(traces);
  const mode = h('select', { 'aria-label': 'Mode' }, h('option', { value: 'grid' }, 'Grid'), h('option', { value: 'random' }, 'Random'));
  const samples = h('input', { type: 'text', inputmode: 'numeric', 'aria-label': 'Samples', placeholder: 'random only' });
  const seed = h('input', { type: 'text', inputmode: 'numeric', 'aria-label': 'Seed', placeholder: 'optional' });
  const paramRows = [];
  const paramBox = h('div', { class: 'sweep-params' });
  const addParam = () => {
    const mk = (label, ph) => h('input', { type: 'text', 'aria-label': label, placeholder: ph });
    const r = { path: mk('Param path', 'gate.hamming'), values: mk('Values', '4, 6, 8'), min: mk('Min', 'min'), max: mk('Max', 'max'), step: mk('Step', 'step') };
    paramRows.push(r);
    paramBox.appendChild(h('div', { class: 'row wrap param-row' }, r.path, r.values, h('span', { class: 'muted' }, 'or range'), r.min, r.max, r.step));
  };
  addParam();
  const result = h('div', { class: 'result', 'aria-live': 'polite' });
  let timer = null;
  let sort = { sort: 'index', dir: 'asc' };
  let sweepId = null;
  ctx.onLeave(() => { if (timer !== null) clearTimeout(timer); timer = null; });

  const show = async () => {
    const job = await ctx.api('GET', '/api/sweeps/' + enc(sweepId) + sortQuery(sort));
    clear(result);
    result.appendChild(h('p', null, h('strong', null, 'Sweep ' + job.sweepId), ' · ' + job.jobState + (job.invalidated ? ' · invalidated (labels changed)' : '')));
    if (job.jobState === 'DONE' && job.result) {
      const s = sweepSummary(job.result);
      if (s.fusionWarning) result.appendChild(h('p', { class: 'warn-text fusion-warning', role: 'alert' }, FUSION_TEXT));
      result.appendChild(h('p', null, s.head));
      result.appendChild(h('p', { class: 'windows' }, s.inexactText));
      if (s.baseline) result.appendChild(h('p', null, s.baseline));
      if (s.warnings.length) result.appendChild(h('ul', { class: 'warnings' }, s.warnings.map((w) => h('li', null, w))));
      result.appendChild(resultTable(job.result, sort, (key) => { sort = nextSort(sort, key); show().catch((e) => ctx.showError(e)); }));
    } else if (job.jobState === 'RUNNING') {
      result.appendChild(btn('Cancel sweep', async () => { try { await ctx.api('POST', '/api/sweeps/' + enc(sweepId) + '/cancel'); await show(); } catch (e) { ctx.showError(e); } }, 'btn'));
      timer = setTimeout(() => { show().catch((e) => ctx.showError(e)); }, POLL_MS);
    } else if (job.jobState === 'CANCELLED') result.appendChild(h('p', { class: 'muted' }, 'Cancelled. No result.'));
  };
  const start = async () => {
    ctx.clearBanner();
    const r = sweepRequest({
      traceIds: picker.picked(), mode: mode.value || 'grid', samples: samples.value, seed: seed.value,
      params: paramRows.map((p) => ({ path: p.path.value, values: p.values.value, min: p.min.value, max: p.max.value, step: p.step.value })),
    });
    if (r.problem) { ctx.showError({ message: r.problem, problems: [] }); return; }
    try { const j = await ctx.api('POST', '/api/sweeps', r.body); sweepId = j.sweepId; sort = { sort: 'index', dir: 'asc' }; await show(); } catch (e) { ctx.showError(e); }
  };
  root.appendChild(h('section', { class: 'card' },
    h('h3', null, 'Traces'), picker.node,
    h('h3', null, 'Parameters'), h('p', { class: 'muted' }, 'A plan path (for example gate.hamming) with a value list, or a min/max/step range. Fusion is not sweepable. Results are estimates.'),
    paramBox, h('div', { class: 'row' }, btn('Add parameter', addParam, 'btn small')),
    h('label', { class: 'field' }, 'Mode', mode), h('label', { class: 'field' }, 'Samples', samples), h('label', { class: 'field' }, 'Seed', seed),
    h('div', { class: 'row' }, btn('Start sweep', start, 'btn primary'))));
  root.appendChild(result);
  let past = [];
  try { past = await ctx.api('GET', '/api/sweeps'); } catch (e) { if (e && e.stale) throw e; }
  if (past.length) {
    root.appendChild(h('section', { class: 'card' }, h('h3', null, 'Earlier sweeps'),
      h('ul', { class: 'plain' }, past.map((p) => h('li', null, btn(p.sweepId + ' · ' + p.jobState + ' · ' + when(p.createdAt), () => { sweepId = p.sweepId; show().catch((e) => ctx.showError(e)); }, 'btn small'))))));
  }
}
