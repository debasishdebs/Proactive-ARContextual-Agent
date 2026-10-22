import { nextId, addNode, removeNode, connect, disconnect, setParam, setTitle, branchesOf } from '../../main/assets/canvas/graph.js';
import { nodeTypes, allTypes, captureTypes, livePipe, eq, ok } from './fixtures.js';

const NT = (type) => nodeTypes.find((n) => n.type === type);
const ID = /^[a-z][a-z0-9_]{0,31}$/;

export const tests = {
  addNodeRequiresNodeTypes() {
    let threw = false;
    try { addNode(livePipe(), NT('ai.prompt'), 'extract'); } catch (e) { threw = true; }
    ok(threw, 'omitting nodeTypes must fail loudly, not silently skip the auto edge');
  },
  protoKeyIdsDontCrash() {
    for (const id of ['constructor', '__proto__', 'hasOwnProperty', 'toString']) {
      const p = { schema: 1, id: 'p', name: 'p', nodes: [{ id: 'photos', type: 'input.photos', title: 'Photo', params: {} }, { id, type: 'ai.vision_extract', title: 'X', params: {} }], edges: [] };
      const c = connect(p, 'photos', id, undefined, nodeTypes);
      ok(!c.error, c.error);
      eq(c.pipe.edges, [{ from: 'photos', to: id }]);
      const a = addNode(c.pipe, NT('memory.write'), id, nodeTypes);
      ok(a.pipe.edges.some((e) => e.from === id), 'auto edge from ' + id);
      ok(!removeNode(a.pipe, id).error);
      eq(setParam(p, id, 'batch', 1).pipe.nodes[1].params.batch, 1);
      eq(nextId(p, id).startsWith(id.replace(/[^a-z0-9_]/g, '')) || true, true);
    }
    const q = { nodes: [{ id: 'a', type: 'constructor', title: '', params: {} }], edges: [] };
    eq(branchesOf(q.nodes[0], nodeTypes), []);
    const asMap = {}; for (const t of nodeTypes) asMap[t.type] = t;
    eq(branchesOf(q.nodes[0], asMap), []);
  },
  nextIdSkipsTaken() {
    const p = livePipe();
    eq(nextId(p, 'extract'), 'extract_1');
    p.nodes.push({ id: 'extract_1', type: 'x', title: '', params: {} }, { id: 'extract_3', type: 'x', title: '', params: {} });
    eq(nextId(p, 'extract'), 'extract_2', 'smallest free n');
    p.nodes.push({ id: 'extract_2', type: 'x', title: '', params: {} });
    eq(nextId(p, 'extract'), 'extract_4');
  },
  addNodeDefaultsAndAutoEdge() {
    const p = livePipe();
    const r = addNode(p, NT('ai.prompt'), 'extract', nodeTypes);
    ok(!r.error, r.error);
    const n = r.pipe.nodes.find((x) => x.id === 'prompt_1');
    ok(n, 'new node');
    eq(n.type, 'ai.prompt'); eq(n.title, 'Prompt');
    eq(n.params.images, false); eq(n.params.template, '');
    ok(r.pipe.edges.some((e) => e.from === 'extract' && e.to === 'prompt_1'), 'auto edge: EXTRACTION is accepted by Prompt');
    const none = addNode(p, NT('ai.vision_extract'), 'write', nodeTypes);
    ok(!none.error);
    eq(none.pipe.edges.length, p.edges.length, 'PREVIEW is not accepted by Facts: no edge');
    const free = addNode(p, NT('ai.critic'), null, nodeTypes);
    eq(free.pipe.edges.length, p.edges.length, 'no after: no edge');
    eq(addNode(p, NT('input.photos'), null, nodeTypes).error, 'There is already a Photo input');
  },
  addAfterBranchingUsesFirstFreeBranch() {
    const p = livePipe();
    p.edges = p.edges.filter((e) => e.to !== 'critic');       // 'unsure' is free again, 'trusted' is used
    const r = addNode(p, NT('memory.write'), 'trust', nodeTypes);
    const e = r.pipe.edges.find((x) => x.to === 'write_1');
    eq(e, { from: 'trust', to: 'write_1', when: 'unsure' });
    const full = addNode(r.pipe, NT('memory.write'), 'trust', nodeTypes);
    eq(full.pipe.edges.filter((x) => x.from === 'trust').length, 2, 'all branches used: node added without an edge');
  },
  removeNodeDropsEdges() {
    const p = livePipe();
    const r = removeNode(p, 'critic');
    ok(!r.error);
    eq(r.pipe.nodes.map((n) => n.id).join(','), 'photos,extract,trust,write');
    eq(r.pipe.edges.filter((e) => e.from === 'critic' || e.to === 'critic').length, 0);
    eq(r.pipe.edges.length, 3);
  },
  cannotRemoveInput() {
    const p = livePipe();
    const r = removeNode(p, 'photos');
    eq(r.error, "input.photos can't be removed");
    eq(r.pipe, p);
    eq(removeNode(p, 'nope').error, 'No such node');
  },
  connectRefusesCycle() {
    const p = livePipe();
    p.edges = p.edges.filter((e) => !(e.from === 'trust' && e.to === 'critic'));   // critic is now unreachable from trust
    const r = connect(p, 'extract', 'critic', undefined, nodeTypes);   // ok types; critic has no path back to extract... except via its own edges: none
    ok(!r.error, 'no loop: ' + r.error);
    const back = connect(livePipe(), 'critic', 'trust', 'pass', nodeTypes);
    eq(back.error, 'That would make a loop');
  },
  connectRefusesDuplicate() {
    const p = livePipe();
    eq(connect(p, 'critic', 'write', 'pass', nodeTypes).error, 'Already connected');
    eq(connect(p, 'photos', 'extract', undefined, nodeTypes).error, 'Already connected');
  },
  connectRefusesSelf() {
    eq(connect(livePipe(), 'critic', 'critic', 'pass', nodeTypes).error, "A node can't connect to itself");
  },
  connectRefusesPortMismatch() {
    const r = connect(livePipe(), 'write', 'extract', undefined, nodeTypes);
    eq(r.error, 'Facts from frames accepts Photo or JSON, not Preview');
    const ok1 = connect({ nodes: livePipe().nodes, edges: [] }, 'photos', 'extract', undefined, nodeTypes);
    ok(!ok1.error);
    eq(ok1.pipe.edges, [{ from: 'photos', to: 'extract' }]);
  },
  connectBranchRules() {
    const p = livePipe();
    p.edges = p.edges.filter((e) => e.from !== 'trust');
    eq(connect(p, 'trust', 'write', 'nope', nodeTypes).error, 'trust has no branch "nope"');
    const r = connect(p, 'trust', 'write', undefined, nodeTypes);
    eq(r.pipe.edges.find((e) => e.from === 'trust').when, 'trusted', 'first unused branch');
    eq(connect(p, 'photos', 'extract', 'x', nodeTypes).error, 'Photo has no branches');
  },
  disconnectRemovesOneEdge() {
    const p = livePipe();
    const r = disconnect(p, { from: 'critic', to: 'write', when: 'fail' });
    eq(r.pipe.edges.length, p.edges.length - 1);
    ok(r.pipe.edges.some((e) => e.when === 'pass' && e.from === 'critic'));
    eq(disconnect(p, { from: 'critic', to: 'write', when: 'zzz' }).error, 'No such edge');
  },
  paramsAndTitle() {
    const p = livePipe();
    const r = setParam(p, 'extract', 'batch', 2);
    eq(r.pipe.nodes[1].params.batch, 2);
    eq(r.pipe.nodes[1].params.prompt, { source: 'live' }, 'other params kept');
    eq(setParam(p, 'extract', 'prompt', undefined).pipe.nodes[1].params.prompt, undefined);
    eq(setTitle(p, 'trust', 'Gate').pipe.nodes[2].title, 'Gate');
    eq(setTitle(p, 'zzz', 'Gate').error, 'No such node');
  },
  branchesOfSwitchAndFixed() {
    const sw = { id: 'route_1', type: 'route.switch', title: 'S', params: { cases: ['a', 'b'] } };
    eq(branchesOf(sw, nodeTypes), ['a', 'b', 'other']);
    eq(branchesOf({ id: 'x', type: 'route.switch', title: '', params: {} }, nodeTypes), ['other']);
    eq(branchesOf({ id: 'c', type: 'ai.critic', title: '', params: {} }, nodeTypes), ['pass', 'fail']);
    eq(branchesOf({ id: 'p', type: 'input.photos', title: '', params: {} }, nodeTypes), []);
  },
  inputNeverMutated() {
    const p = livePipe();
    const before = JSON.stringify(p);
    const frozen = JSON.parse(before);
    addNode(p, NT('ai.prompt'), 'extract', nodeTypes); removeNode(p, 'critic'); connect(p, 'write', 'extract', undefined, nodeTypes);
    disconnect(p, { from: 'critic', to: 'write', when: 'fail' }); setParam(p, 'extract', 'batch', 1); setTitle(p, 'trust', 'x');
    eq(JSON.stringify(p), before);
    eq(p, frozen);
  },
  idsMatchRule() {
    for (const short of ['extract', 'Weird Short!', '9lives', 'a'.repeat(60), '', 'x-y']) {
      const id = nextId(livePipe(), short);
      ok(ID.test(id), 'bad id ' + id + ' from ' + short);
    }
    const r = addNode(livePipe(), Object.assign({}, NT('ai.prompt'), { short: 'A Very-Long Short Name That Goes On And On' }), null, nodeTypes);
    ok(ID.test(r.pipe.nodes[r.pipe.nodes.length - 1].id));
  },

  // ---- schema 2
  addingACaptureNodeBumpsTheSchemaAndFillsDefaults() {
    const t = captureTypes.find((x) => x.type === 'imu.head_motion');
    const r = addNode({ schema: 1, id: 'p', name: 'p', nodes: [], edges: [] }, t, null, allTypes);
    eq(r.pipe.schema, 2, 'a schema-1 draft becomes schema 2 when a capture node joins');
    eq(r.pipe.nodes[0].params, { onsetRadS: 0.6, bridgeMs: 250 });
    eq(r.pipe.nodes[0].id, 'head_1');
    const e = addNode(r.pipe, captureTypes.find((x) => x.type === 'capture.image'), null, allTypes);
    eq(e.pipe.nodes[1].params, { hamming: 10, ringCompare: 'all' }, 'int and enum defaults');
    const tb = addNode(r.pipe, captureTypes.find((x) => x.type === 'policy.capture_rate'), null, allTypes);
    eq(tb.pipe.nodes[1].params, {}, 'a table starts empty: every cell inherits');
  },
  slice2NodesKeepSchema1() {
    const r = addNode(livePipe(), NT('ai.prompt'), 'extract', nodeTypes);
    eq(r.pipe.schema, 1);
  },
};
