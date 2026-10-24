// X5: the boxed expression editor over the fake DOM (what GraalJS can prove): boxes and arrows render, every label goes through textContent,
// opening a step shows its nodes, Back returns, wiring is refused on loops, diagnostics and meters show on the offending step, the JSON fallback applies.
import { mountExprEditor } from '../../main/assets/canvas/exprview.js';
import * as X from '../../main/assets/canvas/exprgraph.js';
import { findAll, find } from './fake-dom.js';
import { eq, ok } from './fixtures.js';
import * as E from './env.js';

const cls = (el, c) => el.classList.contains(c);
const boxes = (root) => findAll(root, (e) => cls(e, 'expr-box'));
const nodes = (root) => findAll(root, (e) => cls(e, 'expr-node'));
const arrows = (root) => findAll(root, (e) => cls(e, 'expr-arrow'));
const byLabel = (root, l) => find(root, (e) => e.getAttribute('aria-label') === l);
const text = (root) => E.textOf(root);
const pick = (root, label, value) => { const s = byLabel(root, label); ok(s, 'no control labelled ' + label); s.value = value; s.dispatch('change'); };

function mount(over, fn) {
  const env = E.setup(['app']);
  try {
    const root = document.getElementById('app');
    const seen = [];
    const opts = Object.assign({ graph: X.defaultGraph(), panel: null, onChange: (g) => seen.push(g) }, over || {});
    const ed = mountExprEditor(root, opts);
    fn({ root, ed, seen });
  } finally { env.uninstall(); }
}

const twoSteps = () => { let g = X.defaultGraph(); g = X.addStep(g).graph; g = X.renameStep(g, 'step', 'still').graph; return X.addInput(g, 'still', { kind: 'step', ref: 'fast' }).graph; };

export const tests = {
  boxesAndArrowsRenderWithTheirLabels() {
    mount({ graph: twoSteps() }, ({ root }) => {
      eq(boxes(root).length, 2);
      eq(arrows(root).length, 1);
      const t = text(root);
      ok(t.includes('step: fast') && t.includes('step: still'), t);
      ok(t.includes('fast → still'), 'the arrow pill names its ends');
      ok(t.includes('in: headSpeed'), 'inputs show inside the box');
      ok(t.includes('guard output'));
      ok(byLabel(root, 'Steps of the expression guard'), 'the svg has a name');
    });
  },
  labelsAreTextNotMarkup() {
    const g = X.defaultGraph();
    g.steps[0].name = '<img src=x onerror=alert(1)>'; g.out = g.steps[0].name;
    mount({ graph: g }, ({ root }) => {
      ok(text(root).includes('<img src=x onerror=alert(1)>'), 'shown literally');
      eq(findAll(root, (e) => e.tagName === 'img').length, 0, 'no element was made from the name');
      eq(findAll(root, (e) => 'innerHTML' in e).length, 0, 'no innerHTML anywhere');
    });
  },
  openAStepAndGoBack() {
    mount({ graph: twoSteps() }, ({ root, ed }) => {
      boxes(root)[0].dispatch('dblclick');
      eq(ed.state().view, 'inner'); eq(ed.state().step, 'fast');
      eq(nodes(root).length, 3, 'speed, limit and over');
      ok(text(root).includes('compare >') && text(root).includes('constant 1.5'));
      E.click(E.buttons(root, 'Back to steps')[0]);
      eq(ed.state().view, 'outer'); eq(boxes(root).length, 2);
      boxes(root)[1].dispatch('keydown', { key: 'Enter' });
      eq(ed.state().step, 'still');
      E.click(E.buttons(root, 'Back to steps')[0]);
      boxes(root)[0].dispatch('click');
      E.click(E.buttons(root, 'Edit step')[0]);
      eq(ed.state().view, 'inner');
    });
  },
  editingStepsReportsEveryAcceptedChange() {
    mount({}, ({ root, seen }) => {
      E.click(E.buttons(root, 'Add step')[0]);
      eq(boxes(root).length, 2); eq(seen.length, 1); eq(seen[0].steps.length, 2);
    });
    mount({}, ({ root, seen }) => {
      boxes(root)[0].dispatch('click');
      const name = find(root, (e) => e.tagName === 'input' && e.value === 'fast');
      name.value = 'quick'; name.dispatch('change');
      ok(text(root).includes('step: quick'));
      eq(seen[0].out, 'quick');
      const bad = find(root, (e) => e.tagName === 'input' && e.value === 'quick');
      bad.value = 'Bad Name'; bad.dispatch('change');
      ok(find(root, (e) => e.getAttribute('role') === 'alert'), 'a refused name shows an alert');
      eq(seen.length, 1, 'a refused edit is not reported');
    });
  },
  stepInputsDrawTheArrowAndALoopIsRefused() {
    mount({ graph: twoSteps() }, ({ root, seen }) => {
      boxes(root)[0].dispatch('click'); // fast
      E.click(E.buttons(root, 'Add earlier step')[0]);
      const t = text(root);
      ok(t.includes('EXPR_LOOP'), 'the loop is refused with its code: ' + t.slice(0, 300));
      eq(seen.length, 0);
      eq(arrows(root).length, 1);
      boxes(root)[1].dispatch('click'); // still reads fast already; add a signal instead
      E.click(E.buttons(root, 'Add signal')[0]);
      eq(seen.length, 1);
    });
  },
  wiringANodePortRefusesALoop() {
    let g = X.defaultGraph();
    g = X.addNode(g, 'fast', 'not').graph; g = X.addNode(g, 'fast', 'and').graph; g = X.setWire(g, 'fast', 'and.a', 'not').graph;
    mount({ graph: g }, ({ root, seen }) => {
      boxes(root)[0].dispatch('dblclick');
      find(root, (e) => cls(e, 'expr-node') && e.getAttribute('data-node') === 'not').dispatch('click');
      pick(root, 'Port a of not', 'and');
      ok(text(root).includes('EXPR_LOOP'), text(root).slice(0, 300));
      eq(seen.length, 0);
      pick(root, 'Port a of not', 'over');
      eq(seen.length, 1);
      ok(seen[0].steps[0].wires.some((w) => w[0] === 'over' && w[1] === 'not.a'));
    });
  },
  constantsAreEditableInTheNode() {
    mount({}, ({ root, seen }) => {
      boxes(root)[0].dispatch('dblclick');
      find(root, (e) => cls(e, 'expr-node') && e.getAttribute('data-node') === 'limit').dispatch('click');
      const v = find(root, (e) => e.tagName === 'input' && e.value === '1.5');
      v.value = '2'; v.dispatch('change');
      eq(seen[0].steps[0].nodes[0].value, 2);
      ok(text(root).includes('constant 2'));
    });
  },
  diagnosticsAndMetersShowOnTheStep() {
    const g = twoSteps();
    const panel = () => ({
      compiled: true, stale: false, warnings: X.attribute(g, ['node e1: EXPR_GUARD_READS_CTX: step still: reads ctx']),
      problems: X.attribute(g, ['node e1: EXPR_PORT: step fast: port over.b is not wired', 'node e1: EXPR_TOO_MANY_NODES: 40 nodes (max 32)']),
      meters: { nodes: 6, maxNodes: 32, cost: 6, maxCost: 64, bytes: 900, maxBytes: 8192 },
    });
    mount({ graph: g, panel }, ({ root }) => {
      ok(text(root).includes('nodes 6/32'), 'meters');
      ok(text(root).includes('of 8192 bytes'));
      ok(text(root).includes('EXPR_TOO_MANY_NODES'), 'a plan-wide problem is listed');
      ok(cls(boxes(root)[0], 'has-problem'), 'the offending step box is marked');
      ok(!cls(boxes(root)[1], 'has-problem'));
      boxes(root)[0].dispatch('dblclick');
      ok(text(root).includes('EXPR_PORT: port over.b is not wired'), 'the step shows its own problem');
      ok(cls(find(root, (e) => e.getAttribute('data-node') === 'over'), 'has-problem'), 'and the node it names');
      ok(!cls(find(root, (e) => e.getAttribute('data-node') === 'limit'), 'has-problem'));
    });
    mount({ panel: () => ({ compiled: false, stale: false, problems: X.attribute(X.defaultGraph(), []), warnings: X.attribute(X.defaultGraph(), []), meters: null }) }, ({ root }) => {
      ok(text(root).includes('Compile to see'));
    });
  },
  aWindowNodeAuthoredInJsonOpensAndSelectsWithoutThrowing() {
    const g = X.defaultGraph();
    g.steps[0].nodes.push({ id: 'w', op: 'win', name: 'stepHz', agg: 'frac', sec: 20, t: 1 }, { id: 'odd', op: 'future_op' });
    mount({ graph: g }, ({ root }) => {
      boxes(root)[0].dispatch('dblclick');
      nodes(root).find((n) => n.getAttribute('data-node') === 'w').dispatch('click');
      ok(byLabel(root, 'Aggregate over the window') && byLabel(root, 'Signal'), 'the window controls show');
      ok(text(root).includes('frac stepHz 20 s > 1') && text(root).includes('Counts seconds above'));
      pick(root, 'Aggregate over the window', 'max');
      ok(!text(root).includes('Counts seconds above'), 'no threshold outside frac');
      nodes(root).find((n) => n.getAttribute('data-node') === 'odd').dispatch('click');
      ok(text(root).includes('future_op'), 'an unknown op is shown, not thrown on');
    });
  },
  aValueEditorShowsItsTargetClampAndTheTryItPanelRunsThePhone() {
    const asked = [];
    const reply = { ok: true, steps: [{ name: 'fast', result: { state: 'BOOL', value: true } }], output: { state: 'BOOL', value: true }, verdict: 'keep' };
    const g = X.defaultGraph();
    mount({ graph: g, target: () => ({ row: 'REST', field: 'quietGapS' }), tryIt: (b) => { asked.push(b); return { then: (f) => f(reply) }; } }, ({ root }) => {
      ok(text(root).includes('Expression value') && text(root).includes('5 to 300 seconds'), text(root));
      const slider = byLabel(root, 'Try value for gyroMax');
      ok(slider, 'one slider per signal the program reads');
      slider.value = '2.5'; slider.dispatch('input');
      E.click(E.buttons(root, 'Run')[0]);
      eq(asked.length, 1); eq(asked[0].signals, { gyroMax: 2.5 }); eq(asked[0].role, 'value'); eq(asked[0].field, 'quietGapS');
      ok(text(root).includes('fast = true') && text(root).includes('keep the picture'));
    });
    mount({ graph: g }, ({ root }) => ok(!byLabel(root, 'Try value for gyroMax'), 'no try-it without a runner'));
  },
  jsonFallbackShowsAndAppliesTheGraph() {
    mount({}, ({ root, seen, ed }) => {
      E.click(E.buttons(root, 'Show JSON')[0]);
      const area = byLabel(root, 'Expression graph as JSON');
      eq(JSON.parse(area.value), X.defaultGraph());
      area.value = '{';
      E.click(E.buttons(root, 'Apply JSON')[0]);
      ok(text(root).includes('Not valid JSON')); eq(seen.length, 0);
      const g = twoSteps();
      byLabel(root, 'Expression graph as JSON').value = JSON.stringify(g);
      E.click(E.buttons(root, 'Apply JSON')[0]);
      eq(seen.length, 1); eq(ed.graph().steps.length, 2);
      E.click(E.buttons(root, 'Hide JSON')[0]);
      eq(boxes(root).length, 2);
    });
  },
  readOnlyOffersNoEditing() {
    mount({ readOnly: true, graph: twoSteps() }, ({ root }) => {
      eq(E.buttons(root, 'Add step').length, 0);
      boxes(root)[0].dispatch('click');
      eq(E.buttons(root, 'Delete step').length, 0); eq(E.buttons(root, 'Add signal').length, 0);
      ok(E.buttons(root, 'Edit step').length === 1, 'looking inside is still allowed');
    });
  },
  nothingMakesAConfirmOrPrompt() {
    globalThis.confirm = () => { throw new Error('confirm used'); };
    globalThis.prompt = () => { throw new Error('prompt used'); };
    try {
      mount({ graph: twoSteps() }, ({ root }) => {
        boxes(root)[0].dispatch('click'); E.click(E.buttons(root, 'Delete step')[0]);
        boxes(root)[1].dispatch('click'); E.click(E.buttons(root, 'Delete step')[0]);
      });
    } finally { delete globalThis.confirm; delete globalThis.prompt; }
  },
};
