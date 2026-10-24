// Composite (black-box) nodes: the collapsed node and its expanded inner flow over the fake DOM (what GraalJS can prove):
// collapse/expand, the inner boxes and wires, exposed params as inputs inside the owning box (defaults shown), edits reported to the host, labels as text.
import { mountComposite, layout } from '../../main/assets/canvas/compositeview.js';
import { findAll, find } from './fake-dom.js';
import { eq, ok, localCriticParams, localCriticInner, visionExtractParams, visionExtractInner, criticInner } from './fixtures.js';
import * as E from './env.js';

const cls = (el, c) => el.classList.contains(c);
const boxes = (root) => findAll(root, (e) => cls(e, 'comp-box'));
const wires = (root) => findAll(root, (e) => cls(e, 'comp-wire'));
const box = (root, id) => find(root, (e) => cls(e, 'comp-box') && e.getAttribute('data-box') === id);
const controls = (el) => findAll(el, (e) => e.tagName === 'input' || e.tagName === 'select');
const toggle = (root) => find(root, (e) => e.tagName === 'button' && cls(e, 'comp-toggle'));

const descriptors = localCriticParams;
const inner = () => JSON.parse(JSON.stringify(localCriticInner));

function mount(over, fn) {
  const env = E.setup(['app']);
  try {
    const root = document.getElementById('app');
    const seen = { params: [], toggles: [] };
    const opts = Object.assign({
      title: 'Confidence check', inner: inner(), descriptors, params: {},
      onParam: (n, raw) => { seen.params.push([n, raw]); return undefined; }, onToggle: (v) => seen.toggles.push(v),
    }, over || {});
    const w = mountComposite(root, opts);
    fn({ root, w, seen });
  } finally { env.uninstall(); }
}

export const tests = {
  collapsedShowsOnlyTheExpandButton() {
    mount({}, ({ root, w }) => {
      eq(boxes(root).length, 0);
      eq(toggle(root).textContent, 'Expand');
      eq(toggle(root).getAttribute('aria-expanded'), 'false');
      eq(toggle(root).getAttribute('aria-label'), 'Expand Confidence check');
      eq(w.state().expanded, false);
    });
  },
  expandDrawsTheInnerBoxesAndWires() {
    mount({}, ({ root, w, seen }) => {
      E.click(toggle(root));
      eq(seen.toggles.join(), 'true');
      eq(w.state().expanded, true);
      eq(boxes(root).length, 6);
      eq(wires(root).length, 5);
      const t = E.textOf(root);
      for (const l of ['Combine confidences', 'Compare', 'trusted', 'unsure', 'value', 'cutoff', 'yes', 'no']) ok(t.includes(l), 'missing ' + l);
      eq(toggle(root).textContent, 'Collapse');
      eq(toggle(root).getAttribute('aria-expanded'), 'true');
    });
  },
  collapseReturnsToTheSingleNode() {
    mount({}, ({ root, w, seen }) => {
      w.toggle(); eq(boxes(root).length, 6);
      E.click(toggle(root));
      eq(boxes(root).length, 0); eq(w.state().expanded, false); eq(seen.toggles.join(), 'true,false');
      w.expand(); w.expand(); eq(seen.toggles.join(), 'true,false,true', 'expand twice notifies once');
      w.toggle(); eq(boxes(root).length, 0, 'toggle() closes an open view (what double click and Enter call)');
      w.toggle(); w.collapse(); eq(boxes(root).length, 0);
    });
  },
  exposedParamsAreInputsInsideTheirOwnBoxWithDefaults() {
    mount({}, ({ root, w }) => {
      w.expand();
      const agg = controls(box(root, 'aggregate'));
      eq(agg.length, 1); eq(agg[0].tagName, 'select'); eq(agg[0].value, 'mean');
      const cmp = controls(box(root, 'compare'));
      eq(cmp.length, 1); eq(cmp[0].value, '>=');
      const cut = controls(box(root, 'cutoff'));
      eq(cut.length, 1); eq(cut[0].value, '0.7'); eq(cut[0].getAttribute('min'), '0'); eq(cut[0].getAttribute('max'), '1');
      ok(cut[0].getAttribute('aria-label').includes('default 0.7'), cut[0].getAttribute('aria-label'));
      for (const id of ['facts', 'trusted', 'unsure']) eq(controls(box(root, id)).length, 0, id + ' is read only');
      ok(cls(box(root, 'facts'), 'read-only') && cls(box(root, 'cutoff'), 'has-params'));
    });
  },
  currentParamsReplaceTheDefaults() {
    mount({ params: { aggregate: 'min', operator: '>', cutoff: 0.5 } }, ({ root, w }) => {
      w.expand();
      eq(controls(box(root, 'aggregate'))[0].value, 'min'); eq(controls(box(root, 'compare'))[0].value, '>'); eq(controls(box(root, 'cutoff'))[0].value, '0.5');
    });
  },
  editingAnExposedParamReportsTheRawValueAndShowsTheHostsAnswer() {
    mount({}, ({ root, w, seen }) => {
      w.expand();
      const c = controls(box(root, 'cutoff'))[0]; c.value = '0.8'; c.dispatch('change');
      eq(JSON.stringify(seen.params), '[["cutoff","0.8"]]');
      const a = controls(box(root, 'aggregate'))[0]; a.value = 'max'; a.dispatch('change');
      eq(seen.params[1][0] + seen.params[1][1], 'aggregatemax');
      w.update({ params: { cutoff: 0.8, aggregate: 'max' } });
      eq(w.state().expanded, true, 'an update keeps the view open');
      eq(controls(box(root, 'cutoff'))[0].value, '0.8'); eq(controls(box(root, 'aggregate'))[0].value, 'max');
    });
  },
  aRefusedValueShowsTheErrorAndKeepsTheOldOne() {
    mount({ onParam: () => 'Must be between 0 and 1' }, ({ root, w }) => {
      w.expand();
      const c = controls(box(root, 'cutoff'))[0]; c.value = '5'; c.dispatch('change');
      ok(E.textOf(root).includes('Must be between 0 and 1'));
      ok(find(root, (e) => e.getAttribute('role') === 'alert'), 'announced');
      eq(controls(box(root, 'cutoff'))[0].value, '0.7', 'not stored, so the default shows again');
      w.update({ params: { cutoff: 0.9 } });
      ok(!E.textOf(root).includes('Must be between'), 'a changed value clears the message');
    });
  },
  readOnlyDisablesEveryInput() {
    mount({ readOnly: true }, ({ root, w }) => {
      w.expand();
      const all = controls(root);
      eq(all.length, 3);
      ok(all.every((e) => e.hasAttribute('disabled')));
    });
  },
  labelsAreTextNotMarkup() {
    const i = inner(); i.nodes[1].label = '<img src=x onerror=alert(1)>';
    mount({ inner: i }, ({ root, w }) => {
      w.expand();
      ok(E.textOf(root).includes('<img src=x onerror=alert(1)>'));
      eq(findAll(root, (e) => e.tagName === 'img').length, 0);
      eq(findAll(root, (e) => 'innerHTML' in e).length, 0);
    });
  },
  layoutPutsSourcesLeftOfBranchesAndSizesBoxesForTheirFields() {
    const L = layout(inner());
    const x = (id) => L.boxes.find((b) => b.id === id).x;
    ok(x('facts') < x('aggregate') && x('aggregate') < x('compare') && x('compare') < x('trusted'));
    eq(x('trusted'), x('unsure'));
    ok(L.boxes.find((b) => b.id === 'aggregate').h > L.boxes.find((b) => b.id === 'facts').h, 'a box with a field is taller');
    eq(L.wires.length, 5);
    const ys = L.boxes.filter((b) => b.x === x('trusted')).sort((a, b) => a.y - b.y);
    ok(ys[0].y + ys[0].h < ys[1].y, 'boxes in a column do not overlap');
  },
  everyExposedParamNamesARealDescriptorAndBox() {
    const i = inner(); const names = descriptors.map((d) => d.name); const ids = i.nodes.map((n) => n.id);
    for (const e of i.exposed) ok(names.includes(e.param) && ids.includes(e.node), e.param);
  },
  // ---- phase B: a composite whose prompt stays in the inspector and whose boxes are mostly read only
  factsFromFramesShowsOnlyTheBatchAsAnInputWithTodaysDefault() {
    mount({ title: 'Facts from frames', inner: JSON.parse(JSON.stringify(visionExtractInner)), descriptors: visionExtractParams }, ({ root, w }) => {
      w.expand();
      eq(boxes(root).length, 6); eq(wires(root).length, 6);
      const all = controls(root);
      eq(all.length, 1, 'only the batch is editable');
      eq(controls(box(root, 'batch'))[0].value, '1'); eq(controls(box(root, 'batch'))[0].getAttribute('max'), '3');
      ok(all[0].getAttribute('aria-label').includes('default 1'));
      eq(controls(box(root, 'prompt')).length, 0, 'the prompt box is read only');
      ok(E.textOf(root).includes('bad batch') && E.textOf(root).includes('Retry one by one'));
    });
  },
  aNoteIsDrawnAsTextBelowTheBoxesAndCountedInTheHeight() {
    const withNote = layout(JSON.parse(JSON.stringify(visionExtractInner)));
    const i = JSON.parse(JSON.stringify(visionExtractInner)); delete i.note;
    ok(withNote.height > layout(i).height, 'the note makes room for itself');
    mount({ inner: JSON.parse(JSON.stringify(visionExtractInner)), descriptors: visionExtractParams }, ({ root, w }) => {
      w.expand();
      ok(E.textOf(root).includes('Read only except the batch size') && E.textOf(root).includes('asked again one frame per call'));
      eq(findAll(root, (e) => cls(e, 'comp-note')).length, 2);
    });
    mount({}, ({ root, w }) => { w.expand(); eq(findAll(root, (e) => cls(e, 'comp-note')).length, 0, 'no note, nothing drawn'); });
  },
  aReadOnlyCompositeDrawsItsStagesWithNoInputAtAll() {
    mount({ title: 'Critic', inner: JSON.parse(JSON.stringify(criticInner)), descriptors: [{ name: 'prompt', kind: 'prompt' }] }, ({ root, w }) => {
      w.expand();
      eq(boxes(root).length, 7); eq(wires(root).length, 8);
      eq(controls(root).length, 0, 'nothing is editable');
      ok(boxes(root).every((b) => cls(b, 'read-only')));
      ok(E.textOf(root).includes('Read only: the critic prompt'));
    });
  },
};
