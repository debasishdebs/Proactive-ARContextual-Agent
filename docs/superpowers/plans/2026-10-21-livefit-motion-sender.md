# LiveFit motion sender (Galaxy Watch → LiveFit phone → Proactive Context) — plan

**Goal:** the Galaxy Watch derives a coarse motion state from Health Services passive data, sends changes (plus a 10-minute refresh) to the LiveFit phone app, and the phone forwards each one to Proactive Context's `WatchMotionProvider` (wave B Task 10) through `ContentResolver.call`. Budget: about 2 hours including reviews, so this is the smallest change that works on the wrist tomorrow.

**Repo:** LiveFit, `/Users/<user>/Desktop/rokid/live-fitness-tracker` (Kotlin packages `com.debasish.livefit.*`, application ID `com.livear.fit` for phone and watch). The receiver spec is `docs/superpowers/specs/2026-10-21-wave-b-context-hub-design.md` §8 in the Proactive Context repo.

## Contract (sender side, binding)

- **Call:** `context.contentResolver.call(Uri.parse("content://com.rokid.context.motionsource"), "motion_state", null, bundleOf("json" to msg))`, always inside `runCatching` (a missing provider throws `IllegalArgumentException` or returns null). The reply is `Bundle("ok" to Boolean, "reason" to String?)`, where reason is one of `not_enabled, bad_caller, bad_cert, too_big, rate, stale_seq, malformed, version`.
- **Message (exact field order and format):**
  `{"v":1,"epoch":E,"seq":N,"state":"WALKING","confidence":0.8,"changedAtMs":T1,"sentAtMs":T2,"source":"galaxy_watch"}`
  - `epoch`: a random positive `Long`, generated once each time the phone process starts and never stored. `seq` starts at 1 for each epoch and goes up by 1 on **every** call, retries included. The receiver treats a new epoch as a new sequence, so a LiveFit restart is never stuck on `stale_seq`.
  - `state` ∈ `STILL, WALKING, RUNNING, CYCLING, IN_VEHICLE, EXERCISE, ASLEEP, UNKNOWN`. The watch never produces `IN_VEHICLE` (Health Services has no vehicle state). It produces `CYCLING` only while a biking exercise runs through Health Services.
  - `confidence` is rendered with Kotlin `Float.toString()` (`0.8`, `0.9`, `0.6`). `changedAtMs` comes from the watch clock, and the receiver ignores it. `sentAtMs` comes from the phone clock.
  - The message is at most 2,048 bytes (it is about 150). It carries no steps, heart rate, cadence or location: cadence stays on the watch.
- **Rate:** at least 1,100 ms between calls. If the reply is `rate`, or there is no reply, retry with backoff (1.1 s, then doubled each time up to 30 s, at most 6 tries), and **always send the latest state**, never an older queued one. For any other rejection (`not_enabled`, `bad_cert`, …), stop until the next change or refresh.
- **When the phone sends:** on every watch report it receives (changes, plus the watch's 10-minute refresh), when its own 10-minute refresh timer fires, when the toggle is turned on, when the phone process starts with a fresh last state, and when the watch link reconnects. It never sends a state that it last heard from the watch more than 30 minutes ago, so the receiver's 30-minute expiry works when the watch goes silent.
- **Watch → phone (internal to LiveFit):** Data Layer path `/lf/motion`, body `Wire.encode(MotionReport)`. `PROTOCOL_VERSION` **stays 4**: the path is additive, and an old phone ignores an unknown path (`WatchMessageCodec` returns null).

## Global Constraints

- **Worktree and branch:** `git -C /Users/<user>/Desktop/rokid/live-fitness-tracker worktree add -b feat/motion-to-proactive-context /Users/<user>/Desktop/rokid/lf-motion/int design/livefit-v1-v2`. This is done once, before Task 1 (the base is `design/livefit-v1-v2` at `4529735`, the current integration branch; there is no `main`). All work happens in `/Users/<user>/Desktop/rokid/lf-motion/int`. Never commit on `design/*` or any other branch. Never push. Never merge.
- **Gradle:** run from the worktree root as `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && /Users/<user>/Desktop/rokid/proactive-context-agent/.superpowers/sdd/gw.sh <args>`. Only **one** Gradle run at a time (the machine allows 2 and other work uses one). Never run `gradlew --stop`. Run the class filter first, then the whole module before committing.
- **Never open or print** `keystore.properties`, `local.properties`, `.env`, keys or certificate digests. Debug builds need none of them (the tiles fall back to OSM, and signing falls back to the debug key).
- **No adb and no device** in Tasks 1–5.
- **Commits:** one per task, using the message given, ending with a blank line and then `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Stage with `git add <paths>` only, never `-A`.
- **Tests:** `kotlin.test` + JUnit4, pure cores with fakes and a fake clock. There is no Robolectric, so no `Bundle`, `Context` or `SharedPreferences` in a tested class (Android shells stay thin and untested).
- **Logs:** tag `LiveFitMotion`. Log only enum names, reasons and counts, never a node id, digest or step count.
- **Existing tests stay green:** `watch/src/test/.../ManifestPolicyTest.kt` forbids `ACCESS_BACKGROUND_LOCATION`, `BODY_SENSORS_BACKGROUND` and `READ_HEALTH_DATA_IN_BACKGROUND`. This plan needs none of them (passive STEPS and user activity need only `ACTIVITY_RECOGNITION`, which is already declared and requested).
- **Exact names** (do not rename): `WatchPaths.MOTION = "/lf/motion"`, `MotionState`, `MotionReport`, `WatchInbound.Motion`, `MotionStateDetector`, `MotionMemory`, `PassiveMotion`, `MotionPassiveService`, `MotionReceiver`, `ContextForwarder`, `MotionRelay`, settings key `shareMotion` (default `false`), prefs files `motion` (watch) and `motion_relay` (phone).

---

### Task 1: Motion wire types and the phone codec case (core:model, services:watch-link)

**Files**
- modify `core/model/src/main/kotlin/com/debasish/livefit/model/Protocol.kt`: add `WatchPaths.MOTION`.
- create `core/model/src/main/kotlin/com/debasish/livefit/model/Motion.kt`.
- modify `services/watch-link/src/main/kotlin/com/debasish/livefit/services/watch/WatchMessageCodec.kt` and `DataLayerWatchLink.kt`.
- tests: create `core/model/src/test/kotlin/com/debasish/livefit/model/MotionWireTest.kt`, and `services/watch-link/src/test/kotlin/com/debasish/livefit/services/watch/WatchMessageCodecMotionTest.kt`.

**Implement**
- In `WatchPaths`: `/** Watch → phone: coarse motion state ([MotionReport]) on change and every 10 min; forwarded to Proactive Context when shared. */ const val MOTION = "/lf/motion"`. Do **not** change `PROTOCOL_VERSION` (it stays 4).
- `Motion.kt`:
  ```kotlin
  @Serializable enum class MotionState { STILL, WALKING, RUNNING, CYCLING, IN_VEHICLE, EXERCISE, ASLEEP, UNKNOWN }
  /** Watch → phone on [WatchPaths.MOTION]. No health values: state only. */
  @Serializable data class MotionReport(
      val protocolVersion: Int = PROTOCOL_VERSION,
      val state: MotionState = MotionState.UNKNOWN,
      val confidence: Float = 0f,
      val changedAtMs: Long = 0,
  )
  ```
- `WatchMessageCodec`: add `data class Motion(val report: MotionReport) : WatchInbound`. Add `WatchPaths.MOTION` to `jsonPaths`, and add the branch `WatchPaths.MOTION -> WatchInbound.Motion(Wire.decode(text))` **before** `else` (otherwise it would decode as `Cmd`). The version check stays as it is (a v3 motion message → `Outdated(3)`).
- `DataLayerWatchLink.onMessage`: add `is WatchInbound.Motion -> Unit // routed by the phone's WatchListener to MotionRelay (Task 5), never here`, so the `when` stays exhaustive.

**Tests (write first)**
- `MotionWireTest`: `Wire.encode(MotionReport(state = WALKING, confidence = 0.8f, changedAtMs = 5))` contains `"protocolVersion":4` and `"state":"WALKING"`, and decodes back equal. `MotionState.entries.map { it.name }` equals the 8 contract names in contract order.
- `WatchMessageCodecMotionTest`: (1) an encoded report on `MOTION` → `WatchInbound.Motion(report)`; (2) `{"protocolVersion":3,"state":"STILL"}` on `MOTION` → `Outdated(3)`; (3) `{"protocolVersion":4,"state":"SWIMMING"}` → `null` (decode fails, so the message is ignored); (4) an encoded report on `/lf/unknown` → `null`.

**Command:** `gw.sh :core:model:test --tests '*MotionWireTest'`, then `gw.sh :services:watch-link:testDebugUnitTest --tests '*WatchMessageCodecMotionTest'`, then `gw.sh :core:model:test :services:watch-link:testDebugUnitTest`.

**Commit:** `feat(model): /lf/motion path and MotionReport; phone codec decodes motion reports`

---

### Task 2: MotionStateDetector, a pure reducer (watch)

**Files:** create `watch/src/main/java/com/debasish/livefit/watch/motion/MotionStateDetector.kt`. Test: `watch/src/test/java/com/debasish/livefit/watch/motion/MotionStateDetectorTest.kt`.

**Interfaces (exact)**
```kotlin
enum class UserActivity { ASLEEP, EXERCISE, PASSIVE, UNKNOWN }
enum class ExerciseKind { RUN, WALK, BIKE, OTHER }
sealed interface MotionEvent {
    data class Activity(val activity: UserActivity, val exercise: ExerciseKind?, val changedAtMs: Long) : MotionEvent
    data class Steps(val count: Long, val startMs: Long, val endMs: Long) : MotionEvent
    data object Tick : MotionEvent
}
@Serializable data class MotionMemory(            // persisted between process deaths (Task 3)
    val activity: UserActivity = UserActivity.UNKNOWN,
    val exercise: ExerciseKind? = null,
    val cadence: Double = 0.0,                    // steps/min of the latest Steps point
    val lastStepEndMs: Long = 0,                  // 0 = never saw steps
    val candidate: MotionState? = null,
    val candidateSinceMs: Long = 0,
    val reported: MotionState = MotionState.UNKNOWN,
    val reportedChangedAtMs: Long = 0,
    val reportedConfidence: Float = 0f,
    val lastSentMs: Long = 0,
    val unsent: Boolean = false,
)
data class MotionStep(val memory: MotionMemory, val report: MotionReport?)
object MotionStateDetector {
    const val HOLD_MS = 30_000L; const val STEP_STALE_MS = 120_000L; const val REFRESH_MS = 600_000L
    const val WALK_CADENCE = 60.0; const val RUN_CADENCE = 140.0; const val MIN_SPAN_MS = 5_000L
    fun next(m: MotionMemory, e: MotionEvent, nowMs: Long): MotionStep
    fun markSent(m: MotionMemory, nowMs: Long): MotionMemory = m.copy(lastSentMs = nowMs, unsent = false)
}
```

**Rules**
1. *Apply the event:*
   - `Activity` sets `activity` and `exercise`.
   - `Steps` with `endMs - startMs < MIN_SPAN_MS` is ignored completely (return `MotionStep(m, null)`). Otherwise it sets `cadence = count * 60_000.0 / (endMs - startMs)` and `lastStepEndMs = endMs`.
   - `Tick` changes nothing.
2. *Classify* `(state, confidence)` at the evidence time `t`, where `t` = `endMs` for Steps and `nowMs` for Activity and Tick. The first rule that matches wins:
   - `ASLEEP` → `ASLEEP`, 0.9.
   - `EXERCISE` → `RUN`→`RUNNING`, `WALK`→`WALKING`, `BIKE`→`CYCLING`, all at 0.9; `OTHER` or null → `EXERCISE`, 0.8.
   - otherwise (PASSIVE or UNKNOWN):
     - `lastStepEndMs == 0` → `UNKNOWN`, 0.
     - `t - lastStepEndMs > STEP_STALE_MS` → `STILL`, 0.6.
     - `cadence ≥ RUN_CADENCE` → `RUNNING`, 0.8.
     - `cadence ≥ WALK_CADENCE` → `WALKING`, 0.8.
     - otherwise → `STILL`, 0.6.
3. *Candidate:* if the state differs from `candidate`, set `candidate = state` and `candidateSinceMs` as follows:
   - Steps → `startMs` (the point's whole span is evidence).
   - Activity → `changedAtMs` (Health Services already debounces).
   - Tick with stale-step STILL → `min(nowMs, lastStepEndMs + STEP_STALE_MS)`.
   - otherwise → `t`.
4. *Change report:* if `t - candidateSinceMs ≥ HOLD_MS` and `state != reported`, then set `reported`, `reportedChangedAtMs = candidateSinceMs`, `reportedConfidence` and `unsent = true`, and emit `MotionReport(state = state, confidence = conf, changedAtMs = candidateSinceMs)`.
5. *Refresh and retry (Tick only, when step 4 emitted nothing):* if `reported != UNKNOWN` and (`unsent` or `nowMs - lastSentMs ≥ REFRESH_MS`), emit a report of the **current** reported state with its original `reportedChangedAtMs`.
6. `next` never changes `lastSentMs`. Only `markSent` does that, after a send succeeds (Task 3).

**Tests (write first; fake times only, no clock object needed)**
- 100 steps over 60 s (`Steps(100, 0, 60_000)`) → WALKING 0.8 with `changedAtMs = 0`. The same point again at `60_000..120_000` → null (changes only).
- `Steps(150, 0, 60_000)` → RUNNING. `Steps(30, 0, 60_000)` → STILL.
- Hold: `Steps(10, 0, 6_000)` (100/min over 6 s) → null; it becomes a candidate but is not held 30 s.
- A point shorter than 5 s → memory unchanged, null.
- `Activity(ASLEEP, null, changedAtMs = 0)` at `nowMs = 60_000` → ASLEEP 0.9, `changedAtMs = 0`.
- `Activity(EXERCISE, BIKE, 0)` at 60 s → CYCLING 0.9. `RUN` → RUNNING. `OTHER` → EXERCISE 0.8.
- Exercise ends: `Activity(PASSIVE, null, …)` after EXERCISE with steps fresh at 100/min → WALKING.
- Stale steps: WALKING reported from `Steps(100, 0, 60_000)`, then `Tick` at `nowMs = 60_000 + 180_000` → STILL with `changedAtMs = 180_000` (= 60_000 + 120_000).
- Refresh: after a report and `markSent(…, 100_000)`, a `Tick` at `100_000 + 599_999` → null, and a `Tick` at `100_000 + 600_000` → the same state and the same `changedAtMs`.
- Retry: a report emitted but `markSent` not called → the next `Tick` (any time) re-emits it.
- No steps ever plus `Tick` → null (UNKNOWN is never reported first).
- `Wire.encode(memory)` → `Wire.decode<MotionMemory>` round-trips equal.

**Command:** `gw.sh :watch:testDebugUnitTest --tests '*MotionStateDetectorTest'`, then `gw.sh :watch:testDebugUnitTest`.

**Commit:** `feat(watch): MotionStateDetector — passive activity, exercise type and step cadence to a held motion state`

---

### Task 3: Passive monitoring shell, boot and tick receiver, send (watch)

**Depends on Tasks 1 and 2.**

**Files**
- create `watch/src/main/java/com/debasish/livefit/watch/motion/PassiveMotion.kt`, `MotionPassiveService.kt` and `MotionReceiver.kt`.
- modify `watch/src/main/AndroidManifest.xml`, `WatchRuntime.kt` (one line) and `watch/src/test/java/com/debasish/livefit/watch/ManifestPolicyTest.kt`.

**Implement**
- `object PassiveMotion`:
  - It has its own `scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))`, so events run one at a time and in order.
  - `@Volatile private var registered = false`.
  - `fun register(context: Context)`:
    1. If `registered`, return.
    2. If `ContextCompat.checkSelfPermission(context, ACTIVITY_RECOGNITION) != GRANTED`, log `"motion: ACTIVITY_RECOGNITION not granted"` and return. Registration is tried again on the next `WatchRuntime.init`, which runs at each process start.
    3. Otherwise launch: `val client = HealthServices.getClient(app).passiveMonitoringClient; val caps = client.getCapabilitiesAsync().await()` (kotlinx-coroutines-guava is already a dependency).
    4. `val types = setOf(DataType.STEPS).filter { it in caps.supportedDataTypesPassiveMonitoring }.toSet()`.
    5. `val config = PassiveListenerConfig.builder().setDataTypes(types).setShouldUserActivityInfoBeRequested(caps.supportedUserActivityStates.isNotEmpty()).build()`.
    6. `client.setPassiveListenerServiceAsync(MotionPassiveService::class.java, config).await()`, then set `registered = true`, then `scheduleTick(app)`.
    7. All of it runs inside `runCatching`, and a failure logs only the exception class name.
  - `fun scheduleTick(context)`: `AlarmManager.setInexactRepeating(ELAPSED_REALTIME_WAKEUP, elapsedRealtime() + 600_000, 600_000, pi)` with `pi = PendingIntent.getBroadcast(ctx, 0, Intent(ctx, MotionReceiver::class.java).setAction(MotionReceiver.ACTION_TICK), FLAG_IMMUTABLE or FLAG_UPDATE_CURRENT)`.
  - `fun onEvents(context: Context, events: List<MotionEvent>): Job = scope.launch { … }`:
    1. Load `MotionMemory` from prefs `motion` key `memory` (`Wire.decode`, falling back to `MotionMemory()`).
    2. Fold each event through `MotionStateDetector.next(m, e, System.currentTimeMillis())` and keep only the **last** non-null report (coalesce a batch).
    3. If there is a report: `WatchRuntime.init(context)`, then `runCatching { WatchRuntime.send(WatchPaths.MOTION, Wire.encode(report).toByteArray()) }`. On success, `m = markSent(m, now)`.
    4. Save the memory with `commit()` (on the IO thread).
    5. Log `"motion ${report.state} sent=$ok"`.
- `class MotionPassiveService : PassiveListenerService()`:
  - `onUserActivityInfoReceived(info)` → `PassiveMotion.onEvents(this, listOf(MotionEvent.Activity(activity(info.userActivityState), kind(info.exerciseInfo?.exerciseType), info.stateChangeTime.toEpochMilli())))`.
  - `onNewDataPointsReceived(c)` → `val boot = Instant.ofEpochMilli(System.currentTimeMillis() - SystemClock.elapsedRealtime())`; `c.getData(DataType.STEPS).sortedBy { it.startDurationFromBoot }.map { Steps(it.value, it.getStartInstant(boot).toEpochMilli(), it.getEndInstant(boot).toEpochMilli()) }` → `onEvents`.
  - `onPermissionLost()` → log only.
  - Mappings: `USER_ACTIVITY_ASLEEP→ASLEEP`, `USER_ACTIVITY_EXERCISE→EXERCISE`, `USER_ACTIVITY_PASSIVE→PASSIVE`, else `UNKNOWN`. `ExerciseType.RUNNING, RUNNING_TREADMILL → RUN`; `WALKING, HIKING → WALK`; `BIKING, MOUNTAIN_BIKING → BIKE`; any other non-null type → `OTHER`; null → null.
- `class MotionReceiver : BroadcastReceiver()`, with `const val ACTION_TICK = "com.debasish.livefit.watch.MOTION_TICK"`:
  - `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED` → `PassiveMotion.register(context)`. Health Services passive registrations do not survive a reboot.
  - `ACTION_TICK` → `val p = goAsync(); PassiveMotion.onEvents(context, listOf(MotionEvent.Tick)).invokeOnCompletion { p.finish() }`.
- `WatchRuntime.init`: after `WatchClient.start()`, add `com.debasish.livefit.watch.motion.PassiveMotion.register(app)`.
- Manifest:
  - `<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />`
  - `<service android:name=".motion.MotionPassiveService" android:exported="true" android:permission="com.google.android.wearable.healthservices.permission.PASSIVE_DATA_BINDING" />`
  - `<receiver android:name=".motion.MotionReceiver" android:exported="true"><intent-filter><action android:name="android.intent.action.BOOT_COMPLETED" /><action android:name="android.intent.action.MY_PACKAGE_REPLACED" /></intent-filter></receiver>`

  A tick sent by a third party only re-evaluates the state, which is harmless.
- `ManifestPolicyTest`: add `@Test fun passiveServiceIsBoundOnlyByHealthServices()`, a regex that `.motion.MotionPassiveService` is followed within its tag by `PASSIVE_DATA_BINDING`. The existing `noBackgroundLocationOrBackgroundHealth` must stay green.

**Tests:** the manifest test above. The shell is not unit-tested (the Global Constraints rule).

**Command:** `gw.sh :watch:testDebugUnitTest`, then `gw.sh :watch:assembleDebug`.

**Commit:** `feat(watch): passive motion monitoring — Health Services listener, boot re-registration, 10-min tick, send on /lf/motion`

---

### Task 4: ContextForwarder, pure (phone)

**Depends on Task 1. Can run before Task 3 (one Gradle run at a time).**

**Files:** create `phone/src/main/java/com/debasish/livefit/phone/motion/ContextForwarder.kt`. Test: `phone/src/test/java/com/debasish/livefit/phone/motion/ContextForwarderTest.kt`.

**Interfaces (exact)**
```kotlin
data class CallResult(val ok: Boolean, val reason: String?)
@Serializable data class LastMotion(val report: MotionReport, val receivedAtMs: Long)
class ContextForwarder(
    private val enabled: () -> Boolean,
    private val clock: () -> Long,
    private val call: (String) -> CallResult?,          // null = provider missing or threw
    private val saveLast: (LastMotion) -> Unit = {},
    loadLast: () -> LastMotion? = { null },
    val epoch: Long = kotlin.random.Random.nextLong(1, Long.MAX_VALUE),
) {
    fun onWatch(report: MotionReport)    // store latest (+saveLast), wake
    fun resendCurrent()                  // wake (toggle on, watch reconnect)
    suspend fun run(): Nothing           // the single delivery loop
    companion object {
        const val MIN_GAP_MS = 1_100L; const val MAX_BACKOFF_MS = 30_000L; const val MAX_TRIES = 6
        const val REFRESH_MS = 600_000L; const val MAX_AGE_MS = 1_800_000L
        fun encode(m: MotionReport, epoch: Long, seq: Long, sentAtMs: Long): String
    }
}
```
- `encode` is exactly `"""{"v":1,"epoch":$epoch,"seq":$seq,"state":"${m.state.name}","confidence":${m.confidence},"changedAtMs":${m.changedAtMs},"sentAtMs":$sentAtMs,"source":"galaxy_watch"}"""`.
- State:
  - `@Volatile latest: LastMotion? = loadLast()`.
  - `seq = 0L` (in memory, per epoch).
  - `lastCallMs = Long.MIN_VALUE / 2`.
  - `wake = Channel<Unit>(Channel.CONFLATED)`.
- `run()`:
  - If `latest` was loaded and is fresh, it wakes once at the start (process start counts as a reconnect).
  - Then it loops: `withTimeoutOrNull(REFRESH_MS) { wake.receive() }` (a timeout is the 10-minute refresh), then `deliver()`.
- `deliver()` makes up to `MAX_TRIES` tries, starting with `backoff = MIN_GAP_MS`. Each try:
  1. Return if `!enabled()`, or `latest == null`, or `clock() - latest.receivedAtMs > MAX_AGE_MS`.
  2. Wait `lastCallMs + MIN_GAP_MS - clock()` if that is positive.
  3. Read `latest` **again** (coalesce to the newest), then `json = encode(it.report, epoch, ++seq, clock())`, `lastCallMs = clock()` and `r = call(json)`.
  4. If `r?.ok == true`, return. If `r != null && r.reason != "rate"`, return (no hammering on `not_enabled`, `bad_cert` and the like).
  5. Otherwise `delay(backoff)` and `backoff = min(backoff * 2, MAX_BACKOFF_MS)`.

**Tests (write first; `runTest`, `clock = { testScheduler.currentTime }`, `backgroundScope.launch { f.run() }`, fake `call` records the JSON strings and returns scripted results)**
- Disabled → no call after `onWatch` plus `advanceUntilIdle`.
- Enabled, `epoch = 7`, `onWatch(MotionReport(state = WALKING, confidence = 0.8f, changedAtMs = 5))` → exactly `{"v":1,"epoch":7,"seq":1,"state":"WALKING","confidence":0.8,"changedAtMs":5,"sentAtMs":0,"source":"galaxy_watch"}`, and the string is ≤ 2,048 bytes.
- Three reports 2 s apart → seq 1, 2, 3 with the same epoch. A new instance with `epoch = 8` → seq 1 again.
- Coalesce: WALKING then RUNNING 100 ms later → calls are ≥ 1,100 ms apart, and the **last** call carries RUNNING.
- Rate: the script returns `(false, "rate")`, then ok. During the backoff `onWatch(RUNNING)` → the retry carries RUNNING with a higher seq.
- No reply (`null`) six times → exactly 6 calls with gaps that grow (1.1, 2.2, 4.4, … capped at 30 s), then silence. The next `onWatch` delivers again.
- `(false, "bad_cert")` → exactly one call, no retry.
- Refresh: after an ok, `advanceTimeBy(600_000)` → one more call with the same state and seq + 1.
- Max age: no `onWatch` for 31 min → the refresh makes no call.
- `loadLast` fresh (`receivedAtMs = 0` at time 0) → one call at start. `loadLast` 31 min old (start the virtual clock with `advanceTimeBy` before constructing) → none.
- `resendCurrent()` → one call.
- The saved `LastMotion` round-trips through `Wire`.

**Command:** `gw.sh :phone:testDebugUnitTest --tests '*ContextForwarderTest'`, then `gw.sh :phone:testDebugUnitTest`.

**Commit:** `feat(phone): ContextForwarder — contract JSON with epoch/seq, coalescing retry, 10-min refresh, 30-min age cap`

---

### Task 5: Phone shell — relay, listener routing without the hub, toggle, package visibility (phone)

**Depends on Tasks 1 and 4.**

**Files**
- create `phone/src/main/java/com/debasish/livefit/phone/motion/MotionRelay.kt`.
- modify `WatchListener.kt`, `ServiceGraph.kt`, `SettingsStore.kt`, `ui/settings/SettingsScreen.kt` and `phone/src/main/AndroidManifest.xml`.
- modify test `phone/src/test/java/com/debasish/livefit/phone/ManifestPolicyTest.kt`.

**Implement**
- `class MotionRelay private constructor(app: Context)`, with `companion { @Volatile private var i: MotionRelay? = null; fun get(c: Context): MotionRelay }` (double-checked, `applicationContext`):
  - `val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)`.
  - `relayPrefs = app.getSharedPreferences("motion_relay", 0)`.
  - `forwarder = ContextForwarder(...)` with these arguments:
    - `enabled = { app.getSharedPreferences("settings", 0).getString("shareMotion", null)?.toBooleanStrictOrNull() ?: false }`
    - `clock = System::currentTimeMillis`
    - `call = { json -> runCatching { app.contentResolver.call(URI, "motion_state", null, bundleOf("json" to json)) }.getOrNull()?.let { CallResult(it.getBoolean("ok"), it.getString("reason")) } }`
    - `saveLast = { relayPrefs.edit().putString("last", Wire.encode(it)).apply() }`
    - `loadLast = { relayPrefs.getString("last", null)?.let { s -> runCatching { Wire.decode<LastMotion>(s) }.getOrNull() } }`
  - In `init`: `scope.launch { forwarder.run() }`.
  - `fun onWatch(r: MotionReport) = forwarder.onWatch(r)`, `fun resendCurrent() = forwarder.resendCurrent()`.
  - Log each call result (`ok` or `reason` only) under `LiveFitMotion`. Wrap `call` so it logs the result.
  - `URI = Uri.parse("content://com.rokid.context.motionsource")`.
- `WatchListener.onMessageReceived`: the **first** line is `if (event.path == WatchPaths.MOTION) { onMotion(event); return }`. This means no `LiveFitHubService.start` and no `(application as LiveFitApp).services`, so the graph is not built.
  - `onMotion`: `val relay = MotionRelay.get(applicationContext)`, `val gate = gate(applicationContext)`, capture `path`, `data` and `sourceNodeId`, then:
    ```kotlin
    relay.scope.launch {
        if (!gate.allows(source)) return@launch
        (WatchMessageCodec.decode(path, data) as? WatchInbound.Motion)?.let { relay.onWatch(it.report) }
    }
    ```
  - Note for the reviewer: `LiveFitApp.onCreate` already calls `LiveFitHubService.ensureRunning` at any process start (existing, conditional behavior). This plan does not change it.
- `ServiceGraph.start()`, next to the other `watch.status` collectors:
  ```kotlin
  scope.launch {
      watch.status.map { it.link }.distinctUntilChanged().collect {
          if (it == LinkState.Connected) MotionRelay.get(app).resendCurrent()
      }
  }
  ```
- `SettingsStore`: `private val _shareMotion = pref("shareMotion", false) { it.toBooleanStrictOrNull() }`, `val shareMotion: StateFlow<Boolean>`, and `fun setShareMotion(v: Boolean)`, which writes the string like `setGpsOutdoors`.
- `SettingsScreen`: next to the "Use GPS outdoors" row, add `ChipRow(Icons.Rounded.DirectionsWalk, LiveFitColors.ChipMint, "Share motion state with Proactive Context", "Watch motion only (still, walking, running…) · no health data", onClick = { val on = !share; services.settings.setShareMotion(on); if (on) MotionRelay.get(context).resendCurrent() }, trailing = { Switch(share, null) })`.
  - Use `LocalContext.current` for `context`.
  - `material-icons-extended` is already a dependency.
- Manifest `<queries>`: add `<package android:name="com.rokid.context" />` and `<provider android:authorities="com.rokid.context.motionsource" />` (Android 11+ package visibility; without them `call` cannot resolve).
- Phone `ManifestPolicyTest`: add `@Test fun proactiveContextProviderIsQueryable()`, which checks that `com.rokid.context.motionsource` is in the manifest.

**Command:** `gw.sh :phone:testDebugUnitTest`, then the full README build: `gw.sh test :phone:assembleDebug :watch:assembleDebug :glasses:assembleDebug`.

**Commit:** `feat(phone): relay watch motion to Proactive Context (opt-in toggle, no hub start, queries entry, reconnect re-send)`

---

### Task 6: Device checklist (not code; only with the user present)

**Prerequisites:** Tasks 1–5 committed. Proactive Context wave B Task 10 is built with the **epoch amendment** (a new `epoch` resets the `seq` guard; without it, the 2nd LiveFit process start reads `stale_seq` until our source is toggled).

1. **Signing (read first):** Data Layer messages flow only between a phone app and a watch app with the **same signing key**. Debug builds are signed with this Mac's debug key. If the phone or watch currently runs a **Play** build, uninstall it on **both** devices first (`adb uninstall com.livear.fit` on each). This deletes LiveFit's local workout history on that device, so sync or export first if it matters. Never mix a Play phone with a debug watch.
2. **APKs:** `phone/build/outputs/apk/debug/phone-debug.apk` and `watch/build/outputs/apk/debug/watch-debug.apk` from the worktree.
3. **Phone:** `adb -s <phone-serial> install -r phone/build/outputs/apk/debug/phone-debug.apk`.
4. **Watch over Wi-Fi.** The LiveFit README has no watch adb section, so these are the standard Wear OS steps:
   1. On the watch: Settings → About watch → Software → tap *Software version* 5× (Developer options on).
   2. Then Settings → Developer options: turn on *ADB debugging* and *Wireless debugging* (the watch and the Mac on the same Wi-Fi).
   3. In *Wireless debugging* → *Pair new device*, note the IP:port and code, then run `adb pair <ip>:<pair-port> <code>`.
   4. Then `adb connect <ip>:<port>` (the port shown on the main Wireless debugging screen).
   5. Then `adb -s <ip>:<port> install -r watch/build/outputs/apk/debug/watch-debug.apk`.
   6. Turn Wireless debugging off afterwards (battery).
5. **Watch permissions:** open LiveFit on the watch and grant *Physical activity* (`ACTIVITY_RECOGNITION`) and the heart-rate prompt (needed by workouts, not by motion). Then **close and reopen** the watch app once, so `WatchRuntime.init` registers the passive listener after the grant. Check with `adb -s <watch> logcat -s LiveFitMotion` (this tag only; never dump unfiltered watch logcat).
6. **Proactive Context first, then the pin:** install the Proactive Context build with `WatchMotionProvider`. With LiveFit already installed, turn on its *Galaxy Watch (LiveFit)* source, which pins LiveFit's **current** certificate. If LiveFit is later reinstalled with another key (Play vs debug), the call returns `bad_cert` until that source is toggled off and on.
7. **LiveFit phone:** Settings → turn on *Share motion state with Proactive Context* (default off). `adb -s <phone> logcat -s LiveFitMotion PcCtx` should show `ok` (or a reason) within seconds if the phone has a last state.
8. **Wear test:**
   - Sit for 3 min → STILL.
   - Walk ≥ 2 min → WALKING.
   - Jog → RUNNING.
   - Start a LiveFit *Cycle* workout → CYCLING. Stop it → back to cadence-based.
   - Expect **1–15 min lag** (see risks). Record each transition's watch-to-receiver delay from the two logcats.
9. **Recovery checks:**
   - Force-stop the LiveFit phone app, then walk → the receiver accepts (new epoch).
   - Turn off phone Bluetooth for 2 min, then back on → the state is re-sent on reconnect.
   - Leave it unchanged for 25 min → a refresh appears about every 10 min.

## Risks the user must know

- **Samsung batching lag:** Galaxy Watch Health Services delivers passive steps in batches (often only every few minutes, or when the screen wakes), and doze defers the 10-minute tick. The 30 s hold is nominal, and real transitions can lag 1–15 min. Stillness is inferred from **missing** steps (120 s stale rule), so it can only show up at the next batch or tick.
- **IN_VEHICLE never comes from the watch, and CYCLING only during a Health Services biking exercise** (a LiveFit *Cycle* workout; Samsung Health auto-detected rides do not go through Health Services). Vehicle detection stays with Proactive Context's phone sources.
- **Certificate pin:** trust is pinned on first enable. Play-signed and debug-signed LiveFit differ, so after any reinstall from the other source, toggle the Watch source off and on.
- **Signing pair:** phone and watch LiveFit must both be debug (or both Play), or the Data Layer delivers nothing (step 1).
- **Battery:** one inexact wake-up every 10 min plus passive steps on the watch is small but not zero. If the phone toggle is off, the watch still sends `/lf/motion` (state only, a few messages an hour) and the phone drops it. A watch-side switch was left out to stay within 2 hours.
- **Process wake:** a `/lf/motion` message never calls `LiveFitHubService.start`, but a cold LiveFit phone process still runs the existing `LiveFitApp.onCreate → ensureRunning`, which may start the hub when a companion device is present. This is pre-existing behavior.

## Rulings

- `PROTOCOL_VERSION` stays 4 (additive path; old peers ignore it).
- The epoch lives in memory per process and `seq` lives in memory per epoch, so nothing is persisted and a restart cannot get stuck. This supersedes the "persisted seq" wording in spec §8.
- The test seam is `(String) -> CallResult?`, not `(String) -> Bundle?`. There is no Robolectric, and `Bundle` is a stub in JVM tests.
- Both ends refresh every 10 min: the watch tick (survives a phone process death) and the phone timer (survives watch doze). A state the phone has not heard from the watch in 30 min is never re-sent.
