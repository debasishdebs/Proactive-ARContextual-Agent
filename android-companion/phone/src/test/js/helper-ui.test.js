// WP9: the browser side of the laptop Python helper. Status only: the page shows the helper card, the per-run confirmation state ("waiting for your tap on the phone",
// declined, expired) and the results; it has NO control that confirms, declines or lists jobs (the tap happens on the phone). Model and helper text is placed with
// textContent only. Everything renders from canned JSON over the fake DOM.
import { runView, runNewView } from '../../main/assets/canvas/views.js';
import * as H from '../../main/assets/canvas/helperview.js';
import { fields } from '../../main/assets/canvas/paramform.js';
import { findAll } from './fake-dom.js';
import { eq, ok } from './fixtures.js';
import * as E from './env.js';

function stubReact() {
  globalThis.React = { createElement: () => null };
  globalThis.ReactDOM = { createRoot() { return { render() {}, unmount() {} }; } };
  globalThis.ReactFlow = { ReactFlowProvider: {}, ReactFlow: {}, Handle: {}, Position: { Top: 'top', Bottom: 'bottom' }, useReactFlow: () => ({}) };
  globalThis.htm = { bind: () => (strings, ...vals) => ({ strings, vals }) };
}
const unstubReact = () => { delete globalThis.React; delete globalThis.ReactDOM; delete globalThis.ReactFlow; delete globalThis.htm; };

const NOW = 5_000_000;
const pyPipe = () => JSON.stringify({
  schema: 2, id: 'py1', name: 'Py',
  nodes: [
    { id: 'photos', type: 'input.photos', title: 'Photo', params: {} },
    { id: 'intent', type: 'ai.prompt', title: 'Intent', params: { schema: '{"type":"object"}' } },
    { id: 'fn', type: 'py.fn', title: 'Python', params: { source: 'def run(x):\n  return {"n": 1}\n', outSchema: '{"type":"object"}' } },
  ],
  edges: [{ from: 'photos', to: 'intent' }, { from: 'intent', to: 'fn' }],
});
const momentPipe = () => JSON.stringify({
  schema: 2, id: 'mp1', name: 'Moments',
  nodes: [
    { id: 'moments', type: 'input.moments', title: 'Moments', params: {} },
    { id: 'fn', type: 'py.fn', title: 'Python', params: { source: 'def run(x):\n  return {"n": 1}\n', outSchema: '{"type":"object"}' } },
  ],
  edges: [{ from: 'moments', to: 'fn' }],
});
const scenePipe = () => JSON.stringify({
  schema: 2, id: 'sp1', name: 'Scenes',
  nodes: [
    { id: 'scenes', type: 'input.scenes', title: 'Scenes', params: {} },
    { id: 'ask', type: 'ai.prompt', title: 'Prompt', params: { schema: '{"type":"object"}' } },
  ],
  edges: [{ from: 'scenes', to: 'ask' }],
});
const sceneList = [
  { id: 'S69011b80', startMs: 1761000000000, endMs: 1761000030000, rule: 'FRAME_CHANGE', activity: 'WALKING', momentIds: ['69011b80', '69011b9e'], derived: {} },
  { id: 'S69011d10', startMs: 1761000400000, endMs: 1761000400000, rule: 'HOUR_END', activity: null, momentIds: ['69011d10'], derived: {} },
];
const momentList = [
  { id: '69011b80', startMs: 1761000000000, photos: 2, memories: 1, marks: 0 },
  { id: '69011b85', startMs: 1761000005000, photos: 1, memories: 0, marks: 1 },
];
const item = (nodeId, over) => Object.assign({ frameId: 'f1', nodeId, status: 'OK', branch: null, output: null, error: null, latencyMs: 5, promptTokens: 0, completionTokens: 0, model: null, promptVersion: null }, over || {});
const pyRun = (status, items) => ({
  runId: 'rp1', abGroup: null, pipelineId: 'py1', version: null, status, startedAt: 1, frameCount: 1, calls: 1, failures: 0,
  pipelineJson: pyPipe(), usage: { calls: 1, est: 1, promptTokens: 1, completionTokens: 1, medianLatencyMs: 5 }, branches: {}, items: items || [], scores: [], progress: { done: 1, total: 3 },
});
const st = (over) => Object.assign({ paired: true, grants: { py_exec: true }, ready: true, readyStale: false, awaitingConfirm: 0, confirmExpiresAtMs: null, serverTimeMs: NOW }, over || {});

async function withView(over, fn) {
  const env = E.setup(['app']);
  stubReact();
  try {
    const api = E.fakeApi(E.routes(over));
    const ctx = E.fakeCtx(api);
    await fn({ env, api, ctx, root: document.getElementById('app') });
  } finally { unstubReact(); env.uninstall(); }
}
const text = (root) => E.textOf(root);
const has = (root, t) => ok(text(root).includes(t), 'expected "' + t + '" in: ' + text(root).slice(0, 700));
const lacks = (root, t) => ok(!text(root).includes(t), 'did not expect "' + t + '" in: ' + text(root).slice(0, 700));
const controls = (el) => findAll(el, (e) => ['button', 'input', 'select', 'textarea', 'a', 'form'].includes(e.tagName));
const cards = (root) => findAll(root, (e) => e.classList.contains('helper-card'));

export const tests = {
  // ---- the pure model ----
  statusLabelShowsStoppedForgetDistinctly() {
    const f = H.statusLabel('STOPPED_FORGET');
    ok(f.includes('forgotten'), f);
    ok(f !== H.statusLabel('CANCELLED') && f !== 'STOPPED_FORGET', 'a distinct, readable label: ' + f);
    eq(H.statusLabel('DONE'), 'DONE');
    eq(H.statusLabel('CANCELLED'), 'CANCELLED');
    eq(H.statusLabel(undefined), '');
  },
  errorTextMapsHelperCodesAndPassesOthersThrough() {
    ok(H.errorText('not_confirmed').includes('declined'), H.errorText('not_confirmed'));
    ok(H.errorText('not_confirmed').includes('expired') || H.errorText('not_confirmed').includes('ended'), H.errorText('not_confirmed'));
    ok(H.errorText('not_confirmed').includes('Nothing was sent'), H.errorText('not_confirmed'));
    ok(H.errorText('frame_forget').includes('forgotten'));
    for (const c of ['voided', 'killed', 'timeout', 'no_helper', 'resources_busy', 'needs_runtime', 'oom', 'runtime_error', 'bad_output', 'too_large', 'disk_low', 'schema_mismatch', 'exception', 'too_big', 'no_result', 'refused', 'cancelled']) {
      ok(H.errorText(c) && H.errorText(c) !== c, c + ' has plain text');
    }
    eq(H.errorText('NOT_SET'), 'NOT_SET');
    eq(H.errorText('<b>x</b>'), '<b>x</b>', 'unknown codes are shown as they are (as text)');
  },
  secondsLeftUsesThePhoneClockNotTheBrowsers() {
    eq(H.secondsLeft(st({ awaitingConfirm: 1, confirmExpiresAtMs: NOW + 95_000 })), 95);
    eq(H.secondsLeft(st({ awaitingConfirm: 1, confirmExpiresAtMs: NOW + 94_001 })), 95);
    eq(H.secondsLeft(st({ awaitingConfirm: 1, confirmExpiresAtMs: NOW - 5 })), 0);
    eq(H.secondsLeft(st({ awaitingConfirm: 0, confirmExpiresAtMs: null })), null);
    eq(H.secondsLeft(st({ awaitingConfirm: 1, confirmExpiresAtMs: NOW + 5000, serverTimeMs: undefined })), null, 'without the phone clock there is no countdown');
    eq(H.mmss(95), '1:35'); eq(H.mmss(7), '0:07'); eq(H.mmss(0), '0:00');
  },
  helperCardStates() {
    const lines = (s) => H.helperLines(s).map((l) => l.text).join(' | ');
    ok(lines(null).includes('unavailable'), lines(null));
    ok(lines(st({ paired: false })).includes('not paired'), lines(st({ paired: false })));
    const off = lines(st({ grants: { py_exec: false } }));
    ok(off.includes('paired') && off.includes('Python') && off.includes('off') && off.includes('on the phone'), off);
    const ready = lines(st());
    ok(ready.includes('ready') && !ready.includes('not ready'), ready);
    ok(lines(st({ ready: false })).includes('not ready'), lines(st({ ready: false })));
    ok(lines(st({ readyStale: true })).includes('not ready') || lines(st({ readyStale: true })).includes('stale'), lines(st({ readyStale: true })));
    const wait = lines(st({ awaitingConfirm: 2, confirmExpiresAtMs: NOW + 61_000 }));
    ok(wait.includes('Waiting for your tap on the phone'), wait);
    ok(wait.includes('1:01'), wait);
    ok(wait.includes('2 runs') || wait.includes('2 '), wait);
  },

  // ---- the views ----
  async runViewShowsWaitingForYourTapWithCountdownAndNoControl() {
    await withView({
      'GET /api/runs/rp1': pyRun('RUNNING', [item('intent', { output: { a: 1 } })]),
      'GET /api/runs': [E.runRow('rp1', 'RUNNING', null, 1)],
      'GET /api/helper-status': st({ awaitingConfirm: 1, confirmExpiresAtMs: NOW + 95_000 }),
    }, async ({ ctx, root, api }) => {
      await runView(ctx, root, 'rp1');
      has(root, 'Waiting for your tap on the phone');
      has(root, '1:35');
      const card = cards(root);
      eq(card.length, 1, 'one helper card');
      eq(controls(card[0]).map((e) => e.tagName), [], 'the card has no control at all: the tap happens on the phone');
      const labels = findAll(root, (e) => e.tagName === 'button').map((b) => E.textOf(b).toLowerCase());
      ok(!labels.some((l) => /confirm|approve|allow|accept|decline|deny|yes/.test(l)), 'no confirm-like button anywhere: ' + labels);
      eq(api.calls.filter((c) => c.method !== 'GET').length, 0, 'the page sent nothing but reads');
      ok(api.calls.some((c) => c.method === 'GET' && c.path === '/api/helper-status'));
      eq(api.calls.filter((c) => c.path.includes('helper') && c.path !== '/api/helper-status').length, 0, 'no other helper route is touched');
    });
  },
  async runViewShowsDeclinedAndExpiredAsNothingSent() {
    await withView({
      'GET /api/runs/rp1': pyRun('DONE', [item('intent', { output: { a: 1 } }), item('fn', { status: 'ERROR', error: 'not_confirmed' })]),
      'GET /api/runs': [E.runRow('rp1', 'DONE', null, 1)],
      'GET /api/helper-status': st(),
    }, async ({ ctx, root, api }) => {
      await runView(ctx, root, 'rp1');
      has(root, 'declined'); has(root, 'expired'); has(root, 'Nothing was sent');
      lacks(root, 'error: not_confirmed');
      eq(cards(root).length, 0, 'a finished run shows no waiting card');
      eq(api.calls.filter((c) => c.method !== 'GET').length, 0);
    });
  },
  async runViewShowsStoppedForgetDistinctlyAndStopsPolling() {
    await withView({
      'GET /api/runs/rp1': pyRun('STOPPED_FORGET', [item('fn', { status: 'ERROR', error: 'frame_forget' })]),
      'GET /api/runs': [E.runRow('rp1', 'STOPPED_FORGET', null, 1)],
      'GET /api/helper-status': st(),
    }, async ({ ctx, root, env }) => {
      await runView(ctx, root, 'rp1');
      const strong = findAll(root, (e) => e.tagName === 'strong' && e.classList.contains('forgotten'));
      eq(strong.length, 1, 'the status has its own class');
      ok(E.textOf(strong[0]).includes('forgotten'), E.textOf(strong[0]));
      lacks(root, 'error: frame_forget');
      eq(env.timers.length, 0, 'a stopped run is final: no poll');
    });
  },
  async resultsAreRenderedAsTextAndNeverAsMarkup() {
    const evil = '<img src=x onerror=alert(1)>';
    await withView({
      'GET /api/runs/rp1': pyRun('DONE', [item('fn', { output: { n: 1, note: evil } }), item('intent', { status: 'ERROR', error: evil })]),
      'GET /api/runs': [E.runRow('rp1', 'DONE', null, 1)],
    }, async ({ ctx, root }) => {
      await runView(ctx, root, 'rp1');
      has(root, evil);
      eq(findAll(root, (e) => e.tagName === 'img' && e.getAttribute('src') === 'x').length, 0, 'no element was made from the text');
      const pre = findAll(root, (e) => e.tagName === 'pre' && E.textOf(e).includes(evil));
      ok(pre.length >= 1, 'the output sits in a <pre> as text');
    });
  },
  async runViewWithoutPythonNeverAsksTheHelper() {
    await withView({ 'GET /api/runs/r1': E.run('r1', 'RUNNING', null) }, async ({ ctx, root, api }) => {
      await runView(ctx, root, 'r1');
      eq(api.calls.filter((c) => c.path.includes('helper')).length, 0);
      eq(cards(root).length, 0);
    });
  },
  async runViewSurvivesAMissingHelperStatus() {
    await withView({
      'GET /api/runs/rp1': pyRun('RUNNING', []),
      'GET /api/runs': [E.runRow('rp1', 'RUNNING', null, 1)],
      'GET /api/helper-status': () => { throw new E.FakeApiError(500, 'internal', 'x'); },
    }, async ({ ctx, root }) => {
      await runView(ctx, root, 'rp1');
      has(root, 'unavailable');
      eq(ctx.errors.length, 0, 'a failed status read is not an error banner');
    });
  },
  async newRunShowsTheHelperCardForAPythonPipelineAndSaysTheTapIsOnThePhone() {
    await withView({
      'GET /api/pipelines/py1': Object.assign(E.pipelineData(), { id: 'py1', json: pyPipe() }),
      'GET /api/helper-status': st({ grants: { py_exec: false } }),
    }, async ({ ctx, root, api }) => {
      await runNewView(ctx, root, 'py1', '');
      eq(cards(root).length, 1);
      has(root, 'on the phone');
      eq(controls(cards(root)[0]).length, 0);
      eq(api.calls.filter((c) => c.method !== 'GET').length, 0);
    });
  },
  async newRunWithoutPythonShowsNoHelperCard() {
    await withView({}, async ({ ctx, root, api }) => {
      await runNewView(ctx, root, 'p1', '1');
      eq(cards(root).length, 0);
    });
  },

  // ---- Moment-lite: a pipeline that starts at input.moments is run over Moments ----
  async newRunOverMomentsListsMomentsWithPhotoCountsAndSendsMomentIdsNotFrameIds() {
    await withView({
      'GET /api/pipelines/mp1': Object.assign(E.pipelineData(), { id: 'mp1', json: momentPipe() }),
      'GET /api/moments': momentList,
      'GET /api/helper-status': st(),
      'POST /api/runs/estimate': { nominal: 0, max: 0, photos: 2, kind: 'moments', photoCount: 3, models: [], confirmToken: 'tok', tokens: null },
      'POST /api/runs': { runId: 'r9' },
    }, async ({ ctx, root, api }) => {
      await runNewView(ctx, root, 'mp1', '1');
      has(root, '2 photos'); has(root, '1 photo'); has(root, 'on the phone');
      ok(!api.calls.some((c) => c.path.startsWith('/api/frames')), 'the Moment page never lists single frames');
      const boxes = findAll(root, (e) => e.tagName === 'input' && e.getAttribute('type') === 'checkbox');
      eq(boxes.length, 2);
      boxes[0].checked = true; boxes[0].dispatch('change');
      boxes[1].checked = true; boxes[1].dispatch('change');
      has(root, '2 of 50 Moments selected');
      has(root, '3 photos');
      E.click(E.buttons(root, 'Estimate')[0]); await E.settle();
      const est = api.calls.find((c) => c.path === '/api/runs/estimate');
      eq(est.body, { pipelineId: 'mp1', momentIds: ['69011b80', '69011b85'], version: 1 });
      ok(!('frameIds' in est.body), 'no frame ids');
      has(ctx.modals[0].content, '2 Moments');
      await E.runAction(ctx, 'Start');
      eq(api.calls.find((c) => c.path === '/api/runs').body, { confirmToken: 'tok', pipelineId: 'mp1', momentIds: ['69011b80', '69011b85'], version: 1 });
      eq(ctx.gone, ['#/run/r9']);
      // the page has no control that confirms anything: only the select/clear/estimate/day controls
      eq(api.calls.filter((c) => c.method !== 'GET').map((c) => c.path), ['/api/runs/estimate', '/api/runs']);
    });
  },
  async newRunOverMomentsWithNothingSelectedAsksForOne() {
    await withView({
      'GET /api/pipelines/mp1': Object.assign(E.pipelineData(), { id: 'mp1', json: momentPipe() }),
      'GET /api/moments': momentList, 'GET /api/helper-status': st(),
    }, async ({ ctx, root, api }) => {
      await runNewView(ctx, root, 'mp1', '');
      E.click(E.buttons(root, 'Estimate')[0]); await E.settle();
      eq(ctx.notices, ['Select at least one Moment.']);
      ok(!api.calls.some((c) => c.path === '/api/runs/estimate'));
    });
  },
  async aMomentRunViewNamesTheMomentAndNeverAsksForAFrame() {
    await withView({
      'GET /api/runs/rm1': Object.assign(pyRun('DONE', [item('fn', { frameId: 'moment:69011b80', output: { n: 1 } })]), { runId: 'rm1', pipelineJson: momentPipe() }),
      'GET /api/runs': [E.runRow('rm1', 'DONE', null, 1)],
    }, async ({ ctx, root, api }) => {
      await runView(ctx, root, 'rm1');
      has(root, 'Moment 69011b80');
      ok(!api.calls.some((c) => c.path.startsWith('/api/frames')), 'no frame lookup for a Moment item');
    });
  },

  // ---- Scenes: a pipeline that starts at input.scenes is run over Scenes ----
  async newRunOverScenesListsScenesAndSendsSceneIdsOnly() {
    await withView({
      'GET /api/pipelines/sp1': Object.assign(E.pipelineData(), { id: 'sp1', json: scenePipe() }),
      'GET /api/scenes': sceneList,
      'POST /api/runs/estimate': { nominal: 2, max: 2, photos: 2, kind: 'scenes', photoCount: 3, models: [], confirmToken: 'tok', tokens: null },
      'POST /api/runs': { runId: 'r8' },
    }, async ({ ctx, root, api }) => {
      await runNewView(ctx, root, 'sp1', '1');
      ok(api.calls.some((c) => c.path.startsWith('/api/scenes?') && c.path.includes('from=') && c.path.includes('to=')), 'the window is sent');
      ok(!api.calls.some((c) => c.path.startsWith('/api/frames') || c.path.startsWith('/api/moments')), 'the Scene page lists only Scenes');
      has(root, '2 moments'); has(root, 'WALKING');
      const boxes = findAll(root, (e) => e.tagName === 'input' && e.getAttribute('type') === 'checkbox');
      eq(boxes.length, 2);
      boxes[0].checked = true; boxes[0].dispatch('change');
      has(root, '1 of 50 Scenes selected');
      E.click(E.buttons(root, 'Estimate')[0]); await E.settle();
      eq(api.calls.find((c) => c.path === '/api/runs/estimate').body, { pipelineId: 'sp1', sceneIds: ['S69011b80'], version: 1 });
      has(ctx.modals[0].content, '2 Scenes');
      await E.runAction(ctx, 'Start');
      eq(api.calls.find((c) => c.path === '/api/runs').body, { confirmToken: 'tok', pipelineId: 'sp1', sceneIds: ['S69011b80'], version: 1 });
      eq(ctx.gone, ['#/run/r8']);
    });
  },
  async newRunOverScenesWithNothingSelectedAsksForOne() {
    await withView({ 'GET /api/pipelines/sp1': Object.assign(E.pipelineData(), { id: 'sp1', json: scenePipe() }), 'GET /api/scenes': sceneList }, async ({ ctx, root, api }) => {
      await runNewView(ctx, root, 'sp1', '');
      E.click(E.buttons(root, 'Estimate')[0]); await E.settle();
      eq(ctx.notices, ['Select at least one Scene.']);
      ok(!api.calls.some((c) => c.path === '/api/runs/estimate'));
    });
  },
  async aSceneRunViewNamesTheSceneAndNeverAsksForAFrame() {
    await withView({
      'GET /api/runs/rs1': Object.assign(pyRun('DONE', [item('ask', { frameId: 'scene:S69011b80', output: { n: 1 } })]), { runId: 'rs1', pipelineJson: scenePipe() }),
      'GET /api/runs': [E.runRow('rs1', 'DONE', null, 1)],
    }, async ({ ctx, root, api }) => {
      await runView(ctx, root, 'rs1');
      has(root, 'Scene S69011b80');
      ok(!api.calls.some((c) => c.path.startsWith('/api/frames')), 'no frame lookup for a Scene item');
    });
  },

  // ---- the editor's Python field ----
  pythonSourceIsATextareaWithItsCap() {
    const d = [{ name: 'source', kind: 'text', label: 'Source', maxLen: 16000 }, { name: 'outSchema', kind: 'text', label: 'Output schema', maxLen: 4000 }];
    const f = fields(d, { source: 'def run(x):\n  return {}\n' }, {});
    eq(f[0].control, 'textarea'); eq(f[0].maxLen, 16000); eq(f[0].value, 'def run(x):\n  return {}\n');
    eq(f[1].control, 'textarea'); eq(f[1].maxLen, 4000);
  },
};
