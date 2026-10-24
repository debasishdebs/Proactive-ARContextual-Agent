// The merged view over the fake DOM: list checkboxes and buttons, the read-only page, per-pipeline errors, no network beyond GETs.
import { pipelinesView } from '../../main/assets/canvas/views.js';
import { mergedView } from '../../main/assets/canvas/mergedview.js';
import { findAll } from './fake-dom.js';
import { eq, ok, capturePipe, livePipe, allTypes } from './fixtures.js';
import * as E from './env.js';

const withEnv = async (over, fn) => {
  const env = E.setup(['app']);
  try {
    const api = E.fakeApi(E.routes(over));
    await fn({ env, api, ctx: E.fakeCtx(api), root: document.getElementById('app') });
  } finally { env.uninstall(); }
};
const boxes = (root) => findAll(root, (e) => e.tagName === 'input' && e.getAttribute('type') === 'checkbox');
const tick = (cb, on) => { cb.checked = on; cb.dispatch('change'); };
const data = (id, pipe) => ({ id, name: id === 'default-capture' ? 'Default capture' : pipe.name, json: JSON.stringify(pipe), draftRev: 1, builtIn: id === 'default-capture', problems: [], layout: { layers: 0, columns: 0, cells: {} }, versions: [], live: null });
const withClass = (root, c) => findAll(root, (e) => e.classList.contains(c));

export const tests = {
  async everyPipelineRowHasACheckboxAndTheMergedButtonNeedsOneSelected() {
    await withEnv({}, async ({ ctx, root }) => {
      await pipelinesView(ctx, root);
      const cbs = boxes(root); eq(cbs.length, 2);
      eq(cbs[0].getAttribute('aria-label'), 'Select Live clone');
      const b = E.buttons(root, 'View merged flow')[0];
      ok(b, 'a View merged flow button'); eq(!!b.disabled, true, 'disabled with nothing selected');
      E.click(b); eq(ctx.gone, [], 'a disabled button goes nowhere');
      tick(cbs[0], true); eq(!!b.disabled, false);
      E.click(b); eq(ctx.gone, ['#/merged?ids=p1']);
      tick(cbs[1], true); E.click(b); eq(ctx.gone[1], '#/merged?ids=p1,live-extract');
      tick(cbs[0], false); tick(cbs[1], false); eq(!!b.disabled, true);
    });
  },
  async showWithCaptureOpensThePipelineWithTheBuiltInDefaultCapture() {
    await withEnv({ 'GET /api/pipelines': [{ id: 'p1', name: 'Mine', builtIn: false, versions: [], live: null }, { id: 'default-capture', name: 'Default capture', builtIn: true, versions: [], live: null }] }, async ({ ctx, root }) => {
      await pipelinesView(ctx, root);
      const b = E.buttons(root, 'Show with capture'); eq(b.length, 1, 'not offered on Default capture itself');
      E.click(b[0]); eq(ctx.gone, ['#/merged?ids=p1,default-capture']);
    });
  },
  async mergedPageDrawsEverySelectedPipelineReadOnly() {
    await withEnv({ 'GET /api/pipelines/default-capture': data('default-capture', capturePipe()), 'GET /api/node-types': allTypes }, async ({ ctx, root, api }) => {
      await mergedView(ctx, root, ['p1', 'default-capture']);
      eq(withClass(root, 'merged-node').length, livePipe().nodes.length + capturePipe().nodes.length);
      eq(withClass(root, 'merged-lane').length, 3);
      eq(withClass(root, 'merged-link-frames').length, 1, 'capture to the Photo input of the other pipeline');
      ok(E.textOf(root).includes('Live clone') && E.textOf(root).includes('Default capture'));
      eq(findAll(root, (e) => e.tagName === 'input' || e.tagName === 'textarea' || e.tagName === 'select').length, 0, 'nothing to edit');
      eq(E.buttons(root, 'Publish').length + E.buttons(root, 'Save draft').length + E.buttons(root, 'Freeze').length, 0);
      eq(api.calls.map((c) => c.method + ' ' + c.path).sort(), ['GET /api/node-types', 'GET /api/pipelines/default-capture', 'GET /api/pipelines/p1']);
    });
  },
  async anUnknownPipelineShowsItsOwnErrorAndTheRestStillDraws() {
    await withEnv({}, async ({ ctx, root }) => {
      await mergedView(ctx, root, ['p1', 'nope']);
      ok(E.textOf(root).includes('nope'), 'names the failed pipeline');
      eq(withClass(root, 'merged-error').length, 1);
      eq(withClass(root, 'merged-node').length, livePipe().nodes.length);
      eq(ctx.errors.length, 0, 'no global error: it is per pipeline');
    });
  },
  async emptySelectionSaysSoAndLinksBack() {
    await withEnv({}, async ({ ctx, root, api }) => {
      await mergedView(ctx, root, []);
      ok(E.textOf(root).includes('Select at least one pipeline'));
      eq(withClass(root, 'merged-node').length, 0);
      eq(api.calls.filter((c) => c.path.startsWith('/api/pipelines/')).length, 0);
    });
  },
  async hostileTextStaysText() {
    const pipe = livePipe(); pipe.nodes[0].title = '<img src=x onerror=alert(1)>'; pipe.name = '<b>evil</b>';
    await withEnv({ 'GET /api/pipelines/p1': data('p1', pipe) }, async ({ ctx, root }) => {
      await mergedView(ctx, root, ['p1']);
      ok(E.textOf(root).includes('<img src=x onerror=alert(1)>'));
      eq(findAll(root, (e) => e.tagName === 'img' || e.tagName === 'b').length, 0);
    });
  },
};
