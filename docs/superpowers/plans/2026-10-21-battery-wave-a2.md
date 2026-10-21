# Battery wave A2: implementation plan (capture calibration)

**Spec:** `docs/superpowers/specs/2026-10-21-battery-wave-a2-calibration.md`. Read §2 (code facts) and the section your task names before starting.

**Where to work:** `/Users/debasishkanhar/Desktop/rokid/pca-bat/int` (branch `battery/integration`, wave A already built there). Tasks run **sequentially**: each one starts from the previous task's commit. Use TDD: write the test, watch it fail (RED), implement, watch it pass (GREEN), commit.

## Global Constraints
- **Modules:** `:core:model` (Kotlin JVM), `:services:glasses-link` (Android library), `:glasses`, `:phone`. Package roots:
  - `com.rokid.context.core.model`
  - `com.rokid.context.link`
  - `com.rokid.context.glasses`
  - `com.rokid.context.phone`
- Glasses paths below are relative to `android-companion/glasses/src/{main,test}/java/com/rokid/context/glasses/`.
- **Wire:**
  - `PROTOCOL_VERSION` stays **1**.
  - Only **additive** fields with defaults, and new channels. Old glasses ↔ new phone, and the reverse, must still parse.
  - An unknown enum value decodes to the property default (`Wire` has `coerceInputValues`).
  - `Wire` has `encodeDefaults = true`.
- **Room:** no change (DB version stays 2).
- **Exact new names and values** (do not rename):
  - `HudSettings.batteryTargetHours: Int = 0`; `HudSettings.cameraWake: CameraWake = CameraWake.SETTLE`.
  - `CaptureProfile.stillCapMs: Long = 60_000` (a new **trailing** constructor parameter with a default; 60 000 in every tier).
  - `CameraPolicy.stillGap(baseMs: Long, step: Int, capMs: Long): Long` = `if (baseMs >= capMs) baseMs else minOf(baseMs shl step, capMs)`.
  - `CameraPolicy.decide(..., stillStep: Int = 0)` (new trailing parameter).
  - `KeyframeGate.lastHashes(): Pair<Long, Long>?`.
  - The `SamplingSession` getters `val stillStep: Int` and `fun stillGapMs(): Long`.
  - Wake reasons (strings): `timer`, `dwell`, `onset`, `now`, `stream`.
  - `enum class SensorRate { FULL, STILL, LOW }`:

    | Rate | Period (µs) | Latency (µs) |
    |---|---|---|
    | FULL | GAME | 0 |
    | STILL | `50_000` | `500_000` |
    | LOW | `100_000` | `1_000_000` |

    `SensorRatePolicy.STILL_AFTER_MS = 10_000`.
  - `FeatureLogger.MIN_GAP_MS = 30_000`; `StatusLogThrottle.MIN_GAP_SEC = 30`.
  - `DiagFlags.VERBOSE_FEATURES_FILE = "diag_verbose_features"`.
  - The heartbeat keys `opens1m`, `wk`, `stillGapMs`, appended after `restartGapSec`; the prefix stays `heartbeat v=2`.
- **Never touch:**
  - the camera LED (`LedSourceRulesTest` must stay green);
  - Rokid system services.

  Logs carry counts and states only: never captions, user text, device names, tokens or `GlassInfo.toString()`.
- **Gradle:**
  - Run from `/Users/debasishkanhar/Desktop/rokid/pca-bat/int/android-companion` through the throttled wrapper `/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/.superpowers/sdd/gw.sh <args>`. It calls `./gradlew --no-daemon`.
  - **One Gradle run at a time.** Never `gradlew --stop`.
  - Unit-test commands:
    - `:core:model:test --tests '<Class>'`
    - `:glasses:testDebugUnitTest --tests '<Class>'`
    - `:phone:testDebugUnitTest --tests '<Class>'`
  - Run the whole module's tests before committing.
- **No device.** No `adb` in Tasks 1–5. No push.
- **Secrets.** Never print `.env` values, keys or tokens.
- **Commits:** local only, one per task, message as given, ending with the trailer `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- **Backward compatibility:**
  - New constructor or function parameters get defaults, so existing call sites compile unchanged.
  - Fix an existing test that relies on an old *default* by passing the old value explicitly (e.g. `cameraWake = CameraWake.MOTION`). Never change behaviour to keep it green.

### Task 1: Defaults: battery target Off, Settle wake (core:model + phone)
**Spec:** §4 and §7.

**Files:**
- `android-companion/core/model/src/main/kotlin/com/rokid/context/core/model/Protocol.kt`
- `android-companion/core/model/src/test/kotlin/com/rokid/context/core/model/ProtocolTest.kt`
- `android-companion/phone/src/main/java/com/rokid/context/phone/ui/settings/capture/CaptureSettings.kt`
- `android-companion/phone/src/test/java/com/rokid/context/phone/ui/settings/capture/CaptureSettingsTest.kt`

**Implement:**
- `HudSettings`: `batteryTargetHours: Int = 0` and `cameraWake: CameraWake = CameraWake.SETTLE`. Update the KDoc: "default Off (wave A2: last-resort lever)" and "default SETTLE (wave A2)".
- `CaptureSettingsUiState`: `batteryTargetHours = 0`, `wake = CameraWake.SETTLE`.
- Leave `BATTERY_TARGET_HOURS_ALLOWED`, `normalized()` and the UI rows unchanged.

**Tests:**
- `ProtocolTest`:
  - `oldSettingsJsonDecodesToBatteryDefaults` now expects SETTLE / 0 / false;
  - `"cameraWake":"WARP"` decodes to SETTLE (rename the test to `unknownCameraWakeDecodesToSettle`);
  - `HudSettings().batteryTargetHours == 0`;
  - `PROTOCOL_VERSION == 1`.
- `CaptureSettingsTest`:
  - rename `batteryTargetDefaultsTo4hAndOffWritesZero` to `batteryTargetDefaultsToOffAndPickingFourWritesFour`. It checks content description `"Battery target: Off"`, then `pick("battery_target", 2)` → 4, then the existing `setBatteryTarget` 5 / 7 cases;
  - a default `CaptureSettingsUiState()` shows the Settle note and hides `quick_look`;
  - tests that assumed Smart by default (e.g. `smartMotionShowsQuickLookNotSettleNote`, the `statusLine` "Smart · …" cases) pass `wake = CameraWake.MOTION` explicitly.

**Command:** `gw.sh :core:model:test`, then `gw.sh :phone:testDebugUnitTest --tests '*CaptureSettingsTest'`, then `gw.sh :phone:testDebugUnitTest`, then `gw.sh :glasses:testDebugUnitTest` (glasses tests that read `HudSettings().cameraWake` get MOTION passed explicitly).

**Commit:** `feat(model): battery target defaults to Off, camera wake defaults to Settle (wave A2)`

### Task 2: Still-scene backoff (glasses)
**Spec:** §3.

**Files:**
- `capture/session/BatteryBudget.kt` (holds `CaptureProfile`)
- `capture/session/CameraPolicy.kt`
- `capture/keyframe/KeyframeGate.kt`
- `capture/session/SamplingSession.kt`
- tests: `CameraPolicyTest.kt`, `KeyframeGateTest.kt`, `SamplingSessionTest.kt`, `SamplingTestKit.kt` (FakeCamera)

**Implement:**
- **`CaptureProfile`:** a trailing `val stillCapMs: Long = 60_000L`; `of()` leaves it at the default.
- **`CameraPolicy`:**
  - `fun stillGap(baseMs: Long, step: Int, capMs: Long): Long`, as defined in the Global Constraints;
  - a new trailing param `stillStep: Int = 0` on `decide`.
  - **MOTION, settled branch:** `var checkMs = stillGap(if (lowPower) LOW_POWER_CHECK_MS else profile.settledCheckMs, stillStep, profile.stillCapMs)`, then the existing quiet `maxOf`, `× slow`, and the motion-end `minOf`, all unchanged.
  - **SETTLE, settled branch:** `var settleMs = stillGap(maxOf(if (lowPower) LOW_POWER_CHECK_MS else 0L, profile.settledCheckMs), stillStep, profile.stillCapMs)`, then quiet and slow as today.
  - WALKING, HEAD_MOVING, decelerating and hold are unchanged.
- **`KeyframeGate`:**
  - `private var lastScoredHashes: Pair<Long, Long>? = null`, set in `offer` right after the hashes are computed (after the blur check; BLUR skips leave it unchanged);
  - `@Synchronized fun lastHashes(): Pair<Long, Long>? = lastScoredHashes`.
- **`SamplingSession`** (all under `lock`):
  - **Fields:**
    - `private var stillStepV = 0`, exposed as `val stillStep: Int get() = synchronized(lock) { stillStepV }`;
    - `private var lastCheckHash: Pair<Long, Long>? = null`;
    - `private var lastProfile = CaptureProfile.DEFAULT`, set in `tick`;
    - `private var lastLowPower = false`, set in `tick`;
    - `private var wasBlocked = true`.
  - **`Episode`:** gets `val why: String`. `startEpisode(continuous, now)` computes it, in this priority: `continuous` → `stream`; `pendingNowAt != null` → `now`; `pendingDwellAt != null` → `dwell`; `forceBrief` → `onset`; else `timer`.
  - **`tick`:** passes `stillStep = stillStepV` to `decide`. After `blocked = why`, when `wasBlocked && why == null` it resets `stillStepV = 0`; then `wasBlocked = why != null`. Session start counts as blocked → running, so the first tick resets.
  - **`onOnset` and `onDwell`:** both set `stillStepV = 0` (`onOnset` in both modes, before its MOTION-only block).
  - **`onFrame`**, inside the existing `gate.offer` synchronized block, read `val h = gate.lastHashes()` right after `offer`. Then, in the final synchronized block, when `usable && !ep.continuous`, call `updateBackoff(ep, h, result, sampleMotion)`:
    1. `result is GateResult.Keep` → `stillStepV = 0`.
    2. Else, when `ep.why == "timer" && sampleMotion == MotionState.STILL && h != null && lastCheckHash != null`:
       - same (`minOf(bitCount(h.first xor prev.first), bitCount(h.second xor prev.second)) <= KeyframeGate.HAMMING`) → `if ((base shl stillStepV) < lastProfile.stillCapMs) stillStepV++`;
       - else `stillStepV = 0`.

       Here `base` = the same base the policy used: MOTION `if (lastLowPower) 10_000 else settledCheckMs`; SETTLE `maxOf(if (lastLowPower) 10_000 else 0, settledCheckMs)`. Put it in a private `backoffBase()`.
    3. Then `if (h != null) lastCheckHash = h`.
  - **Continuous episodes:** `endEpisode` of a continuous episode sets `lastCheckHash = null`.
  - **`fun stillGapMs(): Long`** = `synchronized(lock) { CameraPolicy.stillGap(backoffBase(), stillStepV, lastProfile.stillCapMs) }`.
- **`SamplingTestKit.FakeCamera`:** add `val openTimes = ArrayList<Long>()`, appended in `open`, if it is not there already.

**Tests:**
- `CameraPolicyTest`:
  - `stillGap`: (5 000, 0..5, 60 000) → 5 000, 10 000, 20 000, 40 000, 60 000, 60 000; (20 000, 2, 60 000) → 60 000; (90 000, 3, 60 000) → 90 000.
  - MOTION DEFAULT settled with `lastBriefAtMs = 0`, `msSinceMotionEnd = null`: stillStep 0 → next 5 000; stillStep 3 → 40 000; stillStep 5 → 60 000.
  - MOTION lowPower, step 1 → 20 000.
  - MOTION quiet, step 0 → 15 000; step 2 → 20 000.
  - heatSlow, step 4 → 120 000.
  - SETTLE T0 step 0..4 → 10 000, 20 000, 40 000, 60 000, 60 000; T2 step 1 → 40 000.
  - SETTLE WALKING step 3 → `lastBrief + 10 000` (no backoff).
  - SETTLE HEAD_MOVING → closed with null.
  - The existing tests stay green (`stillStep` defaults to 0).
- `KeyframeGateTest`:
  - `lastHashes()` is null before any offer;
  - after a non-blurred offer it equals `ImageHashes.dHash(thumb)` to `pHash(thumb)`;
  - a BLUR skip leaves it unchanged.
- `SamplingSessionTest` (Rig, no sensors, so the state is STILL; the scene is constant):
  - `stillSceneBacksOffToSixtySeconds`, MOTION DEFAULT: run 200 s; the gaps between consecutive `camera.openTimes` are within ±1 000 ms of [5 000, 10 000, 20 000, 40 000, 60 000, 60 000]; `session.stillGapMs() == 60_000`.
  - `settleStillSceneBacksOff`, SETTLE T0: the gaps are about [10 000, 20 000, 40 000, 60 000].
  - `changedSceneResetsTheBackoff`: once `stillStep >= 2`, set `scene = 2`. The next check resets `stillStep` to 0, and the gap after it is about 5 000.
  - `onsetResetsTheBackoff`: once `stillStep >= 2`, call `motion.onGyro(2f, 0f, 0f, clock())` → `stillStep == 0`.
  - `dwellResetsTheBackoff`, SETTLE: `startSensors()`; once step ≥ 2, `gyroMag = 3f` for 300 ms, then 0. The dwell check opens (the glance promise, unchanged), and the next timer gap is about 10 000.
  - `unblockResetsTheBackoff`: once step ≥ 2, `paused = true` for 2 s, then false → `stillStep == 0` on the next tick.
  - The existing dwell, onset and Settle tests stay green.

**Command:** `gw.sh :glasses:testDebugUnitTest --tests '*CameraPolicyTest' --tests '*KeyframeGateTest' --tests '*SamplingSessionTest'`, then `gw.sh :glasses:testDebugUnitTest`.

**Commit:** `perf(glasses): still-scene backoff of brief checks 5 s -> 60 s (reset on motion, settle, scene change, unblock)`

### Task 3: STILL IMU rate and batched-input-safe classifier (glasses)
**Spec:** §5.

**Files:**
- `capture/motion/SensorFeed.kt`
- `capture/motion/MotionClassifier.kt`
- `capture/session/SensorRatePolicy.kt`
- `capture/CaptureService.kt`
- tests: `AndroidSensorFeedTest.kt`, `MotionClassifierTest.kt`, `SensorRatePolicyTest.kt`

**Implement:**
- **`SensorRate`:** `{ FULL, STILL, LOW }`. Update its KDoc.
- **`AndroidSensorFeed.registerAll`** maps each rate to period and latency per the Global Constraints, with the constants `STILL_PERIOD_US = 50_000` and `STILL_LATENCY_US = 500_000`. `setRate` keeps its re-register-on-change behaviour.
- **In `start()`:** once, `runCatching { DiagLog.i(TAG, "sensors fifo gyro=${gyro?.fifoMaxEventCount ?: -1} acc=${acc?.fifoMaxEventCount ?: -1}") }`. Hold the gyro and accel `Sensor`s in locals for it.
- **`MotionClassifier`:**
  - `private var newestMs = Long.MIN_VALUE`.
  - In `onGyro` and `onLinearAccel`, first `newestMs = maxOf(newestMs, tMs); val t = newestMs`.
  - Use `t` for `update(t)`, `checkDwell(t)` and the `peak` hold test (`t - lastAboveGyroMs >= HEAD_HOLD_MS`).
  - Assign `lastAboveGyroMs = maxOf(lastAboveGyroMs, tMs)` and `stillSinceMs = maxOf(stillSinceMs, tMs)` (also in `onLinearAccel`'s walking branch).
  - The accel window (`accT`, `analyseWindow(tMs)`) keeps the raw `tMs`.
  - Onset and dwell emit `t`.
- **`SensorRatePolicy.update(reason: String?, nowMs: Long, state: MotionState? = null): SensorRate`:**
  - The LOW rule is unchanged and takes precedence.
  - Else, when `reason == null && state == MotionState.STILL`: start `stillSinceMs` if it is unset, and return `STILL` once `nowMs - stillSinceMs >= STILL_AFTER_MS`, else `FULL`.
  - Else reset `stillSinceMs = -1` and return FULL.
  - A LOW-reason path also resets `stillSinceMs`.
- **`CaptureService`:**
  - `Smart` gains `val motion: MotionClassifier` and `var rateJob: Job?`;
  - `private fun applyRate()` = `smart?.let { it.feed.setRate(ratePolicy.update(runtime.captureStatus.value.reason, SystemClock.elapsedRealtime(), it.motion.state.value)) }`;
  - the supervisor calls `applyRate()` in place of the current line;
  - `startSmart` launches `rateJob = scope.launch { motion.state.collect { if (it != MotionState.STILL) applyRate() } }`, and `stopSmart` cancels it;
  - the heartbeat `sensors=` already prints `rate.name.lowercase()` (now `full|still|low`).

**Tests:**
- `SensorRatePolicyTest`:
  - running + STILL: 9 999 ms → FULL, 10 000 → STILL;
  - HEAD_MOVING → FULL at once, and the timer restarts (another 10 000 ms is needed);
  - a null state → FULL always;
  - `not_worn` for 10 000 → LOW whatever the state;
  - unblocked to STILL → FULL until 10 000 ms more;
  - the existing cases unchanged.
- `AndroidSensorFeedTest`:
  - `setRate(STILL)` registers period 50 000 and latency 500 000;
  - STILL → FULL gives GAME / 0;
  - STILL → LOW gives 100 000 / 1 000 000;
  - STILL twice re-registers once.
- `MotionClassifierTest` (20 Hz, 50 ms step, delivered in 500 ms batches: per batch, all gyro samples first, then all accel samples at zero):
  - `batchedHeadTurnEmitsOneOnsetAndAnExactDwell`: still 0–1 950 ms, gyro 2 rad/s at 2 000–2 350, still to 4 000. The onsets are exactly `[2000]`; one dwell at `3350`; the final state is STILL.
  - The same trace without the clamp would emit two onsets. Document this in the test KDoc.
  - `walkingDetectedAt20Hz`: the existing 2 Hz walking trace generator at a 50 ms step → WALKING within 3 000 ms.
  - All existing tests stay green.

**Command:** `gw.sh :glasses:testDebugUnitTest --tests '*SensorRatePolicyTest' --tests '*AndroidSensorFeedTest' --tests '*MotionClassifierTest' --tests '*WearDetectorTest'`, then `gw.sh :glasses:testDebugUnitTest`.

**Commit:** `perf(glasses): STILL IMU rate (20 Hz, 0.5 s batching) after 10 s still; classifier safe for batched streams`

### Task 4: Diagnostics throttle (glasses)
**Spec:** §6 (FeatureLogger, status line).

**Files:**
- `capture/motion/FeatureLogger.kt`
- `capture/motion/SensorFeed.kt`
- new `capture/diag/StatusLogThrottle.kt`
- new `capture/diag/DiagFlags.kt`
- `capture/CaptureService.kt`
- tests: `MotionClassifierTest.kt` (the FeatureLogger case), new `FeatureLoggerTest.kt`, new `StatusLogThrottleTest.kt`

**Implement:**
- **`FeatureLogger(classifier, sink, clock: () -> Long = { 0L }, verbose: Boolean = false)`:** `log(f)` writes the same line when `verbose`, or when `lastState == null || f.state != lastState || clock() - lastAt >= MIN_GAP_MS`. Then it records `lastState` and `lastAt`. `tick()` is unchanged.
- **`AndroidSensorFeed`:**
  - the internal constructor gains `verboseFeatures: Boolean = false`;
  - the public `constructor(context: Context, verboseFeatures: Boolean = false)`;
  - it builds `FeatureLogger(c, { DiagLog.i(TAG, it) }, now, verboseFeatures)`.
- **`DiagFlags`:** `object DiagFlags { const val VERBOSE_FEATURES_FILE = "diag_verbose_features"; fun verboseFeatures(filesDir: File): Boolean = runCatching { File(filesDir, VERBOSE_FEATURES_FILE).exists() }.getOrDefault(false) }`.
- **`StatusLogThrottle`:** `class StatusLogThrottle { fun shouldLog(key: String, line: String, tickSec: Int, force: Boolean = false): Boolean }`. It returns true when:
  - `force`; or
  - `key != lastKey`; or
  - `line != lastLine && tickSec - lastTick >= MIN_GAP_SEC`.

  On true it records the key, line and tick. The first call is always true.
- **`CaptureService`:**
  - `startSmart` reads `val verbose = DiagFlags.verboseFeatures(filesDir)`, logs `diag verbose features=${if (verbose) "on" else "off"}`, and passes it to `AndroidSensorFeed(this, verbose)`.
  - In `publishStatus`, `key = "${shown.running}|${shown.reason}|${shown.restingUntilMs != null}|${heat.level.value}|$degraded"`. Replace the `lastLogged` test with `if (statusThrottle.shouldLog(key, line, tick, force = tick % 60 == 0)) DiagLog.i(TAG, line)`.
  - The heartbeat is unchanged here (Task 5).

**Tests:**
- `FeatureLoggerTest` (fake clock):
  - the first call logs;
  - the same state 29 s later → no line; at 30 s → a line;
  - a state change 1 s later → a line;
  - `verbose = true` → one line per call.
- The existing `featureLoggerWritesOneCountOnlyLinePerTick` is updated to `verbose = true`, or kept with distinct states.
- `StatusLogThrottleTest`:
  - first → true;
  - the same line → false;
  - a changed line at +5 s with the same key → false; at +30 s → true;
  - a key change at +1 s → true;
  - `force` → true.

**Command:** `gw.sh :glasses:testDebugUnitTest --tests '*FeatureLoggerTest' --tests '*StatusLogThrottleTest' --tests '*MotionClassifierTest' --tests '*AndroidSensorFeedTest'`, then `gw.sh :glasses:testDebugUnitTest`.

**Commit:** `perf(glasses): diag log throttle (features on state change or 30 s, status on key change or 30 s; verbose marker file)`

### Task 5: Heartbeat opens1m, wake reasons, still gap; battery-run summary
**Spec:** §6 (heartbeat, summary).

**Files:**
- `capture/session/SamplingSession.kt` (`DiagStats`, `diagSnapshot`, the open counter)
- `capture/diag/Heartbeat.kt`
- `capture/CaptureService.kt`
- `tools/battery-run.sh`, `tools/test-battery-run.sh`, `tools/testdata/battery-run/capture_diag.log`
- tests: `HeartbeatTest.kt`, `SamplingSessionTest.kt`

**Implement:**
- **`DiagStats`:** add trailing `val opens: Int = 0` and `val wakes: Map<String, Int> = emptyMap()`.
- **`SamplingSession`:**
  - under `lock`: `private var opensAccum = 0` and `private val wakeAccum = linkedMapOf("timer" to 0, "dwell" to 0, "onset" to 0, "now" to 0, "stream" to 0)`;
  - in `startEpisode`'s acquire block, where `openedCount.incrementAndGet()` runs, also increment both under `synchronized(lock)` with `ep.why` (from Task 2);
  - `diagSnapshot()` returns them and resets them to 0.
- **`Heartbeat`:** add trailing `val stillGapMs: Long = 0L`. `format()` appends ` opens1m=${perMin(stats.opens.toLong())} wk=timer:N,dwell:N,onset:N,now:N,stream:N stillGapMs=$stillGapMs`. The order is fixed; a missing reason prints 0. The prefix stays `heartbeat v=2`.
- **`CaptureService`:** passes `stillGapMs = smart?.session?.stillGapMs() ?: 0L`.
- **`tools/battery-run.sh summary`:**
  - in the heartbeat loop: `else if (k == "opens1m") op += v`;
  - the second `printf` gains ` opens1m_mean=%.1f` after `batteryC_max=%.1f`, with `(n ? op/n : 0)`.
- **Fixture `capture_diag.log`:** append ` opens1m=12`, ` opens1m=6` and ` opens1m=0` to the 3 in-window heartbeat lines, and ` opens1m=99` to the out-of-window one.
- **`tools/test-battery-run.sh`:** add `expect "batteryC_max=33.0 opens1m_mean=6.0"`. All the existing expectations stay.

**Tests:**
- `HeartbeatTest`:
  - the exact full-sample string is updated with the appended keys (`opens = 3` over a 60 s span → `opens1m=3`, `wakes` timer 2 / dwell 1 → `wk=timer:2,dwell:1,onset:0,now:0,stream:0`, `stillGapMs=20000`);
  - `opens = 4` over a 120 s span → `opens1m=2`;
  - empty `wakes` → all zeros.
- `SamplingSessionTest`:
  - `diagSnapshotCountsOpensAndWakeReasons`: start (one `timer` open), let a dwell check run via `startSensors` and a gyro pulse, then call `diagSnapshot()` → `opens >= 2`, `wakes["timer"]!! >= 1`, `wakes["dwell"]!! >= 1`;
  - a second `diagSnapshot()` straight after → `opens == 0`.

**Command:** `gw.sh :glasses:testDebugUnitTest --tests '*HeartbeatTest' --tests '*SamplingSessionTest'`, then `bash tools/test-battery-run.sh` (from the repo root `/Users/debasishkanhar/Desktop/rokid/pca-bat/int`), then `gw.sh :glasses:testDebugUnitTest`.

**Final gate:** from the repo root, run `tools/gate.sh /Users/debasishkanhar/Desktop/rokid/pca-bat/gate-wave-a2.log`. It must exit 0. If a known flaky `PushToTalkTest` alone fails, re-run it once.

**Commit:** `feat(glasses): heartbeat opens1m, wake reasons and still gap; battery-run summary opens1m_mean`

### Task 6: Device checklist (not code; for when the user is present)
Do not run this without the user. Bluetooth toggles, uninstalls and permission revokes each need an explicit OK. Read only `files/capture_diag.log` and the `battery-run.sh` outputs; never logcat unfiltered (handoff §2 filter).

1. **Pre-A2 baseline on the wave-A build** (install it if wave A Task 9 step 2 has not been done; keep the data; re-grant `SYSTEM_ALERT_WINDOW`).
   - Settings: battery target **Off**, **Pause when not worn off** (otherwise parking hides the camera behaviour), music off.
   - Glasses unplugged, not worn, still on the desk, a lit scene.
   - **D1:** capture paused, 10 min: `G=1901092545029120 tools/battery-run.sh start D1` → unplug → wait 10 min → re-plug → `stop` → `pull ~/Desktop/rokid/battery-runs/D1` → `summary …`.
   - **D2:** Smart (MOTION), running, 10 min, the same commands with label D2.
2. **Install the A2 build** on both devices (`adb -s <dev> install -r <apk>`, keeping the data). Check that the heartbeat shows `target_h=0`. If it shows 4 (stored before A2), set Off in the UI. Select **Settle** explicitly.
3. **D3:** A2, Settle, running, the same desk setup, 10 min. **D4 (optional):** A2, Smart, to see the backoff alone.
4. **Expect, in D3 against D2:**
   - `opens1m_mean` ≤ 2 (D2 about 12);
   - `stillGapMs=60000` in the heartbeats after about 2 min;
   - `sensors=still` in most heartbeats;
   - about 2 `features` lines per minute;
   - status lines no more often than every 30 s.

   Note the `sensors fifo gyro=.. acc=..` line: 0 means the HAL does not batch. Compare the `pct_per_min` of D1, D2 and D3, and the cpu / wake lines in the pulled batterystats.
5. **Glance check, worn:** turn the head, settle on something for about 2 s → a dwell keyframe (LED blink, a `wk=…dwell:N` count). Do it after sitting still for 30 s or more (the STILL rate) and confirm it still fires. Walk for a minute → timer checks about every 10 s (no backoff while walking).
6. **Parking with A2:** Pause when not worn on, the glasses on the desk → `reason=parked` after 120 s and `sensors=low` 10 s later; pick them up → resumes in 1–2 s.
7. **Worn run, 20 min, A2 defaults, music on:** use `battery-run.sh` as above (label `A2w`). Compare its `pct_per_min` with the 1.6 %/min of 2026-10-21.
8. **WALK tuning (only if requested):** `adb -s <dev> shell run-as com.rokid.context.glasses touch files/diag_verbose_features`, then restart the session → `diag verbose features=on` and per-second features. Remove the file afterwards.
9. **Write the results** into handoff §6 (a D1–D4 / A2w table), and revisit the spec §4 ruling (Settle default) and §5 (STILL rate) with the user.
