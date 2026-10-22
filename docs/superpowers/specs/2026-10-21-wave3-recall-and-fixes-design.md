# Wave 3: image recall, plus a small-fixes bundle — design

**Date:** 2026-10-21. **Author:** Opus planner (the user was asleep; every choice below is a ruling, listed again in "Open for the user").
**Inputs:** handoff `2026-10-21-handoff.md` (§8 bugs, §9.6 wave 3, §9.8 device checks); wave-1 spec `2026-10-15-keyframe-capture-image-memory-design.md` §P5 (and §P4, §P6, §F.4); wave-2 plan `2026-10-21-wave2-scene-grouping-and-nomic.md` (Rulings 2, 11, 15, 17); canvas design `2026-10-21-experiment-canvas-design.md` R13; code at `pca-int/int` branch `int/2026-10-21` (`b2fbb0e`).
**Plan:** `docs/superpowers/plans/2026-10-21-wave3-recall-and-fixes.md`.

Format of rulings: `Ruling: <what> — <why> — <cost if wrong>`.

## 0. Round 2 (2026-10-22): Codex plan review folded, state re-checked

**Review:** `.superpowers/codex/wave3-plan-review.md` (21 findings). Each one is ruled in §7 as **Ruling S1–S21**. 20 are accepted, and S16 is accepted with one part deferred. Where §7 and §1–§6 differ, §7 wins.

**Changes in round 2:**
1. **Base moved.** Wave 2 (Room v4), A3 and the canvas MVP (v3) are merged in `bundle/2026-10-21` @`81912dc`. `ctx/int` @`6187727` adds the wave B fast path on top, and that is the build installed on the phone. Wave 3 branches from the current integration tip (`ctx/int` or its successor) and no longer hedges on "if wave 2 / A3 hasn't merged".
2. **Room numbering.** v3 is the canvas MVP, v4 is wave 2, v5 is reserved for wave B Task 8, and v6 and up are expected for canvas slice 2 and 3. Wave 3 still plans no migration (R7). If one turns out to be needed, it takes **the next free version at merge time**, never a hardcoded number (S17).
3. **Wave 2's retention rules are now facts in the code** (`ForgetDao`):
   - a skipped photo needs `SCORED_LOW` (a real salience below 0.5; NULL means "never scored", not "skipped") or `lastError = 'caption_expired'`, plus `dwell = 0`, not a representative, and a settled cluster;
   - deadline-based photo expiry passes `requireLinked`, so an undescribed photo is never deleted by the photo window. Unscored legacy photos are described before delete.
   - Wave 3's describe and Forget must keep these rules true (S3, S4, S2).
4. **Forget covers photo cites.** Explicit Forget now redacts asks that cite `frame:<id>`, and it can remove a moment that only has metadata and a vector left (S1, S2, new Task 11).
5. **Correctness fixes from the review:**
   - recall takes the ACTIVE target by flag (S7);
   - recall gets a retrieval-limit seam above the UI cap of 20 (S8);
   - an expired photo's time is shown as "received" (S9);
   - each image is mapped to its cite explicitly (S10);
   - search results are invalidated when the data changes (S11);
   - describe returns typed outcomes per frame (S5) and uses full-UUID ids (S6);
   - the call-end settle is stamped on the move into NORMAL, on a monotonic clock (S12);
   - WorkRest checks a boot identity (S13);
   - the force-stop classifier reports UNKNOWN separately and needs a fresh exit timestamp (S14).
6. **Module and test corrections:**
   - `WearingStatus.kt` is in `:services:glasses-link` (S15);
   - the canvas D26, D36 and D39 tests exercise behaviour, not a source scan (S16);
   - the "other force-stops" count is published in a count-only diag line (S20).
7. **User rulings now in force:**
   - **intent never throttles capture.** Nothing in wave 3 reads intent or vehicle context to pause or slow capture. WorkRest is the user's own schedule, not intent;
   - **use open-source libraries instead of custom code where possible** (§8);
   - **estimates are in agent-hours** (plan, ordered task list).
8. **Nomic:** the v1.5 bundle, its ONNX runtime and the device harness (`NomicReferenceDeviceTest`, `RealNomicDeviceRuntime`) exist. Nomic is still **not bound** as the embedder. Until it is, recall runs captions only (R9) and Task 10's image steps wait.

## 1. Goal

- **(A) Image recall (spec §P5).** "Find the photo of …" turns words into a search over the keyframe photos, and the Ask path uses the same search. The answer can cite a photo ("where did I leave my keys" → "On the hall shelf, 14:02", with the photo shown on the phone). "Describe on demand" runs the vision extraction on a photo or a scene the user picks.
- **(B) Small fixes:** NOT1, WR, UI1, PERM, DIAG, REF, the storage quota default, a backup-rules check, and a handful of safe deferred minors.

**Builds on wave 2. Those tasks land first:**
- image vectors in `frame_vector` under the ACTIVE space (Task 4, `ImageSpaces`);
- photo expiry that keeps meta, vector and captions (Task 8, Ruling 11);
- Nomic bound as an IMAGE-capable embedder (Task 10).

Before Nomic is bound, recall runs on captions only.

## 2. What exists (facts from the code)

- **Ask path:**
  - `AnswerService.answerOrThrow`: `beforeRetrieve` (ask-time extraction) → `retrieve` (`MemoryRetriever`: BM25 plus semantic, recency, top-k) → one ANSWER `RoleCall` (`inputIds` = retrieved memory ids, class USER) → an `ask` row, behind the wipe and `ForgetFence` checks.
  - `RoleCall.images` exists already. `ModelRouter` strips images when the endpoint has no VISION.
- **Vector search today:** `BruteForceVectorIndex` streams `memory_vector` in rowid pages of 500 into a bounded heap. This is the pattern image recall reuses.
- **Photo vectors:** `frame_vector(frameId, spaceId, blob)`, a rowid table with an index on `spaceId`.
  - Image dims come from `ImageSpaceTarget.imageDims`.
  - The query is `embedQueryFull` → first `imageDims` → L2, the same transform `WatchVectors` uses.
- **Photos:**
  - UI images go through `FrameDecode.opener`, which opens sealed (PCAF) frames in memory.
  - AI images go through `FramePhotos.load` → `FrameInputs.fromFrames`, also in memory.
  - Neither writes a decrypted file.
- **Extraction:**
  - `ExtractSave.extract(runner, photos, callClass)` takes at most 3 photos per call.
  - `ExtractSave.saveExtraction(…, claims = null)` skips the claim CAS but keeps the wipe check, the Forget fence and the provenance rows.
  - The ask claim is `FrameStateDao.claimForAsk`: PENDING, or IN_CLUSTER in an OPEN/CLOSED cluster. The caption queue's claim is `claimCaptionQueued`.
- **HUD answer:** `Overlay.Answer(question, text, speaking)` is built in `VoicePipeline.overlayFor`. TTS speaks `VoiceState.Answer.text`.
- **Ask detail:** `AskDetailModel.build` maps cited memory ids to `SourceCard(framePath)`. Unknown ids count as `missingSources`.

## 3. Image recall design

### 3.1 Search (`PhotoRecall`, phone, `:services:orchestration`)

- **Input:** query text. **Output:** two separate lists plus a state.
  - **Image hits:** cosine between the query vector and photo vectors, top 24.
  - **Caption hits:** top 24, from frames not already in the image list.
  - **State:** `RAN | NO_IMAGE_SPACE | FAILED | TIMED_OUT`, with `scanned` and `truncated`.
- **Ruling R1: no ANN index. Brute-force cosine in a tight loop.**
  - The scan is paged by `frame_vector.rowid DESC` (the most recently indexed vectors first, which is not always the newest captures after a re-index; S21), 500 per page, and joined to `frame_meta` (forgotten frames have no meta).
  - It decodes into one reused buffer and keeps a bounded top-k heap.
  - It stops at **50 000 vectors scanned** or **2 000 ms**, whichever comes first, and reports `truncated`.
  - Why: 20 000 × 768 float32 is about 60 MB to stream and about 15 M multiply-adds, well under a second on the S931B. Today's store is about 3 000 photos, and wave 2's dedupe and the 1-day skipped-photo rule keep it small. An ANN index (HNSW) is a native dependency plus an index to keep in sync with retention.
  - Cost if wrong: a slow search past about 50 000 photos. The cap keeps it bounded (newest first), and the scan sits behind an interface, so an ANN index can replace it later.
- **Ruling R2: no score merging (user decision 3, 2026-10-15).**
  - Image hits and caption hits stay separate lists, each with its own score and source badge. The grid shows image matches first, then caption matches.
  - The Ask path takes **2 photo hits** next to the usual text memories, and the answer model judges relevance.
  - Cost if wrong: a strong caption match can sit below a weak image match in the grid. The badges and scores make that visible.
- **Ruling R3: the caption fallback reuses the existing retriever.**
  - It calls `MemoryRetriever.retrieve` (the user's retrieval mode, BM25 / semantic / hybrid) with a pool of 60. It keeps hits whose memory has a `frameId` (captions, facts, cluster summaries, whose `frameId` is the medoid), keeps the best score per frame, and drops frames already in the image list.
  - Why: no new FTS index. Captions are memories already.
  - Cost if wrong: cluster summaries map to one photo (the medoid) only.
- **Ruling R9: image hits need an ACTIVE space whose embedder declares `Modality.IMAGE`** (wave-2 Ruling 15). Otherwise the state is `NO_IMAGE_SPACE` and only caption hits are returned.
  - The screen then shows the F.4 banner: "Searching captions only. For photo search, turn on Nomic in Settings → AI → On-device models."
  - Cost if wrong: none. It is the honest fallback.
- **Enrichment for each hit:**
  - frame row present (photo stored) or absent (**"Photo expired"**, wave-2 Ruling 11: meta, vector and caption are kept);
  - capture time (`frame.ts`, or `frame_meta.createdAt` once expired);
  - **described** (✓): a live memory with `frameId` = the frame, or a `frame_source` row of role FACT or CLUSTER_REP;
  - the newest caption memory (id and text);
  - `clusterId`.
- **Privacy:**
  - Queries are never stored (except a voice or typed Ask, which is an `ask` row as today) and never logged.
  - Logs are count-only: `PcRecall img=… cap=… scanned=… ms=… state=…`.
  - Photos are decrypted only in memory, through the existing openers. No thumbnail cache is written to disk.
  - Forget wins: forgotten frames have no meta or vector, and forgotten caption memories are INVALIDATED and filtered out by the retriever.

### 3.2 Photo search screen (phone)

- **Route:** `photos?q={q}`.
- **Entry points:**
  - a search icon in the Activity top bar (`photo_search`);
  - "More photos" on an Ask detail (`more_photos`, prefilled with the question).
- **Ruling R8:** search runs on submit, not on each keystroke, and keeps no search history.
  - Why: each search embeds the query and scans the vectors; history would be a new store of user text.
  - Cost if wrong: one tap more.
- **Grid** (3 columns, `FrameThumb`). Each tile shows:
  - the time ("Tue 14:02");
  - the score (2 decimals);
  - a badge, Image or Caption;
  - ✓ when described;
  - for an expired photo, a "Photo expired" tile with 2 lines of caption.
  - The truncated note: "Searched the newest N photos".
- **Tapping a tile** opens a sheet:
  - **Open:** the frame viewer at that frame, or the caption's memory detail when the photo has expired;
  - **Describe this photo;**
  - **Describe this scene** (only with a `clusterId`).
  - Describe is disabled on an expired photo.

### 3.3 Describe on demand

- **Ruling R6:** describe runs **VISION_EXTRACT** on 1–3 photos under **CallClass.USER**, and saves ordinary memories through `ExtractSave`. "Describe this scene" sends the cluster's representatives (`repFrameIds`, at most 3; if there are none, the medoid then the newest members), still through VISION_EXTRACT rather than CLUSTER_SUMMARY.
  - **Not yet extracted** (PENDING, IN_CLUSTER of a cluster that isn't SUMMARIZING, CAPTION_QUEUED): the frame is claimed through the pipeline's existing ask and caption claims and runs the ordinary batch path with `user = true`. The CAS, recovery and follow-up stay as they are.
  - **EXTRACTING** (held by someone else) → **Busy**: "Already being described, check back in a minute."
  - **Anything else** (EXTRACTED, CLUSTERED, NEAR_DUP, FAILED, FAILED_FINAL, IN_CLUSTER of a SUMMARIZING cluster):
    - a no-claim save (`claims = null`) with memory ids `rokid_desc_<8 hex>_<n>` (they start with `rokid_`, never `rokid_review_`);
    - then a guarded `move` of the photo's state from {NEAR_DUP, CLUSTERED, FAILED, FAILED_FINAL} to EXTRACTED, with lastError `described_on_demand`. **A photo the user described therefore leaves the 1-day skipped-photo rule** and follows normal photo retention.
  - **Expired** (no frame row) → "Photo expired". **Wiped** during the call → nothing saved.
  - Why: VISION_EXTRACT already has the provenance contract and the per-photo caption, and CLUSTER_SUMMARY's claim lives on the cluster row, which a user call must not take.
  - Cost if wrong: "Describe again" on an extracted photo adds a second set of facts (allowed: the user asked for it), and a scene description is per-photo rather than one summary.
- **Budget:** USER bypasses CallBudget, as asks do. Each tap is at most 3 photos, and the button is disabled while a describe is running (one at a time).

### 3.4 Ask integration

- **Ruling R4:** `AnswerService.retrieve` also asks `PhotoRecall` for **2 image hits** (image only, budget 1 500 ms; on a timeout or failure no photos are added and the ask goes on).
  - **Each photo becomes one line** in the memories data block: `id=<cite> · <time> · photo · <caption clip | "no caption yet">`.
    - `<cite>` is the photo's newest caption memory id, or `frame:<frameId>` when there is no caption.
    - These ids join `inputIds`, so the answer may cite them.
    - Lines are kept in budget: the text lines are bounded first to `5 000 − photo line chars`.
  - **Up to 2 photo images** (stored, readable) are attached as `RoleCall.images` (low detail). `ModelRouter` drops them on an endpoint without VISION.
  - **An ask with photo hits but no text hits is not "no memories".**
  - **ForgetFence** treats `frame:<id>` as live while the frame's `frame_meta` row exists (Forget deletes it; expiry keeps it).
  - **The ANSWER template is unchanged:** the photo lines sit in the existing data block.
  - Why: user decision 3 ("6 text + 2 photos to the answer AI") and the spec ("top photo hits feed ANSWER as images"), with no prompt migration.
  - Cost if wrong: about 2 extra low-detail images per ask on a vision endpoint (latency and tokens).
- **Ruling R5: the glasses card shows the photo time phone-side.** `AskOutcome.Answered` gains the first cited photo (frameId, capture time). The voice path appends `" · Photo 14:02"` to the HUD text only, in `Overlay.Answer.text`, after clipping to fit `HUD_ANSWER`. TTS still speaks the plain answer.
  - Why: no protocol field and no glasses change (`PROTOCOL_VERSION` 1 untouched, and the A3 branch is editing the glasses code). The spec's "📷" glyph may not render on the glasses font.
  - Cost if wrong: if the user wants an icon, it becomes an additive `Overlay.Answer` field later.
- **Ask detail:**
  - A `frame:<id>` source renders as a photo card (the photo, or "Photo expired"). It is not counted as a missing source.
  - Photo-backed cards show a larger thumbnail.
  - "More photos" opens the search.
- **Ruling R10:** the P5 debug view's Ask half (each retrieved item with score and source, whether it reached the prompt) is **out of scope**. The canvas owns it: canvas design R13 puts it in a later slice.

### 3.5 Storage and schema

- **Ruling R7: no Room change and no protocol change in this wave.**
  - Every new query reads existing tables (`frame_vector` rowid, `frame_meta`, `frame_source`, `memory`). The HUD label is text.
  - If a task finds it needs a schema change, it takes **the next free Room version at merge time** (v3 canvas MVP, v4 wave 2 and v5 wave B Task 8 are taken, and canvas slice 2/3 hold the next ones), additive only. See S17.
  - Cost if wrong: none.

## 4. Small-fixes bundle

### 4.1 NOT1: a dedicated "Open on glasses" notification
- Today "Open on glasses" exists only as the in-app banner (`Banners.kt`, `CLOSED_ON_GLASSES`).
- **Ruling R11:** when the link has been `CLOSED_ON_GLASSES` for **45 s** without a break (the relauncher's first reopen comes at 8 s, so a successful auto-reopen cancels it first), the phone posts **one silent notification**.
  - Text: "Closed on glasses · capture stopped". Action: "Open on glasses", the same `requestReconnect()` path as the banner.
  - Channel `glasses_closed` ("Closed on glasses", IMPORTANCE_LOW), id 32.
  - It is cancelled on any other link state, on the action, or when dismissed. At most once per closed episode.
  - It also shows after a deliberate wearer exit. Why: it is silent, and it is the fastest way back.
  - Cost if wrong: one extra notification in the shade after an exit the wearer meant; dismissing it ends the episode.

### 4.2 WR: WorkRest survives process death
- **Ruling R13:** the WorkRest state (`workStartMs`, `pausedAtMs`, rest end) is persisted on the glasses in prefs `work_rest` on each change, keyed to `elapsedRealtime`.
  - On load, a saved value later than the current `elapsedRealtime` means a reboot, and the state is discarded.
  - With A3's `enabled` switch off, the store is cleared and nothing is written.
  - Why: a Rokid force-stop during a rest otherwise restarts a full work period, and capture runs past the rest the user chose.
  - Cost if wrong: one prefs write per phase change.

### 4.3 UI1 and REF: start-up flash and launcher detection
- **Ruling R14:** when `AutoStartGate` decides to minimise in `onStart`, the activity sets a `hudHidden` state **before the first frame**, so `Hud()` draws nothing (black is transparent on the glasses), and then minimises as today. The state clears on any start that shows the HUD.
  - The rarer "pc_auto_open marker arrives after a launcher-looking start" case still shows the HUD until the marker arrives. That case is left alone.
  - Cost if wrong: that case still flashes for up to about 1 s.
- **REF:** the gate returns a `StartDecision` (minimise, plus the reasons). `MainActivity` writes one count-only line to `capture_diag.log`, for example `start src=LAUNCHER ref=launcher|other|none pending=0|1 marker=0|1 fromMin=0|1 extra=0|1 minimise=0|1`. The referrer is a class only, never the package name.
  - A table-driven test pins every combination.

### 4.4 PERM: READ_PHONE_STATE
- **Ruling R12: no, the app does not request READ_PHONE_STATE.**
  - Android shows it as "make and manage phone calls", which is alarming for a memory app.
  - The guard only decides whether to *reopen the glasses app in icon mode*.
  - The audio mode already covers ringing (`MODE_RINGTONE`), cellular calls and most VoIP (`MODE_IN_CALL` / `MODE_IN_COMMUNICATION`).
- **Implemented path:**
  - `AndroidRelaunchGuards` tracks the last time the audio mode was not NORMAL (`AudioManager.addOnModeChangedListener` on API 31+, plus each poll).
  - Idle needs NORMAL, at least **15 s** since the last non-NORMAL mode (`CALL_END_SETTLE_MS`), and the existing 30 s hold after a scene event.
  - The opportunistic `callState` read stays for the case where a merged library declares the permission and it was granted.
- Cost if wrong: a VoIP app that never sets the audio mode, on a call longer than 30 s, may see the glasses app come back *in icon mode* during the call (never full screen).

### 4.5 DIAG: "Rokid closed the app N times"
- **Ruling R15:** classify each `REASON_USER_REQUESTED` exit by its caller.
  - The `ApplicationExitInfo` description carries `from pid <N>` for a force-stop. The caller's `/proc/<N>/cmdline` is read once at the next start.
  - `com.rokid.*` → **Rokid**.
  - A readable non-Rokid process, or a pid that is gone (adb's short-lived `am`) → **other** (not counted as Rokid; a separate count).
  - No pid, or unreadable → **unknown**, counted as Rokid as today (the best Android offers).
  - The restart gap uses the exit's own `timestamp` when it is newer than the last heartbeat (exact, instead of up to 30 s overstated).
  - The summary becomes "Rokid closed the app N times (last gap Xs; M other force-stops not counted)". The raw description is never logged.
  - Cost if wrong: Diagnostics only. The device checklist verifies an adb force-stop goes to "other" and a call goes to "Rokid".

### 4.6 Storage warning
- **Ruling R16:** the default soft limit becomes **250 MB** (`MemorySettings.quotaMb` and the prefs read default). A value the user set explicitly is kept.
  - The storage banner reads "Memory storage N% full · raise the limit in Settings → Memory".
  - Cost if wrong: the banner shows later for users who never set a limit.

### 4.7 Backup rules
- New prefs in this wave: glasses `work_rest` only (glasses backup is off and excludes every domain). The phone adds no prefs.
- The task re-runs `BackupRulesTest.everyPrefsFileIsRuledOn` and `GlassesBackupRulesTest`, and adds `work_rest` to any glasses list that enumerates names.

### 4.8 Deferred minors (from the wave-1, battery A/A2/A3 and canvas ledgers)

**Ruling R17. Taken now** (each safe, at most about 20 lines):
- A3: tighten the wearingStatus log to a token allow-list (no free text);
- A19: heartbeat `sensors=off` with no session;
- A20: a heartbeat throw must not skip the status line;
- A21: `battery-run.sh` awk `FILENAME == ARGV[1]`;
- A22: pipe the pulled diag logs through the deny filter (secrets);
- A23: don't cache a failed fps-range read;
- D13: skip the canvas critic call on an empty fact list (saves an AI call);
- D26: security headers on the canvas catch-path responses;
- D30: `saveDraft` returns a fixed text, not the decoder reason;
- D39: the canvas page survives a malformed `#t=`;
- D36: a failed poll retries with back-off.

**Not now:**
- A10, B10, B11 (test or API redesign);
- A12, B12, B15 (accepted);
- A28 (log size trade-off);
- B3, B5, B19, B21, B22 (A3 territory or a user decision);
- B20 (ordering, timing-sensitive);
- C1–C4 (A3 still in review);
- D16, D20, D21, D25, D28, D33, D34, D38, D45–D48, D50 (canvas slice 2+, concurrency or security design);
- E1 (check against wave A first);
- E2, E4–E7, E9, E11 (design or device work);
- the cosmetic test and KDoc nits (A1, A2, A4–A9, A11, A13–A18, A24–A27, A29, B2, B4, B6–B9, B13, B14, B16–B18, D1–D12, D14, D15, D17–D19, D22–D24, D27, D29, D31, D32, D35, D37, D40, D42–D44, D49);
- E10 is already fixed (`Hub.kt:231` clears `iconBlocked` on link loss).

## 5. Tests (summary; exact names in the plan)
- **Recall:**
  - the pure top-k scan (order, ties, cap, budget, wrong-dimension rows skipped);
  - newest-first paging and forgotten frames excluded;
  - the caption fallback dedupes by frame and leaves out image-list frames;
  - expired photos come back with their caption;
  - a log carries no query text.
- **Describe:** claim path vs no-claim path, Busy, Expired, Wiped, the guarded move out of NEAR_DUP, USER class, ids prefixed `rokid_desc_`.
- **Ask:**
  - 2 photo lines and images;
  - a photo-only ask answers;
  - `frame:` cites pass ForgetFence until Forget;
  - the HUD label is appended but not spoken;
  - Ask detail renders photo and expired cards.
- **Bundle:** pure decision functions for NOT1, PERM, DIAG and the REF/UI1 gate; WorkRest persistence across a simulated process restart and a reboot; quota default and banner text.
- **Device checklist (last task):** recall with Nomic bound, voice "where did I leave my keys", describe, an expired photo, NOT1, a call during a closed app, WR kill during a rest, the UI1 visual, the REF log line, DIAG adb vs Rokid.

## 6. Open for the user
Round 1 (R-rulings, still open):
1. R12: READ_PHONE_STATE stays off (audio mode plus a 15 s settle). Say if you'd rather grant it for a precise guard.
2. R11: the "Open on glasses" notification also appears after your own deliberate exit (silent, once).
3. R5: the glasses card shows "· Photo 14:02" as text, not an icon.
4. R6: "Describe again" on a photo that already has a caption adds a second set of facts. A photo you describe is kept as a normal photo (7 days), not deleted at 1 day as a skipped photo.
5. R1: brute-force search, capped at 50 000 photos or 2 s, most recently indexed first. Fine for years at today's rate. An open-source ANN index can replace it later (§8).
6. R14: a phone reopen that looks like a launcher open can still show the HUD for up to about 1 s until the phone's marker arrives.
7. R16: default storage limit is 250 MB. You already set 250 by hand, so nothing changes for you.
8. R10: the Ask half of the debug view (scores per retrieved item) waits for the canvas.

Round 2 (new):

9. S9: for an expired photo, "Photo expired" tiles and Ask lines show the time the phone **received** it ("received 14:02"), not the capture time. A Room column for the real capture time would need a migration, which this wave doesn't add. Say if you want `frame_meta.capturedAt` in the next free migration.
10. S14: Diagnostics gets three counts, "Rokid closed N", "M unattributed" and "K other force-stops". An `adb force-stop` may now land in *unattributed* instead of *other*, because a pid that is gone can't be verified. Say if you'd rather count an unverified pid as Rokid, as today.
11. S1: Forget on a moment also clears saved answers that cited that photo, and their proposals and alarms. This matches Forget on a fact.
12. S16: the D36 and D39 canvas fixes need the GraalJS harness, which is on `canvas/integration` (`d21d66b`) and not yet in the base. They move to canvas slice 2 unless that harness merges first.
13. S20: the "other force-stops" count shows in `capture_diag.log` only, not on the phone. Putting it on the phone Diagnostics screen needs an additive hello field and UI work: say if you want it.

## 7. Round 2 rulings on the Codex plan review (S1–S21)

Format: `Ruling Sn: <what> — <why> — <cost if wrong>`. Accept by default. The plan tasks named in brackets carry the change.

- **Ruling S1 (accept, Critical) [Task 11]:** explicit Forget redacts every ask whose cited sources or inputIds contain `frame:<id>` for the forgotten moment, and scrubs their proposals and alarms. It uses the same redaction path as memory ids, and export reads the redacted row. Test: answer commits → Forget moment → the answer and proposal are redacted, export included. — Why: `cascade()` only matches memory ids, and the fence only covers answers still being generated. — Cost if wrong: a forgotten photo stays quoted in a saved answer.
- **Ruling S2 (accept, Critical) [Task 11]:** `forgetMoment` treats a frame with only `frame_meta`, a `frame_vector` row and no `frame` row and no memories as a real moment. It deletes the meta, vector, state, source and canvas rows, writes the tombstone, and redacts `frame:` cites (S1). `EMPTY` is returned only when no frame, meta, vector or memory exists. Test: a skipped photo expires without a caption → Forget → no search hit and no live cite. — Why: skipped photos (`SCORED_LOW`) expire at 1 day without a description and keep meta and vector. — Cost if wrong: unforgettable searchable data.
- **Ruling S3 (accept) [Task 2]:** a frame the user described carries a durable mark: its `frame_source` FACT rows from the describe. `ForgetDao.SKIPPED_PHOTO` (and the skipped clause inside `PHOTO_ELIGIBLE`) gains `AND NOT EXISTS (SELECT 1 FROM frame_source d WHERE d.frameId = f.id AND d.role = 'FACT')`. So cluster settlement moving an `IN_CLUSTER` member to `CLUSTERED` can't put it back under the 1-day rule. Test: describe a SUMMARIZING member → settle the cluster → run retention → it follows the photo window. — Why: a state move alone is undone by settlement. — Cost if wrong: a photo the user described is deleted at 1 day.
- **Ruling S4 (accept) [Task 2]:**
  - describe saves its memories and moves the state in **one transaction**;
  - `expirePhotos` gains `recheckSkipped: Boolean`. With it set, it re-evaluates the `SKIPPED_PHOTO` predicate for each id inside its own transaction and skips any id that no longer qualifies.
  - Test: an interleaving where retention pauses after selection, describe commits, then retention resumes → the photo is kept.
  - Why: retention selects the photo and then deletes it later. — Cost if wrong: a race that deletes a photo the user just described.
- **Ruling S5 (accept) [Task 2]:** `KeyframePipeline.extractNow` returns `Map<String, ExtractNowOutcome>` with `Extracted(memories)`, `ExtractedEmpty`, `LostClaim`, `Failed(kind)` and `Wiped`. A new `Stale` is a claim lost AFTER the model call (a Forget of another photo in the batch, a claim timeout): Busy, never a second call. Only never-claimed (`LostClaim`) frames are re-read: EXTRACTING, PENDING or CAPTION_QUEUED → Busy (the pipeline owns them); a state nobody owns → the no-claim path. (Fix round 1 ruling: a second call after a stale claim would be a second charge and leave the frame PENDING for a third.) `Failed` → Unavailable. `Wiped` stays distinct from a stale claim. — Why: a plain `Set` can't tell these apart, and that leads to double calls or bypassing another extractor. — Cost if wrong: duplicated paid calls.
- **Ruling S6 (accept) [Task 2]:** describe ids are `rokid_desc_<uuid>_<n>` and `rokid_desc_<uuid>_c<n>` with a full `UUID.randomUUID()` (36 characters). Test: two describes with an injected id source that collides on the first 8 hex characters → no overwrite. — Why: `appendFact` upserts by id, and `factPrefix()` documents a past collision. — Cost if wrong: silent loss of facts.
- **Ruling S7 (accept) [Task 1]:** `images.targets().firstOrNull { it.active }`. A list with only BUILDING → `NO_IMAGE_SPACE`. Test included. — Why: `SpaceImageSpaces.targets()` drops a text-only ACTIVE space. — Cost if wrong: search runs against a half-built space.
- **Ruling S8 (accept) [Task 1]:** an internal retrieval-limit seam, `MemoryRetriever.retrieve(…, poolOverride: Int? = null)` (internal, bounded at 200), that bypasses `RetrievalSettings.clamped()`'s `topK ≤ 20` for this caller only. UI settings are unchanged. Test with the **real** retriever: a photo-backed hit ranked 25th is returned. — Why: R3's pool of 60 is silently cut to 20. — Cost if wrong: caption recall misses photos.
- **Ruling S9 (accept, the no-migration option) [Tasks 1, 3, 4]:**
  - `PhotoHit.ts` is `frame.ts` (capture time) when the photo is stored. Otherwise it is `null`, and a new field `receivedAt = frame_meta.createdAt` is set.
  - The UI and Ask lines say "received HH:mm" for expired photos.
  - The HUD `· Photo HH:mm` label is only for stored photos.
  - Test: capture and receipt several hours apart → the expired line shows the receipt time, labelled as such.
  - Why: delivery can be hours late (handoff §6), and a real capture-time column needs a migration (open item 9). — Cost if wrong: weaker "when" evidence for expired photos.
- **Ruling S10 (accept) [Task 3]:** images are assembled by walking the `PhotoCite` list in order and loading each stored photo. Unreadable ones are skipped, and the prompt line of each attached photo gets `image=<n>` (1-based index into `RoleCall.images`). No reliance on map order. Tests: reversed map order; an unreadable first photo → the second gets `image=1`. — Why: positional mismatch cites the wrong photo. — Cost if wrong: wrong "where" answers.
- **Ruling S11 (accept) [Task 4]:**
  - The ViewModel observes Room invalidation for `frame`, `frame_meta`, `memory` and `frame_source` (Room's `InvalidationTracker`/Flow; no custom watcher). On a change it re-enriches the visible ids and drops vanished ones.
  - Each search carries a generation number, and an older completion is discarded.
  - Tests: Forget while visible; return from Forget; a successful describe updates ✓.
  - Why: stale captions, thumbnails and ✓ after Forget. — Cost if wrong: forgotten data stays on screen.
- **Ruling S12 (accept) [Task 5]:** the guard stamps `normalSinceMs` on the **transition into** NORMAL from any other mode (listener and poll), using `SystemClock.elapsedRealtime`. `callIdle` requires `now − normalSinceMs ≥ CALL_END_SETTLE_MS`. Test: hang up just before the next poll → not idle until 15 s after the transition, driven through the guard rather than a precomputed duration. — Why: stamping only non-NORMAL samples can miss the end. — Cost if wrong: a reopen during the last seconds of a call.
- **Ruling S13 (accept) [Task 6]:** the snapshot stores `bootCount = Settings.Global.BOOT_COUNT` (−1 if unreadable). On load, it is discarded when the boot count differs, **or** (only when either count is −1) when `savedAtElapsedMs > clock()`. Test: a reboot whose new uptime exceeds `savedAtElapsedMs` → discarded. — Why: an uptime comparison alone misses long uptimes. — Cost if wrong: a stale rest after a reboot.
- **Ruling S14 (accept) [Task 8]:**
  - `ExitKind { ROKID, OTHER, UNKNOWN }`. ROKID only if `/proc/<pid>/cmdline` is readable **and** starts with `com.rokid.`, and that process's start time (from `/proc/<pid>/stat`) is earlier than the exit timestamp (rules out pid reuse).
  - OTHER only if it is readable with that same start-time check and the name is not Rokid.
  - Absent, hidden, or reused → UNKNOWN.
  - An exit counts only when its timestamp falls in `(lastAliveMs − 5 s, now]` (fresh); otherwise it is ignored.
  - UNKNOWN is counted and reported separately ("M unattributed") and no longer added to the Rokid count (open item 10).
  - Tests: hidden, reused and stale pids.
  - Why: Android's `/proc` visibility restriction and pid reuse. — Cost if wrong: misleading Diagnostics only.
- **Ruling S15 (accept) [Task 9]:** A3-minor is in `:services:glasses-link` (`services/glasses-link/src/main/kotlin/com/rokid/context/link/WearingStatus.kt`, `SAFE` regex). The module constraint allows this one file, and the test runs with `:services:glasses-link:testDebugUnitTest --tests '*WearingStatus*'`. — Why: wrong module. — Cost if wrong: the free-text log stays.
- **Ruling S16 (accept; D36/D39 deferred if no harness) [Task 9]:**
  - D26 is tested through `CanvasServer.Daemon.serve()` with an adapter or conversion failure. Its uncovered catch is there; `CanvasRouter` already adds headers.
  - D36 and D39 are tested by running the JS in the GraalJS harness, with malformed fragments and fake timers that cover recovery, backoff reset, cancellation and stopping on 401/403.
  - The harness is on `canvas/integration` (`d21d66b`), not in the base. If it hasn't merged when Task 9 starts, D36 and D39 move to canvas slice 2 (open item 12).
  - Why: a source scan proves nothing. — Cost if wrong: two small JS fixes wait a slice.
- **Ruling S17 (accept) [Global]:** v3 is the canvas MVP, v4 wave 2, v5 wave B Task 8, and canvas slice 2/3 take the next ones. Any wave-3 migration takes the next free version **at merge time** and follows all reservations: both builders, the exported schema and a `MigrationTestHelper` test from the previous head. — Why: v5 collides with wave B. — Cost if wrong: a broken upgrade path.
- **Ruling S18 (accept) [Task 10]:** before install, read the installed phone schema version (`PRAGMA user_version` on a **copy** of the encrypted backup, through the handoff's `SqlCipherMigrationDeviceTest` path; counts and version only). If the new build crosses a version, run that migration test on the copy first. Count comparisons pause retention and ingest (stop the night job and disconnect the glasses) for the before and after reads. — Why: one install can cross several versions. — Cost if wrong: data loss on install.
- **Ruling S19 (accept) [Tasks 2, 4]:**
  - `PhotoTile` carries `captionMemoryId: String?`. "Open" goes to the frame viewer when the photo is stored, else to `memory/{captionMemoryId}`, and is **disabled** when both are missing (the tile still shows "Photo expired · no caption").
  - Scene fallback: with no `repFrameIds`, use the members' ACTIVE-space image vectors and the existing `SceneRules` representative picker (medoid = max mean cosine). With no vectors, take the newest members by `frame.ts`. Up to 3 either way.
  - Why: an undefined algorithm and dead navigation. — Cost if wrong: none.
- **Ruling S20 (accept, the count-only option) [Tasks 8, 10]:** Task 8 writes `restart … rokid=N unknown=M other=K` (counts only) to `capture_diag.log` on each start. Task 10 checks that line. No transport, hello or phone UI change (open item 13). — Why: `summary()` has no production caller and the phone doesn't receive the count. — Cost if wrong: the count is visible only in pulled logs.
- **Ruling S21 (accept) [Tasks 1, 7]:**
  - Task 1's ordering is "most recently indexed", by `frame_vector.rowid DESC`. The test is renamed `pagesByVectorRowidDesc`, and the note text says "Searched the N most recently indexed photos".
  - Task 7 compares `decide()` and `onStart()` on **separate** gate instances built with identical state, because both consume the one-shot pending flag.
  - Why: the tests encoded the wrong assumptions. — Cost if wrong: none.

## 8. Open-source first (user ruling, 2026-10-22)

Prefer a maintained library or platform API to custom code. Applied here:

| Need | Use | Not |
|---|---|---|
| Bounded top-k heap | `java.util.PriorityQueue`, or the existing `BruteForceVectorIndex` heap if it can be extracted | a hand-written heap |
| Future ANN, if the scan cap is reached | evaluate USearch (Apache-2.0) or sqlite-vec (MIT/Apache) behind `ImageScan`'s interface | — (not in this wave; R1) |
| Result invalidation | Room `InvalidationTracker` / `Flow` | a custom change bus |
| Notification | `NotificationCompat` / `NotificationManagerCompat` (AndroidX) | raw builders |
| URL encoding of `q` | `android.net.Uri.encode` | manual escaping |
| Reboot identity | `Settings.Global.BOOT_COUNT` | a custom boot marker file |
| Representative or medoid | the existing `SceneRules` picker | a new medoid routine |
| JS behaviour tests | the existing GraalJS harness (`d21d66b`) | source scans |

Each implementer report names any place where it wrote custom code although a library was available, and says why.

## 9. User rulings (2026-10-22, answers to the round 2 open list)
1. Capture time: YES, add a capture-time column. Club it into the SAME Room migration as wave B Task 8's intent columns (v5, one migration, one schema JSON, one SqlCipher migration test) instead of a separate version; the expired-photo row shows the capture time.
2. Unverifiable force-stops get their OWN Diagnostics category (not Rokid, not "other"): Rokid / unverified / other.
3. Forget on a photo also redacts saved answers that cited it (S1 stands as written).
4. D36 (failed poll retries with back-off) and D39 (canvas page survives a malformed `#t=`) are two small canvas JS fixes; merge them into canvas slice 2's editor stream (same JS files and GraalJS harness, tasks 11b-11e) so they land in one pass; wave 3 keeps only the tests list reference.
5. The "other force-stops" count appears on the phone screen as well as the glasses log (+~2 agent-h).
6. Ordering (controller's call, fewest round trips): Stream 1 wave B 3→4→5→7b→8→9 with the clubbed v5 migration first; Stream 2 wave 3 (Task 11 Forget first, then recall, phone fix, glasses fixes) in parallel in its own worktree after the v5 migration lands; Stream 3 canvas slice 2 (React Flow editor 11a-11d can start now, interpreter tasks after v5). Items touching the same files are batched into one dispatch.
