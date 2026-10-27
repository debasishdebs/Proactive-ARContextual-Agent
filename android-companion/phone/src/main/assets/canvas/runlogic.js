// Pure run, A/B and rollback logic for the views. No DOM.
export const isActive = (status) => ['RUNNING', 'QUEUED'].includes(String(status || '').toUpperCase());

/** The body of POST /api/publish/rollback: the server REQUIRES the id of the publish the user saw as the newest. */
export const rollbackPayload = (pub) => (pub && Array.isArray(pub.stack) && pub.stack.length ? { expectedId: pub.stack[0].id } : null);

/** The other run of [run]'s A/B group among the GET /api/runs rows, or null. */
export const partnerOf = (rows, run) => (run && run.abGroup ? (rows || []).find((r) => r.abGroup === run.abGroup && r.runId !== run.runId) || null : null);

/** {a, b} run ids of a group: A started first. */
export function abPair(rows, group) {
  const two = (rows || []).filter((r) => r.abGroup === group).sort((x, y) => (x.startedAt || 0) - (y.startedAt || 0) || (x.runId < y.runId ? -1 : 1));
  return two.length === 2 ? { a: two[0].runId, b: two[1].runId } : null;
}

/** Open the compare view only for a pair this view watched run: a finished pair opened from a link stays where it is. */
export const shouldAutoOpen = (sawActive, run, partner) => !!(sawActive && partner && String(run.status).toUpperCase() === 'DONE' && String(partner.status).toUpperCase() === 'DONE');

/** Backoff for a failed poll: 1, 2, 4 s, then stop (null). A missing or forbidden run is not retried. */
export function retryDelay(attempt, err) {
  const s = err && err.status;
  if (s !== 0 && !(s >= 500)) return null;
  return [1000, 2000, 4000][attempt] === undefined ? null : [1000, 2000, 4000][attempt];
}

/** Playground vs Run-and-Save (v0.9.6): a pipeline with a Save memory step offers two saving runs, one without it is a playground (run, see the output, nothing stored). `?after=` of the run page: what to do once DONE (null: nothing). */
export const hasMemoryNode = (pipe) => !!pipe && Array.isArray(pipe.nodes) && pipe.nodes.some((n) => n && n.type === 'memory.write');
export const runChoices = (pipe) => (hasMemoryNode(pipe) ? [{ label: 'Run & Save', after: 'save' }, { label: 'Run, review, then Save', after: 'review' }] : [{ label: 'Run', after: 'play' }]);
export const momentStart = (pipe) => (hasMemoryNode(pipe) ? { label: 'Start', after: null } : { label: 'Run', after: 'play' }); // a Moment/Scene run is never saved (the server refuses it): no memory node = the output dialog
export const afterOf = (v) => (['save', 'review', 'play'].includes(v) ? v : null);
export const refKey = (r) => r.pipelineId + '@' + (r.version ? r.version : 'draft');

/** Redraw only when nothing on screen is final: a finished, unchanged run keeps its open details and typed notes. */
export const shouldRedraw = (drawnStatus, run) => drawnStatus === null || drawnStatus !== String(run.status) || isActive(run.status);

/** What the run poll does next: 'stop', 'pause' (tab hidden: resume on visibilitychange) or 'wait' (schedule the next tick). */
export const pollAction = (hidden, wantMore) => (!wantMore ? 'stop' : hidden ? 'pause' : 'wait');

/** The A/B selector: every pipeline's draft and frozen versions except [exclude] (the base), "<id>@draft" / "<id>@<n>". */
export function abOptions(pipelines, exclude) {
  const out = [];
  for (const p of pipelines || []) {
    out.push({ value: p.id + '@draft', label: p.name + ' (draft)' });
    for (const v of p.versions || []) out.push({ value: p.id + '@' + v.version, label: p.name + ' v' + v.version });
  }
  return out.filter((o) => o.value !== exclude);
}

export function parseRef(value) {
  const i = String(value || '').lastIndexOf('@');
  if (i < 1) return null;
  const v = value.slice(i + 1);
  return v === 'draft' ? { pipelineId: value.slice(0, i) } : { pipelineId: value.slice(0, i), version: parseInt(v, 10) };
}

/**
 * Whether a frame's `motion` (frame_meta.motion: the server sends an enum NAME such as WALKING, VEHICLE, MOVING or HEAD_MOVING; STILL and UNKNOWN are
 * not motion) counts as moving. A number > 0 or `true` is also accepted.
 */
export function isMoving(m) {
  if (typeof m === 'string') return ['WALKING', 'VEHICLE', 'MOVING', 'HEAD_MOVING'].includes(m.trim().toUpperCase());
  if (typeof m === 'number') return m > 0;
  return m === true;
}
