// Capture-plan publish and rollback UI (Task 37): the five evidence gates with their reasons, SHADOW / TRIAL / LIVE, the TRIAL countdown and the plan actions
// (Use settings, Restore previous; Undo latest is the existing group-aware rollback). Everything the server wrote goes through h() only.
import { h, btn } from './dom.js';
import { FUSION_TEXT, hoursText } from './pareto.js';

const enc = encodeURIComponent;
export const PLAN_MODES = [
  { mode: 'SHADOW', label: 'Shadow', text: 'The glasses run the plan beside your settings and report what it would have done. Nothing changes.' },
  { mode: 'TRIAL', label: 'Trial (60 min)', text: 'The plan runs for up to 60 minutes, then the glasses go back to your settings by themselves. Needs milestone M1.' },
  { mode: 'LIVE', label: 'Live', text: 'Not available yet.' },
];
export const LIVE_TEXT = 'Live is refused for now: it needs a verified measured interval of this plan at 0.42 %/min or less. Run a trial or shadow first.';
export const M1_TEXT = 'Milestone M1 (the worn device run) is not recorded as passed. Trial and live stay blocked until it is. Shadow is not blocked.';
const GATE_NAMES = ['Compiles', 'No generated code', 'Replay evidence', 'Mode allowed', 'Glasses ready'];
export const TRIAL_MINUTES = 60;

/** The POST /api/plans/check answer -> one line per gate ({n, name, ok, text}) plus the M1 line. Unmet gates carry the server's plain sentence. */
export function gateLines(check) {
  const c = check || {};
  const gates = (c.gates || []).map((g) => ({ n: g.n, name: GATE_NAMES[g.n - 1] || 'Gate ' + g.n, ok: g.ok === true, text: g.ok === true ? '' : String(g.text || '') }));
  return { gates, m1: c.m1Passed === true ? 'Milestone M1: recorded as passed.' : M1_TEXT, m1Passed: c.m1Passed === true, details: (c.reasons || []).filter((r) => !gates.some((g) => g.text === r)).map(String) };
}

/** The replay behind gate 3: how many windows were compared and how many were excluded as inexact, the estimate, and the fusion warning. Null without evidence. */
export function evidenceLines(ev) {
  if (!ev) return null;
  const fix = (n) => (typeof n === 'number' ? String(+n.toFixed(2)) : '–');
  return {
    windows: (ev.windowsCompared || 0) + ' windows compared, ' + (ev.inexactWindows || 0) + ' inexact (excluded)',
    estimate: 'Estimate over ' + Math.round(ev.minutes || 0) + ' min and ' + (ev.statesCovered || 0) + ' states: plan ' + fix(ev.candidatePctPerMin) + ' %/min · ' + hoursText({ estimatedPctPerMin: ev.candidatePctPerMin }) +
      ' from full; recorded config ' + fix(ev.baselinePctPerMin) + ' %/min.',
    fusionWarning: ev.fusionWarning === true,
  };
}

/** "Trial: about 42 min left of 60 (counted from the send)", or the end text once past it. Null when the plan is not a TRIAL or the send time is unknown. */
export function trialCountdown(active, now) {
  if (!active || String(active.mode).toUpperCase() !== 'TRIAL' || typeof active.sentAtMs !== 'number') return null;
  const left = Math.ceil((active.sentAtMs + TRIAL_MINUTES * 60000 - now) / 60000);
  return left > 0 ? 'Trial: about ' + left + ' min left of ' + TRIAL_MINUTES + ' (counted from the send)' : 'Trial: its 60 minutes are over; the glasses return to your settings by themselves.';
}

const outcomeText = (m) => 'Capture plan ' + String(m).toLowerCase() + ' sent.';

function renderCheck(check) {
  const g = gateLines(check); const ev = evidenceLines(check && check.evidence);
  return h('div', { class: 'plan-check' },
    h('ul', { class: 'plain gates' }, g.gates.map((x) => h('li', { class: x.ok ? 'gate ok' : 'gate unmet' }, 'Gate ' + x.n + ' ' + x.name + ': ' + (x.ok ? 'ok' : x.text)))),
    h('p', { class: g.m1Passed ? 'muted' : 'warn-text' }, g.m1),
    g.details.length ? h('ul', { class: 'problems' }, g.details.map((d) => h('li', null, d))) : null,
    ev ? h('div', { class: 'evidence' }, h('p', { class: 'windows' }, ev.windows), h('p', null, ev.estimate),
      ev.fusionWarning ? h('p', { class: 'warn-text fusion-warning', role: 'alert' }, FUSION_TEXT) : null) : null);
}

/** The publish dialog of one frozen version's capture plan: pick the mode, see the gates, publish. */
export function planDialog(ctx, id, version) {
  const radios = PLAN_MODES.map((m) => { const r = h('input', { type: 'radio', name: 'planmode', value: m.mode, 'aria-label': m.label }); r.checked = m.mode === 'SHADOW'; return r; });
  const pick = () => PLAN_MODES[Math.max(0, radios.findIndex((r) => r.checked))].mode;
  const result = h('div', { class: 'result', 'aria-live': 'polite' });
  const target = () => ({ pipelineId: id, version, mode: pick() });
  const show = (node) => { while (result.firstChild) result.removeChild(result.firstChild); result.appendChild(node); };
  const check = async () => {
    if (pick() === 'LIVE') show(h('p', { class: 'warn-text live-refused', role: 'alert' }, LIVE_TEXT));
    try { const c = await ctx.api('POST', '/api/plans/check', target()); show(h('div', null, pick() === 'LIVE' ? h('p', { class: 'warn-text live-refused', role: 'alert' }, LIVE_TEXT) : null, renderCheck(c))); } catch (e) { ctx.showError(e); }
  };
  const body = h('div', null,
    PLAN_MODES.map((m, i) => h('label', { class: 'field' }, h('span', null, radios[i], ' ', h('strong', null, m.label)), h('span', { class: 'muted' }, m.text))),
    h('div', { class: 'row' }, btn('Check gates', check, 'btn')), result);
  ctx.modal('Publish capture plan v' + version, body, [{ label: 'Publish plan', cls: 'btn primary', run: async (close) => {
    if (pick() === 'LIVE') { show(h('p', { class: 'warn-text live-refused', role: 'alert' }, LIVE_TEXT)); return; }
    try {
      const r = await ctx.api('POST', '/api/plans/publish', target());
      close(); await ctx.reload(); ctx.showNotice(outcomeText(r.mode));
    } catch (e) {
      if (e.code === 'publish_blocked') { close(); ctx.showError({ message: 'Plan publish blocked.', problems: e.problems }); } else ctx.showError(e);
    }
  } }]);
}

/** The active capture plan with its states, the TRIAL countdown, Use settings and Restore previous. Nothing is shown when the plan service is not there. */
export async function planStatusCard(ctx, host, now) {
  let s;
  try { s = await ctx.api('GET', '/api/plans/status'); } catch (e) { if (e && e.stale) throw e; return null; }
  const act = async (path, done) => {
    try { await ctx.api('POST', '/api/plans/' + path, {}); await ctx.reload(); ctx.showNotice(done); } catch (e) {
      if (e.code === 'publish_blocked') ctx.showError({ message: 'Refused.', problems: e.problems }); else ctx.showError(e);
    }
  };
  const a = s && s.active; const left = trialCountdown(a, typeof now === 'number' ? now : Date.now());
  const card = h('section', { class: 'card plan-status' }, h('h3', null, 'Capture plan'),
    a ? h('p', null, a.name + ' v' + a.version + ' (' + String(a.mode).toLowerCase() + ') · ' + a.state) : h('p', { class: 'muted' }, 'No capture plan is active: your settings run the capture.'),
    left ? h('p', { class: 'trial-left' }, left) : null,
    a && (s.rows || []).length ? h('ul', { class: 'plain' }, s.rows.map((r) => h('li', { class: 'muted' }, r))) : null,
    h('div', { class: 'row wrap' },
      a ? btn('Use settings', () => { if (window.confirm('Switch the capture back to your settings? The async half stays.')) act('use-settings', 'Capture is back on your settings.'); }, 'btn') : null,
      btn('Restore previous', () => act('restore-previous', 'Previous capture plan restored.'), 'btn')));
  host.appendChild(card);
  return card;
}
