// The laptop Python helper as the browser shows it: STATUS ONLY. This module builds text and one card; it has no control of any kind and sends nothing
// (its one request is a read of the status route). A run that needs the laptop waits for a tap on the PHONE: the page can say so and count down,
// but it cannot make that tap. Every string, from the API or from a helper, is placed with textContent through h(): there are no HTML sinks.
import { h } from './dom.js';

export const PY_FN = 'py.fn';
const own = (o, k) => Object.prototype.hasOwnProperty.call(o, k);

/** True when the pipeline JSON (a parsed object) holds a Python node. */
export const hasPython = (pipe) => !!pipe && Array.isArray(pipe.nodes) && pipe.nodes.some((n) => n && n.type === PY_FN);

const STATUS = { STOPPED_FORGET: 'Stopped: a photo was forgotten' };

/** The words for a run status. STOPPED_FORGET (a Forget voided the Python job) has its own text; every other status is shown as it is. */
export const statusLabel = (status) => {
  if (status === undefined || status === null) return '';
  const s = String(status);
  return own(STATUS, s) ? STATUS[s] : s;
};

const ERRORS = {
  not_confirmed: 'Not confirmed on the phone: you declined, or the tap window expired. Nothing was sent to the laptop.',
  frame_forget: 'A photo of this run was forgotten, so the run was stopped. Nothing was kept.',
  voided: 'The run was voided: the Python grant was turned off, the helper was disconnected or the session ended.',
  cancelled: 'The run was cancelled.',
  no_helper: 'No laptop helper is paired.',
  refused: 'The laptop job was refused (the input does not fit the limits).',
  killed: 'The laptop stopped answering, so the job was stopped.',
  timeout: 'The job ran out of time.',
  resources_busy: 'The laptop was busy.',
  needs_runtime: 'The laptop has no Python runtime ready.',
  oom: 'The job ran out of memory.',
  runtime_error: 'The Python job failed.',
  bad_output: 'The Python output did not match the output schema.',
  too_large: 'The result was too large.',
  disk_low: 'The laptop is low on disk space.',
  schema_mismatch: 'The output did not match the output schema.',
  exception: 'The Python function raised an error.',
  too_big: 'The output was too big.',
  no_result: 'The laptop sent no result for this photo.',
  bad_schema: 'The output schema is not valid.',
  bad_input: 'This photo had no JSON to send to Python.',
  not_experiment: 'Python runs only in experiments.',
  no_fence: 'The run could not be checked against Forget, so it was not sent.',
};

/** Plain words for an item error code; a code this module does not know is returned as it is (and shown as text). */
export const errorText = (code) => {
  const c = String(code);
  return own(ERRORS, c) ? ERRORS[c] : c;
};

/** Whole seconds until the tap window ends, on the PHONE's clock (the browser's own clock is not trusted); null when nothing waits or the phone sent no clock. */
export function secondsLeft(st) {
  if (!st || !(st.awaitingConfirm > 0) || typeof st.confirmExpiresAtMs !== 'number' || typeof st.serverTimeMs !== 'number') return null;
  return Math.max(0, Math.ceil((st.confirmExpiresAtMs - st.serverTimeMs) / 1000));
}

export function mmss(total) {
  const s = Math.max(0, Math.floor(total));
  return Math.floor(s / 60) + ':' + String(s % 60).padStart(2, '0');
}

/** [{cls, text}] for GET /api/helper-status (null = the read failed). */
export function helperLines(st) {
  if (!st) return [{ cls: 'muted', text: 'Laptop helper status is unavailable.' }];
  const out = [];
  if (!st.paired) {
    out.push({ cls: 'muted', text: 'Laptop helper: not paired. Pair it from the Canvas screen of the phone app.' });
  } else {
    out.push({ cls: '', text: 'Laptop helper: paired.' });
    const on = !!(st.grants && st.grants.py_exec === true);
    out.push({ cls: on ? '' : 'muted', text: on ? 'Python runs are on.' : 'Python runs are off. Turn them on in the Canvas screen on the phone.' });
    const ready = st.ready === true && st.readyStale !== true;
    out.push({ cls: ready ? '' : 'muted', text: ready ? 'The helper is ready.' : 'The helper is not ready (it has not reported Python ready lately).' });
  }
  const n = st.awaitingConfirm | 0;
  if (n > 0) {
    const left = secondsLeft(st);
    out.push({
      cls: 'helper-waiting',
      text: 'Waiting for your tap on the phone' + (n > 1 ? ' (' + n + ' runs)' : '') + (left === null ? '' : ' · ' + mmss(left) + ' left'),
    });
    out.push({ cls: 'muted', text: 'The tap is made in the phone app. This page cannot make it, and a run that is not tapped sends nothing to the laptop.' });
  }
  return out;
}

/** The card: text only, no control. */
export function helperCard(st) {
  const waiting = !!st && st.awaitingConfirm > 0;
  return h('section', { class: 'card helper-card' + (waiting ? ' helper-waiting' : ''), 'aria-live': 'polite' },
    h('h3', null, 'Laptop helper'),
    helperLines(st).map((l) => h('p', { class: l.cls || null }, l.text)));
}

/** One read of the status route: the JSON, or null when it fails. A view that was left stays stale (rethrown). */
export async function loadHelperStatus(ctx) {
  try {
    return await ctx.api('GET', '/api/helper-status');
  } catch (e) {
    if (e && e.stale) throw e;
    return null;
  }
}
