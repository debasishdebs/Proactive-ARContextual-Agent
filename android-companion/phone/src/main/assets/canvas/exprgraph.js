// The pure model of the `expr.guard` graph param (slice 3c X5): edit operations, loop refusal at edit time, the arrows between step
// boxes (derived from step inputs: they are the real connections), layouts, and the compile diagnostics/meters placed on steps.
// Every edit returns {graph, note?} or {error}; the input graph is never mutated. The server (ExprProgram.check) stays the authority.

const own = (o, k) => o != null && Object.prototype.hasOwnProperty.call(o, k);
const NAME = /^[a-z][a-z0-9_]{0,23}$/;
export const MAX_NODES = 32;
export const MAX_COST = 64;
export const MAX_BYTES = 8192;
export const MAX_CONST = 1000000;
export const MAX_FRESH_MS = 60000;
export const STATES = ['UNKNOWN', 'REST', 'WALKING', 'VEHICLE_PASSENGER', 'VEHICLE_DRIVING', 'FLIGHT'];
export const CMP = ['<', '<=', '>', '>=', '=='];
export const ARITH = ['+', '-', '*', '/'];
/** SignalCatalog: the seven per-second features, ctx, ctxAgeS and the alias headSpeed (= gyroMax). */
export const SIGNALS = [
  { name: 'gyroMax', note: 'rad/s' }, { name: 'gyroMean', note: 'rad/s' }, { name: 'gyroRms', note: 'rad/s' }, { name: 'aboveOnFrac', note: '0-1' },
  { name: 'accVar', note: '(m/s^2)^2' }, { name: 'stepHz', note: 'Hz' }, { name: 'rotRad', note: 'rad' }, { name: 'ctx', note: 'activity state' },
  { name: 'ctxAgeS', note: 's' }, { name: 'headSpeed', note: 'alias of gyroMax' },
];
/** Node ops: label and wired ports (every port is wired exactly once). */
export const OPS = {
  sig: { label: 'signal', ports: [] }, const: { label: 'constant', ports: [] }, cmp: { label: 'compare', ports: ['a', 'b'] }, arith: { label: 'arithmetic', ports: ['a', 'b'] },
  and: { label: 'and', ports: ['a', 'b'] }, or: { label: 'or', ports: ['a', 'b'] }, not: { label: 'not', ports: ['a'] }, in: { label: 'is in', ports: ['a'] },
  clamp: { label: 'clamp', ports: ['a'] }, choose: { label: 'choose', ports: ['a', 'b', 'c'] }, fresh: { label: 'fresh', ports: ['a'] }, win: { label: 'window', ports: [] },
};
export const opOf = (op) => (own(OPS, op) ? OPS[op] : { label: String(op), ports: [] });
export const WIN_AGG = ['max', 'mean', 'frac'];
export const WIN_SIGNALS = SIGNALS.filter((x) => x.name !== 'ctx' && x.name !== 'ctxAgeS'); // a window reads the seven one-second features only
export const MAX_WINDOWS = 4;
export const OP_NAMES = Object.keys(OPS);

/** The graph a freshly added Expression guard carries: one step `fast` = headSpeed > 1.5 (3 lowered nodes, cost 3). */
export const DEFAULT_GRAPH_JSON = '{"steps":[{"name":"fast","inputs":[{"name":"speed","sig":"headSpeed"}],"nodes":[{"id":"limit","op":"const","value":1.5},{"id":"over","op":"cmp","f":">"}],"wires":[["speed","over.a"],["limit","over.b"]],"out":"over"}],"out":"fast"}';
export const defaultGraph = () => JSON.parse(DEFAULT_GRAPH_JSON);
/** A fresh Value (expression): one step `cap` = clamp(2, 1, 4), a valid number for the default target (REST capPerMin). */
export const DEFAULT_VALUE_GRAPH_JSON = '{"steps":[{"name":"cap","inputs":[],"nodes":[{"id":"k","op":"const","value":2},{"id":"c","op":"clamp","lo":1,"hi":4}],"wires":[["k","c.a"]],"out":"c"}],"out":"cap"}';
export const defaultGraphFor = (type) => JSON.parse(type === 'expr.value' ? DEFAULT_VALUE_GRAPH_JSON : DEFAULT_GRAPH_JSON);
/** What the row clamps a value program's number to (the program carries no bound of its own). */
export const targetNote = (f) => (f ? 'Tunes ' + f + ', clamped to ' + (f === 'capPerMin' ? '1 to 4 captures a minute (rounded)' : '5 to 300 seconds') + '. An error or a warming window keeps the row value.' : '');

const clone = (g) => JSON.parse(JSON.stringify(g));
const fail = (error) => ({ error });
const stepOf = (g, name) => g.steps.find((s) => s.name === name);

/** raw object or JSON text → a graph with the right shape, or {error}. Only the shape: the compile says whether it is valid. */
export function parseGraph(raw) {
  let g = raw;
  if (typeof raw === 'string') { try { g = JSON.parse(raw); } catch (e) { return fail('Not valid JSON'); } }
  if (g === null || typeof g !== 'object' || Array.isArray(g) || !Array.isArray(g.steps) || typeof g.out !== 'string') return fail('A graph is {"steps": [...], "out": "<step name>"}');
  for (const s of g.steps) {
    if (s === null || typeof s !== 'object' || typeof s.name !== 'string' || !Array.isArray(s.inputs) || !Array.isArray(s.nodes) || !Array.isArray(s.wires)) return fail('Each step needs a name, inputs, nodes and wires');
  }
  return { graph: g };
}

const taken = (s) => new Set(s.inputs.map((i) => i.name).concat(s.nodes.map((n) => n.id)));
function unique(base, used) {
  let i = 1;
  let name = base;
  while (used.has(name)) { i++; name = base + '_' + i; }
  return name;
}

// ---- the outer level
/** Steps a step reads (its `step` inputs). */
const reads = (s) => s.inputs.filter((i) => own(i, 'step')).map((i) => i.step);

/** Would `from` feeding `to` (a step input of `to` reading step `from`) close a loop? True when `from` already depends on `to`, or from === to. */
export function stepLoop(g, to, from) {
  if (to === from) return true;
  const seen = new Set();
  const walk = (n) => {
    if (n === to) return true;
    if (seen.has(n)) return false;
    seen.add(n);
    const s = stepOf(g, n);
    return !!s && reads(s).some(walk);
  };
  return walk(from);
}

/** The box-to-box arrows: one per `step` input, {from, to, input}. These are the real connections. */
export function outerEdges(g) {
  const out = [];
  for (const s of g.steps) for (const i of s.inputs) if (own(i, 'step')) out.push({ from: i.step, to: s.name, input: i.name });
  return out;
}

export function addStep(g) {
  if (g.steps.length >= 6) return fail('At most 6 steps fit in one guard');
  const next = clone(g);
  const name = unique('step', new Set(next.steps.map((s) => s.name)));
  next.steps.push({ name, inputs: [], nodes: [{ id: 'k', op: 'const', value: true }], wires: [], out: 'k' });
  return { graph: next, name };
}

export function renameStep(g, from, to) {
  const t = String(to == null ? '' : to).trim();
  if (!NAME.test(t)) return fail('A step name is a-z, 0-9 and _, starting with a letter, up to 24 characters');
  if (t === from) return { graph: g };
  if (g.steps.some((s) => s.name === t)) return fail('There is already a step called ' + t);
  const next = clone(g);
  for (const s of next.steps) {
    if (s.name === from) s.name = t;
    for (const i of s.inputs) if (own(i, 'step') && i.step === from) i.step = t;
  }
  if (next.out === from) next.out = t;
  return { graph: next };
}

export function deleteStep(g, name) {
  if (g.out === name) return fail('This step is the guard output. Make another step the output first.');
  if (g.steps.length <= 1) return fail('A guard needs at least one step');
  const next = clone(g);
  next.steps = next.steps.filter((s) => s.name !== name);
  let cut = 0;
  for (const s of next.steps) {
    const gone = s.inputs.filter((i) => own(i, 'step') && i.step === name).map((i) => i.name);
    cut += gone.length;
    s.inputs = s.inputs.filter((i) => !gone.includes(i.name));
    s.wires = s.wires.filter((w) => !gone.includes(w[0]));
  }
  return { graph: next, note: cut ? 'Removed ' + cut + (cut === 1 ? ' input' : ' inputs') + ' that read ' + name : '' };
}

export function setGuardOut(g, name) {
  if (!stepOf(g, name)) return fail('No such step');
  const next = clone(g);
  next.out = name;
  return { graph: next };
}

/** source: {kind:'sig', ref} | {kind:'step', ref} | {kind:'const', value: number|boolean}. */
function sourceFields(source) {
  if (source.kind === 'sig') return SIGNALS.some((x) => x.name === source.ref) ? { sig: source.ref } : null;
  if (source.kind === 'step') return { step: source.ref };
  if (typeof source.value === 'boolean') return { const: source.value };
  const v = Number(source.value);
  return Number.isFinite(v) && Math.abs(v) <= MAX_CONST ? { const: v } : null;
}

export function addInput(g, stepName, source) {
  const s = stepOf(g, stepName);
  if (!s) return fail('No such step');
  if (source.kind === 'step' && (!stepOf(g, source.ref) || stepLoop(g, stepName, source.ref))) {
    return fail(source.ref === stepName ? 'A step cannot read itself' : 'Refused: ' + source.ref + ' already reads ' + stepName + ', so this would be a loop (EXPR_LOOP)');
  }
  const f = sourceFields(source);
  if (!f) return fail(source.kind === 'sig' ? 'Unknown signal' : 'A constant is a number within 1000000');
  const next = clone(g);
  const t = stepOf(next, stepName);
  const base = source.kind === 'step' ? source.ref : source.kind === 'sig' ? 'in' : 'k';
  t.inputs.push(Object.assign({ name: unique(base, taken(t)) }, f));
  return { graph: next };
}

/** Changes where an input comes from (the arrow follows). */
export function setInputSource(g, stepName, inputName, source) {
  const s = stepOf(g, stepName);
  if (!s || !s.inputs.some((i) => i.name === inputName)) return fail('No such input');
  if (source.kind === 'step' && (!stepOf(g, source.ref) || stepLoop(g, stepName, source.ref))) {
    return fail(source.ref === stepName ? 'A step cannot read itself' : 'Refused: ' + source.ref + ' already reads ' + stepName + ', so this would be a loop (EXPR_LOOP)');
  }
  const f = sourceFields(source);
  if (!f) return fail(source.kind === 'sig' ? 'Unknown signal' : 'A constant is a number within 1000000');
  const next = clone(g);
  const t = stepOf(next, stepName);
  t.inputs = t.inputs.map((i) => (i.name === inputName ? Object.assign({ name: i.name }, f) : i));
  return { graph: next };
}

export function removeInput(g, stepName, inputName) {
  const s = stepOf(g, stepName);
  if (!s || !s.inputs.some((i) => i.name === inputName)) return fail('No such input');
  const next = clone(g);
  const t = stepOf(next, stepName);
  t.inputs = t.inputs.filter((i) => i.name !== inputName);
  t.wires = t.wires.filter((w) => w[0] !== inputName);
  return { graph: next };
}

// ---- the inner level
const sourcesOf = (s, id) => s.wires.filter((w) => w[1].split('.')[0] === id).map((w) => w[0]);

/** Wiring `from` into node `to`: a loop when `from` already depends on `to` (or is it). */
export function nodeLoop(s, to, from) {
  if (from === to) return true;
  const seen = new Set();
  const walk = (n) => {
    if (n === to) return true;
    if (seen.has(n)) return false;
    seen.add(n);
    return sourcesOf(s, n).some(walk);
  };
  return walk(from);
}

const DEFAULT_NODE = {
  sig: () => ({ name: 'gyroMax' }), const: () => ({ value: 0 }), cmp: () => ({ f: '>' }), arith: () => ({ f: '+' }), in: () => ({ set: ['REST'] }),
  clamp: () => ({ lo: 0, hi: 1 }), fresh: () => ({ ms: 1000 }), win: () => ({ name: 'stepHz', agg: 'mean', sec: 10 }),
};

export function addNode(g, stepName, op) {
  if (!own(OPS, op)) return fail('Unknown node kind');
  if (!stepOf(g, stepName)) return fail('No such step');
  const next = clone(g);
  const s = stepOf(next, stepName);
  const id = unique(op === 'const' ? 'k' : op === 'in' ? 'is_in' : op, taken(s));
  s.nodes.push(Object.assign({ id, op }, own(DEFAULT_NODE, op) ? DEFAULT_NODE[op]() : {}));
  return { graph: next, id };
}

export function deleteNode(g, stepName, id) {
  const s = stepOf(g, stepName);
  if (!s || !s.nodes.some((n) => n.id === id)) return fail('No such node');
  const next = clone(g);
  const t = stepOf(next, stepName);
  t.nodes = t.nodes.filter((n) => n.id !== id);
  t.wires = t.wires.filter((w) => w[0] !== id && w[1].split('.')[0] !== id);
  if (t.out === id) t.out = '';
  return { graph: next, note: t.out === '' && s.out === id ? 'Pick a new step output' : '' };
}

export function renameNode(g, stepName, id, to) {
  const t = String(to == null ? '' : to).trim();
  const s = stepOf(g, stepName);
  if (!s || !s.nodes.some((n) => n.id === id)) return fail('No such node');
  if (!NAME.test(t)) return fail('A node name is a-z, 0-9 and _, starting with a letter, up to 24 characters');
  if (t === id) return { graph: g };
  if (taken(s).has(t)) return fail('There is already a node or input called ' + t);
  const next = clone(g);
  const u = stepOf(next, stepName);
  for (const n of u.nodes) if (n.id === id) n.id = t;
  u.wires = u.wires.map((w) => { const [n, p] = w[1].split('.'); return [w[0] === id ? t : w[0], (n === id ? t : n) + '.' + p]; });
  if (u.out === id) u.out = t;
  return { graph: next };
}

/** One node param from raw input: value (const: number or true/false), f, set (comma list or array of states), lo, hi, ms, name. */
export function setNodeParam(g, stepName, id, key, raw) {
  const s = stepOf(g, stepName);
  const n = s && s.nodes.find((x) => x.id === id);
  if (!n) return fail('No such node');
  const text = String(raw == null ? '' : raw).trim();
  const num = () => { const v = Number(text); return text !== '' && Number.isFinite(v) && Math.abs(v) <= MAX_CONST ? v : null; };
  let value;
  if (n.op === 'const' && key === 'value') {
    if (text === 'true' || text === 'false' || raw === true || raw === false) value = text === 'true' || raw === true;
    else { value = num(); if (value === null) return fail('Must be a number within 1000000, or true or false'); }
  } else if ((n.op === 'cmp' || n.op === 'arith') && key === 'f') {
    if (!(n.op === 'cmp' ? CMP : ARITH).includes(text)) return fail('Pick one of the listed operators');
    value = text;
  } else if (n.op === 'sig' && key === 'name') {
    if (!SIGNALS.some((x) => x.name === text)) return fail('Unknown signal');
    value = text;
  } else if (n.op === 'in' && key === 'set') {
    const list = Array.isArray(raw) ? raw.map(String) : text.split(',').map((x) => x.trim()).filter(Boolean);
    if (list.length === 0 || list.some((x) => !STATES.includes(x))) return fail('Pick at least one known state');
    value = STATES.filter((x) => list.includes(x));
  } else if (n.op === 'clamp' && (key === 'lo' || key === 'hi')) {
    value = num();
    if (value === null) return fail('Must be a number within 1000000');
    if ((key === 'lo' && value > n.hi) || (key === 'hi' && value < n.lo)) return fail('lo must not exceed hi');
  } else if (n.op === 'win' && (key === 'name' || key === 'agg')) {
    if (!(key === 'name' ? WIN_SIGNALS.map((x) => x.name) : WIN_AGG).includes(text)) return fail(key === 'name' ? 'A window reads one of the seven motion features' : 'Pick max, mean or frac');
    value = text;
  } else if (n.op === 'win' && key === 'sec') {
    value = Number(text);
    if (!/^\d+$/.test(text) || value < 1 || value > 300) return fail('Must be a whole number from 1 to 300 seconds');
  } else if (n.op === 'win' && key === 't') {
    value = num();
    if (value === null) return fail('Must be a number within 1000000');
  } else if (n.op === 'fresh' && key === 'ms') {
    value = Number(text);
    if (!/^\d+$/.test(text) || value < 1 || value > MAX_FRESH_MS) return fail('Must be a whole number from 1 to ' + MAX_FRESH_MS);
  } else return fail('That node has no ' + key);
  const next = clone(g);
  const nn = stepOf(next, stepName).nodes.find((x) => x.id === id);
  nn[key] = value;
  if (n.op === 'win' && key === 'agg') { if (value === 'frac' && !own(nn, 't')) nn.t = 0.5; else if (value !== 'frac') delete nn.t; } // only frac takes a threshold
  return { graph: next };
}

/** Wires `from` (a node id or an input name of this step) into `node.port`, replacing what fed that port. A loop is refused here. */
export function setWire(g, stepName, to, from) {
  const s = stepOf(g, stepName);
  const [id, port] = String(to).split('.');
  const target = s && s.nodes.find((n) => n.id === id);
  if (!target || !opOf(target.op).ports.includes(port)) return fail('No such port');
  if (!taken(s).has(from)) return fail('No such source');
  if (s.nodes.some((n) => n.id === from) && nodeLoop(s, id, from)) {
    return fail(from === id ? 'A node cannot feed itself (EXPR_LOOP)' : 'Refused: ' + from + ' already depends on ' + id + ', so this would be a loop (EXPR_LOOP)');
  }
  const next = clone(g);
  const t = stepOf(next, stepName);
  t.wires = t.wires.filter((w) => w[1] !== to);
  t.wires.push([from, to]);
  return { graph: next };
}

export function removeWire(g, stepName, to) {
  const s = stepOf(g, stepName);
  if (!s) return fail('No such step');
  const next = clone(g);
  const t = stepOf(next, stepName);
  t.wires = t.wires.filter((w) => w[1] !== to);
  return { graph: next };
}

export function setStepOut(g, stepName, id) {
  const s = stepOf(g, stepName);
  if (!s || !s.nodes.some((n) => n.id === id)) return fail('No such node');
  const next = clone(g);
  stepOf(next, stepName).out = id;
  return { graph: next };
}

export const wireTo = (s, id, port) => { const w = s.wires.find((x) => x[1] === id + '.' + port); return w ? w[0] : ''; };

/** One line for a node box. */
export function nodeLabel(n) {
  switch (n.op) {
    case 'sig': return 'signal ' + n.name;
    case 'const': return 'constant ' + n.value;
    case 'cmp': return 'compare ' + n.f;
    case 'arith': return 'arithmetic ' + n.f;
    case 'in': return 'is in ' + (n.set || []).join(', ');
    case 'clamp': return 'clamp ' + n.lo + '..' + n.hi;
    case 'fresh': return 'fresh ' + n.ms + ' ms';
    case 'win': return n.agg + ' ' + n.name + ' ' + n.sec + ' s' + (n.agg === 'frac' ? ' > ' + n.t : '');
    default: return opOf(n.op).label;
  }
}

export const inputLabel = (i) => (own(i, 'sig') ? i.sig : own(i, 'step') ? 'step ' + i.step : String(i.const));

// ---- layouts (pure; the view only draws them)
export const BOX = { w: 200, h: 112, gx: 96, gy: 44, pad: 20, top: 16 };

function depths(keys, depsOf) {
  const d = {};
  for (const k of keys) d[k] = 0;
  for (let round = 0; round < keys.length + 1; round++) {
    let moved = false;
    for (const k of keys) for (const p of depsOf(k)) if (own(d, p) && d[k] < d[p] + 1 && d[p] < keys.length) { d[k] = d[p] + 1; moved = true; }
    if (!moved) break;
  }
  return d;
}

/** The outer view: {boxes:[{name,x,y,layer,colour,isOut}], arrows:[{from,to,inputs,label,d,lx,ly}], width, height}; arrows are one per (from,to) pair. */
export function outerLayout(g) {
  const names = g.steps.map((s) => s.name);
  const dep = depths(names, (n) => reads(stepOf(g, n)));
  const rows = {};
  const boxes = names.slice().sort((a, b) => dep[a] - dep[b] || (a < b ? -1 : 1)).map((name) => {
    const layer = dep[name];
    const row = rows[layer] || 0;
    rows[layer] = row + 1;
    return { name, layer, row, x: BOX.pad + layer * (BOX.w + BOX.gx), y: BOX.pad + BOX.top + row * (BOX.h + BOX.gy), colour: names.indexOf(name) % 6, isOut: g.out === name };
  });
  const at = (n) => boxes.find((b) => b.name === n);
  const pairs = new Map();
  for (const e of outerEdges(g)) {
    const k = e.from + '>' + e.to;
    if (!pairs.has(k)) pairs.set(k, { from: e.from, to: e.to, inputs: [] });
    pairs.get(k).inputs.push(e.input);
  }
  const arrows = [];
  for (const p of pairs.values()) {
    const a = at(p.from);
    const b = at(p.to);
    if (!a || !b) continue;
    const sx = a.x + BOX.w; const sy = a.y + BOX.h / 2; const tx = b.x; const ty = b.y + BOX.h / 2;
    const bend = b.layer - a.layer > 1 ? BOX.h / 2 + BOX.gy : 0;
    const mx = (sx + tx) / 2;
    arrows.push({ from: p.from, to: p.to, inputs: p.inputs, label: p.from + ' → ' + p.to, d: 'M' + sx + ' ' + sy + ' C' + (sx + 40) + ' ' + (sy + bend) + ' ' + (tx - 40) + ' ' + (ty + bend) + ' ' + tx + ' ' + ty, lx: mx, ly: (sy + ty) / 2 + bend * 0.75 });
  }
  const maxLayer = boxes.reduce((m, b) => Math.max(m, b.layer), 0);
  const maxRow = boxes.reduce((m, b) => Math.max(m, b.row), 0);
  return { boxes, arrows, width: BOX.pad * 2 + (maxLayer + 1) * BOX.w + maxLayer * BOX.gx, height: BOX.pad * 2 + BOX.top + (maxRow + 1) * BOX.h + maxRow * BOX.gy + 30 };
}

export const NODE = { w: 150, h: 46, gx: 70, gy: 22, pad: 14 };

/** The inner view of one step: inputs (source boxes) and nodes, wires with their port. */
export function innerLayout(s) {
  const keys = s.inputs.map((i) => i.name).concat(s.nodes.map((n) => n.id));
  const dep = depths(keys, (k) => sourcesOf(s, k));
  const rows = {};
  const items = keys.map((key) => {
    const isInput = s.inputs.some((i) => i.name === key);
    const layer = isInput ? 0 : Math.max(1, dep[key]);
    const row = rows[layer] || 0;
    rows[layer] = row + 1;
    const node = s.nodes.find((n) => n.id === key);
    return { key, isInput, layer, row, x: NODE.pad + layer * (NODE.w + NODE.gx), y: NODE.pad + row * (NODE.h + NODE.gy), isOut: s.out === key, label: isInput ? inputLabel(s.inputs.find((i) => i.name === key)) : nodeLabel(node), op: isInput ? 'input' : node.op };
  });
  const at = (k) => items.find((i) => i.key === k);
  const wires = [];
  for (const w of s.wires) {
    const a = at(w[0]);
    const b = at(w[1].split('.')[0]);
    if (!a || !b) continue;
    const port = w[1].split('.')[1];
    const sx = a.x + NODE.w; const sy = a.y + NODE.h / 2; const tx = b.x; const ty = b.y + 10 + 'abc'.indexOf(port) * 12;
    wires.push({ from: w[0], to: w[1], port, d: 'M' + sx + ' ' + sy + ' C' + (sx + 35) + ' ' + sy + ' ' + (tx - 35) + ' ' + ty + ' ' + tx + ' ' + ty, lx: tx - 10, ly: ty - 3 });
  }
  const maxLayer = items.reduce((m, i) => Math.max(m, i.layer), 0);
  const maxRow = items.reduce((m, i) => Math.max(m, i.row), 0);
  return { items, wires, width: NODE.pad * 2 + (maxLayer + 1) * NODE.w + maxLayer * NODE.gx, height: NODE.pad * 2 + (maxRow + 1) * NODE.h + maxRow * NODE.gy };
}

// ---- compile result: diagnostics on steps and nodes, meters
/** Texts like `node <canvas id>: EXPR_X: step <name>: ...` → {general:[{code,text}], steps:{name:[{code,text,nodes:[ids]}]}}. */
export function attribute(g, texts) {
  const out = { general: [], steps: {} };
  for (const raw of texts || []) {
    const body = String(raw).replace(/^node [^:]+: /, '');
    const m = /^(EXPR_[A-Z_]+): ([\s\S]*)$/.exec(body);
    if (!m) { out.general.push({ code: '', text: body }); continue; }
    const code = m[1];
    const s = /^step (\S+?):/.exec(m[2]);
    let step = null;
    if (s) step = /^\d+$/.test(s[1]) ? (g.steps[Number(s[1])] || {}).name : (stepOf(g, s[1]) || {}).name;
    if (!step) { out.general.push({ code, text: m[2] }); continue; }
    const st = stepOf(g, step);
    const ids = new Set(st.nodes.map((n) => n.id).concat(st.inputs.map((i) => i.name)));
    const nodes = (m[2].match(/[a-z][a-z0-9_]*/g) || []).filter((t, i, a) => ids.has(t) && a.indexOf(t) === i);
    (out.steps[step] = out.steps[step] || []).push({ code, text: m[2].replace(/^step \S+?: /, ''), nodes });
  }
  return out;
}

const COST = { clamp: 2, choose: 2, fresh: 2 };
/** planJson (the whole compiled plan, as /api/plans/compile returns it) → {nodes: lowered total over all programs, byId: {canvasId: {nodes, cost}}}; unparsable gives zero. */
export function exprMeters(planJson) {
  const res = { nodes: 0, byId: {}, terms: 0, stateBytes: 0 };
  const seen = new Set();
  let plan;
  try { plan = JSON.parse(planJson); } catch (e) { return res; }
  for (const p of (plan && plan.config && plan.config.exprs) || []) { // CapturePlan: planJson = {planSchema, configHash, config: {..., exprs}, meta}
    const s = p.s || [];
    res.nodes += s.length;
    for (const e of s) if (e.op === 'win' && !seen.has([e.name, e.agg, e.sec, e.t].join())) { seen.add([e.name, e.agg, e.sec, e.t].join()); res.stateBytes += (e.agg === 'max' ? 8 : 4) * e.sec; }
    res.byId[p.id] = { nodes: s.length, cost: s.reduce((c, e) => c + (e.op === 'in' ? 1 + (e.set || []).length : own(COST, e.op) ? COST[e.op] : 1), 0) };
  }
  res.terms = seen.size;
  return res;
}

/** What the editor shows for one canvas node from the compile view (editorlogic.compileView): attributed problems and the meters, or a hint to compile. */
export function panelFor(view, nodeId, g) {
  if (!view || view.state !== 'done') return { compiled: false, stale: false, problems: attribute(g, []), warnings: attribute(g, []), meters: null };
  const mine = (l) => l.filter((p) => p.nodeId === nodeId).map((p) => p.text);
  const ex = view.exprs || { nodes: 0, byId: {} };
  const own1 = ex.byId[nodeId];
  return {
    compiled: true, stale: !!view.stale, problems: attribute(g, mine(view.problems)), warnings: attribute(g, mine(view.warnings)),
    meters: { nodes: ex.nodes, maxNodes: MAX_NODES, cost: own1 ? own1.cost : 0, maxCost: MAX_COST, bytes: view.planBytes, maxBytes: MAX_BYTES, terms: ex.terms || 0, maxTerms: MAX_WINDOWS, stateBytes: ex.stateBytes || 0 },
  };
}
export const metersText = (m) => 'nodes ' + m.nodes + '/' + m.maxNodes + ' · cost ' + m.cost + '/' + m.maxCost + ' · ' + (m.bytes == null ? '?' : m.bytes) + ' of ' + m.maxBytes + ' bytes' + (m.terms ? ' · windows ' + m.terms + '/' + m.maxTerms + ' · window state ' + m.stateBytes + ' bytes (counted in the ' + m.maxBytes + ')' : '');

// ---- try-it (the phone evaluates: POST /api/expr/try runs the glasses' own runner)
const SLIDER = { aboveOnFrac: [0, 1, 0.01], accVar: [0, 50, 0.5], stepHz: [0, 4, 0.05], rotRad: [0, 7, 0.1] };
export const sliderOf = (n) => SLIDER[n] || [0, 10, 0.1];
/** The motion features the program reads (inputs, signal nodes, window nodes), headSpeed folded into gyroMax; ctx and ctxAgeS are not sliders. */
export function signalsOf(g) {
  const out = [];
  const add = (n) => { const c = n === 'headSpeed' ? 'gyroMax' : n; if (SIGNALS.some((x) => x.name === c) && c !== 'ctx' && c !== 'ctxAgeS' && !out.includes(c)) out.push(c); };
  for (const s of g.steps) { for (const i of s.inputs) if (own(i, 'sig')) add(i.sig); for (const n of s.nodes) if (n.op === 'sig' || n.op === 'win') add(n.name); }
  return out;
}
export const tryBody = (g, values, target) => {
  const signals = {};
  for (const n of signalsOf(g)) signals[n] = own(values, n) ? values[n] : sliderOf(n)[0];
  return Object.assign({ graph: g, signals }, target ? { role: 'value', row: target.row, field: target.field } : {});
};
const cellText = (c) => (c ? (c.state === 'NUM' ? String(Math.round(c.value * 1000) / 1000) : c.state === 'BOOL' || c.state === 'ENUM' ? String(c.value) : c.state) : '');
/** The reply of the try-it route as lines: a problem list, or one line per step and the result. */
export function tryLines(r) {
  if (!r || r.ok !== true) return ((r && r.problems) || ['The program does not compile']).map(String);
  const lines = r.steps.map((x) => x.name + ' = ' + cellText(x.result));
  lines.push('result: ' + cellText(r.output) + (r.verdict ? ' (' + r.verdict + ' the picture)' : '') + (r.applied !== undefined ? ' (the row gets ' + r.applied + ')' : ''));
  return lines;
}
