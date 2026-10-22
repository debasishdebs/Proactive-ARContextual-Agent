import { rowsFor } from '../../main/assets/canvas/diffview.js';
import { eq, ok } from './fixtures.js';

export const tests = {
  rowsForAddedRemovedChanged() {
    const r = rowsFor({
      renamed: true,
      nodes: [{ nodeId: 'n1', kind: 'added', params: [] }, { nodeId: 'n2', kind: 'removed', params: [] }, { nodeId: 'n3', kind: 'changed', params: ['@title', 'batch'] }],
      edgesAdded: [{ from: 'a', to: 'b' }, { from: 'b', to: 'c', when: 'trusted' }], edgesRemoved: [{ from: 'x', to: 'y' }], prompts: [],
    });
    eq(r.nodes.map((x) => [x.cls, x.text]), [['add', 'Added node n1'], ['del', 'Removed node n2'], ['chg', 'Changed node n3: @title, batch']]);
    eq(r.edges.map((x) => [x.cls, x.text]), [['add', 'a → b'], ['add', 'b → c (when trusted)'], ['del', 'x → y']]);
    eq(r.renamed, true);
    eq(r.empty, false);
  },
  promptLinesClassed() {
    const r = rowsFor({ renamed: false, nodes: [], edgesAdded: [], edgesRemoved: [], prompts: [
      { nodeId: 'p', part: 'template', lines: [{ op: 'same', text: 'keep' }, { op: 'removed', text: 'old' }, { op: 'added', text: 'new' }] }] });
    eq(r.prompts[0].title, 'p · template');
    eq(r.prompts[0].lines.map((l) => [l.cls, l.text]), [['same', 'keep'], ['del', 'old'], ['add', 'new']]);
  },
  noChangesIsEmpty() {
    const r = rowsFor({ renamed: false, nodes: [], edgesAdded: [], edgesRemoved: [], prompts: [] });
    ok(r.empty);
    ok(rowsFor(null).empty);
  },
};
