import * as E from '../../main/assets/canvas/editorlogic.js';
import { validateDraft, saveDraft, freezeDraft } from '../../main/assets/canvas/editorflow.js';
import { nodeTypes, livePipe, liveCells, eq, ok } from './fixtures.js';

const layout = { layers: 5, columns: 1, cells: liveCells };
const newStore = () => E.createStore(E.open(livePipe(), 3, layout, []));
const edit = (store, v) => store.update((ed) => E.dispatch(ed, { kind: 'param', nodeId: 'extract', key: 'batch', value: v }, nodeTypes).ed);
/** api(method, path, body) answered from a script of {match, reply|fail}; every call is recorded. */
function fakeApi(script) {
  const calls = [];
  const api = (method, path, body) => {
    calls.push({ method, path, body });
    const s = script.find((x) => x.method === method && path.startsWith(x.path));
    if (!s) return Promise.reject(Object.assign(new Error('unexpected ' + method + ' ' + path), { status: 500 }));
    if (s.hook) s.hook();
    return s.fail ? Promise.reject(Object.assign(new Error(s.fail.message || 'failed'), s.fail)) : Promise.resolve(s.reply);
  };
  api.calls = calls;
  return api;
}

export const tests = {
  async validateSendsTheRevAndAppliesTheReply() {
    const store = newStore(); edit(store, 2);
    const api = fakeApi([{ method: 'POST', path: '/api/validate', reply: { rev: 1, layout: { layers: 1, columns: 1, cells: {} }, problems: [{ text: 'P', nodeId: 'trust' }] } }]);
    const r = await validateDraft(store, api);
    ok(r.ok);
    eq(api.calls[0].body.rev, 1); eq(JSON.parse(api.calls[0].body.json).nodes[1].params.batch, 2);
    eq(store.get().ds.problems, [{ text: 'P', nodeId: 'trust' }]);
  },
  async aReplyForAnOlderRevIsDropped() {
    const store = newStore(); edit(store, 2);
    const api = fakeApi([{ method: 'POST', path: '/api/validate', reply: { rev: 1, layout: { layers: 9, columns: 1, cells: {} }, problems: [{ text: 'old' }] }, hook: () => edit(store, 1) }]);
    await validateDraft(store, api);
    eq(store.get().ds.problems, [], 'the edit made while the call was in flight wins');
    eq(store.get().ds.layout, layout);
  },
  async validateFailureKeepsTheOldLayout() {
    const store = newStore(); edit(store, 2);
    const r = await validateDraft(store, fakeApi([{ method: 'POST', path: '/api/validate', fail: { status: 0, message: 'The phone could not be reached.' } }]));
    ok(!r.ok); eq(store.get().ds.layout, layout);
  },
  async saveSendsTheSavedRevAndMarksClean() {
    const store = newStore(); edit(store, 2);
    const api = fakeApi([{ method: 'PUT', path: '/api/pipelines/live-extract', reply: { draftRev: 4, problems: [] } }]);
    const r = await saveDraft(store, api, 'live-extract');
    eq([r.ok, r.draftRev], [true, 4]);
    eq(api.calls[0].body.draftRev, 3); eq(JSON.parse(api.calls[0].body.json).nodes[1].params.batch, 2);
    eq([E.isDirty(store.get()), store.get().ds.savedRev], [false, 4]);
  },
  async saveKeepsEditsMadeDuringTheCall() {
    const store = newStore(); edit(store, 2);
    const api = fakeApi([{ method: 'PUT', path: '/api/pipelines/p', reply: { draftRev: 4 }, hook: () => edit(store, 1) }]);
    await saveDraft(store, api, 'p');
    eq(E.isDirty(store.get()), true, 'the later edit is still unsaved');
  },
  async saveConflictReportsIt() {
    const store = newStore(); edit(store, 2);
    const r = await saveDraft(store, fakeApi([{ method: 'PUT', path: '/api/pipelines/p', fail: { status: 409, message: 'Changed elsewhere' } }]), 'p');
    eq([r.ok, r.conflict], [false, true]);
    eq([E.isDirty(store.get()), store.get().ds.savedRev], [true, 3]);
  },
  async saveEncodesTheId() {
    const store = newStore(); edit(store, 2);
    const api = fakeApi([{ method: 'PUT', path: '/api/pipelines/a%2Fb', reply: { draftRev: 4 } }]);
    ok((await saveDraft(store, api, 'a/b')).ok);
  },
  async freezeSavesFirstThenFreezesWithTheSavedRev() {
    const store = newStore(); edit(store, 2);
    let lockedDuringSave = null, lockedDuringFreeze = null;
    const api = fakeApi([
      { method: 'PUT', path: '/api/pipelines/p', reply: { draftRev: 5 }, hook: () => { lockedDuringSave = store.get().ds.locked; } },
      { method: 'POST', path: '/api/pipelines/p/freeze', reply: { version: 2 }, hook: () => { lockedDuringFreeze = store.get().ds.locked; } },
    ]);
    const r = await freezeDraft(store, api, 'p', 'my note');
    eq([r.ok, r.version], [true, 2]);
    eq(api.calls.map((c) => c.method + ' ' + c.path), ['PUT /api/pipelines/p', 'POST /api/pipelines/p/freeze']);
    eq(api.calls[1].body, { note: 'my note', draftRev: 5 });
    eq([lockedDuringSave, lockedDuringFreeze], [true, true], 'edits are refused while saving and freezing');
    eq([store.get().ds.locked, store.get().ds.savedRev, E.isDirty(store.get())], [false, 5, false]);
  },
  async freezeOfACleanDraftSkipsTheSave() {
    const store = newStore();
    const api = fakeApi([{ method: 'POST', path: '/api/pipelines/p/freeze', reply: { version: 1 } }]);
    const r = await freezeDraft(store, api, 'p', '');
    ok(r.ok); eq(api.calls.length, 1); eq(api.calls[0].body.draftRev, 3);
  },
  async freezeConflictUnlocksAndShowsTheMessage() {
    const store = newStore(); edit(store, 2);
    const api = fakeApi([
      { method: 'PUT', path: '/api/pipelines/p', reply: { draftRev: 5 } },
      { method: 'POST', path: '/api/pipelines/p/freeze', fail: { status: 409, message: 'The draft changed. Save again.' } },
    ]);
    const r = await freezeDraft(store, api, 'p', 'n');
    eq([r.ok, r.conflict, r.message], [false, true, 'The draft changed. Save again.']);
    eq(store.get().ds.locked, false);
    ok(E.dispatch(store.get(), { kind: 'title', nodeId: 'extract', title: 'x' }, nodeTypes).error === undefined, 'editable again');
  },
  async freezeDoesNotFreezeWhenTheSaveFails() {
    const store = newStore(); edit(store, 2);
    const api = fakeApi([{ method: 'PUT', path: '/api/pipelines/p', fail: { status: 409, message: 'Changed elsewhere' } }]);
    const r = await freezeDraft(store, api, 'p', 'n');
    eq([r.ok, r.conflict], [false, true]);
    eq(api.calls.length, 1); eq(store.get().ds.locked, false); eq(store.get().ds.savedRev, 3);
  },
};
