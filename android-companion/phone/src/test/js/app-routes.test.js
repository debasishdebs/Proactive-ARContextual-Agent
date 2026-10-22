// The REAL app.js booted over the fake DOM, a fake fetch and stub React: every route renders, the guard and the hash restore work.
import * as E from './env.js';
import { eq, ok } from './fixtures.js';

let booted = null;
const SEEN = { requests: [] };

async function boot() {
  if (booted) return booted;
  const env = E.setup(['app', 'nav', 'banner']);
  const table = E.routes();
  const api = E.fakeApi(table);
  globalThis.fetch = async (path, init) => {
    const headers = (init && init.headers) || {};
    SEEN.requests.push({ path, auth: headers.Authorization });
    try {
      const v = await api(init.method, path, init.body ? JSON.parse(init.body) : undefined);
      return { ok: true, status: 200, json: async () => v, blob: async () => ({}) };
    } catch (e) {
      return { ok: false, status: e.status, json: async () => ({ error: e.code, message: e.message, problems: e.problems }) };
    }
  };
  globalThis.React = { createElement: () => null };
  globalThis.ReactDOM = { createRoot() { return { render() {}, unmount() {} }; } };
  globalThis.ReactFlow = { ReactFlowProvider: {}, ReactFlow: {}, Handle: {}, Position: { Top: 'top', Bottom: 'bottom' }, useReactFlow: () => ({}) };
  globalThis.htm = { bind: () => (strings, ...vals) => ({ strings, vals }) };
  env.silentHash('#t=secret-token');
  const app = await import('../../main/assets/canvas/app.js');
  await E.settle(400);
  booted = { env, table, app, api, root: document.getElementById('app'), nav: document.getElementById('nav'), banner: document.getElementById('banner') };
  return booted;
}

async function visit(b, hash) {
  globalThis.location.hash = hash;
  await E.settle(600);
}

const ROUTES = ['#/', '#/p/p1', '#/p/p1/diff?a=1&b=draft', '#/live', '#/run/new?p=p1', '#/run/r1', '#/compare?a=r1&b=r2', '#/asks', '#/ask/a1'];

export const tests = {
  async bootsAndRoutesEveryView() {
    const b = await boot();
    ok(b.root.children.length > 0, 'the boot route rendered');
    for (const r of ROUTES) {
      await visit(b, r);
      ok(b.root.children.length > 0, r + ' rendered nothing');
      eq(b.banner.hidden, true, r + ' showed an error: ' + E.textOf(b.banner));
      ok(!E.textOf(b.root).includes('Not found.'), r + ' is not a route');
    }
  },
  async tokenNeverInAUrl() {
    const b = await boot();
    ok(SEEN.requests.length > 5);
    for (const r of SEEN.requests) {
      ok(!r.path.includes('secret-token') && !r.path.includes('token='), r.path);
      if (r.path !== '/api/pair') eq(r.auth, 'Bearer secret-token');
    }
    eq(globalThis.location.hash.includes('secret-token'), false);
  },
  async unknownRouteShowsNotFound() {
    const b = await boot();
    await visit(b, '#/nope/at/all');
    ok(E.textOf(b.root).includes('Not found.'));
  },
  async navShowsLiveOnlyWithAnInterpreter() {
    const b = await boot();
    await visit(b, '#/');
    ok(E.links(b.nav, 'Live').length === 1, 'Live link with an active interpreter');
    ok(E.links(b.nav, 'Asks').length === 1);
    b.table['GET /api/publish'] = E.noPublish;
    await visit(b, '#/asks');
    eq(E.links(b.nav, 'Live').length, 0);
    b.table['GET /api/publish'] = E.publishState;
  },
  async guardDeclinedKeepsTheViewAndRestoresTheHash() {
    const b = await boot();
    await visit(b, '#/asks');
    const before = b.root.children[0];
    b.app.ctx.guard(() => 'Leave with unsaved edits?');
    b.env.confirmAnswer = false;
    b.env.confirms.length = 0;
    await visit(b, '#/live');
    eq(b.env.confirms, ['Leave with unsaved edits?']);
    eq(globalThis.location.hash, '#/asks', 'the hash is put back');
    ok(b.root.children[0] === before, 'the view was not re-rendered');
    // the put-back hashchange is swallowed, and the guard still holds
    await E.settle(300);
    ok(b.root.children[0] === before);
    b.env.confirmAnswer = true;
    await visit(b, '#/live');
    eq(globalThis.location.hash, '#/live');
    ok(E.textOf(b.root).includes('SHADOW'), 'leaving was allowed once confirmed');
  },
  async guardIsClearedWhenTheViewIsLeft() {
    const b = await boot();
    b.app.ctx.guard(() => 'x');
    b.env.confirmAnswer = true;
    await visit(b, '#/asks');
    b.env.confirms.length = 0;
    await visit(b, '#/live');
    eq(b.env.confirms, [], 'the next view has no guard of its own');
  },
  async signOutAsksWhileUnsaved() {
    const b = await boot();
    await visit(b, '#/asks');
    b.app.ctx.guard(() => 'Unsaved!');
    b.env.confirmAnswer = false; b.env.confirms.length = 0;
    E.click(E.buttons(b.nav, 'Sign out')[0]); await E.settle();
    eq(b.env.confirms, ['Unsaved!']);
    ok(b.root.children.length > 0 && !E.textOf(b.root).includes('Pair this browser'), 'still signed in');
  },
};
