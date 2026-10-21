# Battery wave A3: glance model and photo-rate calibration

Date: 2026-10-21. Status: draft for implementation. Plan: `docs/superpowers/plans/2026-10-21-battery-wave-a3.md`. Builds on A2 (`2026-10-21-battery-wave-a2-calibration.md`) in `/Users/<user>/Desktop/rokid/pca-int/int` (branch `int/2026-10-21`: battery A + A2 + canvas merged).

## 1. Why
Measured on the A2 build (2026-10-21, worn 51 min: chores, sitting, balcony, eating, a food delivery):
- Overall 0.6 %/min (was 1.6). Active capture 0.75 %/min with about 10 keyframes/min and 8–14 camera opens/min, nearly all `wk=dwell`; camOpenMs about 7 s/min.
- batterystats: 429 camera opens; CPU system_server 19.5 min, our app 5 min, cameraserver 5.5 min; screen on 25× / 5 min (wake reasons `android.policy:KEY` and `wakeUp`).
- The still-scene backoff never stepped up while worn (`stillGapMs` stayed 10 s).
- WorkRest (45/15) rested 17:30–17:45 and sent exactly 1 keyframe/min: the delivery was under-captured.

Root cause: **a "dwell" is any single gyro sample over 0.6 rad/s followed by 1 s without one** (`MotionClassifier.checkDwell`: `hadMotion` is set by one sample). Head micro-motion while sitting, eating or talking fires a dwell every few seconds, each dwell forces a camera open, and dwell keeps are exempt from the 3 s / 6 s cap and compared only to the last keyframe.

**User direction (binding):** calibrate *when* a photo is taken, not battery workarounds. Approved: about 4 photos/min unless the scene really changes, a 1.5 s settle, skip near-identical views, a mathematical glance model now, a trainable rest-vs-movement classifier later, and user-selectable motion sources ("connectors").

## 2. Code facts relied on (read 2026-10-21 on `int/2026-10-21`)
- `MotionClassifier` runs gyro and accel on separate clocks (`gyroNewestMs`, `accelNewestMs`). `HEAD_MOVING_RAD_S = 0.6`, `HEAD_HOLD_MS = 250`, `DWELL_MS = 1000`. Any sample over 0.6 sets `hadMotion`; `checkDwell` emits when STILL and `t − stillSinceMs ≥ 1000`. Walking also sets `hadMotion`.
- `SamplingSession.onDwell` sets `pendingDwellAt` unconditionally; the next tick forces a brief check (`why = dwell`). `keptAt` holds keep times (10 min). `lastKeyframeAt` is set at start and on each emit.
- `KeyframeGate.finish`: a dwell compares to `ring.takeLast(1)` only; dwell keeps need 0.5 s since the last keep; non-dwell keeps 3 s (6 s moving). `HAMMING = 10`. No per-minute cap.
- Rest: `tick` sets `why = "rest"`, the session blocks and `restTick` runs `fixedCapture` every `REST_CAPTURE_MS = 60_000`. `"rest"` is in `SensorRatePolicy.LOW_REASONS`.
- `WorkRest(clock, workMin, restMin)` is process-wide in `GlassesRuntime`; `HudSettings.workMinutes = 45`, `restMinutes = 15`; there is no on/off.
- Screen: no code in `:glasses`, `:phone` or `:services:glasses-link` calls `PowerManager.wakeUp`, a full/bright wake lock, `FLAG_TURN_SCREEN_ON`, `setTurnScreenOn` or `setShowWhenLocked`. `PopupOverlay` and `MinimisedIcon` are `TYPE_APPLICATION_OVERLAY`, `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE | FLAG_LAYOUT_IN_SCREEN`. `MainActivity` sets `FLAG_KEEP_SCREEN_ON` (it keeps an already-on screen on while the full-screen HUD is in front; it does not wake). `onPhoneOverlay` shows Answer / Nudge / Toast pop-ups and launches the HUD for a Confirm; `ForegroundFallback` launches the HUD when the camera is refused in background. The phone side only sends `sendCustomCmd`.
- LiveFit (`../live-fitness-tracker`) has no IMU classifier. Reusable ideas only: `AutoTypeDetector` (a candidate class must hold for `stableMs` before it is reported) and `SwipeClassifier` (the touchpad sends DPAD key events, which is what `android.policy:KEY` wakes are). No code is copied.

## 3. Glance model (math)
Let ω(t) be the gyro vector (rad/s) and m(t) = |ω(t)|. Parameters (`GlanceParams`, defaults):

| Name | Default | Meaning |
|---|---|---|
| `onsetRadS` | 0.6 | m above it starts a bout (= `HEAD_MOVING_RAD_S`) |
| `releaseRadS` | 0.3 | m above it keeps a bout alive (hysteresis) |
| `bridgeMs` | 250 | a bout ends when no sample > release for longer than this (= `HEAD_HOLD_MS`) |
| `minBoutMs` | 300 | a bout shorter than this is micro-motion |
| `minBoutRad` | 0.26 (≈ 15°) | a bout turning less than this (∫ m dt) is micro-motion |
| `settleMs` | 1 500 | stillness needed after a real bout |
| `maxDtMs` | 100 | dt clamp for integration (batches, gaps) |
| `deadbandRadS` | 0.05 | m under it is not integrated into the pose (gyro bias) |

**Detector** (`GlanceDetector`, pure, per gyro sample, gyro clock):
- **QUIET** → on m > onset: **BOUT**(start = t, lastAbove = t, turned = 0, armed = false).
- **BOUT**: `turned += m · dt`; if m > release, `lastAbove = t`. When `t − lastAbove > bridgeMs` the bout ends at `end = lastAbove`:
  - **real** when `end − start ≥ minBoutMs` and `turned ≥ minBoutRad` → **SETTLING**(since = end, armed = true);
  - else **micro** (counted): back to **SETTLING**(since = end) when a real bout was armed before it, else **QUIET**.
- **SETTLING**: m > onset → **BOUT** (the armed flag carries over); when `t − since ≥ settleMs` and armed and the classifier state is STILL → emit **glance**(t), go **QUIET**.
- **Walking**: while the classifier is WALKING, `onWalking(t)` puts the detector in SETTLING(since = t, armed = true). So a walk end followed by 1.5 s still is a glance ("arrived"), as today.

A glance is therefore: a head movement of ≥ 300 ms and ≥ 15° (gaps ≤ 250 ms bridged, samples ≥ 0.3 rad/s count as movement), then ≥ 1.5 s with no sample over 0.6 rad/s. Micro-motion (a jerk, chewing, nodding while talking) never arms a glance, but it does postpone a pending one (the head has not settled).

The glance replaces the old dwell: `MotionClassifier.dwell` now emits glances. **MotionState transitions, onset and decelerating are unchanged**, so MOTION-wake streaming and its tests keep their behaviour. `GlanceParams.LEGACY` = (release 0.6, minBout 0 ms, minBoutRad 0, settle 1 000 ms, novelty and caps off) reproduces the old dwell for existing tests.

**Head pose.** The classifier integrates `r += ω · dt` (3-vector, dt clamped to `maxDtMs`, samples with m < deadband skipped) since the last mark. `rotationSinceMarkRad() = |r|` (net turn: a look away and back cancels). `walkedSinceMark()` is true when WALKING occurred since the mark. The session marks the pose at start and on every keep. Drift with the deadband: under 0.05 rad/s × 20 s = 1 rad worst case, typically far less; the novelty window (20 s) bounds it.

Ruling: replace only the dwell with the glance detector and keep MotionState as it is — the measured fault is the dwell trigger, and the state drives MOTION streaming, the sensor rate and many green tests — cost if wrong: micro-motion still flips the state to HEAD_MOVING and back (FULL sensor rate for 10 s after each flip); fix in the ML wave.

Ruling: thresholds from the current constants (0.6 / 250 ms) plus 0.3 release, 300 ms, 15°, 1.5 s — the feature logs only carry per-second `gyroMax`, which cannot separate bouts from jitter, so these are first-principles values, made tunable (`GlanceParams`, a canvas touch point later) and re-fitted from the labelled CSV (§8) — cost if wrong: quick glances of 15° in under 300 ms are missed (the timer check still sees the scene within its gap).

## 4. Novelty gate before opening
In `SamplingSession.onDwell` (a glance), in order:
1. **Not novel:** `now − lastKeyframeAt < noveltyWindowMs (20 000)` and `rotationSinceMarkRad() < noveltyMinRad (0.30 ≈ 17°)` and not `walkedSinceMark()` → no camera open; count `novelSkip`.
2. **Over the cap** (§5): keeps in the last 60 s ≥ the current cap and `rotationSinceMarkRad() < bigTurnRad (0.8 ≈ 46°)` and not walked → no open; count `capped`.
3. **Hard ceiling:** keeps in the last 60 s ≥ 2 × cap → no open; count `capped`.
4. Else `pendingDwellAt = now` (as today).

After the open, the gate skips **keeping** a near-identical frame: a glance frame is now compared to the **whole ring of 5** (was the last keyframe only), same `HAMMING = 10`; count `same` (DUPLICATE skips).

Ruling: a 20 s / 17° novelty window on the integrated gyro — the camera's view barely changes under a 17° net turn, and the rule is free (no camera) — cost if wrong: a scene change in front of a still head within 20 s of the last keep waits for the timer check (10–60 s).

Ruling: glance frames are de-duplicated against the whole ring — looking back and forth between two things (TV and plate) re-kept both every time — cost if wrong: a return to a view seen ≤ 5 keeps ago is not re-captured.

## 5. Rate cap
`KeyframeGate.offer(..., capPerMin: Int = Int.MAX_VALUE)`; the gate keeps an in-memory deque of keep times (all keeps, forced included).
- Let `k` = keeps in the last 60 000 ms and `dMin` = min over the ring of `min(Hamming d, Hamming p)` for the best fresh candidate (64 when the ring is empty).
- `k ≥ 2 × capPerMin` → `Skip(CAPPED)`.
- `k ≥ capPerMin` and `dMin < BIG_CHANGE (22)` → `Skip(CAPPED)`.
- "Capture now" (`force`) is exempt.
- The session passes `capPerMin = 4` (working) or `2` (resting).

So: ≤ 4 keyframes per rolling minute; up to 8 when each extra frame differs from every ring keyframe by ≥ 22 of 64 bits.

Ruling: `BIG_CHANGE = 22` — unrelated images sit near 32 ± 4 bits, a moved view of the same scene rarely exceeds 20, and "same" is ≤ 10 — cost if wrong: a busy but related scene (a crowded room) loses frames 5–8 of a minute.

Ruling: drop over-cap glances before the camera opens unless the head turned ≥ 46° or walked — only an IMU signal is free before opening — cost if wrong: a big scene change under a small turn (a door opening in front) waits for the next minute or a timer check.

## 6. WorkRest
- New `HudSettings.workRestEnabled: Boolean = false` (additive). Old glasses ignore it (they keep 45/15); new glasses with an old phone decode false.
- `WorkRest(..., enabled: () -> Boolean = { true })`: when disabled, `onSampling`/`onPaused` clear `resting` and the work clock and return.
- When enabled, **rest no longer blocks**. The session keeps running with `CaptureProfile.forRest()`: wake SETTLE, `settledCheckMs = max(x, 30 000)`, `quietCheckMs = max(x, 60 000)`, `walkCheckMs = max(x ?: settled, 30 000)`, `stillCapMs = max(x, 120 000)`, and `capPerMin = 2`. `restTick`/`fixedCapture` are no longer called in rest. `SessionStatus.restingUntilMs` stays set (HUD and phone still show resting).
- Phone: Settings → Capture → Work and rest gets a switch "Work and rest" (default off); the Work/Rest rows show only when on.

Ruling: WorkRest default Off — it was a heat/battery guess; HeatGuard already slows and stops on real heat, and the measured rest cost the delivery moments — cost if wrong: about 0.75 vs 0.46 %/min for 15 of every 60 min if the user wanted the rest saving (≈ 4.5 %/h).

Ruling: rest = glances only at cap 2/min with ≥ 30 s timer checks, instead of 1 Fixed photo/min — a Fixed photo is blind to what matters, a glance is not — cost if wrong: rest drains a little more than camera-off (estimate 0.5 vs 0.46 %/min).

## 7. Screen wake-ups
Finding (§2): our code never wakes the display. The 25 wakes are `android.policy:KEY` (the wearer's touchpad/button, the DPAD keys LiveFit documented) and `wakeUp` from a system caller (Rokid). So no wake path is removed; instead:
- **Measure:** `ScreenWakeStats` (pure) counts `ACTION_SCREEN_ON` and attributes a wake to us when one of our UI events (pop-up shown, HUD/Confirm launch, fallback launch) happened ≤ 2 000 ms before it. Heartbeat `scrOn=N scrOurs=N` (raw counts per span).
- **Gate:** a **Nudge** pop-up is not shown while the screen is off (`PowerManager.isInteractive == false`); counted `nudgeOff`. Answers, Toasts and Confirms are unchanged (the wearer asked for them).

Ruling: measure and gate nudges, no other change — nothing of ours wakes the display, and blocking Rokid's wakes is forbidden — cost if wrong: if `scrOurs` turns out non-zero, a follow-up gates that event.

## 8. Motion sources and ML groundwork
**Sources (user direction, binding).** Plain Kotlin in `capture/motion/source/`:

```kotlin
enum class SourceKind { GLASSES_IMU, PHONE, WATCH, RING, HOME_CAMERA }
data class MotionEvidence(val atMs: Long, val state: MotionState, val confidence: Float, val sourceId: String)
interface MotionSignalSource {
    val id: String; val kind: SourceKind
    val available: StateFlow<Boolean>
    val evidence: Flow<MotionEvidence>
}
object MotionFusion { fun fuse(latest: List<MotionEvidence>, nowMs: Long): MotionEvidence? }
```
- Fusion rule: drop evidence older than 3 000 ms or with confidence < 0.5; the glasses IMU is authoritative for HEAD_MOVING; another source may lift a glasses STILL to WALKING only with confidence ≥ 0.7; otherwise the glasses evidence wins. With only the glasses source, fusion is the identity.
- `GlassesImuSource` wraps the classifier (one evidence per second, confidence 1.0, always available).
- Setting `HudSettings.motionSourcesOff: List<String> = emptyList()` (ids the user turned off; `glasses_imu` can never be off). Phone UI: Settings → Capture → "Motion sources": Glasses IMU (on, locked), Phone motion and Galaxy Watch (LiveFit) shown disabled "coming later".
- In A3, fusion runs in shadow (logged in the CSV), not driving capture.

Ruling: Phone source deferred to wave B (context hub) — it needs an activity-recognition permission, a new link channel and clock alignment — cost if wrong: no body-motion corroboration in A3.

**Model interface** (`capture/motion/model/`):
- `data class SecondFeatures(gyroMax, gyroMean, gyroRms, aboveOnFrac, accVar, stepHz, rotRad)` with `toArray()` in that order.
- `interface MotionModel { val id: String; fun classify(f: SecondFeatures): MotionState }`.
- `HeuristicMotionModel(p)`: WALKING if `accVar > 0.25 && stepHz in 1.2..2.8`; HEAD_MOVING if `aboveOnFrac ≥ 0.3 && rotRad ≥ p.minBoutRad`; else STILL.
- `TreeMotionModel(feature: IntArray, threshold: FloatArray, left: IntArray, right: IntArray, leafClass: IntArray)`: walks from node 0 (`x[feature] <= threshold` → left; a leaf has `left == -1`); classes index `[STILL, HEAD_MOVING, WALKING]`.

**Labelled capture.**
- `enum class MotionLabel { NONE, SITTING, GLANCING, CHORES, EATING, WALKING, NOT_WORN }` and `HudSettings.motionLabel: MotionLabel = NONE` (unknown → NONE). Phone: Settings → Capture → "Motion labels (training)": a choice row; each change is pushed like any setting.
- Glasses write `files/motion_features.csv` one row per second while `motionLabel != NONE` or the verbose marker exists; rotate to `.1` at 4 MiB. Header:
  `t_ms,gyro_max,gyro_mean,gyro_rms,above_on_frac,acc_var,step_hz,rot_rad,glances,micro,state,model,fused,label,rate`
  Numbers (`%.3f`) and enum names only.
- `tools/motion-train/train.py` (Python 3, scikit-learn): reads CSVs, drops rows within 3 s after a label change and `NONE`/`NOT_WORN`; maps SITTING, EATING → STILL; GLANCING, CHORES → HEAD_MOVING; WALKING → WALKING; `DecisionTreeClassifier(max_depth=4, min_samples_leaf=20, class_weight="balanced")` with leave-one-file-out validation; prints the confusion matrix; writes `TrainedMotionModel.kt` (the five arrays for `TreeMotionModel`). A `--logistic` option prints weights instead.

## 9. Heartbeat and measurement
Heartbeat stays `v=2`; appended after `stillGapMs`:
```
… stillGapMs=N glances1m=N micro1m=N novelSkip1m=N capped1m=N same1m=N scrOn=N scrOurs=N nudgeOff=N
```
`*1m` are normalised per minute over the span; `scrOn`, `scrOurs`, `nudgeOff` are raw counts in the span.

**CPU sampler** (`tools/battery-run.sh`): `start` also launches a `cpu_logger` loop (every 60 s) that writes `/data/local/tmp/cpu.log` lines `MM-DD_HH:MM:SS cpu <label> pid=<pid> ticks=<utime+stime>` for app, `system_server` (ss), cameraserver (cam), the camera provider (camprov), the sensors HAL (sens), bluetooth (bt), audioserver (audio) and the Rokid assistant (assist), plus the top 12 `system_server` threads as `thr ss <comm> ticks=<n>`. Only our labels, sanitised thread names and numbers are written; `pull` filters it with the handoff §2 pattern. `summary` prints `cpu_s_per_min app=.. ss=.. cam=.. camprov=.. sens=.. bt=.. audio=.. assist=..` and `ss_threads=<comm>:<s/min>,…` (top 8); `summary <dir> minutes` prints a per-minute table aligned with heartbeat counters.

Ruling: `/proc/<pid>/stat` ticks over `top` — cumulative, exact, cheap, and the same on every toybox — cost if wrong: none.

## 10. Expected results (worn)
- kf1m ≤ 4 (≤ 8 only with big changes); opens1m about 4–6 while active (was 8–14); micro1m visible and large while sitting and eating; novelSkip1m + capped1m account for the removed opens.
- Active drain about 0.5–0.6 %/min (was 0.75); the cameraserver and system_server CPU per minute fall in proportion to opens.

## 11. Review log
- r1 (2026-10-21, Opus planner): first draft from the A2 worn run, the user's direction and the motion-sources addendum.
