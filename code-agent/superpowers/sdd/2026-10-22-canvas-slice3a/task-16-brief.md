### Task 16: Glasses IMU recorder (A8 part, S65, S66, S57) [CORE 3 h]
**Stream C · 3 h · depends on: 6, 12.**

**Files:**
- round 1 Task 12's recorder parts;
- the `KeyframeGate.setScoreListener` implementation (pinned);
- `SamplingSession` emits the Task 6 events, including `Episode`, `Flush`, `Backoff`, `Plan` and `Close`.

**Implement:**
- **Window and buffering:** round 1 (records only while collecting and eligible; pauses on privacy pause; 64 KB flush on sample arrival; 150 MB cap).
- **Boundaries:** a trace starts only at a clean boundary, with a 10 s pre-roll. `exact = (sampleEvery == 1)`.
- **Epoch:** `TraceControl` with a new `deletionCounter` or `installId` → drop the buffer, delete the local traces and `imu_trace_tmp`, and adopt the epoch.
- **Writes:** chunk files are written to `imu_trace_tmp`, then renamed.

**Tests:**
- round 1's;
- `startsOnlyAtCleanBoundary`, `preRollMarked`;
- `scoreEmittedForBlurFailure`;
- `closeAndPlanEventsEmitted`;
- `newEpochDropsBufferAndTraces`, `newInstallIdResets`;
- `exactFalseWhenDownsampled`.

**Commands:** `gw.sh :core:capture:test`, `gw.sh :glasses:testDebugUnitTest --tests 'com.rokid.context.glasses.capture.trace.*'`.

**Commit:** `feat(glasses): IMU trace recorder on the frozen schema (clean start, pre-roll, every score), epoch-scoped wipe`

