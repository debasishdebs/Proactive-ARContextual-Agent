# Task 27 report (s3a/c): evidence gates (S11, S60, S75)
Status: DONE (core). LIVE gate (LA9) not built, by instruction; M1-dependent parts refuse until `m1Passed` is recorded.

## Built
- `PlanEvidenceGates` (orchestration/canvas/plan): the five `capture_plan` gates, every piece of evidence keyed by `configHash`. `fillMeta` fills `meta.expectOpensP95PerMin` and recomputes the hash over `config` only.
- `M1Evidence` / `DaoM1EvidenceStore`: `canvas_meta` key `m1Passed` (kept by Delete all). Recorded only by `POST /api/plans/m1-evidence` (Developer mode); an unreadable record is NOT a pass. SHADOW never reads it; TRIAL and LIVE refuse without it (fixed text, nothing sent, no row written).
- `CapturePlanPublisher.publish/check` (gates in front of `PlanPublishGroups`), `PlanActivation` (ASYNC interpreter and phone RT activate only after EFFECTIVE), `MetaPlanSource` (plan JSON in `canvas_meta`).
- `CounterfactualReplay`/`ReplayReport.states`: minutes and p95 opens per full minute per context state (the evidence for gate 3 "2 states" and the plan's expected p95).
- `PlanShape` (`:services:ai`): `asyncProjection` (S72: the ASYNC half keeps slice 2's hash), `tier3Nodes`; `CanvasPublisher.publishInterpreter(..., groupId)` publishes the projection; `LiveExtract.load` reads it.
- DAO queries only (no schema change): `validReplays`, `putMeta`, `publishRow`, `capturePlanPublishes`, `publishesOfGroup`, `trialCount`, `setPlanPublishState`.
- `PlanPublishGroups` gained an optional `deactivate` hook (REVERTED ack switches the phone halves off).

## Tests (real counts, all green)
New: core:capture 4 (3 per-state + `forceSettleWithATableReplaysAtSettleWithTheRowMinima`), services:ai 4 (`PlanShapeTest`), orchestration 25 (`PlanEvidenceGatesTest` 16, `CapturePlanPublisherTest` 9). Suites after: core:capture 349, services:ai 539, services:memory 422, services:orchestration 1137 (includes Task 28's 17), 0 failures.
Named: `configHashStableFromReplayToLive`, `staleSourcesFailGate3`, `metaFillDoesNotChangeHash`, `trialSendsOneHourAndIsCountedPerConfigHash` (trialMaxMs 3,600,000, 3 per hash), `shadowNeedsNoM1`, `trialAndLiveAreRefusedUntilM1IsRecorded`.
Not built: `liveRefusedAt043` / `liveAllowedAt042` (the LIVE gate is LA9). Replaced by `liveIsRefusedInCoreEvenWithAVerifiedCalibrationAtTheTarget` (0.43 and 0.42 both refused) and the `LiveEvidence` seam test.
Mutation REDs (each restored): hash meta together with config (6 tests fail incl. `configHashStableFromReplayToLive`, `metaFillDoesNotChangeHash`); labelRev check off (`staleSourcesFailGate3`); revision check off (same); M1 check off (2); trialMaxMs null (`trialSendsOneHour...`); trial count off (2); p95 as max (`p95IsTheNearestRankOverFullMinutes`); forceSettle overriding the row (`forceSettle...Minima`).

## Rulings (round 1 text not on disk; inferred from the slice 3 spec S11, S60, S75, section 7)
1. Gate 3 accepts a replay of EITHER class whose `configHash` is the plan's (a never-recorded plan can only be replayed as a candidate; `validExactReplays` would never match). It also re-checks at gate time: `labelRev` current, every source live, same revision, COMPLETE.
2. A state counts as covered with >= 1 minute; minutes sum over the replay's traces.
3. TRIAL estimate: minutes-weighted candidate %/min <= baseline x 1.10, the baseline being the recorded-config figure of the SAME replay. At most 3 TRIAL rows per `configHash` (rolled-back ones count).
4. `expectOpensP95PerMin` per state = the highest p95 of any trace, 2 decimals (a p95 cannot be merged exactly). A minute belongs to the state in effect at its start.
5. Gates 1-3 and 5 apply to every mode; gate 4 is SHADOW ok / TRIAL / LIVE (refused in core).
6. Tier 3 = node type under `py.` or `tool.`, or domain REPLAY_ONLY (the catalogue has none yet).
7. ASYNC half publishes as SHADOW for a SHADOW or TRIAL plan (an interpreter has no bound of its own), LIVE only for a LIVE plan. An untested AI node leaves ASYNC off; Diagnostics shows "ASYNC: none".
8. M1 note is free text <= 80 chars, never a secret.

## Seams touched (all still no-ops or unbound)
`PhoneRtApplierSeam` (Task 13): `PlanActivation` takes a `(String?) -> Unit`, nothing binds it in HubGraph yet. `GlassesState` supplier (gate 5): to bind to `Hub.planPeer()` plus link state. `LiveEvidence` (LA9). `TraceIntakeSeam`: untouched. Not wired (Task 33): onAck feed from `Hub.onPlanAck` into `PlanPublishGroups.onAck`, a periodic `tick()`, `recover()` at start-up, building `CapturePlanPublisher` in the graph.

## Concerns
- On a REVERTED ack the glasses return to the previous effective plan themselves, but `PlanSender` still holds the reverted gen as its desired state and re-sends it on the next session. S25 says never re-send a reverted gen: Task 33 should move the desired state.
- The undo/restore/use-settings actions live in `CapturePlanPublisher` (this commit); their tests are in the Task 28 commit.
