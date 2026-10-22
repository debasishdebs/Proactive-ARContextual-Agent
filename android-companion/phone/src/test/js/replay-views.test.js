// The replay and sweep views over the fake DOM and a fake api.
import { replayView } from '../../main/assets/canvas/replayview.js';
import { sweepView } from '../../main/assets/canvas/paretoview.js';
import { findAll } from './fake-dom.js';
import { eq, ok } from './fixtures.js';
import * as E from './env.js';

const traces = [{ traceId: 't1', source: 'collect', status: 'DONE', revision: 1, exact: true, startedAtMs: 1000 }, { traceId: 't2', source: 'collect', status: 'DONE', revision: 1, exact: false, startedAtMs: 2000 }];
const FIG = { estimatedPctPerMin: 0.49, estimatedHoursFromFull: 3.4, opensPerMin: 3.4 };
const replayDone = (fusion) => ({ replayId: 'r1', jobState: 'DONE', invalidated: false, createdAt: 1, result: { estimate: true, reports: [{ traceId: 't1', report: {
  replayClass: 'COUNTERFACTUAL', basePassed: 40, baseFailed: 2, inexactWindows: 3, inexactReasons: { CLOCK_GAP: 3 }, shadowOrTrialOnly: fusion, baseline: FIG, candidate: FIG, unobservedOpensPct: 1, warnings: ['replay is approximate'] } }] } });
const pt = (i, hours) => ({ index: i, configHash: 'h' + i, params: { 'gate.hamming': i }, capturesPerMin: 1, opensPerMin: 2, estimatedPctPerMin: 0.5, estimatedHoursFromFull: hours, unobservedOpensPct: 0 });
const sweepDone = (pts) => ({ sweepId: 's1', jobState: 'DONE', invalidated: false, createdAt: 1, result: { estimate: true, mode: 'grid', minutes: 10, inexactWindows: 2, baseline: FIG, points: pts, warnings: [] } });

const has = (root, text) => ok(E.textOf(root).includes(text), 'expected "' + text + '" in: ' + E.textOf(root).slice(0, 700));
const boxes = (root) => findAll(root, (e) => e.tagName === 'input' && e.getAttribute('type') === 'checkbox');
const input = (root, label) => findAll(root, (e) => e.tagName === 'input' && e.getAttribute('aria-label') === label)[0];

async function with_(routes, fn) {
  const env = E.setup(['app']);
  try {
    const api = E.fakeApi(Object.assign({ 'GET /api/traces': traces, 'GET /api/replays': [], 'GET /api/sweeps': [] }, routes));
    await fn({ api, ctx: E.fakeCtx(api), root: document.getElementById('app'), env });
  } finally { env.uninstall(); }
}

export const tests = {
  async replayStartsWithPickedTracesAndShowsExactnessAndFusionWarning() {
    await with_({ 'POST /api/replays': { replayId: 'r1' }, 'GET /api/replays/r1': replayDone(true) }, async ({ api, ctx, root }) => {
      await replayView(ctx, root);
      has(root, 't1'); has(root, 't2 · collect');
      boxes(root)[0].checked = true;
      input(root, 'Candidate config hash (hex)').value = 'ab12';
      input(root, 'Gate hamming').value = '5';
      E.click(E.buttons(root, 'Start replay')[0]); await E.settle();
      const post = api.calls.find((c) => c.method === 'POST');
      eq(post.body, { traceIds: ['t1'], candidate: { configHash: 'ab12', gate: { hamming: 5 } } });
      has(root, '42 windows compared, 3 inexact (excluded)');
      has(root, '0.49 %/min · 3.4 h from full');
      const warn = findAll(root, (e) => e.classList.contains('fusion-warning'));
      ok(warn.length >= 1 && warn[0].getAttribute('role') === 'alert', 'fusion warning is prominent');
      eq(ctx.images.length, 0, 'no frames, no thumbnails');
    });
  },
  async replayWithoutFusionChangeHasNoWarning() {
    await with_({ 'POST /api/replays': { replayId: 'r1' }, 'GET /api/replays/r1': replayDone(false) }, async ({ ctx, root }) => {
      await replayView(ctx, root);
      boxes(root)[0].checked = true;
      E.click(E.buttons(root, 'Start replay')[0]); await E.settle();
      has(root, 'windows compared');
      eq(findAll(root, (e) => e.classList.contains('fusion-warning')).length, 0);
    });
  },
  async replayRefusesWithoutTracesAndPollsWhileRunning() {
    let n = 0;
    await with_({ 'POST /api/replays': { replayId: 'r1' }, 'GET /api/replays/r1': () => (n++ === 0 ? { replayId: 'r1', jobState: 'RUNNING', invalidated: false, createdAt: 1 } : replayDone(false)) }, async ({ api, ctx, root, env }) => {
      await replayView(ctx, root);
      E.click(E.buttons(root, 'Start replay')[0]); await E.settle();
      eq(ctx.errors.length, 1); eq(api.calls.filter((c) => c.method === 'POST').length, 0);
      boxes(root)[1].checked = true;
      E.click(E.buttons(root, 'Start replay')[0]); await E.settle();
      has(root, 'RUNNING'); ok(E.buttons(root, 'Cancel replay').length === 1);
      eq(env.timers.length, 1);
      env.timers[0].fn(); await E.settle();
      has(root, 'windows compared');
    });
  },
  async developerModeOffIsExplained() {
    await with_({ 'GET /api/traces': () => { throw new E.FakeApiError(403, 'developer_mode_off', 'Developer mode is off.'); } }, async ({ ctx, root }) => {
      await replayView(ctx, root); has(root, 'Developer mode is off');
      await sweepView(ctx, root); has(root, 'Developer mode is off');
    });
  },
  async sweepTableSortsThroughTheServer() {
    await with_({
      'POST /api/sweeps': { sweepId: 's1' },
      'GET /api/sweeps/s1': (b, q) => sweepDone(q.get('dir') === 'desc' ? [pt(2, 3.2), pt(1, 3.5)] : [pt(1, 3.5), pt(2, 3.2)]),
    }, async ({ api, ctx, root }) => {
      await sweepView(ctx, root);
      boxes(root)[0].checked = true;
      input(root, 'Param path').value = 'gate.hamming'; input(root, 'Values').value = '1, 2';
      E.click(E.buttons(root, 'Start sweep')[0]); await E.settle();
      eq(api.calls.find((c) => c.method === 'POST').body, { traceIds: ['t1'], mode: 'grid', params: [{ path: 'gate.hamming', values: [1, 2] }] });
      has(root, '2 inexact windows (excluded)'); has(root, 'Baseline: 0.49 %/min');
      const rowsOf = () => findAll(root, (e) => e.tagName === 'tr').slice(1).map((r) => E.textOf(r));
      ok(rowsOf()[0].startsWith('1gate.hamming=1'), rowsOf()[0]);
      has(root, '3.5 h');
      E.click(E.buttons(root, 'Hours from full')[0]); await E.settle();
      ok(api.calls[api.calls.length - 1].path.endsWith('?sort=hours&dir=asc'));
      E.click(E.buttons(root, 'Hours from full ▲')[0]); await E.settle();
      ok(api.calls[api.calls.length - 1].path.endsWith('?sort=hours&dir=desc'), api.calls[api.calls.length - 1].path);
      ok(rowsOf()[0].startsWith('2gate.hamming=2'), 'server order is shown as sent: ' + rowsOf()[0]);
      eq(ctx.images.length, 0);
    });
  },
  async sweepRefusedSpecIsReportedAndNothingStarts() {
    await with_({ 'POST /api/sweeps': () => { throw new E.FakeApiError(400, 'bad_request', 'x: not a sweepable param'); } }, async ({ api, ctx, root }) => {
      await sweepView(ctx, root);
      E.click(E.buttons(root, 'Start sweep')[0]); await E.settle();
      eq(ctx.errors.length, 1); eq(api.calls.filter((c) => c.method === 'POST').length, 0);
      boxes(root)[0].checked = true; input(root, 'Param path').value = 'x'; input(root, 'Values').value = '1';
      E.click(E.buttons(root, 'Start sweep')[0]); await E.settle();
      eq(ctx.errors.length, 2); eq(ctx.errors[1].message, 'x: not a sweepable param');
    });
  },
};
