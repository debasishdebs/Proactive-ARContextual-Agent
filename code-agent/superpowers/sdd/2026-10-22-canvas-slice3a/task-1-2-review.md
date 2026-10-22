# Review: slice 3a Tasks 1 and 2 (s3a/c, 138ebb27..5beac05e)

Reviewer: Opus, read-only (no Gradle, no adb, no stash). Diff package read in two passes (code 1-875, goldens sampled by script plus a recompute).

## Task 1 (2827261a, frozen decision goldens)

### Spec Compliance
- ❌ `expectedResv` ignores S83's "while eligible" and counts a streaming episode as no attempt:
  - all 7 points in `unknown_walk` and the one in `capture_now_mix` fall inside a continuous WALKING `stream` episode (the camera is open and analysing frames). Examples: 287260 and 347260 inside 227260-373470; 1000480 and 1060480 inside 940480-1073670; capture_now 305600 inside 245600-350150.
  - `bind_backoff` 465180 falls inside the camera-busy span 420000-490000 (`GoldenScripts.kt:859-860`), where timer attempts are already being made and failing (425180, 455380, 485530 `open timer failed`). Camera ownership and busy back-off are non-removable invariants, so a `resv` open there is impossible.
  - The plan tells Task 12 to "add a `resv` open exactly there and nowhere else", so this contract is wrong as written. I recomputed it with two changes: an open frame-delivering episode keeps the bound satisfied, and a failed attempt during busy counts as an attempt (or busy makes the session ineligible). With those changes the list is empty for all 8 scripts. That matches S83's own claim ("the reserved attempt fires only on anomalies; the Task 1 goldens prove this for the nominal scripts"). As recorded, they show the opposite for the nominal `unknown_walk`.
- ✅ 8 named scripts, the recorder, the test, one golden per script, settings mode via `ContextPolicies.DEFAULT` -> `withContext` + `contextCap` (`DecisionGoldenRecorder.kt:546-550`). It is test-only: the commit touches only `glasses/src/test`.
- ✅ The `reservedBoundMs` copy (`DecisionGoldenRecorder.kt:538-540`) is token-equal to the pinned Task 4 function: REST, VEHICLE_PASSENGER, VEHICLE_DRIVING, FLIGHT, or `resting && state != WALKING` -> 300 s, else 60 s.
- ⚠️ Cannot verify that 138ebb27 equals unmodified `s2/int` capture code. The controller should check `git diff s2/int 138ebb27 -- android-companion/glasses/src/main` is empty.

### Strengths
- It drives the real `SamplingSession` through the shared `Rig`. One coroutine feeds the sensors and polls, so the output is deterministic, and `recordingIsDeterministic` proves it for two scripts.
- Cause attribution is sound: `wakeAccum` is bumped right before `camera.open` (`SamplingSession.kt:398`), so the counts are 1:1 with the fake camera's `open@` events.
- `goldensCoverTheDecisionKinds` guards against vacuous goldens. Each golden has 214-854 lines, and `skip novel` (27) and `skip cap` (5) are also present.
- The goldens are unchanged by Task 2: `git diff --stat 2827261a 5beac05e` on the golden paths is empty.

### Issues
#### Important
1. `DecisionGoldenRecorder.kt:650-663`: the `expectedResv` rule is mis-specified, as in Spec Compliance above.
   - Fix: while an episode is open and delivering frames, the bound stays satisfied.
   - Fix: treat failed attempts during busy or back-off as attempts, or skip ineligible spans (busy, paused, not worn, blocked).
   - Then regenerate only the footer. The expected result is empty for all 8.
   - The controller must also align the S70 wording ("last episode start that delivered a first frame") with this, or Task 12 inherits the contradiction.

#### Minor
2. `DecisionGoldenRecorder.kt:651-659`: the bound is taken from the state at the gap's start (`at(last)`). Task 12's rule uses the applied state at `now`, so a FLIGHT->UNKNOWN or rest-end mid-gap would be missed. No current golden changes under the corrected rule (I checked), but Task 4 and Task 12 must not copy it.
3. `DecisionGoldenRecorder.kt:572,636,639`: `motionClassifierInputs` counts the test's own feed calls. It equals `2 * durationMs / 20` in every file and cannot change.
   - As S84(4) evidence it cannot fail. The sensor rate lives in `CaptureService`/`SensorRatePolicy`, outside the Rig.
   - Fix: relabel it, or have Task 12 record the requested sensor profile.
4. `DecisionGoldenRecorder.kt:598`: ok/failed is read from `r.camera.isOpen` at poll time, not at the event.
   - An open+close, or a fail+open, inside one 20 ms poll would be mislabelled.
   - Two opens in one poll take their causes in map order, not time order.
   - It is deterministic, so it is harmless for freezing, but it feeds `okOpens`.
5. `DecisionGoldenTest.kt:707-714`: `GOLDEN_RECORD=1` makes `goldensReproduce` pass after writing the files. If the variable is set by accident, the test goes green silently. Fix: `fail("recorded; rerun without GOLDEN_RECORD")` after writing.
6. `expectedResv` points are exact ms. Task 12 fires on the 250 ms tick, so "exactly there" needs a stated tolerance of one tick.
7. There is no VEHICLE_* script, the one state with a 300 s bound whose row has no gaps. Blur and dark skips are not observable, as the report admits.
8. The commit trailer says `Claude Sonnet 5.5`. Global Constraints mandate `Co-Authored-By: Claude Opus 5.5`. This applies to all three commits.

### Verdict Task 1: spec compliant **no**, quality approved **no** (one Important).

## Task 2 (0bedf427 move-only, 5beac05e GlanceAdmission)

### Spec Compliance
- ✅ Move-only, verified with `git diff -M --name-status/--numstat 0bedf427~1 0bedf427`:
  - 16 renames: 8 main and 8 test files are R100 with 0/0 lines.
  - KeyframeGate is R089 (-24 lines). I diffed those 24 lines against the new `PrefsGateStore.kt` minus its `package` line, ignoring blank lines: identical.
  - The 48 lines cut from BatteryBudget.kt, diffed the same way against the new `CaptureProfile.kt`: identical, including `BudgetTier`, the `CameraWake` import, all comments, `DEFAULT` and the companion `of`.
  - Packages are unchanged.
- ✅ No visibility change:
  - the only `internal` members are `MotionClassifier.dwellListener/onsetListener` (`MotionClassifier.kt:37-38`) and `KeyframeGate.retainedCount` (`KeyframeGate.kt:66`);
  - they are used only from the moved `:core:capture` tests (`MotionClassifierTest`, `KeyframeGateTest`), so the associated test compilation sees them;
  - no glasses code references them;
  - there is no `@VisibleForTesting`, and object/companion structure is unchanged;
  - the split package across modules is fine on Android (no JPMS).
- ✅ Module wiring:
  - `core/capture/build.gradle.kts` is kotlin-jvm 17 with `api(:core:model)`, `kotlin("test-junit")` and junit 4.13.2 (the glasses' version);
  - there are no Android imports under `core/capture/src` (grep);
  - glasses uses `implementation`, ai and orchestration use `api` (S71), and `settings.gradle.kts` has the include;
  - `:phone` gets it only transitively through ai/orchestration and has no direct use;
  - glasses `isMinifyEnabled = false` and `proguard-rules.pro` is a no-op, so the R8 claim holds;
  - no stale imports are possible because packages are unchanged.
- ✅ FeatureLogger moved beyond the plan list: it is pure (an injected sink, `java.util.Locale` only), it is needed because `MotionClassifierTest` must stay unchanged, and it is documented in the commit body. Acceptable.
- ✅ GlanceAdmission (`GlanceAdmission.kt:250-254`) matches the 138ebb27 `onDwell` (read via `git show`) token for token, with `hardNow(cc)` -> `hardCap`. The branch order is the same: novelty, then cap or hard ceiling, then open.
  - `hardNow(cc) = resting || cc != null` (`SamplingSession.kt:164`) is a pure field read under the same lock, so evaluating it eagerly is safe.
  - `glance` is a ctor `val`.
- ⚠️ Not re-run (no Gradle by instruction): `:core:capture:test` 122, `:glasses:testDebugUnitTest` 543, `assembleDebug`, and the services compile. The controller should rely on the report or run them once at merge.
- ⚠️ The round 1 Task 1 text ("round 1 names and the 64-case truth table") is not available anywhere I could find, so the truth-table dimensions cannot be checked against it.

### Strengths
- A genuinely clean move: byte-identical splits, no drive-by edits, and goldens green after both commits.
- The extract is minimal, and its doc comment states its provenance.

### Issues
#### Minor
1. `GlanceAdmissionTest.kt:314-315`: the 64-case table never has `turned < noveltyMinRad` (its smallest value is `noveltyMinRad` itself) and never has `k < cap`.
   - As a result, NOVEL_SKIP never appears in the table, and the `since` dimension is dead (32 cases duplicate the other 32).
   - The required `<` -> `<=` mutation is still caught, because `turned == min` is in the table.
   - The novelty mutations (`!walked`, the window edge) are caught only by the 4 hand cases.
   - Fix: use turned {0.1, min, 0.5, big} and k {cap-1, cap, 2cap} (or k {cap-1, cap} and turned without 1.0).
2. The 5beac05e body does not record the mutation RED, which the preamble requires in the commit body (it is in the report only).
3. The trailer is `Sonnet`, not the mandated `Opus` line (same as Task 1 #8).

### Verdict Task 2: spec compliant **yes**, quality approved **yes** (Minor only).
