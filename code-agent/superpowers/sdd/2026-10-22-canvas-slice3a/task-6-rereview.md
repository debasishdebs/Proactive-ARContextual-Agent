# Task 6 re-review (fix round 1, c45f69bc vs parent) - freeze gate for Tasks 16 and 20

Reviewer: Opus, read-only (no Gradle/adb/stash/commits). Read the full TraceRecords, Manifest, ClockMap, TraceFiles, ChunkCodec, the freeze/fixture tests, and cross-checked /pca-s3/c CameraPolicy, SamplingSession, KeyframeGate, GlanceAdmission, MotionClassifier, CaptureProfile.

## Verdict: NOT FROZEN YET (one narrow round: 3 wire changes + 3 doc/logic items). Everything else is FROZEN OK.

Previous C1, I2, I3, I4 and M1-M11 are closed. C2 and I1 are mostly closed with the residuals below.

## Verified closed
- C1: g/a = [tag,tMs,sMs,x,y,z]; tMs delivery order checked, sMs unordered; classifier replay can use sMs (MotionClassifier is a pure function of g/a sensor time + config; config swap only at plan change, which now closes the trace). Batch/regress tests present.
- C2 mostly: pf.quickLookMs, dk, ep.openedAtMs/outcome (all six outcomes match endEpisode: ok / open_failed+no_frames -> busy|bind|cold), sc.cap/hard, snapshot adds gate vs session keep values, lastBriefAt, stillStep, lastCheckHash, darkStreak, bindBackoffUntil, coldStartGrace, startups, pose (all 8 classifier fields), lastPhotoAttemptAtMs. Every CameraPolicy.decide parameter is derivable (profile: pf covers wake/settled/quiet/walk/stillCap/analysis; paused -> bl battery_budget).
- I2: ManifestChunk + TraceFiles layout frozen with goldens; strict canonical file decode, exact file set, epoch check.
- I3: enum names, 16 tags and field lists pinned. I4: decision documented, validator enforces pl/gp at start only, one quickLookMs; closeReason set enforced.
- Goldens: independent python recompute: payload 581 bytes, sha aefe5fc2..., foreign gzip b64 decompresses to the payload, trace sha 75c42182..., float bit values (1.0, -1.0, 9.80665, 12.5, glance 0.6/0.3/0.26/0.05/0.8, pose 0.5/-0.25/0.1/-0.1) and hex hashes all correct; golden manifest is valid sorted-key canonical JSON, 1400 bytes.
- Hostile input: dk streak and every changed field are range-checked and canonical re-encoded; the decode failure paths all end in TraceFormatException; manifest worst case (64+64 kept, 20 startups, 5 ring) is about 4.5 KB under the 8 KiB bound; no new parser-depth or memory risk.

## Remaining findings

### Important (fix before freeze; all cheap, all but #3 change the wire)
1. **Context cap/hard live only inside `sc`.** `capNow(contextCap())`/`hardNow` are also read by `onDwell` (GlanceAdmission), `timerHoldUntil` in tick, and `endEpisode`'s `flushWindow(cap, hard)`; none of those has an sc. contextCap comes from the plan's per-context table (ActiveConfig.contextCap(state)), so replay of dwell opens and timer holds needs the plan JSON resolved from `pl.configHash` (impossible for a glasses-local TRIAL/foreign plan). Fix: add the effective context cap (nullable) to `pf` (or `cx`), so `cap = min(rest/normal cap, ccap)` and `hard` are derivable from snapshot.glance + rs + pf with no plan lookup. Update FIELDS, goldens, SchedulerInputs.
2. **`dk.streak` and `snapshot.darkStreak` are capped at 1000, the live counter is unbounded.** A device left face-down or in a dark bag overnight reaches 1000 in about 4 h at the 15 s quiet cadence; `DarkStreak(streak=1001)` then throws in the recorder (or the snapshot constructor refuses). Only `>= 3` matters (`quiet`), so write in the KDoc and Task 16 text that the recorder clamps to `min(streak, 1000)` (or lower the pin to 3 and say so), and add a test.
3. **Unrecorded clock sources: define the replay tolerance.** The session uses three clocks per frame (onFrame `now`, gate `now` at offer start, emit `now` = lastKeyframeAt) plus tick `now` (motionEndAt is tick time; `atEnd = motionEndAt + hold` is an open time). The trace has only the append time `tMs` of each event, which differs by blur/hash/copy time (ms to tens of ms). `sc` KDoc says the gate `now` is "within the same lock", that is not equal to tMs. Exact bitwise equality of open times is therefore not achievable. Decide now and write into Task 20: an open is "equal" within a stated tolerance (suggest max(TICK_MS, 50 ms)) and cause equal, and `gt` outcomes equal exactly; or add `nowMs` to `sc` (one field) if exact throttle-boundary equality is required. Also note `captureNow` has no input event (only ep cause "now" and sc.force), and an encode failure after a gate keep (session lastKeyframeAt not updated) is not recorded.
4. **TraceFiles.chunkFile uses `"chunk-%04d.json".format(seq)` with the default Locale.** On a device whose locale has non-ASCII digits (ar, some others) the file names are not `[0-9]{4}`, the importer's file-set check fails and traces are unimportable. Use `String.format(Locale.ROOT, ...)` and add a test under a non-ASCII-digit default locale.

### Minor
- I1 residual (logic, not format): `ClockMap.phoneWall` prefers the intake `phoneOffsetMs` over the trace's own phone samples, which is exactly the original I1 scenario (wall corrected between recording and intake). Prefer the samples when present; keep the intake offset as fallback. The phone sample carries no age bound (latest phone `atMs`); if an uncertainty bound is wanted it needs a field now.
- `pf` doc must say whether it is the effective profile AFTER `forRest()` (the session decides with the rest-adjusted profile and `rs` is a separate event). Pin it, or replay will double apply rest.
- Initial blocked state is not carried (`bl` only on change; INITIAL_TAGS has no `bl`). Pin either "recorder starts only when unblocked" or add an initial `bl`.
- `SchedulerInputs` test is existence-only: re-pointing an input to any other existing field passes, and the input list itself is hand-written, so a new scheduler input would not fail it. Acceptable if documented; a stronger check is a Task 20 replay smoke over the Task 1 scripts.
- `TraceFiles.verify` trusts `read()` for size until after the read (MAX_FILE_CHARS is applied post-read); fine for local files, the importer should stat first.
- Spec S42 and plan Task 18 text still need the M1 rationale (not in this worktree), as the implementer reports.

## Test strength (mutation view)
Good: padding, config_change, gunzip limit, canonical re-encode, sha, header binding, wall jump refusal, closeReason set, enum/tag/field pins, golden byte equality. Weak: `SchedulerInputs` (above); no test for dk clamp (new), non-ASCII locale file names (new), phone-sample preference (new).
