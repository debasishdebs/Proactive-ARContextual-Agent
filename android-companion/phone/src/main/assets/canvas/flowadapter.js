// Pipeline model <-> React Flow view model. Pure: no React, no DOM. React Flow never owns the data.
import { addNode, removeNode, connect, disconnect, setParam, setTitle } from './graph.js';
import { portLabel, problemsByNode, edgeKey, typeOf } from './validate.js';

export const CELL_W = 220, CELL_H = 120;

const domainOf = (nt) => (nt && nt.domain) || 'ASYNC';
const titleOf = (n, nt) => String(n.title || (nt && nt.label) || n.id);

/**
 * → {nodes, edges} for a controlled <ReactFlow>. Positions come from the layout cells; nodes not laid out yet
 * (added while /api/validate is pending) sit in a pending row below the last layer, in id order.
 */
export function toFlow(pipe, layout, problems, nodeTypes, opts) {
  const readOnly = !!(opts && opts.readOnly);
  const cells = (layout && layout.cells) || {};
  const layers = Math.max(0, Number(layout && layout.layers) || 0);
  const by = problemsByNode(problems);
  const nodes = (pipe.nodes || []).filter((n) => cells[n.id]).map((n) => node(n, cells[n.id].column * CELL_W, cells[n.id].layer * CELL_H, false));
  (pipe.nodes || []).filter((n) => !cells[n.id]).sort((a, b) => (a.id < b.id ? -1 : a.id > b.id ? 1 : 0))
    .forEach((n, i) => nodes.push(node(n, i * CELL_W, layers * CELL_H, true)));
  // Keep the pipeline's node order for stable rendering.
  const order = new Map((pipe.nodes || []).map((n, i) => [n.id, i]));
  nodes.sort((a, b) => order.get(a.id) - order.get(b.id));

  function node(n, x, y, pending) {
    const nt = typeOf(nodeTypes, n.type);
    const probs = by[n.id] || [];
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
        problems: probs.slice(), domain: domainOf(nt), pending, readOnly,
      },
      deletable: !readOnly && n.type !== 'input.photos',
      connectable: !readOnly,
      ariaLabel: title + ', ' + typeLabel + (probs.length ? ', ' + probs.length + (probs.length === 1 ? ' problem' : ' problems') : ''),
    };
  }

  const titleById = new Map((pipe.nodes || []).map((n) => [n.id, titleOf(n, typeOf(nodeTypes, n.type))]));
  const edges = (pipe.edges || []).map((e) => {
    const id = edgeKey(e);
    const edge = {
      id, source: e.from, target: e.to,
      className: (by[id] || []).length ? 'has-problem' : undefined,
      ariaLabel: (titleById.get(e.from) || e.from) + ' to ' + (titleById.get(e.to) || e.to) + (e.when ? ' ' + e.when : ''),
      deletable: !readOnly,
    };
    if (e.when) edge.label = e.when;
    return edge;
  });
  return { nodes, edges };
}

/** {ok:true} | {ok:false, reason}. conn = {source, target, when?}. */
export function canConnect(conn, pipe, nodeTypes) {
  const a = (pipe.nodes || []).find((n) => n.id === conn.source);
  const b = (pipe.nodes || []).find((n) => n.id === conn.target);
  if (!a || !b) return { ok: false, reason: 'No such node' };
  if (a.id === b.id) return { ok: false, reason: "A node can't connect to itself" };
  const ta = typeOf(nodeTypes, a.type), tb = typeOf(nodeTypes, b.type);
  if (domainOf(ta) !== domainOf(tb)) return { ok: false, reason: titleOf(b, tb) + ' is in another domain' };
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
