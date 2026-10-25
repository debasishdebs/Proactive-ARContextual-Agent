# Handoff: why the mark catch-up made no Moments on the phone, 2026-10-25

Branch `feat/catchup-diag` (worktree `~/Desktop/rokid/pca-v095f`), from the v0.9.5 tip incl. forget gaps. No PROTOCOL_VERSION, TRACE_SCHEMA or Room change. No JS change. No device install.

## Evidence and root cause
Device: four tap marks and three voice marks at 01:50-01:51 were recorded by an older build (no Moment trigger for them); after installing v0.9.4 (Room v13) at about 15:00 and launching, `/api/moments` showed no Moment after 01:28:48 and none with a mark, 80+ minutes later.

By reading and by test, the builder path itself is fine: `CatchUpEndToEndTest.aTapMarkAndAVoiceMarkRecordedBeforeGet...` seeds a real tap (`MarkRecorder.onMark`) and a real voice mark (`applyVoice`, `vm:<startAt>:g`, `clock:receipt`) with NO trigger wired, runs `MomentCadenceWorker` (TestListenableWorkerBuilder) through `MomentTriggers` -> `catchUpMarks` and gets two MARK Moments (tap and voice codes). It passed before any fix. So the marks, `labelSeconds`, the fences and `enabled()` are not the cause. Two defects remain, and the first alone explains the device:

1. **The cadence worker is only scheduled when processing is on (primary cause).** `MomentCadenceWorker` is enqueued only by `OrchestrationScheduler.ensureMomentCadence()`, which is reached only from `scheduleCritic()`, and `MainActivity` calls that only inside `if (deps.pipeline.processingEnabled.value)`. `ForgetStartup` (run on every launch "whatever the pipeline state") scheduled retention only, `ContextApp.onCreate` and the hub service scheduled nothing. A phone with the pipeline paused/off never had the 15-minute job, so no tick, no catch-up, and no log line (matches: no worker run in WorkManager, no Moment). The fresh-install-with-v13 path never got a tick.
2. **The catch-up could be consumed for nothing (latent).** `catchUpMarks` stored its done-marker even when it found no mark seconds, so a tick that ran before marks existed (or an earlier v13 build) blocked it for good. Test red before the fix: `aRunThatFoundNoMarksDoesNotConsumeTheCatchUp`.

I could not read the device state (no adb access, WorkManager buffer only 10 min), so I cannot prove from the device that cause 1 and not cause 2 hit it; both are fixed and the next pass distinguishes them (below).

## Fix (commits: see `git log feat/catchup-diag`)
- `OrchestrationScheduler.ensureBackgroundWork()` = retention + Moment cadence (each in `runCatching`, unique + KEEP, free to repeat). Called from: `scheduleCritic()` (as before), `ForgetStartup.run` (every MainActivity launch, any pipeline state), `ContextApp.onCreate` (every process start, including a service-only start), and `ContextHubService` on its first start (the link-up path). Test: `theCadenceIsScheduledByTheStartPathWhateverThePipelineStateAndOnlyOnce` (red before: 0 work infos).
- `MomentBuilder.catchUpMarks`: the marker is stored only after at least one mark second was found and all chunks were built; an empty scan leaves it unset (the next tick re-scans: one indexed `DISTINCT` query per 15 min on a phone with no marks in 7 days). The marker key is now `mark_moment_catchup_v2_ms`; a device holding the old `mark_moment_catchup_ms` value is caught up once more (idempotent: unchanged seconds write nothing). Test: `aKeyStoredByTheFirstShippedCatchUpDoesNotBlockTheFixedOne`.
- Observability, INFO, tag `PcMoments`, strict scan clean (literals; counts and state names only): worker `cadence start: attempt=N`, `cadence rebuilt: stored= deleted= rejected=`, `cadence end: success|retry|cancelled`, `cadence duration: under 1 s|under 10 s|under 60 s|over 60 s`, `cadence outbox drain failed (Class)`, `cadence derive failed (Class)`, `cadence failed (Class)`; trigger `cadence rebuild skipped: store switch off`, `catch-up failed (Class)`; builder `catch-up skipped: store switch off`, `catch-up skipped: already done`, `catch-up found no mark seconds; will look again`, `catch-up done: scanned= stored= deleted= rejected=`. Test: `theWorkerAndTheCatchUpLogCountsAndStateNamesOnly`.
- Debug route `POST /api/moments/rebuild?day=YYYY-MM-DD` (`phone/.../canvas/api/MomentRoutes.kt`, registered in `CanvasExtras`, builder reached by `MomentTriggers.builder(ctx)`): router auth (bearer, Host) applies; Developer mode only (403 `developer_mode_off`); bad or missing `day` 400 `bad_request`; a call within 10 s of the last accepted one 429 `rate_limited`; one at a time 409 `busy`; failure 500 `internal` (no text). It runs `MomentBuilder.rebuildDay`: the catch-up (no-op once its marker is stored) then the same window rebuild as the cadence over that local day (phone zone), through the same builder, so store switch, tombstones, Forget fences and the unknown-capture-time rule apply (it cannot resurrect a forgotten second). No model call, idempotent, so no confirm token (decision). Answer, counts only: `{"day","catchUp":{"stored","deleted","rejected","done"},"window":{"stored","deleted","rejected"}}`. Call (token from the canvas session): `curl -X POST -H "Authorization: Bearer $TOKEN" "http://<phone>:<port>/api/moments/rebuild?day=2026-10-25"`. Tests: `MomentRoutesTest` (7).

## Files
Orchestration main: `work/OrchestrationScheduler.kt`, `orchestration/forget/ForgetActions.kt` (ForgetStartup), `canvas/moment/{MomentBuilder,MomentBuildTrigger,MomentCadenceWorker}.kt`. Phone main: `ContextApp.kt`, `phone/hub/ContextHubService.kt`, `phone/canvas/CanvasExtras.kt`, `phone/canvas/api/MomentRoutes.kt` (new). Tests (new): orchestration `canvas/moment/CatchUpEndToEndTest.kt`, phone `canvas/api/MomentRoutesTest.kt`.

## Mutations (each red, restored)
Pre-fix state (ForgetStartup scheduling retention only): scheduling test red. Catch-up stores the marker on an empty scan: `aRunThatFoundNoMarksDoesNotConsumeTheCatchUp` and the log test red. Marker key back to the old name: `aKeyStoredByTheFirstShippedCatchUpDoesNotBlockTheFixedOne` red. Route without the Developer mode check: `developerModeOffIs403...` red. Route without the rate limit: `aSecondCallWithinTheInterval...` red. Final run (orchestration, memory, phone, clean): see the final message of the run.

## What the next device pass should show
1. After install and one launch (any pipeline state): within 15 min `pca.log` shows `PcMoments: cadence start: attempt=0`, then `catch-up done: scanned=N stored=N ...` (N around the number of mark seconds of the last 7 days: the 01:50 marks), `cadence rebuilt`, `cadence end: success`. `GET /api/moments?day=2026-10-25` then lists MARK Moments (`type: "MARK"`) at 01:50-01:51. Later ticks show `catch-up skipped: already done`.
2. No `cadence start` at all within 20 min: the job is still not scheduled or the OS does not run it (battery restriction/Doze); then call the rebuild route for the day and read the counts (`catchUp.done`, `window.stored`).
3. `catch-up found no mark seconds; will look again` on every tick while marks exist in the last 7 days means the labels are not in `canvas_label` (a different bug: look at the mark path).
4. `cadence rebuild skipped: store switch off` would mean the kill switch.

## Decisions
- Marker only after a non-empty catch-up; versioned key (one repeat on devices that had the old key, harmless).
- Scheduling in four places (cheap, idempotent) rather than one; the hub service call is on its first start only.
- Rebuild route: no confirm token (no cost, idempotent), 10 s rate limit, day in the phone's zone.
- Window rebuild only reaches the previous and current hour in the cadence; marks older than that are only reached by the catch-up or this route (by day).

## NOT done
- No device or adb check of the real WorkManager state; root cause 1 is from code and tests, not from the device.
- No JS change (the route is curl only). No Codex review. No browser pass.
- A worker `Result.retry()` still has no cap beyond WorkManager's backoff.

## Merge steps
`git merge --no-ff feat/catchup-diag` into the train tip. Shared files: `MomentBuilder.kt`, `MomentBuildTrigger.kt`, `CanvasExtras.kt`, `OrchestrationScheduler.kt`. From android-companion, one Gradle: `./gradlew --no-daemon -Dorg.gradle.workers.max=2 --offline :services:memory:cleanTestDebugUnitTest :services:memory:testDebugUnitTest :services:orchestration:cleanTestDebugUnitTest :services:orchestration:testDebugUnitTest :phone:cleanTestDebugUnitTest :phone:testDebugUnitTest`; rerun flaky tests alone. Ask for "go" and back up the phone before any install.
