// The read-only merged canvas (#/merged?ids=a,b): the selected pipelines drawn together in lanes. Plain DOM and SVG through dom.js
// (createElement and textContent only). The only requests are GET /api/node-types and GET /api/pipelines/{id} for each selected id.
import { h, s, clear } from './dom.js';
import { buildMerged } from './mergedflow.js';

const enc = encodeURIComponent;
const TOP = 28, PAD = 16;

function parse(text) { try { return JSON.parse(text); } catch (e) { return null; } }

/** One pipeline: {id, name, pipe} or {id, name, error}; a failure here never stops the others. */
async function load(ctx, id) {
  try {
    const d = await ctx.api('GET', '/api/pipelines/' + enc(id));
    const pipe = parse(d && d.json);
    if (!pipe || !Array.isArray(pipe.nodes)) return { id, name: (d && d.name) || id, error: 'This pipeline could not be read.' };
    return { id, name: d.name || pipe.name || id, pipe };
  } catch (e) {
    return { id, name: id, error: e && e.status === 404 ? 'No pipeline with this id.' : (e && e.message) || 'Could not load this pipeline.' };
  }
}

function drawing(m) {
  const w = m.width + PAD * 2, hgt = m.height + TOP + PAD * 2;
  const root = s('svg', { class: 'merged-svg', viewBox: '0 0 ' + w + ' ' + hgt, width: w, height: hgt, role: 'group', 'aria-label': 'Merged flow of the selected pipelines' });
  root.appendChild(s('defs', null, s('marker', { id: 'merged-head', viewBox: '0 0 10 10', refX: 9, refY: 5, markerWidth: 7, markerHeight: 7, orient: 'auto' }, s('path', { d: 'M0 0L10 5L0 10z', class: 'merged-head' }))));
  const at = (n) => ({ x: n.x + PAD, y: n.y + TOP + PAD });
  for (const l of m.lanes) {
    root.appendChild(s('g', { class: 'merged-lane lane-' + l.domain.toLowerCase() },
      s('rect', { x: l.x + PAD - 8, y: 0, width: l.width + 16, height: hgt, rx: 10, class: 'merged-lane-band' }),
      s('text', { x: l.x + PAD, y: 18, class: 'merged-lane-label' }, l.label)));
  }
  const by = new Map(m.nodes.map((n) => [n.id, n]));
  const curve = (a, b, off) => {
    const p = at(a), q = at(b);
    const x1 = p.x + a.w / 2 + off, y1 = p.y + a.h, x2 = q.x + b.w / 2 + off, y2 = q.y, my = (y1 + y2) / 2;
    return { d: 'M' + x1 + ' ' + y1 + ' C' + x1 + ' ' + my + ' ' + x2 + ' ' + my + ' ' + x2 + ' ' + y2, lx: (x1 + x2) / 2, ly: my };
  };
  for (const e of m.edges) {
    const c = curve(by.get(e.from), by.get(e.to), 0);
    root.appendChild(s('g', { class: 'merged-edge' }, s('path', { d: c.d, class: 'merged-edge-line', fill: 'none', 'marker-end': 'url(#merged-head)' }),
      e.when ? s('text', { x: c.lx, y: c.ly, 'text-anchor': 'middle', class: 'merged-edge-label' }, e.when) : null));
  }
  for (const k of m.links) {
    const c = curve(by.get(k.from), by.get(k.to), k.kind === 'frames' ? 24 : -24);
    root.appendChild(s('g', { class: 'merged-link merged-link-' + k.kind + (k.dashed ? ' dashed' : '') },
      s('title', null, k.text), s('path', { d: c.d, class: 'merged-link-line', fill: 'none', 'marker-end': 'url(#merged-head)' }),
      s('text', { x: c.lx, y: c.ly, 'text-anchor': 'middle', class: 'merged-link-label' }, k.name)));
  }
  for (const n of m.nodes) {
    const p = at(n);
    const pill = n.pill.length > 22 ? n.pill.slice(0, 21) + '…' : n.pill;
    root.appendChild(s('g', { class: 'merged-node ' + n.colour + (n.composite ? ' composite' : ''), 'data-node': n.id, role: 'group', 'aria-label': n.title + ', ' + n.typeLabel + ', ' + n.pill },
      s('title', null, n.title + ' · ' + n.typeLabel + ' · ' + n.pill),
      s('rect', { x: p.x, y: p.y, width: n.w, height: n.h, rx: 10, class: 'merged-node-body' }),
      s('rect', { x: p.x + 8, y: p.y - 9, width: Math.min(n.w - 16, 16 + pill.length * 6.5), height: 18, rx: 9, class: 'merged-pill' }),
      s('text', { x: p.x + 16, y: p.y + 4, class: 'merged-pill-text' }, pill),
      s('text', { x: p.x + n.w / 2, y: p.y + 40, 'text-anchor': 'middle', class: 'merged-node-title' }, n.title.length > 24 ? n.title.slice(0, 23) + '…' : n.title),
      s('text', { x: p.x + n.w / 2, y: p.y + 60, 'text-anchor': 'middle', class: 'merged-node-type' }, n.typeLabel + (n.composite ? ' · composite' : ''))));
  }
  return h('div', { class: 'merged-scroll' }, root);
}

export async function mergedView(ctx, root, ids) {
  clear(root);
  root.appendChild(h('h2', null, 'Merged flow'));
  root.appendChild(h('p', { class: 'muted' }, 'Read only: the selected pipelines drawn together by where they run. Solid coloured links connect a Send signal to a Receive signal of the same name; dashed links carry captured frames from the glasses capture to the Photo and Moments inputs.'));
  root.appendChild(h('p', null, h('a', { class: 'btn', href: '#/' }, 'Back to pipelines')));
  if (!ids || !ids.length) { root.appendChild(h('p', { role: 'status' }, 'Select at least one pipeline on the Pipelines page.')); return; }
  const [types, entries] = await Promise.all([ctx.api('GET', '/api/node-types'), Promise.all(ids.map((id) => load(ctx, id)))]);
  const m = buildMerged(entries, types);
  root.appendChild(h('ul', { class: 'merged-legend' }, ...m.pipelines.map((p) => h('li', { class: 'merged-chip ' + p.colour + (p.error ? ' merged-error' : ''), role: p.error ? 'alert' : null },
    h('strong', null, p.name), p.error ? ' (' + p.id + '): ' + p.error : ' · ' + p.nodeCount + ' nodes'))));
  if (m.empty) { root.appendChild(h('p', { role: 'status' }, 'Nothing to draw: none of the selected pipelines could be loaded.')); return; }
  root.appendChild(drawing(m));
  const rows = m.links.map((k) => k.text).concat(m.signals.filter((x) => x.state !== 'bound').map((x) => x.text));
  if (rows.length) root.appendChild(h('section', { class: 'card' }, h('h3', null, 'Links'), h('ul', { class: 'bindings' }, ...rows.map((t) => h('li', null, t)))));
}
