import { init, change, validationSent, validationReply, lock, unlock } from '../../main/assets/canvas/draftstate.js';
import { nodeTypes, livePipe, eq, ok } from './fixtures.js';

const edit = (s, key, value) => change(s, { kind: 'param', nodeId: 'extract', key, value }, nodeTypes);

export const tests = {
  initState() {
    const s = init(livePipe(), 7);
    eq([s.rev, s.savedRev, s.locked, s.layout, s.problems], [0, 7, false, null, []]);
  },
  changeBumpsRevOnlyOnSuccess() {
    let s = init(livePipe(), 1);
    let r = edit(s, 'batch', 2);
    ok(!r.error); eq(r.state.rev, 1); eq(r.state.pipe.nodes[1].params.batch, 2);
    eq(s.pipe.nodes[1].params.batch, 3, 'the old state is untouched');
    const bad = change(r.state, { kind: 'remove', nodeId: 'photos' }, nodeTypes);
    eq(bad.error, "input.photos can't be removed"); eq(bad.state.rev, 1); ok(bad.state === r.state);
  },
  staleValidationDropped() {
    let s = init(livePipe(), 1);
    s = edit(s, 'batch', 2).state;
    const sent = validationSent(s);                       // {rev: 1}
    s = edit(s, 'batch', 1).state;                        // rev 2 while the reply is in flight
    const after = validationReply(s, { rev: sent.rev, layout: { layers: 1 }, problems: [{ text: 'old' }] });
    ok(after === s, 'state unchanged');
    eq(after.layout, null);
  },
  reversedRepliesKeepNewest() {
    let s = init(livePipe(), 1);
    s = edit(s, 'batch', 2).state; const first = validationSent(s);
    s = edit(s, 'batch', 1).state; const second = validationSent(s);
    s = validationReply(s, { rev: second.rev, layout: { layers: 2 }, problems: [{ text: 'new' }] });
    s = validationReply(s, { rev: first.rev, layout: { layers: 9 }, problems: [{ text: 'old' }] });   // arrives late
    eq(s.layout, { layers: 2 }); eq(s.problems, [{ text: 'new' }]);
  },
  changeRefusedWhileLocked() {
    let s = lock(init(livePipe(), 1));
    eq(s.locked, true);
    const r = edit(s, 'batch', 2);
    ok(r.error, 'refused'); ok(r.state === s); eq(r.state.rev, 0);
  },
  unlockRecordsSavedRev() {
    let s = edit(init(livePipe(), 1), 'batch', 2).state;
    s = lock(s);
    s = unlock(s, 5);
    eq([s.locked, s.savedRev], [false, 5]);
    eq(edit(s, 'batch', 1).state.rev, 2, 'edits work again');
    eq(unlock(lock(s), undefined).savedRev, 5, 'a failed save keeps the old saved rev');
  },
};
