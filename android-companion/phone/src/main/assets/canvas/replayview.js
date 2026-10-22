// The Replay view (Task 36): pick traces, optionally a gate candidate and fusion values, start a replay, watch it, read the estimate.
// Replay results hold no frames, so there are no thumbnails here (photos.js is for views that show a frame).
import { h, clear, btn } from './dom.js';
import { when } from './format.js';
import { replayRequest, replaySummary, FUSION_TEXT } from './pareto.js';

const enc = encodeURIComponent;
const POLL_MS = 2000;

/** Checkboxes for the stored traces; returns {node, picked()}. */
export function tracePicker(traces) {
  const boxes = (traces || []).map((t) => ({ t, box: h('input', { type: 'checkbox', value: t.traceId, 'aria-label': 'Trace ' + t.traceId }) }));
  const node = boxes.length
    ? h('ul', { class: 'plain traces' }, boxes.map(({ t, box }) => h('li', null, h('label', null, box, ' ' + t.traceId + ' · ' + t.source + ' · ' + when(t.startedAtMs) + (t.exact ? '' : ' · inexact')))))
    : h('p', { class: 'muted' }, 'No traces yet. Collect some for tuning first.');
  return { node, picked: () => boxes.filter((b) => b.box.checked).map((b) => b.t.traceId) };
}

export function reportCard(entry) {
  const s = replaySummary(entry.report);
  return h('section', { class: 'card replay-report' },
    h('strong', null, 'Trace ' + entry.traceId), h('span', { class: 'tag' }, s.replayClass + ' · estimate'),
    s.fusionWarning ? h('p', { class: 'warn-text fusion-warning', role: 'alert' }, FUSION_TEXT) : null,
    h('p', { class: 'windows' }, s.windowsText),
    s.baseline ? h('p', null, 'Recorded config: ' + s.baseline) : null,
    s.candidate ? h('p', null, 'Candidate: ' + s.candidate) : null,
    s.unobserved ? h('p', { class: 'muted' }, s.unobserved) : null,
    s.warnings.length ? h('ul', { class: 'warnings' }, s.warnings.map((w) => h('li', null, w))) : null);
}

function renderJob(job) {
  const reports = (job.jobState === 'DONE' && job.result && job.result.reports) || [];
  const fusion = reports.some((e) => e.report && e.report.shadowOrTrialOnly);
  return h('div', { class: 'job' },
    h('p', null, h('strong', null, 'Replay ' + job.replayId), ' · ' + job.jobState + (job.invalidated ? ' · invalidated (labels changed)' : '')),
    fusion ? h('p', { class: 'warn-text fusion-warning banner-warn', role: 'alert' }, FUSION_TEXT) : null,
    reports.map(reportCard),
    job.jobState === 'CANCELLED' ? h('p', { class: 'muted' }, 'Cancelled. No result.') : null);
}

export async function replayView(ctx, root) {
  clear(root);
  root.appendChild(h('h2', null, 'Replay'));
  let traces = [];
  try { traces = await ctx.api('GET', '/api/traces'); } catch (e) {
    if (e && e.stale) throw e;
    root.appendChild(h('p', { class: 'muted' }, e && e.code === 'developer_mode_off' ? 'Developer mode is off. Turn it on to replay traces.' : 'Traces could not be loaded.'));
    return;
  }
  const picker = tracePicker(traces);
  const field = (label, attrs) => { const i = h('input', Object.assign({ type: 'text', 'aria-label': label }, attrs)); return { i, row: h('label', { class: 'field' }, label, i) }; };
  const hash = field('Candidate config hash (hex)', { placeholder: 'optional' });
  const ham = field('Gate hamming', { inputmode: 'numeric', placeholder: 'default' });
  const big = field('Gate big change', { inputmode: 'numeric', placeholder: 'default' });
  const ring = h('input', { type: 'checkbox', 'aria-label': 'Compare against the whole ring' });
  const fus = field('Fusion values', { placeholder: 'key=number, key2=number' });
  const result = h('div', { class: 'result', 'aria-live': 'polite' });
  let timer = null;
  let current = null;
  ctx.onLeave(() => { if (timer !== null) clearTimeout(timer); timer = null; });

  const show = async (id) => {
    const job = await ctx.api('GET', '/api/replays/' + enc(id));
    current = job;
    clear(result);
    result.appendChild(renderJob(job));
    if (job.jobState === 'RUNNING') {
      result.appendChild(btn('Cancel replay', async () => { try { await ctx.api('POST', '/api/replays/' + enc(id) + '/cancel'); await show(id); } catch (e) { ctx.showError(e); } }, 'btn'));
      timer = setTimeout(() => { show(id).catch((e) => ctx.showError(e)); }, POLL_MS);
    }
  };
  const start = async () => {
    ctx.clearBanner();
    const r = replayRequest({ traceIds: picker.picked(), configHash: String(hash.i.value || '').trim(), hamming: ham.i.value, bigChange: big.i.value, ringCompareAll: ring.checked, fusion: fus.i.value });
    if (r.problem) { ctx.showError({ message: r.problem, problems: [] }); return; }
    try { const j = await ctx.api('POST', '/api/replays', r.body); await show(j.replayId); } catch (e) { ctx.showError(e); }
  };
  root.appendChild(h('section', { class: 'card' },
    h('h3', null, 'Traces'), picker.node,
    h('h3', null, 'Candidate (optional)'), h('p', { class: 'muted' }, 'Leave empty to replay the recorded config. Replay is an estimate; windows it cannot judge exactly are counted and excluded.'),
    hash.row, ham.row, big.row, h('label', { class: 'field' }, ring, ' Compare against the whole ring'), fus.row,
    h('div', { class: 'row' }, btn('Start replay', start, 'btn primary'))));
  root.appendChild(result);
  let past = [];
  try { past = await ctx.api('GET', '/api/replays'); } catch (e) { if (e && e.stale) throw e; }
  if (past.length) {
    root.appendChild(h('section', { class: 'card' }, h('h3', null, 'Earlier replays'),
      h('ul', { class: 'plain' }, past.map((p) => h('li', null, btn(p.replayId + ' · ' + p.jobState + ' · ' + when(p.createdAt), () => { show(p.replayId).catch((e) => ctx.showError(e)); }, 'btn small'))))));
  }
  return current;
}
