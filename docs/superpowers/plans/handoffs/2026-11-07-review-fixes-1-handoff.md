# Review fixes 1 (2026-11-07), branch feat/v099-watch

Inputs: the Codex review (`codex-r2-A.md`, findings 1-6; 7-8 are LiveFit, out of scope here) and the Opus summary. Every finding was checked against the code first; all were correct.
Frozen values untouched: PROTOCOL_VERSION 1, TRACE_SCHEMA 1, plan hash f5782e0d8610, Room v15 (no schema change: the new store fields are SharedPreferences).

## 1. AutoStop never re-wakes (phone/hub)

- `AutoStopController.evaluate()`: after `stopHub()` it returns `wakeDelayMs(wakeStep())` (and hands it to `scheduleWake`), so a link that was already OFFLINE (no further emit) no longer parks the loop on a poke.
  A refused wake returns (and schedules) the time to the next rung, which `wakeAttempted` already moved: 60, 120, 240, 240 min. An issued wake returns `BUSY_RECHECK_MS` so the woken hub is looked at even with no link change.
- `AutoStopWake.onProcessStart`: early (rung not yet due) it schedules a one-shot `AutoStopWakeWorker` (unique work `auto-stop-wake`, REPLACE) for the remaining time; a refused start schedules the next rung.
  Exemption, minutes, link permission and running are injectable lambdas (read only after the cheap exits).
- Cold wake probe: the probe flag now lives in `AutoStopStore` (`wakeProbe`/`setWakeProbe`, prefs key `wake_probe`, committed before the start; cleared by a stop, by the glasses seen present, by a refused start and by `userStarted`). The controller reads it, so a hub woken by a cold process gets the 5 min probe.
- Clock set back: `BackgroundPolicy.deadlinePassed(now, deadline, max)` treats a deadline further ahead than the longest step as passed (same spirit as the `lastStart` guard). Used by `wakeDue` (`wake_next_at`, max 240 min), `Backoff.due` (hub restart `next_at`, max 60 min) and `sleepMs`. `markAutoStopped`/`wakeAttempted` now `commit()`.
- `HubGraph` wires `scheduleWake = { AutoStopWake.schedule(app, it) }`.
- Tests: `AutoStopControllerTest` (OFFLINE-already + stop arms the wake with no poke, via the real loop on virtual time; refused ladder 60/120/240/240; clock set back; stored probe gives 5 min), `AutoStopWakeTest` (early start schedules the remaining 30 min; cold wake sets the probe and a new controller probes 5 min; refused cold wake schedules 120 min; clock back; unique WorkManager job; probe persistence). One existing assertion changed: a stop now returns 60 min, not null.

## 2. RetrieveDebug unfenced hits (services/orchestration, services/memory)

- New `ForgetFence.liveIds(db, ids)` (same rule as `allLive`, per id). `RetrieveDebug` takes a required `live` lambda; after the retrieval and the gate/generation re-check it drops every hit whose id is not live (snippet with it), renumbers ranks, then re-checks gate and generation again before returning.
  `CanvasBackendImpl` passes `ForgetFence.liveIds(MemoryGraph.get(app).db, ids)`.
- Test `aForgetCompletingMidRetrievalIsFencedOut`: a real `ForgetService.forgetFact` completes after the retriever returned the id; the result holds only the live hit and no forgotten snippet.

## 3. GlassesWatchdog persistence

- `hubStarted` saves the attempt first (`PrefsHubRestartStore.saveStart` now `commit()`), then calls `startHub()`. `backoff` and `hubBackoff` are `@Volatile`.
- Tests: save-before-start order, and a start that throws (process death) leaves the next watchdog inside the back-off; a clock set back does not block the hub restart ladder.

## 4. tools/quality-eval.py

- (a) `is_needs_review()` counts only `"NEEDS_REVIEW"` (or a legacy `True`); `"OK"` no longer counts. Mock now returns `OK` + `NEEDS_REVIEW` proposal memories; the table check expects 2 (was a silent 3 under the bug); unit asserts added.
- (b) `MAINTENANCE_READS` (`/api/publish`, `/api/publish/history`, `/api/live`) are skipped by default (Canvas says "skipped"); `--include-maintenance-reads` sends them, and a final assertion checks none was sent without it. Verb guard and the single allowed POST unchanged.
  `tools/QUALITY-EVAL.md` documents that the run is then not read-only. `tools/test-quality-eval.sh`: ALL OK.

## Verification

- `./gradlew --no-daemon -Dorg.gradle.workers.max=2 --offline :phone:testDebugUnitTest :services:orchestration:test`: see the commit message / report for the result.
- No device test (no adb). Not done: LiveFit findings 7-8 (other repo).
