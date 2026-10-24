// The boxed expression editor for the `expr.guard` graph param (slice 3c X5), in the page's own React-free DOM style (dom.js: createElement and
// textContent only, no HTML strings). OUTER level: steps as labelled boxes, box-to-box arrows derived from step inputs. INNER level: open a step
// (double click, Enter, or Edit step) to edit its catalogue nodes and wires; Back returns. All edits are exprgraph.js operations, so a loop or an
// unknown name is refused before it reaches the draft. Wiring is by pickers (works with touch, keyboard and screen readers), the arrows are drawn.
import { h, s, clear, btn } from './dom.js';
import * as X from './exprgraph.js';

const options = (list, current) => list.map((o) => h('option', { value: o.value, selected: o.value === current ? true : null }, o.label));
const select = (label, list, current, onchange, disabled) => h('select', { 'aria-label': label, disabled: disabled ? true : null, onchange: (e) => onchange(e.target.value) }, ...options(list, current));
const field = (label, control) => h('label', { class: 'field' }, h('span', null, label), control);
const textBox = (label, value, onchange, disabled, type) => field(label, h('input', { type: type || 'text', value: String(value), disabled: disabled ? true : null, onchange: (e) => onchange(e.target.value) }));

/**
 * opts: {graph, readOnly, panel(graph) -> exprgraph.panelFor result | null, onChange(graph)} → {update({graph?}), graph(), unmount()}.
 * The editor keeps its own copy of the graph and reports every accepted edit through onChange.
 */
export function mountExprEditor(el, opts) {
  const parsed = X.parseGraph(opts.graph == null ? X.defaultGraph() : opts.graph);
  const st = { graph: parsed.graph || X.defaultGraph(), view: 'outer', step: '', sel: '', msg: parsed.error || '', bad: !!parsed.error, json: false, jsonText: '', tv: {}, tryOut: null };
  const ro = () => opts.readOnly === true;
  let panel = null;
  const refreshPanel = () => { if (opts.panel) panel = opts.panel(st.graph); };

  const say = (m, bad) => { st.msg = m || ''; st.bad = !!bad; };
  const commit = (r) => {
    if (r.error) say(r.error, true);
    else { st.graph = r.graph; say(r.note || '', false); if (opts.onChange) opts.onChange(r.graph); }
    render();
    return r;
  };
  const stepNow = () => st.graph.steps.find((x) => x.name === st.step);
  const open = (name) => { st.view = 'inner'; st.step = name; st.sel = ''; say('', false); render(); };
  const back = () => { st.view = 'outer'; st.sel = st.step; say('', false); render(); };

  const problemsFor = (name) => (panel && panel.problems.steps[name]) || [];
  const warningsFor = (name) => (panel && panel.warnings.steps[name]) || [];

  const metersView = () => (panel && panel.compiled && panel.meters
    ? h('p', { class: 'muted expr-meters' }, X.metersText(panel.meters) + (panel.stale ? ' (the draft changed since this compile)' : ''))
    : h('p', { class: 'muted expr-meters' }, 'Compile to see the node count, cost and size.'));

  const diagList = (items, cls) => (items.length ? h('ul', { class: cls }, ...items.map((p) => h('li', null, p.code ? p.code + ': ' + p.text : p.text))) : null);

  const messages = () => h('div', null,
    st.msg ? h('p', { class: st.bad ? 'err' : 'muted', role: st.bad ? 'alert' : 'status' }, st.msg) : null,
    metersView(),
    panel ? diagList(panel.problems.general, 'problems expr-general') : null,
    panel ? diagList(panel.warnings.general, 'warnings expr-general') : null);

  // ---- outer
  function outerSvg() {
    const L = X.outerLayout(st.graph);
    const svg = s('svg', { class: 'expr-svg', viewBox: '0 0 ' + L.width + ' ' + L.height, width: L.width, height: L.height, role: 'group', 'aria-label': 'Steps of the expression guard' });
    svg.appendChild(s('defs', null, s('marker', { id: 'expr-arrow', viewBox: '0 0 10 10', refX: 9, refY: 5, markerWidth: 8, markerHeight: 8, orient: 'auto' }, s('path', { d: 'M0 0L10 5L0 10z', class: 'expr-arrowhead' }))));
    for (const a of L.arrows) {
      const w = 12 + a.label.length * 7;
      svg.appendChild(s('g', { class: 'expr-arrow' },
        s('title', null, a.label + ' (' + a.inputs.join(', ') + ')'),
        s('path', { d: a.d, class: 'expr-arrow-line', 'marker-end': 'url(#expr-arrow)', fill: 'none' }),
        s('rect', { x: a.lx - w / 2, y: a.ly - 11, width: w, height: 22, rx: 11, class: 'expr-pill-arrow' }),
        s('text', { x: a.lx, y: a.ly + 4, 'text-anchor': 'middle', class: 'expr-pill-text' }, a.label)));
    }
    for (const b of L.boxes) {
      const step = st.graph.steps.find((x) => x.name === b.name);
      const probs = problemsFor(b.name);
      const pw = 24 + (b.name.length + 6) * 7;
      const lines = step.inputs.map(X.inputLabel).slice(0, 3);
      svg.appendChild(s('g', {
        class: 'expr-box xs' + b.colour + (st.sel === b.name ? ' selected' : '') + (probs.length ? ' has-problem' : ''), 'data-step': b.name, tabindex: 0, role: 'button',
        'aria-label': 'Step ' + b.name + (probs.length ? ', ' + probs.length + ' problems' : '') + '. Enter opens it.',
        onclick: () => { st.sel = b.name; render(); }, ondblclick: () => open(b.name),
        onkeydown: (e) => { if (e.key === 'Enter') open(b.name); else if (e.key === ' ') { st.sel = b.name; render(); } },
      },
      s('rect', { x: b.x, y: b.y, width: X.BOX.w, height: X.BOX.h, rx: 12, class: 'expr-box-body' }),
      s('rect', { x: b.x + 14, y: b.y - 12, width: pw, height: 24, rx: 12, class: 'expr-pill' }),
      s('text', { x: b.x + 14 + pw / 2, y: b.y + 5, 'text-anchor': 'middle', class: 'expr-pill-text' }, 'step: ' + b.name),
      ...lines.map((t, i) => s('text', { x: b.x + 14, y: b.y + 36 + i * 18, class: 'expr-box-text' }, 'in: ' + t)),
      s('text', { x: b.x + 14, y: b.y + X.BOX.h - 12, class: 'expr-box-text' }, step.nodes.length + (step.nodes.length === 1 ? ' node' : ' nodes') + (b.isOut ? ' · guard output' : '') + (probs.length ? ' · ' + probs.length + ' problem' + (probs.length === 1 ? '' : 's') : ''))));
    }
    return h('div', { class: 'expr-scroll' }, svg);
  }

  function sourceKind(i) { return Object.prototype.hasOwnProperty.call(i, 'sig') ? 'sig' : Object.prototype.hasOwnProperty.call(i, 'step') ? 'step' : 'const'; }

  function inputRow(step, i) {
    const kind = sourceKind(i);
    const others = st.graph.steps.filter((x) => x.name !== step.name).map((x) => ({ value: x.name, label: x.name }));
    const swap = (k) => {
      const src = k === 'sig' ? { kind: 'sig', ref: 'gyroMax' } : k === 'step' ? { kind: 'step', ref: others.length ? others[0].value : '' } : { kind: 'const', value: 0 };
      commit(X.setInputSource(st.graph, step.name, i.name, src));
    };
    let value;
    if (kind === 'sig') value = select('Signal for ' + i.name, X.SIGNALS.map((x) => ({ value: x.name, label: x.name + ' (' + x.note + ')' })), i.sig, (v) => commit(X.setInputSource(st.graph, step.name, i.name, { kind: 'sig', ref: v })), ro());
    else if (kind === 'step') value = select('Step for ' + i.name, others, i.step, (v) => commit(X.setInputSource(st.graph, step.name, i.name, { kind: 'step', ref: v })), ro());
    else value = h('input', { type: 'text', 'aria-label': 'Constant for ' + i.name, value: String(i.const), disabled: ro() ? true : null, onchange: (e) => {
      const t = e.target.value.trim();
      commit(X.setInputSource(st.graph, step.name, i.name, { kind: 'const', value: t === 'true' ? true : t === 'false' ? false : t === '' ? NaN : Number(t) }));
    } });
    return h('li', { class: 'expr-input' }, h('span', { class: 'mono' }, i.name),
      select('Kind of ' + i.name, [{ value: 'sig', label: 'signal' }, { value: 'step', label: 'earlier step' }, { value: 'const', label: 'constant' }], kind, swap, ro()),
      value, ro() ? null : btn('Remove', () => commit(X.removeInput(st.graph, step.name, i.name)), 'btn danger'));
  }

  function outerPanel() {
    const step = st.graph.steps.find((x) => x.name === st.sel);
    if (!step) return h('p', { class: 'muted' }, 'Select a step box to rename it, wire its inputs or open it. Double click a box (or press Enter) to edit what is inside.');
    const others = st.graph.steps.filter((x) => x.name !== step.name);
    return h('section', { class: 'expr-panel', 'aria-label': 'Step ' + step.name },
      h('h4', null, 'Step ' + step.name),
      textBox('Step name', step.name, (v) => { const r = X.renameStep(st.graph, step.name, v); if (!r.error) st.sel = v.trim(); commit(r); }, ro()),
      h('div', { class: 'row wrap' },
        btn('Edit step', () => open(step.name)),
        ro() || st.graph.out === step.name ? null : btn('Make guard output', () => commit(X.setGuardOut(st.graph, step.name))),
        ro() ? null : btn('Delete step', () => { const r = X.deleteStep(st.graph, step.name); if (!r.error) st.sel = ''; commit(r); }, 'btn danger')),
      st.graph.out === step.name ? h('p', { class: 'muted' }, 'This step is the guard output (it must be true or false).') : null,
      h('h4', null, 'Inputs (the arrows between boxes)'),
      step.inputs.length ? h('ul', { class: 'expr-inputs' }, ...step.inputs.map((i) => inputRow(step, i))) : h('p', { class: 'muted' }, 'No inputs yet. A step reads signals, constants or earlier steps.'),
      ro() ? null : h('div', { class: 'row wrap' },
        btn('Add signal', () => commit(X.addInput(st.graph, step.name, { kind: 'sig', ref: 'gyroMax' }))),
        others.length ? btn('Add earlier step', () => commit(X.addInput(st.graph, step.name, { kind: 'step', ref: others[0].name }))) : null,
        btn('Add constant', () => commit(X.addInput(st.graph, step.name, { kind: 'const', value: 0 })))),
      diagList(problemsFor(step.name), 'problems'), diagList(warningsFor(step.name), 'warnings'));
  }

  // ---- inner
  function innerSvg(step) {
    const L = X.innerLayout(step);
    const svg = s('svg', { class: 'expr-svg', viewBox: '0 0 ' + L.width + ' ' + L.height, width: L.width, height: L.height, role: 'group', 'aria-label': 'Nodes of step ' + step.name });
    svg.appendChild(s('defs', null, s('marker', { id: 'expr-wire', viewBox: '0 0 10 10', refX: 9, refY: 5, markerWidth: 7, markerHeight: 7, orient: 'auto' }, s('path', { d: 'M0 0L10 5L0 10z', class: 'expr-wirehead' }))));
    for (const w of L.wires) {
      svg.appendChild(s('g', { class: 'expr-wire' }, s('title', null, w.from + ' to ' + w.to), s('path', { d: w.d, class: 'expr-wire-line', fill: 'none', 'marker-end': 'url(#expr-wire)' }),
        s('text', { x: w.lx, y: w.ly, 'text-anchor': 'end', class: 'expr-port' }, w.port)));
    }
    const bad = new Set();
    for (const p of problemsFor(step.name)) for (const n of p.nodes) bad.add(n);
    for (const it of L.items) {
      svg.appendChild(s('g', {
        class: 'expr-node' + (it.isInput ? ' input' : '') + (st.sel === it.key ? ' selected' : '') + (bad.has(it.key) ? ' has-problem' : ''), 'data-node': it.key, tabindex: 0, role: 'button',
        'aria-label': (it.isInput ? 'Input ' : 'Node ') + it.key + ': ' + it.label + (it.isOut ? ', step output' : ''),
        onclick: () => { st.sel = it.key; render(); }, onkeydown: (e) => { if (e.key === 'Enter' || e.key === ' ') { st.sel = it.key; render(); } },
      },
      s('rect', { x: it.x, y: it.y, width: X.NODE.w, height: X.NODE.h, rx: 7, class: 'expr-node-body' }),
      s('text', { x: it.x + 8, y: it.y + 18, class: 'expr-node-id' }, it.key + (it.isOut ? ' → out' : '')),
      s('text', { x: it.x + 8, y: it.y + 36, class: 'expr-box-text' }, it.label)));
    }
    return h('div', { class: 'expr-scroll' }, svg);
  }

  function paramRows(step, n) {
    const set = (key) => (e) => commit(X.setNodeParam(st.graph, step.name, n.id, key, e.target.value));
    const num = (key, label) => field(label, h('input', { type: 'text', value: String(n[key]), disabled: ro() ? true : null, onchange: set(key) }));
    switch (n.op) {
      case 'sig': return [select('Signal', X.SIGNALS.map((x) => ({ value: x.name, label: x.name + ' (' + x.note + ')' })), n.name, (v) => commit(X.setNodeParam(st.graph, step.name, n.id, 'name', v)), ro())];
      case 'const': return [field('Value (a number, true or false; a number can be swept by the tuner)', h('input', { type: 'text', value: String(n.value), disabled: ro() ? true : null, onchange: set('value') }))];
      case 'cmp': return [select('Operator', X.CMP.map((x) => ({ value: x, label: x })), n.f, (v) => commit(X.setNodeParam(st.graph, step.name, n.id, 'f', v)), ro())];
      case 'arith': return [select('Operator', X.ARITH.map((x) => ({ value: x, label: x })), n.f, (v) => commit(X.setNodeParam(st.graph, step.name, n.id, 'f', v)), ro())];
      case 'in': return [field('States (comma separated: ' + X.STATES.join(', ') + ')', h('input', { type: 'text', value: n.set.join(', '), disabled: ro() ? true : null, onchange: set('set') }))];
      case 'clamp': return [num('lo', 'Lowest'), num('hi', 'Highest')];
      case 'fresh': return [num('ms', 'Fresh within (ms, 1 to 60000)')];
      case 'win': return [
        select('Signal', X.WIN_SIGNALS.map((x) => ({ value: x.name, label: x.name + ' (' + x.note + ')' })), n.name, (v) => commit(X.setNodeParam(st.graph, step.name, n.id, 'name', v)), ro()),
        select('Aggregate over the window', X.WIN_AGG.map((x) => ({ value: x, label: x })), n.agg, (v) => commit(X.setNodeParam(st.graph, step.name, n.id, 'agg', v)), ro()),
        num('sec', 'Seconds (1 to 300; the window answers only when all of them were seen)'), n.agg === 'frac' ? num('t', 'Counts seconds above') : null];
      default: return [];
    }
  }

  function innerPanel(step) {
    const n = step.nodes.find((x) => x.id === st.sel);
    const sources = step.inputs.map((i) => ({ value: i.name, label: 'input ' + i.name })).concat(step.nodes.map((x) => ({ value: x.id, label: 'node ' + x.id })));
    const adder = ro() ? null : h('div', { class: 'row wrap' },
      select('Kind of node to add', X.OP_NAMES.map((o) => ({ value: o, label: X.OPS[o].label })), 'cmp', (v) => { st.addOp = v; }),
      btn('Add node', () => { const r = X.addNode(st.graph, step.name, st.addOp || 'cmp'); if (!r.error) st.sel = r.id; commit(r); }));
    if (!n) {
      const isInput = step.inputs.some((i) => i.name === st.sel);
      return h('div', null, adder, h('p', { class: 'muted' }, isInput ? 'Inputs are wired on the steps view (the arrows between boxes).' : 'Select a node to set its values and wire its ports.'));
    }
    const ports = X.opOf(n.op).ports.map((p) => {
      const cur = X.wireTo(step, n.id, p);
      const list = [{ value: '', label: 'not wired' }].concat(sources.filter((o) => o.value !== n.id));
      return field('Port ' + p, select('Port ' + p + ' of ' + n.id, list, cur, (v) => commit(v === '' ? X.removeWire(st.graph, step.name, n.id + '.' + p) : X.setWire(st.graph, step.name, n.id + '.' + p, v)), ro()));
    });
    return h('div', null, adder, h('section', { class: 'expr-panel', 'aria-label': 'Node ' + n.id },
      h('h4', null, X.opOf(n.op).label + ' ' + n.id),
      textBox('Node name', n.id, (v) => { const r = X.renameNode(st.graph, step.name, n.id, v); if (!r.error) st.sel = v.trim(); commit(r); }, ro()),
      ...paramRows(step, n), ...ports,
      h('div', { class: 'row wrap' },
        ro() || step.out === n.id ? null : btn('Make step output', () => commit(X.setStepOut(st.graph, step.name, n.id))),
        ro() ? null : btn('Delete node', () => { const r = X.deleteNode(st.graph, step.name, n.id); if (!r.error) st.sel = ''; commit(r); }, 'btn danger')),
      step.out === n.id ? h('p', { class: 'muted' }, 'This node is the output of the step (a number or true/false).') : null));
  }

  function innerView() {
    const step = stepNow();
    if (!step) { st.view = 'outer'; return outerView(); }
    return h('div', { class: 'expr-inner' },
      h('div', { class: 'row wrap' }, btn('Back to steps', back), h('strong', null, 'Step ' + step.name)),
      step.out === '' || !step.nodes.some((x) => x.id === step.out) ? h('p', { class: 'err', role: 'alert' }, 'This step has no output node. Select a node and make it the step output.') : null,
      innerSvg(step), innerPanel(step),
      diagList(problemsFor(step.name), 'problems'), diagList(warningsFor(step.name), 'warnings'));
  }

  function outerView() {
    return h('div', { class: 'expr-outer' },
      h('div', { class: 'row wrap' }, ro() ? null : btn('Add step', () => { const r = X.addStep(st.graph); if (!r.error) st.sel = r.name; commit(r); })),
      outerSvg(), outerPanel());
  }

  // ---- try-it: sliders for the signals the program reads; the PHONE evaluates it with the glasses' own runner (opts.tryIt posts it)
  function tryView() {
    if (!opts.tryIt) return null;
    const rows = X.signalsOf(st.graph).map((n) => {
      const [lo, hi, stp] = X.sliderOf(n);
      const v = Object.prototype.hasOwnProperty.call(st.tv, n) ? st.tv[n] : lo;
      const out = h('output', null, String(v));
      return h('label', { class: 'field' }, h('span', null, n), h('input', { type: 'range', min: lo, max: hi, step: stp, value: String(v), 'aria-label': 'Try value for ' + n, oninput: (e) => { st.tv[n] = Number(e.target.value); out.textContent = e.target.value; } }), out);
    });
    const run = () => {
      st.tryOut = ['Running…'];
      render();
      opts.tryIt(X.tryBody(st.graph, st.tv, opts.target ? opts.target() : null)).then((r) => { st.tryOut = X.tryLines(r); render(); }, (e) => { st.tryOut = [String((e && e.message) || 'The try-it failed.')]; render(); });
    };
    return h('section', { class: 'expr-try', 'aria-label': 'Try it' }, h('h4', null, 'Try it (the phone evaluates it)'), ...rows, btn('Run', run),
      st.tryOut ? h('ul', { class: 'expr-try-out', role: 'status' }, ...st.tryOut.map((l) => h('li', null, l))) : null);
  }

  function jsonView() {
    const area = h('textarea', { class: 'mono', rows: 12, spellcheck: 'false', 'aria-label': 'Expression graph as JSON', readonly: ro() ? true : null }, '');
    area.textContent = st.jsonText;
    area.value = st.jsonText;
    return h('div', null, area, ro() ? null : btn('Apply JSON', () => {
      const r = X.parseGraph(area.value);
      if (r.error) { st.jsonText = area.value; say(r.error, true); render(); return; }
      st.graph = r.graph; st.jsonText = area.value; say('JSON applied. Compile to check it.', false);
      if (opts.onChange) opts.onChange(r.graph);
      render();
    }));
  }

  function render() {
    refreshPanel();
    clear(el);
    const tgt = opts.target ? opts.target() : null;
    const head = h('div', { class: 'row wrap' }, h('strong', null, tgt ? 'Expression value' : 'Expression guard'),
      btn(st.json ? 'Hide JSON' : 'Show JSON', () => { st.json = !st.json; st.jsonText = JSON.stringify(st.graph, null, 2); render(); }, 'btn'));
    if (st.json) st.jsonText = st.jsonText || JSON.stringify(st.graph, null, 2);
    el.appendChild(h('div', { class: 'expr-editor' }, head, tgt ? h('p', { class: 'muted' }, X.targetNote(tgt.field)) : null, messages(), st.json ? jsonView() : st.view === 'inner' ? innerView() : outerView(), tryView()));
  }
  render();

  return {
    update(next) {
      if (next && next.graph !== undefined && JSON.stringify(next.graph) !== JSON.stringify(st.graph)) { const r = X.parseGraph(next.graph); if (r.graph) st.graph = r.graph; }
      st.jsonText = '';
      render();
    },
    graph: () => st.graph,
    state: () => ({ view: st.view, step: st.step, sel: st.sel, json: st.json }),
    unmount() { clear(el); },
  };
}
