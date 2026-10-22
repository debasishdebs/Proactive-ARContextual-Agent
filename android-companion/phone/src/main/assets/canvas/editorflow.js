// The editor's async flows, pure over an injected `api(method, path, body)` and a store from editorlogic.createStore:
// validate (stale replies dropped), save (CAS on the saved draft revision), and save-then-freeze under the edit lock.
import { validationRequest, applyReply, markSaved, lockForSave, unlockAfterSave, isDirty } from './editorlogic.js';

const enc = encodeURIComponent;
const fail = (e) => ({ ok: false, conflict: !!(e && e.status === 409), message: e && e.message ? String(e.message) : 'Request failed.', error: e });

/** POST /api/validate for the current revision; the reply is applied only if it is still the current one. */
export async function validateDraft(store, api) {
  const body = validationRequest(store.get());
  try {
    const reply = await api('POST', '/api/validate', body);
    store.update((ed) => applyReply(ed, reply));
    return { ok: true };
  } catch (e) { return fail(e); } // the old layout and problems stay
}

/** PUT the draft against the saved revision. → {ok, draftRev, problems} | {ok:false, conflict?, message}. */
export async function saveDraft(store, api, id) {
  const ed = store.get();
  try {
    const r = await api('PUT', '/api/pipelines/' + enc(id), { json: JSON.stringify(ed.ds.pipe, null, 2), draftRev: ed.ds.savedRev });
    store.update((cur) => markSaved(cur, r.draftRev, ed.ds.rev));
    return { ok: true, draftRev: r.draftRev, problems: r.problems || [] };
  } catch (e) { return fail(e); }
}

/**
 * Save-and-freeze: lock edits, save when dirty, POST freeze with the revision the save returned, unlock.
 * A stale freeze is a 409 whose message the caller shows. The editor is editable again on every path.
 */
export async function freezeDraft(store, api, id, note) {
  store.update(lockForSave);
  try {
    if (isDirty(store.get())) {
      const s = await saveDraft(store, api, id);
      if (!s.ok) return s;
    }
    const draftRev = store.get().ds.savedRev;
    const r = await api('POST', '/api/pipelines/' + enc(id) + '/freeze', { note, draftRev });
    return { ok: true, version: r.version };
  } catch (e) { return fail(e); } finally {
    store.update((ed) => unlockAfterSave(ed, undefined));
  }
}
