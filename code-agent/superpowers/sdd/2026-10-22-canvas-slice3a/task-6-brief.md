### Task 6: Trace schema freeze (A8 pure part, S65, S66, S73; reviewed by Opus before Tasks 16 and 20 start) [CORE 2.5 h]
**Stream A · 2.5 h · depends on: 2.**

**Files:** `core/capture/src/main/kotlin/com/rokid/context/capture/trace/{TraceRecords,ChunkCodec,Manifest,ClockMap,TraceBounds}.kt` and their tests.

**Implement:**
- **Events** (all `tMs` = glasses `elapsedRealtime`):
  - `Gyro`, `Accel`;
  - `Ctx(state)`, `Resting(on)`;
  - `Profile(wake, settled, quiet, walk, stillCap, analysisMs, tier, lowPower, heatSlow)`;
  - `GateParams(hamming, bigChange, ringAll)`;
  - `Plan(gen, configHash, mode)`;
  - `Episode(open|close, cause: timer|dwell|onset|now|resv|stream, firstFrameAtMs?)`;
  - `Score(seq, d, p, blur, passedBlur, motion, dwellArmed, force)`;
  - `Gate(seq, outcome, reason)`;
  - `Flush(reason)`;
  - `Backoff(stillStep)`;
  - `Blocked(reason?)`;
  - `PreRollEnd`;
  - `Close(reason)`.
- **`StartSnapshot`:** settings hash, ring, `lastKeyframeAt`, `keptAt`, plan gen and hash, the glance params, the applied ctx, resting, tier, `sampleEvery`, `exact`.
- **Clean boundary:** the recorder may start only when the detector is QUIET, no episode is open and the gate window is empty. `PreRollEnd` comes 10 s after the start, and the simulator discards decisions before it.
- **Manifest-lite:** as round 1, plus `installId`, `deletionCounter`, `revision`, `exact`.
- **`TraceBounds`** (S73): the b64, gunzip, events-per-chunk, chunk-count and `traceId` regex limits, enforced on decode (a streamed gunzip limit).

**Tests:**
- `roundTripEveryEventType`, `floatBitsExact`;
- `gunzipBombRejected`, `tooManyEventsRejected`, `badTraceIdRejected`, `tooManyChunksRejected`;
- the manifest COMPLETE/PARTIAL cases (round 1);
- `clockMapStartMidEnd`;
- `downsampledFlaggedNotExact`.

**Command:** `gw.sh :core:capture:test`.

**Commit:** `feat(capture): frozen IMU trace schema (scheduling inputs, scores, flushes, clean start with 10 s pre-roll), bounded codec, manifest-lite with epoch and revision`

