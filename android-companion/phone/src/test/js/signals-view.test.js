// The Signals page: the list and the editor over the fake DOM, and "Try it on recent data": one POST /api/signals/try with the unsaved graph, never a PUT; the honest
// "preview unavailable" and the "off" note; the summary with its denominators; the strip path and the id-and-time Moment lines.
import { signalsView, NO_TRY } from '../../main/assets/canvas/signalsview.js';
import * as X from '../../main/assets/canvas/exprgraph.js';
import { findAll, find } from './fake-dom.js';
import { eq, ok } from './fixtures.js';
import * as E from './env.js';

const def = JSON.parse(X.DEFAULT_GRAPH_JSON);
const list = (available) => ({ signals: [{ name: 'gyroMax', enabled: true }, { name: 'stepHz', enabled: false }], derived: [{ name: 'walky', ref: 'd_walky', graph: def }], tryIt: { available } });
const done = (over) => Object.assign({
  ok: true, available: true, role: 'guard', source: 'Latest imported recording', output: 'Raw expression output',
  recording: { fromMs: 1761000000000, coverageSeconds: 700 }, window: { seconds: 600, covered: 571, missing: 29, excludedZoneHeld: 0, excludedZoneUnknown: 0 },
  summary: { valid: 571, denominator: 571, trueCount: 131, trueFrac: 0.23, states: { NOT_READY: 4, ERROR: 0, DIV0: 0, MISSING: 29 } },
  points: [{ s: 1, state: 'BOOL', flag: false }, { s: 2, state: 'BOOL', flag: true }], moments: [{ id: '6ae1c2f0', s: 1761000300 }],
}, over || {});
const byText = (root, t) => find(root, (e) => e.tagName === 'button' && e.textContent === t);
const items = (root) => findAll(root, (e) => e.tagName === 'li').map((e) => e.textContent);

async function open(routes, avail) {
  const env = E.setup(['app']);
  const api = E.fakeApi(Object.assign({ 'GET /api/signals': list(avail), 'PUT /api/signals/derived/walky': { ok: true }, 'DELETE /api/signals/derived/walky': { ok: true } }, routes || {}));
  const ctx = E.fakeCtx(api); const root = document.getElementById('app');
  await signalsView(ctx, root); await E.settle();
  return { env, api, ctx, root };
}

export const tests = {
  realSummaryLabelsTheSourceAndShowsDenominators() {
    const l = X.realSummary(done());
    ok(l[0].startsWith('Latest imported recording: recorded ') && l[0].includes('700 s covered') && l[0].includes('Raw expression output'), l[0]);
    ok(l[1].includes('true 131 of 571 valid seconds (23 %)'), l[1]);
    ok(l[2].includes('valid 571 · covered 571 of 600 s · missing 29 · warming 4'), l[2]);
    ok(X.realSummary(done({ summary: { valid: 3, trueCount: 0, min: 1, max: 2.5, mean: 1.75, states: { NOT_READY: 0, ERROR: 1, DIV0: 0 } } }))[1].startsWith('min 1 · max 2.5 · mean 1.75'));
    eq(X.realSummary({ ok: true, available: false, message: 'Preview unavailable: no zone history.' }), ['Preview unavailable: no zone history.']);
    eq(X.realSummary({ ok: false, problems: ['x'] }), ['x']);
  },
  stripPathScalesBreaksOnGapsAndSurvivesNoValues() {
    eq(X.stripPath([{ value: 0 }, { value: 2 }, { value: 1 }], 100, 40), 'M0 40L50 0L100 20');
    eq(X.stripPath([{ flag: false }, { state: 'NOT_READY' }, { flag: true }], 100, 40), 'M0 40M100 0');
    eq(X.stripPath([{ state: 'MISSING' }], 100, 40), '');
    eq(X.realBody(def, 600), { graph: def, seconds: 600 });
  },
  async listShowsTheCatalogueAndTheDerivedSignals() {
    const t = await open(null, true);
    try { ok(E.textOf(t.root).includes('stepHz (off)') && E.textOf(t.root).includes('d_walky')); } finally { t.env.uninstall(); }
  },
  async runPostsTheUnsavedGraphWithTheChosenWindowAndNeverSaves() {
    const t = await open({ 'POST /api/signals/try': done() }, true);
    try {
      E.click(byText(t.root, 'Edit')); await E.settle();
      const win = find(t.root, (e) => e.getAttribute('aria-label') === 'Window'); win.value = '1800';
      E.click(byText(t.root, 'Run')); await E.settle();
      const posts = t.api.calls.filter((c) => c.method !== 'GET');
      eq(posts.map((c) => c.method + ' ' + c.path), ['POST /api/signals/try']);
      eq(posts[0].body.seconds, 1800); eq(posts[0].body.graph, def);
      ok(items(t.root).some((l) => l.includes('true 131 of 571')) && items(t.root).some((l) => l === 'Photo Moment 6ae1c2f0 at ' + new Date(1761000300 * 1000).toLocaleTimeString()), items(t.root).join('|'));
      ok(findAll(t.root, (e) => e.classList && e.classList.contains('expr-strip')).length === 1, 'the strip');
    } finally { t.env.uninstall(); }
  },
  async anUnavailablePreviewIsShownAsSuchAndTheOffNoteHidesRun() {
    const t = await open({ 'POST /api/signals/try': { ok: true, available: false, reason: 'no_zone_history', message: 'Preview unavailable: no zone history.' } }, true);
    try {
      E.click(byText(t.root, 'New signal')); await E.settle();
      E.click(byText(t.root, 'Run')); await E.settle();
      eq(items(t.root).filter((l) => l.startsWith('Preview')), ['Preview unavailable: no zone history.']);
      eq(findAll(t.root, (e) => e.classList && e.classList.contains('expr-strip')).length, 0);
    } finally { t.env.uninstall(); }
    const off = await open(null, false);
    try { E.click(byText(off.root, 'New signal')); await E.settle(); ok(E.textOf(off.root).includes(NO_TRY)); ok(!byText(off.root, 'Run'), 'no Run while off'); } finally { off.env.uninstall(); }
  },
  async aRefusedTryShowsTheApiMessageAndSaveAndDeleteGoThroughTheDerivedRoutes() {
    const t = await open({ 'POST /api/signals/try': () => { throw new E.FakeApiError(403, 'developer_mode_off', 'Developer mode is off.'); } }, true);
    try {
      E.click(byText(t.root, 'Edit')); await E.settle();
      E.click(byText(t.root, 'Run')); await E.settle();
      ok(items(t.root).includes('Developer mode is off.'));
      E.click(byText(t.root, 'Save')); await E.settle();
      eq(t.api.calls.filter((c) => c.method === 'PUT').map((c) => c.path), ['/api/signals/derived/walky']);
      E.click(byText(t.root, 'Edit')); await E.settle(); E.click(byText(t.root, 'Delete')); await E.settle();
      eq(t.api.calls.filter((c) => c.method === 'DELETE').map((c) => c.path), ['/api/signals/derived/walky']);
    } finally { t.env.uninstall(); }
  },
};
