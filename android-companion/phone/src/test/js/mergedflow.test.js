// The pure merge of several pipelines into one read-only drawing: ids, lanes, colours, cross-pipeline signal links, captured-frames links.
import { buildMerged, parseIds, mergedHref, withCapture, DEFAULT_CAPTURE, PIPELINE_COLOURS } from '../../main/assets/canvas/mergedflow.js';
import { CARD_W, CARD_H, CELL_H } from '../../main/assets/canvas/flowadapter.js';
import { allTypes, capturePipe, livePipe, eq, ok } from './fixtures.js';

const cap = () => ({ id: 'default-capture', name: 'Default capture', pipe: capturePipe() });
const live = () => ({ id: 'live-extract', name: 'Live', pipe: livePipe() });
const sender = () => ({ id: 'snd', name: 'Sender', pipe: { schema: 2, id: 'snd', name: 'Sender', nodes: [{ id: 'h', type: 'hub.context', title: 'Hub', params: {} }, { id: 'out', type: 'signal.out', title: 'Send intent', params: { name: 'intent', domain: 'RT_PHONE' } }, { id: 'lost', type: 'signal.out', title: 'Send nowhere', params: { name: 'nobody', domain: 'RT_PHONE' } }], edges: [{ from: 'h', to: 'out' }] } });
const receiver = () => ({ id: 'rcv', name: 'Receiver', pipe: { schema: 2, id: 'rcv', name: 'Receiver', nodes: [{ id: 'in', type: 'signal.in', title: 'Got intent', params: { name: 'intent', domain: 'RT_GLASSES' } }, { id: 'photos', type: 'input.photos', title: 'Photo', params: {} }], edges: [] } });
const moments = () => ({ id: 'mom', name: 'Moments', pipe: { schema: 1, id: 'mom', name: 'Moments', nodes: [{ id: 'm', type: 'input.moments', title: 'Moments', params: {} }], edges: [] } });
/** The built-in starter: the glasses capture lane and the live extraction in ONE pipeline, joined by capture image -> Photo. */
const starter = () => {
  const c = capturePipe(), l = livePipe();
  return { id: 'starter', name: 'Starter', pipe: { schema: 2, id: 'starter', name: 'Starter', nodes: c.nodes.concat(l.nodes.filter((n) => n.id !== 'photos')), edges: c.edges.concat(l.edges) } };
};
const build = (...e) => buildMerged(e, allTypes);
const node = (m, id) => m.nodes.find((n) => n.id === id);
const overlap = (a, b) => a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h;

export const tests = {
  nodeIdsArePrefixedPerPipelineSoTheyNeverClash() {
    const m = build(cap(), live(), { id: 'live-2', name: 'Live 2', pipe: livePipe() });
    eq(m.nodes.length, capturePipe().nodes.length + 2 * livePipe().nodes.length);
    eq(new Set(m.nodes.map((n) => n.id)).size, m.nodes.length, 'all ids unique although "photos" exists in three pipelines');
    ok(node(m, 'live-extract::photos') && node(m, 'live-2::photos') && node(m, 'default-capture::photos'));
    eq(node(m, 'live-2::photos').localId, 'photos'); eq(node(m, 'live-2::photos').pid, 'live-2');
    for (const e of m.edges) ok(node(m, e.from) && node(m, e.to), 'edge endpoints are prefixed ids: ' + e.from);
    eq(m.edges.filter((e) => e.kind === 'flow' && e.from.startsWith('live-2::')).length, livePipe().edges.length);
  },
  aSinglePipelineWithBothHalvesShowsAllThreeLanesAndTheJoin() {
    const m = build(starter());
    eq(m.lanes.map((l) => l.domain), ['RT_GLASSES', 'RT_PHONE', 'ASYNC']);
    eq(m.nodes.filter((n) => n.domain === 'RT_GLASSES').length > 0 && m.nodes.some((n) => n.id === 'starter::extract' && n.domain === 'ASYNC'), true);
    ok(m.edges.some((e) => e.kind === 'flow' && e.from === 'starter::capture_1' && e.to === 'starter::photos'), 'capture image feeds the Photo input as an ordinary edge');
    ok(!m.edges.some((e) => e.kind === 'frames'), 'no captured-frames link: the join is inside the one pipeline');
    for (const n of m.nodes) { const l = m.lanes.find((x) => x.domain === n.domain); ok(n.x >= l.x && n.x + n.w <= l.x + l.width, n.id + ' lies inside its lane'); }
  },
  nodesAreGroupedInLanesGlassesPhoneAsync() {
    const m = build(cap(), live());
    eq(m.lanes.map((l) => l.domain), ['RT_GLASSES', 'RT_PHONE', 'ASYNC']);
    eq(m.lanes[0].label, 'Glasses (real time)');
    eq(node(m, 'default-capture::head_1').domain, 'RT_GLASSES');
    eq(node(m, 'default-capture::hub_1').domain, 'RT_PHONE');
    eq(node(m, 'live-extract::extract').domain, 'ASYNC');
    for (const n of m.nodes) { const l = m.lanes.find((x) => x.domain === n.domain); ok(n.x >= l.x && n.x + n.w <= l.x + l.width, n.id + ' lies inside its lane'); }
    ok(m.lanes[0].x < m.lanes[1].x && m.lanes[1].x < m.lanes[2].x);
    eq(build(live()).lanes.map((l) => l.domain), ['ASYNC'], 'a single pipeline still shows its lane');
  },
  aSignalDomainParamPutsTheNodeInThatLane() {
    const m = build(sender(), receiver());
    eq(node(m, 'rcv::in').domain, 'RT_GLASSES'); eq(node(m, 'snd::out').domain, 'RT_PHONE');
  },
  eachPipelineHasItsOwnColourAndNamePill() {
    const m = build(cap(), live(), sender());
    eq(m.pipelines.map((p) => p.name), ['Default capture', 'Live', 'Sender']);
    eq(new Set(m.pipelines.map((p) => p.colour)).size, 3);
    for (const n of m.nodes) eq(n.colour, m.pipelines.find((p) => p.id === n.pid).colour);
    eq(node(m, 'live-extract::photos').pill, 'Live');
    ok(PIPELINE_COLOURS.length >= 6);
  },
  aSendSignalLinksToAMatchingReceiveInAnotherPipeline() {
    const m = build(sender(), receiver());
    const s = m.links.filter((l) => l.kind === 'signal');
    eq(s.length, 1);
    eq([s[0].from, s[0].to, s[0].name, s[0].cross], ['snd::out', 'rcv::in', 'intent', true]);
    ok(s[0].text.includes('intent') && s[0].text.includes('Sender') && s[0].text.includes('Receiver'), s[0].text);
    eq(m.signals.find((x) => x.name === 'intent').state, 'bound');
    eq(m.signals.find((x) => x.name === 'nobody').state, 'unreceived', 'a name nobody receives is listed, not linked');
  },
  aSignalBoundInsideOnePipelineIsLinkedButNotCross() {
    const l = build(cap()).links.filter((x) => x.kind === 'signal');
    eq(l.length, 1); eq(l[0].cross, false);
  },
  capturedFramesLinkTheGlassesCaptureToThePhotoAndMomentsInputsOfTheOtherPipelines() {
    const m = build(cap(), live(), moments());
    const f = m.links.filter((l) => l.kind === 'frames').map((l) => l.from + '>' + l.to).sort();
    eq(f, ['default-capture::capture_1>live-extract::photos', 'default-capture::capture_1>mom::m']);
    ok(m.links.find((l) => l.kind === 'frames').text.includes('captured frames'));
    eq(m.links.find((l) => l.kind === 'frames').dashed, true);
    ok(!f.some((x) => x.endsWith('default-capture::photos')), 'its own photos input keeps its ordinary edge, no frames link');
  },
  noCaptureOrNoOtherPipelineMeansNoFramesLink() {
    eq(build(live(), moments()).links.length, 0);
    eq(build(cap()).links.filter((l) => l.kind === 'frames').length, 0);
  },
  aMissingPipelineIsReportedAndTheRestIsStillDrawn() {
    const m = build(live(), { id: 'gone', name: 'gone', error: 'Not found.' });
    eq(m.pipelines[1].error, 'Not found.'); eq(m.pipelines[1].nodeCount, 0);
    eq(m.nodes.length, livePipe().nodes.length);
    eq(m.empty, false);
  },
  anEmptyOrAllFailedSelectionDrawsNothing() {
    for (const m of [build(), build({ id: 'x', name: 'x', error: 'boom' })]) { eq(m.nodes, []); eq(m.lanes, []); eq(m.empty, true); }
  },
  noTwoBoxesOverlapAndStackedNodesKeepRoomForLabels() {
    const m = build(cap(), live(), sender(), receiver(), moments());
    for (let i = 0; i < m.nodes.length; i++) for (let j = i + 1; j < m.nodes.length; j++) ok(!overlap(m.nodes[i], m.nodes[j]), m.nodes[i].id + ' overlaps ' + m.nodes[j].id);
    for (const n of m.nodes) { eq(n.w, CARD_W); eq(n.h, CARD_H); }
    for (const e of m.edges) { const a = node(m, e.from), b = node(m, e.to); if (a.x === b.x && b.y > a.y) ok(b.y - (a.y + a.h) >= CELL_H - CARD_H, 'room for the label of ' + e.from); }
  },
  aCompositeStaysACollapsedAmberCard() {
    const m = build(live());
    eq(node(m, 'live-extract::trust').composite, true); eq(node(m, 'live-extract::extract').composite, true); eq(node(m, 'live-extract::critic').composite, true); eq(node(m, 'live-extract::write').composite, true); eq(node(m, 'live-extract::photos').composite, false);
  },
  idsAreParsedDedupedAndTheCaptureShortcutAddsTheBuiltIn() {
    eq(parseIds(''), []); eq(parseIds(null), []); eq(parseIds('a,,b, a ,c'), ['a', 'b', 'c']);
    eq(mergedHref(['a', 'b c']), '#/merged?ids=a,b%20c');
    eq(withCapture('p1'), ['p1', DEFAULT_CAPTURE]); eq(withCapture(DEFAULT_CAPTURE), [DEFAULT_CAPTURE]);
    eq(DEFAULT_CAPTURE, 'default-capture');
  },
};
