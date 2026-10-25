# Handoff: hardening B (Photo filters on A/B runs, watch run rows vs the source switch), 2026-10-25

Branch `feat/harden-b` (worktree ~/Desktop/rokid/pca-v095b), from v0.9.5 tip 37f298af (includes hardening A). Two Codex Important findings. No PROTOCOL_VERSION, TRACE_SCHEMA, Room schema, plan, hash or JS change (Default capture f5782e0d8610, `live-extract` 0f331f401992 and the pinned plan bytes are untouched; `jsUnderSizeBudget` stays 4632 because no JS line was added). No new HTTP route: the watch run still has none.

## Built
1. **A/B runs now honour the Photo node settings.** `CanvasBackendImpl.scopedAb(a, b, frameIds) = scoped(a, scoped(b, frameIds))`, used in `estimateAb` AND `startAb`. An A/B run compares both pipelines over ONE frame set (the runner takes one list), so the set is the frames that pass the settings of BOTH arms (source, sinceHours, markedOnly, limit applied one after the other; limit keeps the newest N of what is left). A frame that either filter drops is sent to neither arm. At the defaults on both sides `scoped` returns the ids untouched and reads nothing (as before).
   Typed errors are the ones of a single run, because the confirm token binds the SCOPED list: an empty scoped set is `Estimate.Refused(["no photos"])`; a start whose scoped set differs from the estimate's (a mark added, a frame aged out) or is empty is `IllegalArgumentException("bad_token")` (HTTP invalid token, same as `startRun`).
2. **Watch run rows follow the watch source switch.** Chosen option: tie the rows to the switch (not a reduced serialisation), because the run items are keyed `watch:<atMs>` (the item id is itself a timestamp) and every node writes one row per item, so "no raw per-sample data" would mean changing the item model. New `CanvasDao.deleteWatchRuns()` (an additive query, no schema change): deletes every run that has an item whose frameId starts `watch:` with its items and scores. `ContextHub` got an optional `onWatchOff` callback, called right after `watchRing.clear()` in `stopSource` for `watch_livefit` only; `HubGraph` passes a lambda that calls `deleteWatchRuns()` on the hub's scope. Delete all already wipes every canvas run and item (`WipeDao`), unchanged. The run row itself (`canvas_run`) holds only counts, the pipeline and status, no sample. No log line carries a sample (nothing was added to logs).

## Files
- `phone/.../canvas/api/CanvasBackendImpl.kt` (scopedAb, estimateAb, startAb)
- `services/memory/.../db/CanvasDao.kt` (deleteWatchRuns, watchRunIds)
- `phone/.../context/ContextHub.kt` (onWatchOff), `phone/.../hub/HubGraph.kt` (wiring)
- Tests: `CanvasBackendImplTest` (3 A/B tests), `CanvasDaoTest` (deleteWatchRuns), `WatchRunTest` (a real watch run's rows are found, incl. NO_DATA), `ContextHubTest` (callback fires for the watch source only).

## Per-path scoping table (every entry that selects frames for a run)
| Path | Photo filter | Why |
|---|---|---|
| New run (estimate, start) | scoped (hardening/canvas A, unchanged) | `scoped()` in `estimate` and `startRun` |
| A/B estimate and start | scoped now, both arms, intersection | this fix |
| Live path (`LiveExtract`, `PipelineInterpreter`) | NOT scoped | Frames arrive one capture at a time from the glasses, as they are taken: source is glasses by construction, age is zero, and a frame cannot be marked at that instant, so `markedOnly` and `sinceHours` have no meaning there and applying them would silently turn a published live pipeline off. The user chose to publish it live; the filter is a property of a chosen batch. Decision from the canvas A handoff stands. Human decision below. |
| Replay of an Ask (`AskDebug.replay`) | no pipeline, no Photo node | It re-runs the ANSWER prompt template over the photos that Ask already used (its own recorded recall, gated by `AskDebugPhotos`); there is no `input.photos` node whose settings could apply. |
| Batch runs | no separate entry | A "batch" is `startRun` with many ids (scoped) or the live interpreter's chunk of one live capture (see Live path). No other caller of `runner.start*` exists (grep: only `CanvasBackendImpl`). |
| Moment and Scene runs | not applicable | The pipeline must have exactly one input root; a Moment/Scene pipeline has `input.moments` / `input.scenes` and the validator refuses `input.photos` beside them, so no Photo node settings exist. Their photos come from the Moment resolver, bound by the token (contentRev/contentHash). |
| Watch run | not applicable | no photos at all |

## Mutations (each RED for the right reason, then restored, final run green)
1. A/B skips `scoped()` (`scopedAb` returns `frameIds`): the three A/B tests fail (`arm A since 5 h ... expected:<2> but was:<3>`, run items contain the old frame, the moved-set start is not refused).
2. `deleteWatchRuns` deletes nothing (returns the count only): `CanvasDaoTest.deleteWatchRuns...` and `WatchRunTest.theRowsOfARealWatchRun...` fail (`expected null, but was: CanvasRunEntity`).
3. Hub does not call `onWatchOff`: `ContextHubTest.switchingTheWatchSourceOff...` fails (`expected:<1> but was:<0>`).

## Decisions (for the human)
- A/B uses the INTERSECTION of both arms' filters. If A and B have different Photo settings, the comparison is over frames both accept (the only set that is a fair comparison and never sends a frame a filter excludes). Say so if you would rather refuse A/B when the two settings differ.
- Live path stays unscoped (reason above). If you want `source` honoured live, it is a one-line check on the frame's source in LiveExtract; not done.
- Watch rows are deleted on switch-off, not stored reduced. Consequence: a watch run's result page is gone after the switch is turned off (by design).

## NOT done
- Race: a watch run that is executing at the very moment the source is switched off may write rows after `deleteWatchRuns` ran (the run is pure and lasts milliseconds); those rows would survive until the next switch-off, Delete all or run pruning (the newest 20 runs are kept). Not closed, since closing it needs the hub to reach the runner (cancel + wait).
- Watch rows are not deleted when the source is switched off while the phone process was dead (the callback runs on the live hub; after a restart the source starts OFF/ON from settings with no `stopSource` for a source already off). Rows from before then stay until Delete all or pruning.
- No HTTP route or page for watch runs (unchanged); no install; no Codex review run (credits).

## Merge steps
In ~/Desktop/rokid/pca-int1 (or the current integration worktree): `git merge --no-ff feat/harden-b`. Likely conflict spots: `CanvasBackendImpl.kt` (estimateAb/startAb lines), `ContextHub.kt` constructor (a new last parameter), `HubGraph.kt` (the `ContextHub(` call), the three test files. Then run :services:memory, :services:orchestration, :phone clean tests.
