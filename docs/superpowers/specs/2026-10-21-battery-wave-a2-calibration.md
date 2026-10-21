# Battery wave A2: capture calibration

Date: 2026-10-21. Status: draft for implementation. Plan: `docs/superpowers/plans/2026-10-21-battery-wave-a2.md`. Builds on wave A (`2026-10-21-battery-wave-a-design.md`) in `/Users/debasishkanhar/Desktop/rokid/pca-bat/int` (branch `battery/integration`).

## 1. Why
Measured on 2026-10-21 (handoff §6):
- **Worn, old build:** 1.6 %/min. Drain tracks camera-open share (≈ 0.7 + 2.8 × share %/min). 305 camera opens in 48 min. cameraserver and the camera provider dominate CPU.
- **Desk, glasses still, old build, MOTION:** camOpen 8 % and 1 kf/min, yet CPU stays high: camera provider 9.8 %, our app 10.1 %, system_server 7.5 %, sensors HAL 3.5 %. The CPU is awake for 88 % of screen-off time.

Causes:
1. Settled and quiet brief checks reopen the camera every 5 s, even when neither the scene nor the head has changed.
2. The IMU (gyro, linear accel, proximity) runs at SENSOR_DELAY_GAME with no batching, which keeps the CPU awake.
3. FeatureLogger writes one line a second to `capture_diag.log`, and status lines are written every 5–10 s.

**User direction (binding):** fix the root causes by calibrating *when* the camera opens. The battery-target controller is a last-resort lever, so its default becomes **Off**.

## 2. Code facts relied on (read 2026-10-21)
- `CameraPolicy.decide` (pure) gives the next brief check as `lastBriefAtMs + checkMs × slow`.
  - **MOTION, settled:** `checkMs` is `profile.settledCheckMs`, or 10 s under lowPower. The quiet rule (`msSinceKeyframe > 10 min || quiet`) raises it to `quietCheckMs`. A motion-end check comes from `hold < quickLookMs`.
  - **SETTLE:** never streams. A walking check runs every `walkCheckMs`, and a settled check every `max(lowPower 10 s, settledCheckMs)` (or the quiet value).
- `SamplingSession`:
  - `onOnset` forces a brief check (MOTION only). `onDwell` sets `pendingDwellAt`, and the next tick forces a brief check: this is the glance promise.
  - A brief check ends at its first usable frame. `lastBriefAt` is set when the episode ends.
- `KeyframeGate.offer` already computes `ImageHashes.dHash` and `pHash` of the 160 px thumb for every non-blurred sample. "Same" means `min(Hamming d, Hamming p) ≤ HAMMING (10)`. The ring holds the last 5 *keyframes*, not the last *check*.
- `MotionClassifier` decides per sample using the sensor event timestamp (`e.timestamp / 1e6`). Gyro and accel are separate streams.
  - With batching, each stream arrives in bursts, and the bursts of the two streams interleave out of order. An accel sample older than the newest gyro sample makes `update()` see `t − lastAboveGyroMs ≤ 250` again. The result is a false STILL → HEAD_MOVING flip and a second onset.
- `WearDetector.onSecond` reads `features()` once a second from the feed's handler.
- `SensorRatePolicy` returns FULL or LOW: LOW after 10 s in a blocking reason. The supervisor applies it once a second, and `Smart` holds the session and the feed. The service `scope` runs on `Dispatchers.Main.immediate`, a single thread.
- `CaptureService.publishStatus` logs the status line whenever it changes. The line includes `startupStats()`, which changes on every brief check. The heartbeat runs at `tick % 60` (heartbeat v2).
- `Wire` uses `encodeDefaults = true`. Settings a phone has already stored keep their explicit values, so a default change only affects fields that are absent.

## 3. Still-scene backoff
**State** (in `SamplingSession`, under `lock`):
- `stillStep: Int = 0`;
- `lastCheckHash: Pair<Long, Long>?`, the dHash and pHash of the last usable brief-check frame.

**Gap** (`CameraPolicy.stillGap(base, step, cap)`):
- returns `base` when `base ≥ cap`;
- otherwise returns `min(base shl step, cap)`.

The cap comes from the new field `CaptureProfile.stillCapMs = 60_000` (every tier). It replaces the settled check in both modes:

| Mode | Base | Gaps by step |
|---|---|---|
| MOTION | `if (lowPower) 10 s else settledCheckMs` | 5 → 10 → 20 → 40 → 60 → 60 s |
| SETTLE | `max(lowPower 10 s, settledCheckMs)` | T0/T1: 10 → 20 → 40 → 60 s; T2: 20 → 40 → 60 s |

After that, the quiet rule raises the gap to `quietCheckMs`, and heat ×2 applies last.
- The MOTION motion-end check is unchanged.
- WALKING, HEAD_MOVING, decelerating and the hold are unchanged.

**Step update.** Only on the first usable frame of a brief check started by the **timer** (wake reason `timer`, §6), and only when the sample's motion is STILL:
- `lastCheckHash == null`: record the frame's hashes; the step is unchanged.
- **Same** (`min(Hamming d, Hamming p) ≤ KeyframeGate.HAMMING`): the step goes up by one, while `base shl step < cap`.
- **Changed:** the step goes to 0.
- A `GateResult.Keep` from any check sets the step to 0.

Every usable brief-check frame (timer, dwell, onset, now) records `lastCheckHash`. A continuous (stream) episode clears it.

**Reset to 0:**
- head-motion onset (`onOnset`, both modes);
- settle after motion (`onDwell`);
- a changed scene or a keep;
- any blocked → running transition in `tick` (covers a wear change, unpark, unpause, phone use, heat, rest);
- session start.

The glance promise is untouched: a dwell always forces a check.

Ruling: compare each check's frame to the previous check's frame using the dHash/pHash the gate already computes (read back through `KeyframeGate.lastHashes()`), not to the keyframe ring — the hashes are free, and "the same as 5 s ago" is the question being asked — cost if wrong: a change below Hamming 10 (small text on a screen) counts as the same scene and is seen up to 60 s late.

Ruling: doubling from the profile's base up to a cap of 60 s, applied after the lowPower floor and before quiet and heat — the 5 → 60 s range cuts desk opens from 12/min to about 1/min within 75 s — cost if wrong: a scene change without head motion (someone walks into view) is seen up to 60 s late, or 120 s when hot.

Ruling: no backoff for walking checks — a walk changes the scene by itself — cost if wrong: about 5 % camera share while walking stays.

## 4. Event-first SETTLE, and the default wake
SETTLE already opens the camera only on a dwell (settle after motion) and on timer checks. With §3, the timer checks become a backstop that backs off to 60 s on a still scene.

Ruling: **`HudSettings.cameraWake` defaults to SETTLE** (additive default change; an unknown value now decodes to SETTLE) — the measured dominant worn cost is streaming during head motion (camOpen 60–100 % → 2–3.5 %/min), which only SETTLE removes, and with the controller now Off by default nothing else switches to settle-wake by itself — cost if wrong: glances shorter than about 1.5–2 s are missed by default; Smart is one tap away and the revert is one line. Phones that already stored a value keep it (`encodeDefaults`); the device task sets Settle explicitly.

## 5. IMU: a STILL rate while running and still
`enum class SensorRate { FULL, STILL, LOW }`:

| Rate | Period | Max report latency |
|---|---|---|
| FULL | SENSOR_DELAY_GAME | 0 |
| STILL (new) | 50 000 µs (20 Hz) | 500 000 µs |
| LOW | 100 000 µs | 1 000 000 µs (unchanged) |

`SensorRatePolicy.update(reason, nowMs, state: MotionState? = null)`:
1. **LOW** when `reason ∈ LOW_REASONS` continuously for ≥ 10 000 ms. Unchanged, and it wins over the motion state.
2. Else **STILL** when `reason == null` (running) and `state == STILL` continuously for ≥ `STILL_AFTER_MS = 10_000`.
3. Else **FULL**.
   - Any non-STILL state, a null state (old callers), or any non-null reason resets the still timer.

**Back to FULL at once.** `CaptureService` collects `motion.state`, and on any non-STILL value applies the policy immediately; it does not wait for the 1 s supervisor. Both callers run on the service's single-thread scope.

**MotionClassifier with batched input.**
- Each callback uses `t = newestMs` for `update`, `checkDwell` and the peak-hold test, where `newestMs = max(newestMs, tMs)` across both streams.
- `lastAboveGyroMs` and `stillSinceMs` only move forward (`maxOf`).
- The accel window keeps its own per-stream `tMs`.

What batching does to detection:
- **Onset** carries the sample's own timestamp, so it is detected but **delivered up to 0.5 s late** from a still start.
- **Dwell** is timed by sample timestamps, so it is exact, but it is also delivered up to 0.5 s late when the whole glance falls inside one batch. Once the onset arrives, the feed is FULL again.
- **Walking** at 20 Hz still has at least 16 samples per 1 s window (the rule needs ≥ 8 and a span of 800 ms), which is ample for 1.2–2.8 Hz.

**WearDetector.**
- A 1 s tick can land before a batch arrives and see "no motion". That can only add one still second to a run that is already still. The batch arrives within 0.5 s and breaks the run, so parking (120 s / 600 s unbroken) is unaffected, and unparking is at most 0.5 s later.
- The chain on a desk becomes STILL (10 s) → parked (120 s, with `pauseWhenNotWorn` on) → LOW (10 s later).

**Diagnostics.** `start()` logs once: `sensors fifo gyro=<fifoMaxEventCount> acc=<..>`. 0 means the HAL ignores the latency, and then only the 20 Hz rate helps.

Ruling: STILL = 20 Hz with 500 ms latency, entered after 10 s of STILL while running — this cuts sensor deliveries from about 100/s (two sensors at 50 Hz) to about 4/s and keeps the worst-case glance delay at 0.5 s — cost if wrong: the onset check and the dwell from a long still start come up to 0.5 s later. If the HAL does not batch (fifo=0), the saving is only 2.5×.

Ruling: the cross-stream timestamp clamp in MotionClassifier — batched streams arrive out of order, and without it a single head turn emits two onsets — cost if wrong: none at FULL, where the streams already interleave in time.

## 6. Diagnostics and measurement
**FeatureLogger** (`clock`, `verbose` params):
- logs when `f.state` differs from the last logged state, or when ≥ `30_000` ms have passed since its last line;
- `verbose = true` logs every second, as today.

The feed reads the flag at `startSmart` from `DiagFlags.verboseFeatures(filesDir)`, which is true when `files/diag_verbose_features` exists. It logs `diag verbose features=on|off` once.

**Status line** (`StatusLogThrottle`, pure). The key is `running|reason|resting|heat|degraded`. The line is logged:
- at once when the key changes;
- otherwise when the line has changed and ≥ 30 s have passed since the last status line;
- always on the heartbeat tick (`tick % 60 == 0`).

**Heartbeat** stays `v=2`, with three keys appended after `restartGapSec`:

```
… restartGapSec=.. opens1m=N wk=timer:N,dwell:N,onset:N,now:N,stream:N stillGapMs=N
```

- `opens1m` is the number of camera opens normalised per minute over the snapshot span.
- `wk` holds the raw counts per wake reason in that span. The reason is chosen when the episode starts, in priority order: `stream` (continuous), `now` (pendingNowAt), `dwell` (pendingDwellAt), `onset` (forceBrief), else `timer`. A reason is counted only when the camera actually opens.
- `stillGapMs` is the session's current `stillGap` for the profile and step, or 0 when there is no session.
- `sensors=` now reads `full|still|low`.

**`tools/battery-run.sh summary`** adds ` opens1m_mean=%.1f` after `batteryC_max`.

Ruling: the per-second features are kept behind a marker file, not a wire setting — no protocol or UI change; tuning is a developer action (`adb shell run-as … touch files/diag_verbose_features`, then restart the session) — cost if wrong: a WALK-tuning run needs one adb command first.

Ruling: the heartbeat stays v=2 and the new keys are appended — the summary parses by key, so old and new lines mix — cost if wrong: none.

## 7. Battery target Off by default
- `HudSettings.batteryTargetHours` defaults to `0`, and old JSON without the field decodes to 0. The setting and its UI are kept.
- `CaptureSettingsUiState.batteryTargetHours` defaults to 0, and `wake` to SETTLE.

Ruling: default Off (user direction): the controller hides calibration problems and is the last resort — cost if wrong: a heavy-music day can run flat with no automatic step-down; the user can turn on 4 h.

## 8. Device protocol (plan, last task)
**Desk runs**, 10 min each:
- unplugged, not worn, glasses still on the desk, lit scene, music off;
- `pauseWhenNotWorn` **off** in the running runs, so parking does not hide the camera behaviour;
- battery target Off.

| Run | Build | Configuration |
|---|---|---|
| D1 | wave A (pre-A2) | capture paused |
| D2 | wave A (pre-A2) | Smart (MOTION), running |
| D3 | A2 | Settle (the new default), running |
| D4 (optional) | A2 | Smart, running (backoff alone) |

Compare `pct_per_min`, `opens1m_mean`, `sensors=` counts, and the CPU / wake lines from the pulled batterystats.

Expect, in D3 against D2:
- `opens1m` ≤ 2 (against about 12);
- `sensors=still` in the heartbeats after the first minute;
- about 2 feature lines per minute.

**Then one worn 20-minute run** with A2 defaults and music on, compared with 1.6 %/min.

## 9. Review log
- r1 (2026-10-21, Opus planner): first draft from the measured runs and the user's direction.
