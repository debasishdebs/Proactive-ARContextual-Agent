# Battery wave A: design

Date: 2026-10-21. Status: draft for implementation (user away; rulings recorded below). Plan: `docs/superpowers/plans/2026-10-21-battery-wave-a.md`.

## 1. Goal and scope
Target (user, 2026-10-21): **at least 4 h of glasses battery with music in the background.** Today: about 1 %/min (handoff §6–7, bug BAT).

Wave A ships everything that does **not** depend on the unmeasured battery run, built so each measurement run picks its configuration from a setting:

1. **W1 wear fix**: ask Rokid for the wear snapshot on every bind, log what it says, and fall back to "IMU perfectly still = parked".
2. **Settle-to-wake** camera mode, chosen in Settings → Capture.
3. **LOW**: a lower IMU rate while capture is blocked (not worn, parked, paused, resting).
4. **Battery-life controller**: a target-hours setting, a drain estimate and step-down tiers.
5. **Measurement support**: a v2 heartbeat with per-minute counters, plus `tools/battery-run.sh`.
6. **Pause while using the phone**: a setting, off by default.

Out of scope: everything in §9 (wave B).

Invariants:
- PROTOCOL_VERSION stays 1; only additive fields and channels.
- No Room change.
- The camera LED is never touched.
- No Rokid system service is disabled or worked around.
- Logs carry counts and states only.

## 2. Code facts this design relies on (read 2026-10-21)
- **Wear path.**
  - Phone: `CxrGlassesLink` exposes `wearing: StateFlow<Boolean?>`. Wear changes arrive two ways: the session listener's `onWearingStatusChanged`, and the background `GlassAppWatcher`'s `onGlassWearingStatus` and `onGlassDeviceInfo(GlassInfo)`.
  - `Hub` forwards the value as `pc_wear` (`WearMessage`).
  - Glasses: `HudController.onWear` → `WearDetector.onRemote`. A closed session gives `onWear(null)`, which resets the state to unknown (= worn).
  - `SamplingSession.blockReason` returns `not_worn` only when `pauseWhenNotWorn && !wear.worn`.
- **SDK (client-l 1.1.2, inspected with javap).**
  - `CXRLink` (via `ExternalAppClient`) has `getGlassDeviceInfo()`: fire-and-forget, with the answer arriving in `ICXRLinkCbk.onGlassDeviceInfo(GlassInfo)`. **Our code never calls it**, so the snapshot only arrives when Rokid pushes one.
  - `CXRLink` also has `isWearingCheckOn()`, which says whether the Rokid wear check is enabled at all.
  - `GlassInfo.wearingStatus` is a `String` decoded by gson, with undocumented values.
  - Both callbacks need the `DEVICE_MANAGE` glass permission, which we request.
  - The session API (`CxrSession.queryGlassesInfo()` → `GlassesInfo`) has **no wear field**. So while our app is full screen (session Open, watcher not bound) there is **no snapshot source at all**.
- **IMU.**
  - `AndroidSensorFeed` registers gyroscope and linear acceleration at `SENSOR_DELAY_GAME` (about 50 Hz) with no batching, all the time a Smart session exists, blocked or not.
  - Its 1 s tick calls `FeatureLogger.tick()`, which **consumes** `MotionClassifier.features()` (that call resets the gyro peak).
- **Camera.** The camera is policed by `CameraPolicy.decide` (pure) and `SamplingSession.tick`. A dwell (still for 1 s or more after motion) already forces a brief check (`pendingDwellAt`).
- **Heartbeat.** It is written once a minute in `CaptureService.publishStatus` (`tick % 60`) as `heartbeat running=… kf1m=… camOpen=…% heat=… batteryC=… wear=…`. `SamplingSession.diagStats()` resets the camera-open accumulator on each read.

## 3. W1: wear fix
**3.1 Snapshot on bind (phone, `services/glasses-link`).**
- When the watcher's CXRLink reports `onCXRLConnected(true)`:
  - call `getGlassDeviceInfo()` once;
  - log `isWearingCheckOn()` as `watcher: wear check on=<bool>`.
- `onGlassDeviceInfo` already parses through `WearingStatus.parse`. Add `WearingStatus.describe(raw)` for the log:
  - the raw value when it matches `^[A-Za-z0-9_ -]{1,24}$`;
  - otherwise `<other len=N>`;
  - `<null>` when null.
- The log line is `watcher: wearing snapshot raw=<describe> parsed=<true|false|null>`. Never log `GlassInfo.toString()`, which includes the device name.

Ruling: request the snapshot only on the watcher link (minimised / closed on glasses) — it is the only SDK path that carries wear — the full-screen start is covered by the IMU fallback (3.2) instead; cost if wrong: a desk start in full screen captures for up to 2 min.

Ruling: no change to the parse vocabulary until the device log shows the real values (the `describe` line exists to learn them) — guessing values could mis-gate a worn wearer; cost if wrong: one more device run before the snapshot gates.

**3.2 Parked fallback (glasses, `WearDetector`).**
- A **still second** is one whose features show `gyroMax < 0.05 rad/s` and `accVar < 0.01 (m/s²)²`.
- **Parked** after an unbroken run of still seconds:
  - **120 s** when the wear state is unknown (no `pc_wear` this session);
  - **600 s** when it is known worn (covers a missed removal event, and glasses put down without the sensor noticing).
- **Unpark** on the first non-still second; that is the whole resume path. A `pc_wear` value never clears parked: the phone repeats the last value on every hello. `reset()` (session closed) does not clear parked either.
- Session: `blockReason` returns `parked` after `not_worn`, under the same setting `pauseWhenNotWorn`.
- Plumbing: the sensor feed's 1 s tick reads `classifier.features()` **once** and hands the same `Features` to `FeatureLogger` and `WearDetector.onSecond`.

Ruling: thresholds 0.05 rad/s / 0.01, provisional — no desk-vs-head feature traces exist yet; the per-second `features` lines already log both values for tuning — cost if wrong: either a very still wearer pauses (resumes on the first head move, so cheap) or a desk unit never parks (W1 stays open).

Ruling: 10 min stillness also parks a known-worn unit — a wearer sitting perfectly still for 10 min gets near-duplicate photos anyway — cost if wrong: one missed photo at the first head move.

## 4. Settle-to-wake camera mode
**Wire.** A new additive `HudSettings.cameraWake: CameraWake = MOTION`, with `enum CameraWake { MOTION, SETTLE }`. An unknown value decodes to MOTION (Wire's `coerceInputValues`). `CaptureMode` is unchanged: a new enum value there would fall back to FIXED on older glasses, and `blockReason` keys on `SMART`.

**UI.** Settings → Capture → "Capture mode" offers three options:

| Option | Wire values |
|---|---|
| Smart | `SMART` + `MOTION` |
| Settle | `SMART` + `SETTLE` |
| Fixed | `FIXED` |

Quick look shows for Smart only. Settle shows the note "Photos only when your head settles on something (about 1.5–2 s). Uses much less battery."

**Policy (`CameraPolicy.decide`, `wake == SETTLE`).** The camera opens only for brief checks; there is never a continuous stream.

| State | Behaviour |
|---|---|
| decelerating | ignored (no early open) |
| HEAD_MOVING | closed, no check (`nextBriefCheckAtMs = null`) |
| WALKING | closed, brief check every `walkCheckMs` |
| settled (STILL) | closed, brief check every `settledCheckMs` (`quietCheckMs` after 10 quiet minutes or a dark streak); no hold and no motion-end check |
| dwell | the session's existing forced brief check gives the keyframe (dwell exception in the gate) |

- Session: in SETTLE, `onOnset` does **not** set `forceBrief`, so there is no check at the first sign of movement.
- The `lowPower` rule stays the same in both modes: checks at least every 10 s.

**Cadences** (from `CaptureProfile`, §6):
- MOTION T0: settled 5 s, quiet 15 s (unchanged).
- SETTLE T0: settled 10 s, quiet 30 s, walking 10 s.

Ruling: default stays Smart (MOTION) until the A/B runs — the user asked to measure first, and the controller (§6) already switches to settle-wake by itself when the drain is over budget — cost if wrong: about 1 %/min until a one-line default change.

Ruling: the Settle glance promise is "a settled look of about 1.5–2 s" (1 s dwell, plus camera start-up p95 ≤ 650 ms, plus the first usable frame); Smart keeps 1 s — answers handoff §10.1(a) for this mode only — cost if wrong: quick glances are missed in Settle; the user can pick Smart.

Ruling: walking still gets a check every 10 s in Settle — walking gives no dwell, and a walk through a shop is context — cost if wrong: about 5 % camera share while walking.

## 5. LOW: lower sensor rate when blocked
`SensorRatePolicy.update(reason, nowMs)` is pure:
- It returns **LOW** when the session's block reason has been one of {`not_worn`, `parked`, `paused`, `rest`, `battery_budget`, `battery`, `phone_in_use`, `heat`} for **10 s** or more.
- It returns **FULL** at once otherwise, including `running`.

`SensorFeed.setRate(rate)` re-registers only on a change:
- FULL = `SENSOR_DELAY_GAME`, max report latency 0;
- LOW = `samplingPeriodUs 100_000` (10 Hz), `maxReportLatencyUs 1_000_000` (batching where the HAL supports it).

`CaptureService`'s supervisor applies the rate once a second.

Ruling: 10 Hz with 1 s batching rather than unregistering — the parked/unpark detector and the onset still need motion — cost if wrong: up to about 1.1 s extra to resume after a pick-up (the wearer is not looking yet).

## 6. Battery-life controller
`BatteryBudget` (glasses, pure, `capture/session/BatteryBudget.kt`, with an injected clock).

**Sampling.** `onSample(pct, charging, targetHours)` is called at session start and then every 60 s by the supervisor.
- `targetHours ∈ {0, 3, 4, 5, 6}`; 0 = Off.
- History: non-charging samples of the last 20 min. Charging, or a level rise, clears it.

**Drain estimate.** `drainPctPerMin` = (oldest pct − newest pct) / span minutes over that history.
- It is valid only when span ≥ 10 min; otherwise it is null.
- Budget rate `B = 100 / (targetHours × 60)`; 0.417 %/min at 4 h.
- `projectedHours` = pct / drain / 60.

**Tiers** (`BudgetTier` T0..T3):

| Condition | Result |
|---|---|
| Off, or charging | T0 |
| Level floor: pct ≤ 25 | at least T2 |
| Level floor: pct ≤ 15 | T3 |
| Step down by one: drain > 1.15 × B and ≥ 10 min in the current tier | at most T2 by drain |
| Step up by one: drain < 0.75 × B and ≥ 15 min in the current tier | never above the level floor |
| Drain unknown | hold the current tier, floors still apply |

**`CaptureProfile.of(wake, tier)`** (pure) is what `CameraPolicy` and the session read:

| Tier | wake | settled check | quiet check | walking check | analysis | paused |
|---|---|---|---|---|---|---|
| T0 | setting | 5 s (MOTION) / 10 s (SETTLE) | 15 s / 30 s | – / 10 s | 333 ms | no |
| T1 | SETTLE | 10 s | 30 s | 10 s | 333 ms | no |
| T2 | SETTLE | 20 s | 60 s | 20 s | 666 ms | no |
| T3 | – | – | – | – | – | yes → block reason `battery_budget` |

- The existing guards stay as they are: FrameGuard ≤ 10 % → `battery`; lowPower ≤ 20 % or Battery Saver → checks at least every 10 s, hold halved; heat rules.
- The heat "slow" ×2 multiplies the profile cadence.
- Every tier change is logged: `battery tier T0->T1 pct=.. drain=.. target_h=..`.

**Setting.** New additive field `HudSettings.batteryTargetHours: Int = 4`. `normalized()` snaps it to {0, 3, 4, 5, 6}. Phone UI: "Battery target: Off / 3 h / 4 h / 5 h / 6 h" in the Capture card.

Ruling: default 4 h, ON — it only acts when the measured drain is over budget, so a healthy run is untouched; measurement runs set Off to compare raw modes — cost if wrong: fewer photos than expected on a heavy-music day (visible as `tier=` in the heartbeat).

Ruling: the controller reacts to measured drain, not to a model of camera cost — no per-component numbers exist yet, and the measured run (§8) will calibrate the thresholds — cost if wrong: about 10 min of lag before stepping down.

Ruling: no keyframe-gate rate-cap change in wave A — camera-open time and the number of checks are the main levers, and fewer checks already mean fewer keyframes — cost if wrong: the uplink/BT share stays until wave B.

Ruling: the phone shows no tier, because the glasses send no status; the heartbeat carries it — cost if wrong: the user can't see why there are fewer photos (Diagnostics can follow later).

## 7. Pause while using the phone
- **Wire.** New channel `Channels.PHONE_CONTEXT = "pc_phone_ctx"`, phone → glasses, carrying `PhoneContextMessage(v, inUse: Boolean, atMs, sessionStartMs)`. It is additive. Wave B extends this message (vehicle, place, …).
- **Phone.**
  - `PhoneUseSignal` registers `SCREEN_ON`, `SCREEN_OFF` and `USER_PRESENT`. `inUse` = screen interactive AND keyguard unlocked.
  - The Hub sends it on every change and on `onReady`, always (the glasses decide).
- **Glasses.**
  - `HudController` subscribes and drops stale messages (`atMs < sessionStartMs`), as for wear.
  - A closed session resets the state to not in use.
  - `PhoneUse` (pure) holds `inUseSince`.
  - `blockReason` returns `phone_in_use` when `pauseWhenPhoneInUse` is on and the phone has been in use for 3 s or more.
- **Setting.** New additive field `HudSettings.pauseWhenPhoneInUse: Boolean = false`. Switch: "Pause while I use my phone".

Ruling: default OFF (handoff §10.1(c) is unanswered) — cost if wrong: battery the user could have saved while looking at the phone.

Ruling: the glasses debounce 3 s, so a notification glance doesn't pause; there is no expiry, and the reset happens on session close — cost if wrong: a phone left unlocked on a table keeps capture paused (the user turned the setting on).

## 8. Measurement support
**Heartbeat v2.** The once-a-minute line becomes:

```
heartbeat v=2 running=.. reason=.. ui_mode=.. wake=MOTION|SETTLE tier=T0..T3 target_h=N batt=P chg=B drain=X.XX|na proj_h=X.X|na kf1m=N camOpen=P% camOpenMs1m=N analysed1m=N upBytes1m=N heat=.. batteryC=.. wear=B known=B parked=B sensors=full|low phoneUse=B restartGapSec=..
```

- Per-minute counters are normalised to the span since the previous heartbeat. `SamplingSession.diagSnapshot()` returns `DiagStats(kf1m, openMs, spanMs, analysed)` and resets.
- `FrameUplink` counts sent chunk characters (`bytesSent`, monotonic).
- A pure `Heartbeat` data class plus `format()`, so it can be tested. Numbers and states only.

**`tools/battery-run.sh`** (bash 3.2; device serial from `$G`, default `1901092545029120`):
- `start <label>`:
  - checks the device;
  - kills any old logger;
  - `dumpsys batterystats --reset`;
  - writes `/data/local/tmp/batt_run.txt` (label and start time from the glasses clock);
  - starts the on-glasses logger: a per-minute `dumpsys battery` level / temperature / USB / AC line into `/data/local/tmp/batt.log`, its command tagged `# batt_logger`.
- `stop`: `pkill -f batt_logger`; appends the end time.
- `pull <dir>` saves into the directory:
  - `batt.log`;
  - `batt_run.txt`;
  - `capture_diag.log` and `capture_diag.log.1` via `run-as`;
  - `dumpsys batterystats --charged com.rokid.context.glasses`, piped through the handoff §2 extended filter **before** writing, then narrowed to power / camera / cpu / wake / bluetooth / screen lines.

  It prints only file names and line counts.
- `summary <dir>` prints numbers only:
  - duration, start and end %, %/min, projected hours;
  - from the heartbeat v2 lines inside the run window: mean `camOpenMs1m`, `analysed1m`, `kf1m` and `upBytes1m`; max `batteryC`; counts of `wake=`, `tier=`, `reason=` and `sensors=`.
- **Never** reads or prints logcat.
- A fixture test, `tools/test-battery-run.sh`, runs `summary` on `tools/testdata/battery-run/`.

**Run matrix for the user** (device checklist, plan Task 9). Each run is 20 min unplugged, with music playing as on the trip unless stated:

| Run | Configuration |
|---|---|
| (a) | capture paused, icon shown |
| (b) | Smart, Quick look 1 s, target Off |
| (c) | Settle, target Off |
| (d) | music only (app exited) |
| (e) | Smart, target 4 h (controller on) |

## 9. Wave B outline (not built here; needs the motion/context hub and the fusion design)
- **Motion/context hub** (handoff §9.1 and §9.7): phone and Galaxy Watch motion, using the LiveFit structure at `../live-fitness-tracker`: a plain-Kotlin core, adapters and fakes; the watch sends motion-state changes only. Plus the phone's GPS speed and Activity Recognition (IN_VEHICLE / ON_FOOT / STILL).
- **Vehicle signals:** car Bluetooth devices and Android Auto as the first deterministic "in a car" signal; flight detection (airplane mode plus altitude or speed).
- **Intent step** (canvas router → intent → memory): an image-based activity classifier as the first pipeline stage (sitting idle, workout, computer work, driving, walking, other), shared by capture-rate control and memory routing.
- **Open fusion question (handoff §10.0):** how to merge the deterministic signals (car BT/Android Auto, GPS, AR, IMU, watch) with the probabilistic intent from the image. A passenger on car BT for music is not driving: the image intent must be able to override car BT, and the reverse. Also open: should capture stop entirely while flying?
- **Transport:** `PhoneContextMessage` (§7) is the carrier: wave B adds fields such as `vehicle`, `activity` and `placeStableMin`, and the glasses map them onto `BudgetTier` / `CaptureProfile`.
- **Other levers left for wave B:**
  - display or icon off while minimised, if the measured run shows the display costs;
  - an NV21 copy only for candidates;
  - a stronger same-scene test plus phone-side semantic dedupe (wave 2);
  - a keyframe rate cap per tier.

## 10. Review log
- r1 (2026-10-21, Opus planner): first draft. The user was away, so the rulings above stand until they review.
