// The Devices page: four switches from GET /api/devices, a POST per change, the HUD note, and the API's failure shown.
import { devicesView, deviceRows, HUD_NOTE } from '../../main/assets/canvas/devicesview.js';
import { findAll } from './fake-dom.js';
import { eq, ok } from './fixtures.js';
import * as E from './env.js';

const caps = () => ({ capabilities: [
  { device: 'glasses', capability: 'display', enabled: true, constraint: 'hud' }, { device: 'glasses', capability: 'mic', enabled: false },
  { device: 'phone', capability: 'display', enabled: true }, { device: 'phone', capability: 'mic', enabled: true },
] });
const boxes = (root) => findAll(root, (e) => e.tagName === 'input');

export const tests = {
  rowsAreInFixedOrderWithTheHudNoteOnlyOnTheGlassesDisplay() {
    const rows = deviceRows(caps().capabilities);
    eq(rows.map((r) => r.label), ['Phone mic', 'Phone display', 'Glasses mic', 'Glasses display']);
    eq(rows.map((r) => r.enabled), [true, true, false, true]);
    eq(rows.filter((r) => r.note).map((r) => r.key), ['glasses.display']);
    eq(deviceRows(null), []);
  },
  async viewShowsFourSwitchesAndPostsAChange() {
    E.setup(['app']);
    const api = E.fakeApi({ 'GET /api/devices': caps(), 'POST /api/devices/capability': { ok: true } });
    const ctx = E.fakeCtx(api); const root = document.createElement('div');
    await devicesView(ctx, root);
    eq(boxes(root).length, 4);
    ok(E.textOf(root).includes(HUD_NOTE), 'the HUD note');
    const glassesMic = boxes(root)[2];
    glassesMic.checked = true;
    glassesMic.dispatch('change', { target: glassesMic });
    await E.settle();
    eq(api.calls.filter((c) => c.method === 'POST').map((c) => c.body), [{ device: 'glasses', capability: 'mic', enabled: true }]);
  },
  async aFailedSwitchShowsTheApiError() {
    E.setup(['app']);
    const api = E.fakeApi({ 'GET /api/devices': caps(), 'POST /api/devices/capability': () => { throw new E.FakeApiError(400, 'bad_request', 'Not a device capability.'); } });
    const ctx = E.fakeCtx(api); const root = document.createElement('div');
    await devicesView(ctx, root);
    const b = boxes(root)[0]; b.checked = false; b.dispatch('change', { target: b });
    await E.settle();
    eq(ctx.errors.map((e) => e.message), ['Not a device capability.']);
    eq(boxes(root).length, 4, 're-read after the failure');
  },
};
