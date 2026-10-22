# Task 6 fix round 1 report

Schema stays 1 (not released). Tags and fields changed deliberately; goldens regenerated deliberately (payload, sha, trace sha, manifest, python gzip b64; GlanceParams now explicit in the fixture).

Tests: `:core:capture:test` after `:core:capture:clean`: 253 tests, 0 failures (trace package 71; was 227 / 45). Mutation REDs: padding check removed -> base64MustBePadded; config_change rule removed -> aPlanOrSettingsChangeInsideATraceIsRefused.

## Done
- C1: `g`/`a` = ["g",tMs,sMs,x,y,z]; tMs delivery time (non-decreasing), sMs sensor time (unordered). Tests: batch round trip with regressing sMs, dt preserved across a batch.
- C2: pf+quickLookMs; new `dk` DarkStreak (16 tags); ep close + openedAtMs + outcome (ok|open_failed|no_frames|busy|bind|cold); sc + cap|null + hard; snapshot + gateLastKeptAt/gateKeptAt (gate values) next to lastKeyframeAt/keptAt (SESSION values, pinned in KDoc), lastBriefAt, stillStep, lastCheckHash, darkStreak, bindBackoffUntil, coldStartGrace, startups (<=20), pose (rx ry rz walked qx qy qz quietSinceMs), lastPhotoAttemptAtMs. `SchedulerInputs.ALL` lists every input read in CameraPolicy.decide / SamplingSession / KeyframeGate.offerScored with where it is carried; test checks each target exists. Not recorded by design: tick times (derived).
- I1: manifest gets startPhoneWallMs/endPhoneWallMs (nullable pair); phone offset still filled at intake; ClockMap refuses (null, wallJump=true) when offset moved > 2 s, derives phone time from the samples when no intake offset, uncertainty never null (M11).
- I2: `ManifestChunk` (kind manifest, seq 0, count 1, plain base64 of canonical text, sha over raw UTF-8, epoch must equal manifest) and `TraceFiles` (dir = traceId, manifest.json, chunk-NNNN.json, each one-line canonical TraceChunk JSON, exact file set, epoch check, streaming `verify`); goldens + tests.
- I3: TraceFormatFreezeTest pins enum names, 16 tags, field lists.
- I4: CloseReason {stop,pause,unplug,epoch,cap,reboot,plan,plan_revert,settings}; manifest enforces it; validator: pl/gp only at start, one quickLookMs per trace (reason config_change).
- M1 rationale: in Manifest KDoc. M2 count_mismatch. M3 start_state, pre_roll, close. M4 Gate rules in init. M5 padding enforced. M6/M7 KDoc (TraceEvent, ChunkCodec). M8 explicit glance. M9 bootId = decimal BOOT_COUNT (`Manifest.bootIdOf`), revision 1-based (Room 0 -> 1) in KDoc. M10 dead tests removed, Downsampler + sampleEvery test, manifest key allowlist test. M11 done.

## Not done / for the caller
- Spec S42 and plan Task 18 text are not in this worktree (docs/superpowers/specs here has no slice 3 files). Paste into both: "No per-chunk sha list: up to 4,096 chunks cannot fit one 90,000 char manifest chunk. Integrity is equivalent because traceSha256 chains every chunk's raw sha in seq order; only naming the bad chunk is lost, which is moot without per-seq resume. Task 18 'inventory complete' = every seq in [0,chunkCount) once and TraceAssembler COMPLETE."
- Validator order now: recorded_partial before start_state/config_change/pre_roll/close.
