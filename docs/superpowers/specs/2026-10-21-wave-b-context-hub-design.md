# Wave B: motion and context hub

Date: 2026-10-21. Status: draft for implementation (user asleep; rulings below are the planner's, listed in §12 for the user). Plan: `docs/superpowers/plans/2026-10-21-wave-b-context-hub.md`.

Builds on: battery wave A3 (`2026-10-21-battery-wave-a3-glance-model.md`): its glance model, 4/min cap (`capNow()`), `CaptureProfile.forRest()`, `PopupGate`, the heartbeat keys and the "Motion sources" scaffold (`MotionSignalSource`, `MotionFusion`, `GlassesImuSource`, `HudSettings.motionSourcesOff`). Wave B does not redo any of that. Room: the experiment canvas took v3 and wave 2 took v4 (`int/2026-10-21`, commit `451ebb6`); Wave B takes **v5**.

## 1. Goal and user direction (binding)
- Lower the capture rate when the user is **flying**, **in a car or taxi as a passenger**, **driving**, or **resting**. Keep the full rate when **walking or exploring**. **Never** lower capture on a false "vehicle" while the user is actually walking.
- Signals come from user-selectable sources ("connectors"): glasses IMU (exists), phone (activity recognition, GPS speed, phone IMU), Galaxy Watch via LiveFit, later rings and home cameras.
- Passenger vs driving (handoff §10.0): Android Auto / car Bluetooth is the first deterministic signal, fused with an image-based intent step (activity classified from a keyframe) that routes to intent-specific prompts later. The image must be able to override car Bluetooth, and car Bluetooth the image.
- Privacy: location never leaves the phone raw; only a coarse state goes to the glasses; every source is opt-in; logs carry counts only.
- The phone-side sensing cost is itself measured.

## 2. Code facts relied on (read 2026-10-21)
- `core:model` `Protocol.kt`: `PROTOCOL_VERSION = 1`; `Channels` has `pc_motion_hint` (glasses → phone, `MotionHint(state, atMs)`, defined in wave 1, **never sent**, and it has no `v` field, so `Wire.decode` would reject it as `VersionMismatch(null)`); `pc_phone_ctx` (phone → glasses, `PhoneContextMessage(v, inUse, atMs, sessionStartMs)`). `MotionState` already has `VEHICLE`. `Wire` = `ignoreUnknownKeys`, `encodeDefaults`, `coerceInputValues` (an unknown enum **property** falls back to its default; an unknown enum **inside a list** still fails the whole decode).
- Phone `hub/Hub.kt` sends `pc_wear` and `pc_phone_ctx` on change and in `onReady()`; stale messages are filtered on the glasses by `atMs < sessionStartMs`. `HubGraph.build` wires `PhoneUseSignal` (screen receivers) into `Hub(phoneUse = …)`. Glasses → phone messages are decoded in `services/glasses-link/.../GlassesInbound.kt` into `GlassesEvent`.
- Glasses `link/HudController.kt` subscribes per channel and exposes callbacks (`onWear`, `onPhoneUse`) that `GlassesRuntime` wires; session close calls them with null. `CaptureService` builds `profile = { CaptureProfile.of(s.cameraWake, budget.tier.value) }` and `phoneInUse`.
- Phone manifest already declares `ACTIVITY_RECOGNITION`, `BLUETOOTH_CONNECT`, `ACCESS_COARSE_LOCATION`; `ContextHubService` is a foreground service of type `connectedDevice|microphone`. No Google Play services and no `androidx.car.app` dependency yet. `connectors/sources/LocationSource.kt` reads a last-known coarse fix for a place label (unrelated; untouched).
- AI: `PromptKey(role, label)`; wave 2 appends `CLUSTER_SUMMARY(Role.VISION_EXTRACT, …)` after the existing entries so `PromptKey.of(role)` still returns `VISION_EXTRACT` (commit `b2fbb0e` shows every place a key is registered). `CallClass { USER, EXTRACT, NUDGE, CAPTION_QUEUE, BACKFILL, OTHER }`; `CallBudget` charges EXTRACT/OTHER/USER 1 token, USER bypasses. There is **no** EXPERIMENT class (the canvas uses USER). `FramePhotos.kt` loads sealed frame images into `ExtractPhoto`.
- LiveFit (`../live-fitness-tracker`): watch ↔ LiveFit phone app (`com.livear.fit`) over the Wear OS Data Layer, which only works between apps with the same application id. So our app cannot listen to the watch directly: **the LiveFit phone app must forward**. LiveFit has no motion-state message today; `AutoTypeDetector` (a class must hold `stableMs` before it is reported) is the pattern to reuse there.

## 3. Architecture
```
 glasses IMU ──pc_motion_hint──┐                          ┌── pc_motion_ctx ──> glasses ContextGate
 phone: activity transitions ──┤                          │      (TTL, local walking veto)
 phone: step detector ─────────┤                          │            │
 phone: speed (on demand) ─────┼──> ContextFusion (phone, ┘            v
 phone: car BT / Android Auto ─┤     pure core:model)        ContextPolicies table
 phone: airplane mode ─────────┤                             → CaptureProfile + cap + nudges
 watch (LiveFit forwarder) ────┤
 image intent (INTENT_CLASSIFY)┘
```
- **Plain-Kotlin core** (`core:model`, package `com.rokid.context.core.model.context`): evidence types, `ContextFusion`, `ContextPolicies`, the wire messages and the watch message codec. No Android imports. JVM tests.
- **Adapters** (phone, package `com.rokid.context.phone.context`): one class per source, each with a pure core (`onX(...)` methods tested directly) and a thin Android shell (receivers, Play services, providers), the `PhoneUseSignal` pattern. **Fakes** in tests drive the pure cores.
- Fusion runs **on the phone** (it has every source but the glasses IMU, which arrives as `pc_motion_hint`). The glasses apply the result with a **local walking veto** so a wrong phone state can never cost capture while the glasses see walking (no round-trip latency).

Ruling: fuse on the phone and veto on the glasses — the phone has 7 of 8 sources and the AI result; the glasses keep the safety-critical override local — cost if wrong: one extra hint message a minute over Bluetooth (≈ 100 bytes).

## 4. Wire (PROTOCOL_VERSION stays 1, additive only)
**`pc_motion_ctx`** (phone → glasses), new `Channels.MOTION_CONTEXT = "pc_motion_ctx"`:
```kotlin
@Serializable enum class ContextState { UNKNOWN, REST, WALKING, VEHICLE_PASSENGER, VEHICLE_DRIVING, FLIGHT }
@Serializable data class MotionContextMessage(
    val v: Int = PROTOCOL_VERSION,
    val state: ContextState = ContextState.UNKNOWN,
    val confidence: Float = 0f,          // 0..1
    val sinceMs: Long = 0L,              // phone clock, when the state was entered
    val atMs: Long,                      // phone clock, when sent
    val validForMs: Long = 180_000L,     // the glasses drop it this long after receipt
    val apply: Boolean = true,           // false = shadow mode (logged, not applied)
    val reasons: List<String> = emptyList(), // ContextReason names; strings so a new reason never breaks an old decoder
    val sessionStartMs: Long = 0L,
)
```
Sent on every committed state change, in `onReady()`, and every 60 s while the state is not UNKNOWN (keeps the TTL alive). The glasses measure the TTL from **receipt time** on their own clock, so no clock alignment is needed.

Ruling: TTL by receipt time, not by `atMs` — the two clocks differ and the hello offset can be stale; receipt time is exact to the BT latency — cost if wrong: a message delayed in a BT queue lives up to a few seconds longer.

Ruling: `reasons` is `List<String>` — `coerceInputValues` does not cover enums inside lists, so a new reason would fail an old glasses' whole decode — cost if wrong: none (glasses only log them).

**`pc_motion_hint`** (glasses → phone, existing channel, first use): `MotionHint` gains trailing fields `v: Int = PROTOCOL_VERSION`, `stillFrac5m: Float = -1f` (share of the last 300 s the classifier was STILL; −1 = not yet known), and `state` gets the default `MotionState.UNKNOWN`. Sent on every change into or out of WALKING (at most one per 5 s) and every 60 s with the current state.

## 5. Sources (phone)
Every source is **opt-in, default off** (`ContextSourceSettings`, phone-only SharedPreferences `context_sources`). Ids: `phone_activity`, `phone_steps`, `phone_speed`, `phone_car`, `watch_livefit`, `image_intent`. A3's glasses-side `motionSourcesOff` is unchanged (`glasses_imu` is always on); the glasses additionally ignore `pc_motion_ctx` when `"phone_motion"` is in `motionSourcesOff` (a kill switch, kept from A3's id). A phone switch **"Adjust capture to context"** (`applyToCapture`, default on) sets `apply`.

| Source | API | Evidence | Cost control |
|---|---|---|---|
| Activity (`phone_activity`) | Play services Activity Recognition **Transition API** (`requestActivityTransitionUpdates`) for STILL, WALKING, RUNNING, ON_BICYCLE, IN_VEHICLE, ENTER and EXIT; PendingIntent `FLAG_MUTABLE` to a non-exported receiver | `ActivityTransition(activity, enter)` | event-driven, no polling |
| Phone IMU (`phone_steps`) | `Sensor.TYPE_STEP_DETECTOR`, `maxReportLatencyUs = 30 s` (batched, non-wake-up) | `Steps(stepsPerMin)` per 60 s bucket | batched |
| Speed (`phone_speed`) | `FusedLocationProviderClient`, `PRIORITY_BALANCED_POWER_ACCURACY`, interval 30 s, min 15 s; **only while** a vehicle or flight is suspected (car link, activity IN_VEHICLE/ON_BICYCLE, airplane mode, an image vehicle label) | `Speed(kmh, precise)` | on demand; off otherwise |
| Car (`phone_car`) | A2DP and HEADSET `ACTION_CONNECTION_STATE_CHANGED` + profile proxies at start; device class `AUDIO_VIDEO_CAR_AUDIO`, or an address in the user's **"My car"** list; Android Auto via `androidx.car.app.connection.CarConnection` (`PROJECTION` or `NATIVE`) | `CarLink(kind, connected)` with kind `MY_CAR_BT`, `CAR_CLASS_BT`, `ANDROID_AUTO` | event-driven |
| Airplane (with `phone_speed` or `phone_activity`) | `ACTION_AIRPLANE_MODE_CHANGED` + `Settings.Global.AIRPLANE_MODE_ON` | `Airplane(on)` | event-driven |
| Watch (`watch_livefit`) | §8 | `WatchMotion(state, confidence)` | changes only |
| Image intent (`image_intent`) | §7 | `Intent(label, confidence)` | ≤ 1/min in a vehicle, else 1/5 min |

**Speed**: `location.hasSpeed()` → `Speed(speed × 3.6, precise = true)`. Otherwise derived from the previous fix: `d / Δt` when `Δt ≥ 30 s` and `d > 3 × (acc₁ + acc₂)`, `precise = false` (with approximate location only large moves — highways over minutes, flights — pass this test, which is the intent). Fixes live in memory only (the last one), are never stored, logged or sent; only `kmh` leaves the adapter.

Ruling: location only on demand, BALANCED power, FINE requested but approximate accepted — speed matters only to confirm a vehicle or flight that another signal already suggests; a permanent fix stream would cost more than it saves — cost if wrong: a taxi with no car link, activity recognition off and no image label is not detected (it stays UNKNOWN = full rate, the safe side).

Ruling: Transition API, not periodic `requestActivityUpdates` — transitions are batched by Play services in the sensor hub and are the documented low-power path; they carry no confidence, so fusion uses a fixed 0.8 — cost if wrong: 30–90 s detection latency.

Ruling: car = `AUDIO_VIDEO_CAR_AUDIO` class or the user's "My car" list; Android Auto via `CarConnection`; no `UiModeManager` — car mode is not set by today's Android Auto projection and `CarConnection` is the supported API — cost if wrong: a car head unit that reports another class is seen only after the user adds it to "My car". The glasses (a BT audio device) are never car class.

Ruling: phone IMU = the hardware step detector — it is what "phone IMU" can add over activity recognition at near-zero cost — cost if wrong: none (opt-in).

Ruling: dependencies `com.google.android.gms:play-services-location:21.3.0` and `androidx.car.app:app:1.4.0` in `:phone` — the only supported APIs for activity transitions and Android Auto — cost if wrong: about 1 MB APK; a phone without Play services reports the two sources unavailable.

Location while backgrounded needs the foreground service type `location`: `ContextHubService` adds it (`FOREGROUND_SERVICE_LOCATION`) to its type mask **only while** `phone_speed` is on and a location permission is granted.

## 6. Fusion engine (`ContextFusion`, pure)
Input: `onEvidence(e)`; output: `evaluate(nowMs): ContextDecision(state, confidence, sinceMs, reasons, pDriver)`. The phone calls `evaluate` on every evidence and every 10 s. Times are phone wall clock (`pc_motion_hint` is stamped at receipt).

### 6.1 Derived facts at `now` (`FusionParams` defaults)
- **walking** = any of: glasses hint state WALKING and hint age ≤ 90 s; activity WALKING or RUNNING entered and not exited (age ≤ 30 min); steps ≥ 60/min in a bucket ≤ 90 s old; watch WALKING or RUNNING (age ≤ 30 min).
- **fast** = a `Speed` ≤ 120 s old with `kmh ≥ 25`.
- **walkingVeto** = walking and not fast. (Physics: a phone at ≥ 25 km/h is not on foot; walking along a train or plane aisle is the only exception and costs a few minutes of low rate.)
- **vehicleDet** = any of: Android Auto connected; a car BT link connected and not contradicted; activity IN_VEHICLE entered and not exited (age ≤ 30 min); fast; watch IN_VEHICLE. **Held** for 180 s after it last held (traffic lights, tunnels, a short stop). "Strong" = all but car BT.
- **vehicleImage** = the two newest intents (newest ≤ 5 min old, ≤ 3 min apart) are both DRIVING or VEHICLE_PASSENGER with confidence ≥ 0.7.
- **btContradicted** = car BT is the only vehicle signal (no strong one) and the two newest intents (newest ≤ 5 min) are both non-vehicle (SITTING_IDLE, COMPUTER_WORK, WORKOUT, WALKING) with confidence ≥ 0.7. Lasts until that car link disconnects.
- **flight** = (airplane mode on for ≥ 120 s and (a `Speed ≥ 150` within 10 min, or the newest intent ≤ 10 min old is FLIGHT_CABIN with confidence ≥ 0.6)) or a `Speed ≥ 350` within 10 min.
- **still** = the glasses hint (≤ 120 s old) has `stillFrac5m ≥ 0.85`, or `≥ 0.70` when the newest intent (≤ 10 min) is SITTING_IDLE or COMPUTER_WORK with confidence ≥ 0.7; and activity is not WALKING/RUNNING/ON_BICYCLE; and the newest intent is not WORKOUT. Or watch ASLEEP.

**Review rulings (fix round 1, safe direction):**
- Car-link, airplane and activity events apply in **arrival order**; expiry and the hold use the engine's own monotonic arrival clock, the event stamp is informational (a disconnect after a small backwards clock step is never dropped).
- **fast** is `fastConfirmed` = two CONSECUTIVE readings >= 25 km/h at most 45 s apart (`fastConfirmGapMs`; the speed source samples every 30 s, min 15 s), both <= 120 s old; a slower reading between them resets the pair (60, 10, 60 does not confirm). The walking veto is lifted and `SPEED` is emitted only then; a single reading is recorded and changes nothing.
- Task 4 (deferred, no code now): after a reading >= 25 km/h the speed source requests ONE quick follow-up fix (about 10-15 s later) so confirmation comes sooner.
- The 180 s **hold** counts only while the committed state is already a vehicle state; a held-only vehicle fact never starts a new vehicle episode.

### 6.2 Candidate (first match wins)
1. walkingVeto → **WALKING**
2. flight → **FLIGHT**
3. vehicleDet or vehicleImage → **VEHICLE_DRIVING** or **VEHICLE_PASSENGER** by §6.3
4. still → **REST**
5. else **UNKNOWN** (the default: capture as configured).

### 6.3 Driver or passenger (the open question, ruled)
Log-odds: `L = logit(prior) + Σ s·logit(clamp(c, 0.55, 0.95))` over the newest 3 intents that are ≤ 10 min old, `s = +1` for DRIVING, `−1` for VEHICLE_PASSENGER, other labels ignored; `pDriver = sigmoid(L)`.

| Car link now | prior |
|---|---|
| Android Auto | 0.85 |
| "My car" BT | 0.75 |
| car-class BT (not in the list) | 0.50 |
| none (taxi, bus, train, image only) | 0.15 |

Decision: `pDriver ≥ 0.6` → DRIVING; `≤ 0.4` → PASSENGER; inside the band the previous vehicle sub-state stays; a new vehicle episode inside the band starts as DRIVING when `pDriver ≥ 0.5`, else PASSENGER.

**Override table** (what the user asked for: the image can override car BT, and car BT the image):

| Case | L | pDriver | Result |
|---|---|---|---|
| Android Auto, no image | 1.73 | 0.85 | DRIVING |
| Android Auto + 1 × PASSENGER 0.8 | 0.35 | 0.59 | band: stays DRIVING |
| Android Auto + 2 × PASSENGER 0.8 | −1.04 | 0.26 | PASSENGER |
| My car + 1 × PASSENGER 0.9 | −1.10 | 0.25 | PASSENGER (a family member drives) |
| My car + 1 × PASSENGER 0.8 | −0.29 | 0.43 | band: stays DRIVING (the episode started at 0.75) |
| Car-class BT (friend's car or taxi, the user's example) + 1 × PASSENGER 0.8 | −1.39 | 0.20 | PASSENGER |
| Car-class BT, no image yet | 0 | 0.50 | DRIVING until the first image (probe fires at once, §7.3) |
| No link (taxi) | −1.73 | 0.15 | PASSENGER |
| No link + 1 × DRIVING 0.8 | −0.35 | 0.41 | band: stays PASSENGER |
| No link + 2 × DRIVING 0.8 | 1.04 | 0.74 | DRIVING |
| Android Auto + PASSENGER 0.6 (weak image) | 1.33 | 0.79 | DRIVING (BT overrides a weak image) |
| Car BT only + 2 × COMPUTER_WORK 0.8 | — | — | btContradicted: not a vehicle |

Ruling: the fusion rule is "walking veto > flight > vehicle (deterministic or two consistent images) > rest > unknown", and driver vs passenger is a log-odds sum of a car-link prior and up to 3 image votes with a 0.4–0.6 hysteresis band — it is explainable, testable with exact numbers, lets one confident image override an ambiguous car-BT link and two override Android Auto, and never lets one weak frame flip the state — cost if wrong: priors/thresholds retuned from logged decisions (all inputs are counted in diagnostics).

Ruling: an ambiguous new vehicle episode starts as DRIVING — DRIVING differs from PASSENGER only by suppressing nudges (§9), the safe side while a car may be moving — cost if wrong: a passenger misses nudges for about a minute, until the first image.

### 6.4 Hysteresis (commit)
The committed state changes to a new candidate only after the candidate held for: WALKING 0 s (immediate); VEHICLE_* from a non-vehicle state 30 s; DRIVING ↔ PASSENGER 60 s; FLIGHT 120 s; REST 120 s; UNKNOWN 60 s. Leaving WALKING needs 60 s without walking (that is the UNKNOWN/other dwell). `sinceMs` = the commit time.

Output confidence: WALKING 0.9; FLIGHT 0.9; VEHICLE = `max(pDriver, 1 − pDriver) × (0.95 strong | 0.8 car BT only | 0.75 image only)`; REST = `stillFrac5m`; UNKNOWN 0.

Reasons (`ContextReason` names): `GLASSES_WALK, PHONE_WALK, STEPS, WATCH_WALK, SPEED, ANDROID_AUTO, MY_CAR_BT, CAR_BT, AR_VEHICLE, WATCH_VEHICLE, IMAGE_VEHICLE, IMAGE_DRIVER, IMAGE_PASSENGER, BT_CONTRADICTED, AIRPLANE, IMAGE_FLIGHT, GLASSES_STILL, IMAGE_IDLE, WATCH_ASLEEP`.

## 7. Image intent (INTENT_CLASSIFY)
### 7.1 Prompt and contract
`PromptKey.INTENT_CLASSIFY(Role.VISION_EXTRACT, "Activity from a photo")`, appended **after** `CLUSTER_SUMMARY`; editable in the prompt editor (and so later from the canvas). One photo per call; output schema `{"label": <IntentLabel name>, "confidence": <0..1>}`.

`@Serializable enum class IntentLabel { SITTING_IDLE, WORKOUT, COMPUTER_WORK, DRIVING, VEHICLE_PASSENGER, FLIGHT_CABIN, WALKING, OTHER }` (core:model). An unknown label parses to OTHER; confidence is clamped to 0..1; a parse failure is a counted failure, never evidence.

Seed prompt (exact):
> You see one photo from smart glasses, from the wearer's point of view. Classify what the wearer is doing. Labels: SITTING_IDLE (sitting or standing still, not using a screen), WORKOUT (exercise, gym, sport), COMPUTER_WORK (a laptop, monitor or keyboard in front of the wearer), DRIVING (a steering wheel and dashboard directly in front: the wearer is in the driver's seat), VEHICLE_PASSENGER (inside a car, taxi, bus or train, not in the driver's seat), FLIGHT_CABIN (inside an aircraft cabin), WALKING (moving on foot), OTHER. Reply only with JSON: {"label": "<one label>", "confidence": <0 to 1>}.

Ruling: two labels beyond the user's six (VEHICLE_PASSENGER, FLIGHT_CABIN) — passenger vs driver needs a positive passenger vote and flight needs a cabin vote; "other" cannot carry either — cost if wrong: a slightly longer prompt.

Ruling: reuse the VISION_EXTRACT binding (no new `Role`) — the same pattern as wave 2's CLUSTER_SUMMARY; a new Role would need endpoint seeding and binding UI — cost if wrong: intent runs on the extraction model, not a cheaper one; a later Role split is additive.

Ruling: CallClass **EXTRACT** (charged), not a "counted not charged" class — no such class exists, and an uncharged class would let intent calls exceed the user's AI call pace; at ≤ 1 call/5 min (≤ 1/min in a vehicle, where capture is capped at 1/min anyway) it takes ≤ 20 % of the 12 s pace only in vehicles — cost if wrong: extraction waits a little longer during car rides.

### 7.2 Storage (Room v5)
`frame_meta` gains nullable columns `intentLabel TEXT`, `intentConf REAL`, `intentAt INTEGER` through `MIGRATION_4_5` (additive `ALTER TABLE ADD COLUMN`, both builders, exported `5.json`, `Migration4To5Test`). Wave 2 owns v4; this migration is written on a base that already has `MIGRATION_3_4` and is never renumbered over it.

### 7.3 Sampler (`IntentSampler`, orchestration)
Runs only while `image_intent` is on and the pipeline is enabled. Triggered on every frame receipt (the existing `KeyframeGraph.signal()` path) and picks the newest `frame_meta` row received ≤ 2 min ago with `intentLabel IS NULL`. It calls when, since the last call (success or failure):
- ≥ 60 s and the phone hub says a vehicle or flight is suspected (`vehicleDet`, `vehicleImage`, airplane on, or the committed state is VEHICLE_*/FLIGHT), or the last result was DRIVING, VEHICLE_PASSENGER or FLIGHT_CABIN (the "confirm" probe);
- else ≥ 300 s (the background probe).
At most one call in flight. A result is written to `frame_meta` and fed to fusion as `Intent(atMs = the frame row's ts, …)`; a result whose frame is older than 10 min is stored but not fed.

## 8. Galaxy Watch via LiveFit (contract; LiveFit changes are out of this repo)
**Transport:** the LiveFit phone app calls `contentResolver.call(Uri.parse("content://com.rokid.context.motionsource"), "motion_state", null, bundleOf("json" to <message>))`. Our exported provider `WatchMotionProvider` checks, in order: `callingPackage == "com.livear.fit"`; the caller's signing certificate SHA-256 equals the digest **pinned when the user turned the Watch source on** (`PackageManager.hasSigningCertificate(pkg, digest, CERT_INPUT_SHA256)`); the source is on; payload ≤ 2 048 bytes; at most 1 message per second (extras dropped); `seq` greater than the last accepted. It returns `bundleOf("ok" to Boolean, "reason" to String?)` with reason one of `not_enabled, bad_caller, bad_cert, too_big, rate, stale_seq, malformed, version`. The manifest adds `<queries><package android:name="com.livear.fit"/></queries>`.

**Message (v1)**:
```json
{"v":1,"seq":42,"state":"WALKING","confidence":0.9,"changedAtMs":1761012345678,"sentAtMs":1761012345900,"source":"galaxy_watch"}
```
`state` ∈ `STILL, WALKING, RUNNING, CYCLING, IN_VEHICLE, EXERCISE, ASLEEP, UNKNOWN` (unknown string → UNKNOWN). No heart rate, steps, GPS or any health value. The phone uses its own receipt time (the watch clock is not trusted).

**LiveFit side (for its own repo):** the watch derives the state from Health Services (`PassiveMonitoringClient` user activity state ASLEEP/EXERCISE/PASSIVE, the exercise type when a workout runs, step cadence ≥ 60/min = WALKING, ≥ 140/min = RUNNING), holds a new state for 30 s (`AutoTypeDetector`-style) and sends **changes only** over its Data Layer path; the LiveFit phone app forwards each change to the call above and re-sends the current state once after it reconnects. A state is current on our side until a change, for at most 30 min.

Ruling: `ContentResolver.call` with a package check and a trust-on-first-use certificate pin — a broadcast cannot identify its sender on API < 34 and a custom signature permission needs both apps on one signing key, which is not known — cost if wrong: LiveFit needs about 20 lines more than a broadcast; a LiveFit re-sign needs the user to toggle the source off and on.

## 9. Capture policy (`ContextPolicies`, plain Kotlin table in core:model)
```kotlin
data class ContextCapturePolicy(
    val capPerMin: Int?,          // null = unchanged (A3 cap)
    val minSettledCheckMs: Long, val minQuietCheckMs: Long, val minWalkCheckMs: Long, val minStillCapMs: Long,
    val forceSettle: Boolean, val pause: Boolean, val nudges: Boolean,
)
```
| State | cap/min | settled ≥ | quiet ≥ | walk ≥ | stillCap ≥ | Settle only | pause | nudges |
|---|---|---|---|---|---|---|---|---|
| UNKNOWN | — | 0 | 0 | 0 | 0 | no | no | yes |
| WALKING | — | 0 | 0 | 0 | 0 | no | no | yes |
| REST | 2 | 30 s | 60 s | 30 s | 120 s | yes | no | yes |
| VEHICLE_PASSENGER | 1 | 60 s | 120 s | 60 s | 180 s | yes | no | yes |
| VEHICLE_DRIVING | 1 | 60 s | 120 s | 60 s | 180 s | yes | no | **no** |
| FLIGHT | 1 | 120 s | 300 s | 120 s | 300 s | yes | no | yes |

Applied on the glasses as `profile.withContext(policy)` (each check gap = max(current, minimum); `wake = SETTLE` when `forceSettle`; `paused ||= pause`) and `capNow() = min(A3 cap, policy.capPerMin)`. A3's WorkRest rest and the context REST combine by taking the stricter value. `nudges = false` makes `PopupGate` drop Nudge pop-ups (counted `nudgeOff`). The table is a constant (`ContextPolicies.DEFAULT`); a later canvas edit replaces it through an additive `HudSettings` field (not in Wave B).

Ruling: FLIGHT keeps capturing at 1/min with 2–5 min checks instead of stopping — the user's question "stop capture entirely while flying?" is still open (handoff §10.0) and stopping is the irreversible choice — cost if wrong: about 4 photos per 10 flight minutes the user may not want (pause remains one tap).

Ruling: DRIVING keeps capturing at 1/min and suppresses nudge pop-ups — a pop-up in the driver's view is a safety risk; a photo is not — cost if wrong: a driver misses a nudge until the car stops (they stay in the phone list).

### 9.1 Glasses `ContextGate` (pure)
`effective(now, localState, lastLocalWalkAtMs)`:
1. No message, or `now − receivedAt > validForMs`, or `!apply`, or `"phone_motion"` in `motionSourcesOff` → UNKNOWN.
2. **Local walking veto:** local classifier WALKING now or within the last 15 s, the message state is REST, VEHICLE_* or FLIGHT, and `"SPEED"` is not in `reasons` → UNKNOWN, counted `ctxVeto`.
3. Else the message state.
A message with `atMs < sessionStartMs` is ignored (stale, as `pc_wear`); a session close resets the gate.

## 10. Measurement
- **Glasses heartbeat** (stays `v=2`) appends after A3's `nudgeOff`: ` ctx=<message state|none> ctxAgeS=<n|na> ctxApplied=<effective state> ctxVeto=<n in span>`.
- **Phone `ContextHubStats`** (counts only): `ar`, `steps`, `locFixes`, `locOnS` (seconds location was requested), `bt`, `aa`, `air`, `hints`, `watch`, `watchRej`, `intent`, `intentFail`, `intentSkip`, `sends`, `changes`, seconds per committed state, and the phone battery % at the start and now. Logged every 10 min as one line, tag `PcCtx`: `ctxhub v=1 state=… ar=… steps=… loc=… locOnS=… bt=… aa=… air=… hints=… watch=… watchRej=… intent=… intentFail=… intentSkip=… sends=… changes=… batt=…->…`. Shown in Settings → Diagnostics → "Context hub". Never a device name, address, coordinate, label text other than enum names, or caption.
- **Phone sensing cost:** two 30-min phone runs, all sources off vs all on, same activity; compare the phone battery % (the `batt` field) and `locOnS` (device checklist).

## 11. Privacy
- Location: one fix in memory in the speed adapter; never persisted, logged, exported or sent; only `kmh` leaves it, and only a `ContextState` + reason names leave the phone.
- "My car" addresses and the LiveFit certificate digest live in the `context_sources` prefs; they are excluded from the settings export and cleared by Delete all. Device names show in the picker UI only.
- Every source is opt-in and asks for its permission when turned on (ACTIVITY_RECOGNITION for activity and steps; FINE+COARSE location for speed, approximate accepted; BLUETOOTH_CONNECT for car). A denied permission leaves the switch off with a short note.
- Intent labels are enum names on `frame_meta`; they are deleted with their frame.

## 12. Open for the user (planner rulings to confirm)
1. **Fusion rule**: walking veto > flight > vehicle > rest > unknown; driver vs passenger = log-odds of a car-link prior (AA 0.85, my car 0.75, car-class BT 0.5, none 0.15) plus up to 3 image votes, band 0.4–0.6 (§6.3).
2. Ambiguous new vehicle episodes start as DRIVING (nudges off ~1 min until the first image).
3. FLIGHT does **not** stop capture (1/min). Your §10.0 question stays open.
4. DRIVING keeps 1 photo/min and suppresses nudge pop-ups.
5. Intent labels add VEHICLE_PASSENGER and FLIGHT_CABIN to your six.
6. Intent cadence 1/5 min, 1/min in vehicles; CallClass EXTRACT (charged); reuses the VISION_EXTRACT model binding.
7. Every source default off; "Adjust capture to context" default on once a source is on.
8. Location on demand only (vehicle/flight suspected); FINE asked, approximate accepted.
9. Watch link = LiveFit phone app forwards via `ContentResolver.call` with a pinned certificate (needs a LiveFit change in its repo).
10. New dependencies: Play services location 21.3.0, androidx.car.app 1.4.0.

## 13. Review log
- r1 (2026-10-21, Opus planner): first draft from the handoff §9.1/§10.0, the A3 spec and plan, the phone hub, and LiveFit.
