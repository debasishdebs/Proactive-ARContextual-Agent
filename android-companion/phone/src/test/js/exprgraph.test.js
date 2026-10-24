// The pure model of the expr.guard graph (X5): edit operations, loop refusal at edit time, derived arrows, diagnostics and meters.
import * as X from '../../main/assets/canvas/exprgraph.js';
import { addNode as addCanvasNode } from '../../main/assets/canvas/graph.js';
import { fields, coerce } from '../../main/assets/canvas/paramform.js';
import { applyCompile, compileStart, compileView, exprPanel, open } from '../../main/assets/canvas/editorlogic.js';
import { exprGuardType, exprValueType, allTypes, eq, ok } from './fixtures.js';

const two = () => { // fast -> still, both valid; still reads fast
  let g = X.defaultGraph();
  g = X.addStep(g).graph; // step
  g = X.renameStep(g, 'step', 'still').graph;
  g = X.addInput(g, 'still', { kind: 'step', ref: 'fast' }).graph;
  return g;
};
const step = (g, n) => g.steps.find((s) => s.name === n);

export const tests = {
  defaultGraphIsOneStepFast() {
    const g = X.defaultGraph();
    eq(g.out, 'fast'); eq(g.steps.length, 1); eq(g.steps[0].name, 'fast');
    eq(g.steps[0].inputs, [{ name: 'speed', sig: 'headSpeed' }]);
    eq(g.steps[0].out, 'over');
    eq(JSON.parse(X.DEFAULT_GRAPH_JSON), g);
  },
  paletteAddsAValidDefaultGraph() {
    const r = addCanvasNode({ schema: 2, id: 'p', name: 'p', nodes: [], edges: [] }, exprGuardType, null, allTypes);
    eq(r.pipe.nodes[0].params.graph, X.defaultGraph());
    eq(r.pipe.nodes[0].params.graph === r.pipe.nodes[0].params.graph, true);
  },
  paramformGraphFieldAndUnknownKinds() {
    const f = fields(exprGuardType.params, {}, {})[0];
    eq(f.control, 'graph'); eq(f.value, X.defaultGraph());
    const g = two();
    eq(coerce(f, g), { value: g });
    eq(coerce(f, JSON.stringify(g)), { value: g });
    ok(coerce(f, '{').error, 'bad JSON refused');
    ok(coerce(f, { steps: 'x', out: 'a' }).error, 'bad shape refused');
    const u = fields([{ name: 'mystery', kind: 'hologram' }], { mystery: { a: 1 } }, {})[0];
    eq([u.control, u.readOnly, u.value], ['textarea', true, '{"a":1}']);
    ok(coerce(u, 'x').error, 'an unknown kind is never edited');
    eq(fields([{ name: 't', kind: 'text' }], { t: 'a' }, {})[0].readOnly, undefined);
  },
  renameStepPropagatesToReferences() {
    const g = two();
    const r = X.renameStep(g, 'fast', 'quick').graph;
    eq(g.out, 'fast'); eq(r.out, 'quick');
    eq(step(r, 'still').inputs.find((i) => i.step).step, 'quick');
    eq(r.steps.map((s) => s.name).sort(), ['quick', 'still']);
    eq(X.outerEdges(r), [{ from: 'quick', to: 'still', input: 'fast' }]);
  },
  renameStepRefusesBadAndDuplicateNames() {
    const g = two();
    ok(X.renameStep(g, 'fast', 'Still').error, 'pattern');
    ok(X.renameStep(g, 'fast', '1x').error);
    ok(X.renameStep(g, 'fast', 'x'.repeat(25)).error);
    ok(/already a step/.test(X.renameStep(g, 'fast', 'still').error));
    eq(X.renameStep(g, 'fast', 'fast').graph, g);
  },
  deleteStepCleansReferencesAndKeepsTheOutput() {
    const g = two();
    ok(/guard output/.test(X.deleteStep(g, 'fast').error), 'fast is the guard output in two()');
    const withOut = X.setGuardOut(g, 'still').graph;
    ok(/guard output/.test(X.deleteStep(withOut, 'still').error), 'the output step is protected');
    const d = X.deleteStep(withOut, 'fast');
    eq(d.graph.steps.map((s) => s.name), ['still']);
    eq(step(d.graph, 'still').inputs, []);
    ok(/Removed 1 input/.test(d.note));
    ok(X.deleteStep(X.defaultGraph(), 'fast').error);
  },
  deleteStepRemovesWiresFromItsInput() {
    const g = X.setGuardOut(withWiredStepInput(), 'still').graph;
    ok(step(g, 'still').wires.some((w) => w[0] === 'fast'));
    const d = X.deleteStep(g, 'fast').graph;
    eq(step(d, 'still').wires.filter((w) => w[0] === 'fast'), []);
  },
  outerArrowsAreDerivedFromStepInputsOnly() {
    let g = two();
    g = X.addInput(g, 'still', { kind: 'sig', ref: 'ctx' }).graph;
    g = X.addInput(g, 'still', { kind: 'const', value: 3 }).graph;
    eq(X.outerEdges(g), [{ from: 'fast', to: 'still', input: 'fast' }]);
    const L = X.outerLayout(g);
    eq(L.arrows.length, 1); eq(L.arrows[0].label, 'fast → still');
    const fast = L.boxes.find((b) => b.name === 'fast'); const still = L.boxes.find((b) => b.name === 'still');
    ok(still.x > fast.x, 'a reader sits right of what it reads');
    eq([fast.layer, still.layer], [0, 1]);
  },
  skippingArrowGoesAround() {
    let g = two();
    g = X.addStep(g).graph; // 'step'
    g = X.addInput(g, 'step', { kind: 'step', ref: 'still' }).graph;
    g = X.addInput(g, 'step', { kind: 'step', ref: 'fast' }).graph;
    const L = X.outerLayout(g);
    eq(L.arrows.length, 3);
    eq(L.boxes.find((b) => b.name === 'step').layer, 2);
    const around = L.arrows.find((a) => a.from === 'fast' && a.to === 'step');
    ok(around.ly > L.arrows.find((a) => a.from === 'fast' && a.to === 'still').ly, 'the layer-skipping arrow bulges below');
  },
  stepLoopsAreRefusedAtEditTime() {
    const g = two();
    const self = X.addInput(g, 'fast', { kind: 'step', ref: 'fast' });
    ok(/itself/.test(self.error));
    const loop = X.addInput(g, 'fast', { kind: 'step', ref: 'still' });
    ok(/EXPR_LOOP/.test(loop.error), 'fast reading still, which reads fast, is a loop');
    eq(g.steps.length, 2);
    const two2 = X.addInput(g, 'fast', { kind: 'sig', ref: 'ctx' }).graph;
    const retarget = X.setInputSource(two2, 'fast', 'in', { kind: 'step', ref: 'still' });
    ok(/EXPR_LOOP/.test(retarget.error));
    eq(X.stepLoop(g, 'fast', 'still'), true); eq(X.stepLoop(g, 'still', 'fast'), false);
  },
  inputsCanBeSignalsConstantsAndSteps() {
    let g = X.defaultGraph();
    g = X.addInput(g, 'fast', { kind: 'const', value: 2.5 }).graph;
    g = X.addInput(g, 'fast', { kind: 'const', value: true }).graph;
    g = X.addInput(g, 'fast', { kind: 'sig', ref: 'ctxAgeS' }).graph;
    eq(step(g, 'fast').inputs.slice(1).map((i) => i.const === undefined ? i.sig : i.const), [2.5, true, 'ctxAgeS']);
    ok(X.addInput(g, 'fast', { kind: 'sig', ref: 'nope' }).error, 'unknown signal refused');
    ok(X.addInput(g, 'fast', { kind: 'const', value: NaN }).error);
    ok(X.addInput(g, 'fast', { kind: 'const', value: 2000000 }).error);
    eq(step(X.setInputSource(g, 'fast', 'speed', { kind: 'sig', ref: 'gyroMax' }).graph, 'fast').inputs[0], { name: 'speed', sig: 'gyroMax' });
    ok(X.SIGNALS.some((s) => s.name === 'headSpeed') && X.SIGNALS.some((s) => s.name === 'gyroMax'), 'alias and canonical both listed');
    const names = step(g, 'fast').inputs.map((i) => i.name);
    eq(new Set(names).size, names.length, 'generated input names are unique');
  },
  addAndDeleteNodesKeepWiresConsistent() {
    let g = X.defaultGraph();
    const a = X.addNode(g, 'fast', 'not'); g = a.graph;
    eq(a.id, 'not'); eq(X.addNode(g, 'fast', 'not').id, 'not_2');
    g = X.setWire(g, 'fast', 'not.a', 'over').graph;
    g = X.setStepOut(g, 'fast', 'not').graph;
    const d = X.deleteNode(g, 'fast', 'over');
    eq(step(d.graph, 'fast').wires.filter((w) => w[0] === 'over' || w[1].startsWith('over.')), []);
    eq(step(d.graph, 'fast').wires.length, 0);
    const out = X.deleteNode(g, 'fast', 'not').graph;
    eq(step(out, 'fast').out, '');
    ok(X.addNode(g, 'fast', 'frobnicate').error);
    eq(X.addNode(g, 'fast', 'const').graph.steps[0].nodes.slice(-1)[0], { id: 'k', op: 'const', value: 0 });
  },
  renameNodeFollowsWiresAndOut() {
    const g = X.defaultGraph();
    const r = X.renameNode(g, 'fast', 'over', 'too_fast').graph;
    eq(step(r, 'fast').out, 'too_fast');
    ok(step(r, 'fast').wires.some((w) => w[1] === 'too_fast.a'));
    ok(X.renameNode(g, 'fast', 'over', 'speed').error, 'an input name is taken too');
    ok(X.renameNode(g, 'fast', 'over', 'Bad').error);
  },
  nodeParamsAreValidated() {
    let g = X.defaultGraph();
    const set = (id, key, raw) => X.setNodeParam(g, 'fast', id, key, raw);
    eq(set('limit', 'value', '2.25').graph.steps[0].nodes[0].value, 2.25);
    eq(set('limit', 'value', 'true').graph.steps[0].nodes[0].value, true);
    ok(set('limit', 'value', 'abc').error); ok(set('limit', 'value', '').error); ok(set('limit', 'value', '1e9').error);
    eq(set('over', 'f', '>=').graph.steps[0].nodes[1].f, '>=');
    ok(set('over', 'f', '=>').error);
    g = X.addNode(g, 'fast', 'clamp').graph;
    ok(/exceed/.test(set('clamp', 'lo', '5').error));
    eq(set('clamp', 'hi', '9').graph.steps[0].nodes[2].hi, 9);
    g = X.addNode(g, 'fast', 'fresh').graph;
    ok(set('fresh', 'ms', '0').error); ok(set('fresh', 'ms', '60001').error); ok(set('fresh', 'ms', '1.5').error);
    eq(set('fresh', 'ms', '60000').graph.steps[0].nodes[3].ms, 60000);
    g = X.addNode(g, 'fast', 'in').graph;
    eq(set('is_in', 'set', 'WALKING, REST').graph.steps[0].nodes[4].set, ['REST', 'WALKING']);
    ok(set('is_in', 'set', 'SWIMMING').error); ok(set('is_in', 'set', '').error);
    g = X.addNode(g, 'fast', 'sig').graph;
    eq(set('sig', 'name', 'headSpeed').graph.steps[0].nodes[5].name, 'headSpeed');
    ok(set('sig', 'name', 'nope').error);
    ok(set('over', 'ms', '5').error, 'a param the op does not have');
  },
  innerLoopsAreRefusedAtEditTime() {
    let g = X.defaultGraph();
    g = X.addNode(g, 'fast', 'not').graph;       // not
    g = X.addNode(g, 'fast', 'and').graph;       // and
    g = X.setWire(g, 'fast', 'and.a', 'not').graph;
    ok(/EXPR_LOOP/.test(X.setWire(g, 'fast', 'not.a', 'and').error), 'not -> and -> not');
    ok(/itself/.test(X.setWire(g, 'fast', 'not.a', 'not').error));
    eq(step(X.setWire(g, 'fast', 'not.a', 'over').graph, 'fast').wires.filter((w) => w[1] === 'not.a'), [['over', 'not.a']]);
    ok(X.setWire(g, 'fast', 'not.a', 'ghost').error);
    ok(X.setWire(g, 'fast', 'not.b', 'over').error, 'not has no port b');
  },
  rewiringAPortReplacesTheOldWire() {
    const g = X.setWire(X.defaultGraph(), 'fast', 'over.a', 'limit').graph;
    eq(step(g, 'fast').wires.filter((w) => w[1] === 'over.a'), [['limit', 'over.a']]);
    eq(X.wireTo(step(g, 'fast'), 'over', 'b'), 'limit');
    eq(step(X.removeWire(g, 'fast', 'over.a').graph, 'fast').wires.length, 1);
  },
  innerLayoutPutsInputsLeftAndFollowsDepth() {
    let g = X.defaultGraph();
    g = X.addNode(g, 'fast', 'not').graph;
    g = X.setWire(g, 'fast', 'not.a', 'over').graph;
    const L = X.innerLayout(step(g, 'fast'));
    const at = (k) => L.items.find((i) => i.key === k);
    eq(at('speed').layer, 0); eq(at('over').layer, 1); eq(at('not').layer, 2);
    ok(at('not').x > at('over').x);
    eq(L.wires.length, 3);
    eq(at('over').label, 'compare >'); eq(at('speed').label, 'headSpeed'); eq(at('limit').label, 'constant 1.5');
  },
  diagnosticsLandOnTheOffendingStepAndNode() {
    const g = two();
    const a = X.attribute(g, [
      'node e1: EXPR_PORT: step still: port k.a is not wired',
      'node e1: EXPR_LOOP: step fast: nodes limit, over feed each other in a loop',
      'node e1: EXPR_TOO_MANY_NODES: 40 nodes (max 32)',
      'node e1: EXPR_SHAPE: step 1: unknown key "x"',
    ]);
    eq(a.steps.still.map((p) => p.code), ['EXPR_PORT', 'EXPR_SHAPE']);
    eq(a.steps.still[0].nodes, ['k']);
    eq(a.steps.fast[0].nodes, ['limit', 'over']);
    eq(a.general.map((p) => p.code), ['EXPR_TOO_MANY_NODES']);
  },
  paletteAddsAValueNodeWithAValidNumericGraphAndTheDefaultTarget() {
    const r = addCanvasNode({ schema: 2, id: 'p', name: 'p', nodes: [], edges: [] }, exprValueType, null, allTypes);
    const n = r.pipe.nodes[0];
    eq(n.params.graph, X.defaultGraphFor('expr.value')); eq(n.params.row, 'REST'); eq(n.params.field, 'capPerMin');
    eq(X.defaultGraphFor('expr.guard'), X.defaultGraph());
    eq(X.targetNote('capPerMin').includes('1 to 4'), true); eq(X.targetNote('quietGapS').includes('5 to 300'), true); eq(X.targetNote(''), '');
  },
  windowNodesAreEditableAndOnlyFracTakesAThreshold() {
    let g = X.addNode(X.defaultGraph(), 'fast', 'win').graph;
    const id = 'win';
    eq(g.steps[0].nodes.find((n) => n.id === id), { id, op: 'win', name: 'stepHz', agg: 'mean', sec: 10 });
    g = X.setNodeParam(g, 'fast', id, 'agg', 'frac').graph;
    eq(g.steps[0].nodes.find((n) => n.id === id).t, 0.5);
    g = X.setNodeParam(g, 'fast', id, 'sec', '300').graph; g = X.setNodeParam(g, 'fast', id, 'name', 'gyroMax').graph; g = X.setNodeParam(g, 'fast', id, 't', '1.5').graph;
    eq(g.steps[0].nodes.find((n) => n.id === id), { id, op: 'win', name: 'gyroMax', agg: 'frac', sec: 300, t: 1.5 });
    eq(X.nodeLabel(g.steps[0].nodes.find((n) => n.id === id)), 'frac gyroMax 300 s > 1.5');
    eq('t' in X.setNodeParam(g, 'fast', id, 'agg', 'max').graph.steps[0].nodes.find((n) => n.id === id), false);
    for (const [k, v] of [['sec', '0'], ['sec', '301'], ['sec', '1.5'], ['name', 'ctx'], ['name', 'ctxAgeS'], ['agg', 'median']]) ok(X.setNodeParam(g, 'fast', id, k, v).error, k + ' ' + v);
    eq(X.setWire(g, 'fast', id + '.a', 'speed').error, 'No such port');
    eq(X.nodeLabel({ id: 'x', op: 'nope' }), 'nope');
  },
  metersShowWindowTermsAndStateBytes() {
    const w = (n, agg, sec, t) => Object.assign({ op: 'win', name: n, agg, sec }, t === undefined ? {} : { t });
    const plan = JSON.stringify({ config: { exprs: [{ id: 'a', s: [w('stepHz', 'mean', 10), w('gyroMax', 'max', 300), w('stepHz', 'mean', 10)] }, { id: 'b', s: [w('stepHz', 'frac', 5, 1)] }] } });
    const m = X.exprMeters(plan);
    eq(m.terms, 3); eq(m.stateBytes, 4 * 10 + 8 * 300 + 4 * 5, 'a shared term counts once; max costs 8 bytes a second');
    const t = X.metersText({ nodes: 4, maxNodes: 32, cost: 6, maxCost: 64, bytes: 900, maxBytes: 8192, terms: m.terms, maxTerms: X.MAX_WINDOWS, stateBytes: m.stateBytes });
    ok(t.includes('windows 3/4') && t.includes('window state 2460 bytes') && t.includes('8192'), t);
  },
  tryBodyAndLines() {
    let g = X.addNode(X.defaultGraph(), 'fast', 'win').graph;
    eq(X.signalsOf(g), ['gyroMax', 'stepHz'], 'headSpeed folds into gyroMax; windows count');
    eq(X.tryBody(g, { stepHz: 2 }, null), { graph: g, signals: { gyroMax: 0, stepHz: 2 } });
    eq(X.tryBody(g, {}, { row: 'REST', field: 'capPerMin' }).role, 'value');
    eq(X.tryLines({ ok: true, steps: [{ name: 'fast', result: { state: 'BOOL', value: false } }, { name: 'w', result: { state: 'NOT_READY' } }], output: { state: 'BOOL', value: false }, verdict: 'drop' }),
      ['fast = false', 'w = NOT_READY', 'result: false (drop the picture)']);
    eq(X.tryLines({ ok: false, problems: ['node try: EXPR_PORT: x'] }), ['node try: EXPR_PORT: x']);
    eq(X.tryLines({ ok: true, steps: [], output: { state: 'NUM', value: 3 }, applied: 3 }), ['result: 3 (the row gets 3)']);
  },
  metersComeFromTheCompileResult() {
    const plan = JSON.stringify({ planSchema: 2, configHash: 'abc', meta: { name: 'p' }, config: { exprs: [{ id: 'e1', role: 'guard', out: 2, s: [{ op: 'sig', name: 'gyroMax' }, { op: 'const', v: 1.5 }, { op: 'cmp', f: '>', a: 0, b: 1 }, { op: 'in', a: 0, set: ['REST', 'WALKING'] }, { op: 'fresh', a: 0, ms: 5 }] }] } });
    const m = X.exprMeters(plan);
    eq(m.nodes, 5); eq(m.byId.e1, { nodes: 5, cost: 1 + 1 + 1 + 3 + 2 });
    eq(X.exprMeters('not json'), { nodes: 0, byId: {}, terms: 0, stateBytes: 0 });
    eq(X.exprMeters(JSON.stringify({ exprs: [{ id: 'e1', s: [{ op: 'sig', name: 'ctx' }] }] })).nodes, 0, 'a top-level exprs is not the compiled shape');
    let ed = open({ schema: 2, id: 'p', name: 'p', nodes: [{ id: 'e1', type: 'expr.guard', title: 'g', params: { graph: X.defaultGraph() } }], edges: [] }, 1, {}, [], {});
    ed = compileStart(ed);
    ed = applyCompile(ed, ed.compile.rev, { ok: false, planJson: plan, problems: ['node e1: EXPR_PORT: step fast: port over.b is not wired', 'node other: something'], warnings: ['node e1: EXPR_GUARD_READS_CTX: step fast: reads ctx'] });
    const p = exprPanel(ed, 'e1', X.defaultGraph());
    eq(p.compiled, true);
    eq(p.problems.steps.fast.map((x) => x.code), ['EXPR_PORT']);
    eq(p.problems.general, []);
    eq(p.warnings.steps.fast.map((x) => x.code), ['EXPR_GUARD_READS_CTX']);
    eq(p.meters.nodes, 5); eq(p.meters.cost, 8); eq(p.meters.maxNodes, 32); eq(p.meters.maxBytes, 8192);
    eq(X.metersText({ nodes: 3, maxNodes: 32, cost: 3, maxCost: 64, bytes: 120, maxBytes: 8192 }), 'nodes 3/32 · cost 3/64 · 120 of 8192 bytes');
    eq(exprPanel(open({ schema: 2, id: 'p', name: 'p', nodes: [], edges: [] }, 1, {}, [], {}), 'e1', X.defaultGraph()).compiled, false);
  },
};

function withWiredStepInput() {
  let g = two();
  g = X.addNode(g, 'still', 'not').graph;
  g = X.setWire(g, 'still', 'not.a', 'fast').graph;
  return g;
}
