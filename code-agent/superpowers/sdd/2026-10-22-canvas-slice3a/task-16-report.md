# Task 16 report (stream C, branch s3a/c, base 5b28975c): glasses IMU trace recorder

Status: DONE. Trace schema 1 (`:core:capture` trace package) NOT touched. Clean-module counts (real XML totals): :core:capture 283 tests, :glasses 695 tests, 0 failures, 0 errors, 0 skipped. New: 44 tests in `com.rokid.context.glasses.capture.trace` (TraceRecorderTest 28, TraceSessionTest 16).

## Built
- `glasses/.../capture/trace/`: `TraceRecorder` (SessionTrace + SampleTap), `EventBuf` (struct-of-arrays, no allocation per sensor sample), `TraceStorage` (files), `SessionTrace` + `TraceInfo`/`TraceProfile`/`TraceStart`/`TraceSettingsHash`.
- Window: records only while a `pc_trace_ctl` says collect (expiry measured on the glasses clock from the phone's two stamps) AND the session offers a clean boundary (detector QUIET, no episode, empty gate window, nothing pending, unblocked). Initial cx/rs/pf/gp/pl at the start stamp; `pr` carries exactly start+10 s and is inserted before the first append at or after it.
- Single writer: `tMs = clock()` taken INSIDE the append lock. Writer thread (`imu-trace`, idle daemon, no timer/wake-up) does gzip/b64/sha/files; at 64 KB estimated payload the buffer is handed over; writer 3 buffers behind => batch dropped and trace PARTIAL (never blocks sensing). `b64_too_long`/`gunzip_too_big` => split in two, retry (first half keeps seq).
- Files: `imu_trace_tmp/<id>/chunk-NNNN.json` (via `.part` rename), manifest last, dir renamed to `traces/<id>`; count stamped at publish. Start-up deletes unfinished tmp. 150 MB cap: trace closes `cap`, no new start until space frees, nothing deleted, nothing dropped.
- Epoch: other installId or larger deletionCounter => drop buffer + running trace, delete traces + tmp, adopt and persist (`trace_epoch`); same install with smaller counter = stale, ignored.
- Close reasons: collect off/expiry/heat STOP/session stop => `stop`; privacy pause => `pause`; plan change => `plan`; back to no plan => `plan_revert`; settings hash change => `settings`; caps => `cap`. New segment at the next clean boundary (new snapshot, pl/gp at new start). `clampDarkStreak()` applied in the recorder (`dk`) and the snapshot.
- Session seam (`SamplingSession`, optional `trace`/`traceInfo` params, null = byte-identical): score listener (context via fields set before `gate.offer`; `sc.nowMs` from new `KeyframeGate.lastOfferNowMs()`), ep open/close (outcome = scheduler view: ok/cold/busy/bind), fl, gt, bo/dk via `still()`/`dark()` choke points, bl/cx/rs/pf/gp on change per tick. Gate additions: `paramsNow`, `traceState`, `windowEmpty`, `lastOfferNowMs` (flushWindow sets it). MotionClassifier: `poseSnapshot`, `prevGyroMs`.
- Wiring (compiled, not unit-testable): `HudController.onTraceControl` (pc_trace_ctl), `GlassesRuntime.traceRecorder`, `CaptureService` tap + params, `PlanController.traceKey()`.
- Coordinator rules for Task 20 (pinned by `gateEventsFollowTheSimulatorRules`): one `gt` per `sc` for the seq the gate judged (skip: own seq; window keep: WINNER seq); a flush that keeps appends `gt` for the winner; a flush that keeps nothing appends none; `keptAtMs`/`encoded` are the session bookkeeping values (`anEncodedKeepCarries...`, `anEncodeFailure...`).

## RED then GREEN
The implementation was written before the tests (integration seam discovery), so RED is shown by mutation, each reverted and killed: stamp outside lock -> eachSampleIsStamped...; pr at stamp -> preRollMarkedEvenWhenTheNextAppendIsFarPastIt; no close on config change -> aPlanActivation..., closeAndPlanEventsEmitted; no clean-boundary episode check -> startsOnlyAtCleanBoundary; no clamp -> darkStreakIsClamped; no split -> anOversizedChunkIsSplit...; no epoch wipe -> newEpochDropsBufferAndTraces + newInstallIdResets; score listener off -> scoreEmittedForBlurFailure + 2 more; sampleEvery ignored -> exactFalseWhenDownsampled; gt for an empty flush -> gateEventsFollowTheSimulatorRules; keptAt off by 1 -> 2 tests; pause not closed -> aPrivacyPause...; cap not enforced -> theDiskCap.... Four real bugs found by the tests while writing them: pooled buffer leaked per trace (nobuffer drops), episode open at virtual t=0 reads as never opened, tmp wiped by epoch adoption in a test, pr count in totals.
`recordingDoesNotChangeWhatTheSessionDoes`: same scenario with/without recorder gives identical camera opens, keyframes and policy-tick count (S84, no extra wake-up). Existing goldens (DecisionGoldenTest, SensingUnchangedTest, ReservedAttemptTest...) stay green.

## Concerns
- Task 17 items not done: tombstones, forget ranges, `send`/transfer.
- A pause/heat close happens in the tick, before the async `endEpisode`: such a trace ends with an `ep` open that has no close and its pending flush is not recorded (the trace is already closed). Replay treats the end of trace as the end.
- A flush that keeps nothing records `fl` only (the frozen `gt` needs a seq and a Skip has none) - per coordinator rule.
- `ep` outcome uses the scheduler's view (ok/cold/busy/bind); OPEN_FAILED/NO_FRAMES are never written.
- Unplug close reason and phone wall samples: phone samples come from the `pc_trace_ctl` stamps only; `unplug` is never emitted (transfer is Task 17).
- Heat STOP closes with `stop` (no heat-specific reason exists in the frozen set).
- Service wiring and the heartbeat have no unit test (service not JVM-testable); heartbeat unchanged.
