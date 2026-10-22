// Every non-editor view renders from canned JSON, with the fake DOM and a fake api. The editor is stubbed (React is not loaded here).
import { pipelinesView, pipelineView, runNewView, runView, compareView } from '../../main/assets/canvas/views.js';
import { diffView } from '../../main/assets/canvas/versions.js';
import { liveView } from '../../main/assets/canvas/liveview.js';
import { asksView, askView } from '../../main/assets/canvas/asks.js';
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

async function withView(over, fn) {
  const env = E.setup(['app']);
  try {
    const api = E.fakeApi(E.routes(over));
    const ctx = E.fakeCtx(api);
    const root = document.getElementById('app');
    await fn({ env, api, ctx, root });
  } finally { env.uninstall(); }
}
const has = (root, text) => ok(E.textOf(root).includes(text), 'expected "' + text + '" in: ' + E.textOf(root).slice(0, 600));
const radios = (root) => findAll(root, (e) => e.tagName === 'input' && e.getAttribute('type') === 'radio');
/** Picks radio [i] the way a browser does: the others in the group clear. */
const choose = (rs, i) => rs.forEach((r, k) => { r.checked = k === i; });
const withClass = (root, c) => findAll(root, (e) => e.classList.contains(c));
const hrefs = (root) => findAll(root, (e) => e.tagName === 'a').map((a) => a.getAttribute('href'));

export const tests = {
  async pipelineViewRenders() {
    stubReact();
    try {
      await withView({}, async ({ ctx, root }) => {
        await pipelineView(ctx, root, 'p1');
        has(root, 'Live clone'); has(root, 'v1'); has(root, 'v2');
        const hs = hrefs(root);
        ok(hs.includes('#/p/p1/diff?a=1&b=2'), 'diff with previous: ' + hs);
        ok(hs.includes('#/p/p1/diff?a=2&b=draft'), 'diff with draft: ' + hs);
        ok(!hs.includes('#/p/p1/diff?a=0&b=1'), 'v1 has no previous');
      });
    } finally { unstubReact(); }
  },
  async publishDialogShowsHonestCostAndModes() {
    stubReact();
    try {
      await withView({}, async ({ ctx, root, env, api }) => {
        await pipelineView(ctx, root, 'p1');
        E.click(E.buttons(root, 'Publish')[1]); // v2, which carries its own shape
        const m = ctx.modals[ctx.modals.length - 1];
        eq(m.title, 'Publish v2');
        const t = E.textOf(m.content);
        for (const s of ['Prompts only', 'Shadow', 'Live', 'unmetered', '1 in 4', '240 s', 'one live fallback', 'about 4–6× the model calls of Live extraction', 'Live always runs the confidence check']) ok(t.includes(s), 'dialog lacks ' + s);
        eq(radios(m.content).map((r) => r.value), ['prompts', 'shadow', 'live']);
        // Live asks a second time; a "no" sends nothing
        choose(radios(m.content), 2);
        env.confirmAnswer = false;
        await E.runAction(ctx, 'Publish');
        eq(api.calls.filter((c) => c.method === 'POST').length, 0, 'declined second confirm publishes nothing');
        env.confirmAnswer = true;
        api.calls.length = 0;
      });
    } finally { unstubReact(); }
  },
  async publishDialogWithoutAVersionShapeHidesTheDraftCost() {
    stubReact();
    try {
      await withView({}, async ({ ctx, root }) => {
        await pipelineView(ctx, root, 'p1');
        E.click(E.buttons(root, 'Publish')[0]); // v1 has no shape of its own
        const t = E.textOf(ctx.modals[0].content);
        ok(t.includes('The cost estimate describes your current draft; this version may differ. See Diff with draft.'), t);
        ok(!t.includes('about 4–6×') && !t.includes('Live always runs the confidence check and the critic after'), 'no draft numbers or warnings: ' + t);
      });
    } finally { unstubReact(); }
  },
  async publishSendsTheChosenModeAndShowsBlockedReasons() {
    stubReact();
    try {
      let n = 0;
      await withView({
        'POST /api/pipelines/p1/versions/2/publish': (body) => {
          n++;
          if (n === 1) { throw new E.FakeApiError(409, 'publish_blocked', 'Blocked.', ['node x: not interpretable']); }
          return { publishId: 8, keys: ['EXTRACT'], kind: 'interpreter', mode: 'SHADOW' };
        },
      }, async ({ ctx, root, api }) => {
        await pipelineView(ctx, root, 'p1');
        E.click(E.buttons(root, 'Publish')[1]);
        choose(radios(ctx.modals[0].content), 1); // shadow: no second confirm
        await E.runAction(ctx, 'Publish');
        eq(api.calls.filter((c) => c.method === 'POST')[0].body, { mode: 'shadow' });
        eq(ctx.errors[0].problems, ['node x: not interpretable']);
        await E.runAction(ctx, 'Publish');
        ok(ctx.notices.some((t) => t.includes('Published')), 'notice: ' + ctx.notices);
      });
    } finally { unstubReact(); }
  },
  async rollbackSendsExpectedIdAndShowsRaceTexts() {
    let n = 0;
    await withView({
      'POST /api/publish/rollback': () => {
        n++;
        if (n === 1) throw new E.FakeApiError(409, 'rollback_blocked', 'Blocked.', ['That publish was already rolled back.']);
        if (n === 2) throw new E.FakeApiError(409, 'rollback_blocked', 'Blocked.', ['A newer publish exists. Reload and try again.']);
        return { publishId: 7, keys: [], kind: 'interpreter' };
      },
    }, async ({ ctx, root, api, env }) => {
      await pipelinesView(ctx, root);
      has(root, '#7'); has(root, 'SHADOW');
      const b = E.buttons(root, 'Roll back')[0];
      ok(b, 'a Roll back button');
      env.confirmAnswer = false;
      E.click(b); await E.settle();
      eq(env.confirms[0], 'Roll back #7 · interpreter (SHADOW) · p1 v2?');
      eq(api.calls.filter((c) => c.path === '/api/publish/rollback').length, 0, 'declined: nothing is undone');
      env.confirmAnswer = true;
      E.click(b); await E.settle();
      eq(api.calls.filter((c) => c.path === '/api/publish/rollback')[0].body, { expectedId: 7 });
      eq(ctx.errors[0].problems, ['That publish was already rolled back.']);
      E.click(E.buttons(root, 'Roll back')[0]); await E.settle();
      eq(ctx.errors[1].problems, ['A newer publish exists. Reload and try again.']);
    });
  },
  async nothingToRollBackShowsNoButton() {
    await withView({ 'GET /api/publish': E.noPublish }, async ({ ctx, root }) => {
      await pipelinesView(ctx, root);
      eq(E.buttons(root, 'Roll back').length, 0);
    });
  },
  async historyShowsKindModeAndReason() {
    await withView({}, async ({ ctx, root }) => {
      await pipelinesView(ctx, root);
      has(root, '#6 · interpreter (LIVE) · p1 v1');
      has(root, 'Rolled back: fallbacks');
    });
  },
  async diffViewRenders() {
    await withView({}, async ({ ctx, root, api }) => {
      await diffView(ctx, root, 'p1', '1', 'draft');
      eq(api.calls.find((c) => c.path.startsWith('/api/pipelines/p1/diff')).path, '/api/pipelines/p1/diff?a=1&b=draft');
      has(root, 'Changed node extract: batch'); has(root, 'a → b (when x)'); has(root, 'old line'); has(root, 'new line');
      eq(withClass(root, 'del').length >= 1 && withClass(root, 'add').length >= 1, true);
    });
  },
  async liveViewRenders() {
    await withView({}, async ({ ctx, root }) => {
      await liveView(ctx, root);
      await E.settle();
      has(root, 'SHADOW'); has(root, 'interpreted 12'); has(root, 'fallbacks 1'); has(root, 'meeting 3');
      has(root, 'a desk'); // the live memory of f1
      has(root, 'batch: none'); // f2 has batch null
      ok(ctx.images.includes('/api/frames/f1/thumb'));
      eq(findAll(root, (e) => e.tagName === 'b').length, 0, 'model text is text, never markup');
      has(root, '<b>x</b>');
    });
  },
  async liveViewWithoutInterpreter() {
    await withView({ 'GET /api/live': { active: false, interpreted: 0, fallbacks: 0, labels: {}, frames: [] } }, async ({ ctx, root }) => {
      await liveView(ctx, root);
      has(root, 'No interpreter is published.');
    });
  },
  async runNewHasDayPickerAndAbEstimate() {
    await withView({
      'POST /api/runs/estimate-ab': { nominal: 10, max: 12, photos: 1, models: [], confirmToken: 'tok', tokens: { promptTokens: 12000, completionTokens: 2000, basedOn: 40 } },
      'POST /api/runs/ab': { group: 'g1', runA: 'r1', runB: 'r2' },
    }, async ({ ctx, root, api }) => {
      await runNewView(ctx, root, 'p1', '1');
      const day = findAll(root, (e) => e.tagName === 'input' && e.getAttribute('type') === 'date')[0];
      ok(day, 'a date input');
      day.value = '2026-10-21'; day.dispatch('change'); await E.settle();
      ok(api.calls.some((c) => c.path.startsWith('/api/frames?') && c.query.get('day') === '2026-10-21'), 'frames asked with day');
      const sel = findAll(root, (e) => e.tagName === 'select' && e.getAttribute('aria-label') === 'A/B against…')[0];
      ok(sel, 'A/B selector');
      eq(findAll(sel, (e) => e.tagName === 'option').map((o) => o.value).includes('p1@1'), false, 'the base is not offered against itself');
      sel.value = 'p1@2'; sel.dispatch('change');
      E.click(findAll(root, (e) => e.classList.contains('thumb'))[0]);
      E.click(E.buttons(root, 'Estimate')[0]); await E.settle();
      eq(api.calls.find((c) => c.path === '/api/runs/estimate-ab').body, { a: { pipelineId: 'p1', version: 1 }, b: { pipelineId: 'p1', version: 2 }, frameIds: ['f1'] });
      const t = E.textOf(ctx.modals[0].content);
      ok(t.includes('about 12k tokens in, 2k out, from 40 earlier items'), t);
      ok(t.includes('Live clone v1') && t.includes('Live clone v2'), t);
      await E.runAction(ctx, 'Start');
      eq(api.calls.find((c) => c.path === '/api/runs/ab').body, { confirmToken: 'tok', a: { pipelineId: 'p1', version: 1 }, b: { pipelineId: 'p1', version: 2 }, frameIds: ['f1'] });
      eq(ctx.gone, ['#/run/r1']);
    });
  },
  async motionOnlyUnderstandsTheServersEnumValues() {
    // GET /api/frames returns frame_meta.motion as a string (MotionClass / MotionState names), not a number or a boolean
    const frames = ['WALKING', 'VEHICLE', 'MOVING', 'HEAD_MOVING', 'STILL', 'UNKNOWN', null].map((m, i) => ({ id: 'f' + i, ts: 1000 + i, dwell: false, motion: m }));
    await withView({ 'GET /api/frames': frames }, async ({ ctx, root }) => {
      await runNewView(ctx, root, 'p1', '1');
      const cells = () => findAll(root, (e) => e.classList.contains('thumb')).length;
      eq(cells(), 7);
      const label = findAll(root, (e) => e.tagName === 'label' && E.textOf(e).includes('Motion only'))[0];
      const box = findAll(label, (e) => e.tagName === 'input')[0];
      box.checked = true; box.dispatch('change'); await E.settle();
      eq(cells(), 4, 'WALKING, VEHICLE, MOVING and HEAD_MOVING stay; STILL, UNKNOWN and none are hidden');
    });
  },
  async runViewShowsUsageAndBranches() {
    await withView({}, async ({ ctx, root }) => {
      await runView(ctx, root, 'r1');
      has(root, 'calls 14 (estimated 15) · tokens 12.1k in / 2.3k out · median 2.4 s');
      has(root, 'trust: trusted 2, unsure 1');
      ok(hrefs(root).includes('#/run/r2'), 'link to the partner run');
      eq(ctx.gone, [], 'a finished pair opened from a link is not bounced to compare');
    });
  },
  async runOpensCompareWhenTheWatchedPairFinishes() {
    let phase = 0;
    const st = () => [['RUNNING', 'QUEUED'], ['DONE', 'RUNNING'], ['DONE', 'DONE']][phase];
    await withView({
      'GET /api/runs/r1': () => E.run('r1', st()[0], 'g1'),
      'GET /api/runs': () => [E.runRow('r1', st()[0], 'g1', 10), E.runRow('r2', st()[1], 'g1', 20)],
    }, async ({ ctx, root, env }) => {
      await runView(ctx, root, 'r1');
      ok(env.timers.length === 1, 'still polling while the pair is active');
      phase = 1; await env.runTimers();
      ok(env.timers.length === 1, 'A done but B running: keeps polling');
      eq(ctx.gone, []);
      phase = 2; await env.runTimers();
      eq(ctx.gone, ['#/compare?a=r1&b=r2']);
    });
  },
  async finishedRunAKeepsItsPageWhileBRuns() {
    let phase = 0;
    await withView({
      'GET /api/runs/r1': () => E.run('r1', 'DONE', 'g1'),
      'GET /api/runs': () => [E.runRow('r1', 'DONE', 'g1', 10), E.runRow('r2', phase === 0 ? 'RUNNING' : 'RUNNING', 'g1', 20)],
      'POST /api/runs/r1/cancel': { ok: true },
    }, async ({ ctx, root, env, api }) => {
      await runView(ctx, root, 'r1');
      ok(E.buttons(root, 'Cancel A/B pair').length === 1, 'cancel stays while B runs');
      const marker = document.createElement('i');
      root.appendChild(marker); // stands for an opened <details> or typed note
      await env.runTimers();
      ok(marker.parentNode === root, 'the page was not redrawn');
      ok(env.timers.length === 1, 'still watching B');
    });
  },
  async hiddenTabPausesThePollAndResumes() {
    await withView({ 'GET /api/runs/r1': E.run('r1', 'RUNNING', 'g1') }, async ({ ctx, root, env, api }) => {
      let hidden = true;
      Object.defineProperty(document, 'hidden', { get: () => hidden, configurable: true });
      const handlers = [];
      document.addEventListener = (t, f) => { if (t === 'visibilitychange') handlers.push(f); };
      document.removeEventListener = () => {};
      await runView(ctx, root, 'r1');
      eq(env.timers.length, 0, 'no timer while hidden');
      const n = api.calls.length;
      hidden = false; for (const f of handlers) await f();
      ok(api.calls.length > n, 'resumed with a fetch');
      eq(env.timers.length, 1);
    });
  },
  async runCancelOnAGroupIsLabelledAsOne() {
    await withView({ 'GET /api/runs/r1': E.run('r1', 'RUNNING', 'g1'), 'POST /api/runs/r1/cancel': { ok: true } }, async ({ ctx, root, api, env }) => {
      await runView(ctx, root, 'r1');
      const b = E.buttons(root, 'Cancel A/B pair')[0];
      ok(b, 'cancel label for a group');
      E.click(b); await E.settle();
      ok(api.calls.some((c) => c.path === '/api/runs/r1/cancel'));
    });
  },
  async runPollRetriesThenStops() {
    await withView({ 'GET /api/runs/r1': () => { throw new E.FakeApiError(0, 'network', 'The phone could not be reached.'); } }, async ({ ctx, root, api, env }) => {
      await runView(ctx, root, 'r1');
      const delays = [];
      for (let i = 0; i < 6; i++) {
        if (!env.timers.length) break;
        delays.push(env.timers[0].ms);
        await env.runTimers();
      }
      eq(delays, [1000, 2000, 4000]);
      eq(api.calls.filter((c) => c.path === '/api/runs/r1').length, 4, 'first try plus three retries');
      eq(ctx.errors.length, 1, 'one error, shown when the retries are used up');
      eq(env.timers.length, 0);
    });
  },
  async compareShowsAgreement() {
    await withView({}, async ({ ctx, root }) => {
      await compareView(ctx, root, 'r1', 'r2');
      has(root, '50%'); has(root, 'trusted 2'); has(root, 'trusted 1, unsure 1'); has(root, 'extract');
      has(root, 'calls 14 (estimated 15)');
      ok(ctx.images.includes('/api/frames/f1/thumb'), 'thumbnails stay');
    });
  },
  async asksListRenders() {
    await withView({}, async ({ ctx, root }) => {
      await asksView(ctx, root);
      has(root, 'where was my laptop?'); has(root, '(redacted)');
      ok(hrefs(root).includes('#/ask/a1'));
    });
  },
  async askViewRendersHitsAndPrompt() {
    await withView({ 'POST /api/check-prompt': { problems: ['needs {{question}}'] }, 'POST /api/asks/a1/replay': { answer: 'Replayed answer', sources: ['m1'], latencyMs: 1200, promptTokens: 100, completionTokens: 20, model: 'mm' } }, async ({ ctx, root, api }) => {
      await askView(ctx, root, 'a1');
      for (const a of findAll(root, (e) => e.tagName === 'textarea')) eq([a.getAttribute('autocomplete'), a.getAttribute('spellcheck')], ['off', 'false']);
      has(root, '0.123'); has(root, 'cited'); has(root, 'in prompt'); has(root, 'a desk with <b>a laptop</b>');
      has(root, 'Re-retrieved as of the ask\'s time; may differ if settings or embeddings changed');
      ok(ctx.images.includes('/api/frames/f1/thumb'));
      const areas = findAll(root, (e) => e.tagName === 'textarea');
      const prompt = areas.find((a) => a.value.startsWith('SYSTEM <script>'));
      ok(prompt && prompt.hasAttribute('readonly'), 'composed prompt in a read-only textarea');
      eq(findAll(root, (e) => e.tagName === 'script' || e.tagName === 'b').length, 0, 'text only');
      const tpl = areas.find((a) => a.value === 'TEMPLATE {{q}}');
      ok(tpl && !tpl.hasAttribute('readonly'), 'editable template');
      tpl.value = 'EDITED';
      E.click(E.buttons(root, 'Check')[0]); await E.settle();
      eq(api.calls.find((c) => c.path === '/api/check-prompt').body, { nodeType: 'ask.answer', template: 'EDITED' });
      has(root, 'needs {{question}}');
      E.click(E.buttons(root, 'Replay')[0]);
      ok(E.textOf(ctx.modals[0].content).includes('1 model call'));
      await E.runAction(ctx, 'Replay'); await E.settle();
      eq(api.calls.find((c) => c.path === '/api/asks/a1/replay').body, { template: 'EDITED' });
      has(root, 'Replayed answer'); has(root, 'mm');
    });
  },
  async askReplayDeadlineText() {
    await withView({ 'POST /api/asks/a1/replay': () => { throw new E.FakeApiError(503, 'busy', 'The phone is busy. Try again.'); } }, async ({ ctx, root }) => {
      await askView(ctx, root, 'a1');
      E.click(E.buttons(root, 'Replay')[0]);
      await E.runAction(ctx, 'Replay'); await E.settle();
      ok(ctx.errors[0].message.includes('took too long'), ctx.errors[0].message);
    });
  },
  async askRedactedShowsMessage() {
    await withView({ 'GET /api/asks/a2': () => { throw new E.FakeApiError(409, 'refused', 'This ask was redacted'); } }, async ({ ctx, root }) => {
      await askView(ctx, root, 'a2');
      has(root, 'This ask was redacted');
      eq(ctx.errors.length, 0);
    });
  },
};
