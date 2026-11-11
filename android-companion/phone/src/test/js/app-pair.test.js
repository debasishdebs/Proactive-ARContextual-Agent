// Codex r22: the REAL app.js booted from a pairing code (#pair=<code>, a fresh browser session): once paired, the data check runs as on every other boot path.
import * as E from './env.js';
import { find } from './fake-dom.js';
import { eq, ok } from './fixtures.js';

export const tests = {
  async aSessionPairedByCodeReadsItsOpenViewAgainAfterARemoval() {
    const env = E.setup(['app', 'nav', 'banner']);
    let gen = 3;
    const table = E.routes({ 'POST /api/pair': { token: 'paired-token' }, 'GET /api/data-generation': () => ({ gen }) });
    const api = E.fakeApi(table);
    globalThis.fetch = async (path, init) => {
      try {
        const v = await api(init.method, path, init.body ? JSON.parse(init.body) : undefined);
        return { ok: true, status: 200, json: async () => v, blob: async () => ({}) };
      } catch (e) { return { ok: false, status: e.status, json: async () => ({ error: e.code, message: e.message }) }; }
    };
    globalThis.React = { createElement: () => null };
    globalThis.ReactDOM = { createRoot() { return { render() {}, unmount() {} }; } };
    env.silentHash('#pair=123456');
    await import('../../main/assets/canvas/app.js');
    await E.settle(400);
    const root = document.getElementById('app');
    ok(E.textOf(root).includes('Pair this browser'), 'the pairing screen');
    const check = env.intervals.find((x) => x.ms === 5000);
    ok(check, 'the data check is installed on the pairing boot path');
    ok((document.listeners.visibilitychange || []).length === 1, 'and the visibility check');
    E.click(E.buttons(root, 'Pair')[0]);
    await E.settle(600);
    ok(!E.textOf(root).includes('Pair this browser'), 'paired');
    globalThis.location.hash = '#/ask/a1';
    await E.settle(600);
    const reads = () => api.calls.filter((c) => c.path === '/api/asks/a1').length;
    const before = reads();
    ok(before >= 1, 'the ask is open');
    await check.fn(); await E.settle(200);
    eq(reads(), before, 'an unchanged generation reads nothing again');
    gen = 4;
    await check.fn(); await E.settle(600);
    eq(reads(), before + 1, 'a removal reads the open view again');
    ok(find(root, (e) => e.tagName === 'input' && e.getAttribute('aria-label') === 'Pairing code') === null, 'still signed in');
  },
};
