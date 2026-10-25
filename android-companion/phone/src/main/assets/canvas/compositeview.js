// The inner flow of a composite (black-box) node, in the page's React-free DOM style (dom.js: createElement and textContent only).
// A composite's registry entry carries `inner` {nodes, wires, exposed}; it is a DESCRIPTION. The values live in the outer node's params:
// each exposed param is an input INSIDE the box that owns it (paramform.js builds and coerces the field), the other boxes are read-only.
// Collapsed: just the Expand button. Expanded: boxes and wires drawn like the boxed expression editor (exprview.js classes).
import { h, s, clear } from './dom.js';
import { fields } from './paramform.js';

export const BOX = { w: 176, head: 40, field: 58, cell: 34, min: 62, gapX: 56, gapY: 22, pad: 14, noteLine: 18 };

// an exposed entry names a param, or ONE cell of a table param (row, col): its key is 'param.row.col' and its control is a compact number
const ekey = (e) => (e.col ? e.param + '.' + e.row + '.' + e.col : e.param);
const layoutDepth = (inner) => {
  const depth = new Map(inner.nodes.map((n) => [n.id, 0]));
  for (let pass = 0; pass < inner.nodes.length; pass++) {
    for (const w of inner.wires) if (depth.has(w.from) && depth.has(w.to) && depth.get(w.to) < depth.get(w.from) + 1) depth.set(w.to, depth.get(w.from) + 1);
  }
  return depth;
};

/** Pure geometry: boxes in columns by wire depth (a box is as tall as its exposed fields need), cubic wires from a right edge to a left edge. */
export function layout(inner) {
  const depth = layoutDepth(inner);
  const owned = (id) => (inner.exposed || []).filter((e) => e.node === id).map(ekey);
  const cols = [];
  inner.nodes.forEach((n, i) => { const d = depth.get(n.id); (cols[d] = cols[d] || []).push({ n, i }); });
  const boxes = [];
  let height = 0;
  cols.forEach((col, c) => {
    let y = BOX.pad;
    for (const { n, i } of col || []) {
      const params = owned(n.id);
      const fy = [];
      let acc = 0;
      for (const k of params) { fy.push(acc); acc += k.indexOf('.') < 0 ? BOX.field : BOX.cell; }
      const hgt = Math.max(BOX.min, BOX.head + acc + 8);
      boxes.push({ id: n.id, label: n.label, kind: n.kind, params, fy, x: BOX.pad + c * (BOX.w + BOX.gapX), y, w: BOX.w, h: hgt, colour: i % 6 });
      y += hgt + BOX.gapY;
    }
    height = Math.max(height, y);
  });
  const by = new Map(boxes.map((b) => [b.id, b]));
  const wires = (inner.wires || []).filter((w) => by.has(w.from) && by.has(w.to)).map((w) => {
    const a = by.get(w.from), b = by.get(w.to);
    const x1 = a.x + a.w, y1 = a.y + a.h / 2, x2 = b.x, y2 = b.y + b.h / 2, mx = (x1 + x2) / 2;
    return { from: w.from, to: w.to, port: w.port || '', d: 'M' + x1 + ' ' + y1 + ' C' + mx + ' ' + y1 + ' ' + mx + ' ' + y2 + ' ' + x2 + ' ' + y2, lx: x2 - 6, ly: y2 - 6 };
  });
  const notes = (inner.note || []).length;
  return { boxes, wires, notesY: height + BOX.pad, width: BOX.pad * 2 + cols.length * BOX.w + Math.max(0, cols.length - 1) * BOX.gapX, height: height + BOX.pad + notes * BOX.noteLine };
}

const hint = (d) => (d && d.default !== undefined ? ' (default ' + d.default + ')' : '');

/**
 * opts: {title, inner, descriptors (the node type's params), params (the node's current params), readOnly,
 *        onParam(name, raw) -> error text | undefined (the host coerces and stores), onCell(param, row, col, raw) likewise for a table cell, onToggle(expanded)}
 * → {toggle(), expand(), collapse(), update({params}), state(), unmount()}. The expanded flag is view-only and not saved.
 */
export function mountComposite(el, opts) {
  const st = { expanded: false, params: opts.params || {}, errs: {} };
  const inner = opts.inner;
  const ro = () => opts.readOnly === true;
  const desc = (name) => (opts.descriptors || []).find((d) => d.name === name);

  const set = (v) => { st.expanded = v; if (opts.onToggle) opts.onToggle(v); render(); };

  function control(f) {
    const label = f.label + (f.cell ? (f.hint === undefined ? '' : ' (default ' + f.hint + ')') : hint(desc(f.name)));
    const change = (e) => {
      const raw = f.control === 'checkbox' ? e.target.checked : e.target.value;
      const err = f.cell ? (opts.onCell ? opts.onCell(f.cell.param, f.cell.row, f.cell.col, raw) : undefined) : opts.onParam ? opts.onParam(f.name, raw) : undefined;
      if (err) st.errs[f.name] = err; else delete st.errs[f.name];
      render();
    };
    let input;
    if (f.control === 'select') {
      input = h('select', { 'aria-label': label, disabled: ro() ? true : null, onchange: change },
        ...f.options.map((o) => h('option', { value: o.value, selected: o.value === f.value ? true : null }, o.label)));
      input.value = f.value; // after the options exist, so the browser picks the matching one
    } else if (f.control === 'checkbox') {
      input = h('input', { type: 'checkbox', 'aria-label': label, checked: f.value === true ? true : null, disabled: ro() ? true : null, onchange: change });
    } else {
      input = h('input', { type: f.control === 'number' ? 'number' : 'text', 'aria-label': label, min: f.min, max: f.max, step: f.step, value: String(f.value), placeholder: f.cell ? f.ph : null, disabled: ro() ? true : null, onchange: change });
    }
    return h('div', { class: 'comp-field' + (f.cell ? ' cell' : '') }, h('span', { class: 'comp-label', title: label }, f.label), input);
  }

  // one compact field per exposed table cell: the cell's own value, else blank with the default (or 'unchanged' / 'inherit') as the placeholder
  const cells = () => (inner.exposed || []).filter((e) => e.col).map((e) => {
    const c = (((desc(e.param) || {}).table || {}).cols || []).find((x) => x.name === e.col) || {};
    const cur = ((st.params[e.param] || {})[e.row] || {})[e.col];
    return { name: ekey(e), cell: { param: e.param, row: e.row, col: e.col }, control: 'number', label: String(c.label || e.col).replace('timer photo gap ', 'gap '), min: c.allowZero ? 0 : c.min, max: c.max, step: 1,
      value: cur === undefined || cur === null ? '' : cur, hint: e.default, ph: e.default !== undefined ? String(e.default) : c.allowZero ? 'inherit' : 'unchanged' };
  });

  function svg() {
    const L = layout(inner);
    const all = fields((opts.descriptors || []).filter((d) => (inner.exposed || []).some((e) => e.param === d.name && !e.col)), st.params, {}).concat(cells());
    const root = s('svg', { class: 'expr-svg comp-svg', viewBox: '0 0 ' + L.width + ' ' + L.height, width: L.width, height: L.height, role: 'group', 'aria-label': 'Inner flow of ' + (opts.title || 'this node') });
    root.appendChild(s('defs', null, s('marker', { id: 'comp-wire', viewBox: '0 0 10 10', refX: 9, refY: 5, markerWidth: 7, markerHeight: 7, orient: 'auto' }, s('path', { d: 'M0 0L10 5L0 10z', class: 'expr-wirehead' }))));
    for (const w of L.wires) {
      root.appendChild(s('g', { class: 'expr-wire comp-wire' }, s('title', null, w.from + ' to ' + w.to), s('path', { d: w.d, class: 'expr-wire-line', fill: 'none', 'marker-end': 'url(#comp-wire)' }),
        w.port ? s('text', { x: w.lx, y: w.ly, 'text-anchor': 'end', class: 'expr-port' }, w.port) : null));
    }
    for (const b of L.boxes) {
      const pw = 24 + b.label.length * 7;
      const mine = b.params.map((p) => all.find((f) => f.name === p)).filter(Boolean);
      const g = s('g', { class: 'expr-box comp-box xs' + b.colour + (mine.length ? ' has-params' : ' read-only'), 'data-box': b.id, role: 'group', 'aria-label': b.label + (mine.length ? ', editable: ' + mine.map((f) => f.label).join(', ') : ', read only') },
        s('rect', { x: b.x, y: b.y, width: b.w, height: b.h, rx: 12, class: 'expr-box-body' }),
        s('rect', { x: b.x + 10, y: b.y - 12, width: Math.min(pw, b.w - 20), height: 24, rx: 12, class: 'expr-pill' }),
        s('text', { x: b.x + 10 + Math.min(pw, b.w - 20) / 2, y: b.y + 5, 'text-anchor': 'middle', class: 'expr-pill-text' }, b.label));
      mine.forEach((f, i) => {
        const fo = s('foreignObject', { x: b.x + 8, y: b.y + BOX.head - 6 + b.fy[i], width: b.w - 16, height: (f.cell ? BOX.cell : BOX.field) - 4, class: 'comp-fo' });
        fo.appendChild(control(f));
        g.appendChild(fo);
      });
      root.appendChild(g);
    }
    (inner.note || []).forEach((t, i) => root.appendChild(s('text', { x: BOX.pad, y: L.notesY + (i + 1) * BOX.noteLine - 4, class: 'comp-note' }, t)));
    return h('div', { class: 'expr-scroll comp-scroll' }, root);
  }

  function render() {
    clear(el);
    const errs = Object.keys(st.errs).map((k) => h('li', null, st.errs[k]));
    el.appendChild(h('div', { class: 'comp' + (st.expanded ? ' open' : '') },
      h('button', { type: 'button', class: 'btn comp-toggle', 'aria-expanded': st.expanded ? 'true' : 'false', 'aria-label': (st.expanded ? 'Collapse ' : 'Expand ') + (opts.title || 'node'), onclick: () => set(!st.expanded) },
        st.expanded ? 'Collapse' : 'Expand'),
      st.expanded ? svg() : null,
      st.expanded && errs.length ? h('ul', { class: 'problems comp-errs', role: 'alert' }, ...errs) : null));
  }
  render();

  return {
    toggle: () => set(!st.expanded),
    expand: () => { if (!st.expanded) set(true); },
    collapse: () => { if (st.expanded) set(false); },
    update(next) {
      if (next && next.params && JSON.stringify(next.params) !== JSON.stringify(st.params)) { st.params = next.params; st.errs = {}; }
      render();
    },
    state: () => ({ expanded: st.expanded, errs: Object.assign({}, st.errs) }),
    unmount() { clear(el); },
  };
}
