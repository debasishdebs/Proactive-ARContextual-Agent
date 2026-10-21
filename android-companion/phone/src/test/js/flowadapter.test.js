import { toFlow, canConnect, applyChange, edgeFromId, CELL_W, CELL_H } from '../../main/assets/canvas/flowadapter.js';
import { nodeTypes, livePipe, liveCells, eq, ok } from './fixtures.js';

const layout = { layers: 5, columns: 1, cells: liveCells };
const flow = (pipe, problems, opts, lay) => toFlow(pipe || livePipe(), lay === undefined ? layout : lay, problems || [], nodeTypes, opts || {});
const node = (f, id) => f.nodes.find((n) => n.id === id);

export const tests = {
  positionsFromLayoutCells() {
    const p = livePipe();
    const lay = { layers: 3, columns: 2, cells: Object.assign({}, liveCells, { critic: { layer: 2, column: 1 } }) };
    const f = flow(p, [], {}, lay);
    eq(node(f, 'critic').position, { x: 1 * CELL_W, y: 2 * CELL_H });
    eq(node(f, 'extract').position, { x: 0, y: 1 * CELL_H });
    eq([CELL_W, CELL_H], [220, 120]);
    eq(node(f, 'extract').type, 'card');
    eq(node(f, 'extract').data.title, 'Facts from frames');
    eq(node(f, 'extract').data.typeLabel, 'Facts from frames');
    eq(node(f, 'extract').data.inPorts, ['Photo', 'JSON']);
    eq(node(f, 'extract').data.outPort, 'Facts');
  },
  pendingNodesGetPendingRow() {
    const p = livePipe();
    p.nodes.push({ id: 'zeta_1', type: 'ai.prompt', title: 'Z', params: {} }, { id: 'alpha_1', type: 'ai.prompt', title: 'A', params: {} });
    const f = flow(p);
    eq(node(f, 'alpha_1').position, { x: 0, y: 5 * CELL_H });
    eq(node(f, 'zeta_1').position, { x: CELL_W, y: 5 * CELL_H }, 'id order along the row');
    eq(node(f, 'alpha_1').data.pending, true);
    eq(node(f, 'extract').data.pending, false);
    const none = flow(livePipe(), [], {}, null);
    eq(node(none, 'critic').position, { x: 0, y: 0 }, 'no layout at all: everything pending, layer 0, id order');
    eq(node(none, 'photos').position, { x: 2 * CELL_W, y: 0 });
    eq(node(none, 'photos').data.pending, true);
  },
  problemBadgesPerNodeAndEdge() {
    const f = flow(livePipe(), [
      { text: 'node trust: bad', nodeId: 'trust' },
      { text: 'edge critic->write: x', edge: { from: 'critic', to: 'write', when: 'pass' } },
    ]);
    eq(node(f, 'trust').data.problems, ['node trust: bad']);
    eq(node(f, 'extract').data.problems, []);
    eq(f.edges.find((e) => e.id === 'critic>write>pass').className, 'has-problem');
    eq(f.edges.find((e) => e.id === 'critic>write>fail').className, undefined);
  },
  inputNotDeletable() {
    const f = flow();
    eq(node(f, 'photos').deletable, false);
    eq(node(f, 'extract').deletable, true);
    eq(node(f, 'extract').connectable, true);
  },
  readOnlyDisablesDeleteAndConnect() {
    const f = flow(livePipe(), [], { readOnly: true });
    ok(f.nodes.every((n) => n.deletable === false && n.connectable === false && n.data.readOnly === true));
    ok(f.edges.every((e) => e.deletable === false));
    ok(flow().edges.every((e) => e.deletable === true));
  },
  edgeIdsRoundTrip() {
    const f = flow();
    eq(f.edges.map((e) => e.id), ['photos>extract>', 'extract>trust>', 'trust>write>trusted', 'trust>critic>unsure', 'critic>write>pass', 'critic>write>fail']);
    for (const e of f.edges) {
      const d = edgeFromId(e.id);
      eq(d.from, e.source); eq(d.to, e.target);
      eq(d.when, e.label, 'when is the label');
    }
    eq(edgeFromId('a>b>'), { from: 'a', to: 'b' });
    eq(edgeFromId('a>b>pass'), { from: 'a', to: 'b', when: 'pass' });
  },
  ariaLabelsPresent() {
    const f = flow(livePipe(), [{ text: 't', nodeId: 'trust' }]);
    eq(node(f, 'trust').ariaLabel, 'Confidence check, Local trust check, 1 problem');
    eq(node(f, 'extract').ariaLabel, 'Facts from frames, Facts from frames');
    eq(f.edges.find((e) => e.id === 'trust>critic>unsure').ariaLabel, 'Confidence check to Critic unsure');
    eq(f.edges.find((e) => e.id === 'photos>extract>').ariaLabel, 'Photo to Facts from frames');
  },
  domainDefaultsAsync() {
    const f = flow();
    ok(f.nodes.every((n) => n.data.domain === 'ASYNC'));
    const nt = nodeTypes.map((t) => (t.type === 'ai.critic' ? Object.assign({}, t, { domain: 'SYNC' }) : t));
    eq(toFlow(livePipe(), layout, [], nt, {}).nodes.find((n) => n.id === 'critic').data.domain, 'SYNC');
  },

  canConnectPortMismatchReason() {
    const r = canConnect({ source: 'write', target: 'extract' }, livePipe(), nodeTypes);
    eq(r, { ok: false, reason: 'Facts from frames accepts Photo or JSON, not Preview' });
    eq(canConnect({ source: 'photos', target: 'extract' }, { nodes: livePipe().nodes, edges: [] }, nodeTypes), { ok: true });
  },
  canConnectRefusesCycle() {
    eq(canConnect({ source: 'critic', target: 'trust', when: 'pass' }, livePipe(), nodeTypes), { ok: false, reason: 'That would make a loop' });
    eq(canConnect({ source: 'trust', target: 'trust' }, livePipe(), nodeTypes), { ok: false, reason: "A node can't connect to itself" });
    eq(canConnect({ source: 'critic', target: 'write', when: 'pass' }, livePipe(), nodeTypes), { ok: false, reason: 'Already connected' });
  },
  canConnectRefusesCrossDomain() {
    const nt = nodeTypes.map((t) => (t.type === 'memory.write' ? Object.assign({}, t, { domain: 'SYNC' }) : t));
    const p = { nodes: livePipe().nodes, edges: [] };
    eq(canConnect({ source: 'extract', target: 'write' }, p, nt), { ok: false, reason: 'Save memory is in another domain' });
    eq(canConnect({ source: 'extract', target: 'write' }, p, nodeTypes), { ok: true });
  },

  applyAddUsesShortId() {
    const r = applyChange(livePipe(), { kind: 'add', nodeType: 'ai.prompt', after: 'extract' }, nodeTypes);
    ok(!r.error, r.error);
    ok(r.pipe.nodes.some((n) => n.id === 'prompt_1'));
    ok(r.pipe.edges.some((e) => e.from === 'extract' && e.to === 'prompt_1'));
    const byObj = applyChange(livePipe(), { kind: 'add', nodeType: nodeTypes[4], after: null }, nodeTypes);
    ok(byObj.pipe.nodes.some((n) => n.id === 'prompt_1'), 'a node type object works too');
    ok(applyChange(livePipe(), { kind: 'add', nodeType: 'nope.type', after: null }, nodeTypes).error);
  },
  applyConnectPicksFirstFreeBranch() {
    const p = livePipe();
    p.edges = p.edges.filter((e) => e.from !== 'trust');
    const r = applyChange(p, { kind: 'connect', from: 'trust', to: 'write' }, nodeTypes);
    eq(r.pipe.edges.find((e) => e.from === 'trust'), { from: 'trust', to: 'write', when: 'trusted' });
    const r2 = applyChange(r.pipe, { kind: 'connect', from: 'trust', to: 'critic' }, nodeTypes);
    eq(r2.pipe.edges.filter((e) => e.from === 'trust').map((e) => e.when), ['trusted', 'unsure']);
    eq(applyChange(p, { kind: 'connect', from: 'write', to: 'extract' }, nodeTypes).error, 'Facts from frames accepts Photo or JSON, not Preview');
  },
  applyRemoveDropsEdges() {
    const r = applyChange(livePipe(), { kind: 'remove', nodeId: 'critic' }, nodeTypes);
    eq(r.pipe.edges.length, 3);
    eq(applyChange(livePipe(), { kind: 'remove', nodeId: 'photos' }, nodeTypes).error, "input.photos can't be removed");
  },
  applyRemoveEdge() {
    const r = applyChange(livePipe(), { kind: 'removeEdge', edgeId: 'critic>write>fail' }, nodeTypes);
    eq(r.pipe.edges.length, 5);
    ok(!r.pipe.edges.some((e) => e.when === 'fail'));
    eq(applyChange(livePipe(), { kind: 'removeEdge', edgeId: 'nope>x>' }, nodeTypes).error, 'No such edge');
  },
  applyParamAndTitle() {
    const a = applyChange(livePipe(), { kind: 'param', nodeId: 'extract', key: 'batch', value: 1 }, nodeTypes);
    eq(a.pipe.nodes[1].params.batch, 1);
    const b = applyChange(a.pipe, { kind: 'title', nodeId: 'extract', title: 'Read' }, nodeTypes);
    eq(b.pipe.nodes[1].title, 'Read');
    ok(applyChange(livePipe(), { kind: 'bogus' }, nodeTypes).error);
  },
  applyNeverMutatesDraft() {
    const p = livePipe();
    const before = JSON.stringify(p);
    for (const c of [
      { kind: 'add', nodeType: 'ai.prompt', after: 'extract' }, { kind: 'connect', from: 'extract', to: 'critic' },
      { kind: 'remove', nodeId: 'critic' }, { kind: 'removeEdge', edgeId: 'critic>write>fail' },
      { kind: 'param', nodeId: 'extract', key: 'batch', value: 2 }, { kind: 'title', nodeId: 'extract', title: 'x' },
    ]) applyChange(p, c, nodeTypes);
    eq(JSON.stringify(p), before);
    toFlow(p, layout, [], nodeTypes, {}); canConnect({ source: 'extract', target: 'critic' }, p, nodeTypes);
    eq(JSON.stringify(p), before);
  },
};
