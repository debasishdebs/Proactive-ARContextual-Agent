import * as E from '../../main/assets/canvas/editorlogic.js';
import { nodeTypes, allTypes, captureTypes, capturePipe, livePipe, liveCells, eq, ok } from './fixtures.js';

const layout = { layers: 5, columns: 1, cells: liveCells };
const open = (o) => E.open(livePipe(), 3, layout, [], o);
const key = (k, extra) => Object.assign({ key: k, tag: 'DIV', editable: false, mod: false, nodeId: null }, extra || {});
const edgeIds = (ed) => ed.ds.pipe.edges.map((e) => e.from + '>' + e.to + '>' + (e.when || ''));
const nodeIds = (ed) => ed.ds.pipe.nodes.map((n) => n.id);
const apply = (ed, chg) => { const r = E.dispatch(ed, chg, nodeTypes); ok(!r.error, r.error); return r.ed; };
const env = { activePrompts: { extract: { template: 'LIVE T', schema: '{"a":1}', version: 4 } } };
/** photos -> extract -> trust, nothing after trust: both branches free */
const small = () => {
  const p = livePipe();
  p.nodes = p.nodes.filter((n) => ['photos', 'extract', 'trust', 'write'].includes(n.id));
  p.edges = [{ from: 'photos', to: 'extract' }, { from: 'extract', to: 'trust' }];
  const cells = {}; for (const n of p.nodes) cells[n.id] = liveCells[n.id];
  return E.open(p, 1, { layers: 5, columns: 1, cells }, []);
};

export const tests = {
  openStartsCleanAndValidated() {
    const ed = open();
    eq(ed.ds.layout, layout); eq(E.isDirty(ed), false); eq(ed.status, ''); eq(ed.ds.savedRev, 3);
    eq(E.open(livePipe(), 3, null, []).ds.layout, null, 'no layout yet');
  },
  dirtyTracksRevAgainstSave() {
    let ed = apply(open(), { kind: 'param', nodeId: 'extract', key: 'batch', value: 2 });
    eq(E.isDirty(ed), true);
    const saved = E.markSaved(ed, 9, ed.ds.rev);
    eq([E.isDirty(saved), saved.ds.savedRev], [false, 9]);
    const again = apply(saved, { kind: 'param', nodeId: 'extract', key: 'batch', value: 1 });
    eq(E.isDirty(again), true);
    const during = E.markSaved(again, 10, saved.ds.rev);          // an edit landed while the save was in flight
    eq([E.isDirty(during), during.ds.savedRev], [true, 10], 'the older save leaves the newer edit unsaved');
  },
  readOnlyRefusesEveryChange() {
    const ed = open({ readOnly: true });
    const r = E.dispatch(ed, { kind: 'param', nodeId: 'extract', key: 'batch', value: 2 }, nodeTypes);
    eq(r.error, 'Built-in pipelines are read-only. Duplicate it to edit.');
    eq([r.ed.status, r.ed.ds.rev, E.isDirty(r.ed)], [r.error, 0, false]);
    eq(E.paletteGroups(ed, nodeTypes), [], 'palette hidden');
  },
  lockedMakesTheFlowReadOnly() {
    const ed = E.lockForSave(open());
    const f = E.flowFor(ed, nodeTypes);
    ok(f.nodes.every((n) => n.deletable === false && n.connectable === false));
    ok(f.edges.every((e) => e.deletable === false));
    const r = E.dispatch(ed, { kind: 'title', nodeId: 'extract', title: 'x' }, nodeTypes);
    eq(r.error, 'Saving. Try again in a moment.');
  },
  addFromPaletteChainsAndSelects() {
    let ed = E.selectNode(open(), 'extract');
    ed = E.addFromPalette(ed, 'memory.write', nodeTypes);
    ok(nodeIds(ed).includes('write_1'));
    ok(edgeIds(ed).includes('extract>write_1>'), 'auto edge from the selected node');
    eq(ed.sel, { nodes: ['write_1'], edges: [] }, 'the new node is selected');
    eq(E.isDirty(ed), true);
  },
  addWithoutSelectionIsUnconnected() {
    const ed = E.addFromPalette(open(), 'memory.write', nodeTypes);
    ok(nodeIds(ed).includes('write_1'));
    ok(!edgeIds(ed).some((e) => e.includes('write_1')));
    eq(E.addFromPalette(open(), 'nope.type', nodeTypes).status, 'Unknown node type');
  },
  removeItemsRemovesOnceAndIgnoresMissing() {
    const ed = open();
    const items = { nodeIds: ['critic'], edgeIds: ['trust>critic>unsure', 'critic>write>pass', 'critic>write>fail'] };
    const a = E.removeItems(ed, items, nodeTypes);
    eq(nodeIds(a), ['photos', 'extract', 'trust', 'write']);
    eq(a.ds.rev, 1, 'one revision: the edges went with the node and are not removed again');
    eq(a.status, '');
    const b = E.removeItems(a, items, nodeTypes);                 // a second press / the second handler
    ok(b === a, 'nothing left to remove: the very same state');
  },
  removeEdgeOnly() {
    const a = E.removeItems(open(), { nodeIds: [], edgeIds: ['critic>write>fail'] }, nodeTypes);
    ok(!edgeIds(a).includes('critic>write>fail')); eq(a.ds.rev, 1);
  },
  removeInputIsRefused() {
    const a = E.removeItems(open(), { nodeIds: ['photos'], edgeIds: [] }, nodeTypes);
    eq(a.status, "input.photos can't be removed"); eq(a.ds.rev, 0); eq(nodeIds(a).length, 5);
  },
  removalPrunesSelection() {
    let ed = E.selectNode(open(), 'critic');
    ed = E.removeItems(ed, { nodeIds: ['critic'], edgeIds: [] }, nodeTypes);
    eq(ed.sel, { nodes: [], edges: [] });
  },
  beforeDeleteKeepsOnlyDeletableAndKnown() {
    const f = E.flowFor(open(), nodeTypes);
    const r = E.deletable({ nodes: f.nodes, edges: f.edges });
    eq(r.nodes.map((n) => n.id), ['extract', 'trust', 'critic', 'write']);
    eq(r.edges.length, 6);
    const ro = E.flowFor(open({ readOnly: true }), nodeTypes);
    eq(E.deletable({ nodes: ro.nodes, edges: ro.edges }), { nodes: [], edges: [] });
  },
  selectionFromFlowChanges() {
    let ed = open();
    ed = E.applySelection(ed, 'node', [{ type: 'select', id: 'trust', selected: true }, { type: 'dimensions', id: 'x' }]);
    eq(ed.sel, { nodes: ['trust'], edges: [] });
    ed = E.applySelection(ed, 'edge', [{ type: 'select', id: 'extract>trust>', selected: true }]);
    eq(ed.sel.edges, ['extract>trust>']);
    ed = E.applySelection(ed, 'node', [{ type: 'select', id: 'trust', selected: false }]);
    eq(ed.sel.nodes, []);
    const same = E.applySelection(ed, 'node', [{ type: 'position', id: 'x' }]);
    ok(same === ed, 'no selection change: the same state');
  },
  connectPlanApplyChooseRefuse() {
    const ed = open();
    eq(E.connectPlan(ed, { source: 'extract', target: 'critic' }, nodeTypes), { kind: 'apply', change: { kind: 'connect', from: 'extract', to: 'critic', when: undefined } });
    eq(E.connectPlan(ed, { source: 'trust', target: 'write' }, nodeTypes), { kind: 'refuse', reason: 'All branches of Confidence check are connected' });
    eq(E.connectPlan(ed, { source: 'write', target: 'extract' }, nodeTypes), { kind: 'refuse', reason: 'Facts from frames accepts Photo or JSON, not Preview' });
    const s = small();
    eq(E.connectPlan(s, { source: 'trust', target: 'write' }, nodeTypes), { kind: 'choose', from: 'trust', to: 'write', branches: ['trusted', 'unsure'], pick: 'trusted' });
    eq(E.connectPlan(s, { source: 'trust', target: 'write', when: 'unsure' }, nodeTypes).kind, 'apply');
  },
  connectToChoosesThenPicks() {
    let ed = E.connectTo(small(), { source: 'trust', target: 'write' }, nodeTypes);
    eq(ed.choose, { from: 'trust', to: 'write', branches: ['trusted', 'unsure'], pick: 'trusted' });
    eq(ed.ds.rev, 0, 'nothing applied before the pick');
    ed = E.pickBranch(ed, 'unsure');
    eq(ed.choose.pick, 'unsure');
    ed = E.confirmChoose(ed, nodeTypes);
    ok(edgeIds(ed).includes('trust>write>unsure')); eq(ed.choose, null);
    const cancelled = E.cancelChoose(E.connectTo(small(), { source: 'trust', target: 'write' }, nodeTypes));
    eq(cancelled.choose, null);
  },
  connectToShowsRefusalAndKeepsDraft() {
    const ed = E.connectTo(open(), { source: 'write', target: 'extract' }, nodeTypes);
    eq(ed.status, 'Facts from frames accepts Photo or JSON, not Preview'); eq(ed.ds.rev, 0);
  },
  refusalFromConnectionStateReason() {
    const bad = { isValid: false, fromNode: { id: 'write' }, toNode: { id: 'extract' }, fromHandle: { type: 'source' } };
    eq(E.refusalFromConnectionState(bad, open().ds.pipe, nodeTypes), 'Facts from frames accepts Photo or JSON, not Preview');
    const rev = { isValid: false, fromNode: { id: 'extract' }, toNode: { id: 'write' }, fromHandle: { type: 'target' } };
    eq(E.refusalFromConnectionState(rev, open().ds.pipe, nodeTypes), 'Facts from frames accepts Photo or JSON, not Preview', 'dragged from a target handle: the ends swap');
    eq(E.refusalFromConnectionState({ isValid: true, fromNode: { id: 'write' }, toNode: { id: 'extract' } }, open().ds.pipe, nodeTypes), '');
    eq(E.refusalFromConnectionState({ isValid: false, fromNode: { id: 'write' }, toNode: null }, open().ds.pipe, nodeTypes), '', 'dropped on the empty pane');
    eq(E.refusalFromConnectionState(null, open().ds.pipe, nodeTypes), '');
  },
  keysIgnoredInInputs() {
    for (const tag of ['INPUT', 'TEXTAREA', 'SELECT']) {
      for (const k of ['Delete', 'Backspace', 'a', 'c', 'ArrowDown']) {
        const r = E.keyDecision(E.selectNode(open(), 'critic'), key(k, { tag, nodeId: 'critic' }), nodeTypes);
        eq([r.handled, r.effect], [false, undefined], tag + ' ' + k);
      }
    }
    eq(E.keyDecision(open(), key('a', { editable: true }), nodeTypes).handled, false, 'contenteditable');
    eq(E.keyDecision(open(), key('a', { mod: true }), nodeTypes).handled, false, 'ctrl/meta/alt shortcuts pass through');
    eq(E.keyDecision(open(), key('Enter', { tag: 'BUTTON' }), nodeTypes).handled, false, 'a button keeps its own Enter');
  },
  deleteOnAFocusedNodeRemovesItOnceAndStopsReactFlow() {
    const r = E.keyDecision(open(), key('Delete', { nodeId: 'critic' }), nodeTypes);
    eq([r.handled, r.stop], [true, true]);
    ok(!nodeIds(r.ed).includes('critic')); eq(r.ed.ds.rev, 1);
    const b = E.keyDecision(open(), key('Backspace', { nodeId: 'critic' }), nodeTypes);
    ok(!nodeIds(b.ed).includes('critic'));
    // React Flow's own handler then reports the same node and its edges: nothing more is removed
    const again = E.removeItems(r.ed, { nodeIds: ['critic'], edgeIds: ['trust>critic>unsure'] }, nodeTypes);
    ok(again === r.ed);
  },
  deleteWithNoFocusedNodeIsLeftToReactFlow() {
    const r = E.keyDecision(open(), key('Delete'), nodeTypes);
    eq([r.handled, r.ed.ds.rev], [false, 0]);
  },
  deleteOnTheInputNodeIsRefusedInline() {
    const r = E.keyDecision(open(), key('Delete', { nodeId: 'photos' }), nodeTypes);
    eq(r.ed.status, "input.photos can't be removed"); eq(r.ed.ds.rev, 0);
  },
  arrowsFocusByLayerAndColumn() {
    const r = E.keyDecision(open(), key('ArrowDown', { nodeId: 'extract' }), nodeTypes);
    eq([r.handled, r.effect], [true, { kind: 'focus', id: 'trust' }]);
    const stay = E.keyDecision(open(), key('ArrowUp', { nodeId: 'photos' }), nodeTypes);
    eq(stay.handled, false, 'at the edge nothing happens, React Flow may pan');
    const sel = E.keyDecision(E.selectNode(open(), 'trust'), key('ArrowDown'), nodeTypes);
    eq(sel.effect, { kind: 'focus', id: 'critic' }, 'the selected node is current when nothing is focused');
  },
  connectModeFlowByKeyboard() {
    let r = E.keyDecision(small(), key('c', { nodeId: 'trust' }), nodeTypes);
    eq([r.handled, r.ed.nav.mode, r.ed.nav.from], [true, 'connecting', 'trust']);
    ok(/Connect mode/.test(r.ed.status));
    r = E.keyDecision(r.ed, key('ArrowDown', { nodeId: 'trust' }), nodeTypes);
    eq([r.ed.nav.target, r.effect], ['write', { kind: 'focus', id: 'write' }]);
    const flow = E.flowFor(r.ed, nodeTypes);
    eq(flow.nodes.find((n) => n.id === 'trust').data.role, 'from');
    eq(flow.nodes.find((n) => n.id === 'write').data.role, 'target');
    r = E.keyDecision(r.ed, key('Enter', { nodeId: 'write' }), nodeTypes);
    eq(r.ed.nav.mode, 'idle');
    eq(r.ed.choose.branches, ['trusted', 'unsure'], 'a branching source asks which branch');
  },
  connectModeAppliesPlainEdge() {
    let r = E.keyDecision(open(), key('c', { nodeId: 'extract' }), nodeTypes);
    r = E.keyDecision(r.ed, key('ArrowDown'), nodeTypes);          // trust
    r = E.keyDecision(r.ed, key('Enter'), nodeTypes);
    eq(r.ed.status, 'Already connected');
  },
  escapeCancelsConnect() {
    let r = E.keyDecision(open(), key('c', { nodeId: 'extract' }), nodeTypes);
    r = E.keyDecision(r.ed, key('Escape'), nodeTypes);
    eq([r.handled, r.ed.nav.mode, r.ed.status], [true, 'idle', 'Connect cancelled.']);
    eq(E.keyDecision(open(), key('c'), nodeTypes).ed.status, 'Focus a node first, then press c.');
  },
  aFocusesThePalette() {
    eq(E.keyDecision(open(), key('a'), nodeTypes).effect, { kind: 'palette' });
  },
  paletteGroupsByCategory() {
    const g = E.paletteGroups(open(), nodeTypes);
    eq(g.map((x) => x.category), ['AI', 'Checks', 'Routing', 'Memory']);
    eq(g[0].items.map((i) => i.type), ['ai.vision_extract', 'ai.critic', 'ai.prompt']);
    ok(!g.some((x) => x.items.some((i) => i.type === 'input.photos')), 'palette:false is left out');
  },
  photoNodeSettingsAreEditableInTheInspector() {
    const ed = E.selectNode(open(), 'photos');
    const m = E.inspectorFor(ed, nodeTypes, env);
    eq(m.fields.map((f) => f.name), ['source', 'sinceHours', 'markedOnly', 'limit']);
    eq(m.fields.map((f) => f.value), ['glasses', 0, false, 50], 'defaults show today\'s behaviour');
    const r = E.inputChange(ed, 'photos', 'source', null, 'all', nodeTypes, env);
    ok(!r.error, r.error);
    eq(r.ed.ds.pipe.nodes.find((n) => n.id === 'photos').params, { source: 'all' }, 'only the changed setting is stored');
    eq(E.inspectorFor(r.ed, nodeTypes, env).fields[0].value, 'all');
    ok(E.inputChange(ed, 'photos', 'limit', null, '60', nodeTypes, env).error, 'above the maximum is refused beside the field');
    ok(E.inputChange(ed, 'photos', 'sinceHours', null, '-1', nodeTypes, env).error);
    eq(E.inputChange(ed, 'photos', 'limit', null, '5', nodeTypes, env).ed.ds.pipe.nodes.find((n) => n.id === 'photos').params, { limit: 5 });
  },
  nearestAboveDrop() {
    const nodes = E.flowFor(open(), nodeTypes).nodes;
    eq(E.nearestAbove(nodes, { x: 40, y: 250 }), 'trust', 'y=250 is between layer 2 (240) and 3 (360): the node above');
    eq(E.nearestAbove(nodes, { x: 40, y: 0 }), null, 'nothing above the first row');
    eq(E.nearestAbove([], { x: 0, y: 99 }), null);
    const two = [{ id: 'l', position: { x: 0, y: 0 } }, { id: 'r', position: { x: 220, y: 0 } }];
    eq(E.nearestAbove(two, { x: 230, y: 100 }), 'r', 'same row: the nearer column');
  },
  armThenTapThePane() {
    let ed = E.arm(open(), 'memory.write', nodeTypes);
    eq(ed.armed, 'memory.write'); ok(/Tap the canvas/.test(ed.status));
    const nodes = E.flowFor(ed, nodeTypes).nodes;
    ed = E.paneClick(ed, { x: 0, y: 130 }, nodes, nodeTypes);      // below layer 1 (extract)
    ok(edgeIds(ed).includes('extract>write_1>'));
    eq(ed.armed, null);
    const plain = E.paneClick(E.selectNode(open(), 'trust'), { x: 0, y: 0 }, nodes, nodeTypes);
    eq(plain.sel, { nodes: [], edges: [] }, 'an unarmed pane tap clears the selection');
  },
  inspectorListsFieldsAndLiveParts() {
    const ed = E.selectNode(open(), 'extract');
    const m = E.inspectorFor(ed, nodeTypes, env);
    eq([m.id, m.title, m.typeLabel, m.removable, m.readOnly], ['extract', 'Facts from frames', 'Facts from frames', true, false]);
    eq(m.fields.map((f) => f.name), ['batch', 'prompt']);
    eq(m.fields[0].min, 1);
    eq(m.fields[1].options.map((o) => o.value), ['live', 'draft']);
    eq(m.fields[1].parts.map((p) => [p.part, p.value, p.readOnly]), [['template', 'LIVE T', true], ['schema', '{"a":1}', true]]);
    eq(E.inspectorFor(open(), nodeTypes, env), null, 'nothing selected');
    eq(E.inspectorFor(E.selectNode(open(), 'photos'), nodeTypes, env).removable, false);
  },
  inspectorForSwitchAndPromptNodes() {
    let ed = E.addFromPalette(open(), 'route.switch', nodeTypes);
    const sw = E.inspectorFor(ed, nodeTypes, env);
    eq(sw.fields.map((f) => [f.name, f.control, f.value]), [['field', 'text', 'activity'], ['cases', 'labels', 'case_1']]);
    ed = E.addFromPalette(open(), 'ai.prompt', nodeTypes);
    const pr = E.inspectorFor(ed, nodeTypes, env);
    eq(pr.fields.map((f) => [f.name, f.control]), [['role', 'select'], ['template', 'textarea'], ['schema', 'textarea'], ['images', 'checkbox']]);
    eq(pr.fields[0].options.map((o) => o.value).includes('VISION_EXTRACT'), true);
    eq(pr.fields[0].options.map((o) => o.value).includes('EMBED'), false);
  },
  inputChangeCoercesAndDispatches() {
    const ed = open();
    let r = E.inputChange(ed, 'extract', 'batch', null, '2', nodeTypes, env);
    eq(r.ed.ds.pipe.nodes[1].params.batch, 2); ok(!r.error);
    r = E.inputChange(ed, 'extract', 'batch', null, '9', nodeTypes, env);
    eq(r.error, 'Must be between 1 and 3'); ok(r.ed === ed, 'a bad value changes nothing');
    r = E.inputChange(ed, 'extract', 'batch', null, 'x', nodeTypes, env);
    eq(r.error, 'Must be a whole number');
    r = E.inputChange(ed, 'nope', 'batch', null, '2', nodeTypes, env);
    eq(r.error, 'No such node');
  },
  inlineDraftPromptPrefillsFromLiveAndEdits() {
    let r = E.inputChange(open(), 'extract', 'prompt', null, 'draft', nodeTypes, env);
    eq(r.ed.ds.pipe.nodes[1].params.prompt, { source: 'draft', template: 'LIVE T', schema: '{"a":1}' });
    let ed = r.ed;
    const m = E.inspectorFor(E.selectNode(ed, 'extract'), nodeTypes, env);
    eq(m.fields[1].parts.map((p) => [p.part, p.readOnly]), [['template', false], ['schema', false]]);
    r = E.inputChange(ed, 'extract', 'prompt', 'template', 'My {{x}} template', nodeTypes, env);
    eq(r.ed.ds.pipe.nodes[1].params.prompt.template, 'My {{x}} template');
    r = E.inputChange(r.ed, 'extract', 'prompt', 'schema', '', nodeTypes, env);
    eq('schema' in r.ed.ds.pipe.nodes[1].params.prompt, false, 'an empty schema clears');
    const bad = E.inputChange(r.ed, 'extract', 'prompt', 'schema', '{nope', nodeTypes, env);
    eq(bad.error, 'Must be valid JSON'); ok(bad.ed === r.ed);
    r = E.inputChange(r.ed, 'extract', 'prompt', null, 'live', nodeTypes, env);
    eq(r.ed.ds.pipe.nodes[1].params.prompt, { source: 'live' });
  },
  titleChange() {
    const r = E.titleChange(open(), 'extract', 'Mine', nodeTypes);
    eq(r.ed.ds.pipe.nodes[1].title, 'Mine'); eq(E.flowFor(r.ed, nodeTypes).nodes[1].data.title, 'Mine');
  },
  checkPayloadUsesTheLiveEditorText() {
    let ed = E.inputChange(open(), 'extract', 'prompt', null, 'draft', nodeTypes, env).ed;
    eq(E.checkPayload(ed, 'extract', nodeTypes, env, {}), { nodeType: 'ai.vision_extract', template: 'LIVE T', schema: '{"a":1}' });
    eq(E.checkPayload(ed, 'extract', nodeTypes, env, { 'prompt.template': 'typed now', 'prompt.schema': '   ' }), { nodeType: 'ai.vision_extract', template: 'typed now', schema: null });
    eq(E.checkPayload(open(), 'extract', nodeTypes, env, {}), { nodeType: 'ai.vision_extract', template: 'LIVE T', schema: '{"a":1}' }, 'a live prompt checks the live text');
    ed = E.addFromPalette(open(), 'ai.prompt', nodeTypes);
    const id = ed.sel.nodes[0];
    eq(E.checkPayload(ed, id, nodeTypes, env, { template: 'free', schema: '{}' }), { nodeType: 'ai.prompt', template: 'free', schema: '{}' });
    eq(E.checkPayload(open(), 'trust', nodeTypes, env, {}), null, 'a node with no prompt has nothing to check');
  },
  problemItemsFocusTargets() {
    let ed = open();
    ed = E.applyReply(ed, { rev: 0, layout, problems: [{ text: 'a', nodeId: 'trust' }, { text: 'b', edge: { from: 'x', to: 'y' } }, 'plain'] });
    eq(E.problemItems(ed), [{ text: 'a', nodeId: 'trust' }, { text: 'b', nodeId: 'x', edgeId: 'x>y>' }, { text: 'plain' }]);
  },
  staleReplyIsIgnoredNewestWins() {
    let ed = apply(open(), { kind: 'param', nodeId: 'extract', key: 'batch', value: 2 });
    const first = E.validationRequest(ed);
    eq(first.rev, 1); eq(JSON.parse(first.json).nodes[1].params.batch, 2);
    ed = apply(ed, { kind: 'param', nodeId: 'extract', key: 'batch', value: 1 });
    const late = E.applyReply(ed, { rev: first.rev, layout: { layers: 9, columns: 1, cells: {} }, problems: [{ text: 'old' }] });
    ok(late === ed, 'old reply dropped');
    const fresh = E.applyReply(ed, { rev: 2, layout: { layers: 2, columns: 1, cells: {} }, problems: [{ text: 'new' }] });
    eq(fresh.ds.problems, [{ text: 'new' }]);
  },
  flowMarksSelectionAndPendingNodes() {
    let ed = E.selectNode(open(), 'trust');
    ed = E.addFromPalette(ed, 'ai.critic', nodeTypes);               // not laid out yet
    const f = E.flowFor(ed, nodeTypes);
    const added = f.nodes.find((n) => n.id === 'critic_1');
    eq([added.data.pending, added.selected], [true, true]);
    eq(f.nodes.find((n) => n.id === 'trust').selected, false);
    eq(f.nodes.find((n) => n.id === 'critic_1').position.y, 5 * 120, 'pending row under the last layer');
  },
  storeNotifiesOnlyOnChange() {
    const st = E.createStore(open());
    let n = 0;
    const off = st.subscribe(() => { n++; });
    st.set(st.get()); eq(n, 0);
    st.update((ed) => E.selectNode(ed, 'trust')); eq(n, 1);
    off(); st.update((ed) => E.selectNode(ed, 'extract')); eq(n, 1, 'unsubscribed');
    eq(st.get().sel.nodes, ['extract']);
  },
  protoKeyNodeIdsDoNotBreakSelectionOrFlow() {
    const p = livePipe();
    p.nodes.push({ id: 'constructor', type: 'memory.write', title: 'C', params: {} });
    const ed = E.selectNode(E.open(p, 1, layout, []), 'constructor');
    eq(E.flowFor(ed, nodeTypes).nodes.find((n) => n.id === 'constructor').selected, true);
    eq(E.inspectorFor(ed, nodeTypes, env).id, 'constructor');
  },
  connectOptionsListOnlyValidTargets() {
    const ed = open();
    eq(E.connectOptions(ed, 'extract', nodeTypes).map((o) => o.id), ['critic', 'write'], 'extract already feeds trust; photos takes no input');
    eq(E.connectOptions(small(), 'trust', nodeTypes).map((o) => o.id), ['write']);
    eq(E.connectOptions(open(), 'write', nodeTypes), [], 'nothing accepts a Preview');
  },
  measuresAreRecordedOnlyWhenTheyChange() {
    const m0 = Object.create(null);
    const m1 = E.recordMeasures(m0, [{ type: 'dimensions', id: 'trust', dimensions: { width: 180, height: 64 } }, { type: 'select', id: 'x', selected: true }]);
    eq(m1.trust, { width: 180, height: 64 }); ok(m1 !== m0);
    ok(E.recordMeasures(m1, [{ type: 'dimensions', id: 'trust', dimensions: { width: 180, height: 64 } }]) === m1, 'same size: same map');
    ok(E.recordMeasures(m1, [{ type: 'position', id: 'trust' }, { type: 'dimensions', id: 'y' }]) === m1);
    eq(E.recordMeasures(m1, [{ type: 'dimensions', id: 'constructor', dimensions: { width: 1, height: 2 } }]).constructor, { width: 1, height: 2 });
  },
  stabilizeReusesUnchangedNodesAndAddsMeasures() {
    const ed = open();
    const m = E.recordMeasures(Object.create(null), [{ type: 'dimensions', id: 'trust', dimensions: { width: 180, height: 64 } }]);
    const a = E.stabilize(null, E.flowFor(ed, nodeTypes), m);
    eq(a.nodes.find((n) => n.id === 'trust').measured, { width: 180, height: 64 });
    const b = E.stabilize(a, E.flowFor(ed, nodeTypes), m);
    ok(b.nodes === a.nodes && b.edges === a.edges, 'nothing changed: the very same arrays');
    const c = E.stabilize(b, E.flowFor(E.selectNode(ed, 'critic'), nodeTypes), m);
    ok(c.nodes !== b.nodes);
    ok(c.nodes.find((n) => n.id === 'trust') === a.nodes.find((n) => n.id === 'trust'), 'an unchanged node keeps its object');
    eq(c.nodes.find((n) => n.id === 'critic').selected, true);
    ok(c.edges === b.edges, 'edges did not change');
  },
  leaveTextOnlyWhenDirty() {
    const ed = open();
    eq(E.leaveText(ed), null);
    const d = apply(ed, { kind: 'param', nodeId: 'extract', key: 'batch', value: 2 });
    ok(/unsaved/i.test(E.leaveText(d)));
    eq(E.leaveText(E.markSaved(d, 4, d.ds.rev)), null, 'saved: nothing to lose');
    ok(/Publish/.test(E.leaveText(d, 'Publishing reloads this page.')), 'a caller can name the action');
  },
  nearestAboveUsesTheCardCentre() {
    const two = [{ id: 'l', position: { x: 0, y: 0 } }, { id: 'r', position: { x: 220, y: 0 } }];
    eq(E.nearestAbove(two, { x: 210, y: 100 }), 'r', 'cards are 180 wide: centres at 90 and 310');
  },

  // ---- schema 2 (capture half)
  paletteGroupsCaptureNodesByCategoryAndHidesUnliveable() {
    const types = allTypes.concat([Object.assign({}, captureTypes[0], { type: 'expr', label: 'Expression', liveable: false, category: 'Thresholds/Filters' })]);
    const g = E.paletteGroups(open(), types);
    const names = g.map((x) => x.category);
    for (const c of ['Sensors', 'Thresholds/Filters', 'Signals/Hub', 'Policy/Frequency', 'Actions/Capture']) ok(names.includes(c), c);
    ok(g.find((x) => x.category === 'Actions/Capture').items.some((it) => it.label === 'Capture image'));
    ok(!JSON.stringify(g).includes('Expression'), 'an expression node (not liveable until 3c) is not offered');
    ok(!JSON.stringify(g).includes('input.photos') && !JSON.stringify(g).includes('"Photo"'), 'the Photo input stays out of the palette');
  },
  paletteOffersThePythonExperimentUnderCodeWithItsHint() {
    const hint = 'Needs the laptop helper and your confirmation on the phone for each run; never published to the glasses.';
    const py = Object.assign({}, captureTypes[0], { type: 'py.fn', label: 'Python (experiment)', short: 'pyfn', inputs: ['JSON'], output: 'JSON', root: false, domain: 'ASYNC', executor: 'PYTHON',
      category: 'Code', liveable: false, hint, params: [{ name: 'source', kind: 'text', label: 'Source', maxLen: 16000 }, { name: 'outSchema', kind: 'text', label: 'Output schema', maxLen: 4000 }] });
    const expr = Object.assign({}, captureTypes[0], { type: 'expr', label: 'Expression', liveable: false, executor: 'EXPR', category: 'Thresholds/Filters' });
    const types = allTypes.concat([py, expr]);
    const g = E.paletteGroups(open(), types);
    const code = g.find((x) => x.category === 'Code');
    ok(code, 'a Code group');
    eq(code.items, [{ type: 'py.fn', label: 'Python (experiment)', hint }]);
    ok(!JSON.stringify(g).includes('Expression'), 'an unliveable non-python type stays hidden');
    ok(g.filter((x) => x.category !== 'Code').every((x) => x.items.every((i) => !i.hint)), 'other items carry no hint');
    // adding it from the palette gives a node with empty source and outSchema (the validator asks for them) and the registry short id
    const ed = E.addFromPalette(E.selectNode(open(), 'extract'), 'py.fn', types);
    const n = ed.ds.pipe.nodes.find((x) => x.type === 'py.fn');
    ok(n, 'py.fn node added'); eq(n.params, { source: '', outSchema: '' }); ok(n.id.startsWith('pyfn'), 'id from short: ' + n.id);
  },
  paletteOffersTheScenesInputUnderInputs() {
    const root = { liveable: false, root: true, domain: 'ASYNC', executor: 'BUILTIN', category: 'Inputs', inputs: [], branches: [], params: [] };
    const scenes = Object.assign({}, captureTypes[0], root, { type: 'input.scenes', label: 'Scenes', short: 'scenes', output: 'SCENES' });
    const moments = Object.assign({}, captureTypes[0], root, { type: 'input.moments', label: 'Moments', short: 'moments', output: 'MOMENTS' });
    const g = E.paletteGroups(open(), allTypes.concat([scenes, moments]));
    eq(g.find((x) => x.category === 'Inputs').items, [{ type: 'input.scenes', label: 'Scenes' }], 'Scenes is offered; the Photo input and (unchanged) the Moments input are not');
    // adding it gives a root node with no params and the registry short id
    const ed = E.addFromPalette(open(), 'input.scenes', allTypes.concat([scenes]));
    const n = ed.ds.pipe.nodes.find((x) => x.type === 'input.scenes');
    ok(n, 'input.scenes node added'); eq(n.params, {}); ok(n.id.startsWith('scenes'), 'id from short: ' + n.id);
  },
  inspectorIsGeneratedFromTheParamSchema() {
    const cap = () => E.open(capturePipe(), 1, { layers: 5, columns: 1, cells: {} }, []);
    let ed = E.selectNode(cap(), 'head_1');
    const m = E.inspectorFor(ed, allTypes, {});
    eq(m.fields.map((f) => [f.name, f.control, f.sweep]), [['onsetRadS', 'number', true], ['bridgeMs', 'number', true]]);
    eq(m.fields[0].value, 0.6, 'the schema default shows');
    ed = E.selectNode(ed, 'rate_1');
    const t = E.inspectorFor(ed, allTypes, {});
    eq(t.fields.map((f) => f.control), ['table']);
    ok(t.fields[0].table.hint.includes('reserved timer photo attempt'));
    eq(E.inspectorFor(E.selectNode(ed, 'thresh_1'), allTypes, {}).fields.map((f) => f.control), ['select', 'select', 'number']);
    eq(E.inspectorFor(E.selectNode(ed, 'hub_1'), allTypes, {}).fields, [], 'the hub has no tunables');
  },
  inputChangeBoundsAndEnum() {
    let ed = E.selectNode(E.open(capturePipe(), 1, null, []), 'thresh_1');
    const bad = E.inputChange(ed, 'thresh_1', 'value', null, '9000', allTypes, {});
    eq(bad.error, 'Must be between 0 and 5000'); eq(bad.ed.ds.rev, 0, 'a refused value changes nothing');
    const good = E.inputChange(ed, 'thresh_1', 'feature', null, 'boutMs', allTypes, {});
    eq(good.ed.ds.pipe.nodes.find((n) => n.id === 'thresh_1').params.feature, 'boutMs');
    eq(E.isDirty(good.ed), true);
    eq(E.inputChange(ed, 'thresh_1', 'feature', null, 'nope', allTypes, {}).error, 'Pick one of the listed options');
  },
  tableChangeWritesAndBlankInherits() {
    let ed = E.open(capturePipe(), 1, null, []);
    let r = E.tableChange(ed, 'rate_1', 'rows', 'REST', 'settledGapS', '60', allTypes);
    ok(!r.error, r.error);
    eq(r.ed.ds.pipe.nodes.find((n) => n.id === 'rate_1').params.rows, { REST: { settledGapS: 60 } });
    r = E.tableChange(r.ed, 'rate_1', 'rows', 'REST', 'settledGapS', '', allTypes);
    eq(r.ed.ds.pipe.nodes.find((n) => n.id === 'rate_1').params, {}, 'blank inherits: the cell, the row and the param are gone');
    const refused = E.tableChange(ed, 'rate_1', 'rows', 'REST', 'settledGapS', '4', allTypes);
    eq([refused.error, refused.ed.ds.rev], ['Must be 0 (inherit) or between 5 and 300', 0]);
    eq(E.tableChange(E.open(capturePipe(), 1, null, [], { readOnly: true }), 'rate_1', 'rows', 'REST', 'settledGapS', '60', allTypes).error, E.READ_ONLY_TEXT);
  },
  flowCarriesLanesAndBindings() {
    const ed = E.open(capturePipe(), 1, { layers: 5, columns: 1, cells: {} }, []);
    const f = E.flowFor(ed, allTypes);
    eq(f.lanes.map((l) => l.domain), ['RT_GLASSES', 'RT_PHONE', 'ASYNC']);
    eq(E.bindingsFor(ed, allTypes).map((b) => b.name), ['ctx', 'photos']);
  },

  // ---- compile (a read-only call: it never saves) and the draft's own save
  compileStartsAtTheCurrentRevAndSendsTheDraft() {
    let ed = apply(E.open(capturePipe(), 1, null, []), { kind: 'param', nodeId: 'thresh_1', key: 'value', value: 0.8 });
    eq(JSON.parse(E.compileRequest(ed).json).nodes[1].params.value, 0.8);
    ed = E.compileStart(ed);
    eq([ed.compile.state, ed.compile.rev], ['running', ed.ds.rev]);
    eq(E.compileView(ed).state, 'running');
    eq(E.compileView(open()), null, 'nothing compiled yet');
  },
  compileReplyIsAttributedToNodes() {
    let ed = E.compileStart(E.open(capturePipe(), 1, { layers: 5, columns: 1, cells: {} }, []));
    const rev = ed.compile.rev;
    ed = E.applyCompile(ed, rev, {
      ok: false, planJson: null, configHash: null, staticCost: { modelCalls: 0, rtOps: 3, cpuClass: 1 },
      problems: ['node thresh_1: gyroPeak threshold must be between 0 and 10', 'pipeline has no capture half'], warnings: ['timer photo gap below the settings profile has no effect'],
    });
    const v = E.compileView(ed);
    eq([v.state, v.ok, v.stale], ['done', false, false]);
    eq(v.problems, [{ text: 'node thresh_1: gyroPeak threshold must be between 0 and 10', nodeId: 'thresh_1' }, { text: 'pipeline has no capture half' }]);
    eq(v.warnings.map((w) => w.text), ['timer photo gap below the settings profile has no effect']);
    const f = E.flowFor(ed, allTypes);
    const n = (id) => f.nodes.find((x) => x.id === id);
    eq(n('thresh_1').data.problems, ['node thresh_1: gyroPeak threshold must be between 0 and 10'], 'the compile problem shows on its node');
    eq(n('rate_1').data.warnings, ['timer photo gap below the settings profile has no effect'], 'the gap warning shows on the capture frequency node');
    eq(n('head_1').data.problems, []);
  },
  compileWarningsNeverBlockAndSayWhatCompiled() {
    let ed = E.compileStart(E.open(capturePipe(), 1, null, []));
    ed = E.applyCompile(ed, ed.compile.rev, { ok: true, planJson: '{"v":1}', configHash: 'abcdef0123456789', staticCost: { modelCalls: 0, rtOps: 3, cpuClass: 1 }, problems: [], warnings: ['timer photo gap below the settings profile has no effect'] });
    const v = E.compileView(ed);
    eq([v.ok, v.problems.length, v.warnings.length], [true, 0, 1]);
    ok(v.summary.includes('7 bytes') && v.summary.includes('abcdef01') && v.summary.includes('3 rt ops'), v.summary);
    ok(!JSON.stringify(v).toLowerCase().includes('clamp'), 'there is no clamped text');
  },
  anEditMakesTheCompileStaleAndHidesItsMarks() {
    let ed = E.compileStart(E.open(capturePipe(), 1, null, []));
    ed = E.applyCompile(ed, ed.compile.rev, { ok: false, planJson: null, problems: ['node thresh_1: x'], warnings: [], staticCost: { modelCalls: 0, rtOps: 0, cpuClass: 0 } });
    ed = apply(ed, { kind: 'param', nodeId: 'thresh_1', key: 'value', value: 0.9 });
    const v = E.compileView(ed);
    eq(v.stale, true); ok(v.note.includes('changed since'), v.note);
    eq(E.flowFor(ed, allTypes).nodes.find((n) => n.id === 'thresh_1').data.problems, [], 'old compile marks are not shown against a changed draft');
  },
  aReplyForAnOlderCompileIsDropped() {
    let ed = E.compileStart(E.open(capturePipe(), 1, null, []));
    const old = ed.compile.rev;
    ed = apply(ed, { kind: 'param', nodeId: 'thresh_1', key: 'value', value: 0.9 });
    ed = E.compileStart(ed);
    const same = E.applyCompile(ed, old, { ok: true, planJson: '{}', configHash: 'h', problems: [], warnings: [], staticCost: {} });
    eq(same, ed, 'the reply for revision ' + old + ' is not the running compile');
    eq(E.compileFailed(ed, old, 'x'), ed);
  },
  compileDoesNotTouchDirtyOrSaved() {
    let ed = apply(E.open(capturePipe(), 4, null, []), { kind: 'param', nodeId: 'thresh_1', key: 'value', value: 0.9 });
    const before = [E.isDirty(ed), ed.ds.savedRev, ed.ds.rev];
    ed = E.applyCompile(E.compileStart(ed), ed.ds.rev, { ok: true, planJson: '{}', configHash: 'h', problems: [], warnings: [], staticCost: {} });
    eq([E.isDirty(ed), ed.ds.savedRev, ed.ds.rev], before, 'compiling is not saving');
    ok(E.compileView(ed).note.includes('unsaved'), 'the view says it compiled unsaved edits');
    const saved = E.markSaved(ed, 5, ed.ds.rev);
    eq(E.compileView(saved).stale, false, 'saving does not change what was compiled');
    eq(E.compileView(saved).note, '');
  },
  readOnlyCanStillCompile() {
    const ed = E.compileStart(E.open(capturePipe(), 1, null, [], { readOnly: true }));
    eq(ed.compile.state, 'running');
  },
};
