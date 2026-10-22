import * as F from '../../main/assets/canvas/format.js';
import { eq, ok } from './fixtures.js';

export const tests = {
  tokensCompact() {
    eq(F.tokensCompact(12100), '12.1k');
    eq(F.tokensCompact(12000), '12k');
    eq(F.tokensCompact(950), '950');
    eq(F.tokensCompact(2300), '2.3k');
    eq(F.tokensCompact(null), null);
  },
  usageLine() {
    eq(F.usageLine({ calls: 14, est: 15, promptTokens: 12100, completionTokens: 2300, medianLatencyMs: 2400 }),
      'calls 14 (estimated 15) · tokens 12.1k in / 2.3k out · median 2.4 s');
  },
  nullTokensOmitted() {
    const l = F.usageLine({ calls: 3, est: null, promptTokens: null, completionTokens: null, medianLatencyMs: null });
    eq(l, 'calls 3');
    ok(!l.includes('tokens') && !l.includes('median'));
    eq(F.usageLine(null), '');
    eq(F.usageLine({ calls: 2, est: 2, promptTokens: 500, completionTokens: null, medianLatencyMs: 0 }), 'calls 2 (estimated 2) · tokens 500 in / – out · median 0.0 s');
  },
  agreementPercent() {
    eq(F.agreementPercent(0.8), '80%');
    eq(F.agreementPercent(1), '100%');
    eq(F.agreementPercent(0.666), '67%');
    eq(F.agreementPercent(undefined), '–');
  },
  tokenEstimateText() {
    eq(F.tokenEstimateText({ promptTokens: 12000, completionTokens: 2000, basedOn: 40 }), 'about 12k tokens in, 2k out, from 40 earlier items');
    eq(F.tokenEstimateText(null), 'No token estimate: there are no earlier items to go by.');
  },
  callRangeText() {
    eq(F.callRangeText([4, 6]), 'about 4–6× the model calls of Live extraction');
    eq(F.callRangeText([2.5, 2.5]), 'about 2.5× the model calls of Live extraction');
    eq(F.callRangeText([0, 0]), '');
  },
  modesStateTheHonestCost() {
    eq(F.MODES.map((m) => m.mode), ['prompts', 'shadow', 'live']);
    const by = Object.fromEntries(F.MODES.map((m) => [m.mode, m.text]));
    ok(by.shadow.includes('unmetered') && by.shadow.includes('1 in 4') && by.shadow.includes('2–2.5×'), by.shadow);
    ok(by.live.includes('3 intent calls') && by.live.includes('1–3 extract calls') && by.live.includes('240 s') && by.live.includes('one live fallback'), by.live);
    ok(by.live.includes('confidence check') && by.live.includes('critic') && by.live.includes('save rules'));
  },
  rollbackRaceTexts() {
    eq(F.rollbackProblems(['That publish was already rolled back.']), ['That publish was already rolled back.']);
    eq(F.rollbackProblems(['A newer publish exists. Reload and try again.']), ['A newer publish exists. Reload and try again.']);
    eq(F.rollbackProblems(['something odd from a newer server']), ['The rollback was refused.']);
    eq(F.rollbackProblems([]), ['The rollback was refused.']);
  },
  historyRows() {
    const rows = F.historyRows([
      { id: 5, kind: 'interpreter', mode: 'LIVE', pipelineId: 'p1', version: 2, publishedAt: 1, rolledBackAt: 9, rollbackReason: 'fallback rate' },
      { id: 4, kind: 'prompts', mode: null, pipelineId: 'p1', version: 1, publishedAt: 0, rolledBackAt: null, rollbackReason: null },
    ]);
    eq(rows[0].title, '#5 · interpreter (LIVE) · p1 v2');
    eq([rows[0].rolledBack, rows[0].reason], [true, 'fallback rate']);
    eq(rows[1].title, '#4 · prompts · p1 v1');
    eq([rows[1].rolledBack, rows[1].reason], [false, '']);
  },
  branchesAndCompare() {
    eq(F.branchText({ route: { meeting: 3, desk: 2 } }), ['route: meeting 3, desk 2']);
    eq(F.branchText({}), []);
    const s = F.compareSummary({
      branches: { a: { route: { x: 2 } }, b: { route: { x: 1, y: 1 } } }, agreement: { route: 0.5 }, scoreSums: { extract: { a: 3, b: -1 } },
    });
    eq(s.switches, [{ id: 'route', a: 'x 2', b: 'x 1, y 1', agreement: '50%' }]);
    eq(s.scores, [{ id: 'extract', a: 3, b: -1 }]);
    eq(F.compareSummary({}).switches, []);
    eq(F.compareSummary(null), { switches: [], scores: [] });
  },
};
