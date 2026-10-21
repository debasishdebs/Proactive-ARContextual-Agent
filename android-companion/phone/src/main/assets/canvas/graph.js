// Pure pipeline-graph operations for the canvas editor. Never mutates its input;
// every operation returns { pipe, error? } (on error, pipe is the unchanged input).
import { portCheck, typeOf } from './validate.js';

const ID = /^[a-z][a-z0-9_]{0,31}$/;
const clone = (p) => JSON.parse(JSON.stringify(p));
const fail = (pipe, error) => ({ pipe, error });
const nodeOf = (pipe, id) => (pipe.nodes || []).find((n) => n.id === id) || null;

/** "<short>_<n>", smallest free n ≥ 1; the result always matches ^[a-z][a-z0-9_]{0,31}$. */
export function nextId(pipe, short) {
  let base = String(short || '').toLowerCase().replace(/[^a-z0-9_]+/g, '_').replace(/^[^a-z]+/, '');
  if (!base) base = 'node';
  base = base.slice(0, 24);
  const taken = new Set((pipe.nodes || []).map((n) => n.id));
  for (let n = 1; ; n++) {
    const id = base + '_' + n;
    if (!taken.has(id)) return id;
  }
}

/** The branch labels a node can emit (a switch: its cases plus "other"). */
export function branchesOf(node, nodeTypes) {
  const t = typeOf(nodeTypes, node.type);
  if (!t) return [];
  if (t.branchesFromCases || node.type === 'route.switch') {
    const cases = Array.isArray(node.params && node.params.cases) ? node.params.cases.filter((c) => typeof c === 'string') : [];
    return cases.concat('other');
  }
  return (t.branches || []).slice();
}

function firstUnused(pipe, node, nodeTypes) {
  const used = new Set((pipe.edges || []).filter((e) => e.from === node.id).map((e) => e.when));
  return branchesOf(node, nodeTypes).find((b) => !used.has(b));
}

function defaultParams(nodeType) {
  const out = {};
  for (const d of nodeType.params || []) {
    if (d.default !== undefined) { out[d.name] = d.default; continue; }
    switch (d.kind) {
      case 'int': out[d.name] = d.min !== undefined ? d.min : 0; break;
      case 'bool': out[d.name] = false; break;
      case 'text': out[d.name] = ''; break;
      case 'prompt': out[d.name] = { source: 'live' }; break;
      case 'labels': out[d.name] = ['case_1']; break;
      default: break; // role, schema: absent until the user picks (the server validates)
    }
  }
  return out;
}

/**
 * Adds a node of nodeType; an edge after → new when the types fit (a branching source: its first unused branch).
 * nodeTypes (the /api/node-types list) is needed to know the source's output and branches; without it no edge is made.
 */
export function addNode(pipe, nodeType, after, nodeTypes) {
  if (nodeType.type === 'input.photos') return fail(pipe, 'There is already a Photo input');
  const next = clone(pipe);
  next.nodes = next.nodes || []; next.edges = next.edges || [];
  const id = nextId(next, nodeType.short || String(nodeType.type).split('.').pop());
  next.nodes.push({ id, type: nodeType.type, title: nodeType.label, params: defaultParams(nodeType) });
  const src = after && nodeTypes ? nodeOf(next, after) : null;
  if (src) {
    if (portCheck(next, src.id, id, nodeTypes) === null) {
      if (branchesOf(src, nodeTypes).length === 0) next.edges.push({ from: src.id, to: id });
      else {
        const free = firstUnused(next, src, nodeTypes);
        if (free !== undefined) next.edges.push({ from: src.id, to: id, when: free });
      }
    }
  }
  return { pipe: next };
}

/** Removes the node and its edges; the Photo input can't be removed. */
export function removeNode(pipe, nodeId) {
  const n = nodeOf(pipe, nodeId);
  if (!n) return fail(pipe, 'No such node');
  if (n.type === 'input.photos') return fail(pipe, "input.photos can't be removed");
  const next = clone(pipe);
  next.nodes = next.nodes.filter((x) => x.id !== nodeId);
  next.edges = (next.edges || []).filter((e) => e.from !== nodeId && e.to !== nodeId);
  return { pipe: next };
}

function reaches(pipe, start, goal) {
  const seen = new Set([start]);
  const stack = [start];
  while (stack.length) {
    const cur = stack.pop();
    if (cur === goal) return true;
    for (const e of pipe.edges || []) if (e.from === cur && !seen.has(e.to)) { seen.add(e.to); stack.push(e.to); }
  }
  return false;
}

/** from → to (with `when` on a branching source; omitted: its first unused branch). Refuses self, duplicate, port mismatch and loops. */
export function connect(pipe, from, to, when, nodeTypes) {
  const a = nodeOf(pipe, from), b = nodeOf(pipe, to);
  if (!a || !b) return fail(pipe, 'No such node');
  if (from === to) return fail(pipe, "A node can't connect to itself");
  const bs = branchesOf(a, nodeTypes);
  let w = when === undefined || when === null || when === '' ? undefined : when;
  if (bs.length === 0) {
    if (w !== undefined) return fail(pipe, (a.title || a.id) + ' has no branches');
  } else if (w === undefined) {
    w = firstUnused(pipe, a, nodeTypes);
    if (w === undefined) return fail(pipe, 'All branches of ' + (a.title || a.id) + ' are connected');
  } else if (!bs.includes(w)) {
    return fail(pipe, a.id + ' has no branch "' + w + '"');
  }
  if ((pipe.edges || []).some((e) => e.from === from && e.to === to && (e.when == null ? undefined : e.when) === w)) return fail(pipe, 'Already connected');
  const refusal = portCheck(pipe, from, to, nodeTypes);
  if (refusal) return fail(pipe, refusal);
  if (reaches(pipe, to, from)) return fail(pipe, 'That would make a loop');
  const next = clone(pipe);
  next.edges = next.edges || [];
  next.edges.push(w === undefined ? { from, to } : { from, to, when: w });
  return { pipe: next };
}

/** Removes the edge matching {from, to, when?}. */
export function disconnect(pipe, edge) {
  const w = edge.when == null ? undefined : edge.when;
  const i = (pipe.edges || []).findIndex((e) => e.from === edge.from && e.to === edge.to && (e.when == null ? undefined : e.when) === w);
  if (i < 0) return fail(pipe, 'No such edge');
  const next = clone(pipe);
  next.edges.splice(i, 1);
  return { pipe: next };
}

/** Sets one param; value === undefined removes it. */
export function setParam(pipe, nodeId, key, value) {
  if (!nodeOf(pipe, nodeId)) return fail(pipe, 'No such node');
  const next = clone(pipe);
  const n = nodeOf(next, nodeId);
  n.params = n.params || {};
  if (value === undefined) delete n.params[key]; else n.params[key] = value;
  return { pipe: next };
}

export function setTitle(pipe, nodeId, title) {
  if (!nodeOf(pipe, nodeId)) return fail(pipe, 'No such node');
  const next = clone(pipe);
  nodeOf(next, nodeId).title = String(title);
  return { pipe: next };
}
