# Wave 1 device session checklist (2026-10-15)

Build under test: branch `wave1/integration` (merged into `feat/proactive-mvp-implementation` only after this session). App v1.0.0 (`versionCode 2`), Room v2. Tag `v1-beta` = the previous build, for rollback.
Rules: never print keys or tokens; filter logcat with `grep -v -i -E "token|AuthorizationHelper"`; never toggle Wi-Fi; back up the phone's app data first (runbook "Phone app data backup").
Legend: **U** = needs the user (wear, tap, speak); **A** = I can run it over adb; **~min** = estimate.

## 0. Install (A, 10 min)
1. Back up the phone app data (tar via `run-as`, runbook) to `~/Desktop/rokid/backups/`.
2. `install -r` the phone and glasses debug APKs from `wave1/integration`; grant the overlay appop on the glasses.
3. Open the phone app. Expected: **no crash on the Room v1 → v2 migration** (encrypted database), existing memories and notes intact, Settings → Capture shows **Smart** and the new rows.

## 1. Glasses capture, desk (A + U, 20 min)
1. HUD visible, glasses on a desk, not worn, `pauseWhenNotWorn` off in Settings → Capture. Check `PcCapture` heartbeat: sampling session starts, **camera is closed when settled** and briefly opens every ~5 s; dark-scene share of camera-open time < 12 %.
2. Camera start-up: read `startupStats()` p50/p95 from the heartbeat. **Gate:** p95 ≤ 700 ms. If higher, the 1 s promise needs the brief-check lead (it is measured live) and the GlimpseOffsetTest 700 ms row applies; record the numbers.
3. Proximity: put the glasses on and off. Confirm "worn" follows the sensor (or the log says "no wear sensor").
4. LED: it lights only while the camera is open, and goes dark when settled (visual).

## 2. Glasses capture, worn (U, 25 min)
1. Walk 5 min, then stand still 3 min, then glance at 5 placed objects for ~1.5 s each. **Gate:** each object produces one keyframe (look at Activity on the phone; `motion=HEAD_MOVING/STILL`, `dwell=true` on the looks).
2. Minimise the glasses app (4 taps) and repeat 2 of the glances. Keyframes keep arriving.
3. Quick look = 1 s default. Optional: try 1.5 s and compare LED time.
4. Heat: battery temperature log every 30 s for the session. Gate: < +3 °C; work/rest: set Work 30 / Rest 5 and confirm the rest period happens (Fixed capture every 60 s during rest).
5. **Unplugged 30-minute run: battery drain** (first real measurement). Record % lost.
6. Tune from logs if needed: `BLUR_FLOOR`, motion thresholds (0.6 rad/s head, walking variance), hold time.

## 3. Phone intake (A + U, 25 min)
1. Keyframes appear in Activity within ~15 s (p50) of capture. Settings → Diagnostics: backlog by state, keyframes/h, calls/h by class, latency p50/p95. **Gate:** p50 ≤ 15 s, 95 % ≤ 45 s with provider p95 ≤ 8 s.
2. Ask "what am I looking at?" with a backlog: answer uses the latest frames (USER class).
3. A dwell look produces a nudge within ~45 s (gap and settle settings in Settings → Nudges).
4. Phone heat: log battery temperature + thermal status every 30 s during §2.5. If the phone rises > 3 °C or reports MODERATE, note it (background pause is implemented).
5. **Doze check (20 min):** phone screen off, unplugged, `adb shell dumpsys deviceidle force-idle` while keyframes arrive; afterwards no frame is stuck `EXTRACTING`; all end EXTRACTED or are retried; record latency.
6. Kill test: force-stop the phone app mid-backlog; restart; every frame ends EXTRACTED/FAILED_FINAL (none lost, no duplicates).
7. Forget one memory from a 3-photo batch; the other two frames are not stuck.

## 4. Data durability (A, 15 min)
1. Receipt durability: `DirSync` works on the phone's filesystem (no frame is ever un-acked: check glasses `PcUplink` ack lines); capture 20 frames and confirm 20 rows, files in the archive, no stray `.meta.json` or `.part` files.
2. Two drains at once: trigger Activity refresh while a backlog drains; no missing photo.
3. Delete all: frame tables cleared, notes kept.
4. Encryption on → keyframes sealed ("PCAF"); photos still show.

## 5. Nomic (A + U, optional, 25 min)
1. Settings → AI → On-device models: download **Nomic Embed v1.5** (progress, resume, SHA verify). Do not bind yet.
2. `NomicReferenceDeviceTest` (androidTest, `RealNomicDeviceRuntime` is wired): cosine >= 0.99 for 10 texts and 5 images. `NomicVisionDeviceTest`: >= 20 images/s. The test app is `com.rokid.context.services.ai.test`: push the two model files to `/data/local/tmp/pca-nomic/` and run with `-e modelsDir /data/local/tmp/pca-nomic` (or copy them into that package's `files/models` with `run-as com.rokid.context.services.ai.test`). A missing bundle shows as **skipped**, which means not run.
3. **Photo-dimension test FIRST (Ruling 17), before binding Nomic** (debug build, Developer mode on). Rule: store images at 256 only if recall@3 drops <= 3 points overall and <= 5 on the small-object subset; **default stays 768 until this runs.** The image dimension is part of the space fingerprint, so binding first and changing it later forces a second re-index.
   - Label in the app: open a photo in the frame viewer, tap **Add to photo test**, type a short query, tick "Small or edge object" when it applies. Repeat for >= 60 photos, >= 40 queries, >= 15 small/edge queries (a query may name several photos by adding the same text again). The test screen shows the counts and a **Clear labels** button. (Alternative: push `files/photo-eval/queries.json` with `run-as com.rokid.context`.)
   - The app adds up to 1,500 of your other stored photos as distractors (none within 60 s of a labelled one) and refuses with fewer than max(300, 5 x labelled).
   - Diagnostics -> "Photo search dimension test (debug)" -> Run test. Read `files/photo-eval/result.json` (numbers only: verdict, counts, hits). Verdicts: PASS_256, KEEP_768, INCONCLUSIVE (768 already >= 95% overall and on the small subset: keep 768).
   - Truncating the vision vector to 256 is not a documented Nomic property (only the text model is Matryoshka); this test is the only evidence. Record "decided on N labelled + M distractor photos" in the decision log.
   - **Before the 7th day after install** (the default Photos retention is 7 days): set Settings -> Memory -> Photos to 30, 90 or Forever, or run the photo test within the first week. The test needs >= 60 labelled photos + >= 300 distractors still on disk.
   - Label **every** near-identical photo of a scene with the same query when building the photo test (otherwise a near-duplicate counts as a distractor and a wrong hit).
   - **Before installing the wave-2 build:** run `SqlCipherMigrationDeviceTest` on the phone, and copy `databases/` and `shared_prefs/` with `run-as` (backup for rollback).
4. Only if the reference test passed: set the chosen width (`NomicVision.STORED_DIMS`, rebuild if 256), then "Use for Embeddings" -> re-index with progress; asks still work; **Roll back** appears for 7 days and returns the old space without rebuilding.

## 6. Carried from earlier device rounds (A + U, as time allows)
Activity sort and ✓ cue; minimised-link steps 5–7 (hand-back without duplicates, real exit, swipe from recents); pop-up over Maps, Confirm auto-open and re-minimise, notifications over the full HUD; VAD; photo encryption D-D4 + SqlCipherMigrationDeviceTest; battery gate banner; Battery Saver ask block; Nearby devices switch; "note that…" flow (Confirm on glasses → Notes page; Notes off → notice on glasses); D-A1 restore to automatic time. Full list: backlog §3.

## Recording results
Add pass/fail and numbers to `docs/superpowers/specs/2026-10-14-decision-log.md` (new "Wave 1 device results" section) and update backlog §3/§11. Gates that fail become fix tasks for the 1–3 pm window.
