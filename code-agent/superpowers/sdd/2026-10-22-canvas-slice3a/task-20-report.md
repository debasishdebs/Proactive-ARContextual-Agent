# Task 20 report: CaptureSimulator replay (approximate, per controller ruling)

Worktree `/Users/debasishkanhar/Desktop/rokid/pca-s3/a`, branch `s3a/a`, base 5b28975c. Commit `30786b43`.

## Status
DONE with concerns (below). `:core:capture:clean :core:capture:test`: 305 tests, 0 failures, 0 skipped (22 new: 18 `CaptureSimulatorTest`, 1 `CaptureSimulatorStreamTest`, 3 `MotionClassifierRestoreTest`).
`:glasses:testDebugUnitTest --tests '*golden*' --tests '*SamplingSessionTest'` green (the classifier seam changed nothing live).
Gate command was `./gradlew --no-daemon --offline` (no `gw.sh` in this checkout); no adb; no background processes started by me.

## What was built
- `core/capture/.../capture/replay/CaptureSimulator.kt`: `CaptureSimulator.replay(events: Sequence<TraceEvent>, m: Manifest, candidate: ReplayCandidate? = null): ReplayResult`.
  - `snapshot.exact == false` -> `IllegalArgumentException("approximate trace")`.
  - Streams event by event; state is the open window, pending decisions and results (the 12 h test proves it).
  - Reuses the real `MotionClassifier` (fed `sMs`), `CameraPolicy.decide`, `GlanceAdmission.decide`, `KeyframeGate.offerScored/flushWindow/timerHoldUntil`, `reservedBoundMs`, `CaptureProfile`. The gate clock is a seam (`fl.nowMs`, `sc.nowMs`); the gate ring, last keep and `keptTimes` are seeded from the snapshot (`recordKeep` with a frozen store, no production change).
  - Tick every 250 ms of event time with the live loop's `kick` and `wait` shortening, from the recorded `Profile`, `Backoff`, `Blocked`, `Ctx`, `Resting`, `DarkStreak`, snapshot start state and the S83 reserved attempt.
  - Episodes follow the recording; the replay only decides OPENS (when none is open, or while a recorded open still waits for its twin). Open match = `ReplayRule.opensMatch` (same cause, within 250 ms). Gates compared with `ReplayRule.gatesMatch`.
  - Windows are cut at every recorded open (both ends included). `Exactness.classify` marks CAPTURE_NOW / BIND_FAILURE / ASYNC_CALLBACK windows (bind back-off carried into later windows); inexact windows are excluded from pass/fail and counted in `ReplayTally` and `inexactReasons`. Pre-roll warms up and is discarded.
  - Base vs candidate: `ReplayResult.configHash` = candidate's hash, else the recorded `Plan` hash, else `"0"`. A candidate (`glance` / `gate` overrides) is NOT compared (`compared=false`, passed/failed/inexact 0); bookkeeping then comes from the replayed gate, not the recording.
- `MotionClassifier.restoreForReplay(...)` (internal): applies the snapshot's `prevGyroMs` and pose. Only addition to production code.
- Trace format untouched (frozen). `ReplayRule`, `Exactness`, `ReplayTally` were already in `TraceFiles.kt`; used as is.

## Tests (RED then GREEN, mutation-checked)
`CaptureSimulatorTest`: recomputedOpensEqualRecorded, backoffLadderIsRecomputedFromRecordedStillStep, recomputedOpensCatchABrokenPolicyInput (the brief's mutation: shifted `stillStep`), exactReplayMatchesGoldenSessionTrace, aGateMismatchFailsTheWindow, windowSelectionReproduced, preRollDiscarded, approximateTraceRefused, deterministic, captureNowWindowIsInexactAndExcluded, bindFailureWindowIsInexact, asyncDwellWindowIsInexactButStillReplayed (hand-derived ONSET 20,000 and DWELL 22,200), configHashOnResults, candidateGateParamsChangeTheKeeps, gateUsesTheRecordedDecisionTimeNotTheAppendTime, flushUsesTheRecordedFlushTime, darkStreakDrivesTheQuietCadence, reservedPhotoAttemptIsRecomputed. `CaptureSimulatorStreamTest.streamsWithoutMaterialising` (12 h, 2.16 M sensor events plus 8.1 k episodes, retained heap after forced GC under 64 MB). Expected opens come from arithmetic in `ReplayFixtures` (an independent oracle), never from the simulator.

Mutations applied one at a time to the simulator/classifier (each restored): all RED on the intended test:
ignore `bo` (ladder + broken-input), full echo of opens (broken-input only), drop flush, no pre-roll, `events.toList()` (stream), no prevGyro restore, ignore inexact, ignore gate compare, `sc.nowMs`->`tMs`, `fl.nowMs`->`tMs`, ignore `dk`, drop S83 reserved attempt, candidate hash. Two initial survivors (nowMs, dk, resv) got new tests and then went RED.

## Concerns
1. **Task 1 scripts not used as fixtures.** They live in `:glasses` tests (real `SamplingSession` via `Rig`) and no recorder (Task 16) exists in this branch, so a real-session trace cannot be produced. `exactReplayMatchesGoldenSessionTrace` replays a scripted two-minute oracle trace instead; the real-session check is Task 21/23a (`sessionRecordingReplaysBitIdentical`).
2. **Tick body is mirrored, not shared.** The loop around `CameraPolicy.decide` (brief lead, hold, reserved attempt, flags) lives in glasses `SamplingSession`; Task 1/2 moved only the pure classes. Constants (lead 535, p95 clamp, backoffs) are duplicated. Suggest extracting a pure `SchedulerTick` into `:core:capture` later; Task 21's bit-identical session test is the guard until then.
3. **Recorder assumptions Task 16 must honour** (documented in the class KDoc): one `gt` per `sc` for the judged seq; a window/flush keep appends `gt` for the WINNER's seq; a flush that keeps nothing appends none. `gt.keptAtMs` and `encoded` are adopted from the recording for a reproduced keep (the frame-entry time and the encoder are not in the trace), so they are not independently verified.
4. Outcome mapping for failures is my reading of the schema (COLD -> +500 ms, BUSY -> +30 s, BIND -> +1 s and counts toward 3, OPEN_FAILED/NO_FRAMES -> cold if grace else bind). These windows are inexact anyway. `lastKeyframeAt`/`lastBriefAt`/`lastPhotoAttemptAtMs` null in the snapshot are taken as start / start-40 s / start.
5. Candidate mode follows recorded episodes (frames exist only where the base config opened); counterfactual fidelity is Task 21's concern.
6. Two Java processes (Gradle daemon 8.13 and a Kotlin daemon, about 1 h old) were already running; not killed (cannot tell they are mine).
