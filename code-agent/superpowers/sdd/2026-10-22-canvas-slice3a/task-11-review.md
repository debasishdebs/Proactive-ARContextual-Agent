# Task 11 review: glasses staged atomic apply, local TRIAL, provider params (ad9cc15d, 55e745bb, 03b23eef)

Reviewer: Opus, read-only. I read review-7f9b7c6d..03b23eef.diff (2,145 lines) once, in four pages. Files outside the diff were opened only for the named checks at the end.

### Spec Compliance

- ❌ **Issues found:**
  - **The S74 caps announcement is not caps-only once a plan has been held.** `capsAck = { plan.statusAck() }` (GlassesRuntime.kt:677), and statusAck returns gen N with the install and state (PlanController.kt:1012-1021). See Important 1.
  - **"The camera wake is SETTLE" under a plan is missing.** The brief's Apply bullet, the Global Constraints hard caps ("SETTLE wake under a plan") and spec §3 item 4 (design.md:229, :555) all require it. `profile = { plan.active.profile(s.cameraWake, …) }` (CaptureService.kt:785) passes the settings wake through, and the test pins the opposite (PlanLiveApplyTest.kt:1776, "the settings wake stays"). See Important 2.
- Everything else in the brief is present:
  - the three new files;
  - every listed modification (planAware is wired through `GlassesRuntime.onPhoneSettings` rather than inside HudController, which is fine);
  - all 16 named tests;
  - both named mutation REDs, recorded in the 03b23eef commit body.
- `GateParams`, `ScoreListener` and `offerScored` are not extras: round 3 (plan line 68) assigns all three to Task 11, and they match the pinned signatures (plan :579-581).
- ⚠️ **Cannot verify from this diff:**
  - The phone side of Important 1 (Task 26 / PlanPeerTracker UX).
  - The Task 12 revert caller is not wired yet. This is fine: `PlanController.revert` exists at :1006.
  - Whether `Settings.Global.BOOT_COUNT` is readable on the Rokid ROM. This needs a device check in Task 38 (Minor 11).

### Verification answers
1. **No behaviour change in SETTINGS mode: verified.**
   - **Goldens:** `git diff --stat 7f9b7c6d..03b23eef -- android-companion/glasses/src/test/resources/golden` is empty. `DecisionGoldenRecorder` with `planFor = null` takes `ContextPolicies.of(c)` exactly as before (diff :1391).
   - **KeyframeGate:** production passes `{ runtime.plan.active.gate }` (CaptureService.kt:726). That is a volatile read of a shared `GateParams()` instance, with no allocation. `finish` reads params once per decision (KeyframeGate.kt:257). The defaults equal HAMMING, BIG_CHANGE and the whole ring. `scoreListener` adds one volatile read per camera frame, not per IMU sample.
   - **`gate.hammingNow()` in updateBackoff (SamplingSession.kt:1211):** a true no-op at defaults, since SETTINGS gives `GateParams().hamming` == `KeyframeGate.HAMMING`. It is not synchronized and takes no gate lock. It is called once per timer brief-check frame.
   - **`glanceNow()` (capNow, onDwell):** one volatile read per decision. `ActiveConfig.SETTINGS.glance == GlanceParams()`, which startSmart used before (diff :770).
   - **MotionClassifier 50 Hz path:** 4 volatile reads of `config` per gyro sample (:390, :392, :393, :418), inside the monitor `onGyro` already holds. There is no allocation, no boxing (primitive fields), no new lock and no logging. `detector.p` is a plain field. The cost is negligible (Minor 7).
   - **Reaches neither SensorRatePolicy nor sensor registration (S84):** `movementSignals()` uses `onset` from `update()` on the constant `HEAD_MOVING_RAD_S`, and `state`. `SensorRatePolicy.update` takes the status reason and the motion state. No plan field reaches either one or `AndroidSensorFeed`.
2. **Concurrency:**
   - **The receiver is private and serialized:** every entry point takes `lock`, and `locked{}` sends acks after releasing it (PlanController.kt:1066-1071).
   - **The swap:** `active` is an immutable data class behind `@Volatile`. The gate, rows and session glance switch in one write. The classifier's copy follows in the next statement, `host.install` (:1062).
   - **Lock order:** plan → motion and plan → session, never nested between motion and session. No path takes the plan lock while holding the session, gate or motion lock, because all hot-path reads of `active` are lock-free. So there is no deadlock.
   - **The 10 s abort drops a real glance:** yes, at most one per activation.
     - `abort()` resets `phase`/`armed`. `beginBout` re-initialises `start`/`lastAbove`/`turned`, so the detector stays consistent.
     - A turn still in progress restarts as a new, shorter bout.
     - This is acceptable as designed, but it repeats every second if the activation commit fails (Minor 2).
   - **Open episode past 10 s:** new gate values apply from the next decision (params are read once per `finish`). Pending window candidates are re-judged by the new threshold. Mixing within one episode is limited to cap and threshold. This path is untested (Minor 8).
   - **Mixed state does exist in one race** (Minor 1).
3. **Safety:**
   - **LIVE:** refused by the receiver (`"live" !in CAPS`). A persisted LIVE state is reverted `unsupported:live` on load (:969), and a test covers it.
   - **TRIAL:**
     - It ends on elapsed time and on a boot id change.
     - `tick` runs before anything persisted is applied (:966).
     - A staged state is applied only at the first safe tick, so a crash mid-apply leaves SETTINGS.
     - SHADOW and SETTINGS map to `ActiveConfig.SETTINGS`.
     - Unreadable state decodes to null, which means SETTINGS.
   - **commit():**
     - It runs on the CXR callback thread for messages.
     - It runs on the **main** thread for activation, TRIAL end and old-phone revert, because the supervisor scope is `Dispatchers.Main.immediate` (CaptureService.kt:718, :748). The bootstrap revert runs at `GlassesRuntime` construction, also main.
     - It happens only on state changes: about 2 to 3 per TRIAL. There is none on the idle tick, whose fast path is one uncontended monitor (:989). The battery cost is nil.
   - **Fails open on a persist failure** (Important 3).
   - **The boot-instant fallback:** a stale state can survive a reboot only when BOOT_COUNT is unreadable **and** the wall clock comes up at a fixed epoch (Minor 11).
4. **Plan semantics:**
   - **Locked rows:** `ActiveConfig.of` iterates only `PlanState` (4 editable rows; CapturePlan.kt:13). UNKNOWN and WALKING are always the DEFAULT rows (:892), and the test checks `table.size == 6`.
   - **An absent editable row is the UNKNOWN row.** The spec and plan never define this (S68 is silent; the Task 4 review asked Tasks 7 and 11 to pin it). It is now pinned on the glasses only (Minor 10).
   - **Row `capPerMin` null** gives a null context cap and `hardNow` false. This matches the goldens: the identity plan reproduces every script.
   - **`ctxBound` false** → the UNKNOWN row for every state.
   - **`hamming`** drives both novelty and the same-scene backoff.
   - **`ringCompare != "all"`** → `takeLast(1)`, and PrefsGateStore orders oldest to newest, so this is the newest keyframe (PrefsGateStore.kt:7-15).
5. **Ack and session:**
   - With no plan ever held, the caps ack is gen 0, caps, `""`, 0 and no reason, which is CAPS (PlanAck.kt:24).
   - With any plan ever held (even reverted), the caps ack is STALE (Important 1).
6. **Backup:** `capture_plan` is a MODE_PRIVATE shared pref, covered by `sharedpref:.` in backup_rules and in both data_extraction sections. The new test asserts this (GlassesBackupRulesTest :1328-1337). It is not in any export list.
7. **Untracked logs:** `.kotlin/errors/*.log` (4 files) is untracked and not in any of the three commits. It is not gitignored either (Minor 18).

### Strengths
- **The cleanest possible staging model.**
  - It uses one immutable `ActiveConfig` and lock-free reads on every hot path.
  - The plan lock is the outermost lock and is never taken from capture threads.
  - Acks go out after the lock is released.
  - The idle tick costs one uncontended monitor and does no IO, so it adds no new wakeups or timers.
- **Golden-backed identity tests.**
  - The settings path through the plan machinery and the identity TRIAL both equal the frozen goldens on every script, and the test checks that the plan really was applied (`configHash`).
  - SHADOW is proven inert with a deliberately harsh plan.
- **Bootstrap order is right:**
  - tick, then terminal check, then LIVE refusal, then decode, then resume.
  - A staged state is never applied at load.
  - The boot id is read once.
- **`offerScored` shares the `decide` path with `offer`,** so replay cannot drift. It has a recorded-score parity test.

### Issues

#### Critical
None.

#### Important
1. **After the first plan, the session-open ack is never CAPS, and no glasses ack ever MATCHes** (GlassesRuntime.kt:677, PlanController.kt:1012-1021; PlanAck.kt:24-27).
   - **What breaks:**
     - `receiver.current` is non-null forever after the first message, including after a revert. So `statusAck()` sends gen N with `phoneInstall` set and `sessionStartMs = 0`.
     - PlanAckGate classifies that as STALE, and PlanPeerTracker never reaches CAPABLE. The phone shows "Glasses app too old" on every session from then on.
     - Separately, no ack from PlanController or PlanReceiver carries `sessionStartMs`. So STAGED, EFFECTIVE and REVERTED (`auto:trial_end`, `auto:reboot`, `auto:old_phone`) are all STALE on the phone. This is Task 5 review Important 1a, carried forward and still unaddressed.
     - It contradicts S74 ("caps … at gen 0 with no plan") and the Task 5 review requirement that the announcement stay caps-only.
   - **Fix:**
     - Keep `capsAck` as the gen-0 caps-only ack.
     - After it, send `statusAck()` as a second ack.
     - Stamp every ack (onMessage acks with `m.sessionStartMs`, and self-emitted acks and the status ack with the latest phone `sessionStartMs` seen this session) inside `locked{}` before sending.
     - Add a cross-module test: a receiver ack, stamped, goes through `PlanAckGate.classify` and yields MATCH, and the caps ack after a held plan still yields CAPABLE.
2. **SETTLE wake under a plan is not applied** (CaptureService.kt:785; ActiveConfig.kt:870-871; PlanLiveApplyTest.kt:1773-1781).
   - A TRIAL on a MOTION-wake user keeps MOTION streaming. That is the costlier mode, and the invariant exists to exclude it (spec §3.4, S27, the brief's Apply bullet).
   - **Fix:** a non-SETTINGS `ActiveConfig` composes `CaptureProfile.of(CameraWake.SETTLE, tier)`. `ActiveConfig.SETTINGS` keeps the settings wake, so the goldens are unchanged.
   - Flip the test to assert SETTLE under the plan, and add a mutation RED.
3. **A persist failure fails open, or crashes the app** (PlanController.kt:966-971, :975-985, :997-1003; PrefsPlanPersistence.kt:1097).
   - **TRIAL end can fail open:**
     - If `commit()` throws during `receiver.tick` → `revert("auto:trial_end")`, then `st` is unchanged and `reverted()` never runs.
     - The supervisor's catch swallows it (CaptureService.kt:749), so the TRIAL config **stays active past its limit** for as long as the write keeps failing. This breaks S59's local bound.
   - **Unguarded throws crash the app:**
     - The same throw in `bootstrap` (from `GlassesRuntime` init) crashes the app on every start while the state persists.
     - In `onMessage` and `onPhoneSettings`, it propagates into the CXR callback, which has no catch around `onText` (GlassesBridge.kt:21-23).
   - **Fix:**
     - On any revert path, swap to `ActiveConfig.SETTINGS` in memory and clear pending **before** or regardless of the commit, so it fails closed. Keep a `revertPending` flag that retries the persist.
     - Catch persist exceptions in `bootstrap`, `onMessage` and `onPhoneSettings`, and stay on SETTINGS.
     - Test: `failNextCommit` at TRIAL end still leaves SETTINGS active.

#### Minor
1. **The `startSmart` race** (CaptureService.kt:772 vs :805).
   - The glance is read before `plan.host` is set. An activation (onMessage) or revert (onPhoneSettings) on the CXR thread in between finds `host == null`, so `install` is skipped.
   - The classifier then runs the old glance for the whole session. After a revert, that means TRIAL glance tunables under SETTINGS.
   - **Fix:** a `plan.attach(host)` that sets the host and installs `active.glance` under the plan lock.
2. **A failed activation commit forces again every second** (`tryActivate` catch, :1044-1047).
   - `stagedAt` is not reset, so each 1 s tick past 10 s calls `abortBout()` again. Glances are suppressed until the write succeeds.
   - **Fix:** back off after a failure, for example by resetting `stagedAt`.
3. **`plan.tick` shares the try block with `budget.onSample` and `applyRate`** (CaptureService.kt:745-749). A throw in either skips the TRIAL check for that second. Give it its own guard.
4. **TRIAL end is checked only while the CaptureService supervisor runs.**
   - `statusAck()` can report EFFECTIVE for a TRIAL past its limit when the service is down. Nothing captures then, but the report is wrong.
   - **Fix:** run `receiver.tick` inside `statusAck`.
5. **Commits happen on the main thread** at activation, TRIAL end and the bootstrap revert (prefs file up to about 17 KB with `previous.planJson`).
   - `onMessage` holds the plan lock across its fsync, so the main-thread `tick` can block on it briefly.
   - It is rare, so I accept it. Optionally move the slow path to ioScope.
6. **The default provider `{ GateParams() }` allocates on every decision and every `hammingNow()`** (KeyframeGate.kt:150, :152; the Rig at SamplingTestKit.kt:2065). It is test-only today. Use a shared `GateParams.DEFAULT`.
7. **`onGyro` reads the `@Volatile config` 4 times per sample under its own monitor.** This is plan-mandated (S69) and harmless. Reading it once into a local is a free tidy-up.
8. **The forced swap with an open camera episode is untested.** StagedActivationTest never starts the session, so `RigHost.episodeOpen()` is always false, and `noMixedOldDetectorNewCaps` covers the detector only.
9. **Some tests are close to vacuous:**
   - `settingsEditAfterPublishDoesNotChangeLivePlan` (PlanLiveApplyTest.kt:1783-1796): `ActiveConfig` has no settings input, and `onPhoneSettings(true)` returns at once, so it cannot fail. It never checks the composed profile after the edit.
   - `heatStopBeatsPlan` and `userPauseBeatsPlan`: the Rig's profile is not taken from the plan rows.
10. **The meaning of an absent editable row is now pinned (as the UNKNOWN row) only on the glasses** (ActiveConfig.kt:884).
    - `PlanResolver.resolve` fills an absent row from base, and `decodeStrict` accepts an absent row.
    - Task 7 must emit all four rows, or Task 20 must use `ActiveConfig.of`, or replay and the glasses will diverge. Flag this to the controller.
11. **The `bt:` fallback boot id** (ReceiverStateJson.kt:106-107) cannot see a reboot on a device whose wall clock starts at a fixed epoch before NTP. Only `now < start` catches it then.
    - Verify `BOOT_COUNT >= 0` on the Rokid in Task 38.
    - Optionally log once at bootstrap when the fallback is in use.
12. **The pc_state Paused path re-feeds cached settings into `onPhoneSettings`** (HudController.kt:144).
    - A cache written before the phone updated has `planAware = false`, which gives a spurious `auto:old_phone`.
    - It fails safe. Gate the revert on real `pc_settings` only.
13. **A corrupt stored state decodes to null and loses the `terminal` map.** A re-sent, already-REVERTED gen could then be applied again. This happens only after corruption, so it is accepted.
14. **Fixed mode shares the gate** (CaptureService.kt:724-726). A TRIAL's hamming and ring rule therefore also change Fixed-mode dedupe. Confirm this is intended.
15. **`appliedState()` is computed every TRIAL second** (`readNormalized` copies the settings), but `receiver.tick` ignores it. This is trivial.
16. **The commit trailers say "Claude Sonnet 5.5"**; Global Constraints require Opus 5.5. This repeats from Task 5.
17. **The report carries no test output or counts**, only "green". Report this gap to the controller.
18. **`android-companion/.kotlin/` is not gitignored.** The error logs are untracked and uncommitted, which is fine. Add the ignore before an explicit-path commit picks them up.

### Checks run (named risk, then what was checked)
- **Goldens untouched:** `git diff --stat` on `glasses/src/test/resources/golden` is empty.
- **Logs not committed:** `git diff --stat` on `android-companion/.kotlin` is empty. `git check-ignore` finds no rule.
- **Ack classification:** read PlanAck.kt (classify, PlanPeerTracker) and Hub.kt:421-424. No ack is stamped with a session, and CAPS needs gen 0, `""` and 0.
- **Receiver semantics and persistence throws:** read PlanReceiver.kt in full (LIVE refusal, boot read once, tick and revert commit paths).
- **SETTLE invariant:** spec design.md:229, :555, and `CaptureProfile.withContext` (wake changes only on `forceSettle`).
- **Init order:** GlassesRuntime.kt declares `settingsCache` at :71, `contextGate` at :103 and `plan` at :119, so `appliedState` is safe during bootstrap. `controller` is read only on send.
- **Callback threads and crash surface:** GlassesBridge.kt:21-23 and SharedBridge.kt:21-23 have no catch around `onText`.
- **S84 sensing isolation:** SensorRatePolicy.update and `movementSignals`, and MotionClassifier `update` and onset on the constant threshold.
- **Ring order:** PrefsGateStore.lastKeyframes and push (`takeLast`, appended newest).
- **planAware source:** HudController.kt:62 (`cache.read()`) and :144 (the pc_state path), and HudSettingsCache read and write.
- **Not run:** Gradle or tests (read-only). The tests I would add:
  - a stamped ack through the gate yields MATCH;
  - the caps ack after a held plan yields CAPABLE;
  - a failed commit at TRIAL end still leaves SETTINGS;
  - SETTLE under a TRIAL with MOTION settings;
  - the forced swap with an open episode.

### Assessment

**Task quality:** Needs fixes

**Reasoning:** The hot-path changes are behaviour-preserving and cost nothing measurable. The settings path is proven against untouched goldens, and the staging, locking and bootstrap order are sound. However, three things block approval:
- the session-open and plan acks can never be matched on the phone after the first plan (an S74 violation, carried forward from Task 5);
- the SETTLE-wake invariant under a plan is missing;
- a persist failure keeps a TRIAL running past its limit, or crashes the app.
