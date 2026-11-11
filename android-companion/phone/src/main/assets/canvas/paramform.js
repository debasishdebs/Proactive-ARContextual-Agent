// The inspector's field model: node-type param descriptors → form fields, and raw input → coerced values. Pure.
// No JSON Schema library: a schema is only checked to be valid JSON here; the server is the authority.
import { parseGraph, defaultGraph, own } from './exprgraph.js';

const ID = /^[a-z][a-z0-9_]{0,31}$/;

/** What the capture-rate table says about its gaps (S84): they space reserved timer photo attempts only. */
export const TABLE_HINT = 'A timer photo gap spaces reserved timer photo attempts; glance captures stay immediate. A blank gap inherits the settings profile; a blank cell is unchanged.';

const label = (name) => {
  const t = String(name).replace(/_/g, ' ');
  return t.charAt(0).toUpperCase() + t.slice(1);
};

const options = (list) => (Array.isArray(list) ? list : []).map((o) => (typeof o === 'string' ? { value: o, label: o } : { value: String(o.value), label: String(o.label == null ? o.value : o.label) }));

const BOOL_OPTIONS = [{ value: '', label: 'unchanged' }, { value: 'true', label: 'on' }, { value: 'false', label: 'off' }];

/** A table column's control and what a blank means: blankable = unchanged, allowZero = inherit; bool columns are a three-way select. */
const colControl = (c) => (c.kind === 'bool' ? 'select' : 'number');
const colPlaceholder = (c) => (c.allowZero ? 'inherit' : c.blankable || c.kind === 'bool' ? 'unchanged' : '');

function tableModel(spec, cur) {
  const t = spec || { rows: [], cols: [] };
  const cols = (t.cols || []).map((c) => ({
    name: c.name, label: c.label || label(c.name), kind: c.kind, control: colControl(c), min: c.min, max: c.max,
    blankable: c.blankable === true, allowZero: c.allowZero === true, group: c.group || '', placeholder: colPlaceholder(c),
  }));
  // adjacent columns with the same group share one heading
  const groups = [];
  for (const c of cols) {
    if (!c.group) continue;
    const last = groups[groups.length - 1];
    if (last && last.label === c.group) last.span++; else groups.push({ label: c.group, span: 1 });
  }
  const rows = (t.rows || []).map((name) => {
    const row = own(cur, name) && cur[name] && typeof cur[name] === 'object' ? cur[name] : {};
    return {
      name,
      cells: cols.map((c) => {
        const v = own(row, c.name) ? row[c.name] : undefined;
        const cell = { col: c.name, control: c.control, value: v === undefined || v === null ? '' : String(v), placeholder: c.placeholder };
        if (c.kind === 'bool') cell.options = BOOL_OPTIONS;
        return cell;
      }),
    };
  });
  return { cols, groups, rows, hint: TABLE_HINT };
}

/**
 * descriptors from /api/node-types params: {name, kind: int|number|enum|bool|text|prompt|role|schema|labels|table, label?, min?, max?, step?, maxLen?, sweep?, options?, table?, default?}
 * → [{name, kind, control: number|checkbox|text|textarea|select|labels|table, label, min?, max?, step?, maxLen?, sweep, options?, table?, value, current}]
 * value is the current param, or its default; ctx = {roles, prompts} supplies the select options.
 */
export function fields(descriptors, params, ctx) {
  const ps = params || {};
  return (descriptors || []).map((d) => {
    const cur = own(ps, d.name) ? ps[d.name] : undefined;
    const f = { name: d.name, kind: d.kind, label: d.label || label(d.name), current: cur, sweep: d.sweep === true };
    switch (d.kind) {
      case 'int':
      case 'number':
        f.control = 'number';
        if (d.min !== undefined) f.min = d.min;
        if (d.max !== undefined) f.max = d.max;
        if (d.step !== undefined) f.step = d.step;
        f.value = cur !== undefined ? cur : d.default !== undefined ? d.default : d.min !== undefined ? d.min : 0;
        break;
      case 'enum':
        f.control = 'select';
        f.options = options(d.options);
        f.value = cur !== undefined ? String(cur) : d.default !== undefined ? String(d.default) : '';
        break;
      case 'table':
        f.control = 'table';
        f.table = tableModel(d.table, cur);
        f.value = cur;
        break;
      case 'graph': // the expr.guard graph: the boxed editor (exprview.js) edits it; the value is the graph object itself
        f.control = 'graph';
        f.value = cur !== undefined ? cur : defaultGraph();
        break;
      case 'bool':
        f.control = 'checkbox';
        f.value = cur !== undefined ? cur === true : d.default === true;
        break;
      case 'prompt':
        f.control = 'select';
        f.options = options(ctx && ctx.prompts);
        f.value = cur && typeof cur === 'object' && cur.source ? String(cur.source) : 'live';
        break;
      case 'role':
        f.control = 'select';
        f.options = options(ctx && ctx.roles);
        f.value = cur !== undefined ? String(cur) : '';
        break;
      case 'schema':
        f.control = 'textarea';
        f.value = cur === undefined ? '' : typeof cur === 'string' ? cur : JSON.stringify(cur);
        break;
      case 'labels':
        f.control = 'labels';
        f.value = Array.isArray(cur) ? cur.join(', ') : '';
        break;
      default: // text; a kind this page does not know shows its value as read-only JSON and refuses edits (the server stays the authority)
        if (d.kind !== 'text' && d.kind !== undefined) {
          f.unknown = true;
          f.control = 'textarea';
          f.readOnly = true;
          f.value = cur === undefined ? '' : typeof cur === 'string' ? cur : JSON.stringify(cur);
          break;
        }
        f.control = d.name === 'template' || d.name === 'source' || d.name === 'outSchema' ? 'textarea' : 'text'; // source and outSchema: the Python node's fields
        if (d.maxLen !== undefined) f.maxLen = d.maxLen;
        f.value = cur !== undefined ? String(cur) : d.default !== undefined ? String(d.default) : '';
    }
    return f;
  });
}

/** A schema is JSON text for an object; empty clears it ({value: undefined}). */
function schemaText(raw) {
  const s = String(raw == null ? '' : raw);
  if (s.trim() === '') return { value: undefined };
  let parsed;
  try { parsed = JSON.parse(s); } catch (e) { return { error: 'Must be valid JSON' }; }
  if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) return { error: 'Must be a JSON object' };
  return { value: s };
}

const rangeText = (f) => (f.min !== undefined && f.max !== undefined ? 'Must be between ' + f.min + ' and ' + f.max
  : f.min !== undefined ? 'Must be at least ' + f.min : 'Must be at most ' + f.max);

/** One table cell: raw text → {value: the whole new table value (undefined when it ends up empty)} | {error}. Never mutates f. */
export function coerceCell(f, rowName, colName, raw) {
  const t = f.table;
  const row = t && t.rows.find((r) => r.name === rowName);
  const col = t && t.cols.find((c) => c.name === colName);
  if (!row) return { error: 'No such row' };
  if (!col) return { error: 'No such column' };
  const text = String(raw == null ? '' : raw).trim();
  let value;
  if (text === '') {
    if (!(col.blankable || col.allowZero || col.kind === 'bool')) return { error: 'Must be a whole number' };
    value = undefined; // blank = inherit (gap) or unchanged
  } else if (col.kind === 'bool') {
    if (text !== 'true' && text !== 'false') return { error: 'Pick one of the listed options' };
    value = text === 'true';
  } else {
    if (!/^-?\d+$/.test(text)) return { error: 'Must be a whole number' };
    value = Number(text);
    const inRange = (col.min === undefined || value >= col.min) && (col.max === undefined || value <= col.max);
    if (!(inRange || (col.allowZero && value === 0))) {
      return { error: col.allowZero ? 'Must be 0 (inherit) or between ' + col.min + ' and ' + col.max : rangeText(col) };
    }
  }
  const out = {};
  const cur = f.current && typeof f.current === 'object' ? f.current : {};
  for (const k of Object.keys(cur)) if (cur[k] && typeof cur[k] === 'object') out[k] = Object.assign({}, cur[k]);
  const r = own(out, rowName) ? out[rowName] : {};
  if (value === undefined) delete r[colName]; else r[colName] = value;
  if (Object.keys(r).length === 0) delete out[rowName]; else out[rowName] = r;
  return { value: Object.keys(out).length === 0 ? undefined : out };
}

/** raw input → {value} | {error}. */
export function coerce(f, raw) {
  switch (f.kind) {
    case 'number': {
      const s = typeof raw === 'number' ? String(raw) : String(raw == null ? '' : raw).trim();
      const n = Number(s);
      if (s === '' || !Number.isFinite(n)) return { error: 'Must be a number' };
      if ((f.min !== undefined && n < f.min) || (f.max !== undefined && n > f.max)) return { error: rangeText(f) };
      return { value: n };
    }
    case 'enum': {
      const v = String(raw);
      if (!f.options.some((o) => o.value === v)) return { error: 'Pick one of the listed options' };
      return { value: v };
    }
    case 'table': return { error: 'Edit a cell' };
    case 'graph': { const r = parseGraph(raw); return r.error ? { error: r.error } : { value: r.graph }; }
    case 'int': {
      const s = typeof raw === 'number' ? String(raw) : String(raw == null ? '' : raw).trim();
      if (!/^-?\d+$/.test(s)) return { error: 'Must be a whole number' };
      const n = Number(s);
      if ((f.min !== undefined && n < f.min) || (f.max !== undefined && n > f.max)) return { error: rangeText(f) };
      return { value: n };
    }
    case 'bool':
      return { value: raw === true || raw === 'true' };
    case 'prompt': {
      const src = String(raw);
      if (!f.options.some((o) => o.value === src)) return { error: 'Pick one of the listed options' };
      const base = f.current && typeof f.current === 'object' ? Object.assign({}, f.current) : {};
      base.source = src;
      if (src === 'draft' && typeof base.template !== 'string') base.template = '';
      if (src === 'live') { delete base.template; delete base.schema; }
      return { value: base };
    }
    case 'role': {
      const v = String(raw);
      if (!f.options.some((o) => o.value === v)) return { error: 'Pick one of the listed options' };
      return { value: v };
    }
    case 'schema': return schemaText(raw);
    case 'labels': {
      const items = String(raw == null ? '' : raw).split(',').map((x) => x.trim()).filter((x) => x.length > 0);
      if (items.length === 0) return { error: 'Add at least one label' };
      if (items.length > 12) return { error: 'At most 12 labels' };
      if (new Set(items).size !== items.length) return { error: 'Labels must be unique' };
      if (items.some((x) => !ID.test(x) || x === 'other')) return { error: 'Labels use a-z, 0-9 and _, and "other" is reserved' };
      return { value: items };
    }
    default: {
      if (f.unknown) return { error: "This parameter can't be edited here" };
      const v = String(raw == null ? '' : raw);
      if (f.maxLen !== undefined && v.length > f.maxLen) return { error: 'At most ' + f.maxLen + ' characters' };
      return { value: v };
    }
  }
}

const text = (v) => (v == null ? '' : typeof v === 'string' ? v : JSON.stringify(v));

/**
 * The inline parts of a prompt param: a draft prompt carries its own template and schema (editable); a live one shows
 * the active prompt's text (read-only, `live` = {template, schema}); no live text known: no parts.
 */
export function promptParts(f, live) {
  const draft = f.value === 'draft';
  const src = draft ? (f.current && typeof f.current === 'object' ? f.current : {}) : live;
  if (!src) return [];
  return [
    { part: 'template', label: 'Template', control: 'textarea', value: text(src.template), readOnly: !draft },
    { part: 'schema', label: 'Schema (JSON)', control: 'textarea', value: text(src.schema), readOnly: !draft },
  ];
}

/** raw text for one inline part → {value: the whole new prompt object} | {error}. Never mutates f. */
export function coercePromptPart(f, part, raw) {
  if (f.value !== 'draft') return { error: 'Switch to Draft to edit the prompt' };
  const base = f.current && typeof f.current === 'object' ? Object.assign({}, f.current) : { source: 'draft' };
  base.source = 'draft';
  if (part === 'template') { base.template = String(raw == null ? '' : raw); return { value: base }; }
  if (part === 'schema') {
    const r = schemaText(raw);
    if (r.error) return r;
    if (r.value === undefined) delete base.schema; else base.schema = r.value;
    return { value: base };
  }
  return { error: 'Unknown prompt field' };
}
