import * as R from '../../main/assets/canvas/runlogic.js';
import { eq, ok } from './fixtures.js';

const rows = [
  { runId: 'r2', abGroup: 'g1', status: 'QUEUED', startedAt: 20 },
  { runId: 'r1', abGroup: 'g1', status: 'DONE', startedAt: 10 },
  { runId: 'r9', abGroup: null, status: 'DONE', startedAt: 5 },
];

export const tests = {
  isMovingKnowsServerEnumsNumbersAndBooleans() {
    for (const m of ['WALKING', 'walking', 'VEHICLE', 'MOVING', 'HEAD_MOVING', 1, 0.5, true]) ok(R.isMoving(m), String(m));
    for (const m of ['STILL', 'UNKNOWN', 'NONE', 'NOT_WORN', '', null, undefined, 0, false, NaN]) ok(!R.isMoving(m), String(m));
  },
  rollbackPayloadNamesTheNewestPublish() {
    eq(R.rollbackPayload({ stack: [{ id: 7 }, { id: 3 }] }), { expectedId: 7 });
    eq(R.rollbackPayload({ stack: [] }), null);
    eq(R.rollbackPayload(null), null);
    eq(R.rollbackPayload({}), null);
  },
  partnerAndPair() {
    eq(R.partnerOf(rows, { runId: 'r1', abGroup: 'g1' }).runId, 'r2');
    eq(R.partnerOf(rows, { runId: 'r2', abGroup: 'g1' }).runId, 'r1');
    eq(R.partnerOf(rows, { runId: 'r9', abGroup: null }), null);
    eq(R.partnerOf(rows, { runId: 'zz', abGroup: 'gone' }), null);
    eq(R.abPair(rows, 'g1'), { a: 'r1', b: 'r2' });
    eq(R.abPair(rows, 'nope'), null);
  },
  autoOpenOnlyAfterWatchingItRun() {
    const done = { status: 'DONE' };
    ok(R.shouldAutoOpen(true, done, { status: 'DONE' }));
    ok(!R.shouldAutoOpen(false, done, { status: 'DONE' }), 'a finished pair opened from a link is not bounced');
    ok(!R.shouldAutoOpen(true, done, { status: 'QUEUED' }));
    ok(!R.shouldAutoOpen(true, done, { status: 'CANCELLED' }), 'only when both are DONE');
    ok(!R.shouldAutoOpen(true, done, null));
  },
  redrawOnlyWhenSomethingChanged() {
    ok(R.shouldRedraw(null, { status: 'DONE' }), 'first draw');
    ok(!R.shouldRedraw('DONE', { status: 'DONE' }), 'a finished, unchanged run keeps its open details');
    ok(R.shouldRedraw('RUNNING', { status: 'DONE' }), 'status changed');
    ok(R.shouldRedraw('RUNNING', { status: 'RUNNING' }), 'a running run shows progress');
  },
  pollPausesWhileTheTabIsHidden() {
    eq(R.pollAction(false, true), 'wait');
    eq(R.pollAction(true, true), 'pause');
    eq(R.pollAction(true, false), 'stop');
    eq(R.pollAction(false, false), 'stop');
  },
  activeAndKeepPolling() {
    ok(R.isActive('RUNNING') && R.isActive('QUEUED') && R.isActive('running'));
    ok(!R.isActive('DONE') && !R.isActive(undefined));
  },
  retryBacksOffThreeTimesThenStops() {
    eq([0, 1, 2, 3].map((n) => R.retryDelay(n, { status: 0 })), [1000, 2000, 4000, null]);
    eq(R.retryDelay(0, { status: 503 }), 1000);
    eq(R.retryDelay(0, { status: 404 }), null, 'a missing run is not retried');
    eq(R.retryDelay(0, { status: 401 }), null);
  },
  abOptions() {
    const list = [{ id: 'p1', name: 'Live', versions: [{ version: 1 }, { version: 2 }] }, { id: 'p2', name: 'Mine', versions: [] }];
    const o = R.abOptions(list);
    eq(o.map((x) => x.value), ['p1@draft', 'p1@1', 'p1@2', 'p2@draft']);
    eq(R.abOptions(list, 'p1@1').map((x) => x.value), ['p1@draft', 'p1@2', 'p2@draft'], 'the base is not offered against itself');
    eq(R.refKey({ pipelineId: 'p1', version: 1 }), 'p1@1');
    eq(R.refKey({ pipelineId: 'p1' }), 'p1@draft');
    eq(o[1].label, 'Live v1');
    eq(o[0].label, 'Live (draft)');
    eq(R.parseRef('p1@2'), { pipelineId: 'p1', version: 2 });
    eq(R.parseRef('p1@draft'), { pipelineId: 'p1' });
    eq(R.parseRef(''), null);
  },
};
