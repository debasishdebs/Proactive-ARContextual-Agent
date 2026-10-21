# Wave B: motion and context hub — implementation plan

## Fast path (amended 2026-10-21 evening)
**Why:** the user wears a Galaxy Watch tomorrow and wants to see the fused context drive the glasses end to end. Tasks 1 and 2 are done and reviewed. The remaining work is re-sliced into a **watch-first fast path** (the least code for watch → phone fusion → `pc_motion_ctx` → glasses capture) and a **deferred** set (still planned, after tomorrow). Task numbers are unchanged; changed task text is marked **[AMENDED]** inline.

**New order:**
1. **Task 6** [AMENDED] — context hub with a source registry; works with zero sources registered and sources registered later. Depends on 1, 2 only (not 3–5 any more).
2. **Task 11** — glasses ContextGate, policy, motion hints, heartbeat. Depends on Task 1 only; its code may be written in parallel with Task 6 (glasses files only), but Gradle runs stay one at a time.
3. **Task 10** [AMENDED] — LiveFit watch intake registered as the first real `ContextSource`. Depends on 6.
4. **Task 7a** [AMENDED, the minimal slice of Task 7] — Context sources screen with the "Adjust capture to context" master switch, the watch switch with the certificate pin, a state line, and a one-line Diagnostics entry. Depends on 6 and 10.
5. **Fast-path gate:** merge `int/2026-10-21` first (see the branch note below), then `tools/gate.sh /Users/<user>/Desktop/rokid/pca-ctx/gate-wave-b-fast.log` exits 0. Then **Task 12 step 0** (watch smoke on device, user present).

**Deferred (in this order, after tomorrow):** Task 3 → 4 → 5 (phone sources; each only registers itself with the Task 6 registry), Task 7b (the rest of Task 7), Task 8 → 9 (intent), then the full final gate and Task 12 steps 1–10.

**Deferred review findings** (Codex plan review r2, `proactive-context-agent/.superpowers/codex/waveb-plan-review.md`; fold into these tasks when they are picked up):
- T8/T9 (critical): the intent sampler must respect deletion fences — check `MemoryWipe.generation`, source-enabled and pipeline state at admission **and** completion; persist conditionally in a transaction (use `setIntent`'s row count) and emit evidence only after a successful update; tests for Forget/Delete all and disabling while a classification is suspended.
- T4: location foreground-service activation — define foreground entry/re-entry, an actual `locationHeld` state, per-type fallback that never drops the microphone type, deferred retry on resume, fixes only while location access is valid; tests on API 31/35 grant, background suspicion, refusal, sticky restart.
- T4: approximate speed — keep one baseline fix until displacement/time criteria pass (bounded expiry, reset rules); test a continuous stream of intermediate fixes, not only two endpoints.
- T3/T4: observation time ≠ callback time — map `elapsedRealtimeNanos` of transitions/fixes/step events into the fusion clock, reject stale or out-of-order observations, bucket steps by sensor time; tests for delayed batches and wall-clock changes.
- T8/T9: historical photos must not drive live capture — separate receipt time from validated capture time; backlog/uncertain-age frames are stored but not fed; test buffered flight photos arriving during a walk.
- T9: sampler lifecycle — shared power/thermal/pipeline admission gates (no direct `RoleRunner` bypass), cancellation of an in-flight call on uninstall, completion rechecks, single flight across replacement instances; tests for pause, severe heat, off→on during a call.
- T5: Bluetooth aggregation by `(address, profile)`; recompute kinds when "My car" changes while connected; correct receiver export flags; tests for both counterexamples.
- T8: the seeded `INTENT_CLASSIFY` prompt must pass `TemplateVariables.required()` (needs `{{recent_context}}` or a key-specific rule) and the prompt editor must offer photo input for it; test save-and-run through the real editor path.
- T8/T12 Room v5: update `SqlCipherMigrationDeviceTest` (hardcoded v4, older shapes built by removing wave-2 columns) for v5; real encrypted v4→v5 coverage through both production builders; run it on a disposable DB before installing over user data. Reserve v5 for Wave B (canvas slice 2 becomes v6); verify the merged chain and schema identities incl. upgrades from v3, v4, v5 before install.
- T2 (reviewed engine; change only by a new task): ON_BICYCLE as speed suspicion is in the spec table but not in `vehicleSuspected`; the `fastSpeedLiftsTheVeto` test expects 30 s despite the 60 s exit from WALKING; the vehicle hold should anchor to the last raw vehicle evidence, never the held result (largely covered by Task 2 fix round 1, spec §6.1 review rulings: the hold counts only while already committed to a vehicle; re-check when Task 4 lands). (The −1 confidence is clamped in Task 6, above.)
- T7b/T12 diagnostics: define how adapters' raw fix counts, location-on time and battery readings reach `ContextHubStats`; add enum-only reasons to the `PcCtx` line (the checklist needs them) or revise the checklist.

**User direction — car rule (recorded 2026-10-21 evening; contract for Tasks 5/8/9; Task 2 is not changed in this pass):**
- **Deterministic trigger:** the phone connected to a user-listed Bluetooth device ("My car" / the listed devices) **is** a vehicle episode. Task 5 must make a listed device the primary vehicle trigger (it already is `MY_CAR_BT`, a deterministic `vehicleDet` signal).
- **Image intent only inside a vehicle episode:** the driver-vs-passenger classifier (the expensive INTENT_CLASSIFY step, Tasks 8/9) runs **only while a vehicle episode is on**; no intent calls otherwise (a cost saver). This replaces spec §7.3's 1/5-min background probe: Task 9's `poke()` returns at once unless the hub reports a vehicle episode (committed VEHICLE_* or `vehicleDet`).
- **Vehicle type:** the same call also classifies the vehicle type — two-wheeler, three-wheeler (auto-rickshaw), four-wheeler (car/taxi), bus/train/other. Deferred item for Tasks 1-follow-up/8/9: extend the labels additively (e.g. a new `VehicleType { TWO_WHEELER, THREE_WHEELER, FOUR_WHEELER, BUS_TRAIN_OTHER, UNKNOWN }` field in `IntentOutput` and the schema, a nullable `intentVehicle` column in the v5 migration — decide before Task 8 writes `MIGRATION_4_5`; keep `IntentLabel` values unchanged and never put the enum inside a wire list).
- **Open items for the user:** (a) a two-wheeler rider cannot glance at a phone and wears a helmet — proposed: a two-wheeler driver gets the DRIVING policy (cap 1/min, nudges off), pause to be decided by the user; (b) mismatch with the reviewed engine: Task 2 also takes vehicle evidence from activity recognition IN_VEHICLE, speed, the watch and two consistent images, not only from a listed BT device. Proposed ruling: keep those as additional vehicle evidence (the watch-only fast path depends on watch IN_VEHICLE), but gate the image sampler on the episode as above; confirm with the user. (c) With image calls only inside episodes, image-only vehicle detection (`vehicleImage` with no other signal), image-assisted REST (the 0.70 threshold) and FLIGHT_CABIN votes can no longer start on their own (`btContradicted` still works: a car link is an episode); proposed: treat airplane mode on as an episode for the cabin vote only — confirm.

**Branch note [AMENDED]:** the worktree branch is `ctx/int` (not `ctx/integration`): `int/2026-10-21` @ `6f6cc2b` + A3 (`battery/integration`, merged at `ada2281`). Wave 2's later final-review fixes land on `int/2026-10-21` after that point (today 1 commit: `f04b50a`). The controller runs `git merge int/2026-10-21` into `ctx/int` **before the fast-path gate** (the APK the user wears tomorrow must carry them) and again before the full final gate and before Task 8. If a merged wave 2 fix adds a Room migration or bumps the DB version past 4, **stop and report** before Task 8 (v5 would need renumbering).

**What the watch alone can produce** (spec §6.1/§6.2 with only `WatchMotion` evidence, plus the glasses hints of Task 11; no speed, car link, activity, steps, airplane or image evidence exists until Tasks 3/4/5/9):

| Watch state (age ≤ 30 min) | Fact (§6.1) | Committed state (§6.2, dwell §6.4) | Capture effect on the glasses (§9) |
|---|---|---|---|
| WALKING, RUNNING | walking; `fast` is never true (no speed source) so walkingVeto holds | **WALKING**, immediate; leaving needs 60 s | none: A3 rate unchanged, nudges on |
| IN_VEHICLE | vehicleDet (strong), held 180 s after it ends | **VEHICLE_PASSENGER** after 30 s (prior "no link" 0.15, no image votes → pDriver 0.15 → PASSENGER; confidence 0.85 × 0.95 ≈ 0.81) | cap 1/min, SETTLE only, checks ≥ 60/120/60/180 s, nudges **on** |
| ASLEEP | still | **REST** after 120 s | cap 2/min, SETTLE only, checks ≥ 30/60/30/120 s, nudges on |
| STILL, CYCLING, EXERCISE, UNKNOWN | none | **UNKNOWN** after 60 s | none (today's behaviour) |

The glasses' own hints (Task 11) add: hint WALKING (≤ 90 s) → WALKING (wins over watch IN_VEHICLE, rule 1 beats rule 3); `stillFrac5m ≥ 0.85` (≤ 120 s) → REST after 120 s (the 0.70 threshold needs an image, so not before Task 9). So the fast path can yield exactly **UNKNOWN, WALKING, REST, VEHICLE_PASSENGER**. It **cannot** yield **VEHICLE_DRIVING** (needs a car link, Task 5, or two DRIVING images, Tasks 8/9) or **FLIGHT** (needs airplane mode with speed or a cabin image, or speed ≥ 350, Tasks 3/4/9). Hence: no driver-vs-passenger until Tasks 5/8/9; no nudge suppression while driving until then (a watch-detected car ride is always PASSENGER, nudges on — the user must not rely on it while driving; Task 12 step 5 stays deferred); no btContradicted, no flight, no image REST. [AMENDED, review r2] The glasses' local walking veto is unconditional in every phase (the `SPEED` exception is removed, see Task 11).

**Safety statements (unchanged by the fast path, restated as acceptance criteria):**
- **UNKNOWN = today's behaviour.** The UNKNOWN row is all zeros, cap null, nudges on; `DEFAULT.withContext(UNKNOWN row) == DEFAULT` and `contextCap = { null }` keeps A3 exactly. No source on (or none registered/available) → the hub sends nothing (`message == null`) → the glasses see no message → UNKNOWN.
- **Local walking veto on the glasses [AMENDED, review r2 ruling 2]:** the glasses classifier WALKING now or within 15 s overrides **every** reducing message (REST / VEHICLE_* / FLIGHT) to UNKNOWN, counted `ctxVeto`, **whatever the reasons** (no `SPEED` exception, in any phase). The phone's fusion keeps its own guarded `fast` rule (Task 2 unchanged); the glasses never reduce capture within 15 s of locally seen walking. A WALKING message is never vetoed (it already means full rate).
- **180 s expiry:** the glasses drop a phone state 180 s (`validForMs`) after **receipt** on their own clock; the hub re-sends every 60 s while not UNKNOWN, so a dead phone, a BT drop or a crashed hub returns the glasses to UNKNOWN within 3 min. A session close resets the gate; a message with `atMs < sessionStartMs` is ignored, and within a session a message older (`atMs`) than the last accepted one is ignored [AMENDED r2-8]. Revocation is explicit too: disabling the last source, the master switch or shadow mode sends an UNKNOWN / `apply=false` message before the hub goes silent [AMENDED r2-4], so the 180 s expiry is only the fallback.
- **Watch staleness [AMENDED r2-9]:** LiveFit sends changes **and** re-sends an unchanged state every 10 min; the hub builds its fusion with `FusionParams(watchTtlMs = 1_500_000)` (25 min = two missed refreshes plus margin; a parameter, Task 2 code unchanged). A stale IN_VEHICLE after LiveFit dies can therefore hold PASSENGER (1/min) for up to ~28 min (25 min + 180 s hold); walking always breaks it through the glasses veto and the fusion walking rule. Accepted for tomorrow; revisit with the user (Task 12 step 0 notes).
- **Kill switches:** "Adjust capture to context" off → `apply = false`, sent at once [AMENDED r2-4] → glasses UNKNOWN (shadow mode, still logged); `"phone_motion"` in the glasses' `motionSourcesOff` → UNKNOWN.
- **Hard caps [AMENDED r2-7]:** REST 2/min and vehicle/FLIGHT 1/min are true caps (no dwell 2× bonus); the only exemption is a manual "Capture now" (forced keep), which stays uncapped because the user asked for that photo.

**External dependency for tomorrow:** the watch path needs LiveFit's phone app to forward `motion_state` (spec §8, LiveFit side, in its own repo). Without that forwarder only `FakeLiveFit` unit tests exercise Task 10; `adb shell content call` cannot stand in (caller `com.android.shell` → `bad_caller`, by design — no debug bypass is added). The controller confirms the forwarder's status with the user before Task 12 step 0.

**Spec:** `docs/superpowers/specs/2026-10-21-wave-b-context-hub-design.md`. Read §2 (code facts) and the sections your task names before you start.

**Where to work (controller ruling):** a new worktree `/Users/<user>/Desktop/rokid/pca-ctx/int`, branch `ctx/integration` ([AMENDED] actually `ctx/int`; see the branch note above), created by the controller from `int/2026-10-21` **after** `battery/integration` (wave A3, all 7 code tasks) has been merged into it. That base has the canvas Room v3, wave 2's Room v4 (`451ebb6`, `CLUSTER_SUMMARY` `b2fbb0e`) and A3's glance model, `capNow()`, `PopupGate`, `MotionFusion` and the heartbeat keys. If `MIGRATION_3_4` or A3's `PopupGate`/`capNow()` is missing on your base, **stop and report**; never recreate or renumber them. Submodule: `git submodule update --init -- android-companion/services/ai/src/main/cpp/llama.cpp` (never `--reference`).

Ruling: Wave B starts on `int/2026-10-21` + A3 — it needs A3's glasses hooks and wave 2's v4; branching earlier would force a v5 renumber — cost if wrong: the phone-only tasks (1–7) wait for the A3 merge (about one task's time).

**Streams** (TDD in each: write the test, see it fail, implement, see it pass, commit):
- [AMENDED] Superseded by the fast-path order above: 1, 2 (done) → 6 ∥ 11 → 10 → 7a → merge + fast gate → Task 12 step 0; then deferred 3 → 4 → 5 → 7b, 8 → 9 → merge + full gate → Task 12.
- ~~Task 1 first; then in parallel Task 2, Tasks 3 → 4 → 5, Task 11; then Task 6 (needs 2–5), then in parallel Task 7, Task 8 → 9, Task 10.~~
- Task 12 is the device checklist (not code).

## Global Constraints
- **Modules and package roots:**
  - `:core:model` (Kotlin JVM, `kotlin.test`, JUnit Platform): `android-companion/core/model/src/{main,test}/kotlin/com/rokid/context/core/model/`; new package `com.rokid.context.core.model.context` in subfolder `context/`.
  - `:phone`: `android-companion/phone/src/{main,test}/java/com/rokid/context/phone/`; new package `com.rokid.context.phone.context` in `context/`.
  - `:glasses`: `android-companion/glasses/src/{main,test}/java/com/rokid/context/glasses/`.
  - `:services:glasses-link`: `android-companion/services/glasses-link/src/{main,test}/kotlin/com/rokid/context/link/`.
  - `:services:ai`: `android-companion/services/ai/src/{main,test}/java/com/rokid/context/`.
  - `:services:memory`: `android-companion/services/memory/src/{main,test}/java/com/rokid/context/memory/`.
  - `:services:orchestration`: `android-companion/services/orchestration/src/{main,test}/java/com/rokid/context/`.
- **Wire:** `PROTOCOL_VERSION` stays **1**. Additive fields with defaults and one new channel only. Old glasses ↔ new phone and the reverse must still parse. Unknown enum **properties** fall back to their default (`coerceInputValues`); never put an enum inside a wire list (use `List<String>`).
- **[AMENDED r2-3] Manifest names:** the phone namespace/applicationId is `com.rokid.context`, so `.context.X` resolves to `com.rokid.context.context.X`. Use fully qualified names (`com.rokid.context.phone.context.WatchMotionProvider`) or `.phone.context.…`, and test resolution through the merged manifest (a Robolectric `PackageManager.resolveContentProvider`/`getReceiverInfo` test).
- **Room:** v4 → **v5** through `MIGRATION_4_5`, additive only (`ALTER TABLE frame_meta ADD COLUMN …`), registered in **both** builders in `DatabaseFactory.kt` (`.addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)`), schema exported to `services/memory/schemas/com.rokid.context.memory.db.ContextDatabase/5.json` and committed, tested by `Migration4To5Test` (Task 8 only). Never edit an existing migration. Wave 2 owns v4.
- **Exact names and values** (do not rename): channel `Channels.MOTION_CONTEXT = "pc_motion_ctx"`; source ids `phone_activity`, `phone_steps`, `phone_speed`, `phone_car`, `watch_livefit`, `image_intent`; A3 ids `glasses_imu`, `phone_motion` (kill switch); prefs file ~~`context_sources`~~ **`ctx_hub_state`** [AMENDED r2-1: `context_sources` already belongs to `SourceSettingsStore` (`services/connectors/.../SourceSettings.kt`), is exported and backed up; never reuse it]; provider authority `com.rokid.context.motionsource`, method `motion_state`, extra `json`; LiveFit package `com.livear.fit`; log tag `PcCtx`; prompt key `INTENT_CLASSIFY`. All other names and numbers are in the task that introduces them and in spec §4–§10.
- **Dependencies (phone only, Task 3):** `com.google.android.gms:play-services-location:21.3.0`, `androidx.car.app:app:1.4.0`. No other new library.
- **Gradle:** run from `/Users/<user>/Desktop/rokid/pca-ctx/int/android-companion` through `/Users/<user>/Desktop/rokid/proactive-context-agent/.superpowers/sdd/gw.sh <args>` (it calls `./gradlew --no-daemon`). **One Gradle run at a time** across all Wave B worktrees (the Mac manages 2 in total and other waves use one); if another Gradle run is going, wait. Never `gradlew --stop`. Test commands: `:core:model:test`, `:phone:testDebugUnitTest`, `:glasses:testDebugUnitTest`, `:services:glasses-link:testDebugUnitTest`, `:services:ai:testDebugUnitTest`, `:services:memory:testDebugUnitTest`, `:services:orchestration:testDebugUnitTest`, each with `--tests '<Class>'` first, then the whole module before committing.
- **No device:** no `adb` in Tasks 1–11. No push.
- **Never touch the camera LED** (`LedSourceRulesTest` stays green) or Rokid system services.
- **Secrets and privacy:** never print `.env` values, keys or tokens. Logs and diagnostics carry counts, numbers and enum names only — never a device name, BT address, coordinate, caption, prompt text or certificate digest. A location fix never leaves the speed adapter.
- **Commits:** local only, one per task, the message given, ending with the trailer `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Stage only your task's files (`git add <paths>`), never `git add -A`.
- **Backward compatibility:** new constructor/function parameters get defaults so existing call sites compile unchanged. Fix a test that relies on an old default by passing the old value explicitly; never change behaviour to keep a test green. Where a task changes behaviour on purpose it names the test to rewrite.
- **Android shells vs pure cores:** each phone adapter is a pure class with `on…()` methods (unit-tested with fakes and a fake clock) plus a thin Android shell (`start(context)`/`stop()`) that only translates callbacks; Robolectric tests for shells are optional, pure-core tests are required.

### Task 1: Wire types, evidence types and the policy table (core:model)
**Spec:** §4, §6 (evidence), §7.1 (labels), §8 (watch message), §9 (table).

**Files:**
- modify `core/model/src/main/kotlin/com/rokid/context/core/model/Protocol.kt`
- create `core/model/src/main/kotlin/com/rokid/context/core/model/context/ContextTypes.kt`, `Evidence.kt`, `ContextPolicies.kt`, `WatchMotionMessage.kt`
- tests: `ProtocolTest.kt` (extend), new `context/ContextTypesTest.kt`, `context/ContextPoliciesTest.kt`, `context/WatchMotionMessageTest.kt`

**Implement:**
- `Channels.MOTION_CONTEXT = "pc_motion_ctx"` with KDoc "Wave B, phone to glasses: a [MotionContextMessage]. Additive."; update the `MOTION_HINT` KDoc to "Glasses to phone: a [MotionHint] (wave B: sent on WALKING changes and every 60 s)".
- `MotionHint`: `data class MotionHint(val state: MotionState = MotionState.UNKNOWN, val atMs: Long, val v: Int = PROTOCOL_VERSION, val stillFrac5m: Float = -1f)` (keeps the positional `(state, atMs)` order).
- In `context/ContextTypes.kt` (package `com.rokid.context.core.model.context`, all `@Serializable`):
  - `enum class ContextState { UNKNOWN, REST, WALKING, VEHICLE_PASSENGER, VEHICLE_DRIVING, FLIGHT }`
  - `enum class ContextReason { GLASSES_WALK, PHONE_WALK, STEPS, WATCH_WALK, SPEED, ANDROID_AUTO, MY_CAR_BT, CAR_BT, AR_VEHICLE, WATCH_VEHICLE, IMAGE_VEHICLE, IMAGE_DRIVER, IMAGE_PASSENGER, BT_CONTRADICTED, AIRPLANE, IMAGE_FLIGHT, GLASSES_STILL, IMAGE_IDLE, WATCH_ASLEEP }`
  - `enum class IntentLabel { SITTING_IDLE, WORKOUT, COMPUTER_WORK, DRIVING, VEHICLE_PASSENGER, FLIGHT_CABIN, WALKING, OTHER }` with `companion fun parse(s: String?): IntentLabel` (unknown/null → OTHER).
  - `data class MotionContextMessage` exactly as spec §4 (`atMs` has no default; all others as listed).
- In `context/Evidence.kt` (plain data classes, not serialized): `sealed interface Evidence { val atMs: Long }` with
  `GlassesMotion(atMs, state: MotionState, stillFrac5m: Float)`, `ActivityTransition(atMs, activity: PhoneActivity, enter: Boolean)`, `Steps(atMs, stepsPerMin: Int)`, `Speed(atMs, kmh: Float, precise: Boolean)`, `CarLink(atMs, kind: CarLinkKind, connected: Boolean)`, `Airplane(atMs, on: Boolean)`, `WatchMotion(atMs, state: WatchState, confidence: Float)`, `Intent(atMs, label: IntentLabel, confidence: Float)`;
  `enum class PhoneActivity { STILL, WALKING, RUNNING, ON_BICYCLE, IN_VEHICLE }`, `enum class CarLinkKind { MY_CAR_BT, CAR_CLASS_BT, ANDROID_AUTO }`, `@Serializable enum class WatchState { STILL, WALKING, RUNNING, CYCLING, IN_VEHICLE, EXERCISE, ASLEEP, UNKNOWN }`.
- In `context/ContextPolicies.kt`: `data class ContextCapturePolicy(...)` exactly as spec §9 and `object ContextPolicies { val DEFAULT: Map<ContextState, ContextCapturePolicy>; fun of(s: ContextState, table: Map<ContextState, ContextCapturePolicy> = DEFAULT): ContextCapturePolicy }` with the spec §9 values (ms: REST 30 000 / 60 000 / 30 000 / 120 000, cap 2; PASSENGER and DRIVING 60 000 / 120 000 / 60 000 / 180 000, cap 1; FLIGHT 120 000 / 300 000 / 120 000 / 300 000, cap 1; UNKNOWN and WALKING cap null, zeros, `forceSettle = false`; `nudges = false` only for VEHICLE_DRIVING; `pause = false` everywhere). A missing key → the UNKNOWN row.
- In `context/WatchMotionMessage.kt`: `@Serializable data class WatchMotionMessage(val v: Int = 1, val seq: Long, val state: WatchState = WatchState.UNKNOWN, val confidence: Float = 0.8f, val changedAtMs: Long = 0, val sentAtMs: Long = 0, val source: String = "galaxy_watch")` and `object WatchMotionCodec { const val VERSION = 1; fun decode(json: String): WatchMotionMessage? }` (uses `Wire.json`; returns null when malformed or `v != 1`; confidence clamped 0..1). Note: `v` here is the **watch contract** version, independent of `PROTOCOL_VERSION`.

**Tests:**
- `ProtocolTest`: `MotionContextMessage` round-trip; old-style JSON `{"v":1,"atMs":5}` → state UNKNOWN, `validForMs == 180000`, `apply == true`, reasons empty; `"state":"TELEPORTING"` → UNKNOWN; `"reasons":["SPEED","NEW_REASON"]` decodes (strings kept); `MotionHint` encoded JSON contains `"v":1`, decodes via `Wire.decode<MotionHint>`; old `{"state":"STILL","atMs":3,"v":1}` → `stillFrac5m == -1f`; `Channels.MOTION_CONTEXT == "pc_motion_ctx"`; `PROTOCOL_VERSION == 1`.
- `ContextTypesTest`: `IntentLabel.parse("DRIVING")`, `parse("driving")` → OTHER (exact names only), `parse(null)` → OTHER.
- `ContextPoliciesTest`: every `ContextState` has a row; REST cap 2 / 120 000 stillCap; DRIVING `nudges == false`; PASSENGER `nudges == true`; WALKING equals UNKNOWN; `of(FLIGHT, emptyMap())` → the UNKNOWN row.
- `WatchMotionMessageTest`: the spec §8 sample decodes (seq 42, WALKING, 0.9); `"state":"SWIMMING"` → UNKNOWN; `"v":2` → null; `"confidence":3` → 1.0; `"{"` → null.

**Command:** `gw.sh :core:model:test`.

**Commit:** `feat(model): wave B context wire types (pc_motion_ctx, MotionHint v/stillFrac5m), evidence, intent labels, watch message and the capture policy table`

### Task 2: ContextFusion engine (core:model, pure)
**Spec:** §6 (all of it). Depends on Task 1.

**Files:** create `core/model/src/main/kotlin/com/rokid/context/core/model/context/ContextFusion.kt`; test `context/ContextFusionTest.kt`.

**Implement:**
- `data class FusionParams(` `hintWalkTtlMs = 90_000`, `arTtlMs = 1_800_000`, `stepsTtlMs = 90_000`, `stepsWalkPerMin = 60`, `watchTtlMs = 1_800_000`, `fastKmh = 25f`, `fastTtlMs = 120_000`, `vehicleHoldMs = 180_000`, `imageMinConf = 0.7f`, `imageNewestMaxAgeMs = 300_000`, `imagePairMaxGapMs = 180_000`, `airplaneMinMs = 120_000`, `flightKmh = 150f`, `flightAnyKmh = 350f`, `flightWindowMs = 600_000`, `flightImageConf = 0.6f`, `hintStillTtlMs = 120_000`, `stillFrac = 0.85f`, `stillFracWithImage = 0.70f`, `intentWindowMs = 600_000`, `priorAa = 0.85`, `priorMyCar = 0.75`, `priorCarBt = 0.50`, `priorNone = 0.15`, `confLo = 0.55`, `confHi = 0.95`, `driverHi = 0.6`, `driverLo = 0.4`, `enterVehicleMs = 30_000`, `switchDriverMs = 60_000`, `enterFlightMs = 120_000`, `enterRestMs = 120_000`, `enterUnknownMs = 60_000)` (Long/Float/Double as the values suggest).
- `data class ContextDecision(val state: ContextState, val confidence: Float, val sinceMs: Long, val reasons: Set<ContextReason>, val pDriver: Double?, val candidate: ContextState, val vehicleSuspected: Boolean)` — `vehicleSuspected` = vehicleDet or vehicleImage or airplane on (used by the speed adapter and the intent sampler).
- `class ContextFusion(private val p: FusionParams = FusionParams())`, not thread-safe (the hub calls it on one scope):
  - `fun onEvidence(e: Evidence)`: keeps the latest `GlassesMotion`; per `PhoneActivity` the current activity (an ENTER sets it with its time; an EXIT of the current one clears it); the latest `Steps`; the latest `Speed`s within `flightWindowMs` (a short list); per `CarLinkKind` connected/disconnected (a disconnect of a kind clears `btContradicted` for it); `Airplane` on-since; the latest `WatchMotion`; the newest 3 `Intent`s (sorted by `atMs`).
  - `fun evaluate(nowMs: Long): ContextDecision`: computes the derived facts of spec §6.1 exactly, the candidate of §6.2, `pDriver` of §6.3 (`logit(x) = ln(x / (1 − x))`, `sigmoid`), commits with the §6.4 dwell table (track `candidate` and `candidateSinceMs`; commit when `now − candidateSinceMs ≥ dwell(from, to)`, where to WALKING is 0, VEHICLE_* from a non-vehicle state `enterVehicleMs`, DRIVING ↔ PASSENGER `switchDriverMs`, FLIGHT `enterFlightMs`, REST `enterRestMs`, UNKNOWN `enterUnknownMs`; leaving WALKING for anything uses `enterUnknownMs` at least), output confidence as §6.4, reasons = the `ContextReason`s whose facts were true.
  - The band rule: while committed in a vehicle sub-state, a candidate inside `driverLo..driverHi` keeps the committed sub-state; a new vehicle episode in the band → DRIVING when `pDriver ≥ 0.5`, else PASSENGER.

**Tests** (`ContextFusionTest`, a fake clock starting at 1 000 000; helper `at(ms) { … }`):
- `emptyIsUnknown`.
- `glassesWalkIsImmediate`: hint WALKING → WALKING at once, reason GLASSES_WALK.
- `walkingVetoesCarBt`: car-class BT connected 5 min + hint WALKING → WALKING.
- `fastSpeedLiftsTheVeto`: AR IN_VEHICLE + Speed 60 km/h (fresh) + hint WALKING → after 30 s VEHICLE_* (not WALKING).
- `stepsAloneAreWalking` (70/min) and `oldStepsExpire` (91 s old → not walking).
- `androidAutoIsDrivingAfter30s`: AA connected → UNKNOWN at +29 s, VEHICLE_DRIVING at +30 s, `pDriver ≈ 0.85`.
- The **override table** of spec §6.3, one test per row, asserting `pDriver` (± 0.01) and the committed state after the dwells: AA; AA + 1 PASS 0.8 (stays DRIVING); AA + 2 PASS 0.8 (PASSENGER after 60 s); my car + PASS 0.9; my car + PASS 0.8 (stays DRIVING); car-class + PASS 0.8; car-class alone (DRIVING); no link + AR IN_VEHICLE (PASSENGER); no link + 1 DRIVING 0.8 (stays PASSENGER); no link + 2 DRIVING 0.8 (DRIVING); AA + PASS 0.6 (DRIVING).
- `btContradictedByTwoIndoorImages`: car BT only + 2 × COMPUTER_WORK 0.8 within 3 min → not vehicle; after a BT disconnect and reconnect → vehicle again.
- `imageOnlyVehicle`: 2 × VEHICLE_PASSENGER 0.8, 2 min apart, no deterministic signal → PASSENGER; one alone → not vehicle.
- `vehicleHoldBridgesAStop`: AR IN_VEHICLE then EXIT; still VEHICLE at +179 s, UNKNOWN after the hold plus `enterUnknownMs`.
- `flightNeedsAirplaneAndSpeedOrImage`: airplane on 3 min alone → not FLIGHT; + Speed 800 → FLIGHT after 120 s; airplane + FLIGHT_CABIN 0.7 → FLIGHT; Speed 400 without airplane → FLIGHT.
- `restFromStillGlasses`: hint stillFrac5m 0.9 → REST after 120 s; 0.75 → no REST; 0.75 + SITTING_IDLE 0.8 → REST; WORKOUT 0.8 newest → no REST; AR WALKING → WALKING, not REST.
- `walkingExitNeeds60s`: WALKING, then hint STILL → WALKING until +59 s, then the next state.
- `staleHintIgnored`: hint WALKING 91 s old → not walking.
- `vehicleSuspectedFlag`: airplane on → true; nothing → false.

**Command:** `gw.sh :core:model:test --tests '*ContextFusionTest'`, then `gw.sh :core:model:test`.

**Commit:** `feat(model): ContextFusion — walking veto, flight, vehicle with driver/passenger log-odds and hysteresis, rest`

### Task 3: Phone activity, step and airplane sources; new dependencies (phone)
**Spec:** §5 (rows Activity, Phone IMU, Airplane). Depends on Task 1. [AMENDED] Deferred; now runs **after Task 6** and plugs into its registry: `EvidenceSink` already exists (Task 6 creates it — do not create it here); each of `ActivitySource`, `StepSource`, `AirplaneSource` gets a `ContextSource` wrapper (ids `phone_activity`, `phone_steps`, `phone_airplane`) whose `available()` = Play services present (activity) / sensor present (steps) / true (airplane) and whose `start()/stop()` call the shell; `HubGraph` adds `registry.register(…)` for the three. No `ContextHub` code changes. Until this task lands: no phone walking, no IN_VEHICLE, no steps, no airplane evidence.

**Files:**
- modify `phone/build.gradle.kts` (both dependencies of the Global Constraints), `phone/src/main/AndroidManifest.xml` (a non-exported receiver ~~`.context.ActivityTransitionReceiver`~~ `com.rokid.context.phone.context.ActivityTransitionReceiver` [AMENDED r2-3])
- create `phone/src/main/java/com/rokid/context/phone/context/ActivitySource.kt`, `ActivityTransitionReceiver.kt`, `StepSource.kt`, `AirplaneSource.kt`, `EvidenceSink.kt`
- tests: `phone/src/test/java/com/rokid/context/phone/context/ActivitySourceTest.kt`, `StepSourceTest.kt`, `AirplaneSourceTest.kt`

**Implement:**
- `fun interface EvidenceSink { fun accept(e: Evidence) }`.
- `ActivitySource(sink, clock)`: pure `onTransition(activityType: Int, transitionType: Int)` maps Play services `DetectedActivity.STILL/WALKING/RUNNING/ON_BICYCLE/IN_VEHICLE` (other types ignored, counted `ignored`) and `ActivityTransition.ACTIVITY_TRANSITION_ENTER/EXIT` to `Evidence.ActivityTransition(clock(), …)`; counter `events`. Shell `start(context): Boolean` (false and no request when `ACTIVITY_RECOGNITION` is not granted) calls `ActivityRecognition.getClient(ctx).requestActivityTransitionUpdates(request, pi)` with ENTER+EXIT for the five types and a `PendingIntent.getBroadcast(…, FLAG_UPDATE_CURRENT or FLAG_MUTABLE)` to `ActivityTransitionReceiver`; `stop()` removes the updates. The receiver extracts `ActivityTransitionResult` and forwards each event to the process-wide instance (`ContextSources.activity`, a nullable `@Volatile` holder in `ActivitySource.Companion`; no-op when null).
- `StepSource(sink, clock)`: pure `onSteps(count: Int, atMs: Long)` accumulates into 60 s buckets; when a bucket closes (first event after its end, or `flush(nowMs)`), emits `Steps(bucketEnd, stepsPerMin)`. Shell registers `TYPE_STEP_DETECTOR` with `samplingPeriodUs = SENSOR_DELAY_NORMAL` and `maxReportLatencyUs = 30_000_000`; absent sensor → `start` returns false.
- `AirplaneSource(sink, clock)`: pure `onAirplane(on: Boolean)` emits only on change; shell reads `Settings.Global.AIRPLANE_MODE_ON` at start and listens to `Intent.ACTION_AIRPLANE_MODE_CHANGED`.
- Every source exposes `val events: Int` (read by the stats in Task 6) and never logs payloads.

**Tests:** `ActivitySourceTest`: (IN_VEHICLE, ENTER) → `ActivityTransition(IN_VEHICLE, enter = true)`; (TILTING, ENTER) → nothing, `ignored == 1`. `StepSourceTest`: 70 steps in the first 60 s, one in the next bucket → `Steps(stepsPerMin = 70)`; no steps → nothing; `flush` closes the bucket. `AirplaneSourceTest`: on, on, off → two evidences.

**Command:** `gw.sh :phone:testDebugUnitTest --tests '*ActivitySourceTest' --tests '*StepSourceTest' --tests '*AirplaneSourceTest'`, then `gw.sh :phone:testDebugUnitTest`.

**Commit:** `feat(phone): activity-transition, step-detector and airplane-mode context sources (Play services location, car app deps)`

### Task 4: Speed source on demand (phone)
**Spec:** §5 (Speed row and the speed paragraph), §11. Depends on Task 3. [AMENDED] Deferred; after Task 6. Registers `SpeedSource` as `ContextSource` id `phone_speed` and as `registry.speed` (the `SpeedControl` Task 6 calls with `setWanted`). This task (not Task 6) also adds to `HubGraph.build` the line `ContextHubService.wantLocation = { ctxHub.speedWanted }` (Task 6 only exposes `ContextHub.speedWanted`). Until this task lands: `fast` is never true, no SPEED reason, no location use, no `location` service type.

**Files:**
- modify `phone/src/main/AndroidManifest.xml` (`ACCESS_FINE_LOCATION`, `FOREGROUND_SERVICE_LOCATION`; `ContextHubService` `foregroundServiceType="connectedDevice|microphone|location"`), `phone/src/main/java/com/rokid/context/phone/hub/ContextHubService.kt` (add `FOREGROUND_SERVICE_TYPE_LOCATION` to the type mask only when `ContextHubService.wantLocation` returns true; fall back as the mic type does if refused)
- create `phone/src/main/java/com/rokid/context/phone/context/SpeedSource.kt`
- test `SpeedSourceTest.kt`

**Implement:**
- `SpeedSource(sink, clock)`: pure `onFix(timeMs: Long, lat: Double, lon: Double, accuracyM: Float, speedMps: Float?)`: when `speedMps != null` → `Speed(clock(), speedMps * 3.6f, precise = true)`; else, with a previous fix, `dt = timeMs − prev.timeMs`, `d` = haversine metres; when `dt ≥ 30_000 && d > 3 * (acc + prev.acc)` → `Speed(clock(), (d / (dt / 1000.0) * 3.6).toFloat(), precise = false)`. Keeps only the last fix (private fields), never exposes it. Counters `fixes`, `onMs` (accumulated while requested).
- `fun setWanted(wanted: Boolean)`: the shell requests `FusedLocationProviderClient.requestLocationUpdates(LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 30_000).setMinUpdateIntervalMillis(15_000).build(), callback, Looper.getMainLooper())` when wanted and permitted (FINE or COARSE), removes updates when not. The shell passes `location.speed` only when `location.hasSpeed()`. `setWanted(false)` also drops the last fix.
- `ContextHubService.wantLocation: () -> Boolean = { false }` (companion `@Volatile var`, set by Task 6).

**Tests:** speed 10 m/s → 36 km/h precise; two approximate fixes 2 km accuracy, 10 km apart, 5 min → derived ≈ 120 km/h; the same fixes 3 km apart → nothing (under 3 × accuracy); `dt` 20 s → nothing; `setWanted(false)` then a fix without speed → no derived speed (previous fix dropped); `onMs` grows only while wanted (fake clock).

**Command:** `gw.sh :phone:testDebugUnitTest --tests '*SpeedSourceTest'`, then `gw.sh :phone:testDebugUnitTest`.

**Commit:** `feat(phone): on-demand speed source (balanced power, speed only, fix never leaves the adapter)`

### Task 5: Car Bluetooth and Android Auto source (phone)
**Spec:** §5 (Car row). Depends on Task 4. [AMENDED] Deferred; after Task 6. Registers `CarSource` as `ContextSource` id `phone_car` (`available()` = true; the BT part degrades without `BLUETOOTH_CONNECT` as below) and passes `myCars = { settings.myCars }`. Until this task lands: no car link, so no prior other than "none" (0.15) and no VEHICLE_DRIVING from a car.

**Files:** create `phone/src/main/java/com/rokid/context/phone/context/CarSource.kt`; test `CarSourceTest.kt`.

**Implement:**
- `CarSource(sink, clock, myCars: () -> Set<String>)`: pure `onBtDevice(address: String, deviceClass: Int?, connected: Boolean)`: kind = `MY_CAR_BT` when `address in myCars()`, else `CAR_CLASS_BT` when `deviceClass == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO` (0x0420), else ignored; tracks connected addresses per kind and emits `CarLink(kind, connected)` only when a kind's any-connected flips. `onAndroidAuto(type: Int)`: `CarConnection.CONNECTION_TYPE_PROJECTION` or `NATIVE` → connected, `NOT_CONNECTED` → disconnected; emits on change. `fun currentKinds(): Set<CarLinkKind>`.
- Shell `start(context)`: requires `BLUETOOTH_CONNECT` (else BT part off, Android Auto still on); gets A2DP and HEADSET profile proxies and feeds their `connectedDevices`; registers `BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED` and `BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED` (state CONNECTED/DISCONNECTED; `EXTRA_DEVICE` → address and `bluetoothClass?.deviceClass`); `CarConnection(context).type.observeForever { onAndroidAuto(it) }` on the main thread; `stop()` undoes all (`runCatching`). Addresses stay in memory; never logged.
- `fun bondedDevicesForPicker(context): List<Pair<String, String>>` (address, display name) for Task 7's picker; display only.

**Tests:** car-class connect → `CarLink(CAR_CLASS_BT, true)`; a headset class device → nothing; address in `myCars` with any class → `MY_CAR_BT`; two car devices, one disconnects → no event, both → disconnect event; AA PROJECTION → `ANDROID_AUTO` connected, NOT_CONNECTED → disconnected; repeated same state → one event.

**Command:** `gw.sh :phone:testDebugUnitTest --tests '*CarSourceTest'`, then `gw.sh :phone:testDebugUnitTest`.

**Commit:** `feat(phone): car Bluetooth (car-audio class or "My car") and Android Auto context source`

### Task 6: Context hub on the phone: settings, fusion loop, pc_motion_ctx and pc_motion_hint (phone, glasses-link)
**Spec:** §3, §4, §5 (opt-in), §6 (loop), §10 (stats). Depends on Tasks 2–5. [AMENDED] Depends on Tasks 1 and 2 only; Tasks 3, 4, 5, 9 and 10 plug in later through the registry below without changing `ContextHub`.

**[AMENDED] Shape required so deferring 3–5 forces no rework** (this replaces the `ContextHubSources { start(id); stop(id); setSpeedWanted }` interface below; where the two disagree, this block wins):
- create `phone/src/main/java/com/rokid/context/phone/context/EvidenceSink.kt` (`fun interface EvidenceSink { fun accept(e: Evidence) }`; moved here from Task 3) and `ContextSource.kt`:
  ```kotlin
  interface ContextSource {
      val id: String                 // one of the six ids, or the internal "phone_airplane"
      fun available(): Boolean       // hardware/Play services/app present; false → never started
      fun start(): Boolean           // false = could not start (permission, missing sensor)
      fun stop()
      val events: Int
  }
  fun interface SpeedControl { fun setWanted(on: Boolean) }
  class ContextSourceRegistry {
      fun register(s: ContextSource)  // thread-safe; a later register while the hub runs triggers a reconcile
      fun get(id: String): ContextSource?
      val ids: Set<String>
      @Volatile var speed: SpeedControl? = null   // null until Task 4; setSpeedWanted is then a no-op
      val changes: StateFlow<Int>     // bumped on every register
  }
  enum class SourceStatus { OFF, ON, UNAVAILABLE, FAILED }   // UNAVAILABLE = switch on but not registered or available() false
  ```
- `ContextHub(scope, settings, registry: ContextSourceRegistry, fusion = ContextFusion(), clock, stats, log)`. **Reconcile** runs on start, on `settings.changes` and on `registry.changes`: for each of the six ids plus `phone_airplane` (wanted when `phone_activity` or `phone_speed` is on), wanted ∧ registered ∧ `available()` → `start()` once (false → FAILED), not wanted → `stop()` if running; switch on but unregistered/unavailable → UNAVAILABLE. `val status: StateFlow<Map<String, SourceStatus>>` for the UI.
- **No-sources state:** with an empty registry (the state after this task alone) or nothing running, the loop still runs, `onMotionHint` still feeds fusion (so `decision` stays meaningful for the UI), but `message == null` and nothing is sent → glasses UNKNOWN. `message` becomes non-null only while at least one source is `ON` (running), not merely switched on.
- `setSpeedWanted(...)` goes through `registry.speed?.setWanted(...)`; `val speedWanted: Boolean` is exposed; `ContextHubService.wantLocation` is **not** touched here (Task 4 wires it).
- Production `HubGraph.build` creates an empty `ContextSourceRegistry`, the hub, and nothing else; Task 10 adds the first `registry.register(…)`.
- **[AMENDED r2-5] Ownership and lifecycle:** one process-wide `ContextHub` (and settings, registry). `HubGraph.build()` only constructs it — it does **not** start sensing. `ContextHub.start()`/`stop()` are idempotent and are called from the `Hub`/`ContextHubService` lifecycle (`Hub.start()` → `ctxHub.start()`, `Hub.stop()` → `ctxHub.stop()`, which stops every running source and the loop). Tests: start twice → sources started once; stop → all sources stopped, no further sends; service stop then restart → sources restarted once; a write through a second `ContextSourceSettings` reference is observed.
- **[AMENDED r2-6] One serial executor:** evidence intake (`accept`, `onMotionHint`), settings/registry reconcile and the 10 s tick all run on one single-threaded dispatcher (`Dispatchers.Default.limitedParallelism(1)` or an actor `Channel`); callers on other threads (Binder threads of the provider, Android callbacks, the sampler) only **enqueue**. `ContextFusion` stays not thread-safe and is touched only there. Test: 8 threads × 500 `accept` calls concurrently with settings toggles → no exception, `stats` totals exact, final decision equals the serial replay.
- **[AMENDED r2-4] Revocation:** turning a source off (reconcile → `stop()`) immediately (a) marks the source revoked so late callbacks from it are dropped (each `ContextSource` gets a generation; the sink wrapper drops evidence whose generation is stale), (b) clears that source's evidence from fusion — Task 2's engine is not changed: the hub **rebuilds** the fusion (`ContextFusion(params)`) and replays the retained latest evidence of the still-enabled sources (the hub keeps a small last-per-kind buffer, the same shape the engine keeps), (c) re-evaluates at once (a rebuild restarts hysteresis from UNKNOWN — the safe side), and (d) sends the result. When nothing remains running, or `applyToCapture` turns off, or any shadow-mode change happens, the hub first sends an explicit revoke message (`state = UNKNOWN`, `apply = settings.applyToCapture`, `reasons = []`; `apply=false` for the master switch) and only then sets `message` to null. `Hub` sends that revoke even though the following value is null. Tests: DRIVING via a fake `phone_car` then switch it off → next message UNKNOWN within the same tick, then null; a late evidence from the stopped source is ignored; `applyToCapture` off with no state change → an `apply=false` message at once; on again → `apply=true` at once.
- **[AMENDED r2-8] Reconnect:** `onReady()` does not resend the cached object: it sends a fresh envelope `current.copy(atMs = now, sessionStartMs = sessionStartMs)` (`sinceMs`, state, reasons preserved). `HubTest`: ready after a new `sessionStartMs` → the resent message has `atMs ≥ sessionStartMs` and the old `sinceMs`.
- **Fusion params [AMENDED r2-9]:** the hub builds `ContextFusion(FusionParams(watchTtlMs = 1_500_000))`.
- **[AMENDED r2-minor]** The hub clamps `MotionContextMessage.confidence` to 0..1 (watch-ASLEEP REST with no glasses fraction yields −1 from the Task 2 engine). Test: `WatchMotion(ASLEEP)` alone → REST message with `confidence == 0f`.

**Files:**
- create `phone/src/main/java/com/rokid/context/phone/context/ContextSourceSettings.kt`, `ContextHub.kt`, `ContextHubStats.kt`, [AMENDED] `EvidenceSink.kt`, `ContextSource.kt` (interface, `SpeedControl`, `ContextSourceRegistry`, `SourceStatus`)
- modify `services/glasses-link/src/main/kotlin/com/rokid/context/link/GlassesEvent.kt` (add `data class MotionHintReceived(val hint: MotionHint) : GlassesEvent`), `GlassesInbound.kt` (`Channels.MOTION_HINT -> gate(Wire.decode<MotionHint>(first)) { GlassesEvent.MotionHintReceived(it) }`)
- modify `phone/src/main/java/com/rokid/context/phone/hub/Hub.kt` (new trailing param `motionContext: StateFlow<MotionContextMessage?> = MutableStateFlow(null)` and `onMotionHint: (MotionHint) -> Unit = {}`; send on change, in `onReady()`, and route the event), `HubGraph.kt` (build the `ContextHub`)
- tests: `ContextSourceSettingsTest.kt`, `ContextHubTest.kt`, `ContextHubStatsTest.kt` (phone); `HubTest.kt` (extend); `GlassesInboundTest.kt` (glasses-link; extend, or create if absent)

**Implement:**
- `ContextSourceSettings(prefs: SharedPreferences)` over the ~~`context_sources`~~ **`ctx_hub_state`** file [AMENDED r2-1; switches, `applyToCapture`, `myCars`, `liveFitCertSha256` and the watch `epoch`/`lastSeq` all live there; the whole file is excluded from `backup_rules.xml`, **both** `<cloud-backup>` and `<device-transfer>` in `data_extraction_rules.xml`, and `ConfigExport.PREFS_FILES`; `BackupRulesTest` gains `ctx_hub_state` in its excluded list; after a restore every context source is off, the safe side; the existing `context_sources` file and its tests are untouched]: `enabled(id): Boolean` (default false for the six ids), `setEnabled(id, on)`, `applyToCapture` (default true), `myCars: Set<String>` (addresses), `addMyCar`/`removeMyCar`, `liveFitCertSha256: String?`, `clearPersonal()` (clears `myCars`, the digest and the watch epoch/seq), and `val changes: StateFlow<Int>` (a counter bumped on every write). `companion fun of(context)`. [AMENDED r2-5] `of(context)` returns **one process-wide instance**; `changes` is driven by a registered `OnSharedPreferenceChangeListener` (held strongly), so a write through any reference, or a direct prefs edit, is observed.
- ~~`ContextHub(scope, settings, fusion, sources: ContextHubSources, …)`~~ [AMENDED] `ContextHub(scope, settings, registry, fusion = ContextFusion(), clock = System::currentTimeMillis, stats = ContextHubStats(clock), log: (String) -> Unit)` with the registry shape above (no production source wrappers in this task; each later task registers its own).
  - `fun accept(e: Evidence)` (the shared `EvidenceSink`): `fusion.onEvidence(e)`, `stats.count(e)`, re-evaluate.
  - `fun onMotionHint(h: MotionHint)`: → `Evidence.GlassesMotion(clock(), h.state, h.stillFrac5m)`.
  - A loop every 10 s while started: `evaluate(now)`; `registry.speed?.setWanted(settings.enabled("phone_speed") && decision.vehicleSuspected)` [AMENDED]; reconcile as above on settings and registry changes (`phone_activity` and `phone_steps` both need ACTIVITY_RECOGNITION — checked inside their `start()`, Task 3; airplane = internal id `phone_airplane`, wanted when `phone_speed` or `phone_activity` is on).
  - `val message: StateFlow<MotionContextMessage?>`: null while no source is running ([AMENDED]: status `ON`, see the no-sources state); else `MotionContextMessage(state, confidence, sinceMs, atMs = now, apply = settings.applyToCapture, reasons = reasons.map { it.name })`; updated on a committed change and every 60 s while not UNKNOWN (a new `atMs` makes the StateFlow emit).
  - `val decision: StateFlow<ContextDecision>` for the UI and the sampler.
- `ContextHubStats(clock)`: counters of spec §10 (`count(e: Evidence)` by type; `onSend()`, `onChange()`, `onIntent(ok: Boolean)`, `onIntentSkip()`, `onWatchRejected()`, `addStateTime(state, ms)`, `phoneBatteryStart/Now`), `fun line(state: ContextState): String` = the exact spec §10 format, and `fun snapshot(): Map<String, Long>` for Diagnostics. The hub logs `line()` via `log` every 10 min (tag `PcCtx` in production).
- `Hub`: `scope.launch { motionContext.collect { m -> if (m != null) guarded("motion_ctx") { sendMotionContext(m) } } }`; `sendMotionContext` sends on `Channels.MOTION_CONTEXT` with `sessionStartMs = sessionStartMs`; `onReady()` re-sends the current value as a fresh envelope (r2-8 above) [AMENDED]; `onEvent` routes `GlassesEvent.MotionHintReceived` → `onMotionHint(e.hint)`.
- `HubGraph.build`: `ContextHub` with `ContextSourceSettings.of(app)`, [AMENDED] an empty `ContextSourceRegistry` (exposed on the graph for later `register` calls), `motionContext = ctxHub.message`, `onMotionHint = ctxHub::onMotionHint`; ~~`ContextHubService.wantLocation = { ctxHub.speedWanted }`~~ (moved to Task 4); `ctxHub.start()` with the hub.

**Tests:**
- `ContextSourceSettingsTest` (Robolectric or an in-memory fake prefs): defaults off, `applyToCapture` true, `clearPersonal` keeps switches.
- [AMENDED] `ContextHubTest` additions (fake registry sources): empty registry with all six switches on → no crash, every status `UNAVAILABLE`, `message == null`, no send; a fake `watch_livefit` source registered **after** `start()` with its switch already on → started once and status `ON`; `available() == false` → never started, `UNAVAILABLE`; `start()` false → `FAILED`, `message` stays null; switching off → `stop()` called; `registry.speed == null` with `phone_speed` on and a vehicle suspected → no crash; watch-only fusion through the hub: `WatchMotion(WALKING)` → `message.state == WALKING` at once, `WatchMotion(IN_VEHICLE)` → `VEHICLE_PASSENGER` at +30 s (never DRIVING), `WatchMotion(ASLEEP)` → `REST` at +120 s, `WatchMotion(STILL)` → UNKNOWN; hints alone with no source running → `decision` updates but `message == null`.
- `ContextHubTest` (fake sources, fake clock, `TestScope`): no source on → `message == null`; turning `phone_car` on starts `"phone_car"` (a fake registered under that id); a `CarLink(ANDROID_AUTO, true)` → after 30 s `message.state == VEHICLE_DRIVING` with `"ANDROID_AUTO"` in reasons; a fake `registry.speed` got `setWanted(true)` [AMENDED] when `phone_speed` is on and a vehicle is suspected, and `false` after it ends; a `MotionHint(WALKING)` → `message.state == WALKING`; `applyToCapture = false` → `apply == false`; a re-send every 60 s with a new `atMs`.
- `ContextHubStatsTest`: the line for a known set of counts equals the exact string.
- `HubTest`: a non-null context message is sent on `pc_motion_ctx` with the session start; it is re-sent on ready; a `MotionHintReceived` event reaches `onMotionHint`.
- `GlassesInboundTest`: a `pc_motion_hint` JSON with `"v":1` → `MotionHintReceived`; without `v` → `Outdated(null)` (existing gate behaviour).

**Command:** `gw.sh :services:glasses-link:testDebugUnitTest`, then `gw.sh :phone:testDebugUnitTest --tests '*ContextSourceSettingsTest' --tests '*ContextHubTest' --tests '*ContextHubStatsTest' --tests '*HubTest'`, then `gw.sh :phone:testDebugUnitTest`.

**Commit:** `feat(phone): context hub — opt-in sources, fusion loop, pc_motion_ctx out, pc_motion_hint in, count-only stats`

### Task 7: Context sources screen, permission flows, My car, Diagnostics card (phone UI)
**Spec:** §5, §10, §11. Depends on Task 6.

**[AMENDED] Split into 7a (fast path, depends on 6 and 10) and 7b (deferred, the rest).**

**Task 7a — minimal screen (fast path).** Files: create `ContextSourcesSettings.kt` (ViewModel, UiState, Composable) with only the parts below; modify `CaptureSettings.kt` (the `context_sources_link` row exactly as in 7's first bullet, replacing `source_phone_motion` and `source_watch_livefit`; `CaptureSettingsTest` rewrite as listed), `SettingsDestinations.kt` (route `settings/capture/context`), `Diagnostics.kt` (one text line [`diag_ctx`] = `ContextHubStats.line(state)`, numbers and enum names only; no card), `SettingsDeps.kt` (Delete all also calls `clearPersonal()` — kept in 7a because the pinned digest is stored now), [AMENDED r2-1] `phone/src/main/res/xml/backup_rules.xml` and `data_extraction_rules.xml` (exclude `ctx_hub_state.xml` in both `<cloud-backup>` and `<device-transfer>`), `BackupRulesTest` (expects the exclusion). [AMENDED r2-4] Turning the watch switch or `ctx_apply` off goes through the hub's revocation path (no UI-side shortcuts).
- Screen: master switch [`ctx_apply`] "Adjust capture to context" (`applyToCapture`); the watch switch [`ctx_watch_livefit`] "Galaxy Watch (LiveFit app)" with the certificate pin exactly as in 7 (LiveFit missing → off + [`ctx_watch_missing`]; on → store SHA-256 of the current signing cert in `liveFitCertSha256`; off → clear it); a state line [`ctx_state`] = the committed state in words + the watch status (`SourceStatus`) + `watch`/`watchRej` counts. No other switches, no `PermissionPlan`, no runtime-permission flow (the watch needs none), no My car.
- Tests (`ContextSourcesSettingsTest`, 7a part): both switches render, watch off by default, `ctx_apply` on by default; LiveFit missing → `ctx_watch_missing`, switch stays off; LiveFit present (fake package lookup returning a fixed cert) → digest stored, switch on; off → digest null; `ctx_state` shows the fake decision's state. `CaptureSettingsTest` rewrite as in 7. `ConfigExport.PREFS_FILES` does not contain `ctx_hub_state` [AMENDED r2-1]; `BackupRulesTest`: `ctx_hub_state.xml` excluded in all three rule sets. Diagnostics shows the `diag_ctx` line from a fake stats.
- Command: `gw.sh :phone:testDebugUnitTest --tests '*ContextSourcesSettingsTest' --tests '*CaptureSettingsTest' --tests '*Diagnostics*'`, then `gw.sh :phone:testDebugUnitTest`.
- Commit: `feat(phone): Context sources screen (apply switch, Galaxy Watch with certificate pin, state line), Diagnostics context line`

**Task 7b — the rest (deferred, after Tasks 3–5).** Everything below not covered by 7a: the five phone/intent switches, `PermissionPlan` and the permission flows, My car, and the full "Context hub" card [`diag_ctx`] replacing 7a's single line. Each switch shows `SourceStatus.UNAVAILABLE` as "Not available on this phone" when its source is not registered. Commit: `feat(phone): Context sources — phone and photo switches, permission flows, My car, Diagnostics context-hub card`.

**Files:**
- create `phone/src/main/java/com/rokid/context/phone/ui/settings/capture/ContextSourcesSettings.kt` (ViewModel, UiState, Composable)
- modify `phone/src/main/java/com/rokid/context/phone/ui/settings/capture/CaptureSettings.kt` (replace the two disabled A3 rows), `phone/src/main/java/com/rokid/context/phone/ui/shell/SettingsDestinations.kt` (route `settings/capture/context`), `phone/src/main/java/com/rokid/context/phone/ui/settings/Diagnostics.kt` (a "Context hub" card), `phone/src/main/java/com/rokid/context/phone/ui/settings/SettingsDeps.kt` (the Delete all `wipe` also calls `ContextSourceSettings.of(app).clearPersonal()`)
- tests: `ContextSourcesSettingsTest.kt`, `CaptureSettingsTest.kt` (rewrite two expectations), `DiagnosticsTest` (extend if it exists, else a new `ContextHubCardTest.kt`)

**Implement:**
- Capture card "Motion sources": keep `source_glasses_imu`; **replace** `source_phone_motion` and `source_watch_livefit` (intentional change) with a row tag `context_sources_link`: "Phone, car, watch and photo context" → navigates to the new route. Subtitle = the current committed state in words (Unknown / Resting / Walking / Passenger / Driving / Flying) from `ContextHub.decision`.
- `ContextSourcesSettings` screen (tags in brackets): a switch "Adjust capture to context" [`ctx_apply`]; switches [`ctx_phone_activity`] "Phone activity (walking, in a vehicle)", [`ctx_phone_steps`] "Phone step counter", [`ctx_phone_speed`] "Speed, only while in a vehicle (location stays on the phone)", [`ctx_phone_car`] "Car Bluetooth and Android Auto", [`ctx_watch_livefit`] "Galaxy Watch (LiveFit app)", [`ctx_image_intent`] "Activity from photos (one AI call every 5 min, 1 a minute in a vehicle)"; a "My car" section [`ctx_my_cars`] listing bonded devices from `CarSource.bondedDevicesForPicker` with checkboxes (names shown only here), visible when `phone_car` is on; a status line [`ctx_state`].
- Turning a switch on requests its permission first (`ActivityResultContracts.RequestMultiplePermissions`): activity and steps → `ACTIVITY_RECOGNITION`; speed → `ACCESS_FINE_LOCATION` + `ACCESS_COARSE_LOCATION` (granted coarse only is accepted; the note [`ctx_speed_note`] says "Approximate location: only long fast trips are detected"); car → `BLUETOOTH_CONNECT`. Denied → the switch stays off and a note [`ctx_denied_<id>`] "Permission needed. Turn it on again to ask." The watch switch pins the LiveFit certificate: when `com.livear.fit` is not installed → stays off with note [`ctx_watch_missing`] "Install LiveFit on this phone first."; else store the SHA-256 of its current signing certificate (`PackageManager.getPackageInfo(…, GET_SIGNING_CERTIFICATES)`) in `liveFitCertSha256`; turning it off clears the digest. The permission logic lives in a pure `PermissionPlan.forSource(id): List<String>` (tested).
- Diagnostics "Context hub" card [`diag_ctx`]: state, `sends`, `changes`, `ar`, `steps`, `locFixes`, `locOnS`, `bt`, `aa`, `air`, `hints`, `watch`, `watchRej`, `intent`, `intentFail`, `intentSkip`, phone battery start→now, seconds per state. Numbers only.
- Settings export: ~~`context_sources` must not be added~~ [AMENDED r2-1] `ctx_hub_state` is never in `ConfigExport.PREFS_FILES` and is backup-excluded (done in 7a).

**Tests:** `ContextSourcesSettingsTest`: all switches off by default; tapping `ctx_phone_car` asks for `BLUETOOTH_CONNECT` (fake permission launcher) and, granted, enables it; denied → off + `ctx_denied_phone_car`; `ctx_my_cars` hidden while car is off; the watch switch with LiveFit missing → `ctx_watch_missing`; `PermissionPlan` table. `CaptureSettingsTest`: `context_sources_link` shown, `source_phone_motion` and `source_watch_livefit` gone (rewrite), `source_glasses_imu` still disabled. Context hub card shows the counts from a fake snapshot. `ConfigExport.PREFS_FILES` does not contain `ctx_hub_state` [AMENDED r2-1].

**Command:** `gw.sh :phone:testDebugUnitTest --tests '*ContextSourcesSettingsTest' --tests '*CaptureSettingsTest' --tests '*ContextHubCard*' --tests '*Diagnostics*'`, then `gw.sh :phone:testDebugUnitTest`.

**Commit:** `feat(phone): Context sources settings (opt-in, permissions, My car), Diagnostics context-hub card`

### Task 8: INTENT_CLASSIFY prompt and Room v5 intent columns (ai, memory)
**Spec:** §7.1, §7.2. Depends on Task 1. Independent of Tasks 2–7 (may start right after Task 1 if Gradle is free; commit after Task 6 lands to keep the stream order simple). [AMENDED] Deferred; starts only after `git merge int/2026-10-21` (branch note) confirms the DB is still at v4. Until Tasks 8/9 land: no image votes, so no driver/passenger override, no btContradicted, no image-only vehicle, no FLIGHT_CABIN, no image-assisted REST.

**Files:**
- `services/ai/…/ai/contract/PromptKey.kt` (append `INTENT_CLASSIFY(Role.VISION_EXTRACT, "Activity from a photo")` **after** `CLUSTER_SUMMARY`), and every registration place commit `b2fbb0e` touched for `CLUSTER_SUMMARY` (seed text in `prompt/PromptSeeds.kt`, schema in `ai/contract/DefaultSchemas.kt`, contract/output in `ai/contract/RoleContracts.kt` and `ContractOutputs.kt`, the default call class in `ai/run/RoleRunner.kt` → `CallClass.EXTRACT`); new output `data class IntentOutput(val label: IntentLabel, val confidence: Float) : …` per the contract pattern there.
- `services/memory/…/memory/db/FrameEntities.kt` (`FrameMetaEntity` gains trailing `@ColumnInfo(defaultValue = "NULL") val intentLabel: String? = null`, `val intentConf: Float? = null`, `val intentAt: Long? = null`), `FrameDaos.kt` (`FrameMetaDao.setIntent(frameId: String, label: String, conf: Float, at: Long): Int`; `newestUnlabelled(sinceMs: Long): FrameMetaEntity?` = newest row with `createdAt >= sinceMs AND intentLabel IS NULL`), `ContextDatabase.kt` (`version = 5`, `MIGRATION_4_5` = three `ALTER TABLE frame_meta ADD COLUMN` statements matching Room's generated schema), `DatabaseFactory.kt` (both builders), `services/memory/schemas/com.rokid.context.memory.db.ContextDatabase/5.json` (exported, committed)
- tests: `services/ai/src/test/.../IntentContractTest.kt`; `services/memory/src/test/java/com/rokid/context/memory/db/Migration4To5Test.kt`; extend `DatabaseFactoryUpgradeTest.kt` (v1 → v5 chain); extend `FrameMetaDao` tests (find the existing `FrameMeta*Test`)

**Implement:** the seed prompt text exactly as spec §7.1; schema `{"type":"object","properties":{"label":{"type":"string","enum":[the 8 names]},"confidence":{"type":"number"}},"required":["label","confidence"]}`; the parser maps through `IntentLabel.parse` and clamps confidence; an output without `label` is a contract failure. The prompt is editable in the existing prompt editor like every key.

**Tests:** `IntentContractTest`: `{"label":"DRIVING","confidence":0.8}` → `IntentOutput(DRIVING, 0.8)`; `"label":"FLYING"` → OTHER; `"confidence":1.7` → 1.0; missing label → failure; `PromptKey.of(Role.VISION_EXTRACT) == VISION_EXTRACT` still; the default call class of INTENT_CLASSIFY is EXTRACT. `Migration4To5Test`: v4 rows survive (one per table, the wave-2 v4 fixture pattern), the three columns exist and are null, no other table changed (compare 4.json and 5.json columns), `runMigrationsAndValidate(name, 5, true, MIGRATION_4_5)`; DAO `setIntent` then read back; `newestUnlabelled` skips labelled rows.

**Command:** `gw.sh :services:ai:testDebugUnitTest --tests '*IntentContractTest'`, `gw.sh :services:memory:testDebugUnitTest`, then `gw.sh :services:ai:testDebugUnitTest`.

**Commit:** `feat(ai,db): INTENT_CLASSIFY prompt and contract; Room v5 additive frame_meta intent columns`

### Task 9: Intent sampler and its feed into fusion (orchestration, phone)
**Spec:** §7.3. Depends on Tasks 6 and 8.

**Files:**
- create `services/orchestration/src/main/java/com/rokid/context/work/frames/intent/IntentSampler.kt`; test `IntentSamplerTest.kt`
- modify `services/orchestration/.../work/frames/KeyframeGraph.kt` (`signal()` also pokes the sampler when one is installed: `@Volatile var intentSampler: IntentSampler? = null`)
- modify `phone/src/main/java/com/rokid/context/phone/context/ContextHub.kt` (install/uninstall the sampler with the `image_intent` switch; feed results as `Evidence.Intent`; stats) — [AMENDED] done as a `ContextSource` id `image_intent` registered in `HubGraph` (`start()` installs `KeyframeGraph.intentSampler`, `stop()` sets it null); `ContextHub` itself changes only if the sampler needs `decision` (pass `ctxHub.decision` into the source's constructor instead)

**Implement:**
- `IntentSampler(scope, dao: FrameMetaDao-like port, classify: suspend (frameId: String) -> IntentOutput?, clock, suspected: () -> Boolean, onResult: (frameId: String, frameTs: Long, out: IntentOutput) -> Unit, enabled: () -> Boolean, stats: (ok: Boolean?) -> Unit)` where `stats(null)` = a skip:
  - `fun poke()`: when not enabled or a call is in flight → return (skip counted only when enabled). Pick `newestUnlabelled(clock() − 120_000)`; none → skip. `since = clock() − lastCallAt`; call when `(suspected() || lastWasVehicleOrCabin) && since ≥ 60_000` or `since ≥ 300_000`; else skip.
  - A call: `lastCallAt = clock()` before it; `classify(frameId)`; null/exception → `stats(false)`; else `dao.setIntent(...)`, `stats(true)`, and `onResult` when the frame's `createdAt ≥ clock() − 600_000`.
  - Production `classify` = load the photo with the existing `FramePhotos` loader (one photo), `RoleRunner.run(RoleCall(PromptKey.INTENT_CLASSIFY, vars, images = listOf(photo.image), callClass = CallClass.EXTRACT))`, map to `IntentOutput`.
- `ContextHub`: on `image_intent` on → `KeyframeGraph.intentSampler = IntentSampler(…, suspected = { decision.value.vehicleSuspected || decision.value.state in VEHICLE/FLIGHT }, onResult = { _, ts, o -> accept(Evidence.Intent(ts, o.label, o.confidence)) })`; off → null.

**Tests** (`IntentSamplerTest`, fake DAO and clock, `TestScope`): first poke with a fresh frame → one call; a poke 30 s later → skip; 301 s later → call; with `suspected = true`, 61 s later → call; after a DRIVING result, 61 s later → call (confirm probe) even with `suspected = false`; a frame 3 min old → skip (not ≤ 2 min); a classify failure → `stats(false)`, no `setIntent`; disabled → no call and no skip count; two pokes while one call is in flight → one call; a result for a frame 11 min old is stored but `onResult` is not called.

**Command:** `gw.sh :services:orchestration:testDebugUnitTest --tests '*IntentSamplerTest'`, then `gw.sh :services:orchestration:testDebugUnitTest`, then `gw.sh :phone:testDebugUnitTest`.

**Commit:** `feat(intent): IntentSampler — 1 per 5 min, 1 a minute in a vehicle, results to frame_meta and the context fusion`

### Task 10: Galaxy Watch (LiveFit) receiver behind a fake (phone)
**Spec:** §8. Depends on Tasks 1 and 6.

**Files:**
- create `phone/src/main/java/com/rokid/context/phone/context/WatchMotionIntake.kt` (pure), `WatchMotionProvider.kt` (Android `ContentProvider`); test `WatchMotionIntakeTest.kt`, `FakeLiveFit.kt` (test helper)
- modify `phone/src/main/AndroidManifest.xml`: `<provider android:name="com.rokid.context.phone.context.WatchMotionProvider" android:authorities="com.rokid.context.motionsource" android:exported="true" />` and `<queries><package android:name="com.livear.fit" /></queries>`
- ~~modify `ContextHub.kt`: the `watch_livefit` switch enables the intake~~ [AMENDED] create `WatchSource.kt`: a `ContextSource` id `watch_livefit`, `available()` = `com.livear.fit` installed, `start()` = set the process-wide holder `WatchMotionIntake.current` (`@Volatile`, read by the provider; null → `not_enabled`) and return true only when `liveFitCertSha256` is set, `stop()` = clear the holder; `HubGraph.build` calls `registry.register(WatchSource(…, sink = ctxHub::accept, onRejected = stats::onWatchRejected))`. Accepted messages → `accept(Evidence.WatchMotion(clock(), m.state, m.confidence))`; rejects → `stats.onWatchRejected()`. `ContextHub.kt` is not modified. Extra test: `WatchSource` with no pinned digest → `start() == false` (hub shows FAILED). Note: a `ContentProvider` can start the app process before the hub exists; the holder is then null → `not_enabled` (LiveFit re-sends on its next change).

**Implement:**
- `WatchMotionIntake(enabled: () -> Boolean, pinnedSha256: () -> String?, clock, sink)`: `fun accept(callerPackage: String?, certMatches: (String) -> Boolean, json: String?): Result` where `Result(ok: Boolean, reason: String?)` and the checks run in the spec §8 order with exactly the reason strings `not_enabled, bad_caller, bad_cert, too_big, rate, stale_seq, malformed, version`. Rate: an accepted message less than 1 000 ms after the previous accepted one → `rate`. `seq` ≤ last accepted → `stale_seq`. Version: decoded `v != 1` → `version` (use `WatchMotionCodec`; distinguish malformed from version by first reading `v`).
- `WatchMotionProvider.call(method, arg, extras)`: only `"motion_state"` (else null); `callingPackage`; `certMatches = { sha -> packageManager.hasSigningCertificate(pkg, hexToBytes(sha), PackageManager.CERT_INPUT_SHA256) }`; returns `bundleOf("ok" to r.ok, "reason" to r.reason)`. `query/insert/update/delete/getType` return null/0. The intake instance is the process-wide one set by `ContextHub` (null → `not_enabled`).
- **[AMENDED r2-6] Atomic intake:** `WatchMotionIntake.accept` is `@Synchronized` (check-and-advance of rate, epoch and `seq` in one critical section); the provider (Binder thread) runs it there and then only **enqueues** the accepted evidence onto the hub's serial executor. Test: 16 threads deliver the same 100 messages concurrently → exactly 100 accepted, each `seq` once, `rate` decided against a monotonic accept time.
- **[AMENDED r2-9] Delivery recovery (watch contract v1, additive):** `WatchMotionMessage` gains `val epoch: Long = 0` (core:model, a default so Task 1's tests stay green; spec §8 updated). The sender picks a random non-zero `epoch` per install/process start. Intake rule replacing the plain `seq` check: a message whose `epoch` differs from the last accepted epoch **resets** the sequence (accepted if it passes the other checks; counted `watchEpoch`); within one epoch `seq ≤ last` → `stale_seq`. The last `(epoch, seq)` is persisted in `ctx_hub_state` so a phone restart does not accept a replay. **Rate coalescing:** a message rejected only for `rate` is kept as the single pending latest state and applied when the 1 s window opens (a 1 s one-shot on the serial executor; a newer message replaces it); the reply is still `ok=false, reason="rate"` and the sender need not retry. **Refresh:** the sender re-sends the unchanged current state every 10 min (new `seq`, same state), so the hub's 25-min watch TTL never drops a live state. Tests: sender restart (new epoch, `seq` 1) → accepted; same epoch `seq` 1 after 42 → `stale_seq`; two messages 300 ms apart → second answered `rate` but its state becomes evidence 1 s after the first; a third within the window replaces the pending one; an unchanged state refreshed every 10 min for 40 min stays current (hub test); phone restart (persisted epoch/seq) then a replayed old message → `stale_seq`.
- **[AMENDED r2-3]** Manifest test: the merged manifest resolves authority `com.rokid.context.motionsource` to `com.rokid.context.phone.context.WatchMotionProvider` (Robolectric `packageManager.resolveContentProvider`).
- `FakeLiveFit` (test): builds spec §8 messages with increasing `seq` (and a fixed `epoch`) and calls `intake.accept("com.livear.fit", { true }, json)`.

**Tests:** happy path → ok, one `WatchMotion(WALKING, 0.9)`; disabled → `not_enabled`; caller `com.evil` → `bad_caller`; cert mismatch → `bad_cert`; no pinned digest → `bad_cert`; 2 049-byte payload → `too_big`; two messages 500 ms apart → second `rate`; same `seq` → `stale_seq`; `"v":2` → `version`; garbage → `malformed`; `"state":"SWIMMING"` → accepted as UNKNOWN.

**Command:** `gw.sh :phone:testDebugUnitTest --tests '*WatchMotionIntakeTest'`, then `gw.sh :phone:testDebugUnitTest`.

**Commit:** `feat(phone): LiveFit watch motion intake (ContentResolver.call, package and pinned-cert check, rate and seq guards)`

### Task 11: Glasses: ContextGate, policy applied to capture, motion hints, heartbeat (glasses)
**Spec:** §4 (hint cadence), §9, §9.1, §10 (heartbeat). Depends on Task 1 and A3 on the base.

**Files:**
- create `glasses/src/main/java/com/rokid/context/glasses/capture/session/ContextGate.kt`, `capture/motion/MotionHintSender.kt`
- modify `capture/session/BatteryBudget.kt` (`CaptureProfile.withContext(p: ContextCapturePolicy): CaptureProfile`), `capture/session/SamplingSession.kt` (new trailing param `contextCap: () -> Int? = { null }`; `capNow()` = `min(A3 value, contextCap() ?: Int.MAX_VALUE)`), `capture/CaptureService.kt` (profile lambda, `contextCap`, heartbeat fields, hint sender tick), `link/HudController.kt` (subscribe `Channels.MOTION_CONTEXT`; `@Volatile var onMotionContext: (MotionContextMessage?) -> Unit = {}`; stale check `atMs < sessionStartMs`; null on session close; `fun sendMotionHint(h: MotionHint): Boolean`), `GlassesRuntime.kt` (`val contextGate = ContextGate()`; wire `onMotionContext`), A3's `PopupGate` (new param `nudgesAllowed: Boolean = true`: a Nudge needs `interactive && nudgesAllowed`), `MainActivity.kt` (pass `ContextPolicies.of(runtime.contextApplied()).nudges`), `capture/diag/Heartbeat.kt` (trailing `ctx: String = "none"`, `ctxAgeS: Long? = null`, `ctxApplied: String = "UNKNOWN"`, `ctxVeto: Int = 0`)
- tests: new `ContextGateTest.kt`, `MotionHintSenderTest.kt`; extend `CameraPolicyTest.kt` (withContext), `SamplingSessionTest.kt`, `PopupGateTest.kt`, `HeartbeatTest.kt`, `HudControllerTest.kt`

**Implement:**
- `ContextGate` (`@Synchronized`): `onMessage(m: MotionContextMessage?, receivedAtMs: Long)` (null = reset); `fun effective(nowMs: Long, localState: MotionState, lastLocalWalkAtMs: Long?, sourcesOff: List<String>): ContextState` per spec §9.1 (local veto window 15 000 ms; ~~veto skipped when `"SPEED"` in reasons~~ [AMENDED r2-2] the veto applies whatever the reasons; counts `vetoes`); `fun raw(): ContextState?`, `fun ageMs(nowMs): Long?`, `fun takeVetoes(): Int`.
- `CaptureProfile.withContext(p)`: `copy(wake = if (p.forceSettle) CameraWake.SETTLE else wake, settledCheckMs = maxOf(settledCheckMs, p.minSettledCheckMs), quietCheckMs = maxOf(quietCheckMs, p.minQuietCheckMs), walkCheckMs = if (p.minWalkCheckMs > 0) maxOf(walkCheckMs ?: settledCheckMs, p.minWalkCheckMs) else walkCheckMs, stillCapMs = maxOf(stillCapMs, p.minStillCapMs), paused = paused || p.pause)`; the UNKNOWN row returns an equal profile.
- `CaptureService`: `val applied = runtime.contextGate.effective(now, motion.state.value, lastWalkAt, settings.motionSourcesOff)`; `profile = { CaptureProfile.of(…).withContext(ContextPolicies.of(applied)) }` (A3's `forRest()` still applies on top inside the session); `contextCap = { ContextPolicies.of(applied).capPerMin }`; [AMENDED r2-7] `SamplingSession` passes `hardCap = resting || contextCap() != null` to `KeyframeGate.offer(…)` and `flushWindow(…)` (A3 already has the `hardCap` parameter, today fed only by `resting`), so context caps are hard caps; forced keeps (manual "Capture now") stay exempt — stated in the KDoc of `contextCap`; [AMENDED r2-8] `HudController` keeps the last accepted `atMs` per session and drops older ones; track `lastWalkAt` from the classifier state. `runtime.contextApplied()` exposes the latest applied state for `MainActivity`.
- `MotionHintSender(clock, send: (MotionHint) -> Boolean)`: `onSecond(state: MotionState, nowMs)` keeps a 300-slot ring of STILL flags (one per second) → `stillFrac5m` (−1 until 60 slots are filled); sends when the state enters or leaves WALKING (≥ 5 000 ms since the last send) or 60 000 ms since the last send. Called from the per-second feature tick (`AndroidSensorFeed`'s tick or the service's 1 s loop — use whichever A3 Task 5 wired; do not add a new timer). Times are glasses `elapsedRealtime`; the phone stamps receipt time.
- Heartbeat appends after A3's `nudgeOff`: ` ctx=$ctx ctxAgeS=${ctxAgeS ?: "na"} ctxApplied=$ctxApplied ctxVeto=$ctxVeto`.

**Tests:**
- `ContextGateTest`: no message → UNKNOWN; VEHICLE_PASSENGER fresh → itself; 180 001 ms after receipt → UNKNOWN; `apply = false` → UNKNOWN (raw still PASSENGER); `"phone_motion"` in sourcesOff → UNKNOWN; local WALKING now → UNKNOWN and `vetoes == 1`; last local walk 14 s ago → vetoed, 16 s → not; ~~with `"SPEED"` in reasons → not vetoed~~ [AMENDED r2-2, rewritten] with `"SPEED"` (and every other reason) in reasons, local walk 5 s ago → still vetoed to UNKNOWN; WALKING message is never "vetoed"; reset (null) → UNKNOWN.
- `CameraPolicyTest`: `DEFAULT.withContext(UNKNOWN row) == DEFAULT`; `withContext(PASSENGER)` → SETTLE, 60 000 / 120 000 / 60 000 / 180 000; T2 profile with REST keeps its larger gaps.
- `SamplingSessionTest`: `contextCap = { 1 }` with A3 defaults → at most 1 keyframe per rolling minute over 3 min of changing scenes; `contextCap = { null }` → A3 behaviour unchanged.
- `PopupGateTest`: Nudge + interactive + `nudgesAllowed = false` → false; Answer + `nudgesAllowed = false` → true.
- `MotionHintSenderTest`: STILL for 120 s → sends at 60 s and 120 s, `stillFrac5m` −1 then 1.0 (60 of 60 slots); enter WALKING at 130 s → immediate send with WALKING; leave at 132 s → no send (< 5 s), at 136 s → send.
- `HeartbeatTest`: the appended keys in order; defaults ` ctx=none ctxAgeS=na ctxApplied=UNKNOWN ctxVeto=0`.
- `HudControllerTest`: a `pc_motion_ctx` with `atMs < sessionStartMs` is ignored; a valid one reaches `onMotionContext`; session close → `onMotionContext(null)`; [AMENDED r2-8] within one session a message with `atMs` older than the last accepted one is ignored, an equal-or-newer one is accepted; a reconnect (new `sessionStartMs`) followed by the phone's fresh envelope (`atMs ≥ sessionStartMs`, old `sinceMs`) is accepted.
- [AMENDED r2-7] `KeyframeGateTest` (extend) / `SamplingSessionTest`: with a context cap active, `hardCap = true` reaches `offer()`, dwell admission and `flushWindow()` → vehicle cap 1 admits at most 1 per rolling minute and REST cap 2 at most 2, even for large turns and frames differing by ≥ `BIG_CHANGE` bits; a forced keep (manual "Capture now") is still admitted over the cap.

**Command:** `gw.sh :glasses:testDebugUnitTest --tests '*ContextGateTest' --tests '*MotionHintSenderTest' --tests '*CameraPolicyTest' --tests '*SamplingSessionTest' --tests '*PopupGateTest' --tests '*HeartbeatTest' --tests '*HudControllerTest'`, then `gw.sh :glasses:testDebugUnitTest`.

[AMENDED] Task 11 is unchanged in scope and is on the fast path (the hint sender feeds glasses REST and walking to the hub; the gate, policy and heartbeat make the watch-driven state visible). **Fast-path gate** (after 6, 11, 10, 7a and `git merge int/2026-10-21`): the same command with log `gate-wave-b-fast.log`. The full final gate below runs again after the deferred tasks.

**Final gate (after Tasks 1–11):** from the repo root of the worktree, `tools/gate.sh /Users/<user>/Desktop/rokid/pca-ctx/gate-wave-b.log` exits 0 (a known-flaky `PushToTalkTest` alone may be re-run once).

**Commit:** `feat(glasses): apply pc_motion_ctx (TTL, local walking veto) to the capture profile, cap and nudges; motion hints to the phone; heartbeat ctx keys`

### Task 12: Device checklist (not code; only with the user present)
Bluetooth toggles, uninstalls and permission revokes each need an explicit OK at that time. Back up the phone app data first (the build runs the v4 → v5 migration). Read only `files/capture_diag.log` on the glasses and the phone's `PcCtx` lines (`adb logcat -s PcCtx`); never unfiltered glasses logcat (handoff §2 filter). Never print a device name, BT address or location.

0. [AMENDED] **Fast-path watch smoke (tomorrow, after the fast-path gate; needs the LiveFit forwarder):** install both APKs (`install -r`; no DB migration on the fast path). Context sources: all off → heartbeat `ctx=none ctxApplied=UNKNOWN`. Turn on the watch switch (pin taken), `ctx_apply` on. (a) Walk 5 min: `ctx=WALKING` or none, `ctxApplied` WALKING/UNKNOWN, `kf1m` as in A3. (b) Sit still 10 min: watch STILL alone stays UNKNOWN; REST may come from the glasses' `stillFrac5m ≥ 0.85` after ~2–3 min (`kf1m ≤ 2`); stand and walk → WALKING at once and `ctxVeto` counts if a REST message was still live. (c) Car or taxi as passenger only (never test while driving): watch IN_VEHICLE → `VEHICLE_PASSENGER` after ~30 s past the watch's own 30 s hold, `kf1m ≤ 1`, nudges still shown; DRIVING cannot appear. (d) Turn `ctx_apply` off → `ctxApplied=UNKNOWN` while `ctx=` still shows the state. (e) Turn the phone's Bluetooth off only with the user's OK → `ctxApplied=UNKNOWN` within 180 s. Diagnostics `diag_ctx`: `watch` rises on changes, `watchRej` stays 0. Note the minutes of each change and any 30-min stale watch state.
1. **Install** on both devices with `install -r`; check DB counts only (`frame_meta`, `frame_state`, `memory` unchanged after the migration). Settings → Capture → Context sources: all off; heartbeat `ctx=none ctxApplied=UNKNOWN`.
2. **Opt-in flow:** turn on each source, accept each permission; for speed choose "Approximate" once (note shown), then "Precise". Mark the user's car in "My car" if they have one. Diagnostics → Context hub shows counters rising only for enabled sources.
3. **Walking (must stay full rate):** a 10-min walk outdoors with the glasses on: `ctxApplied` WALKING or UNKNOWN in every heartbeat, `kf1m` as in A3, `ctxVeto` counted if a vehicle state ever arrived.
4. **Car as passenger** (taxi or someone else driving), phone on car BT for music if possible: within about 1 min `ctx=VEHICLE_*`; after the first two images `VEHICLE_PASSENGER`; `kf1m ≤ 1`; nudges still shown. Note the minute of each change and the `PcCtx` reasons.
5. **Driving** (only if the user drives; never interact with the glasses or phone while driving; the user reports afterwards): Android Auto or "My car" → `VEHICLE_DRIVING`, `nudgeOff` counted, `kf1m ≤ 1`.
6. **Rest:** sit still 10 min → REST after about 2–3 min (`stillFrac5m` ≥ 0.85), `kf1m ≤ 2`; stand up and walk → WALKING at once.
7. **Flight** (next trip): airplane mode with BT on → FLIGHT after 2 min plus a speed fix or a cabin image; `kf1m ≤ 1`. Ask the user again whether capture should stop entirely while flying.
8. **Watch:** only after LiveFit ships the forwarder (its repo): Diagnostics `watch` rises on state changes; `watchRej` stays 0.
9. **Phone sensing cost:** two 30-min phone runs with the same activity, all sources off vs all on; compare the `PcCtx` `batt=` values and `locOnS`.
10. **Write the results** into handoff §6 (a "Wave B" row) and revisit spec §12 rulings 1–10 with the user, especially the driver priors and the flight answer.
