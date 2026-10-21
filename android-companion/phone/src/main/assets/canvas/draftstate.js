// The editor's single draft: revisions, stale-validation filtering and the save-and-freeze lock. Pure.
import { applyChange } from './flowadapter.js';

export function init(pipe, serverRev) {
  return { pipe, rev: 0, savedRev: serverRev, locked: false, layout: null, problems: [] };
}

/** → {state, error?}; refused (state unchanged) while locked or when the change fails. */
export function change(state, chg, nodeTypes) {
  if (state.locked) return { state, error: 'Saving. Try again in a moment.' };
  const r = applyChange(state.pipe, chg, nodeTypes);
  if (r.error) return { state, error: r.error };
  return { state: Object.assign({}, state, { pipe: r.pipe, rev: state.rev + 1 }) };
}

/** What to send alongside the draft with POST /api/validate. */
export function validationSent(state) { return { rev: state.rev }; }

/** Applied only when the reply was for the current revision; an older reply leaves the state untouched. */
export function validationReply(state, reply) {
  if (!reply || reply.rev !== state.rev) return state;
  return Object.assign({}, state, { layout: reply.layout == null ? null : reply.layout, problems: reply.problems || [] });
}

export function lock(state) { return Object.assign({}, state, { locked: true }); }

/** savedRev: the draftRev the save returned (undefined keeps the old one: the save failed). */
export function unlock(state, savedRev) {
  return Object.assign({}, state, { locked: false, savedRev: savedRev === undefined ? state.savedRev : savedRev });
}
