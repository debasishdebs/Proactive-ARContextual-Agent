// Pure text for the views: usage lines, token estimates, publish mode costs, rollback and history rows. No DOM.
export const when = (ms) => (ms ? new Date(ms).toLocaleString() : '–');
export const tokensCompact = (n) => (n === null || n === undefined ? null : n < 1000 ? String(n) : (+(n / 1000).toFixed(1)) + 'k');

/** "calls 14 (estimated 15) · tokens 12.1k in / 2.3k out · median 2.4 s"; a missing part is left out. */
export function usageLine(u) {
  if (!u) return '';
  const parts = ['calls ' + (u.calls === null || u.calls === undefined ? '–' : u.calls) + (u.est === null || u.est === undefined ? '' : ' (estimated ' + u.est + ')')];
  if ((u.promptTokens !== null && u.promptTokens !== undefined) || (u.completionTokens !== null && u.completionTokens !== undefined)) {
    parts.push('tokens ' + (tokensCompact(u.promptTokens) || '–') + ' in / ' + (tokensCompact(u.completionTokens) || '–') + ' out');
  }
  if (u.medianLatencyMs !== null && u.medianLatencyMs !== undefined) parts.push('median ' + (u.medianLatencyMs / 1000).toFixed(1) + ' s');
  return parts.join(' · ');
}

export const agreementPercent = (x) => (typeof x === 'number' ? Math.round(x * 100) + '%' : '–');

export function tokenEstimateText(t) {
  if (!t) return 'No token estimate: there are no earlier items to go by.';
  return 'about ' + tokensCompact(t.promptTokens) + ' tokens in, ' + tokensCompact(t.completionTokens) + ' out, from ' + t.basedOn + ' earlier items';
}

const num = (n) => String(+Number(n).toFixed(1));
/** "about 4–6× the model calls of Live extraction" from the server's callRange [min, max]; '' when there is none. */
export function callRangeText(range) {
  if (!range || range.length < 2 || !(range[1] > 0)) return '';
  const span = range[0] === range[1] ? num(range[0]) : num(range[0]) + '–' + num(range[1]);
  return 'about ' + span + '× the model calls of Live extraction';
}

/** The three publish modes with their honest cost (spec S7, S8, S30). */
export const MODES = [
  { mode: 'prompts', label: 'Prompts only', text: 'Live extraction keeps its own steps and uses this version\'s prompts. No extra model calls.' },
  { mode: 'shadow', label: 'Shadow', text: 'Live extraction stays as it is. On about 1 in 4 batches, after the live save, the pipeline also runs as unmetered experiment calls: roughly 2–2.5× the model calls of those batches. It saves traces only, and it pauses while Developer mode is off.' },
  { mode: 'live', label: 'Live', text: 'The pipeline replaces the extraction step for every batch: about 3 intent calls plus 1–3 extract calls per batch. A batch gets up to 240 s, then one live fallback. Live always runs the confidence check, the critic and the save rules.' },
];

const RACE = ['That publish was already rolled back.', 'A newer publish exists. Reload and try again.'];
/** Only the two known race texts are shown as the server wrote them; anything else is one fixed sentence. */
export function rollbackProblems(problems) {
  const known = (problems || []).filter((p) => RACE.includes(p));
  return known.length ? known : ['The rollback was refused.'];
}

/** Rows of /api/publish/history: {id, title, rolledBack, reason}. */
export function historyRows(list) {
  return (list || []).map((p) => ({
    id: p.id, groupId: p.groupId || null,
    title: '#' + p.id + ' · ' + p.kind + (p.mode ? ' (' + p.mode + ')' : '') + ' · ' + p.pipelineId + ' v' + p.version,
    rolledBack: p.rolledBackAt !== null && p.rolledBackAt !== undefined,
    reason: p.rollbackReason || '',
    publishedAt: p.publishedAt, rolledBackAt: p.rolledBackAt,
  }));
}

const counts = (o) => Object.keys(o || {}).map((k) => k + ' ' + o[k]).join(', ');
/** ["route: meeting 3, desk 2"] from a run's branches {switchId: {label: n}}. */
export const branchText = (branches) => Object.keys(branches || {}).map((s) => s + ': ' + counts(branches[s]));

/** The compare view's tables: per switch the branch counts of A and B and the agreement; per node the score sums. */
export function compareSummary(c) {
  const a = (c && c.branches && c.branches.a) || {};
  const b = (c && c.branches && c.branches.b) || {};
  const ids = [...new Set([...Object.keys(a), ...Object.keys(b), ...Object.keys((c && c.agreement) || {})])];
  const sums = (c && c.scoreSums) || {};
  return {
    switches: ids.map((id) => ({ id, a: counts(a[id]), b: counts(b[id]), agreement: agreementPercent(((c && c.agreement) || {})[id]) })),
    scores: Object.keys(sums).map((id) => ({ id, a: sums[id].a, b: sums[id].b })),
  };
}
