# WP7 review (S6 on every publish path, scope pins): opus substitute review 1

Worktree `/Users/debasishkanhar/Desktop/rokid/pca-3b-wp7`. WP7 commits 5227815b and 08c9f240. The requested base, 65b530ef, also pulls in merged WP4, WP11, WP14 and LGC3, so the WP7-only diff was read as `17d636b8..08c9f240` (12 files). The real files on disk were read, not only the diff.

## What I verified (no finding)

**Publish and activation path enumeration.** I ran a grep over `:phone` and `:services:orchestration` main sources for `publish(`, `publishInterpreter(`, `activate` and `PlanMode`.
- **HTTP `POST /api/pipelines/:id/versions/:n/publish`** (`CanvasApi.kt:280-287`):
  - `prompts` goes through `CanvasBackendImpl.publish` to `CanvasPublisher.publish` and then `publishLocked`. The guard is at `CanvasPublisher.kt:132`, before `LiveShape.matches`.
  - `shadow` and `live` go through `CanvasBackendImpl.publishInterpreter` to `CanvasPublisher.publishInterpreter`. The guard is at `CanvasPublisher.kt:93`, on the FULL decoded graph, before `asyncProjection`.
- **HTTP `POST /api/plans/publish` and `/api/plans/check`** (`PlanRoutes.kt:154`, `:140`) go to `CapturePlanPublisher.publish` / `check` and then `PlanEvidenceGates.evaluate`. Gate 2 (`PlanEvidenceGates.kt:77`) runs on the full graph (`prepare` decodes `v.json` without projection), and `G2` is now an alias of `PlanShape.S6_REFUSAL`. The text is byte-identical to the old G2, so no UI text changed.
- **Activation after the glasses ack.** `PlanPublishGroups.onAck` (EFFECTIVE) and `recover()` (EFFECTIVE after a restart) both call `activateOf`, then `PlanActivation.activate` (`CapturePlanPublisher.kt:246-255`), then `publishInterpreter`, which is guarded.
- **`restore-previous`, `undoLatest` and `reinstate`** only re-send an existing `capture_plan` row's `planJson`. That JSON holds RT nodes only. Its ASYNC half reaches the phone only through `PlanActivation`, which is guarded.
  - No such row can exist for a tier-3 graph. Before WP7, `py.`/`tool.` were already refused by gate 2. Before WP7, `py.fn` was also an unknown type, which fails `PlanCompiler` through `PipelineValidator`, which fails gate 1.
- **Live run.** `LiveExtract.interpret` loads the version and takes its ASYNC projection (`LiveExtract.kt:181-186`), then `PipelineInterpreter.run`. That function now returns `Failed("tier3_node")` with one `_batch` ERROR row per photo (`PipelineInterpreter.kt:69`). Because `py.fn` is ASYNC and `tool.*` is unresolved (so not RT), the projection keeps those nodes, and the guard sees them. This also neutralises any legacy active row.
- **`duplicate`, `saveDraft` and `freeze`** create drafts or versions only, never a publish row.
- **Replay and sweep requests** carry no graph (`ReplayRequest`, `SweepRequest` hold PlanConfig and trace ids).

**Registry false positives.** `ExecutorKind.PYTHON`, `DRAFT` and `Domain.REPLAY_ONLY` are used by no built-in except `py.fn`. `memory.write` is `ExecutorKind.TOOL`, but it is not `tool.`-prefixed and TOOL is not matched. The signal domains are RT only. The prefix rules are unchanged. No existing node type is newly refused.

**Executors.** `Executors.all()` is unchanged (`Executors.kt:38-39`), and `Executors.kt` was not edited. `CanvasRunner` also uses `Executors.all()`, so `py.fn` has no executor anywhere.

**Interpreter finding confirmed.** `InterpretableShape.preSaveNodes` (`InterpretableShape.kt:93-96`) keeps only `PRE` types (`input.photos`, `ai.prompt`, `route.switch`) plus `ai.vision_extract`. Any other type, unknown ones included, was silently skipped. The part-B row at line 26 ("FALSE (fails the item)") is wrong, and the implementer is right. Existing graphs are unaffected: no stored, publishable graph can hold a `py.`/`tool.`/PYTHON/DRAFT/REPLAY_ONLY node.

**py.fn entry and the validator.** I checked `NodeTypes.kt:119-126` against the spec: ASYNC, CODE, PYTHON, JSON in and out, `liveable=false`, `palette=false`, `modelCalls=0`, `source` 16000, `outSchema` 4000, `sinceSchema=2`. `resolve("py.fn", 1)` is null and `PipelineJson` accepts schema 2.

A frozen `py.fn` graph before WP8 is not dangerous:
- Publish and run are refused as shown above.
- `POST /api/runs` reaches `CanvasRunner.execute`, where `py.fn` writes `no_executor` rows and nothing executes. The estimate counts 0 calls.
- No export or share route exists.
- The DB is excluded from backup.

**Edited existing tests.** `InterpretableShapeTest.kt:143-144` (the registry enumeration now includes `PY_FN`, as its own comment predicted) and `CanvasBackendImplTest.kt:353` (`py.fn` is non-palette and appended last) are both justified and minimal.

**Mutation sensitivity of PublishRefusesPyFnTest.**
- Removing the `publishInterpreter` guard: `assertEquals(Blocked(fixed), ...)` fails (other gates would produce different texts).
- Removing gate 2: `capturePlanPublishRefusesTier3` fails.
- Removing the `publishLocked` guard: the shape text appears instead, so `publishLegacyRefusesTier3` fails.
- Removing the interpreter guard: `interpreterRefusesToStartWithTier3Node` fails.
- Inserting versions straight into the table correctly models the stored-row threat.
- `aGraphWithoutTier3NodesIsNotRefusedByTheGuard` is a real negative control.

## Findings

1. **Minor: the Developer-mode `debug-shadow` route has no S6 guard, and WP7 newly opens it to `py.fn` graphs.** `phone/src/main/java/com/rokid/context/phone/canvas/api/PlanRoutes.kt:97-112`
   - Before WP7, `py.fn` was an unknown type. `PlanCompiler` (which runs `PipelineValidator` on the full graph, `PlanCompiler.kt:100`) failed, so the route answered 422.
   - Now `py.fn` validates, and `ScopePinsTest.captureConfigHashUnchangedByAsyncNodes` pins that the compile succeeds. So `POST /api/plans/debug-shadow {json: <graph with py.fn>}` sends that graph's capture half to the glasses as SHADOW. `/api/plans/compile` also reports `ok:true`.
   - Not blocking: the sent `planJson` holds RT nodes only, no publish row or group is written (`PlanSender.sendShadow` uses `publishId = "debug-<gen>"`), and nothing reaches `PlanActivation` or the interpreter. The py.fn code itself is never carried.
   - It still contradicts the KDoc claim "the ONE guard every publish path calls" (`PlanShape.kt:38`).
   - Edit, in `debugShadow` after `val r = compile(pipeline(b))`: hold the decoded pipeline in a local `p`, then add `if (PlanShape.tier3Nodes(p).isNotEmpty()) return HttpResponse.error(409, "publish_blocked", "Blocked.", listOf(PlanShape.S6_REFUSAL))`. Alternatively, record it as an accepted gap in the WP7 notes and narrow the `PlanShape.kt:38` KDoc to "every publish path".

2. **Minor: the interpreter pin test's KDoc states the opposite of the real behaviour.** `services/orchestration/src/test/java/com/rokid/context/canvas/PipelineInterpreterTest.kt:508`
   - `aNodeWithNoExecutorFailsTheBatchWithNoExecutor` says "a node type the interpreter's map does not hold fails the batch ... it is never skipped".
   - The test only covers `ai.vision_extract`, which is in the preSave set, with an empty executor map. A node type outside `PRE` and `VISION_EXTRACT` is still skipped silently by `preSaveNodes`, and the new tier-3 guard is what closes that for tier 3.
   - Failure scenario: a later reader trusts the pin and removes the tier-3 guard as "redundant". Unknown-type nodes would then be skipped again with no test failing except the tier-3 one.
   - Edit: reword the KDoc to "an in-path node whose executor is missing fails with `no_executor`; types outside `preSaveNodes` are skipped, so tier 3 is closed by the start guard (`interpreterRefusesToStartWithTier3Node`)". Optionally add a pin that a non-PRE node (for example a disconnected `memory.write`) is skipped.

3. **Minor: `ScopePinsTest.debugRouteStaysShadowOnly` is tautological.** `services/orchestration/src/test/java/com/rokid/context/canvas/plan/ScopePinsTest.kt:49-53`
   - `PlanMode.LIVE` occurs nowhere in `PlanRoutes.kt`, before or after `debug-shadow`, so the second assertion can never fail.
   - Removing the `shadow_only` refusal at `PlanRoutes.kt:101-103` would pass the test. `sendShadow` hardcodes SHADOW anyway.
   - Edit: assert on the refusal text instead, for example `assertTrue(s.contains("\"shadow_only\""))`. Better, rely on the existing route test that posts `mode: "LIVE"` to `/api/plans/debug-shadow` and expects 400 `shadow_only`, and reference it here.

4. **Minor: the reflection half of `noPythonInSweepsOrReplays` misses function-typed dependencies.** `ScopePinsTest.kt:102-110`
   - Constructor and field types are matched by class name. A helper port injected as a lambda, for example `runPy: suspend (String) -> String`, has the type `kotlin.jvm.functions.Function2` and passes.
   - Only the case-insensitive `helper|python` source grep catches it, and only through the parameter's name. A neutrally named lambda (`exec: suspend (String) -> String`) passes both checks.
   - Edit: also assert that `SweepService` and `ReplayService` constructors take no `kotlin.jvm.functions.*` or `kotlin.coroutines.*` parameter beyond an explicit allow-list of the existing ones, so a new function-typed dependency fails the pin.

5. **Minor: the PublishRefusesPyFnTest KDoc overstates the CapturePlanPublisher coverage.** `services/orchestration/src/test/java/com/rokid/context/canvas/PublishRefusesPyFnTest.kt:31`
   - "with ONE fixed text" holds for `CanvasPublisher`. For the plan paths, the S6 text is one reason among G3, G5 and so on (`assertTrue(S6_REFUSAL in o.reasons)`), which is fine, but the KDoc implies exactly one.
   - Edit: say "the fixed S6 text is among the reasons" for the plan paths.

## Counts

Critical 0, Important 0, Minor 5.

VERDICT: READY
