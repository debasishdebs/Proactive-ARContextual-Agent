// A browser-ish environment for the view tests under GraalJS: the fake DOM, window, location, timers, a fake api and canned JSON.
import { installFakeDom, uninstall, findAll } from './fake-dom.js';
import { nodeTypes, livePipe, liveCells } from './fixtures.js';

export const textOf = (el) => el.textContent;
export const buttons = (root, label) => findAll(root, (e) => e.tagName === 'button' && e.textContent === label);
export const links = (root, label) => findAll(root, (e) => e.tagName === 'a' && e.textContent === label);
export const click = (el) => el.dispatch('click');

class Params {
  constructor(init) {
    this.m = new Map();
    if (typeof init === 'string') {
      for (const kv of init.split('&').filter(Boolean)) { const i = kv.indexOf('='); this.m.set(decodeURIComponent(i < 0 ? kv : kv.slice(0, i)), i < 0 ? '' : decodeURIComponent(kv.slice(i + 1))); }
    } else if (init) for (const k of Object.keys(init)) this.m.set(k, String(init[k]));
  }
  get(k) { return this.m.has(k) ? this.m.get(k) : null; }
  set(k, v) { this.m.set(k, String(v)); }
  has(k) { return this.m.has(k); }
  toString() { return [...this.m].map(([k, v]) => encodeURIComponent(k) + '=' + encodeURIComponent(v)).join('&'); }
}

/** Installs the globals; returns {timers, runTimers, confirms, uninstall}. [ids] are the elements getElementById knows. */
export function setup(ids) {
  installFakeDom(ids || []);
  const env = { timers: [], intervals: [], confirms: [], confirmAnswer: true, listeners: {}, prompts: [] };
  globalThis.setTimeout = (fn, ms) => { env.timers.push({ fn, ms }); return env.timers.length; };
  globalThis.setInterval = (fn, ms) => { env.intervals.push({ fn, ms }); return env.intervals.length; }; // run by hand (env.intervals[i].fn())
  globalThis.clearTimeout = () => {};
  globalThis.URLSearchParams = Params;
  globalThis.URL = { createObjectURL: () => 'blob:fake', revokeObjectURL() {} };
  const store = new Map();
  globalThis.sessionStorage = { getItem: (k) => (store.has(k) ? store.get(k) : null), setItem: (k, v) => store.set(k, String(v)), removeItem: (k) => store.delete(k) };
  // like a browser: assigning a different hash fires hashchange later; replaceState changes it silently
  let hash = '';
  const norm = (v) => { const t = String(v); return t.startsWith('#') ? t : '#' + t; };
  globalThis.location = {
    get hash() { return hash; },
    set hash(v) { const n = norm(v); if (n === hash) return; hash = n; Promise.resolve().then(() => { for (const f of env.listeners.hashchange || []) f(); }); },
  };
  env.silentHash = (v) => { hash = norm(v); };
  globalThis.history = { replaceState: (a, b, url) => { hash = norm(String(url).replace(/^\/?/, '')); } };
  globalThis.window = {
    confirm: (t) => { env.confirms.push(t); return env.confirmAnswer; },
    prompt: () => 'copy',
    addEventListener: (type, fn) => { (env.listeners[type] = env.listeners[type] || []).push(fn); },
    removeEventListener() {},
  };
  /** Runs the timers queued now (a timer queued by one of them waits for the next call). */
  env.runTimers = async () => { const t = env.timers.splice(0); for (const x of t) await x.fn(); };
  env.uninstall = () => {
    uninstall();
    for (const k of ['setTimeout', 'setInterval', 'clearTimeout', 'URLSearchParams', 'URL', 'sessionStorage', 'location', 'history', 'window']) delete globalThis[k];
  };
  return env;
}

export const settle = async (n) => { for (let i = 0; i < (n || 40); i++) await Promise.resolve(); };

export class FakeApiError extends Error {
  constructor(status, code, message, problems) { super(message); this.status = status; this.code = code; this.problems = problems || []; }
}

/** A fake api: routes are "METHOD /path" (query ignored) -> value | (body, query) => value. calls records every request. */
export function fakeApi(routes) {
  const calls = [];
  const api = async (method, path, body) => {
    const q = path.indexOf('?');
    const p = q < 0 ? path : path.slice(0, q);
    const query = new Params(q < 0 ? '' : path.slice(q + 1));
    calls.push({ method, path, body, query });
    const r = routes[method + ' ' + p];
    if (r === undefined) throw new FakeApiError(404, 'not_found', 'Not found.');
    const v = typeof r === 'function' ? await r(body, query) : r;
    return JSON.parse(JSON.stringify(v));
  };
  api.calls = calls;
  return api;
}

export function fakeCtx(api, extra) {
  const ctx = {
    api, errors: [], notices: [], modals: [], gone: [], leaves: [], images: [],
    showError(e) { ctx.errors.push(e); }, showNotice(t) { ctx.notices.push(t); }, clearBanner() {},
    imageUrl: async () => null, loadImage(img, path) { ctx.images.push(path); },
    modal(title, content, actions) { const m = { title, content, actions: actions || [], closed: false }; ctx.modals.push(m); return () => { m.closed = true; }; },
    onLeave(fn) { ctx.leaves.push(fn); }, go(h) { ctx.gone.push(h); }, guard() {}, reload: async () => {},
  };
  return Object.assign(ctx, extra || {});
}

/** Runs a modal action the way the Start/Publish button would. */
export async function runAction(ctx, label, i) {
  const m = ctx.modals[i === undefined ? ctx.modals.length - 1 : i];
  const a = m.actions.find((x) => x.label === label);
  if (!a) throw new Error('no action ' + label + ' in ' + m.title);
  await a.run(() => { m.closed = true; });
}

// ---- canned JSON, shaped like the real routes ----
export const pipe = () => JSON.stringify(livePipe());
export const interpretable = { problems: [], warnings: ['Live always runs the confidence check and the critic after extraction'], callRange: [4, 6] };
// v2 carries its own shape (the server serves it); v1 is an older row without one
export const versions = [{ version: 1, hash: 'h1', note: 'first', frozenAt: 1000 }, { version: 2, hash: 'h2', note: 'second', frozenAt: 2000, liveShape: true, interpretable }];
export const publishState = {
  active: false,
  stack: [{ id: 7, kind: 'interpreter', mode: 'SHADOW', pipelineId: 'p1', version: 2, publishedAt: 1000 }],
  interpreter: { id: 7, kind: 'interpreter', mode: 'SHADOW', pipelineId: 'p1', version: 2, publishedAt: 1000, interpreted: 12, fallbacks: 1 },
};
export const noPublish = { active: false, stack: [], interpreter: null };
export const history = [
  { id: 7, kind: 'interpreter', mode: 'SHADOW', pipelineId: 'p1', version: 2, publishedAt: 1000, rolledBackAt: null, rollbackReason: null },
  { id: 6, kind: 'interpreter', mode: 'LIVE', pipelineId: 'p1', version: 1, publishedAt: 500, rolledBackAt: 900, rollbackReason: 'fallbacks' },
];
export const usage = { calls: 14, est: 15, promptTokens: 12100, completionTokens: 2300, medianLatencyMs: 2400 };
export const runRow = (id, status, group, startedAt) => ({ runId: id, abGroup: group || null, pipelineId: 'p1', version: 1, status, startedAt: startedAt || 1, frameCount: 1, calls: 1, failures: 0 });
export const run = (id, status, group) => Object.assign(runRow(id, status, group), {
  pipelineJson: pipe(), usage, branches: { trust: { trusted: 2, unsure: 1 } },
  items: [{ frameId: 'f1', nodeId: 'extract', status: 'OK', branch: null, output: { caption: 'a desk', facts: ['laptop'] }, error: null, latencyMs: 5, promptTokens: 1, completionTokens: 1, model: 'm', promptVersion: 1 }],
  scores: [], progress: { done: 1, total: 5 },
});
export const frame = { id: 'f1', ts: 1000, motion: 1, dwell: 0, blur: 0, state: 'KEPT', live: [{ id: 'm1', text: 'a desk', type: 'note' }] };
export const compare = {
  branches: { a: { trust: { trusted: 2 } }, b: { trust: { trusted: 1, unsure: 1 } } }, agreement: { trust: 0.5 }, scoreSums: { extract: { a: 2, b: -1 } },
  frames: [{ frameId: 'f1', nodes: [{ nodeId: 'extract', a: 'x', b: 'y', diff: [{ op: 'removed', text: 'x' }, { op: 'added', text: 'y' }] }] }],
};
export const diff = {
  renamed: false, nodes: [{ nodeId: 'extract', kind: 'changed', params: ['batch'] }], edgesAdded: [{ from: 'a', to: 'b', when: 'x' }], edgesRemoved: [],
  prompts: [{ nodeId: 'extract', part: 'template', lines: [{ op: 'same', text: 'keep' }, { op: 'removed', text: 'old line' }, { op: 'added', text: 'new line' }] }],
};
export const live = {
  active: true, publishId: 7, pipelineId: 'p1', version: 2, mode: 'SHADOW', publishedAt: 1000, interpreted: 12, fallbacks: 1, labels: { meeting: 3, desk: 1 },
  frames: [
    { frameId: 'f1', at: 5, label: 'meeting', batch: 'OK', nodes: [{ nodeId: 'extract', status: 'OK', branch: null, output: '{"caption":"<b>x</b>"}', error: null }] },
    { frameId: 'f2', at: 6, label: null, batch: null, nodes: [] },
  ],
};
export const hit = { memoryId: 'm1', text: 'a desk with <b>a laptop</b>', frameId: 'f1', lexical: 0.5, semantic: 0.25, fused: 0.123456, score: 0.7, cited: true, inPrompt: true };
export const ask = {
  askId: 'a1', ts: 1000, question: 'where was my laptop?', answer: 'On the desk.', cited: ['m1'], rating: 1, mode: 'LIVE', fallback: false,
  hits: [hit, Object.assign({}, hit, { memoryId: 'm2', frameId: null, cited: false, inPrompt: false })], composedPrompt: 'SYSTEM <script>x</script>\nQ: where', activeTemplate: 'TEMPLATE {{q}}', activeVersion: 3,
};
export const pipelinesList = [
  { id: 'p1', name: 'Live clone', builtIn: false, draftRev: 2, versions, live: null },
  { id: 'live-extract', name: 'Live', builtIn: true, draftRev: 1, versions: [{ version: 1, hash: 'h', note: '', frozenAt: 1 }], live: { version: 1 } },
];
export const pipelineData = () => ({
  id: 'p1', name: 'Live clone', json: pipe(), draftRev: 2, builtIn: false, problems: [], problemsDetailed: [], interpretable, layout: { layers: 5, columns: 1, cells: liveCells },
  versions, liveShape: true, live: null, activePrompts: {},
});

/** The whole route table of the canned server; tests override single entries. */
export function routes(over) {
  return Object.assign({
    'GET /api/node-types': nodeTypes,
    'GET /api/pipelines': pipelinesList,
    'GET /api/pipelines/p1': pipelineData(),
    'GET /api/pipelines/p1/diff': diff,
    'GET /api/publish': publishState,
    'GET /api/publish/history': history,
    'GET /api/live': live,
    'GET /api/runs': [runRow('r1', 'DONE', 'g1', 10), runRow('r2', 'DONE', 'g1', 20)],
    'GET /api/runs/r1': run('r1', 'DONE', 'g1'),
    'GET /api/runs/r2': run('r2', 'DONE', 'g1'),
    'GET /api/frames': [{ id: 'f1', ts: 1000, dwell: false, motion: 0 }],
    'GET /api/frames/f1': frame,
    'GET /api/compare': compare,
    'GET /api/asks': [{ id: 'a1', ts: 1000, question: 'where was my laptop?', answer: 'On the desk.', rating: 1, redacted: false }, { id: 'a2', ts: 900, question: '', answer: '', rating: 0, redacted: true }],
    'GET /api/asks/a1': ask,
    'POST /api/check-prompt': { problems: [] },
  }, over || {});
}
