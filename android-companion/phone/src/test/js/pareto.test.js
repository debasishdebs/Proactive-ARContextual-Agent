import * as P from '../../main/assets/canvas/pareto.js';
import { eq, ok } from './fixtures.js';

const report = {
  replayClass: 'COUNTERFACTUAL', estimate: true, minutes: 20, basePassed: 40, baseFailed: 2, inexactWindows: 3, inexactReasons: { CLOCK_GAP: 2, NO_FRAME: 1 }, shadowOrTrialOnly: true,
  unobservedOpensPct: 12.34, warnings: ['fusion changes are not replayed', 'replay is approximate: 3 window(s) excluded'],
  baseline: { estimatedPctPerMin: 0.49, estimatedHoursFromFull: 3.4, opensPerMin: 3.4 }, candidate: { estimatedPctPerMin: 0.5, opensPerMin: 4 },
};

export const tests = {
  hoursFormula() {
    ok(Math.abs(P.hoursFromPct(0.5) - 100 / 0.5 / 60) < 1e-12);
    eq(P.hoursFromPct(0), null);
    eq(P.hoursFromPct(undefined), null);
    eq(P.hoursText({ estimatedPctPerMin: 0.5 }), '3.3 h');               // formula when the server sent no hours
    eq(P.hoursText({ estimatedPctPerMin: 0.5, estimatedHoursFromFull: 9 }), '9 h'); // the server's own figure wins
  },
  replaySummaryCountsWindowsAndExcludesInexact() {
    const s = P.replaySummary(report);
    eq(s.windowsText, '42 windows compared, 3 inexact (excluded) (clock_gap 2, no_frame 1)');
    eq(s.compared, 42); eq(s.inexact, 3);
    eq(s.fusionWarning, true);
    eq(s.baseline, '0.49 %/min · 3.4 h from full · 3.4 opens/min (estimate)');
    eq(s.candidate, '0.5 %/min · 3.3 h from full · 4 opens/min (estimate)');
    eq(s.unobserved, 'unobserved opens: 12.3 %');
    eq(P.replaySummary(Object.assign({}, report, { shadowOrTrialOnly: false })).fusionWarning, false);
    eq(P.replaySummary({ basePassed: 5, baseFailed: 0, inexactWindows: 0 }).windowsText, '5 windows compared, 0 inexact (excluded)');
  },
  replayRequestBuildsTheRouteBody() {
    eq(P.replayRequest({ traceIds: [] }).problem, 'Pick at least one trace.');
    eq(P.replayRequest({ traceIds: ['t1'] }).body, { traceIds: ['t1'] });
    eq(P.replayRequest({ traceIds: ['t1'], configHash: 'ab12', hamming: '5', bigChange: '', ringCompareAll: true, fusion: 'w=0.5, v=2' }).body,
      { traceIds: ['t1'], candidate: { configHash: 'ab12', gate: { hamming: 5, ringCompareAll: true } }, fusion: { w: 0.5, v: 2 } });
    ok(P.replayRequest({ traceIds: ['t1'], hamming: '5' }).problem.includes('config hash'));
    ok(P.replayRequest({ traceIds: ['t1'], configHash: 'ab', hamming: '5.5' }).problem.includes('whole number'));
    ok(P.replayRequest({ traceIds: ['t1'], fusion: 'w' }).problem.includes('key=number'));
    ok(P.replayRequest({ traceIds: ['t1'], fusion: 'w=abc' }).problem.includes('key=number'));
  },
  sweepRequestBuildsValuesAndRanges() {
    eq(P.parseValues('4, 6.5, off, null'), [4, 6.5, 'off', null]);
    const r = P.sweepRequest({ traceIds: ['t1'], mode: 'grid', params: [{ path: 'gate.hamming', values: '4, 6' }, { path: 'glance.x', min: '1', max: '2', step: '0.5' }, { path: '  ' }] });
    eq(r.body, { traceIds: ['t1'], mode: 'grid', params: [{ path: 'gate.hamming', values: [4, 6] }, { path: 'glance.x', min: 1, max: 2, step: 0.5 }] });
    eq(P.sweepRequest({ traceIds: ['t1'], mode: 'random', samples: '8', seed: '7', params: [{ path: 'a', values: '1' }] }).body.seed, 7);
    ok(P.sweepRequest({ traceIds: ['t1'], mode: 'random', samples: '', params: [{ path: 'a', values: '1' }] }).problem.includes('sample'));
    ok(P.sweepRequest({ traceIds: ['t1'], mode: 'grid', params: [{ path: 'a', min: '1', max: '0', step: '1' }] }).problem.includes('step'));
    ok(P.sweepRequest({ traceIds: ['t1'], mode: 'grid', params: [{ path: 'a', min: '1', max: '2', step: '0' }] }).problem.includes('step'));
    ok(P.sweepRequest({ traceIds: ['t1'], mode: 'grid', params: [] }).problem.includes('at least one'));
    ok(P.sweepRequest({ traceIds: ['t1'], mode: 'x', params: [] }).problem.includes('grid'));
    ok(P.sweepRequest({ traceIds: ['t1'], mode: 'grid', params: [{ path: 'a', values: '' }] }).problem.includes('values'));
  },
  nextSortFlipsOrStartsAscending() {
    eq(P.nextSort({ sort: 'index', dir: 'asc' }, 'hours'), { sort: 'hours', dir: 'asc' });
    eq(P.nextSort({ sort: 'hours', dir: 'asc' }, 'hours'), { sort: 'hours', dir: 'desc' });
    eq(P.nextSort({ sort: 'hours', dir: 'desc' }, 'hours'), { sort: 'hours', dir: 'asc' });
    eq(P.sortQuery({ sort: 'hours', dir: 'desc' }), '?sort=hours&dir=desc');
    eq(P.arrow({ sort: 'pct', dir: 'desc' }, 'pct'), ' ▼');
    eq(P.arrow({ sort: 'pct', dir: 'desc' }, 'hours'), '');
    // every sortable column is one the server accepts
    eq(P.COLUMNS.filter((c) => c.key).map((c) => c.key), ['index', 'captures', 'opens', 'pct', 'hours', 'unobserved']);
  },
  sweepRowsKeepServerOrderAndShowHours() {
    const result = { mode: 'grid', minutes: 20, inexactWindows: 4, baseline: { estimatedPctPerMin: 0.49, opensPerMin: 3.4 }, warnings: [],
      points: [{ index: 2, params: { 'gate.hamming': 8 }, capturesPerMin: 1.234, opensPerMin: 3, estimatedPctPerMin: 0.4912, estimatedHoursFromFull: 3.39, unobservedOpensPct: 5 },
        { index: 0, params: { 'gate.hamming': null }, capturesPerMin: 2, opensPerMin: 4, estimatedPctPerMin: 0.5, unobservedOpensPct: 0 }] };
    const rows = P.sweepRows(result);
    eq(rows.map((r) => r.index), ['2', '0']);
    eq(rows[0], { index: '2', params: 'gate.hamming=8', captures: '1.23', opens: '3', pct: '0.491', hours: '3.4 h', unobserved: '5' });
    eq(rows[1].params, 'gate.hamming=unchanged'); eq(rows[1].hours, '3.3 h');
    const s = P.sweepSummary(result);
    eq(s.inexactText, '4 inexact windows (excluded)');
    eq(s.head, 'grid sweep · 2 points · 20 min of trace · estimate');
    eq(s.fusionWarning, false);
    eq(P.sweepSummary({ warnings: ['fusion changes are not replayed'] }).fusionWarning, true);
  },
};
