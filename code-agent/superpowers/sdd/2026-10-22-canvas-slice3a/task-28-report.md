# Task 28 report (s3a/c): S26 actions, Settings and Diagnostics rows, full-graph API and publisher test (S26, S12, S72)
Status: DONE (core). Extras (LA10) not built. Depends on Task 27 (commit c2cdc732), which holds the action code (`CapturePlanPublisher.undoLatest/restorePrevious/useSettings/status`, `PlanActivation`); this commit holds their tests, the API, the UI rows and the real-API test.

## Built
- Routes (`PlanRoutes`, registered through `CanvasExtras.build(plans =, m1 =)`): `POST /api/plans/check|publish|restore-previous|use-settings|m1-evidence`, `GET /api/plans/status`. 503 until the composition task binds the publisher.
- Undo latest = the existing `POST /api/publish/rollback`, now group-aware in `CanvasBackendImpl.rollback` (a newest row with a group, or a `capture_plan` row, goes to `CapturePlanPublisher.undoLatest`: ASYNC first, then the phone RT and glasses as one new desired state; a plain publish stays slice 2's). `CanvasBackendImpl.publisher` is public so ONE `CanvasPublisher` serves the process. `GET /api/publish` gains `plan` and `groupId`/`configHash` on stack rows.
- Settings > Capture: row "Capture plan: <name> v<N> (<mode>) · Use settings" (tap = Use settings) and "Frozen in canvas plan vN" under the Capture mode, Every, Quick look and Work and rest rows (`CapturePlanControl`, default None). Diagnostics: plan card line + one row per component ("Glasses: EFFECTIVE · gen 4 · trial", "Phone RT: applied", "ASYNC: shadow").

## Tests (real counts, all green)
New: orchestration 17 (`CapturePlanActionsTest`), phone 13 (`CanvasPlanPublishApiTest` 5, `CaptureSettingsTest` +5, `DiagnosticsTest` +3). Suites: services:orchestration 1137/0; phone 1474 with 1 failure, `PromptEditorTest.savingCreatesVersionTwoThenOverwritesWhenVersioningIsOff`, which passes alone (17/17; the run logged "CodeCache is full", so a load flake, in code this task never touched).
`fullThreeDomainGraphPublishesThroughRealApi`: the real `CanvasBackendImpl`, its real `CanvasPublisher` and the real tables over `CanvasApi`; the glasses RT + phone RT + ASYNC graph publishes the capture half as a `capture_plan` row (group, `configHash`, plan JSON), activates the phone RT and publishes the ASYNC projection as an `interpreter` row of the same group only after EFFECTIVE (hash = slice 2 hash of the projection), refuses a stale undo, and undoes the group as one unit (ASYNC then plan, glasses back to SETTINGS).
Also: `useSettingsDisablesTheCaptureOverridesAndLeavesAsyncAlone`, `undoLatestUndoesTheWholeGroupAsyncFirstThenTheGlasses`, `undoRevealsThePreviousActivePlanInItsOwnMode`, `restorePreviousSendsTheEarlierPlanInItsModeWithTheTrialReBounded`, `aGlassesRevertDeactivatesTheAsyncHalfAndThePhoneRt`, `aNewPublishCompensatesAnUnknownRemoteGroupFirst`.
Mutation REDs (each restored): skip activation on EFFECTIVE (7 fail); skip the ASYNC step of undo; Use settings closing every row (ASYNC); restore sending SHADOW (trial not re-bounded); no deactivate on revert; no compensation; backend not routing undo to the group; interpreter hash from the full graph (3 fail); group id not stamped on the interpreter row; M1 route without Developer mode; frozen label removed; Use settings tap removed.

## Rulings
1. Undo reveals the next active capture plan in its recorded mode; a TRIAL with no trial left (or no M1) becomes SHADOW. Restore previous re-publishes the newest earlier non-reverted plan (group state not REVERTED or FAILED) in its own mode with the 60-minute TRIAL again, as a counted trial; refused with the gate text when none is left. Every reinstatement is a clone row in a new group and the original is closed ("reinstated", "restore_previous").
2. Use settings closes every active `capture_plan` row ("use_settings") and sends SETTINGS; it never touches ASYNC rows. A glasses revert closes the group's rows ("plan_reverted") and the phone RT follows the plan still active (or settings).
3. "Frozen in canvas plan vN" is shown on the rows the plan resolves: Capture mode, Every, Quick look, Work and rest. N is the pipeline version.
4. The mode shown in the Settings row is lower case ("shadow", "trial", "live").
5. Diagnostics shows three component rows (glasses, phone RT, ASYNC), not raw gen rows for a phone RT that has no state of its own: phone RT reads "applied" once the group is EFFECTIVE.
6. S72: `PlanShape.asyncProjection` is applied in `CanvasPublisher.publishInterpreter` and `LiveExtract`; `CanvasRunner` still runs the whole version (a full graph's runs are not yet recorded under the projection hash; the test records the run row directly).

## Seams touched (all still unbound)
`PhoneRtApplierSeam` (Task 13): `PlanActivation.phoneRt`; no-op in HubGraph. `GlassesState` supplier (gate 5; to bind to `Hub.planPeer()`). `CapturePlanControl` / `KeyframeDiagnostics.capturePlan()` / `SettingsDeps.capturePlan`: None defaults, to bind in Task 33 together with `CanvasExtras.build(plans, m1)`, `CanvasBackendImpl.create(plans)`, the `Hub.onPlanAck` feed into `PlanPublishGroups.onAck`, `tick()` and `recover()`. `TraceIntakeSeam`, `LiveEvidence` (LA9): untouched.

## Concerns
- `CanvasRunner` records runs under the full-graph hash, so a three-domain graph's "tested" gate for ASYNC needs a run of the ASYNC-only hash until the runner takes the projection (S72 says it should); Task 33 or a follow-up.
- On a REVERTED ack `PlanSender` still keeps the reverted gen as desired state (re-sent next session). See Task 27 report.
- A TRIAL's ASYNC half is a SHADOW interpreter that is not bounded in time (the bound is the glasses' clock); it is closed when the glasses report REVERTED.
