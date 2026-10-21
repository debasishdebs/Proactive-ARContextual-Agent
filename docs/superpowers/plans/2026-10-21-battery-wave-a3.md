# Battery wave A3: implementation plan (glance model and photo-rate calibration)

**Spec:** `docs/superpowers/specs/2026-10-21-battery-wave-a3-glance-model.md`. Read §2 (code facts) and the section your task names before starting.

**Where to work:** `/Users/debasishkanhar/Desktop/rokid/pca-bat/int` (branch `int/2026-10-21`). A wave-2 implementer commits `:services:orchestration` work on the same branch: pull/rebase before each task, never edit its files. Tasks 1–6 run **sequentially** (each starts from the previous commit). Task 7 touches only `tools/` and may run in parallel with Tasks 1–6. TDD: write the test, watch it fail (RED), implement, watch it pass (GREEN), commit.

## Global Constraints
- **Modules:** `:core:model` (Kotlin JVM), `:services:glasses-link` (Android library), `:glasses`, `:phone`. Package roots:
  - `com.rokid.context.core.model`
  - `com.rokid.context.link`
  - `com.rokid.context.glasses`
  - `com.rokid.context.phone`
- Glasses paths below are relative to `android-companion/glasses/src/{main,test}/java/com/rokid/context/glasses/`.
- **Scope:** glasses-side code, `core:model` settings fields, and the phone Capture settings screen only. **Do not edit any file under `android-companion/services/orchestration/`** (wave 2 owns it) or Room entities/DAOs.
- **Wire:**
  - `PROTOCOL_VERSION` stays **1**.
  - Only **additive** fields with defaults, and new channels. Old glasses ↔ new phone, and the reverse, must still parse.
  - An unknown enum value decodes to the property default (`Wire` has `coerceInputValues`).
  - `Wire` has `encodeDefaults = true`.
- **Room:** no change.
- **Exact new names and values** (do not rename):
  - `HudSettings.workRestEnabled: Boolean = false`; `HudSettings.motionLabel: MotionLabel = MotionLabel.NONE`; `HudSettings.motionSourcesOff: List<String> = emptyList()`.
  - `enum class MotionLabel { NONE, SITTING, GLANCING, CHORES, EATING, WALKING, NOT_WORN }` (core:model).
  - `data class GlanceParams(val onsetRadS: Float = 0.6f, val releaseRadS: Float = 0.3f, val bridgeMs: Long = 250, val minBoutMs: Long = 300, val minBoutRad: Float = 0.26f, val settleMs: Long = 1_500, val maxDtMs: Long = 100, val deadbandRadS: Float = 0.05f, val noveltyWindowMs: Long = 20_000, val noveltyMinRad: Float = 0.30f, val bigTurnRad: Float = 0.8f, val capPerMin: Int = 4, val restCapPerMin: Int = 2)` in `capture/motion/GlanceParams.kt`, with `companion object { val LEGACY = GlanceParams(releaseRadS = 0.6f, minBoutMs = 0, minBoutRad = 0f, settleMs = 1_000, noveltyMinRad = 0f, bigTurnRad = 0f, capPerMin = 1_000, restCapPerMin = 1_000) }`.
  - `KeyframeGate.CAPPED = "CAPPED"`, `KeyframeGate.BIG_CHANGE = 22`; `offer(c, motion, dwellArmed, force = false, capPerMin: Int = Int.MAX_VALUE)`.
  - `MotionClassifier(clock, params: GlanceParams = GlanceParams())`; `rotationSinceMarkRad(): Float`, `walkedSinceMark(): Boolean`, `markPose()`, `takeGlanceCounts(): GlanceCounts` (`data class GlanceCounts(val glances: Int, val micro: Int)`).
  - `CaptureProfile.forRest(): CaptureProfile`; `WorkRest(clock, workMin, restMin, enabled: () -> Boolean = { true })`.
  - CSV file `files/motion_features.csv`, rotated to `motion_features.csv.1` at `4 * 1024 * 1024` bytes; header exactly as spec §8.
  - Heartbeat keys appended after `stillGapMs`, in this order: `glances1m micro1m novelSkip1m capped1m same1m scrOn scrOurs nudgeOff`. Prefix stays `heartbeat v=2`.
  - Source ids: `glasses_imu`, `phone_motion`, `watch_livefit`.
- **Never touch:**
  - the camera LED (`LedSourceRulesTest` must stay green);
  - Rokid system services (no wake/sleep calls, no assistant interaction).

  Logs and CSVs carry counts, numbers and enum names only: never captions, user text, device names, tokens or `GlassInfo.toString()`.
- **Gradle:**
  - Run from `/Users/debasishkanhar/Desktop/rokid/pca-bat/int/android-companion` through the throttled wrapper `/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/.superpowers/sdd/gw.sh <args>`. It calls `./gradlew --no-daemon`.
  - **One Gradle run at a time** in this worktree (wave 2 shares it: if its build is running, wait). Never `gradlew --stop`.
  - Unit-test commands:
    - `:core:model:test --tests '<Class>'`
    - `:glasses:testDebugUnitTest --tests '<Class>'`
    - `:phone:testDebugUnitTest --tests '<Class>'`
  - Run the whole module's tests before committing.
- **No device.** No `adb` in Tasks 1–7. No push.
- **Secrets.** Never print `.env` values, keys or tokens.
- **Commits:** local only, one per task, message as given, ending with the trailer `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Stage only your task's files (`git add <paths>`), never `git add -A` (wave 2 has work in flight).
- **Backward compatibility:**
  - New constructor or function parameters get defaults, so existing call sites compile unchanged.
  - Fix an existing test that relies on an old *default* by passing the old value explicitly (e.g. `GlanceParams.LEGACY`, `workRestEnabled = true`). Never change behaviour to keep it green. Where this plan changes behaviour on purpose, it names the test to rewrite.

### Task 1: Settings fields and the Capture screen (core:model + phone)
**Spec:** §6 (switch), §8 (labels, sources).

**Files:**
- `android-companion/core/model/src/main/kotlin/com/rokid/context/core/model/Protocol.kt`
- `android-companion/core/model/src/test/kotlin/com/rokid/context/core/model/ProtocolTest.kt`
- `android-companion/phone/src/main/java/com/rokid/context/phone/ui/settings/capture/CaptureSettings.kt`
- `android-companion/phone/src/test/java/com/rokid/context/phone/ui/settings/capture/CaptureSettingsTest.kt`

**Implement:**
- `MotionLabel` enum (Global Constraints). `HudSettings` gains the three trailing fields with KDoc ("wave A3: WorkRest default Off", "training label, enum only", "source ids the user turned off; glasses_imu is ignored here").
- `HudSettings.normalized()` removes `"glasses_imu"` from `motionSourcesOff`.
- `CaptureSettingsUiState`: `workRestEnabled = false`, `motionLabel = MotionLabel.NONE`, `motionSourcesOff = emptyList()`, filled from `HudSettings` in the existing factory.
- ViewModel setters: `setWorkRestEnabled(Boolean)`, `setMotionLabel(MotionLabel)`, `setMotionSource(id: String, on: Boolean)` (adds/removes the id; `glasses_imu` is a no-op) — each through `hud.update { … .normalized() }`.
- Composable (new trailing lambdas with defaults):
  - "Work and rest": a switch tag `work_rest` ("Work and rest", subtitle "Off: capture never rests. On: rest captures glances only, at most 2 photos a minute."); the Work and Rest rows and `rest_note` show only when on.
  - "Motion sources" section, tag `motion_sources`: rows `source_glasses_imu` (switch on, disabled, "Always on"), `source_phone_motion` and `source_watch_livefit` (switch off, disabled, "Coming later").
  - "Motion labels (training)": `ChoiceRow` tag `motion_label` over `MotionLabel.entries` with labels None / Sitting / Glancing / Chores / Eating / Walking / Not worn; when not NONE a `NoticeText` tag `motion_label_note`: "Recording motion features on the glasses. Set back to None when done."

**Tests:**
- `ProtocolTest`: old JSON without the fields → `workRestEnabled == false`, `motionLabel == NONE`, `motionSourcesOff` empty; `"motionLabel":"DANCING"` → NONE; round-trip of `motionLabel = EATING` and `motionSourcesOff = listOf("phone_motion")`; `normalized()` drops `glasses_imu`; `PROTOCOL_VERSION == 1`.
- `CaptureSettingsTest`: default state hides tags `work`, `rest`; switching `work_rest` on calls the setter with true and (with state on) shows `work`; `source_glasses_imu` is shown and disabled; picking `motion_label` index 4 → `EATING`; `motion_label_note` shows only when not NONE. Existing Work/Rest tests pass `workRestEnabled = true` in their state.

**Command:** `gw.sh :core:model:test`, then `gw.sh :phone:testDebugUnitTest --tests '*CaptureSettingsTest'`, then `gw.sh :phone:testDebugUnitTest`.

**Commit:** `feat(settings): WorkRest switch (default off), motion labels and motion sources settings (wave A3)`

### Task 2: Glance detector, head pose and per-second features (glasses)
**Spec:** §3, §8 (features).

**Files:**
- new `capture/motion/GlanceParams.kt`, new `capture/motion/GlanceDetector.kt`
- `capture/motion/MotionClassifier.kt`
- tests: new `GlanceDetectorTest.kt`, `MotionClassifierTest.kt`, `session/SamplingTestKit.kt`

**Implement:**
- `GlanceParams` exactly as in the Global Constraints.
- `GlanceDetector(p: GlanceParams)` (pure, not thread-safe; the classifier calls it under its own lock):
  - `fun onGyro(mag: Float, tMs: Long, dtMs: Long, still: Boolean): Boolean` — returns true when a glance fires on this sample; implements the QUIET / BOUT / SETTLING machine of spec §3 (`turned += mag * dtMs.coerceAtMost(p.maxDtMs) / 1000f`). `still` = the classifier state is STILL (a glance is emitted only then; otherwise it stays SETTLING).
  - `fun onTime(tMs: Long, still: Boolean): Boolean` — the settle test only (called from accel callbacks with gyro time).
  - `fun onWalking(tMs: Long)` — SETTLING(since = tMs, armed = true).
  - counters `glances`, `micro` (Ints, read and reset by the classifier).
- `MotionClassifier(clock, params: GlanceParams = GlanceParams())`:
  - Replace `hadMotion` / `checkDwell` with the detector: in `onGyro` after `update(t)`, `if (detector.onGyro(mag, tMs, dt, _state.value == STILL)) emitDwell(t)`; in `onLinearAccel`, when walking call `detector.onWalking(tMs)`, then `if (detector.onTime(gyroNewestMs, _state.value == STILL)) emitDwell(gyroNewestMs)`. `dt = tMs − lastGyroMs` (0 on the first sample; negative → 0).
  - Pose: `rx, ry, rz += x·dt, y·dt, z·dt` (seconds, dt clamped to `maxDtMs`) when `mag ≥ deadbandRadS`; `rotationSinceMarkRad() = sqrt(rx²+ry²+rz²)`; `markPose()` zeroes them and `walked`; `walked = true` whenever the state is WALKING. All `@Synchronized`.
  - Per-second accumulators since the last `features()`: n, Σmag, Σmag², count above `onsetRadS`, Σ(mag·dt) (travelled). `Features` gains trailing defaulted vals `gyroMean`, `gyroRms`, `aboveOnFrac`, `rotRad`, `glances`, `micro` (per-second deltas of the detector counters).
  - `takeGlanceCounts()` returns and resets separate per-heartbeat glance/micro accumulators.
  - MotionState, onset and decelerating logic are **unchanged**. `DWELL_MS` stays (KDoc: "legacy; see GlanceParams.LEGACY").
- `SamplingTestKit.Rig`: `MotionClassifier(clock, GlanceParams.LEGACY)`, and a new Rig param `glance: GlanceParams = GlanceParams.LEGACY` used for it (Task 3 passes it to the session too).

**Tests:**
- `GlanceDetectorTest` (50 Hz, 20 ms step, defaults):
  - `realTurnThenSettleIsOneGlance`: 1.2 rad/s for 400 ms (0.48 rad) from t = 1 000, then 0 → exactly one glance at `lastAbove + 1 500` (± 20 ms); `micro == 0`.
  - `shortJerkIsMicro`: 2 rad/s for 100 ms → no glance in 5 s; `micro == 1`.
  - `slowSmallTurnIsMicro`: 0.7 rad/s for 320 ms (0.22 rad < 0.26) → no glance; `micro == 1`.
  - `jitterBelowOnsetNeverStartsABout`: 0.5 rad/s noise for 10 s → no glance, `micro == 0`.
  - `releaseHysteresisBridgesTheTurn`: 1.0 rad/s 200 ms, 0.4 rad/s 150 ms, 1.0 rad/s 200 ms → one bout (one glance), not two micros.
  - `microDuringSettlePostponesTheGlance`: a real turn, then at +800 ms a 100 ms jerk → glance at jerk end + 1 500, exactly one.
  - `walkEndThenStillIsAGlance`: `onWalking` at t = 5 000, then still samples → glance at 6 500.
  - `noGlanceWhileNotStill`: settle elapsed with `still = false` → none; the next sample with `still = true` → one.
  - `legacyMatchesOldDwell`: `GlanceParams.LEGACY`, a single 2 rad/s sample at 1 000 → glance at 2 000.
- `MotionClassifierTest`:
  - existing dwell tests (`dwellAfterOneSecondStill`, `noDwellWithoutPriorMotion`, `batchedHeadTurnEmitsOneOnsetAndAnExactDwell`, …) construct `MotionClassifier(clock, GlanceParams.LEGACY)` and keep their expectations;
  - `defaultsIgnoreMicroMotion`: default params, ten 60 ms jerks of 1.5 rad/s spaced 2 s → no dwell, `takeGlanceCounts().micro == 10`;
  - `defaultsDwellAfterRealTurnAndOneAndAHalfSeconds`: 1.5 rad/s for 400 ms → one dwell at last-above + 1 500;
  - `poseIntegratesNetTurn`: z = +1 rad/s for 500 ms → `rotationSinceMarkRad()` ≈ 0.5 (± 0.03); then z = −1 for 500 ms → ≈ 0 (± 0.05); `markPose()` → 0;
  - `deadbandSkipsBias`: 0.03 rad/s for 60 s → `rotationSinceMarkRad() < 0.01`;
  - `walkedSinceMarkAfterWalking`: the 2 Hz walking trace → `walkedSinceMark()`; after `markPose()` → false;
  - `featuresCarryPerSecondStats`: 50 samples of 1.0 rad/s in 1 s → `gyroMean ≈ 1.0`, `aboveOnFrac == 1.0`, `rotRad ≈ 1.0` (± 0.05); a second `features()` → zeros.

**Command:** `gw.sh :glasses:testDebugUnitTest --tests '*GlanceDetectorTest' --tests '*MotionClassifierTest'`, then `gw.sh :glasses:testDebugUnitTest`.

**Commit:** `feat(glasses): glance detector (bout >= 300 ms and 15 deg, settle 1.5 s, micro-motion ignored) and head pose integration`

### Task 3: Novelty gate and the 4-per-minute cap (glasses)
**Spec:** §4, §5.

**Files:**
- `capture/keyframe/KeyframeGate.kt`
- `capture/session/SamplingSession.kt`
- `capture/session/DiagStats` (in `SamplingSession.kt`)
- `capture/CaptureService.kt` (construct with one shared `GlanceParams()`)
- tests: `KeyframeGateTest.kt`, `SamplingSessionTest.kt`, `SamplingTestKit.kt`

**Implement:**
- **`KeyframeGate`:**
  - `private val keptTimes = ArrayDeque<Long>()`, appended on every keep (in `finish` and `recordKeep`), pruned to the last 60 000 ms.
  - `offer(..., capPerMin: Int = Int.MAX_VALUE)` passes the cap to `finish`; `flushWindow(capPerMin: Int = Int.MAX_VALUE)` likewise.
  - In `finish`, after `best` is chosen and before the gap checks: `k = keptTimes.count { now − it < 60_000 }`; `dMin = ring.minOfOrNull { min(hamming(best.d, d), hamming(best.p, p)) } ?: 64`; `if (capPerMin != Int.MAX_VALUE && (k >= 2 * capPerMin || (k >= capPerMin && dMin < BIG_CHANGE))) return Skip(CAPPED)`. Use `Long` arithmetic for `2 * capPerMin` overflow safety.
  - Dwell compares against the **whole ring** (`against = ring`).
  - A `Skip(DUPLICATE)` increments `@Volatile var duplicates` and `Skip(CAPPED)` increments `capped` (read-and-reset by `takeSkipCounts(): Pair<Int, Int>`).
- **`SamplingSession`** (new trailing param `glance: GlanceParams = GlanceParams()`):
  - `start()` calls `motion.markPose()`; `emit` calls `motion.markPose()` after `lastKeyframeAt = now`.
  - `private var resting = false` (set in `tick` by Task 4; false for now) and `private fun capNow() = if (resting) glance.restCapPerMin else glance.capPerMin`.
  - `onDwell`, under `lock`, after `stillStepV = 0`: evaluate spec §4 steps 1–3 using `keptAt.count { now − it < 60_000 }`, `motion.rotationSinceMarkRad()`, `motion.walkedSinceMark()`; on a skip increment `novelSkipAccum` or `cappedAccum` and return without setting `pendingDwellAt`. Else as today.
  - `gate.offer(..., capPerMin = capNow())` and `gate.flushWindow(capNow())`.
  - `DiagStats` gains trailing `glances: Int = 0, micro: Int = 0, novelSkips: Int = 0, capped: Int = 0, same: Int = 0`; `diagSnapshot()` fills them from `motion.takeGlanceCounts()`, the session accumulators and `gate.takeSkipCounts()` (capped = session + gate), then resets.
- **`CaptureService`:** one `val glance = GlanceParams()` passed to `MotionClassifier` and `SamplingSession`.
- **`SamplingTestKit.Rig`** passes its `glance` (default LEGACY) to the session.

**Tests:**
- `KeyframeGateTest`:
  - rename `dwellBypassesLastFive` to `dwellComparesToTheWholeRing`: a dwell on a scene equal to ring entry 3 of 5 → `Skip(DUPLICATE)` (intentional change);
  - `fifthKeepInAMinuteIsCappedUnlessBigChange` (`capPerMin = 4`): four distinct dwell keeps at 0, 5, 10, 15 s; a fifth at 20 s at distance 15 from the ring → `CAPPED`; a fifth at distance ≥ 22 from every ring entry → Keep;
  - `hardCeilingIsTwiceTheCap`: eight big-change keeps within 60 s; the ninth (big change) → `CAPPED`;
  - `capWindowRolls`: after 4 keeps at 0–15 s, a distance-15 keep at 61 s → Keep;
  - `forcedIsExemptFromTheCap`;
  - `defaultCapIsUnlimited`: existing tests unchanged (no `capPerMin` passed).
  Use `ImageHashes`-controlled thumbs from the existing helpers; build distances by flipping known bit counts if the helpers allow, else document the chosen scenes' measured distances in the test.
- `SamplingSessionTest` (new tests pass `glance = GlanceParams()` defaults to the Rig, SETTLE wake):
  - `glanceWithoutHeadTurnSoonAfterAKeepDoesNotOpen`: a keep, then 5 s later a real turn whose net rotation is ~0 (+1 rad/s 300 ms on z then −1 rad/s 300 ms) → no camera open for that glance; `diagSnapshot().novelSkips == 1`;
  - `glanceAfterTwentyOneSecondsOpens`: same, 21 s after the keep → opens;
  - `glanceWithSeventeenDegreeTurnOpens`: z = +1 rad/s for 400 ms → opens;
  - `overCapGlanceIsDroppedBeforeOpening`: after 4 keeps in 50 s (scene changed each time), a 0.5 rad turn → no open, `capped >= 1`; a 1.0 rad turn → opens;
  - existing tests stay green (LEGACY default in the Rig).

**Command:** `gw.sh :glasses:testDebugUnitTest --tests '*KeyframeGateTest' --tests '*SamplingSessionTest'`, then `gw.sh :glasses:testDebugUnitTest`.

**Commit:** `feat(glasses): novelty gate before opening, glance frames deduped against the ring, 4/min keep cap (8 with big changes)`

### Task 4: WorkRest off by default; rest captures glances at 2/min (glasses)
**Spec:** §6.

**Files:**
- `capture/session/WorkRest.kt`, `capture/session/BatteryBudget.kt` (`CaptureProfile.forRest`)
- `capture/session/SamplingSession.kt`
- `GlassesRuntime.kt` (pass `enabled = { settingsCache.capture().workRestEnabled }`)
- tests: `WorkRestTest.kt`, `CameraPolicyTest.kt` (forRest), `SamplingSessionTest.kt`, `SamplingTestKit.kt`

**Implement:**
- `WorkRest(..., enabled: () -> Boolean = { true })`: at the top of `onSampling` and `onPaused`, `if (!enabled()) { _resting.value = null; workStartMs = null; pausedAtMs = null; return }`.
- `CaptureProfile.forRest() = copy(wake = CameraWake.SETTLE, settledCheckMs = maxOf(settledCheckMs, 30_000), quietCheckMs = maxOf(quietCheckMs, 60_000), walkCheckMs = maxOf(walkCheckMs ?: settledCheckMs, 30_000), stillCapMs = maxOf(stillCapMs, 120_000))`.
- `SamplingSession.tick`: remove `if (why == null && workRest.resting.value != null) why = "rest"`. Instead `resting = workRest.resting.value != null` (under `lock`) and `val prof = if (resting) profile().forRest() else profile()`. `restTick` is no longer called (delete it and `nextRestCaptureAt`; keep the `fixedCapture` constructor param, KDoc "unused since wave A3 rest; kept for call-site compatibility"). `publish` already reports `restingUntilMs`.
- Rest → work and work → rest reset `stillStepV = 0` (profile change).

**Tests:**
- `WorkRestTest`: `disabledNeverRests` (enabled false, 3 h of `onSampling`) → `resting` stays null; `disablingMidRestClears`; existing tests unchanged (default enabled).
- `CameraPolicyTest`: `forRest` of DEFAULT → SETTLE, 30 000, 60 000, walk 30 000, stillCap 120 000; of T2 → settled 30 000, quiet 60 000.
- `SamplingSessionTest`:
  - rewrite `restUsesFixedEvery60s` as `restKeepsSamplingAtTwoPerMinute` (intentional change): `workRestEnabled = true`, work 1 min / rest 5 min (Rig `work = 1, rest = 5` via `WorkRest` clamps), defaults `GlanceParams()`; during rest, six real glances on changing scenes 10 s apart → `status.running == true`, `restingUntilMs != null`, keyframes in rest ≤ 2 per rolling minute, `fixedCaptures == 0`;
  - `fallbackAndRestFixedCapturesNeverRunInsideTheSessionBlock`: keep the fallback assertion; drop the rest-Fixed part;
  - `workRestDisabledByDefaultNeverRests`: default settings, 2 h → never `restingUntilMs`;
  - other rest tests pass `workRestEnabled = true` explicitly.

**Command:** `gw.sh :glasses:testDebugUnitTest --tests '*WorkRestTest' --tests '*CameraPolicyTest' --tests '*SamplingSessionTest'`, then `gw.sh :glasses:testDebugUnitTest`.

**Commit:** `feat(glasses): WorkRest off by default; rest keeps glance capture at 2/min instead of one Fixed photo a minute`

### Task 5: Motion sources, MotionModel and the labelled feature CSV (glasses)
**Spec:** §8.

**Files:**
- new `capture/motion/source/MotionSignalSource.kt` (enum, evidence, interface), `capture/motion/source/MotionFusion.kt`, `capture/motion/source/GlassesImuSource.kt`
- new `capture/motion/model/MotionModel.kt` (`SecondFeatures`, `MotionModel`, `HeuristicMotionModel`, `TreeMotionModel`)
- new `capture/motion/MotionCsv.kt`
- `capture/motion/SensorFeed.kt` (`AndroidSensorFeed`: the per-second tick)
- `capture/CaptureService.kt` (pass the label provider and `filesDir`)
- tests: new `MotionFusionTest.kt`, `MotionModelTest.kt`, `MotionCsvTest.kt`, `GlassesImuSourceTest.kt`; `AndroidSensorFeedTest.kt`

**Implement:**
- Types exactly as spec §8. `MotionFusion.fuse`: filter `nowMs − atMs <= 3_000 && confidence >= 0.5f`; let `g` = the `glasses_imu` evidence; `g == null` → the highest-confidence other or null; `g.state == HEAD_MOVING` → g; `g.state == STILL` and some other WALKING with confidence ≥ 0.7 → that one; else g.
- `GlassesImuSource(model: MotionModel)`: `id = "glasses_imu"`, `kind = GLASSES_IMU`, `available = MutableStateFlow(true)`, `evidence` a `MutableSharedFlow(extraBufferCapacity = 4, DROP_OLDEST)`; `fun onSecond(f: SecondFeatures, tMs: Long)` emits `MotionEvidence(tMs, model.classify(f), 1.0f, id)` and returns it.
- `HeuristicMotionModel(p: GlanceParams = GlanceParams())` (`id = "heuristic-v1"`) and `TreeMotionModel` (`id` param) per spec §8. `TreeMotionModel` validates equal array sizes in `init` (`require`).
- `MotionCsv(dir: File, maxBytes: Long = 4L * 1024 * 1024)`: `fun append(row: String)`; creates the file with the header when absent or empty; when `length() >= maxBytes`, renames to `.1` (replacing it) and starts a new file with the header. Never throws (`runCatching`, counts failures).
- `AndroidSensorFeed`: new internal-constructor params `label: () -> MotionLabel = { MotionLabel.NONE }`, `csvDir: File? = null`; in the existing per-second tick, after `features.log(f)`: build `SecondFeatures` from `f`, `ev = imuSource.onSecond(sf, now)`, `fused = MotionFusion.fuse(listOf(ev), now)`; if `csvDir != null && (verboseFeatures || label() != NONE)`, append one row (`String.format(Locale.US, …%.3f…)`, columns in header order; `rate` = current `SensorRate.name.lowercase()`).
- `CaptureService.startSmart`: `AndroidSensorFeed(this, verbose, label = { runtime.settingsCache.capture().motionLabel }, csvDir = filesDir)` (add the public-constructor params with defaults).

**Tests:**
- `MotionFusionTest`: glasses only → identity; stale (3 001 ms) other ignored; glasses STILL + other WALKING 0.8 → WALKING; + WALKING 0.6 → STILL; glasses HEAD_MOVING + other WALKING 0.9 → HEAD_MOVING; confidence 0.4 dropped; empty → null.
- `MotionModelTest`: heuristic: accVar 0.3 / stepHz 2.0 → WALKING; aboveOnFrac 0.5 / rotRad 0.4 → HEAD_MOVING; aboveOnFrac 0.5 / rotRad 0.1 → STILL; zeros → STILL. Tree: a hand-built depth-2 tree (`feature = [0, 4, -2, -2, -2]`, `threshold = [0.6, 0.25, 0, 0, 0]`, `left = [1, 3, -1, -1, -1]`, `right = [2, 4, -1, -1, -1]`, `leafClass = [0, 0, 1, 0, 2]`) → gyroMax 0.2 / accVar 0.1 → STILL; gyroMax 0.2 / accVar 0.4 → WALKING; gyroMax 0.9 → HEAD_MOVING; mismatched sizes → `IllegalArgumentException`.
- `MotionCsvTest` (temp dir): first append writes header + row; second append adds one line; with `maxBytes = 200`, rows past it rotate to `.1` and the new file starts with the header; a row never contains a comma-free text field other than enum names (assert the row matches `^[0-9.,A-Z_a-z-]+$`).
- `GlassesImuSourceTest`: `onSecond` emits one evidence with confidence 1.0 and `available.value == true`.
- `AndroidSensorFeedTest`: label NONE and not verbose → no CSV file; label EATING → one row per tick with `,EATING,` and 15 columns.

**Command:** `gw.sh :glasses:testDebugUnitTest --tests '*MotionFusionTest' --tests '*MotionModelTest' --tests '*MotionCsvTest' --tests '*GlassesImuSourceTest' --tests '*AndroidSensorFeedTest'`, then `gw.sh :glasses:testDebugUnitTest`.

**Commit:** `feat(glasses): motion sources (glasses IMU) with fusion, MotionModel interface (heuristic, tree) and labelled per-second feature CSV`

### Task 6: Screen-wake attribution, nudge gate, heartbeat counters (glasses)
**Spec:** §7, §9 (heartbeat).

**Files:**
- new `capture/diag/ScreenWakeStats.kt`
- `GlassesRuntime.kt` (`val screenWakes = ScreenWakeStats(SystemClock::elapsedRealtime)`)
- `MainActivity.kt` (`onPhoneOverlay`, the Confirm launch, the "opened from phone" pop-up)
- `capture/ForegroundFallback.kt` (an `onLaunch: () -> Unit = {}` hook)
- `capture/CaptureService.kt` (screen receiver, heartbeat fields)
- `capture/diag/Heartbeat.kt`
- `tools/battery-run.sh` summary keys (coordinate with Task 7 if it runs in parallel: Task 6 adds only the four `_mean` keys below)
- tests: new `ScreenWakeStatsTest.kt`, new `PopupGateTest.kt`, `HeartbeatTest.kt`

**Implement:**
- `ScreenWakeStats(clock)`: `onOurUi()` records `lastOurUiMs`; `onScreenOn()` increments `on` and, when `clock() − lastOurUiMs in 0..2_000`, `ours`; `onNudgeSkipped()` increments `nudgeOff`; `take(): Triple<Int, Int, Int>` returns and resets. `@Synchronized`.
- `object PopupGate { fun show(o: Overlay?, interactive: Boolean): Boolean = o !is Overlay.Nudge || interactive }`.
- `MainActivity.onPhoneOverlay`: before `runtime.popup.show(...)`, `if (!PopupGate.show(o, pm.isInteractive)) { runtime.screenWakes.onNudgeSkipped(); return }`; call `runtime.screenWakes.onOurUi()` right before every `popup.show` and the Confirm `startActivity`. `ForegroundFallback(presence, …, onLaunch = { screenWakes.onOurUi() })` from `GlassesRuntime`, called just before its `startActivity`.
- `CaptureService`: register a dynamic receiver for `Intent.ACTION_SCREEN_ON` in `onCreate` (unregister in `onDestroy`, `runCatching`) → `runtime.screenWakes.onScreenOn()`.
- `Heartbeat`: trailing `val scrOn: Int = 0, val scrOurs: Int = 0, val nudgeOff: Int = 0`; `format()` appends ` glances1m=${perMin(stats.glances)} micro1m=${perMin(stats.micro)} novelSkip1m=${perMin(stats.novelSkips)} capped1m=${perMin(stats.capped)} same1m=${perMin(stats.same)} scrOn=$scrOn scrOurs=$scrOurs nudgeOff=$nudgeOff`. `CaptureService` fills them from `runtime.screenWakes.take()`.
- `tools/battery-run.sh summary`: sum `glances1m`, `capped1m`, `same1m`, `novelSkip1m` and print ` glances1m_mean=%.1f capped1m_mean=%.1f same1m_mean=%.1f novelSkip1m_mean=%.1f` after `opens1m_mean`; fixture heartbeat lines get `glances1m=6 capped1m=2 same1m=1 novelSkip1m=3`, `glances1m=4 capped1m=0 same1m=1 novelSkip1m=1`, `glances1m=2 capped1m=1 same1m=1 novelSkip1m=2` (in window) and `glances1m=99` (out); `tools/test-battery-run.sh` expects `opens1m_mean=6.0 glances1m_mean=4.0 capped1m_mean=1.0 same1m_mean=1.0 novelSkip1m_mean=2.0`.

**Tests:**
- `ScreenWakeStatsTest`: on without UI → on 1 / ours 0; UI at 0, on at 1 500 → ours 1; UI at 0, on at 2 500 → ours 0; `take()` resets; nudge count.
- `PopupGateTest`: Nudge + not interactive → false; Nudge + interactive → true; Answer / Toast / Confirm + not interactive → true.
- `HeartbeatTest`: the full-sample string gains the appended keys (glances 6, micro 20, novelSkips 3, capped 2, same 1 over 60 s; scrOn 2, scrOurs 0, nudgeOff 1); over a 120 s span `glances = 6` → `glances1m=3`; defaults → zeros.

**Command:** `gw.sh :glasses:testDebugUnitTest --tests '*ScreenWakeStatsTest' --tests '*PopupGateTest' --tests '*HeartbeatTest'`, then `bash tools/test-battery-run.sh` (from `/Users/debasishkanhar/Desktop/rokid/pca-bat/int`), then `gw.sh :glasses:testDebugUnitTest`.

**Final gate (after Tasks 1–7):** from the repo root, `tools/gate.sh /Users/debasishkanhar/Desktop/rokid/pca-int/gate-wave-a3.log` exits 0. A known flaky `PushToTalkTest` alone may be re-run once. If a wave-2 orchestration test fails, report it; do not fix it here.

**Commit:** `feat(glasses): screen-wake attribution and nudge gate while the screen is off; heartbeat glance, cap and dedupe counters`

### Task 7: Measurement tools: per-process CPU sampler and the training script
**Spec:** §9 (CPU sampler), §8 (training). Touches only `tools/`; may run in parallel with Tasks 1–6.

**Files:**
- `tools/battery-run.sh`, `tools/test-battery-run.sh`, new `tools/testdata/battery-run/cpu.log`, `tools/testdata/battery-run/batt_run.txt` (add `clk_tck=100`)
- new `tools/motion-train/train.py`, new `tools/motion-train/testdata/sample.csv`, new `tools/test-motion-train.sh`

**Implement:**
- **`cmd_start`:** record `clk_tck=$(adb shell getconf CLK_TCK 2>/dev/null || echo 100)` in `$RUNFILE`; kill any old `[c]pu_logger`; launch a second `setsid nohup sh -c '… # cpu_logger' > /data/local/tmp/cpu.log` loop, every 60 s:
  - for each `label:pattern` in `app:^com.rokid.context.glasses$ ss:^system_server$ cam:^cameraserver$ camprov:camera.provider sens:sensors bt:bluetooth audio:^audioserver$ assist:assistserver`: `pid` = first match of `ps -A -o PID,NAME` (use `grep -E` and `cut`; no awk on the device); read `/proc/$pid/stat`, strip through `") "` with `sed 's/.*) //'`, then `set -- $rest` → `ticks=$((${12} + ${13}))`; print `"$T cpu $label pid=$pid ticks=$ticks"` (`pid=0 ticks=0` when absent).
  - system_server threads: for each `/proc/$sspid/task/*`, `comm` sanitised with `tr -cd 'A-Za-z0-9_.:-'`, ticks as above; sort by ticks descending (`sort -t= -k3 -n -r`), keep 12, print `"$T thr ss $comm ticks=$n"`.
- **`cmd_stop`:** also kills `[c]pu_logger`. **`cmd_pull`:** pulls `cpu.log`, then rewrites it through the existing `grep -v -i -E "<handoff §2 pattern>"` (without `AssistServer`, since only the label `assist` is written) and counts lines.
- **`cmd_summary`:** if `cpu.log` exists, a second awk pass: per label, the in-window first and last samples; `cpu_s_per_min = Σ(segment Δticks) / clk_tck / (last − first minutes)`, where a pid change starts a new segment; print `cpu_s_per_min app=%.2f ss=%.2f cam=%.2f camprov=%.2f sens=%.2f bt=%.2f audio=%.2f assist=%.2f` and `ss_threads=<comm>:<%.2f>,…` (top 8 by s/min). `summary <dir> minutes` prints one line per heartbeat minute: `HH:MM opens1m=.. kf1m=.. glances1m=.. app=.. ss=.. cam=.. camprov=..` (CPU from consecutive samples in that minute; `na` when missing).
- **Fixture `cpu.log`:** two samples per label exactly 2 min apart inside the fixture window: app 1000 → 1600, ss 5000 → 7400, cam 2000 → 2900, camprov 100 → 340, sens 50 → 110, bt 10 → 70, audio 0 → 0, assist 300 → 1500; threads `thr ss binder:1_2` 100 → 340 and `thr ss SensorService` 20 → 80. One out-of-window app sample with ticks 999999.
- **`tools/test-battery-run.sh`:** expect `cpu_s_per_min app=3.00 ss=12.00 cam=4.50 camprov=1.20 sens=0.30 bt=0.30 audio=0.00 assist=6.00` and `ss_threads=binder:1_2:1.20,SensorService:0.30`. Existing expectations stay.
- **`tools/motion-train/train.py`** (Python 3, `argparse`; imports `sklearn` lazily with a clear error): `train.py <csv>... [--out TrainedMotionModel.kt] [--logistic]`. Behaviour as spec §8; the exported Kotlin is `object TrainedMotionModel { val model = TreeMotionModel("tree-<yyyymmdd>", intArrayOf(..), floatArrayOf(..), intArrayOf(..), intArrayOf(..), intArrayOf(..)) }` with sklearn's `tree_.feature` (leaves −2), `threshold`, `children_left`, `children_right` and `argmax(value)` mapped to `[STILL, HEAD_MOVING, WALKING]`; feature order = the CSV columns `gyro_max … rot_rad` = `SecondFeatures.toArray()`.
- **`tools/motion-train/testdata/sample.csv`:** the spec header plus 120 synthetic rows (40 SITTING with low gyro, 40 CHORES with high gyro/aboveOnFrac, 40 WALKING with accVar 0.4 / stepHz 1.8, two label switches).
- **`tools/test-motion-train.sh`:** exits 0 with `skip: no sklearn` when `python3 -c 'import sklearn'` fails; else runs `train.py` on the sample into a temp file and checks that it contains `TreeMotionModel(` and that the printed leave-one-out accuracy line exists.

**Tests / Command:** `bash tools/test-battery-run.sh`, `bash tools/test-motion-train.sh` (from the repo root). No Gradle.

**Commit:** `feat(tools): per-process and system_server-thread CPU sampler in battery-run, motion-model training script`

### Task 8: Device checklist (not code; for when the user is present)
Do not run this without the user. Bluetooth toggles, uninstalls and permission revokes each need an explicit OK. Read only `files/capture_diag.log`, `files/motion_features.csv` and the `battery-run.sh` outputs; never logcat unfiltered (handoff §2 filter).

1. **Install the A3 build** on both devices (`adb -s <dev> install -r <apk>`, keeping data; re-grant `SYSTEM_ALERT_WINDOW` if needed). Check the heartbeat: `wake=SETTLE`, `target_h=0`, and no `restingUntil` (Work and rest shows Off on the phone; turn it off if a stored value says on). Battery target Off. Motion label None. Note the device's `CLK_TCK` line in `batt_run.txt`.
2. **One worn 20-min run**, unplugged, label `A3w`: `G=1901092545029120 tools/battery-run.sh start A3w`, then in this order (the user notes the clock time of each step):
   - sit still 3 min looking at one thing; then glance twice at two different things about 45° away, holding each about 2 s;
   - chores 6 min (moving around, picking things up);
   - eat or drink something sitting down, 4 min;
   - a door/delivery-like sequence: get up, walk to the door, open it, look at a person or object there for about 3 s, take something, walk back, look at it on a table for 3 s;
   - sit for the rest.
   Then `stop`, `pull ~/Desktop/rokid/battery-runs/A3w`, `summary …` and `summary … minutes`.
3. **Expect:**
   - `kf1m` ≤ 4 in every heartbeat (≤ 8 only where `capped1m` stays low and the scene changed);
   - sitting and eating minutes: `micro1m` high, `glances1m` low, `opens1m` ≤ 2; the two deliberate glances each produce one keyframe;
   - chores: `opens1m` about 4–6 (A2: 8–14); `novelSkip1m + capped1m` account for the drop;
   - the door sequence yields at least 2 keyframes (door, and the thing on the table);
   - `stillGapMs` climbs above 10 000 while sitting;
   - `scrOurs` = 0 (if not, note the minute and what was on screen);
   - `pct_per_min` vs A2's 0.6 overall / 0.75 active; `cpu_s_per_min` for cam/camprov/ss vs A2 batterystats (cameraserver 5.5 min/51 min ≈ 6.5 s/min, system_server ≈ 23 s/min); the `ss_threads` top 8 for what dominates system_server.
4. **Labelled data (optional, same session or after):** set Motion labels on the phone to Sitting 3 min → Glancing 3 min → Chores 5 min → Eating 4 min → Walking 3 min → None. Pull `adb -s <glasses> exec-out run-as com.rokid.context.glasses cat files/motion_features.csv > …` and run `python3 tools/motion-train/train.py …` for a first confusion matrix (do not ship the model in A3).
5. **Write the results** into handoff §6 (an A3w row with the per-minute table summary and CPU breakdown) and revisit the spec rulings §3 (thresholds), §4 (novelty 20 s / 17°), §5 (cap 4 / BIG_CHANGE 22) and §6 (WorkRest Off) with the user.
