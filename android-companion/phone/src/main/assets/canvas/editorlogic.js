// Every decision of the DAG editor screen, pure: no React, no DOM, no fetch. floweditor.js only renders this state.
// State (`ed`) is never mutated; each operation returns a new one (or the same object when nothing changed).
import * as draft from './draftstate.js';
import { toFlow, canConnect, bindingsOf, lanesOf } from './flowadapter.js';
import { branchesOf } from './graph.js';
import { typeOf, own, edgeKey, attributeProblems } from './validate.js';
import { fields, coerce, coerceCell, promptParts, coercePromptPart } from './paramform.js';
import * as keys from './keys.js';
import { exprMeters, panelFor } from './exprgraph.js';

/** Model roles an `ai.prompt` node can use (services/ai Role; EMBED is not allowed by the validator). */
export const ROLES = ['VISION_EXTRACT', 'EXPLICIT_REMEMBER', 'SUMMARIZE', 'CRITIC', 'ANSWER', 'NUDGE'];
export const PROMPTS = [{ value: 'live', label: 'Live prompt' }, { value: 'draft', label: 'Draft (edit here)' }];
export const READ_ONLY_TEXT = 'Built-in pipelines are read-only. Duplicate it to edit.';
const EDITABLE_TAGS = ['INPUT', 'TEXTAREA', 'SELECT'];

const nodeById = (pipe, id) => (pipe.nodes || []).find((n) => n.id === id) || null;
const titleOf = (pipe, id, nodeTypes) => { const n = nodeById(pipe, id); return n ? String(n.title || (typeOf(nodeTypes, n.type) || {}).label || n.id) : String(id); };
const edgeIdsOf = (pipe) => (pipe.edges || []).map(edgeKey);
const noSel = () => ({ nodes: [], edges: [] });

// ---------------------------------------------------------------- state

/** layout/problems: from GET /api/pipelines/{id}; opts: {readOnly}. */
export function open(pipe, serverRev, layout, problems, opts) {
  let ds = draft.init(pipe, serverRev);
  if (layout) ds = draft.validationReply(ds, { rev: 0, layout, problems: problems || [] });
  return { ds, cleanRev: 0, readOnly: !!(opts && opts.readOnly), sel: noSel(), nav: keys.idle, status: '', armed: null, choose: null, compile: null };
}

/** Dirty is tracked here: the draft's own revision against the revision last saved (or loaded). */
export const isDirty = (ed) => ed.ds.rev !== ed.cleanRev;

/** A save of revision atRev succeeded and the server answered serverRev. A later edit stays dirty. */
export function markSaved(ed, serverRev, atRev) {
  return Object.assign({}, ed, { cleanRev: atRev, ds: Object.assign({}, ed.ds, { savedRev: serverRev }) });
}

/** The confirm text before something that would drop unsaved edits (leave, reload after publish); null when saved. */
export const leaveText = (ed, what) => (isDirty(ed) ? (what ? what + ' ' : '') + 'You have unsaved changes to this pipeline. Continue and lose them?' : null);

export const lockForSave = (ed) => Object.assign({}, ed, { ds: draft.lock(ed.ds), choose: null });
export const unlockAfterSave = (ed, savedRev) => Object.assign({}, ed, { ds: draft.unlock(ed.ds, savedRev) });
export const isReadOnly = (ed) => ed.readOnly || ed.ds.locked;
export const say = (ed, status) => (ed.status === status ? ed : Object.assign({}, ed, { status }));

/** The request body's draft part for POST /api/validate. */
export function validationRequest(ed) {
  return { json: JSON.stringify(ed.ds.pipe), rev: draft.validationSent(ed.ds).rev };
}

/** A /api/validate reply: applied only for the current revision. */
export function applyReply(ed, reply) {
  const ds = draft.validationReply(ed.ds, reply);
  return ds === ed.ds ? ed : Object.assign({}, ed, { ds });
}

/** A tiny observable holder, so the async flows and React see the same, always-current state. */
export function createStore(initial) {
  let cur = initial;
  const subs = new Set();
  const store = {
    get: () => cur,
    set(next) { if (next === cur) return; cur = next; for (const f of Array.from(subs)) f(); },
    update(fn) { store.set(fn(cur)); },
    subscribe(f) { subs.add(f); return () => { subs.delete(f); }; },
  };
  return store;
}

// ---------------------------------------------------------------- changes

function prune(ed, pipe) {
  const ids = new Set((pipe.nodes || []).map((n) => n.id));
  const eids = new Set(edgeIdsOf(pipe));
  const sel = { nodes: ed.sel.nodes.filter((i) => ids.has(i)), edges: ed.sel.edges.filter((i) => eids.has(i)) };
  const nav = ed.nav.mode === 'connecting' && !(ids.has(ed.nav.from) && ids.has(ed.nav.target)) ? keys.idle : ed.nav;
  return Object.assign({}, ed, { sel, nav });
}

/** One change through draftstate. → {ed, error?}; a refusal shows in the status line and changes nothing else. */
export function dispatch(ed, chg, nodeTypes) {
  if (ed.readOnly) return { ed: say(ed, READ_ONLY_TEXT), error: READ_ONLY_TEXT };
  const r = draft.change(ed.ds, chg, nodeTypes);
  if (r.error) return { ed: say(ed, r.error), error: r.error };
  const before = new Set((ed.ds.pipe.nodes || []).map((n) => n.id));
  let next = prune(Object.assign({}, ed, { ds: r.state, status: '', choose: null }), r.state.pipe);
  if (chg.kind === 'add') {
    const added = (r.state.pipe.nodes || []).map((n) => n.id).filter((i) => !before.has(i));
    next = Object.assign({}, next, { sel: { nodes: added, edges: [] }, armed: null });
  }
  return { ed: next };
}

/** Removes the listed nodes and edges, each only if it still exists (a second handler for the same key press finds nothing). */
export function removeItems(ed, items, nodeTypes) {
  let cur = ed;
  let refusal = '';
  for (const id of items.nodeIds || []) {
    if (!nodeById(cur.ds.pipe, id)) continue;
    const r = dispatch(cur, { kind: 'remove', nodeId: id }, nodeTypes);
    if (r.error) refusal = r.error; else cur = r.ed;
  }
  for (const eid of items.edgeIds || []) {
    if (!edgeIdsOf(cur.ds.pipe).includes(eid)) continue;
    const r = dispatch(cur, { kind: 'removeEdge', edgeId: eid }, nodeTypes);
    if (r.error) refusal = r.error; else cur = r.ed;
  }
  return refusal ? say(cur, refusal) : cur;
}

/** React Flow's onBeforeDelete: only the items the flow marks deletable (never the Photo input, nothing when read-only). */
export function deletable(items) {
  return { nodes: (items.nodes || []).filter((n) => n.deletable !== false), edges: (items.edges || []).filter((e) => e.deletable !== false) };
}

export function titleChange(ed, nodeId, title, nodeTypes) { return dispatch(ed, { kind: 'title', nodeId, title }, nodeTypes); }

// ---------------------------------------------------------------- selection

export function selectNode(ed, id) { return Object.assign({}, ed, { sel: { nodes: [id], edges: [] } }); }
export function selectEdge(ed, id) { return Object.assign({}, ed, { sel: { nodes: [], edges: [id] } }); }
export function clearSelection(ed) { return ed.sel.nodes.length || ed.sel.edges.length ? Object.assign({}, ed, { sel: noSel() }) : ed; }
export const singleNode = (ed) => (ed.sel.nodes.length === 1 ? ed.sel.nodes[0] : null);

/** kind: 'node' | 'edge'; changes: React Flow's onNodesChange/onEdgesChange arrays (only `select` ones count). */
export function applySelection(ed, kind, changes) {
  const key = kind === 'edge' ? 'edges' : 'nodes';
  let list = ed.sel[key];
  for (const c of changes || []) {
    if (!c || c.type !== 'select') continue;
    if (c.selected && !list.includes(c.id)) list = list.concat(c.id);
    else if (!c.selected && list.includes(c.id)) list = list.filter((i) => i !== c.id);
  }
  if (list === ed.sel[key]) return ed;
  return Object.assign({}, ed, { sel: Object.assign({}, ed.sel, { [key]: list }) });
}

// ---------------------------------------------------------------- connect

/** conn: {source, target, when?} → apply / choose a branch / refuse. */
export function connectPlan(ed, conn, nodeTypes) {
  const pipe = ed.ds.pipe;
  const c = canConnect(conn, pipe, nodeTypes);
  if (!c.ok) return { kind: 'refuse', reason: c.reason };
  const src = nodeById(pipe, conn.source);
  const bs = branchesOf(src, nodeTypes);
  if (bs.length === 0 || (conn.when !== undefined && conn.when !== null && conn.when !== '')) {
    return { kind: 'apply', change: { kind: 'connect', from: conn.source, to: conn.target, when: conn.when } };
  }
  const used = new Set((pipe.edges || []).filter((e) => e.from === src.id).map((e) => e.when));
  const free = bs.filter((b) => !used.has(b));
  return { kind: 'choose', from: conn.source, to: conn.target, branches: free, pick: free[0] };
}

/** [{id, title}]: the nodes `source` can connect to right now (the button equivalent of dragging a handle). */
export function connectOptions(ed, source, nodeTypes) {
  return (ed.ds.pipe.nodes || []).filter((n) => n.id !== source && canConnect({ source, target: n.id }, ed.ds.pipe, nodeTypes).ok)
    .map((n) => ({ id: n.id, title: titleOf(ed.ds.pipe, n.id, nodeTypes) }));
}

/** Applies a connection (or opens the branch picker, or shows why not). */
export function connectTo(ed, conn, nodeTypes) {
  const plan = connectPlan(ed, conn, nodeTypes);
  if (plan.kind === 'refuse') return say(ed, plan.reason);
  if (plan.kind === 'choose') return Object.assign({}, ed, { choose: { from: plan.from, to: plan.to, branches: plan.branches, pick: plan.pick }, status: '' });
  return dispatch(ed, plan.change, nodeTypes).ed;
}

export function pickBranch(ed, branch) {
  if (!ed.choose || !ed.choose.branches.includes(branch)) return ed;
  return Object.assign({}, ed, { choose: Object.assign({}, ed.choose, { pick: branch }) });
}
export function confirmChoose(ed, nodeTypes) {
  if (!ed.choose) return ed;
  const c = ed.choose;
  return dispatch(Object.assign({}, ed, { choose: null }), { kind: 'connect', from: c.from, to: c.to, when: c.pick }, nodeTypes).ed;
}
export const cancelChoose = (ed) => (ed.choose ? Object.assign({}, ed, { choose: null }) : ed);

/** onConnectEnd(event, connectionState): why a drop on a node was refused ('' when it was made, or on the empty pane). */
export function refusalFromConnectionState(cs, pipe, nodeTypes) {
  if (!cs || cs.isValid !== false || !cs.fromNode || !cs.toNode) return '';
  const fromTarget = !!(cs.fromHandle && cs.fromHandle.type === 'target');
  const conn = fromTarget ? { source: cs.toNode.id, target: cs.fromNode.id } : { source: cs.fromNode.id, target: cs.toNode.id };
  const r = canConnect(conn, pipe, nodeTypes);
  return r.ok ? '' : r.reason;
}

// ---------------------------------------------------------------- palette

/** The generic flow (specs/2026-10-24-generic-flow-families.md): the palette groups are these five, in this order; a node's lane is an attribute of its item, not a group. */
export const FAMILIES = ['Source', 'Aggregate', 'Process', 'Condition', 'Outcome'];
export const SOON_TEXT = 'Coming soon';
/** The wording of the inspector's chooser for the following node (the last node has none to pick). */
export const NEXT_STEP_LABEL = 'Next step';
export const NO_NEXT_STEP = 'No next step needed';

/** [{category, soon, items:[{type, label, lane, hint?}]}] for the palette: always the five families (an empty one is `soon`); a type with no family goes under Other. Empty when read-only. */
export function paletteGroups(ed, nodeTypes) {
  if (isReadOnly(ed)) return [];
  const groups = FAMILIES.map((category) => ({ category, soon: false, items: [] }));
  const other = { category: 'Other', soon: false, items: [] };
  for (const t of nodeTypes || []) {
    // `expr` is registered but hidden until 3c; the python experiment node and the Scenes input are the unliveable types offered (S6 keeps them out of every publish)
    if (!t.palette || (t.liveable === false && t.executor !== 'PYTHON' && t.type !== 'input.scenes')) continue;
    const g = groups.find((x) => x.category === t.family) || other;
    const lane = t.domain || 'ASYNC';
    g.items.push(t.hint ? { type: t.type, label: t.label, lane, hint: t.hint } : { type: t.type, label: t.label, lane });
  }
  for (const g of groups) g.soon = g.items.length === 0;
  return other.items.length ? groups.concat(other) : groups;
}

/** Adds a node of `type` after the selected node (if one is selected), and selects it. */
export function addFromPalette(ed, type, nodeTypes) {
  return dispatch(ed, { kind: 'add', nodeType: type, after: singleNode(ed) }, nodeTypes).ed;
}

/** The node just above a point (flow coordinates): the smallest vertical gap, then the nearer column. Never stores a position. */
export function nearestAbove(nodes, pos) {
  let best = null, bestKey = null;
  for (const n of nodes || []) {
    const gap = pos.y - n.position.y;
    if (gap <= 0) continue;
    const k = [gap, Math.abs(n.position.x + 90 - pos.x)];
    if (!bestKey || k[0] < bestKey[0] || (k[0] === bestKey[0] && k[1] < bestKey[1])) { best = n.id; bestKey = k; }
  }
  return best;
}

/** Touch flow: tap a palette button, then tap the pane. */
export function arm(ed, type, nodeTypes) {
  const t = typeOf(nodeTypes, type);
  return Object.assign({}, ed, { armed: type, status: 'Tap the canvas to place ' + (t ? t.label : type) + '.' });
}

/** A pane tap places the armed node after the nearest node above it, or else clears the selection. */
export function paneClick(ed, pos, flowNodes, nodeTypes) {
  if (!ed.armed) return clearSelection(ed);
  return dispatch(Object.assign({}, ed, { armed: null }), { kind: 'add', nodeType: ed.armed, after: nearestAbove(flowNodes, pos) }, nodeTypes).ed;
}

/** HTML5 drop: the same placement, from a dragged palette type. */
export function dropOnPane(ed, type, pos, flowNodes, nodeTypes) {
  return dispatch(ed, { kind: 'add', nodeType: type, after: nearestAbove(flowNodes, pos) }, nodeTypes).ed;
}

// ---------------------------------------------------------------- the view model

/** toFlow plus the editor's selection and connect-mode marks, and the compile marks while they still describe this draft. */
export function flowFor(ed, nodeTypes, expanded) {
  const c = compileView(ed);
  const marks = c && c.state === 'done' && !c.stale ? c : null;
  const problems = marks ? (ed.ds.problems || []).concat(marks.problems.filter((p) => p.nodeId || p.edge)) : ed.ds.problems;
  const f = toFlow(ed.ds.pipe, ed.ds.layout, problems, nodeTypes, { expanded, readOnly: isReadOnly(ed), warnings: marks ? marks.warnings.filter((w) => w.nodeId) : [] });
  const connecting = ed.nav.mode === 'connecting';
  for (const n of f.nodes) {
    n.selected = ed.sel.nodes.includes(n.id);
    n.data = Object.assign({}, n.data, { role: connecting ? (n.id === ed.nav.from ? 'from' : n.id === ed.nav.target ? 'target' : null) : null });
  }
  for (const e of f.edges) e.selected = ed.sel.edges.includes(e.id);
  return f;
}

/**
 * React Flow measures nodes itself; in a controlled flow the measured size must come back in the node objects, and a node
 * object that is rebuilt every render makes it forget it. recordMeasures keeps the sizes from `dimensions` changes;
 * stabilize puts them on the nodes and reuses the previous node/edge object whenever nothing about it changed.
 */
export function recordMeasures(map, changes) {
  let out = map;
  for (const c of changes || []) {
    if (!c || c.type !== 'dimensions' || !c.dimensions) continue;
    const old = own(out, c.id) ? out[c.id] : null;
    if (old && old.width === c.dimensions.width && old.height === c.dimensions.height) continue;
    if (out === map) out = Object.assign(Object.create(null), map);
    out[c.id] = { width: c.dimensions.width, height: c.dimensions.height };
  }
  return out;
}

export function stabilize(prev, next, measured) {
  const old = new Map(((prev && prev.nodes) || []).map((n) => [n.id, n]));
  const oldEdges = new Map(((prev && prev.edges) || []).map((e) => [e.id, e]));
  const nodes = next.nodes.map((n) => {
    const cand = own(measured, n.id) ? Object.assign({}, n, { measured: measured[n.id] }) : n;
    const was = old.get(n.id);
    return was && JSON.stringify(was) === JSON.stringify(cand) ? was : cand;
  });
  const edges = next.edges.map((e) => { const was = oldEdges.get(e.id); return was && JSON.stringify(was) === JSON.stringify(e) ? was : e; });
  const sameList = (a, b) => a && a.length === b.length && a.every((x, i) => x === b[i]);
  return {
    nodes: prev && sameList(prev.nodes, nodes) ? prev.nodes : nodes,
    edges: prev && sameList(prev.edges, edges) ? prev.edges : edges,
  };
}

/** The domain lanes the canvas draws ([] for a single-domain graph). */
export const lanesFor = (ed, nodeTypes) => lanesOf(ed.ds.pipe, ed.ds.layout, nodeTypes);

/** The named links between lanes, for the bindings list. */
export const bindingsFor = (ed, nodeTypes) => bindingsOf(ed.ds.pipe, nodeTypes);

// ---------------------------------------------------------------- compile (POST /api/plans/compile)

/** The compile route is read-only: it sends nothing and stores nothing, and it never saves the draft. */
export const compileRequest = (ed) => ({ json: JSON.stringify(ed.ds.pipe) });

/** Marks a compile of the current revision as running (a newer start supersedes an older one in flight). */
export const compileStart = (ed) => Object.assign({}, ed, { compile: { state: 'running', rev: ed.ds.rev } });

const utf8Length = (str) => { let n = 0; for (const ch of String(str)) { const c = ch.codePointAt(0); n += c < 0x80 ? 1 : c < 0x800 ? 2 : c < 0x10000 ? 3 : 4; } return n; };

/** A compile reply for `rev`: dropped unless it is the running compile's. Kept even when the draft has moved on (the view then says stale). */
export function applyCompile(ed, rev, reply) {
  if (!ed.compile || ed.compile.rev !== rev || ed.compile.state !== 'running') return ed;
  const cost = (reply && reply.staticCost) || {};
  return Object.assign({}, ed, {
    compile: {
      state: 'done', rev, ok: !!(reply && reply.ok), planBytes: reply && typeof reply.planJson === 'string' ? utf8Length(reply.planJson) : null,
      hash: reply && reply.configHash ? String(reply.configHash) : '', exprs: exprMeters(reply && reply.planJson), modelCalls: Number(cost.modelCalls) || 0, rtOps: Number(cost.rtOps) || 0,
      problems: attributeProblems(reply && reply.problems), warnings: attributeProblems(reply && reply.warnings),
    },
  });
}

export function compileFailed(ed, rev, message) {
  if (!ed.compile || ed.compile.rev !== rev || ed.compile.state !== 'running') return ed;
  return Object.assign({}, ed, { compile: { state: 'failed', rev, message: String(message || 'The compile failed.') } });
}

/** A warning that the compiler words for the whole plan belongs on the node that causes it. */
const WARNING_HOMES = [{ match: /timer photo gap/, type: 'policy.capture_rate' }];
function placeWarnings(pipe, list) {
  const out = [];
  for (const w of list) {
    if (w.nodeId) { out.push(w); continue; }
    const home = WARNING_HOMES.find((h) => h.match.test(w.text));
    const nodes = home ? (pipe.nodes || []).filter((n) => n.type === home.type) : [];
    if (nodes.length === 0) out.push(w); else for (const n of nodes) out.push({ text: w.text, nodeId: n.id });
  }
  return out;
}

/** null before any compile; else {state, stale, ok, problems, warnings, summary, note, message}. A stale view's marks are not drawn on the nodes. */
export function compileView(ed) {
  const c = ed.compile;
  if (!c) return null;
  const stale = c.rev !== ed.ds.rev;
  if (c.state !== 'done') return { state: c.state, stale, ok: false, problems: [], warnings: [], summary: '', note: '', message: c.message || '' };
  const bytes = c.planBytes === null ? '' : c.planBytes + ' bytes';
  const summary = c.ok
    ? 'Compiles: plan ' + bytes + (c.hash ? ', hash ' + c.hash.slice(0, 8) : '') + ', ' + c.modelCalls + ' model calls, ' + c.rtOps + ' rt ops.'
    : 'Does not compile yet: ' + c.problems.length + (c.problems.length === 1 ? ' problem.' : ' problems.');
  const note = stale ? 'The draft has changed since this compile. Compile again.' : isDirty(ed) ? 'Compiled your unsaved edits. Save to keep them.' : '';
  return { state: 'done', stale, ok: c.ok, exprs: c.exprs, planBytes: c.planBytes, problems: c.problems, warnings: placeWarnings(ed.ds.pipe, c.warnings), summary, note, message: '' };
}

/** What the boxed editor of an expr.guard node shows from the compile view: EXPR_* problems placed on steps and nodes, and the meters. */
export function exprPanel(ed, nodeId, graph) { return panelFor(compileView(ed), nodeId, graph); }

/** [{text, nodeId?, edgeId?}]: what the problems list shows; nodeId is what a click focuses. */
export function problemItems(ed) {
  const out = [];
  for (const p of ed.ds.problems || []) {
    if (typeof p === 'string') { out.push({ text: p }); continue; }
    if (!p) continue;
    const item = { text: String(p.text) };
    if (p.nodeId) item.nodeId = p.nodeId;
    else if (p.edge) item.nodeId = p.edge.from;
    if (p.edge) item.edgeId = edgeKey(p.edge);
    out.push(item);
  }
  return out;
}

// ---------------------------------------------------------------- the inspector

const liveOf = (env, id) => (env && env.activePrompts && own(env.activePrompts, id) ? env.activePrompts[id] : null);
const FIELD_CTX = { roles: ROLES, prompts: PROMPTS };

/** The selected node's inspector model, or null. fields come from paramform; a prompt field also carries its inline `parts`. */
export function inspectorFor(ed, nodeTypes, env) {
  const id = singleNode(ed);
  const node = id && nodeById(ed.ds.pipe, id);
  if (!node) return null;
  const nt = typeOf(nodeTypes, node.type);
  const fs = fields(nt ? nt.params : [], node.params || {}, FIELD_CTX);
  for (const f of fs) if (f.kind === 'prompt') f.parts = promptParts(f, liveOf(env, id));
  return {
    id, type: node.type, typeLabel: nt ? nt.label : node.type, title: String(node.title || ''),
    readOnly: isReadOnly(ed), removable: !isReadOnly(ed) && node.type !== 'input.photos', fields: fs,
  };
}

/**
 * A value typed in the inspector. name: the param; part: null, or 'template' | 'schema' of an inline prompt.
 * → {ed, error?}: a value that fails to coerce changes nothing (the error shows beside the field).
 */
export function inputChange(ed, nodeId, name, part, raw, nodeTypes, env) {
  const node = nodeById(ed.ds.pipe, nodeId);
  const nt = node && typeOf(nodeTypes, node.type);
  const desc = nt && (nt.params || []).find((d) => d.name === name);
  if (!node || !desc) return { ed, error: 'No such node' };
  const f = fields([desc], node.params || {}, FIELD_CTX)[0];
  const r = part ? coercePromptPart(f, part, raw) : coerce(f, raw);
  if (r.error) return { ed, error: r.error };
  let value = r.value;
  if (!part && f.kind === 'prompt' && value && value.source === 'draft' && value.template === '') {
    const live = liveOf(env, nodeId); // a new draft starts from the live prompt
    if (live) {
      value = Object.assign({}, value, { template: String(live.template == null ? '' : live.template) });
      if (live.schema != null) value.schema = typeof live.schema === 'string' ? live.schema : JSON.stringify(live.schema);
    }
  }
  return dispatch(ed, { kind: 'param', nodeId, key: name, value }, nodeTypes);
}

/**
 * One cell of a table param typed in the inspector. → {ed, error?}; a value that fails to coerce changes nothing.
 * A blank cell is removed (a gap inherits the settings profile, a cap is unchanged); a table left empty removes the param.
 */
export function tableChange(ed, nodeId, name, row, col, raw, nodeTypes) {
  const node = nodeById(ed.ds.pipe, nodeId);
  const nt = node && typeOf(nodeTypes, node.type);
  const desc = nt && (nt.params || []).find((d) => d.name === name && d.kind === 'table');
  if (!node || !desc) return { ed, error: 'No such node' };
  const r = coerceCell(fields([desc], node.params || {}, FIELD_CTX)[0], row, col, raw);
  if (r.error) return { ed, error: r.error };
  return dispatch(ed, { kind: 'param', nodeId, key: name, value: r.value }, nodeTypes);
}

/** What POST /api/check-prompt gets for a node: the text as typed now (`texts` overrides the stored values), or null. */
export function checkPayload(ed, nodeId, nodeTypes, env, texts) {
  const node = nodeById(ed.ds.pipe, nodeId);
  const nt = node && typeOf(nodeTypes, node.type);
  if (!node || !nt) return null;
  const typed = texts || {};
  const params = node.params || {};
  const asText = (v) => (v == null ? '' : typeof v === 'string' ? v : JSON.stringify(v));
  const pick = (key, stored) => (own(typed, key) ? String(typed[key]) : asText(stored));
  let template, schema;
  if ((nt.params || []).some((d) => d.kind === 'prompt')) {
    const p = params.prompt && typeof params.prompt === 'object' ? params.prompt : { source: 'live' };
    const src = p.source === 'draft' ? p : liveOf(env, nodeId) || {};
    template = pick('prompt.template', src.template); schema = pick('prompt.schema', src.schema);
  } else if ((nt.params || []).some((d) => d.name === 'template')) {
    template = pick('template', params.template); schema = pick('schema', params.schema);
  } else return null;
  return { nodeType: node.type, template, schema: schema.trim() ? schema : null };
}

// ---------------------------------------------------------------- keyboard

/**
 * One key press on the pane. ev: {key, tag, editable, mod, nodeId (the focused node, or null)}.
 * → {ed, handled, stop?, effect?}: effect is {kind:'focus', id} | {kind:'palette'}; the component performs it.
 * Keys typed in inputs, textareas, selects and contenteditable, and any ctrl/meta/alt combination, are never ours.
 * Delete/Backspace on a focused node removes it here and `stop`s the event so React Flow's own delete handler
 * never sees it; if it does anyway, removeItems finds nothing left to remove.
 */
export function keyDecision(ed, ev, nodeTypes) {
  const none = { ed, handled: false };
  if (!ev || ev.mod || ev.editable || EDITABLE_TAGS.includes(ev.tag)) return none;
  if ((ev.tag === 'BUTTON' || ev.tag === 'A') && (ev.key === 'Enter' || ev.key === ' ')) return none;
  const current = ev.nodeId || singleNode(ed);
  const cells = (ed.ds.layout && ed.ds.layout.cells) || {};
  const r = keys.reduce(ed.nav, ev.key, { cells, current });
  const next = r.state === ed.nav ? ed : Object.assign({}, ed, { nav: r.state });
  const a = r.action;
  if (!a) {
    if (ev.key === 'c' && r.state.mode === 'connecting') return { ed: say(next, 'Connect mode: arrows pick a target, Enter connects, Escape cancels.'), handled: true };
    if (ev.key === 'c' && ed.nav.mode === 'idle') return { ed: say(ed, 'Focus a node first, then press c.'), handled: true };
    return next === ed ? none : { ed: next, handled: true };
  }
  switch (a.action) {
    case 'focus': return { ed: next, handled: true, effect: { kind: 'focus', id: a.id } };
    case 'target': return { ed: say(next, 'Connect to ' + titleOf(next.ds.pipe, a.id, nodeTypes) + '? Enter to confirm.'), handled: true, effect: { kind: 'focus', id: a.id } };
    case 'cancel': return { ed: say(next, 'Connect cancelled.'), handled: true };
    case 'palette': return { ed: next, handled: true, effect: { kind: 'palette' } };
    case 'connect': return { ed: connectTo(next, { source: a.from, target: a.to }, nodeTypes), handled: true };
    case 'remove': return { ed: removeItems(next, { nodeIds: [a.id], edgeIds: [] }, nodeTypes), handled: true, stop: true };
    default: return none;
  }
}
