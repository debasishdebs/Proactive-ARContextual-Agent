// The DAG as SVG. Positions come from the server (FlowLayouts cells: layer and column per node id).
import { s } from './dom.js';

export const X0 = 40, DX = 220, Y0 = 40, DY = 110, NODE_W = 180, NODE_H = 64;

export function kindOf(type) {
  const t = String(type || '');
  if (t.startsWith('input.')) return 'input';
  if (t === 'memory.write') return 'output';
  if (t.startsWith('ai.')) return 'ai';
  return 'step';
}

const cut = (t, n) => (t.length > n ? t.slice(0, n - 1) + '…' : t);

/** Builds the svg. opts: { selected, onSelect(nodeId) }. */
export function drawDag(pipe, layout, opts) {
  const o = opts || {};
  const cells = (layout && layout.cells) || {};
  const nodes = ((pipe && pipe.nodes) || []).filter((n) => cells[n.id]);
  const pos = (id) => ({ x: X0 + cells[id].column * DX, y: Y0 + cells[id].layer * DY });
  const cols = Math.max(1, Number(layout && layout.columns) || 1);
  const rows = Math.max(1, Number(layout && layout.layers) || 1);
  const width = X0 * 2 + (cols - 1) * DX + NODE_W;
  const height = Y0 * 2 + (rows - 1) * DY + NODE_H;
  const svg = s('svg', {
    class: 'dag', role: 'img', 'aria-label': 'Pipeline graph',
    viewBox: '0 0 ' + width + ' ' + height, width: width, height: height,
  });
  svg.appendChild(s('defs', null,
    s('marker', { id: 'arrow', viewBox: '0 0 10 10', refX: 9, refY: 5, markerWidth: 7, markerHeight: 7, orient: 'auto-start-reverse' },
      s('path', { d: 'M0,0 L10,5 L0,10 z', class: 'arrow' }))));
  for (const e of (pipe && pipe.edges) || []) {
    if (!cells[e.from] || !cells[e.to]) continue;
    const a = pos(e.from), b = pos(e.to);
    const x1 = a.x + NODE_W / 2, y1 = a.y + NODE_H, x2 = b.x + NODE_W / 2, y2 = b.y;
    const my = (y1 + y2) / 2;
    svg.appendChild(s('path', { class: 'edge', d: 'M' + x1 + ',' + y1 + ' C' + x1 + ',' + my + ' ' + x2 + ',' + my + ' ' + x2 + ',' + y2, 'marker-end': 'url(#arrow)' }));
    if (e.when) svg.appendChild(s('text', { class: 'edge-label', x: (x1 + x2) / 2, y: my, 'text-anchor': 'middle' }, cut(String(e.when), 24)));
  }
  for (const n of nodes) {
    const p = pos(n.id);
    const g = s('g', {
      class: 'node ' + kindOf(n.type) + (o.selected === n.id ? ' selected' : ''),
      transform: 'translate(' + p.x + ',' + p.y + ')', tabindex: 0, role: 'button', 'data-node': n.id,
    },
    s('title', null, String(n.title || n.id) + ' (' + String(n.type) + ')'),
    s('rect', { width: NODE_W, height: NODE_H, rx: 10, ry: 10 }),
    s('text', { class: 'node-title', x: NODE_W / 2, y: 28, 'text-anchor': 'middle' }, cut(String(n.title || n.id), 24)),
    s('text', { class: 'node-type', x: NODE_W / 2, y: 48, 'text-anchor': 'middle' }, cut(String(n.type), 28)));
    const pick = () => { if (o.onSelect) o.onSelect(n.id); };
    g.addEventListener('click', pick);
    g.addEventListener('keydown', (ev) => { if (ev.key === 'Enter' || ev.key === ' ') { ev.preventDefault(); pick(); } });
    svg.appendChild(g);
  }
  return svg;
}
