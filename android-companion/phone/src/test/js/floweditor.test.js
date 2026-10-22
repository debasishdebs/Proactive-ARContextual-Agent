// floweditor.js and views.js load under GraalJS with no React present, and mountEditor's handle works over stub globals.
import { mountEditor } from '../../main/assets/canvas/floweditor.js';
import * as views from '../../main/assets/canvas/views.js';
import { nodeTypes, livePipe, liveCells, eq, ok } from './fixtures.js';

const layout = { layers: 5, columns: 1, cells: liveCells };
function stubGlobals() {
  const seen = { rendered: null, unmounted: 0, el: null };
  globalThis.React = { createElement: () => null };
  globalThis.ReactDOM = { createRoot(el) { seen.el = el; return { render(x) { seen.rendered = x; }, unmount() { seen.unmounted++; } }; } };
  globalThis.ReactFlow = { ReactFlowProvider: {}, ReactFlow: {}, Handle: {}, Position: { Top: 'top', Bottom: 'bottom' }, useReactFlow: () => ({}) };
  globalThis.htm = { bind: () => (strings, ...vals) => ({ strings, vals }) };
  return seen;
}
const unstub = () => { delete globalThis.React; delete globalThis.ReactDOM; delete globalThis.ReactFlow; delete globalThis.htm; };

export const tests = {
  floweditorAndViewsImportWithoutReact() {
    eq(typeof globalThis.React, 'undefined');
    eq(typeof mountEditor, 'function');
    eq(typeof views.pipelineView, 'function');
  },
  mountRendersOnceAndExposesTheHandle() {
    const seen = stubGlobals();
    try {
      const drafts = [];
      const h = mountEditor({ tag: 'div' }, {
        api: async () => ({}), nodeTypes, readOnly: false, onDraft: (d) => drafts.push(d),
        pipeline: { id: 'p', pipe: livePipe(), draftRev: 3, layout, problems: [], activePrompts: {} },
      });
      ok(seen.rendered, 'rendered a tree'); eq(seen.el, { tag: 'div' });
      eq([h.isDirty(), h.savedRev(), h.getDraft().nodes.length], [false, 3, 5]);
      eq(drafts.length, 0, 'nothing reported before a change');
      h.unmount(); eq(seen.unmounted, 1);
    } finally { unstub(); }
  },
  async handleSaveAndFreezeGoThroughTheApi() {
    stubGlobals();
    try {
      const calls = [];
      const api = async (m, path, body) => { calls.push(m + ' ' + path); return m === 'PUT' ? { draftRev: 4 } : { version: 1 }; };
      const h = mountEditor({}, { api, nodeTypes, readOnly: false, pipeline: { id: 'p', pipe: livePipe(), draftRev: 3, layout, problems: [] } });
      const r = await h.freeze('n');
      eq([r.ok, r.version], [true, 1]);
      eq(calls, ['POST /api/pipelines/p/freeze'], 'a clean draft is not saved first');
      h.unmount();
    } finally { unstub(); }
  },
};
