# Task 24 report: RT param sweeps (core trim)

Worktree pca-s3/a, branch s3a/a, base 9ff34e3e. Gate: `./gradlew --no-daemon --offline -Dorg.gradle.workers.max=2`, `:core:capture:clean :core:capture:test` 343 tests 0 failures 0 skipped (23 new: 7 CandidatePolicySimulatorTest, 10 SweepPlannerTest, 2 SweepObjectivesTest, plus tests already on the branch); `:services:orchestration:testDebugUnitTest --tests 'com.rokid.context.canvas.plan.*'` 25 tests, 0 failures (13 new SweepServiceTest); `:phone:testDebugUnitTest --tests '*SweepRoutesTest' --tests '*ReplayRoutesTest'` 5 tests, 0 failures (3 new). No adb, no background processes left, no stash/pkill.

## Built
- `core/capture/.../sweep/SweepPlanner.kt`: grid or seeded-random expansion, paths `glance.*`, `gate.*`, `policy.<REST|VEHICLE_PASSENGER|VEHICLE_DRIVING|FLIGHT>.capPerMin|timerPhotoGapsMs.{settledMs,quietMs,walkMs,stillCapMs}`; every point is validated by the publish `PlanValidator` (gaps 0 or 5-300 s); sensing keys, fusion, locked rows refused with fixed texts; max 4 params, max 200 configs, duplicates (same config hash) dropped; `candidate(point)`.
- `.../sweep/SweepObjectives.kt`: captures/min, opens/min, cam-open s/min, estimated %/min and hours (BatteryLine), unobserved opens %, totals over traces.
- `CaptureSimulator`/`ReplayCandidate`: new `table` (policy rows) + `ctxBound`; candidate profile composed as a plan does (SETTLE on the recorded tier, row as minima, forRest), row cap replaces the recorded context cap, own cadence (see ruling 3). `PlanValidator.SENSING` made internal.
- `orchestration/.../plan/SweepService.kt` (RUNNING with sourcesJson and labelRev at start; DONE via `commitSweepFenced`; CANCELLED on thermal, shutdown, dev-mode off, user, failure, fence refusal; `recover()` ABORTED), shares the one-job mutex with `ReplayService` (new `jobGate` constructor param). Constructor has no model dependency.
- `phone/.../api/SweepRoutes.kt` (NOT registered; Task 33): POST/GET/GET one (sortable `sort=index|captures|opens|pct|hours|unobserved`, `dir`)/cancel.

## Tests and mutations (each mutation restored; all RED on the intended test)
Names: sweepGapUpTo300s (planner + service), sweepSensingKeyRefused (planner + service + route), labelDeleteInvalidatesSweep, constructorHasNoModelDependency, plus sources/labelRev recorded, one-job gate shared with replay, cancel paths, abort after restart, fence refusal on moved labels and tombstoned source.
Mutations: add a `RoleRunner` param (constructorHasNoModelDependency RED), own Mutex instead of shared (gate test RED), labelRev 0, sourcesJson `[]`, dev-mode cancel removed, fence-refusal close removed, sensing check removed, MAX_CONFIGS 201, validator removed, seed ignored, candidate table dropped, dedupe removed, objectives (keeps->opens, unobserved denominator); simulator: own cadence off, table profile off, row cap ignored, `forRest` off (first survived: added workRestLiftsTheCandidateGap, then RED).

## Rulings (spec text for round 1 Task 17 is not on disk)
1. Scope: holdout by day, moment recall, intent hit rate, Pareto front and `splitDaysJson` content are LATER LA6 and NOT built (`splitDaysJson` stays `[]`); the commit message says what was built. Provenance (`sourcesJson`, `labelRev`) and invalidation are built.
2. The sweep base is `PlanResolver.defaults()` plus an optional partial `base`; the sweep row's `configHash` is the base config hash, each point carries its own.
3. A replay candidate with a policy table keeps its OWN cadence: a decided open sets `lastBriefAt = t + 700 ms` and `lastPhotoAttemptAt = t`; recorded closes no longer drive them (else a wider gap would never fall due while the 5 s recording keeps resetting the clock). The still back-off ladder and recorded episodes (which block a decision while open) still follow the recording; approximate.
4. Random mode: `samples` 1..200, seed to `java.util.Random`, each param picks uniformly from its value list; duplicate hashes dropped, so a result may hold fewer than `samples` points. Ranges (`min,max,step`) expand with BigDecimal, max 200 values per param.
5. Objectives are totals over traces divided by total minutes (not means of means); `inexactWindows` and the "replay is approximate" and unobserved warnings are carried in the result.
6. A refused spec writes nothing and throws before the gate is taken; the route returns 400 with the fixed text only when it matches a safe character set.

## Concerns
- Each point re-runs the baseline pass inside `CounterfactualReplay.run` (2 passes per point and trace); 200 configs on 4 h traces costs about double what it must. A baseline-reuse flag is a small follow-up.
- Gaps are replayed approximately (ruling 3); expect rank, not absolute value, to be meaningful until a TRIAL measures it.
- `SweepRoutes` and the real thermal/developer-mode sources are unwired until Task 22/33.
