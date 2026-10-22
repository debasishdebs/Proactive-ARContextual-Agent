# Task 6 review: frozen trace schema (cb9d251c, base 2a4adf87)

Reviewer: Opus, read-only. Read the diff package once, all 1,359 lines in two pages. I ran no Gradle and no tests. To check named risks I read code outside the diff: `SamplingSession.kt` and `SensorFeed.kt` in /pca-s3/c (glasses), `MotionClassifier.kt`, `CameraPolicy.kt`, `KeyframeGate.kt` and `CaptureProfile.kt` in /pca-s3/a :core:capture, and `CanvasSlice3Entities.kt` in /pca-s3/b. I also read spec S42 (§6.1) and plan S65, S66, S73, S75 and Tasks 16 to 20.

## Spec Compliance

- ❌ **Issues found.** The brief's checklist is all present: 15 tags, the listed `StartSnapshot` fields, the listed `Profile`, `Score`, `Gate` and `Plan` fields, the bounds, the manifest additions and every named test. However, two binding requirements behind the brief are not met:
  - **S65:** "events carry every scheduling input" and "exact replay recomputes opens ... must equal the recorded `Episode(open)`" (Task 20). See C1 and C2.
  - **S42 lite:** "Clock offset: glasses <-> phone, sampled at the trace start and at its end". See I1.
  - The manifest dropping the per-chunk sha list is an acceptable deviation, but it must be written back into S42 and the plan (M1).
- ⚠️ **Cannot verify from the diff:**
  - `TraceChunk.installId` and `deletionCounter` are pinned on the wire. `ChunkCodec` and `EncodedChunk` neither produce nor check them. Task 17 must fill them, and Task 18 must check `chunk epoch == manifest epoch`.
  - The `canvas_trace.wipeGen` column has no manifest counterpart. Spec S42 lists wipeGen as part of the manifest. Confirm that the S57 epoch (`installId` + `deletionCounter`) replaces it, and that the phone fills `wipeGen` locally.

## Strengths

- **Codec discipline is excellent.** Decode accepts a line only if it re-encodes to exactly itself, at both levels: `EventCodec.decode` (TraceRecords.kt:765-770) and `Manifest.decode` (Manifest.kt:394). This canonical check also rescues the `toInt()` truncations at Manifest.kt:385, 389 and 404: for example `revision` 4294967297 becomes 1, re-encodes differently and is refused.
- **Bounds are checked in the right order:**
  - sha regex, then b64 length, then base64, then streamed gunzip;
  - then sha over the RAW payload, then strict UTF-8;
  - then the header count (checked before any event line is parsed);
  - then the line count;
  - each event line is length-capped (512) before `parseToJsonElement`, so parser depth is bounded too.
- **The gunzip limit really bounds memory** (ChunkCodec.kt:159-173). The check comes before each `write`, so the buffer never exceeds the limit; the initial capacity is `min(limit, 4*gz)`; the inflater is fixed-size. Behaviour on hostile gzip input:
  - Concatenated members are inflated by `GZIPInputStream` and stay under the same limit.
  - Trailing non-gzip bytes are ignored by the JDK. This is harmless, because the sha binds the raw payload and b64 is capped at 90,000 characters (report concern 4 is OK).
- **Every decode failure comes back as a `TraceFormatException` in a Result.** `ChunkCodec.decode` uses `runCatching` plus `recoverFormat`, so Errors such as OOM are caught too. `Manifest.decodeStrict` pre-checks depth, so `parseToJsonElement` cannot overflow the stack.
- **Float canonicality is right.** IEEE bits travel as a signed int; `-0.0` becomes -2147483648. Denormals round-trip, NaN bits are refused (`finite`), and 2147483648 is refused (`int()` range).
- **Text fields cannot break the JSON.** The token, hex and id regexes make an unescaped `str()` safe. A `A` escape is refused by re-encoding, and non-ASCII fails the regexes.
- **The chunk header binds `traceId` and `seq`.** Swapped, reordered or foreign payloads are caught twice: by `header_mismatch` and by `TraceAssembler`.
- **`TraceAssembler` catches:**
  - a missing seq;
  - a duplicate with a different sha (conflict);
  - a seq beyond the count;
  - time running backwards across chunks;
  - events outside [start, end];
  - totals that disagree;
  - a substituted self-consistent chunk (trace sha);
  - a trace the glasses recorded as PARTIAL.
- **The golden fixtures are byte-exact:** payload text, raw size, sha, manifest JSON and trace sha. `GOLDEN_B64` really is a foreign gzip (header `1f8b0800 00000000 02ff`, OS=255, which is Python's; the JDK writes OS=0), so `foreignGzipOfGoldenDecodes` is a genuine cross-implementation test.
- **Mutation REDs are listed in the report:** the gunzip limit, the boot check, the sha check and the canonical check.

## Issues

### Critical (must fix before the freeze)

**C1. Gyro and accel carry the wrong time for the classifier, and the non-decreasing rule makes the right time unrepresentable.**
- **Where:** TraceRecords.kt:590-592 and 600-608; ChunkCodec.kt:79-84 and 132-136.
- **What the format says:** every `tMs` is the append time, and `tMs` is non-decreasing over the whole trace.
- **What the classifier uses:** the sensor event time. `SensorFeed.kt:111` passes `e.timestamp / 1_000_000` to `onGyro` and `onLinearAccel`. `MotionClassifier` (core MotionClassifier.kt:43-48, 89-100) integrates `dt` from it and explicitly keeps the two streams on separate clocks, because "batched delivery (STILL rate) gives no cross-sensor order".
- **Why both stamping choices fail:** at the STILL rate (0.5 s report latency) and the LOW rate (1 s), samples arrive in batches.
  - If the recorder stamps append time, a batch gets near-identical `tMs`, so `dt` is about 0 and rotation, glances and dwell all diverge.
  - If it stamps sensor time, batched gyro and accel overlap and fall behind the already-appended `Score`, `Profile` and `Episode` events, so `ChunkCodec.encode` throws `not_monotonic`.
- **Fix (schema 1):**
  - Give `g` and `a` a separate sensor-time field, for example `["g",tMs,sMs,x,y,z]`, where `sMs` = `e.timestamp/1e6` (the exact ms value the classifier sees).
  - Keep the line `tMs` as the append or delivery time. It is non-decreasing, and it gives the delivery interleaving against ticks that replay needs.
  - Do not require `sMs` to be monotonic (the classifier uses `maxOf`).
- **Tests to add:** a batched out-of-order gyro/accel fixture round-trips, and the decoder accepts `sMs < tMs` and `sMs` regressions.

**C2. Scheduling inputs and start state that Task 20 needs to recompute opens exactly are missing.**
- **What breaks:** `CameraPolicy.decide`, the session loop and `KeyframeGate.offerScored` read the values below. None of them is in an event or in `StartSnapshot` by value, so "recomputed opens must equal the recorded `Episode(open)`" cannot hold in common cases. This includes the default T0 MOTION wake.
- **Inputs absent today** (all cited in /pca-s3/c `SamplingSession.kt`):
  - `quickLookMs` (:323): `holdMs` decides open and hold in MOTION wake. Only a settings hash prefix is recorded, which cannot be inverted.
  - `quiet = darkStreak >= 3` (:324, :564, :593): dark and no-copy frames never reach the gate, so there is no `Score`, and the 15 s/quiet cadence cannot be reproduced. Task 1's report notes the same blind spot: "blur/dark not observable".
  - The episode close outcome (:436-446): `openFailed` or `noFrames`, then busy (30 s), bind failure (1 s) or cold-start (500 ms). These set `lastBriefAt = now - 40 s` and `bindBackoffUntil`. `Episode(close)` carries only `firstFrameAtMs`.
  - The brief lead (:460-468, :528): the rolling p95 of `firstFrameAt - openedAt` over 20 samples. `openedAt` (camera actually opened) is not recorded, and the startup ring is not in the snapshot. The lead shifts every timer open by 250-1,500 ms.
  - `cap` and `hard` (:271-273, :553-554): pinned `offerScored(..., cap, hard, nowMs)` takes them. Recording them in `Score`, or in a profile event, removes a context/plan re-derivation from replay.
- **Start state absent from the snapshot:**
  - `lastBriefAt` (it decides the first timer open);
  - `stillStep` and `lastCheckHash` (the backoff);
  - `darkStreak`, `bindBackoffUntil` and `coldStartGrace`;
  - the startup ring;
  - the classifier pose since the last mark (`rx`, `ry`, `rz`, `walked`, the `q*` and `quietSinceMs`). It accumulates since the last keep, often before the trace, and feeds `GlanceAdmission` novelty until the next keep. The 10 s pre-roll does not refill it;
  - `lastPhotoAttemptAtMs` for the S83/S70 reserved attempt.
- **`lastKeyframeAt` and `keptAt` are ambiguous.** The session's `lastKeyframeAt` and `keptAt` and the gate's `store.lastKeptAt` and `keptTimes` are different values (they differ after an encode failure, a forced keep, or a session restart). The doc must say which one is recorded, or both must be recorded.
- **Fix (schema 1):**
  - Add `quickLookMs` to `pf`.
  - Add a frame-unusable event, or a `quiet` flag change event.
  - Add `outcome` (ok|open_failed|no_frames|busy|bind|cold) and `openedAtMs` to the `ep` close.
  - Add `cap` and `hard` to `sc`.
  - Extend `StartSnapshot` with the scheduler and classifier start state above. Alternatively, define the clean boundary to require these to be at known values, and say so.
- **Test to add:** a Task 20 smoke check. Drive the Task 1 scripts and assert that every input `CameraPolicy.decide` reads is derivable from the trace.

### Important (should fix before the freeze)

**I1. The phone clock offset is a single value "filled by the phone at intake", not sampled at the trace start and end (S42).**
- **Where:** Manifest.kt:271-273 and 286-287; ClockMap.kt:212-215 and 223-226.
- **Why it maps traces wrongly:** if the glasses wall clock is corrected between recording and intake, the intake-time offset does not apply to the trace. That happens on an NTP or link sync after boot, on a manual set, or with a Task 19 pulled import days later. The map then returns a confidently wrong phone time, with no flag.
- **Fix:** have the recorder take `phoneWallMs` samples from the link at the start and end, for example from the latest phone `atMs` with a round-trip bound. Make them nullable when unlinked.
- **Clock jumps:** a glasses wall jump mid-trace is smeared linearly. Its only trace is `uncertaintyMs`, and that is null when unsynced, even though the drift is known (ClockMap.kt:225). Refuse, meaning return null, when `|eo - so|` exceeds a fixed bound, for example 2 s. Test both cases.
- **What is right already:** reboot handling (another `bootId` gives null; `resolve` filters by boot) and the 60 s hold.

**I2. The manifest-as-`TraceChunk` encoding and the on-disk layout are not frozen.**
- **Where:** Manifest.kt:257-260.
- **What is undefined:** the manifest "rides one `TraceChunk` of kind manifest", but nothing defines:
  - its `seq` and `count`;
  - whether `b64` is gzip or plain;
  - what `sha256` covers.
- **Why it matters in CORE:** the core interchange is Task 16 writing files that Task 19's `TraceImport` reads after `adb pull`, and those tasks are in streams C and A. The file naming, the chunk file contents (raw payload, gzip, or `TraceChunk` JSON) and the manifest file are part of the frozen contract but undefined.
- **Fix:** add a `ManifestChunk.encode/decode` and a `TraceFiles` layout spec, with a golden for each.

**I3. No guard test pins the enum wire names.**
- **Where:** TraceRecords.kt:718-724; Manifest.kt:361, 390, 411-412.
- **The enums that travel by name:**
  - `ContextState`, `PlanMode` and `MotionState` (in `:core:model`, edited by other tasks);
  - `CameraWake`, `BudgetTier`, `EpisodeCause`, `GateOutcome` and `TraceStatus`.
- **Risk:** the goldens pin only REST, SETTLE, T0, SHADOW, STILL, timer, dwell, keep and COMPLETE. Renaming or adding a value silently changes schema 1. Old traces become unreadable, or new traces cannot be read by an old phone.
- **Fix:** add `enumWireNamesFrozen`, asserting the exact ordered name list of each enum and the 15-tag list, with a "bump TRACE_SCHEMA" message.

**I4. Mid-trace plan and settings changes are not carried by value.**
- **Where:** TraceRecords.kt:631-634 (`pl` = gen, hash, mode only); the snapshot glance is start-only.
- **Problem:** activating a plan mid-trace changes the glance params, and so `MotionClassifier` and `GlanceAdmission`. Replay then has to resolve the plan JSON by `configHash`. Glasses-local TRIAL reverts and older or foreign plans may not be resolvable. A settings edit mid-trace changes `quickLookMs` and so on, with no event.
- **Fix:** either add a `gl` glance-params event, or require the recorder to close the trace (`cl` with plan or settings) on activation or edit. Document the choice in Task 16.

### Minor

- **M1. Per-chunk sha list deviation** (Manifest.kt:262-265). The deviation is sound. Integrity is equivalent, because the chained sha binds every chunk's raw sha. The only loss is identifying which chunk is bad, which is moot with no per-seq resume. Record it in spec S42 and in the plan Task 18 text ("inventory complete" = all seqs once + traceSha), or Task 18's reviewer will flag it.
- **M2. `TraceAssembler` ignores `TraceChunk.count`** (Manifest.kt:476-482). `DecodedChunk` drops `count`, so a chunk claiming `count` ≠ `manifest.chunkCount` is not flagged. The content is still caught by the trace sha. Carry `count` and flag `count_mismatch`.
- **M3. Recorder obligations are documented but unchecked:**
  - `Close` last and equal to `closeReason`;
  - `PreRollEnd` exactly at `startElapsedMs + 10 s` (it duplicates `preRollEndMs`);
  - the initial `cx`, `rs`, `pf`, `gp` and `pl` at the start.
  - Fix: add an assembler or validator check, so a recorder bug becomes PARTIAL rather than a silent bad replay (report concern 3).
- **M4. `Gate` has no cross-field check.** KEEP should have `""`; SKIP should be one of BLUR, DUPLICATE, THROTTLED, WINDOW or CAPPED (TraceRecords.kt:650-652). The token regex allows any 32-char token.
- **M5. The base64 decoder accepts unpadded input** (JDK basic decoder), so the "padded" rule is not enforced. This is harmless because the sha is over the raw payload. Either enforce `b64.length % 4 == 0` or document it. URL-safe characters and whitespace are refused (tested at ChunkCodecTest.kt:906).
- **M6. Single writer (report concern 1).** Task 16 must take `elapsedRealtime` inside the append lock across the sensor thread, the camera executor (score listener), the session tick and the link thread. Times with their own meaning (the gate's `now`, sensor time, the first frame) belong in fields, not in `tMs`. Write this into the Task 16 text.
- **M7. The b64 bound, not 512 KiB, is the binding chunk limit.** IMU lines gzip roughly 3:1, so 90,000 characters is about 67 KB gzip, or about 200 KB raw. `encode` throws `b64_too_long` after the work is done. Task 16 needs a split-and-retry, or a raw target of about 150 KB. The round-1 64 KB flush fits. 4,096 chunks × 64 KB = 256 MiB = `MAX_TRACE_RAW_BYTES`, so 12 h at 100 events/s (about 195 MB) fits.
- **M8. The golden manifest depends on `GlanceParams()` defaults** (TraceFixtures.kt:1333). A legitimate default retune breaks a "frozen" golden. Use explicit values.
- **M9. Naming cross-check with `canvas_trace`:**
  - `installId`, `deletionCounter`, `revision`, `parentTraceId` and `exact` match.
  - The Room `revision` default is 0, while the manifest requires ≥ 1. Task 18 must map this.
  - The manifest `exact` is derived from `snapshot.exact`, and is not a top-level key.
  - `bootId` is a string, while `MarkMessage.bootCount` is an Int. Pin `bootId` = the decimal `Settings.Global.BOOT_COUNT`, or the mark-to-trace join will mismatch.
- **M10. Tests that cannot fail or are dead:**
  - `TraceFixtures.rawChunk` is unused.
  - `preRollIsTenSecondsAfterTheStart` restates constants.
  - `nothingPrivateInTheManifest` checks only ASCII.
  - There is no test that `sampleEvery` applies only to `g` and `a`, and no doc saying so.
- **M11. `uncertaintyMs` is null when unsynced** (ClockMap.kt:225), although the glasses-side drift `|eo - so|` is known.

## Checks run (named risk, then what I checked)

- **Gunzip bomb:** the limit check comes before the write, the buffer starts at min(limit, 4·gz), and GZIPInputStream handles concatenated members and ignores trailing bytes. Memory is bounded at limit + 8 KiB. OK.
- **Huge numbers:** INT ≤ 16 digits plus the canonical re-encode, and `toInt()` truncation is caught by re-encoding. OK.
- **JSON depth:** event lines are capped at 512 characters before parsing; the manifest uses `depthOk(5)` before parsing. OK.
- **Sensor time vs append time:** `SensorFeed.kt:111` and `MotionClassifier.kt:43-48`. This gives C1.
- **Scheduler inputs:** `SamplingSession.kt:271-345, 436-468, 520-595`. This gives C2.
- **Room naming:** `CanvasSlice3Entities.kt:21-40`. This gives M9.

## Assessment

- **Spec compliant:** No. S65 "every scheduling input" and the exact-recompute contract fail (C1, C2), and the S42 start/end phone offset is missing (I1).
- **Task quality:** Needs fixes. The codec and bounds work is high quality, and the hostile-input handling is sound. But the event model cannot reproduce the classifier's input times, or several `CameraPolicy` and session inputs. Fixing either later needs a schema bump and invalidates collected traces, so both must be fixed now.

**FROZEN: NOT YET.** Do not start Tasks 16 and 20 until C1, C2, I1, I2 and I3 are fixed and re-reviewed. I4 needs at least a recorded decision.
