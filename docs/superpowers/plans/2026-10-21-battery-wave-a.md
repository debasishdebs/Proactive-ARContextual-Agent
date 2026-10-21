# Battery wave A: implementation plan

**Spec:** `docs/superpowers/specs/2026-10-21-battery-wave-a-design.md`. Read §2 (code facts) and the section your task names before starting.

**Where to work:** `/Users/<user>/Desktop/rokid/pca-bat/int` (branch `battery/integration`). Tasks run **sequentially**: each one starts from the previous task's commit. Use TDD: write the test, watch it fail (RED), implement, watch it pass (GREEN), commit.

## Global Constraints
- **Modules:** `:core:model` (Kotlin JVM), `:services:glasses-link` (Android library), `:glasses`, `:phone`. Package roots:
  - `com.rokid.context.core.model`
  - `com.rokid.context.link`
  - `com.rokid.context.glasses`
  - `com.rokid.context.phone`
- **Wire:**
  - `PROTOCOL_VERSION` stays **1**.
  - Only **additive** fields with defaults, and new channels. Old glasses ↔ new phone, and the reverse, must still parse.
  - An unknown enum value decodes to the property default (`Wire` has `coerceInputValues`).
- **Room:** no change (DB version stays 2).
- **Exact new names and values** (do not rename):
  - `enum class CameraWake { MOTION, SETTLE }`; `HudSettings.cameraWake: CameraWake = CameraWake.MOTION`.
  - `HudSettings.batteryTargetHours: Int = 4`; `BATTERY_TARGET_HOURS_ALLOWED = listOf(0, 3, 4, 5, 6)` (0 = Off); snapped in `normalized()`.
  - `HudSettings.pauseWhenPhoneInUse: Boolean = false`.
  - `Channels.PHONE_CONTEXT = "pc_phone_ctx"`; `@Serializable data class PhoneContextMessage(val v: Int = PROTOCOL_VERSION, val inUse: Boolean, val atMs: Long, val sessionStartMs: Long = 0L)`.
  - New block reasons (strings): `parked`, `battery_budget`, `phone_in_use`. Order in `blockReason`: stopped, mode, paused, not_worn, **parked**, **phone_in_use**, heat, battery, **battery_budget**, then rest.
  - Parked:
    - a still second is `gyroMax < 0.05f` and `accVariance < 0.01f`;
    - `PARK_UNKNOWN_MS = 120_000`; `PARK_WORN_MS = 600_000`.
  - Sensor rate:
    - FULL = `SensorManager.SENSOR_DELAY_GAME` with latency 0;
    - LOW = period `100_000` µs with latency `1_000_000` µs;
    - LOW after `10_000` ms in a blocking reason.
  - Budget:
    - B = `100.0 / (targetHours * 60)`;
    - history window `20 min`; minimum span `10 min`;
    - down at drain > `1.15 × B` after `10 min` in the tier; up at drain < `0.75 × B` after `15 min`;
    - floors: pct ≤ `25` → ≥ T2; pct ≤ `15` → T3.
  - Profile cadences: the table in spec §6, exactly.
  - Phone in use: debounce `3_000` ms on the glasses.
- **Never touch:**
  - the camera LED (`LedSourceRulesTest` must stay green);
  - Rokid system services.

  Logs carry counts and states only: never captions, user text, device names, tokens or `GlassInfo.toString()`.
- **Gradle:**
  - Run from `/Users/<user>/Desktop/rokid/pca-bat/int/android-companion` through the throttled wrapper `/Users/<user>/Desktop/rokid/proactive-context-agent/.superpowers/sdd/gw.sh <args>`. It calls `./gradlew --no-daemon`.
  - **One Gradle run at a time.** Never `gradlew --stop`.
  - Unit-test commands:
    - `:core:model:test --tests '<Class>'`
    - `:services:glasses-link:testDebugUnitTest --tests '<Class>'`
    - `:glasses:testDebugUnitTest --tests '<Class>'`
    - `:phone:testDebugUnitTest --tests '<Class>'`
  - Run the whole module's tests before committing, e.g. `:glasses:testDebugUnitTest`.
- **No device.** No `adb` in Tasks 1–8. No push.
- **Secrets.** Never print `.env` values, keys or tokens.
- **Commits:** local only, one per task, message as given, ending with the trailer `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- **Backward compatibility:** new constructor or function parameters get defaults, so existing call sites and tests compile unchanged.

### Task 1: Protocol additions (core:model)
**Files:** `android-companion/core/model/src/main/kotlin/com/rokid/context/core/model/Protocol.kt`, `android-companion/core/model/src/test/kotlin/com/rokid/context/core/model/ProtocolTest.kt`.

**Implement:**
- `CameraWake`; the three `HudSettings` fields, with KDoc "additive"; `BATTERY_TARGET_HOURS_ALLOWED`.
- In `normalized()`: `batteryTargetHours = BATTERY_TARGET_HOURS_ALLOWED.nearest(batteryTargetHours)`. Ties go to the smaller value, as the existing helper does; e.g. 1 → 0, 2 → 3, 9 → 6.
- `Channels.PHONE_CONTEXT`; `PhoneContextMessage`.

**Tests (ProtocolTest):**
- old JSON without the new fields decodes to MOTION / 4 / false;
- `"cameraWake":"WARP"` decodes to MOTION;
- `PhoneContextMessage` round-trips through `Wire`;
- the `normalized()` snaps for batteryTargetHours listed above;
- `PROTOCOL_VERSION == 1`.

**Command:** `gw.sh :core:model:test`.

**Commit:** `feat(model): cameraWake, batteryTargetHours, pauseWhenPhoneInUse, pc_phone_ctx (additive)`

### Task 2: W1 snapshot request on bind (services:glasses-link)
**Spec:** §3.1.

**Files:**
- `services/glasses-link/src/main/kotlin/com/rokid/context/link/WearingStatus.kt`
- `.../link/GlassAppWatcher.kt`
- `services/glasses-link/src/test/kotlin/com/rokid/context/link/WearingStatusTest.kt`

**Implement:**
- `WearingStatus.describe(raw: String?): String`:
  - `"<null>"` for null;
  - the raw string when it matches `^[A-Za-z0-9_ -]{1,24}$`;
  - else `"<other len=N>"`.
- In `GlassAppWatcher.LinkCbk`:
  - `onCXRLConnected(true)` → after `onBound(true)`, call a hook that, on the watcher's `link`, runs `runCatching { it.getGlassDeviceInfo() }` and logs `watcher: wear check on=${runCatching { it.isWearingCheckOn() }.getOrNull()}`.
  - Pass the hook in the constructor, because `LinkCbk` is a static nested class; it must use the live `link` field. Inside `start()` the link is the local `l`; use `this.link ?: built`.
  - `onGlassDeviceInfo(info)` logs `watcher: wearing snapshot raw=${WearingStatus.describe(info.wearingStatus)} parsed=${parsed}` (tag `CxrGlassesLink.TAG`), then calls `onSnapshot(parsed)`.
  - Never log `info` itself.
- In `CxrGlassesLink`, keep the existing `onWearingSnapshot` behaviour.

**Tests (WearingStatusTest):**
- `describe` for null, `"1"`, `"wearing_on"`, a 25-character string, and a string containing `{` or `:` (→ `<other len=N>`);
- all existing `parse` cases unchanged.

**Command:** `gw.sh :services:glasses-link:testDebugUnitTest`.

**Commit:** `fix(link): W1 ask the wear snapshot on every watcher bind; log raw wearingStatus safely`

### Task 3: Parked fallback and low sensor rate (glasses)
**Spec:** §3.2 and §5.

**Files:**
- `glasses/src/main/java/com/rokid/context/glasses/capture/motion/WearDetector.kt`
- `.../capture/motion/FeatureLogger.kt`
- `.../capture/motion/SensorFeed.kt`
- new `.../capture/session/SensorRatePolicy.kt`
- tests: `WearDetectorTest.kt`, `AndroidSensorFeedTest.kt`, new `SensorRatePolicyTest.kt`

**Implement:**
- **`WearDetector`:**
  - `val parked: StateFlow<Boolean>`; `fun onSecond(gyroMax: Float, accVariance: Float, tMs: Long)`.
  - A still second, per the Global Constraints, starts or extends a run (`stillSinceMs`); any other second clears it and sets `parked = false`.
  - `parked = run ≥ (if (known && worn.value) PARK_WORN_MS else PARK_UNKNOWN_MS)`.
  - `reset()` and `onRemote()` do **not** touch `parked`.
  - Also add `fun verdictLine(): String = "wear=${worn.value} known=$known parked=${parked.value}"`.
- **`FeatureLogger`:** add `fun log(f: MotionClassifier.Features)`; keep `tick()` as `log(classifier.features())`.
- **`AndroidSensorFeed`:**
  - The 1 s runnable reads `val f = c.features()` once, then calls `features.log(f)` and `w.onSecond(f.gyroMax, f.accVariance, SystemClock.elapsedRealtime())`. Inject the clock as a constructor param `now: () -> Long = SystemClock::elapsedRealtime` so the test can pin it.
  - Add `enum class SensorRate { FULL, LOW }` and `fun setRate(r: SensorRate)` to the `SensorFeed` interface (default no-op for fakes).
  - The implementation unregisters and then re-registers the gyro, linear acceleration and proximity sensors with the FULL/LOW values, only on a change and only while started. Expose `val rate: SensorRate`.
- **`SensorRatePolicy`** (pure; clock passed per call):
  - `fun update(reason: String?, nowMs: Long): SensorRate`.
  - LOW when `reason` ∈ {`not_worn`, `parked`, `paused`, `rest`, `battery_budget`, `battery`, `phone_in_use`, `heat`} continuously for ≥ 10 000 ms; FULL immediately for anything else (null included).
  - A change of reason within the set keeps the timer.

**Tests:**
- `WearDetectorTest`:
  - 119 still seconds → not parked; the 120th → parked (unknown);
  - known worn → parks at 600 s, not at 120 s;
  - one moving second → unparked;
  - `reset()` keeps parked; `onRemote(true)` keeps parked;
  - `verdictLine` format.
- `AndroidSensorFeedTest`:
  - `setRate(LOW)` re-registers with period 100 000 and latency 1 000 000;
  - `setRate(LOW)` twice re-registers once;
  - `setRate(FULL)` goes back to GAME with latency 0.
- `SensorRatePolicyTest`:
  - 9 999 ms → FULL, 10 000 ms → LOW;
  - null resets;
  - not_worn → parked keeps the timer.

**Command:** `gw.sh :glasses:testDebugUnitTest --tests '*WearDetectorTest' --tests '*AndroidSensorFeedTest' --tests '*SensorRatePolicyTest'`, then `:glasses:testDebugUnitTest`.

**Commit:** `feat(glasses): W1 parked-on-stillness fallback and low IMU rate while capture is blocked`

### Task 4: Battery budget and capture profile (glasses, pure)
**Spec:** §6.

**Files:** new `glasses/src/main/java/com/rokid/context/glasses/capture/session/BatteryBudget.kt` and new test `glasses/src/test/java/com/rokid/context/glasses/capture/session/BatteryBudgetTest.kt`.

**Implement:**
- `enum class BudgetTier { T0, T1, T2, T3 }`.
- `data class CaptureProfile(val wake: CameraWake, val settledCheckMs: Long, val quietCheckMs: Long, val walkCheckMs: Long?, val analysisMs: Long, val paused: Boolean)`, with `companion fun of(wake: CameraWake, tier: BudgetTier): CaptureProfile` per the spec §6 table and `val DEFAULT = of(CameraWake.MOTION, BudgetTier.T0)`:
  - MOTION T0 = 5 000 / 15 000 / null / 333 / false;
  - SETTLE T0 = 10 000 / 30 000 / 10 000 / 333;
  - T1 = SETTLE 10 000 / 30 000 / 10 000 / 333;
  - T2 = SETTLE 20 000 / 60 000 / 20 000 / 666;
  - T3 = paused = true (other fields as T2).
- `class BatteryBudget(private val clock: () -> Long)`:
  - `val tier: StateFlow<BudgetTier>`;
  - `fun onSample(pct: Int, charging: Boolean, targetHours: Int): BudgetTier`;
  - `val drainPctPerMin: Double?`; `val projectedHours: Double?`;
  - `var onChange: (String) -> Unit = {}`, which receives the log line `battery tier T0->T1 pct=.. drain=%.2f target_h=..`.
  - A pct < 0 (unknown) is ignored.

  Rules exactly as spec §6:
  - Off or charging → T0, history cleared;
  - a level rise clears the history;
  - step down or up by one tier per sample when the dwell allows;
  - floors are applied last.

**Tests (fake clock, one sample per minute):**
- Off → always T0;
- 4 h target with a 1 %/min drain:
  - T0 until 10 min of span plus 10 min in the tier, then T1;
  - 10 min later T2;
  - never T3 by drain;
- a 0.2 %/min drain at T1 after 15 min → T0;
- pct 25 → T2 at once; pct 15 → T3;
- charging → T0 and the history cleared;
- a level rise clears the history (drain null);
- `projectedHours` at 0.5 %/min and 80 % = 2.67;
- `CaptureProfile.of` covers every table row.

**Command:** `gw.sh :glasses:testDebugUnitTest --tests '*BatteryBudgetTest'`.

**Commit:** `feat(glasses): battery budget controller and capture profile (pure)`

### Task 5: Settle-to-wake and profile wiring (glasses)
**Spec:** §4 and §6.

**Files:**
- `.../capture/session/CameraPolicy.kt`
- `.../capture/session/SamplingSession.kt`
- `.../capture/CaptureService.kt`
- tests: `CameraPolicyTest.kt`, `SamplingSessionTest.kt`

**Implement:**
- **`CameraPolicy.decide`:** add a trailing `profile: CaptureProfile = CaptureProfile.DEFAULT`.
  - **MOTION:** behaviour unchanged, except that `SETTLED_CHECK_MS` and `QUIET_CHECK_MS` come from `profile.settledCheckMs` / `profile.quietCheckMs`, and the interval is `profile.analysisMs * slow`. With DEFAULT, every existing test stays green.
  - **SETTLE**, per the spec §4 table:
    - decelerating ignored;
    - HEAD_MOVING → `Decision(false, interval, null)`;
    - WALKING → `Decision(false, interval, lastBrief + walkCheckMs * slow)`;
    - settled → `checkMs = maxOf(if (lowPower) LOW_POWER_CHECK_MS else 0, profile.settledCheckMs)`, quiet → `maxOf(checkMs, profile.quietCheckMs)`;
    - no hold and no motion-end check.
- **`SamplingSession`:** a new ctor param `profile: () -> CaptureProfile = { CaptureProfile.DEFAULT }`.
  - `tick` reads it once and passes it to `decide`.
  - `blockReason` adds `battery_budget` when `profile().paused` (order per the Global Constraints). The `parked` reason (Task 3's `wear.parked`, under `s.pauseWhenNotWorn`) is also added here.
  - `onOnset` sets `forceBrief` and `forceAnalyse` only when `profile().wake == CameraWake.MOTION`.
- **`CaptureService`:**
  - `private val budget = BatteryBudget(SystemClock::elapsedRealtime).also { it.onChange = { l -> DiagLog.i(TAG, l) } }`;
  - in the supervisor loop, on `tick % 60 == 0`, call `budget.onSample(batteryPercent(), isCharging(), runtime.settingsCache.readNormalized().batteryTargetHours)`;
  - `startSmart` passes `profile = { CaptureProfile.of(runtime.settingsCache.readNormalized().cameraWake, budget.tier.value) }`;
  - also, once a second in the supervisor: `smart?.feed?.setRate(ratePolicy.update(runtime.captureStatus.value.reason, SystemClock.elapsedRealtime()))`, with `private val ratePolicy = SensorRatePolicy()`.

**Tests:**
- `CameraPolicyTest`, SETTLE:
  - moving → closed with null;
  - walking → a check at +10 s;
  - settled → +10 s; quiet → +30 s;
  - decelerating → closed;
  - lowPower T2 → 20 s;
  - heat slow doubles.
- `CameraPolicyTest`, MOTION + DEFAULT: one regression case equal to today.
- `SamplingSessionTest`:
  - SETTLE: an onset opens no camera; a dwell opens a brief check;
  - `paused` profile → reason `battery_budget` and the camera closed;
  - `wear.parked` true with `pauseWhenNotWorn` → reason `parked`; with the setting off → running.

**Command:** `gw.sh :glasses:testDebugUnitTest --tests '*CameraPolicyTest' --tests '*SamplingSessionTest'`, then `:glasses:testDebugUnitTest`.

**Commit:** `feat(glasses): settle-to-wake camera mode, battery tiers and sensor rate wired into the session`

### Task 6: Phone Settings → Capture (phone)
**Spec:** §4 (UI), §6 (setting) and §7 (setting).

**Files:**
- `phone/src/main/java/com/rokid/context/phone/ui/settings/capture/CaptureSettings.kt`
- `phone/src/main/java/com/rokid/context/phone/ui/shell/SettingsDestinations.kt`
- `phone/src/test/java/com/rokid/context/phone/ui/settings/capture/CaptureSettingsTest.kt`

**Implement:**
- **UI state:** add `wake: CameraWake`, `batteryTargetHours: Int`, `pauseWhenPhoneInUse: Boolean`.
- **`statusLine`:** shows `Smart`, `Settle` or `Fixed`.
- **Capture-mode `ChoiceRow`:** options `"Smart"`, `"Settle"`, `"Fixed"`. The selected index is derived from (mode, wake). `vm.setModeChoice(i)`:
  - 0 → `SMART` + `MOTION`;
  - 1 → `SMART` + `SETTLE`;
  - 2 → `FIXED` (wake untouched).
- **Settle choice:** shows `NoticeText("Photos only when your head settles on something (about 1.5–2 s). Uses much less battery.", tag = "settle_note")` and hides Quick look.
- **New `ChoiceRow("Battery target", ["Off","3 h","4 h","5 h","6 h"], tag = "battery_target")`** in the first card, under the mode rows, with `NoticeText("Takes fewer photos when the glasses battery would not last this long.")`.
- **New `SwitchRow("Pause while I use my phone", null, …, tag = "pause_phone_use")`** under "Pause when not worn".
- **ViewModel:** `setBatteryTarget(h)` (normalised) and `setPauseWhenPhoneInUse(on)`.
- **Wiring:** in `SettingsDestinations`, add the new callbacks. Keep `onMode` for compatibility, or replace its call site with `onModeChoice`.

**Tests (Robolectric Compose, existing style):**
- three mode options, and choosing Settle writes `SMART` + `SETTLE`;
- Fixed keeps wake;
- the Settle note shows and Quick look is hidden;
- the battery target row defaults to "4 h", and selecting Off writes 0;
- the phone-use switch defaults off and toggles the field;
- `statusLine` reads "Settle · …".

**Command:** `gw.sh :phone:testDebugUnitTest --tests '*CaptureSettingsTest'`.

**Commit:** `feat(phone): Capture settings: Settle mode, battery target, pause while using the phone`

### Task 7: Phone-in-use signal end to end (phone + glasses)
**Spec:** §7.

**Files:**
- new `phone/src/main/java/com/rokid/context/phone/hub/PhoneUseSignal.kt`
- `phone/src/main/java/com/rokid/context/phone/hub/Hub.kt`
- `phone/src/main/java/com/rokid/context/phone/hub/HubGraph.kt`
- `glasses/src/main/java/com/rokid/context/glasses/link/HudController.kt`
- `glasses/src/main/java/com/rokid/context/glasses/GlassesRuntime.kt`
- new `glasses/src/main/java/com/rokid/context/glasses/capture/session/PhoneUse.kt`
- `.../capture/session/SamplingSession.kt` and `.../capture/CaptureService.kt` (one line each)
- tests: `HubTest.kt`, new `phone/src/test/java/com/rokid/context/phone/hub/PhoneUseSignalTest.kt`, `HudControllerTest.kt`, new `PhoneUseTest.kt`, `SamplingSessionTest.kt`

**Implement:**
- **Phone, `PhoneUseSignal`:**
  - `val inUse: StateFlow<Boolean>`;
  - pure core `fun onScreen(interactive: Boolean, keyguardLocked: Boolean)`; inUse = interactive && !keyguardLocked;
  - an Android adapter `start(context)` / `stop()` registering `ACTION_SCREEN_ON`, `ACTION_SCREEN_OFF` and `ACTION_USER_PRESENT` (dynamic receiver on the application context), reading `PowerManager.isInteractive` and `KeyguardManager.isKeyguardLocked`.
- **Phone, Hub:**
  - new ctor param `phoneUse: StateFlow<Boolean> = MutableStateFlow(false)`;
  - in `start()`, collect it → `sendPhoneContext()`; `onReady()` also calls `sendPhoneContext()`;
  - it sends `PhoneContextMessage(inUse, atMs = clock(), sessionStartMs)` on `Channels.PHONE_CONTEXT`, always (the glasses gate on the setting);
  - `HubGraph` builds and starts a `PhoneUseSignal` and passes its flow.
- **Glasses, `PhoneUse`** (pure, clock passed in):
  - `fun onRemote(inUse: Boolean, nowMs: Long)`; `fun reset()`;
  - `fun active(nowMs: Long): Boolean` = inUse for ≥ 3 000 ms.
- **Glasses, `HudController`:**
  - subscribe `Channels.PHONE_CONTEXT`; a stale message (`atMs < sessionStartMs`) is ignored;
  - `@Volatile var onPhoneUse: (Boolean?) -> Unit = {}`; `closeSession` calls `onPhoneUse(null)`.
- **Glasses, `GlassesRuntime`:**
  - `val phoneUse = PhoneUse()`;
  - attach maps `onPhoneUse` to `onRemote` / `reset` with `SystemClock.elapsedRealtime()`; detach clears it.
- **Glasses, session:**
  - `SamplingSession` gets the ctor param `phoneInUse: () -> Boolean = { false }`;
  - `blockReason` adds `phone_in_use` when `s.pauseWhenPhoneInUse && phoneInUse()`, after `parked`;
  - `CaptureService` passes `{ runtime.phoneUse.active(SystemClock.elapsedRealtime()) }`.

**Tests:**
- `HubTest`: a flow change sends `pc_phone_ctx` with `inUse`; `onReady` re-sends it.
- `HudControllerTest`: a fresh message reaches `onPhoneUse(true)`; a stale one is ignored; a session close gives null.
- `PhoneUseTest`: 2 999 ms → false, 3 000 ms → true; reset → false.
- `PhoneUseSignal` core: locked + interactive → false.
- `SamplingSessionTest`: the setting on + in use → `phone_in_use`; the setting off → running.

**Command:** `gw.sh :phone:testDebugUnitTest --tests '*HubTest' --tests '*PhoneUseSignalTest'`, then `gw.sh :glasses:testDebugUnitTest`.

**Commit:** `feat: pause capture while the phone is in use (pc_phone_ctx, setting default off)`

### Task 8: Heartbeat v2 and battery-run tooling
**Spec:** §8.

**Files:**
- new `glasses/src/main/java/com/rokid/context/glasses/capture/diag/Heartbeat.kt`
- `.../capture/session/SamplingSession.kt` (`diagSnapshot`)
- `.../link/FrameUplink.kt` (`bytesSent`)
- `.../capture/CaptureService.kt` (the line)
- new `tools/battery-run.sh`, new `tools/test-battery-run.sh`, new fixtures `tools/testdata/battery-run/{batt.log,batt_run.txt,capture_diag.log}`
- tests: new `HeartbeatTest.kt`, `FrameUplinkTest.kt` (counter case)

**Implement:**
- **`SamplingSession.diagSnapshot(): DiagStats`**, with `data class DiagStats(val kf1m: Int, val openMs: Long, val spanMs: Long, val analysed: Int)`. `analysed` is the delta of `analysedFrames` since the last snapshot. It resets the accumulators. Keep `diagStats()` as a wrapper or delete it if unused.
- **`FrameUplink`:** `val bytesSent: Long` (AtomicLong), adding `c.chunk.length` after each `sendChunk(c) == 0`.
- **`Heartbeat`:** a data class with the spec §8 fields, plus `fun format(): String`, which writes the exact spec §8 key order and prefix `heartbeat v=2`.
  - Per-minute values = `value * 60_000 / spanMs` (span ≥ 1).
  - `drain`/`proj_h` print `na` when null, else 2 and 1 decimals (`Locale.US`).
- **`CaptureService.publishStatus`:** the minute line uses `Heartbeat(...).format()`, with:
  - the uplink delta, kept in the service;
  - `budget` fields;
  - `smart?.feed?.rate`;
  - `runtime.wear.verdictLine` parts;
  - `runtime.phoneUse.active(now)`;
  - the battery pct and charging state.
- **`tools/battery-run.sh`** (bash 3.2, `set -u`, `G="${G:-1901092545029120}"`, always `adb -s "$G"`). Subcommands per spec §8:
  - **`start <label>`.** The logger is started with:

    ```
    adb -s "$G" shell 'setsid nohup sh -c "while true; do echo \"\$(date +%m-%d_%H:%M:%S) \$(dumpsys battery | grep -E \"^ +(level|temperature|USB powered|AC powered):\" | tr -s \" \" | tr \"\\n\" \" \")\"; sleep 60; done # batt_logger" > /data/local/tmp/batt.log 2>&1 < /dev/null &'
    ```

    Verify the quoting by hand-reading; do not run it.
  - **`pull <dir>`** pipes batterystats through:

    ```
    grep -v -i -E "token|AuthorizationHelper|deviceSecret|deviceKey|secret|password|DevCmdHelper|PhoneCallManager|TelephoneManager|BluetoothPBAPManager|MobileMsgDialog"
    ```

    then `grep -i -E "Estimated power use|camera|cpu|wake|bluetooth|screen"`, before writing. It prints only the file names and `wc -l` counts.
  - **`summary <dir>`** is pure awk over the pulled files: no adb, so it is testable. Window = the `batt_run.txt` start/end. It prints:

    ```
    duration_min= batt_start= batt_end= pct_per_min= proj_h= hb_lines= camOpenMs1m_mean= analysed1m_mean= kf1m_mean= upBytes1m_mean= batteryC_max=
    ```

    followed by `wake=`, `tier=`, `reason=` and `sensors=` count lines.
  - The script **never** calls `logcat`. A test greps the script for `logcat` and fails if found.
- **`tools/test-battery-run.sh`:** runs `tools/battery-run.sh summary tools/testdata/battery-run`. Its asserts use fixtures with known values: a 20 min run from 90 % to 80 % → `pct_per_min=0.50 proj_h=3.3`; 3 heartbeat lines inside the window and 1 outside; means checked. It also asserts there is no `logcat` in `battery-run.sh`. Exit non-zero on any mismatch.

**Tests:**
- `HeartbeatTest`: exact string for a full sample; `na` cases; per-minute normalisation over a 120 s span.
- `FrameUplinkTest`: `bytesSent` sums the chunk lengths of accepted sends only.

**Command:** `gw.sh :glasses:testDebugUnitTest --tests '*HeartbeatTest' --tests '*FrameUplinkTest'`, then `bash tools/test-battery-run.sh` (from the repo root), then `gw.sh :glasses:testDebugUnitTest`.

**Final gate:** `tools/gate.sh /Users/<user>/Desktop/rokid/pca-bat/gate-wave-a.log` from the repo root. It runs the full `test :phone:assembleDebug :glasses:assembleDebug` and must exit 0; re-run a known flaky `PushToTalkTest` once if it alone fails.

**Commit:** `feat: heartbeat v2 counters and tools/battery-run.sh (counts only, no logcat)`

### Task 9: Device checklist (not code; for when the user is back)
Do not run this without the user. Each step needs the user present; Bluetooth toggles, uninstalls and permission revokes each need an explicit OK.

1. **First:** read the 2026-10-21 battery run (handoff §0), record it in handoff §6, and stop the old logger.
2. **Install the wave-A build on both devices.**
   - `adb -s <dev> install -r <apk>`, keeping the data.
   - Glasses: re-grant `SYSTEM_ALERT_WINDOW` (`appops set … allow`).
3. **W1, three cases** (read `files/capture_diag.log` only; never logcat unfiltered):
   - **(a) Start while off-head on the desk, minimised:** expect `watcher: wear check on=…` and `wearing snapshot raw=… parsed=…`. Record the raw vocabulary in the spec §3.1. If parsed=null, extend `WearingStatus.parse` (small follow-up). Within about 2 min: `reason=parked` or `not_worn`, and `sensors=low` 10 s later.
   - **(b) Desk start, full screen:** `parked` after 120 s.
   - **(c) Worn, then put down without removing** (sensor not triggered): `parked` within 10 min; picking the glasses up resumes within about 1–2 s (`sensors=full`).
4. **Settle mode:**
   - LED visual: dark while turning the head, a brief blink about 1.5 s after settling.
   - A dwell photo exists per settle.
   - `camOpenMs1m` is far below Smart.
5. **Run matrix (spec §8):** 20 min each, unplugged, music on as on the trip. For each run:
   - `G=1901092545029120 tools/battery-run.sh start <a|b|c|d|e>`; unplug; the user wears the glasses (sitting is fine) and notes any walking;
   - re-plug; `tools/battery-run.sh stop`; `pull ~/Desktop/rokid/battery-runs/<label>`; `summary …`;
   - put the summaries in a table in the handoff §6.

   Configurations:
   - (a) paused;
   - (b) Smart, Quick look 1 s, target Off;
   - (c) Settle, target Off;
   - (d) music only, app exited;
   - (e) Smart, target 4 h.
6. **Controller:**
   - In run (e), check that `tier=` stepped down if `pct_per_min > 0.48`, with a `battery tier` line.
   - From the summaries, decide whether Settle becomes the default. That is the spec §4 ruling, revisited.
7. **Phone in use:** setting on; unlock the phone and use it for more than 3 s → `reason=phone_in_use`; lock it → capture resumes. Setting off → no effect.
8. **Decide with the user** (handoff §10.1):
   - the Settle glance promise;
   - whether the icon must stay visible (compare (a) with the icon against a run with the icon hidden, if (a) or (d) shows the display matters);
   - the phone-in-use default.
9. **Write the results** into the handoff §6/§8 (close W1 and LOW if they pass; BAT gets the numbers) and the backlog.
