import { toFlow, canConnect, applyChange, edgeFromId, lanesOf, bindingsOf, CELL_W, CELL_H, LANE_GAP, CARD_W, CARD_H, MIN_GAP } from '../../main/assets/canvas/flowadapter.js';
import { nodeTypes, allTypes, capturePipe, livePipe, liveCells, eq, ok } from './fixtures.js';

const layout = { layers: 5, columns: 1, cells: liveCells };
const flow = (pipe, problems, opts, lay) => toFlow(pipe || livePipe(), lay === undefined ? layout : lay, problems || [], nodeTypes, opts || {});
const node = (f, id) => f.nodes.find((n) => n.id === id);

const boxOf = (n) => ({ id: n.id, x: n.position.x, y: n.position.y, w: n.data.cardW, h: n.data.cardH });
const overlap = (a, b) => a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h;
const noOverlap = (f) => { const bs = f.nodes.map(boxOf); for (let i = 0; i < bs.length; i++) for (let j = i + 1; j < bs.length; j++) ok(!overlap(bs[i], bs[j]), bs[i].id + ' overlaps ' + bs[j].id); };
/** each edge between nodes stacked in a column leaves MIN_GAP of free space (the edge label) between the boxes */
const labelRoom = (f) => { for (const e of f.edges) { const a = boxOf(node(f, e.source)), b = boxOf(node(f, e.target)); if (b.y > a.y) ok(b.y - (a.y + a.h) >= MIN_GAP, e.id + ' has no room for its label: ' + (b.y - (a.y + a.h))); } };

export const tests = {
  layoutConstantsLeaveRoomForLabels() {
    ok(CARD_H + MIN_GAP <= CELL_H, 'a collapsed card plus the label gap must fit its layout cell');
    ok(MIN_GAP >= 24); eq(CARD_W <= CELL_W - 20, true);
  },
  collapsedChainWithACompositeHasNoOverlapAndRoomForLabels() {
    const f = flow(); noOverlap(f); labelRoom(f);
    eq(node(f, 'critic').position.y, 3 * CELL_H);
    eq(node(f, 'trust').data.cardH, CARD_H);
  },
  expandedCompositePushesItsSuccessorsDown() {
    const c = flow(); const f = flow(null, null, { expanded: { trust: true } });
    noOverlap(f); labelRoom(f);
    const h = node(f, 'trust').data.cardH;
    ok(h > CARD_H, 'an expanded composite is taller than the collapsed card');
    for (const id of ['critic', 'write']) eq(node(f, id).position.y, node(c, id).position.y + (node(f, 'trust').position.y + h + MIN_GAP - node(c, 'critic').position.y), id + ' moves down by the extra height');
    for (const id of ['photos', 'extract', 'trust']) eq(node(f, id).position, node(c, id).position, id + ' above or at it does not move');
  },
  collapsingRestoresTheCollapsedPositions() {
    eq(flow(null, null, { expanded: { trust: false } }).nodes.map((n) => n.position), flow().nodes.map((n) => n.position));
  },
  pendingRowSitsBelowAnExpandedComposite() {
    const p = livePipe(); p.nodes.push({ id: 'late', type: 'ai.prompt', title: 'Late', params: {} });
    const f = flow(p, null, { expanded: { trust: true } });
    noOverlap(f);
    ok(node(f, 'late').position.y >= node(f, 'write').position.y + CARD_H, 'pending row is below the last layer');
  },

  onlyACompositeTypeCarriesItsInnerFlowAndParams() {
    const f = flow();
    const c = node(f, 'trust');
    ok(c.data.composite, 'the local critic is composite');
    eq(c.data.composite.inner.nodes.length, 6);
    eq(c.data.composite.descriptors.map((d) => d.name), ['aggregate', 'operator', 'cutoff']);
    eq(c.data.composite.params, {});
    for (const n of f.nodes.filter((x) => x.id !== 'trust')) eq(n.data.composite, null, n.id + ' is plain');
  },

  protoKeyIdsDontCrash() {
    for (const id of ['constructor', '__proto__', 'hasOwnProperty', 'toString']) {
      const pipe = { schema: 1, id: 'p', name: 'p', nodes: [{ id: 'photos', type: 'input.photos', title: 'Photo', params: {} }, { id, type: 'ai.vision_extract', title: 'X', params: {} }], edges: [{ from: 'photos', to: id }] };
      const lay = JSON.parse('{"layers":2,"columns":1,"cells":{"photos":{"layer":0,"column":0},"' + id + '":{"layer":1,"column":0}}}');
      const f = toFlow(pipe, lay, [], nodeTypes, {});
      eq(f.nodes.find((n) => n.id === id).position, { x: 0, y: CELL_H });
      eq(f.nodes.find((n) => n.id === id).data.pending, false);
      eq(f.nodes.find((n) => n.id === id).data.problems, []);
      const g = toFlow(pipe, { layers: 0, columns: 0, cells: {} }, [{ text: 'p', nodeId: id }], nodeTypes, {});
      eq(g.nodes.find((n) => n.id === id).data.problems, ['p']);
      eq(g.nodes.find((n) => n.id === id).data.pending, true);
      eq(g.edges[0].className, undefined);
      const h = toFlow(pipe, lay, [{ text: 'e', edge: { from: 'photos', to: id } }], nodeTypes, {});
      eq(h.edges[0].className, 'has-problem');
      ok(!canConnect({ source: 'photos', target: id }, pipe, nodeTypes).ok, 'duplicate refused');
      const a = applyChange(pipe, { kind: 'param', nodeId: id, key: 'batch', value: 2 }, nodeTypes);
      eq(a.pipe.nodes[1].params.batch, 2);
    }
  },
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

  // ---- schema 2: lanes, the photos binding, warnings
  lanesSplitTheCanvasByDomain() {
    // the server lays all nodes out in layers; each domain gets its own band of columns
    const cells = {
      head_1: { layer: 0, column: 0 }, thresh_1: { layer: 1, column: 0 }, hub_1: { layer: 0, column: 1 }, sigout_1: { layer: 1, column: 1 },
      sigin_1: { layer: 0, column: 2 }, rate_1: { layer: 2, column: 0 }, capture_1: { layer: 3, column: 0 }, photos: { layer: 4, column: 0 },
    };
    const f = toFlow(capturePipe(), { layers: 5, columns: 3, cells }, [], allTypes, {});
    eq(f.lanes.map((l) => l.domain), ['RT_GLASSES', 'RT_PHONE', 'ASYNC']);
    eq(f.lanes.map((l) => l.label), ['Glasses (real time)', 'Phone (real time)', 'Async (phone)']);
    const x = (id) => node(f, id).position.x;
    const lane = (d) => f.lanes.find((l) => l.domain === d);
    for (const n of f.nodes) {
      const l = lane(n.data.domain);
      ok(n.position.x >= l.x && n.position.x + CELL_W <= l.x + l.width, n.id + ' sits inside its lane');
    }
    ok(lane('RT_PHONE').x >= lane('RT_GLASSES').x + lane('RT_GLASSES').width + LANE_GAP - 1, 'lanes do not overlap');
    ok(x('hub_1') > x('rate_1'), 'the phone lane is to the right of the glasses lane');
    eq(node(f, 'capture_1').data.domain, 'RT_GLASSES');
    eq(node(f, 'photos').position.y, 4 * CELL_H, 'rows still come from the layer');
  },
  defaultCaptureHasLanesAndNoAsyncHalf() {
    const n = (id, type, params) => ({ id, type, title: id, params: params || {} });
    const pipe = {
      schema: 2, id: 'default-capture', name: 'Default capture',
      nodes: [n('head', 'imu.head_motion'), n('thresh', 'gate.threshold', { feature: 'gyroPeak', op: '>', value: 0.6 }), n('settle', 'imu.settle'),
        n('novel', 'gate.novelty'), n('rate', 'policy.capture_rate'), n('cap', 'gate.rate_cap'), n('cam', 'capture.image'),
        n('cin', 'signal.in', { name: 'ctx', domain: 'RT_GLASSES' }), n('hub', 'hub.context'), n('cout', 'signal.out', { name: 'ctx', domain: 'RT_PHONE' })],
      edges: [['head', 'thresh'], ['thresh', 'settle'], ['settle', 'novel'], ['novel', 'rate'], ['cin', 'rate'], ['rate', 'cap'], ['cap', 'cam'], ['hub', 'cout']].map(([from, to]) => ({ from, to })),
    };
    const cells = { head: { layer: 0, column: 0 }, thresh: { layer: 1, column: 0 }, settle: { layer: 2, column: 0 }, novel: { layer: 3, column: 0 }, rate: { layer: 4, column: 0 },
      cap: { layer: 5, column: 0 }, cam: { layer: 6, column: 0 }, cin: { layer: 0, column: 1 }, hub: { layer: 0, column: 2 }, cout: { layer: 1, column: 2 } };
    const glasses = (type) => Object.assign({}, allTypes.find((t) => t.type === 'gate.threshold'), { type, params: [] });
    const types = allTypes.concat(['imu.settle', 'gate.novelty', 'gate.rate_cap'].map(glasses)); // the fixture lacks these three
    const f = toFlow(pipe, { layers: 7, columns: 3, cells }, [], types, { readOnly: true });
    eq(f.nodes.length, 10);
    eq(f.edges.length, 8);
    eq(f.lanes.map((l) => l.domain), ['RT_GLASSES', 'RT_PHONE']);
    for (const nd of f.nodes) { const l = f.lanes.find((x) => x.domain === nd.data.domain); ok(nd.position.x >= l.x && nd.position.x + CELL_W <= l.x + l.width, nd.id + ' sits inside its lane'); }
    eq(bindingsOf(pipe, types).length >= 1, true);
  },
  oneDomainKeepsTheSlice2Positions() {
    const f = flow();
    eq(f.lanes, [], 'a single-domain graph has no lanes');
    eq(node(f, 'extract').position, { x: 0, y: CELL_H });
    eq(lanesOf(livePipe(), layout, nodeTypes), []);
  },
  signalNodeLaneComesFromItsDomainParam() {
    const p = capturePipe();
    const f = toFlow(p, { layers: 5, columns: 1, cells: {} }, [], allTypes, {});
    eq(node(f, 'sigin_1').data.domain, 'RT_GLASSES');
    eq(node(f, 'sigout_1').data.domain, 'RT_PHONE');
    const q = capturePipe(); q.nodes.find((n) => n.id === 'sigin_1').params.domain = 'RT_PHONE';
    eq(toFlow(q, { layers: 0, columns: 0, cells: {} }, [], allTypes, {}).nodes.find((n) => n.id === 'sigin_1').data.domain, 'RT_PHONE');
  },
  canConnectAllowsThePhotosBindingAcrossLanes() {
    const p = capturePipe(); p.edges = p.edges.filter((e) => e.to !== 'photos');
    eq(canConnect({ source: 'capture_1', target: 'photos' }, p, allTypes), { ok: true });
    eq(canConnect({ source: 'thresh_1', target: 'photos' }, p, allTypes), { ok: false, reason: 'Photo is in another domain' });
    eq(canConnect({ source: 'hub_1', target: 'rate_1' }, p, allTypes), { ok: false, reason: 'Capture frequency by activity is in another domain' });
    eq(canConnect({ source: 'rate_1', target: 'photos' }, p, allTypes).ok, false);
  },
  warningsShowOnTheirNodeAndCountInTheLabel() {
    const p = capturePipe();
    const lay = { layers: 5, columns: 1, cells: {} };
    const f = toFlow(p, lay, [{ text: 'node thresh_1: bad', nodeId: 'thresh_1' }], allTypes, { warnings: [{ text: 'timer photo gap below the settings profile has no effect', nodeId: 'rate_1' }] });
    eq(node(f, 'rate_1').data.warnings, ['timer photo gap below the settings profile has no effect']);
    eq(node(f, 'rate_1').data.problems, []);
    eq(node(f, 'thresh_1').data.problems, ['node thresh_1: bad']);
    eq(node(f, 'thresh_1').data.warnings, []);
    ok(node(f, 'rate_1').ariaLabel.endsWith(', 1 warning'), node(f, 'rate_1').ariaLabel);
    ok(node(f, 'thresh_1').ariaLabel.endsWith(', 1 problem'));
  },
  bindingsListEachNameOncePerSide() {
    const b = bindingsOf(capturePipe(), allTypes);
    eq(b, [
      { name: 'ctx', kind: 'signal', from: 'sigout_1', to: 'sigin_1', state: 'bound', text: 'ctx: Send signal (phone) to Receive signal (glasses)' },
      { name: 'photos', kind: 'photos', from: 'capture_1', to: 'photos', state: 'bound', text: 'photos: Capture image (glasses) to Photo (async)' },
    ]);
    const p = capturePipe(); p.nodes = p.nodes.filter((n) => n.id !== 'sigin_1'); p.edges = p.edges.filter((e) => e.from !== 'sigin_1' && e.to !== 'sigin_1' && e.to !== 'photos');
    const c = bindingsOf(p, allTypes);
    eq(c.map((x) => [x.name, x.state]), [['ctx', 'unreceived'], ['photos', 'unbound']]);
    ok(c[0].text.includes('nothing receives it'), c[0].text);
    eq(bindingsOf(livePipe(), nodeTypes), [], 'a slice 2 pipeline has no bindings');
  },
};
