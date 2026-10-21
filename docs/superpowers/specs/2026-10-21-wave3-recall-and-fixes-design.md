# Wave 3: image recall, plus a small-fixes bundle — design

**Date:** 2026-10-21. **Author:** Opus planner (the user was asleep; every choice below is a ruling, listed again in "Open for the user").
**Inputs:** handoff `2026-10-21-handoff.md` (§8 bugs, §9.6 wave 3, §9.8 device checks); wave-1 spec `2026-10-15-keyframe-capture-image-memory-design.md` §P5 (and §P4, §P6, §F.4); wave-2 plan `2026-10-21-wave2-scene-grouping-and-nomic.md` (Rulings 2, 11, 15, 17); canvas design `2026-10-21-experiment-canvas-design.md` R13; code at `pca-int/int` branch `int/2026-10-21` (`b2fbb0e`).
**Plan:** `docs/superpowers/plans/2026-10-21-wave3-recall-and-fixes.md`.

Format of rulings: `Ruling: <what> — <why> — <cost if wrong>`.

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
  - The scan is paged by `frame_vector.rowid DESC` (newest first, 500 per page) and joined to `frame_meta` (forgotten frames have no meta).
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
  - If a task finds it needs a schema change, it takes the next free Room version (v5 is expected after wave 2's v4), additive only. Check at merge.
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
1. R12: READ_PHONE_STATE stays off (audio mode plus a 15 s settle). Say if you'd rather grant it for a precise guard.
2. R11: the "Open on glasses" notification also appears after your own deliberate exit (silent, once).
3. R5: the glasses card shows "· Photo 14:02" as text, not an icon.
4. R6: "Describe again" on a photo that already has a caption adds a second set of facts. A photo you describe is kept as a normal photo (7 days), not deleted at 1 day as a skipped photo.
5. R1: brute-force search, capped at 50 000 photos or 2 s, newest first. Fine for years at today's rate.
6. R14: a phone reopen that looks like a launcher open can still show the HUD for up to about 1 s until the phone's marker arrives.
7. R16: default storage limit is 250 MB.
8. R10: the Ask half of the debug view (scores per retrieved item) waits for the canvas.
