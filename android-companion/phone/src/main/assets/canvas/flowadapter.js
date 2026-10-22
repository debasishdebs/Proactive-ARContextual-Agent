// Pipeline model <-> React Flow view model. Pure: no React, no DOM. React Flow never owns the data.
import { addNode, removeNode, connect, disconnect, setParam, setTitle } from './graph.js';
import { portLabel, problemsByNode, edgeKey, typeOf, own, isPhotosBinding } from './validate.js';

export const CELL_W = 220, CELL_H = 120, LANE_GAP = 40;

/** The three rooted domains (S34), left to right; any other domain name follows in order of appearance. */
export const LANES = [{ domain: 'RT_GLASSES', label: 'Glasses (real time)' }, { domain: 'RT_PHONE', label: 'Phone (real time)' }, { domain: 'ASYNC', label: 'Async (phone)' }];
const laneLabel = (d) => { const l = LANES.find((x) => x.domain === d); return l ? l.label : String(d); };

const domainOf = (nt) => (nt && nt.domain) || 'ASYNC';
/** A signal node's domain is its own `domain` param (S34); every other node's is its type's. */
const nodeDomain = (n, nt) => {
  if (nt && nt.domainFromParams && n.params && typeof n.params.domain === 'string') return n.params.domain;
  return domainOf(nt);
};
const titleOf = (n, nt) => String(n.title || (nt && nt.label) || n.id);

/**
 * Domain lanes: each domain present gets its own band of columns, in LANES order. Only a graph with two or more domains has lanes
 * (a slice 2 graph keeps its positions). → {lanes: [{domain, label, x, width}], place(node) → x}.
 */
function laneModel(pipe, cells, nodeTypes) {
  const nodes = pipe.nodes || [];
  const dom = new Map(nodes.map((n) => [n.id, nodeDomain(n, typeOf(nodeTypes, n.type))]));
  const present = Array.from(new Set(dom.values()));
  if (present.length < 2) return { lanes: [], x: null };
  const order = LANES.map((l) => l.domain).filter((d) => present.includes(d)).concat(present.filter((d) => !LANES.some((l) => l.domain === d)));
  const lanes = [];
  const base = new Map();
  let x = 0;
  for (const d of order) {
    const mine = nodes.filter((n) => dom.get(n.id) === d);
    const cols = mine.filter((n) => own(cells, n.id)).map((n) => cells[n.id].column);
    const pending = mine.filter((n) => !own(cells, n.id)).length;
    const minCol = cols.length ? Math.min(...cols) : 0;
    const span = Math.max(cols.length ? Math.max(...cols) - minCol + 1 : 0, pending, 1);
    lanes.push({ domain: d, label: laneLabel(d), x, width: span * CELL_W });
    base.set(d, { x, minCol });
    x += span * CELL_W + LANE_GAP;
  }
  return { lanes, x: (n, col) => { const b = base.get(dom.get(n.id)); return b.x + (col - b.minCol) * CELL_W; }, laneX: (n) => base.get(dom.get(n.id)).x };
}

/** The lanes of a pipeline as the canvas draws them ([] for a single-domain graph). */
export const lanesOf = (pipe, layout, nodeTypes) => laneModel(pipe, (layout && layout.cells) || {}, nodeTypes).lanes;

/**
 * → {nodes, edges, lanes} for a controlled <ReactFlow>. Positions come from the layout cells; nodes not laid out yet
 * (added while /api/validate is pending) sit in a pending row below the last layer, in id order.
 */
export function toFlow(pipe, layout, problems, nodeTypes, opts) {
  const readOnly = !!(opts && opts.readOnly);
  const cells = (layout && layout.cells) || {};
  const layers = Math.max(0, Number(layout && layout.layers) || 0);
  const by = problemsByNode(problems);
  const warned = problemsByNode(opts && opts.warnings);
  const lm = laneModel(pipe, cells, nodeTypes);
  const nodes = (pipe.nodes || []).filter((n) => own(cells, n.id)).map((n) => node(n, lm.x ? lm.x(n, cells[n.id].column) : cells[n.id].column * CELL_W, cells[n.id].layer * CELL_H, false));
  const pendingIdx = new Map();
  (pipe.nodes || []).filter((n) => !own(cells, n.id)).sort((a, b) => (a.id < b.id ? -1 : a.id > b.id ? 1 : 0))
    .forEach((n) => {
      const d = nodeDomain(n, typeOf(nodeTypes, n.type));
      const i = pendingIdx.get(d) || 0; pendingIdx.set(d, i + 1);
      nodes.push(node(n, lm.laneX ? lm.laneX(n) + i * CELL_W : nodes.filter((m) => m.data.pending).length * CELL_W, layers * CELL_H, true));
    });
  // Keep the pipeline's node order for stable rendering.
  const order = new Map((pipe.nodes || []).map((n, i) => [n.id, i]));
  nodes.sort((a, b) => order.get(a.id) - order.get(b.id));

  function node(n, x, y, pending) {
    const nt = typeOf(nodeTypes, n.type);
    const probs = own(by, n.id) ? by[n.id] : [];
    const warns = own(warned, n.id) ? warned[n.id] : [];
    const typeLabel = nt ? nt.label : n.type;
    const title = titleOf(n, nt);
    return {
      id: n.id,
      type: 'card',
      position: { x, y },
      data: {
        title, typeLabel,
        inPorts: nt ? (nt.inputs || []).map(portLabel) : [],
        outPort: nt ? portLabel(nt.output) : '',
        problems: probs.slice(), warnings: warns.slice(), domain: nodeDomain(n, nt), pending, readOnly,
      },
      deletable: !readOnly && n.type !== 'input.photos',
      connectable: !readOnly,
      ariaLabel: title + ', ' + typeLabel + (probs.length ? ', ' + probs.length + (probs.length === 1 ? ' problem' : ' problems') : '')
        + (warns.length ? ', ' + warns.length + (warns.length === 1 ? ' warning' : ' warnings') : ''),
    };
  }

  const titleById = new Map((pipe.nodes || []).map((n) => [n.id, titleOf(n, typeOf(nodeTypes, n.type))]));
  const edges = (pipe.edges || []).map((e) => {
    const id = edgeKey(e);
    const edge = {
      id, source: e.from, target: e.to,
      className: own(by, id) && by[id].length ? 'has-problem' : undefined,
      ariaLabel: (titleById.get(e.from) || e.from) + ' to ' + (titleById.get(e.to) || e.to) + (e.when ? ' ' + e.when : ''),
      deletable: !readOnly,
    };
    if (e.when) edge.label = e.when;
    return edge;
  });
  return { nodes, edges, lanes: lm.lanes };
}

/** {ok:true} | {ok:false, reason}. conn = {source, target, when?}. */
export function canConnect(conn, pipe, nodeTypes) {
  const a = (pipe.nodes || []).find((n) => n.id === conn.source);
  const b = (pipe.nodes || []).find((n) => n.id === conn.target);
  if (!a || !b) return { ok: false, reason: 'No such node' };
  if (a.id === b.id) return { ok: false, reason: "A node can't connect to itself" };
  const ta = typeOf(nodeTypes, a.type), tb = typeOf(nodeTypes, b.type);
  if (nodeDomain(a, ta) !== nodeDomain(b, tb) && !isPhotosBinding(a, b)) return { ok: false, reason: titleOf(b, tb) + ' is in another domain' };
  const r = connect(pipe, conn.source, conn.target, conn.when, nodeTypes);
  return r.error ? { ok: false, reason: r.error } : { ok: true };
}

/** "from>to>when" → {from, to, when?}. */
export function edgeFromId(edgeId) {
  const parts = String(edgeId).split('>');
  const from = parts[0], to = parts[1], when = parts.slice(2).join('>');
  return when === '' ? { from, to } : { from, to, when };
}

/**
 * change = {kind:"add", nodeType (object or type string), after} | {kind:"connect", from, to, when?} | {kind:"remove", nodeId}
 *        | {kind:"removeEdge", edgeId} | {kind:"param", nodeId, key, value} | {kind:"title", nodeId, title}
 * → {pipe, error?}; the draft is never mutated.
 */
export function applyChange(draft, change, nodeTypes) {
  switch (change && change.kind) {
    case 'add': {
      const nt = typeof change.nodeType === 'string' ? typeOf(nodeTypes, change.nodeType) : change.nodeType;
      if (!nt) return { pipe: draft, error: 'Unknown node type' };
      return addNode(draft, nt, change.after == null ? null : change.after, nodeTypes);
    }
    case 'connect': return connect(draft, change.from, change.to, change.when, nodeTypes);
    case 'remove': return removeNode(draft, change.nodeId);
    case 'removeEdge': return disconnect(draft, edgeFromId(change.edgeId));
    case 'param': return setParam(draft, change.nodeId, change.key, change.value);
    case 'title': return setTitle(draft, change.nodeId, change.title);
    default: return { pipe: draft, error: 'Unknown change' };
  }
}

/**
 * The named links between lanes (S34): each `signal.out`/`signal.in` name once per side, and the `photos` binding (capture.image to the Photo input).
 * → [{name, kind: 'signal'|'photos', from, to, state: 'bound'|'unreceived'|'unsent'|'unbound', text}], sorted by name.
 */
export function bindingsOf(pipe, nodeTypes) {
  const out = [];
  const nodes = pipe.nodes || [];
  const title = (n) => titleOf(n, typeOf(nodeTypes, n.type));
  const where = (n) => ({ RT_GLASSES: 'glasses', RT_PHONE: 'phone', ASYNC: 'async' }[nodeDomain(n, typeOf(nodeTypes, n.type))] || 'other');
  const named = (n) => (n.params && typeof n.params.name === 'string' ? n.params.name : '');
  const sigs = nodes.filter((n) => n.type === 'signal.out' || n.type === 'signal.in');
  for (const name of Array.from(new Set(sigs.map(named).filter(Boolean))).sort()) {
    const o = sigs.find((n) => n.type === 'signal.out' && named(n) === name);
    const i = sigs.find((n) => n.type === 'signal.in' && named(n) === name);
    const state = o && i ? 'bound' : o ? 'unreceived' : 'unsent';
    const text = state === 'bound' ? name + ': ' + title(o) + ' (' + where(o) + ') to ' + title(i) + ' (' + where(i) + ')'
      : name + ': ' + title(o || i) + ' (' + where(o || i) + ') ' + (state === 'unreceived' ? 'sends it, but nothing receives it' : 'receives it, but nothing sends it');
    out.push({ name, kind: 'signal', from: o ? o.id : '', to: i ? i.id : '', state, text });
  }
  const cap = nodes.find((n) => n.type === 'capture.image');
  if (cap) {
    const edge = (pipe.edges || []).find((e) => e.from === cap.id && (nodes.find((n) => n.id === e.to) || {}).type === 'input.photos');
    const photo = edge ? nodes.find((n) => n.id === edge.to) : null;
    out.push(photo
      ? { name: 'photos', kind: 'photos', from: cap.id, to: photo.id, state: 'bound', text: 'photos: ' + title(cap) + ' (' + where(cap) + ') to ' + title(photo) + ' (' + where(photo) + ')' }
      : { name: 'photos', kind: 'photos', from: cap.id, to: '', state: 'unbound', text: 'photos: ' + title(cap) + ' is not connected to a Photo input' });
  }
  return out.sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0));
}
