# Task 21 report: counterfactual replay, battery line, ReplayService, ReplayRoutes

Worktree pca-s3/a, branch s3a/a, base 30786b43. Commits: 89eaacf0 (capture side), second commit (service, routes, tests).
Gate: `./gradlew --no-daemon --offline` (no gw.sh). After `clean`: `:core:capture:test` 320 tests, 0 failures, 0 skipped (305 + 15 new: 4+1 BatteryLine, 9 CounterfactualReplay, 1 CaptureSimulator); `:services:orchestration:testDebugUnitTest --tests 'com.rokid.context.canvas.plan.*'` 12 tests, 0 failures; `:phone:testDebugUnitTest --tests '*ReplayRoutesTest'` 2 tests green. No adb, no background processes left.

## Built
- `core/capture/.../replay/BatteryLine.kt` (0.433 + 0.0239 * camOpenS/min; hours = 100 / pctPerMin / 60).
- `core/capture/.../replay/CounterfactualReplay.kt`: `run(source: () -> Sequence<TraceEvent>, manifest, candidate?, recordedFusion, candidateFusion, tick)` -> `ReplayReport` (baseline + candidate `ReplayFigures`, unobserved %, base passed/failed, inexact windows and reasons, `shadowOrTrialOnly`, warnings, `toJson()` with `"estimate":true`). Streams; one pass per config.
- `services/orchestration/.../canvas/plan/ReplayService.kt`: Mutex single job, RUNNING persisted, DONE via `commitReplayFenced` (fence captured before start, labelRev and sources recorded at start), CANCELLED on thermal SEVERE / shutdown / Developer mode off / user / failure / fence refusal, `recover()` = `markRunningAborted`. `ReplayTrace` (abstract; `ReplayTrace.of(manifest, ...)` is the production one) and `ReplayTraces` seam for Task 22 to bind to chunk files.
- `phone/.../canvas/api/ReplayRoutes.kt` (NOT registered): POST /api/replays (202), GET list, GET one, POST cancel; 409 busy, 403 developer_mode_off, 404, 400.

## Fix to Task 20 code (needed for counterfactual)
In candidate mode a recorded open at the same instant pre-empted the candidate's own decision, so a candidate replay counted ~1 open. `CaptureSimulator` now keeps recorded opens in `awaiting` in candidate mode too (consumed by a matching decision, expired after 250 ms). New test `aCandidateCountsTheOpensItDecidesWhereARecordedOpenCoincides` (RED by mutation). Base-mode behaviour untouched (all 18 existing sim tests green).

## Rulings for the controller (spec silent)
1. Keep estimate is per OPEN: nearest recorded `gt` decision (by `tMs`) within 2000 ms inclusive decides KEEP/SKIP; earlier one wins a tie; none = KEEP and counted unobserved. "Unobserved opens %" = unobserved / candidate opens.
2. Camera-open seconds per candidate open = mean recorded open duration (ep open to close, after pre-roll), 0.7 s default when none; baseline uses the recorded durations.
3. Per-minute span = pre-roll end to last event.
4. Fusion warning "fusion changes are not replayed" when candidate fusion map != trace's plan fusion map (defaults when none); sets `shadowOrTrialOnly`.
5. Exact class = no candidate (configHash = trace's plan hash or "0"); counterfactual configHash = candidate hash. Multi-trace request: one report per trace inside one result JSON (max 8 traces).
6. A job refused by the fence (wipe, tombstoned/rewritten source, moved labelRev) or that throws is CANCELLED with no result, never DONE or left RUNNING.
7. Approximate replay carried through: every report has "replay is approximate" (with the excluded-window count and reasons when any), "keeps and battery are estimates"; base failures add a disagreement warning.

## Mutation checks (each restored, all RED on the intended test)
BatteryLine: 0.433->0.434, 0.0239->0.0240 (first survived the loose 0.001 tolerance; tightened test `constantsAreExactlyTheMeasuredLine`, then RED), `/60` dropped, `/` -> `*`. Estimator: `<=` -> `<` at 2 s, unobserved not counted as keep, fusion warning off. Service: mutex check removed (oneJobAtATime), `markRunningAborted` removed (abortedAfterRestart), dev-mode cancel removed, thermal cancel removed, sourcesJson `[]` and labelRev 0 (sourcesRecorded), fence-refusal close removed (aWipeDuringTheJobDropsTheResult, movedLabelsDropTheResult). Simulator: candidate `awaiting` removed.

## Concerns
- Service tests use fake `ReplayTrace`s (capture fixtures are not visible to orchestration); the real-trace path is covered by the capture tests. The bit-identical real-session test remains Task 23a.
- `ReplayRoutes` is in :phone (HttpRequest/HttpResponse live there). Candidate over the API supports gate params only (glance overrides not exposed). Thermal status is an injected `Flow<Boolean>`; Task 22 must wire the real source and the developer-mode flow.
- Trace chunk reader (ReplayTraces implementation) is not in this task.
