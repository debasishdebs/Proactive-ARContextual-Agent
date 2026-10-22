// Pure logic of the replay and sweep views (Task 36, table only: the Pareto front and its chart are later work). No DOM.
// Everything shown is an ESTIMATE; hours follow the S80 battery line: hours from full = 100 / (%/min) / 60.
export const hoursFromPct = (pctPerMin) => (typeof pctPerMin === 'number' && pctPerMin > 0 ? 100 / pctPerMin / 60 : null);

const fix = (n, d) => (typeof n === 'number' && isFinite(n) ? String(+n.toFixed(d)) : '–');
/** The server's hours when it sent them, else the S80 formula over %/min. */
export const hoursOf = (f) => (f && typeof f.estimatedHoursFromFull === 'number' ? f.estimatedHoursFromFull : hoursFromPct(f && f.estimatedPctPerMin));
export const hoursText = (f) => { const h = hoursOf(f); return h === null ? '–' : fix(h, 1) + ' h'; };

/** "0.49 %/min · 3.4 h from full · 3.4 opens/min (estimate)". */
export function figureLine(f) {
  if (!f) return '';
  return fix(f.estimatedPctPerMin, 2) + ' %/min · ' + hoursText(f) + ' from full · ' + fix(f.opensPerMin, 1) + ' opens/min (estimate)';
}

/** One replay report -> what the view shows. compared = windows the base comparison could judge; inexact ones are excluded and counted. */
export function replaySummary(report) {
  const r = report || {};
  const compared = (r.basePassed || 0) + (r.baseFailed || 0);
  const inexact = r.inexactWindows || 0;
  const reasons = Object.keys(r.inexactReasons || {}).map((k) => k.toLowerCase() + ' ' + r.inexactReasons[k]).join(', ');
  return {
    replayClass: r.replayClass || '',
    compared, inexact, passed: r.basePassed || 0, failed: r.baseFailed || 0,
    windowsText: compared + ' windows compared, ' + inexact + ' inexact (excluded)' + (reasons ? ' (' + reasons + ')' : ''),
    baseline: r.baseline ? figureLine(r.baseline) : '',
    candidate: r.candidate ? figureLine(r.candidate) : '',
    unobserved: typeof r.unobservedOpensPct === 'number' ? 'unobserved opens: ' + fix(r.unobservedOpensPct, 1) + ' %' : '',
    fusionWarning: r.shadowOrTrialOnly === true,
    warnings: Array.isArray(r.warnings) ? r.warnings.map(String) : [],
  };
}
export const FUSION_TEXT = 'This plan changes fusion params. Fusion is not replayed: the result cannot be trusted for it, and the plan can run only in shadow or trial.';

const intOrNull = (v) => { const t = String(v === undefined || v === null ? '' : v).trim(); return /^-?\d+$/.test(t) ? Number(t) : null; };
const numOrNull = (v) => { const t = String(v === undefined || v === null ? '' : v).trim(); return t !== '' && isFinite(Number(t)) ? Number(t) : null; };

/** form {traceIds[], hamming, bigChange, ringCompareAll, fusion: "key=1, key2=2"} -> {body} | {problem}. A blank gate field keeps the default. */
export function replayRequest(form) {
  const f = form || {};
  if (!f.traceIds || !f.traceIds.length) return { problem: 'Pick at least one trace.' };
  const body = { traceIds: f.traceIds.slice() };
  const gate = {};
  for (const k of ['hamming', 'bigChange']) {
    if (String(f[k] === undefined || f[k] === null ? '' : f[k]).trim() === '') continue;
    const n = intOrNull(f[k]);
    if (n === null) return { problem: k + ' must be a whole number.' };
    gate[k] = n;
  }
  if (f.ringCompareAll === true) gate.ringCompareAll = true;
  if (Object.keys(gate).length) {
    if (!f.configHash) return { problem: 'A candidate needs a config hash (hex).' };
    body.candidate = { configHash: f.configHash, gate };
  } else if (f.configHash) body.candidate = { configHash: f.configHash };
  const fusion = {};
  for (const part of String(f.fusion || '').split(',').map((s) => s.trim()).filter(Boolean)) {
    const i = part.indexOf('=');
    const n = i < 0 ? null : numOrNull(part.slice(i + 1));
    if (n === null || i < 1) return { problem: 'Fusion entries look like key=number.' };
    fusion[part.slice(0, i).trim()] = n;
  }
  if (Object.keys(fusion).length) body.fusion = fusion;
  return { body };
}

/** "0.5, 1, off" -> numbers where numeric, strings otherwise; "null" is the unchanged marker. */
export function parseValues(text) {
  return String(text || '').split(',').map((s) => s.trim()).filter(Boolean).map((s) => (s === 'null' ? null : numOrNull(s) !== null ? Number(s) : s));
}

/** form {traceIds[], mode, samples, seed, params: [{path, values?, min?, max?, step?}]} -> {body} | {problem}. */
export function sweepRequest(form) {
  const f = form || {};
  if (!f.traceIds || !f.traceIds.length) return { problem: 'Pick at least one trace.' };
  if (f.mode !== 'grid' && f.mode !== 'random') return { problem: 'Pick grid or random.' };
  const params = [];
  for (const p of f.params || []) {
    const path = String(p.path || '').trim();
    if (!path) continue;
    if (String(p.min || '').trim() !== '' || String(p.max || '').trim() !== '') {
      const min = numOrNull(p.min); const max = numOrNull(p.max); const step = numOrNull(p.step);
      if (min === null || max === null || step === null || !(step > 0) || max < min) return { problem: path + ': min, max and a positive step are needed.' };
      params.push({ path, min, max, step });
    } else {
      const values = parseValues(p.values);
      if (!values.length) return { problem: path + ': give values or a min/max/step range.' };
      params.push({ path, values });
    }
  }
  if (!params.length) return { problem: 'Add at least one parameter to sweep.' };
  const body = { traceIds: f.traceIds.slice(), mode: f.mode, params };
  if (f.mode === 'random') {
    const s = intOrNull(f.samples);
    if (s === null || s < 1) return { problem: 'Random needs a sample count of 1 or more.' };
    body.samples = s;
    const seed = String(f.seed === undefined || f.seed === null ? '' : f.seed).trim();
    if (seed !== '') { const n = intOrNull(seed); if (n === null) return { problem: 'Seed must be a whole number.' }; body.seed = n; }
  }
  return { body };
}

/** The result table's columns; key is the server's sort value. */
export const COLUMNS = [
  { key: 'index', label: '#' },
  { key: null, label: 'Params' },
  { key: 'captures', label: 'Captures/min' },
  { key: 'opens', label: 'Opens/min' },
  { key: 'pct', label: '%/min' },
  { key: 'hours', label: 'Hours from full' },
  { key: 'unobserved', label: 'Unobserved opens %' },
];

/** Clicking a header: the same column flips its direction, a new one starts ascending. */
export function nextSort(cur, key) {
  const c = cur || { sort: 'index', dir: 'asc' };
  return c.sort === key ? { sort: key, dir: c.dir === 'asc' ? 'desc' : 'asc' } : { sort: key, dir: 'asc' };
}
export const sortQuery = (s) => '?sort=' + encodeURIComponent(s.sort) + '&dir=' + encodeURIComponent(s.dir);
export const arrow = (cur, key) => (cur && cur.sort === key ? (cur.dir === 'asc' ? ' ▲' : ' ▼') : '');

const paramsText = (p) => Object.keys(p || {}).map((k) => k + '=' + (p[k] === null ? 'unchanged' : String(p[k]))).join(', ');
/** Rows in the server's order (the server sorts). Text only. */
export function sweepRows(result) {
  return ((result && result.points) || []).map((p) => ({
    index: String(p.index), params: paramsText(p.params), captures: fix(p.capturesPerMin, 2), opens: fix(p.opensPerMin, 2),
    pct: fix(p.estimatedPctPerMin, 3), hours: hoursText(p), unobserved: fix(p.unobservedOpensPct, 1),
  }));
}
export function sweepSummary(result) {
  const r = result || {};
  return {
    head: (r.mode || '') + ' sweep · ' + ((r.points || []).length) + ' points · ' + fix(r.minutes, 1) + ' min of trace · estimate',
    inexactText: (r.inexactWindows || 0) + ' inexact windows (excluded)',
    baseline: r.baseline ? 'Baseline: ' + figureLine(r.baseline) : '',
    warnings: Array.isArray(r.warnings) ? r.warnings.map(String) : [],
    fusionWarning: (r.warnings || []).some((w) => /fusion/i.test(String(w))),
  };
}
