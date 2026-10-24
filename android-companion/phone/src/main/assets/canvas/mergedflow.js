// Several pipelines drawn together, read only. Pure: no DOM, no network. entries = [{id, name, pipe}] or [{id, name, error}] for a pipeline that did not load.
// Node ids become "<pipelineId>::<nodeId>" so two pipelines never clash. Lanes (glasses, phone, async) are shared; each pipeline keeps its own colour and name pill.
// Cross-pipeline links reuse the single-pipeline signal rule (flowadapter.bindingsOf: a Send name matches a Receive name), and a dashed
// "captured frames" link joins a glasses capture.image to the input.photos / input.moments of the OTHER selected pipelines.
import { LANES, CARD_W, CARD_H, CELL_W, CELL_H, LANE_GAP, bindingsOf, nodeDomain, titleOf, laneLabel } from './flowadapter.js';
import { typeOf } from './validate.js';

export const DEFAULT_CAPTURE = 'default-capture';
export const PIPELINE_COLOURS = ['pl0', 'pl1', 'pl2', 'pl3', 'pl4', 'pl5'];
const SEP = '::';

/** "a,,b, a" → ['a', 'b'] (trimmed, deduplicated, order kept). */
export const parseIds = (text) => Array.from(new Set(String(text || '').split(',').map((x) => x.trim()).filter(Boolean)));
export const mergedHref = (ids) => '#/merged?ids=' + ids.map(encodeURIComponent).join(',');
/** The pipeline plus the built-in Default capture (just the capture when it is the capture itself). */
export const withCapture = (id) => (id === DEFAULT_CAPTURE ? [id] : [id, DEFAULT_CAPTURE]);

/** Longest-path layer of each node (0 for roots); a cycle or a dangling edge cannot loop. */
function layersOf(pipe) {
  const depth = new Map((pipe.nodes || []).map((n) => [n.id, 0]));
  for (let pass = 0; pass < depth.size; pass++) {
    for (const e of pipe.edges || []) if (depth.has(e.from) && depth.has(e.to) && depth.get(e.to) < depth.get(e.from) + 1 && depth.get(e.from) + 1 < depth.size) depth.set(e.to, depth.get(e.from) + 1);
  }
  return depth;
}

export function buildMerged(entries, nodeTypes) {
  const seen = new Set();
  const list = (entries || []).filter((e) => e && !seen.has(e.id) && seen.add(e.id));
  const pipelines = list.map((e, i) => ({ id: e.id, name: e.name || e.id, colour: PIPELINE_COLOURS[i % PIPELINE_COLOURS.length], error: e.pipe ? undefined : String(e.error || 'Could not load this pipeline.'), nodeCount: e.pipe ? (e.pipe.nodes || []).length : 0 }));
  const loaded = list.filter((e) => e.pipe);
  const pid = (p, n) => p.id + SEP + n;
  const drawn = [];
  for (const e of loaded) {
    const depth = layersOf(e.pipe), meta = pipelines.find((p) => p.id === e.id);
    for (const n of e.pipe.nodes || []) {
      const nt = typeOf(nodeTypes, n.type);
      drawn.push({ id: pid(e, n.id), pid: e.id, localId: n.id, type: n.type, title: titleOf(n, nt), typeLabel: nt ? nt.label : n.type, domain: nodeDomain(n, nt), composite: !!(nt && nt.inner),
        colour: meta.colour, pill: meta.name, layer: depth.get(n.id), x: 0, y: 0, w: CARD_W, h: CARD_H, params: n.params || {} });
    }
  }
  // lanes in LANES order, then any other domain as met; inside a lane each pipeline gets its own band of columns
  const present = Array.from(new Set(drawn.map((n) => n.domain)));
  const order = LANES.map((l) => l.domain).filter((d) => present.includes(d)).concat(present.filter((d) => !LANES.some((l) => l.domain === d)));
  const lanes = [];
  let x = 0;
  for (const d of order) {
    let width = 0;
    for (const e of loaded) {
      const mine = drawn.filter((n) => n.pid === e.id && n.domain === d);
      if (!mine.length) continue;
      const used = new Map();
      for (const n of mine) { const c = used.get(n.layer) || 0; used.set(n.layer, c + 1); n.x = x + width + c * CELL_W; n.y = n.layer * CELL_H; }
      width += Math.max(...used.values()) * CELL_W;
    }
    lanes.push({ domain: d, label: laneLabel(d), x, width });
    x += width + LANE_GAP;
  }
  for (const n of drawn) delete n.layer;
  const byId = new Map(drawn.map((n) => [n.id, n]));
  const edges = [];
  for (const e of loaded) for (const ed of e.pipe.edges || []) if (byId.has(pid(e, ed.from)) && byId.has(pid(e, ed.to))) edges.push({ id: pid(e, ed.from) + '>' + pid(e, ed.to) + (ed.when ? '>' + ed.when : ''), from: pid(e, ed.from), to: pid(e, ed.to), when: ed.when || '', kind: 'flow' });

  const label = (n) => n.title + ' (' + (pipelines.find((p) => p.id === n.pid) || {}).name + ')';
  const links = [];
  const named = (n) => (typeof n.params.name === 'string' ? n.params.name : '');
  for (const out of drawn.filter((n) => n.type === 'signal.out' && named(n))) {
    for (const inn of drawn.filter((n) => n.type === 'signal.in' && named(n) === named(out))) {
      links.push({ kind: 'signal', name: named(out), from: out.id, to: inn.id, cross: out.pid !== inn.pid, dashed: false, text: named(out) + ': ' + label(out) + ' to ' + label(inn) });
    }
  }
  for (const c of drawn.filter((n) => n.type === 'capture.image')) {
    for (const t of drawn.filter((n) => (n.type === 'input.photos' || n.type === 'input.moments') && n.pid !== c.pid)) {
      links.push({ kind: 'frames', name: 'captured frames', from: c.id, to: t.id, cross: true, dashed: true, text: 'captured frames: ' + label(c) + ' to ' + label(t) });
    }
  }
  // the single-pipeline rule over the union: a name is bound when some Send and some Receive share it, wherever they live
  const union = { nodes: drawn.map((n) => ({ id: n.id, type: n.type, title: n.title, params: n.params })), edges: [] };
  const signals = bindingsOf(union, nodeTypes).filter((b) => b.kind === 'signal').map((b) => ({ name: b.name, state: b.state, text: b.text }));
  const nodes = drawn.map((n) => { const o = Object.assign({}, n); delete o.params; return o; });
  const height = nodes.length ? Math.max(...nodes.map((n) => n.y + n.h)) : 0;
  return { pipelines, lanes, nodes, edges, links, signals, width: Math.max(0, x - LANE_GAP), height, empty: nodes.length === 0 };
}
