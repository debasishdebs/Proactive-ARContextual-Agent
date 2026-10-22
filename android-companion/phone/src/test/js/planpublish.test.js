// Task 37: the capture-plan publish dialog, the gate lines, the TRIAL countdown, the plan status card and the group-aware undo.
import { gateLines, evidenceLines, trialCountdown, planDialog, planStatusCard, LIVE_TEXT, M1_TEXT } from '../../main/assets/canvas/planpublish.js';
import { publishCard } from '../../main/assets/canvas/versions.js';
import { findAll } from './fake-dom.js';
import { eq, ok } from './fixtures.js';
import * as E from './env.js';

const GATE3 = 'Gate 3: needs a finished replay of this exact plan.';
const check = (over) => Object.assign({ ok: false, configHash: 'h', m1Passed: false, reasons: [GATE3], gates: [
  { n: 1, ok: true, text: '' }, { n: 2, ok: true, text: '' }, { n: 3, ok: false, text: GATE3 }, { n: 4, ok: true, text: '' }, { n: 5, ok: true, text: '' }],
  evidence: { replayId: 'r1', minutes: 40, statesCovered: 2, candidatePctPerMin: 0.45, baselinePctPerMin: 0.49, windowsCompared: 42, inexactWindows: 3, fusionWarning: true } }, over || {});
const has = (root, text) => ok(E.textOf(root).includes(text), 'expected "' + text + '" in: ' + E.textOf(root).slice(0, 700));
const radios = (root) => findAll(root, (e) => e.tagName === 'input' && e.getAttribute('type') === 'radio');
const choose = (rs, i) => rs.forEach((r, k) => { r.checked = k === i; });
async function with_(routes, fn) {
  const env = E.setup(['app']);
  try { const api = E.fakeApi(routes); await fn({ env, api, ctx: E.fakeCtx(api), root: document.getElementById('app') }); } finally { env.uninstall(); }
}

export const tests = {
  gateLinesCarryTheServersSentenceForEachUnmetGateAndTheM1Flag() {
    const g = gateLines(check());
    eq(g.gates.map((x) => x.ok), [true, true, false, true, true]);
    eq(g.gates[2].text, GATE3); eq(g.gates[0].text, '');
    eq(g.m1, M1_TEXT); eq(g.details, []);
    eq(gateLines(check({ m1Passed: true })).m1, 'Milestone M1: recorded as passed.');
    eq(gateLines(check({ reasons: [GATE3, 'node x: bad'] })).details, ['node x: bad']);
  },
  evidenceShowsWindowsInexactAndFusion() {
    const e = evidenceLines(check().evidence);
    eq(e.windows, '42 windows compared, 3 inexact (excluded)');
    ok(e.estimate.includes('0.45 %/min') && e.estimate.includes('0.49 %/min'), e.estimate);
    eq(e.fusionWarning, true); eq(evidenceLines(null), null);
  },
  trialCountdownCountsDownFromTheSend() {
    const a = { mode: 'TRIAL', sentAtMs: 0 };
    eq(trialCountdown(a, 18 * 60000), 'Trial: about 42 min left of 60 (counted from the send)');
    ok(trialCountdown(a, 61 * 60000).includes('over'));
    eq(trialCountdown({ mode: 'SHADOW', sentAtMs: 0 }, 0), null); eq(trialCountdown({ mode: 'TRIAL' }, 0), null);
  },
  async dialogChecksGatesAndShowsReasonsEvidenceAndFusionWarning() {
    await with_({ 'POST /api/plans/check': check() }, async ({ api, ctx }) => {
      planDialog(ctx, 'p1', 2);
      const body = ctx.modals[0].content;
      choose(radios(body), 1);
      E.click(E.buttons(body, 'Check gates')[0]); await E.settle();
      eq(api.calls[0].body, { pipelineId: 'p1', version: 2, mode: 'TRIAL' });
      has(body, 'Gate 3 Replay evidence: ' + GATE3); has(body, 'Gate 1 Compiles: ok'); has(body, M1_TEXT);
      has(body, '42 windows compared, 3 inexact (excluded)');
      const w = findAll(body, (e) => e.classList.contains('fusion-warning'));
      ok(w.length === 1 && w[0].getAttribute('role') === 'alert');
    });
  },
  async publishSendsTheModeAndReportsBlockedReasons() {
    let n = 0;
    await with_({ 'POST /api/plans/publish': (b) => { if (n++ === 0) throw new E.FakeApiError(409, 'publish_blocked', 'Blocked.', [GATE3]); return { publishId: 3, mode: b.mode, groupId: 'g' }; } }, async ({ api, ctx }) => {
      planDialog(ctx, 'p1', 2);
      await E.runAction(ctx, 'Publish plan');
      eq(api.calls[0].body, { pipelineId: 'p1', version: 2, mode: 'SHADOW' });
      eq(ctx.errors[0].problems, [GATE3]);
      planDialog(ctx, 'p1', 2);
      await E.runAction(ctx, 'Publish plan');
      ok(ctx.notices.some((t) => t.includes('shadow')), ctx.notices);
    });
  },
  async liveIsRefusedWithItsReasonAndNothingIsSent() {
    await with_({ 'POST /api/plans/publish': { publishId: 1 }, 'POST /api/plans/check': check() }, async ({ api, ctx }) => {
      planDialog(ctx, 'p1', 2);
      const body = ctx.modals[0].content;
      choose(radios(body), 2);
      await E.runAction(ctx, 'Publish plan');
      has(body, LIVE_TEXT);
      eq(api.calls.filter((c) => c.path === '/api/plans/publish').length, 0);
    });
  },
  async statusCardShowsPlanCountdownAndRunsUseSettingsAndRestorePrevious() {
    await with_({
      'GET /api/plans/status': { active: { name: 'Quiet walk', version: 3, mode: 'TRIAL', state: 'EFFECTIVE', sentAtMs: 0 }, rows: ['Glasses: EFFECTIVE · gen 4 · trial', 'ASYNC: shadow'] },
      'POST /api/plans/use-settings': { publishId: 0 }, 'POST /api/plans/restore-previous': { publishId: 5 },
    }, async ({ api, ctx, root, env }) => {
      await planStatusCard(ctx, root, 18 * 60000);
      has(root, 'Quiet walk v3 (trial) · EFFECTIVE'); has(root, 'about 42 min left of 60'); has(root, 'ASYNC: shadow');
      env.confirmAnswer = false;
      E.click(E.buttons(root, 'Use settings')[0]); await E.settle();
      eq(api.calls.filter((c) => c.path === '/api/plans/use-settings').length, 0, 'declined');
      env.confirmAnswer = true;
      E.click(E.buttons(root, 'Use settings')[0]); await E.settle();
      eq(api.calls.filter((c) => c.path === '/api/plans/use-settings').length, 1);
      E.click(E.buttons(root, 'Restore previous')[0]); await E.settle();
      eq(api.calls.filter((c) => c.path === '/api/plans/restore-previous').length, 1);
    });
  },
  async statusCardIsAbsentWhenThePlanServiceIsNotThere() {
    await with_({ 'GET /api/plans/status': () => { throw new E.FakeApiError(503, 'unavailable', 'Capture plans are not available.'); } }, async ({ ctx, root }) => {
      eq(await planStatusCard(ctx, root, 0), null); eq(E.textOf(root), '');
    });
  },
  async undoLatestIsNamedAsAGroupActionAndSendsTheExpectedId() {
    await with_({
      'GET /api/publish': { stack: [{ id: 9, kind: 'capture_plan', mode: 'SHADOW', pipelineId: 'p1', version: 2, groupId: 'g1', rolledBackAt: null }], interpreter: null },
      'GET /api/publish/history': [], 'GET /api/plans/status': { active: null, rows: [] }, 'POST /api/publish/rollback': { publishId: 9 },
    }, async ({ api, ctx, root, env }) => {
      await publishCard(ctx, root);
      const b = E.buttons(root, 'Undo latest (whole group)')[0];
      ok(b, 'group-aware label');
      env.confirmAnswer = true; E.click(b); await E.settle();
      ok(env.confirms[0].startsWith('Undo the whole group'), env.confirms[0]);
      eq(api.calls.find((c) => c.path === '/api/publish/rollback').body, { expectedId: 9 });
    });
  },
};
