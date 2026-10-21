import { drawDag, kindOf, NODE_W, NODE_H, X0, DX, Y0, DY } from '../../main/assets/canvas/dag.js';
import { installFakeDom, uninstall, findAll } from './fake-dom.js';

function eq(a, b, msg) { if (a !== b) throw new Error((msg || 'expected') + ': ' + JSON.stringify(a) + ' !== ' + JSON.stringify(b)); }

const pipe = {
  nodes: [
    { id: 'in', type: 'input.frame', title: 'Frame' },
    { id: 'ai', type: 'ai.vision', title: 'Describe' },
    { id: 'out', type: 'memory.write', title: 'Save' },
    { id: 'ghost', type: 'step.noop', title: 'Not laid out' },
  ],
  edges: [
    { from: 'in', to: 'ai' },
    { from: 'ai', to: 'out', when: 'confidence > 0.5' },
    { from: 'ai', to: 'ghost' },
  ],
};
const layout = { columns: 2, layers: 3, cells: { in: { layer: 0, column: 0 }, ai: { layer: 1, column: 0 }, out: { layer: 2, column: 1 } } };
const withDom = (f) => () => { installFakeDom(); try { f(); } finally { uninstall(); } };
const groups = (svg) => findAll(svg, (e) => e.tagName === 'g');

export const tests = {
  oneGroupPerLaidOutNode: withDom(() => {
    const gs = groups(drawDag(pipe, layout, {}));
    eq(gs.length, 3, 'ghost has no cell, so no group');
    eq(gs.map((g) => g.getAttribute('data-node')).join(','), 'in,ai,out');
    eq(gs[0].getAttribute('transform'), 'translate(' + X0 + ',' + Y0 + ')');
    eq(gs[2].getAttribute('transform'), 'translate(' + (X0 + DX) + ',' + (Y0 + 2 * DY) + ')');
    eq(kindOf('input.frame'), 'input');
    eq(kindOf('memory.write'), 'output');
    eq(kindOf('ai.vision'), 'ai');
    eq(gs[0].getAttribute('class'), 'node input');
  }),
  edgePathPerEdge: withDom(() => {
    const svg = drawDag(pipe, layout, {});
    const edges = findAll(svg, (e) => e.tagName === 'path' && e.getAttribute('class') === 'edge');
    eq(edges.length, 2, 'the edge to the unlaid-out node is skipped');
    eq(edges[0].getAttribute('marker-end'), 'url(#arrow)');
    if (!/^M\d+(\.\d+)?,\d+(\.\d+)? C/.test(edges[0].getAttribute('d'))) throw new Error('bad path d: ' + edges[0].getAttribute('d'));
  }),
  whenLabelDrawn: withDom(() => {
    const svg = drawDag(pipe, layout, {});
    const labels = findAll(svg, (e) => e.getAttribute('class') === 'edge-label');
    eq(labels.length, 1);
    eq(labels[0].textContent, 'confidence > 0.5');
  }),
  selectedClass: withDom(() => {
    const gs = groups(drawDag(pipe, layout, { selected: 'ai' }));
    eq(gs.filter((g) => g.classList.contains('selected')).length, 1);
    eq(gs.find((g) => g.classList.contains('selected')).getAttribute('data-node'), 'ai');
  }),
  enterSelects: withDom(() => {
    const picked = [];
    const gs = groups(drawDag(pipe, layout, { onSelect: (id) => picked.push(id) }));
    const ev = gs[1].dispatch('keydown', { key: 'Enter' });
    eq(ev.defaultPrevented, true);
    gs[1].dispatch('keydown', { key: 'a' });
    gs[2].dispatch('click');
    eq(picked.join(','), 'ai,out');
  }),
  viewBoxFitsLayout: withDom(() => {
    const svg = drawDag(pipe, layout, {});
    const w = X0 * 2 + DX + NODE_W, hgt = Y0 * 2 + 2 * DY + NODE_H;
    eq(svg.getAttribute('viewBox'), '0 0 ' + w + ' ' + hgt);
    eq(svg.getAttribute('width'), String(w));
    eq(svg.getAttribute('height'), String(hgt));
    const empty = drawDag(null, null, {});
    eq(empty.getAttribute('viewBox'), '0 0 ' + (X0 * 2 + NODE_W) + ' ' + (Y0 * 2 + NODE_H), 'empty input still draws');
  }),
};
