# Motion-aware keyframe capture and image memory: design

Status: revision 4 (three review rounds + user decisions A1/B1/C1/D1, 3, 6a), 2026-10-15. Branch base: `feat/proactive-mvp-implementation` @ `8da5bf1`.
Backlog link: status-and-backlog §5 item 1, §10 "Faster capture → keyframes only", §11 step 4b.

## 0. Intent (agreed with the user, 2026-10-15)

- **Goal: both recall and live awareness, equally.** Keep every distinct scene, including 1–2 s moments ("where did I leave my keys", "what was on that whiteboard"), and react to what the user is looking at within seconds.
- **Cost model (user decision):** capture fast on the glasses but send only changed scenes, and cap AI calls at about 1 per 10–15 s.
- **Verifiability:** in debug, the user must be able to compare each saved caption, cluster and retrieval against the photo it came from. That's why stored keyframes are 640 px, not 160 px. It also supports the v3 agent canvas, where a router triggers subagents per task.
- **Privacy and storage:** full-resolution originals never leave the glasses. Each kind of stored data has its own retention setting. Eventually only vectors and text remain.
- **Simplicity this run (user decision):** a single embedding model family, **Nomic Embed v1.5** (text and vision share one vector space), replaces bge-small, with a re-index. There is no SigLIP and no second vision interface. A vision-model chooser comes in v1.5; see §F.3.
- **Agents (user rule):** Opus writes specs, plans and reviews; Sonnet writes code. Never Haiku.
- **Versioning (user decision C1):** the build at `b2fc43a` is tagged **`v1-beta`** (local tag only). This feature set ships as app **v1.0.0** (`versionName "1.0.0"`, `versionCode 2`, on both apps). Room moves from version 1 to **version 2** with **additive tables only** (§C). Room versions must be integers, so "v1.0" is the app name and "2" is the DB number. PROTOCOL_VERSION stays 1 and additive.

### Captions vs embeddings (user question, answered)
- **Captions** are text written by the **vision LLM**: the VISION_EXTRACT role, Azure today or an on-device vision model later.
- **Nomic** writes no text. It turns photos and text into **vectors** for search, clustering and dedupe.
- Captioning happens at two times:
  1. **Live:** every keyframe sent in an extraction call gets its **own caption** in that call's output (§P2.1 provenance). Cluster representatives are captioned live the same way.
  2. **Night (charging):** only **describe before deleting** (§P6). It covers a photo that is about to expire and is linked to **no memory at all**, such as a salient keyframe that never got its live call.
- **No extra captioning (user decision, 2026-10-15):** the whole point of dedupe and clustering is to save vision-LLM calls, so the photos they skip are not captioned later.
  - **A skipped photo** is a near-duplicate (`near_dup_of`) or a non-representative cluster member (`frame_source` role CLUSTER_MEMBER, covered by the cluster summary).
  - **Rule (user decision):** if a skipped photo turns out to be a **key moment**, meaning salience ≥ 0.5 (dwell, text, novelty or watch list, §P4.1), it joins the **caption queue**: a live call under the normal budget, at the lowest priority behind new keyframes and nudge evaluation.
  - **Aging (review fix 7):** after 10 min of waiting it moves above new non-dwell keyframes; after 30 min it takes the **next unreserved** token (never a nudge-reserved one).
  - **Promotion** cancels its 1-day skipped-photo deletion; it then follows normal photo retention (7 days).
  - **Otherwise** it is never captioned, and its photo is deleted after **1 day**. Its vector and its links (original or cluster) are kept.
  - **Image vectors** are still made for every keyframe (on-device, no LLM cost), so they stay findable by photo search while the photo is stored.
- **Re-indexing**, after a change of embedding model, re-makes **vectors only**; it never re-captions. Captions are kept because they feed future personality dimensions and evaluation datasets (user intent).

### What exists today (facts, 2026-10-15)
- **Glasses capture:**
  - an AlarmManager chain (`CaptureScheduler`, `CaptureAlarmReceiver`);
  - default interval 300 s, minimum 30 s, doubled when battery is low or Battery Saver is on;
  - CameraX is bound for each capture: an 8 s window, analysed at 1 Hz;
  - frames are 360 px at JPEG q62;
  - `FrameGuard` applies these checks, in order: dark-frame skip, a 64-bit average hash with Hamming distance ≤ 4 against the last kept frame, and at least 2.5 s between kept frames.
- **The `burstOnChange` setting is dead:** no glasses code reads it. No IMU, motion or heat code exists.
- **Uplink:** `OutboundBuffer` (200 frames), then `FrameUplink` (stop-and-wait with a 5 s ack timeout), over CXR or the session-less `MINIMISED` link.
- **Phone processing:**
  - `FrameIngestor` → `RokidFrameInbox` dedupes by frame id only;
  - `FrameIngestWorker` and `QueueDrainWorker` are **periodic every 15 minutes**: 8 jobs per run, 1 VISION_EXTRACT per photo, at most 3 photos per batch;
  - no cost cap.
- **Text embeddings:** bge-small-en-v1.5 q8 through llama.cpp JNI (`llama_jni`), behind `Embedder` (`services/memory/.../Embedder.kt`), with one fingerprinted space per config.

### Glasses hardware (read on device, 2026-10-15; RG-glasses, platform "neo", Android 12)
- **Sensors:** accelerometer and gyroscope (TDK icm4x6xx, wakeup and non-wakeup), linear acceleration, gravity, game rotation vector, and **proximity**, used as a wear check.
- **Heat:**
  - `dumpsys thermalservice` shows Thermal HAL 2.0, and the thermal status is readable (0 at the time).
  - The HAL exposes **no temperature sensors**.
  - Battery temperature is available (`BatteryManager.EXTRA_TEMPERATURE`; 31.0 °C at the time).
- **The user's observation:** after about 50–60 minutes of full-resolution video at 30–40 fps, with a battery capsule, the glasses get hot and have shut down once or twice. Stills sampled at low resolution should run cooler. §P1.5 sets the limits.

## Phases and order

| Phase | Topic | Devices | Order |
|---|---|---|---|
| F | Shared foundation: `LanguageModel`, `EmbeddingService`, Nomic, re-index | phone | 1 |
| C | Database v2: additive tables `frame_meta`, `frame_state`, `frame_source`, `frame_cluster`, `frame_vector` | phone | 1 (together with F) |
| P1 | Glasses capture: motion, keyframes, 640 px, heat, work/rest | glasses | 2 (together with P2) |
| P2 | Phone keyframe intake: process on arrival, call budget, fast path | phone | 2 |
| P4 | Image index (indexing time): image vectors, semantic dedupe, clusters, salience, cluster-batch AI calls | phone | 3 (together with P6) |
| P6 | Storage lifecycle (indexing time, at night): retention for each kind of data, describe before deleting | phone | 3 |
| P5 | Image recall (ask time): words-to-photo search, describe on demand, debug view | phone, glasses screen | 4 |
| P3 | Motion hub: motion sources on phone, watch and glasses, hints to the glasses | phone, watch, glasses | 5 |

Each phase below has the same sections: **New**, **Indexing time**, **Ask time**, **Per device**, **Errors and limits**, **Tests**.

Shared rules:
- Room goes from v1 to v2 **only through §C** (additive tables, decision C1). Any further schema change needs its own recorded decision.
- PROTOCOL_VERSION 1 is additive only: new optional fields and new message types.

---

## F. Shared foundation

### F.1 New
- **`LanguageModel`** (`:services:ai`). A single interface for every chat and vision call, cloud or on-device.
  - It wraps today's `ModelProvider` and `ModelGateway`; callers stop depending on provider classes.
  - Shape: `suspend fun complete(request: ModelRequest): ModelResult`. `ModelRequest` carries the messages, any images (JPEG bytes), the schema and a timeout. `ModelResult` carries the text, the parsed JSON, usage and latency.
  - Existing roles (VISION_EXTRACT, ANSWER, NUDGE, critic) call through it.
  - No behaviour change: this is a seam only, so that P2's budget and the v3 router have one place to hook into.
- **`EmbeddingService`**. Replaces `Embedder`, and keeps today's per-space fingerprint rule.
  ```kotlin
  interface EmbeddingService {
      val fingerprint: String              // the space: family + version + dimension + normalisation
      val modalities: Set<Modality>        // TEXT, IMAGE
      suspend fun embedDocuments(texts: List<String>): List<FloatArray>
      suspend fun embedQuery(text: String): FloatArray
      suspend fun embedImages(jpegs: List<ByteArray>): List<FloatArray>   // throws if IMAGE is not in modalities
  }
  ```
  - `Embedder` stays as a type alias or a thin adapter during the move, so `:services:memory` doesn't depend on `:services:ai`.
  - `SpaceEmbedderProvider` returns an `EmbeddingService`.
- **Nomic Embed v1.5**, the default from now on.
  - **Text:** `nomic-embed-text-v1.5` GGUF, Q8_0, run through the existing llama.cpp JNI. llama.cpp supports the `nomic-bert` architecture.
    - Document and query prefixes are required: `search_document: ` and `search_query: `.
    - Captions sent for embedding use `search_document: `.
  - **Vision:** the `nomic-embed-vision-v1.5` ONNX export, run with ONNX Runtime Android (MIT licence). Input is 224 px, centre-cropped from the 640 px keyframe. Images are embedded straight into the same space.
  - **Exact transforms** (from the official model cards; review finding 1):
    - **Text:** mean-pool the token embeddings (768) → **LayerNorm over the full 768 dimensions, no learned weights** → truncate to the first 256 → L2-normalise. Today's JNI does only L2 normalisation, so the plan adds the LayerNorm step in Kotlin (or in the JNI) before truncation.
    - **Vision:** the CLS embedding (768) → L2-normalise (the model card's recipe), then truncate to the stored dimension → L2-normalise again.
  - **Stored dimension:** 256 for text (Matryoshka, documented for the text model). The image dimension is **256 only if the §F.5 photo acceptance test passes at 256; otherwise images are stored at 768**, while text stays at 256 (user decision: separate dimensions, for storage).
  - **Queries with different dimensions (user decision 3):** a question is embedded **once** at 768 with the query prefix. The text index uses it shortened to 256 and L2-normalised; the image index uses it at the image dimension. One model and one call. Text and image scores are **never compared** (see §P5.1). The dimension is part of each space's fingerprint. A later change is a new space, which means a re-index.
  - **Reference-vector test:** the plan fixes 10 texts and 5 images with vectors computed by the official Python pipeline (`sentence-transformers` / `transformers` at a pinned commit). On-device vectors must reach cosine ≥ 0.99 against them for every item.
  - **Download:** both files go through the existing `LocalModelDownloadWorker`: https only, Range resume, SHA-256 verify, pinned to a Hugging Face commit. Exact URLs, sizes and hashes are pinned in the plan.
  - **Catalog entry:** "Nomic Embed v1.5 (text + images)". It replaces bge-small as the recommended entry. bge-small stays in the catalog so older spaces can still answer.
  - **Battery gate:** unchanged (the G6 rule, charging or above 70 %, applies to bulk embedding).
- **Licences:** Nomic text and vision v1.5 are Apache 2.0 (vision since 2025-01-17); ONNX Runtime is MIT. Each is recorded in the decision log with its source link.

### F.2 Indexing time: re-index from bge-small to Nomic (and any later model change)
- This uses the existing re-index flow: the new space is built in the background while the old space keeps answering.
- **Coverage rule, changed (decision A1):** the swap needs 100 % of text memories and 100 % of photos **still stored**. Image vectors of photos that are already deleted can't be rebuilt. They are dropped at the swap, and those items stay findable by caption only.
  - Before the swap, the confirmation counts the deleted photos in two groups:
    - **"N photos will be searchable by text only"**: those with their own caption or a surviving linked memory (`frame_source`) or cluster summary. Recall is reduced.
    - **"M photos will no longer be findable at all"**: no caption, no linked memory, vector only, such as ordinary skipped photos whose photo was deleted. Recall is lost.
    - The dialog says that **both groups can be recovered with Roll back for 7 days**, after which the loss is permanent. M is also shown in Diagnostics after the swap until the grace period ends.
- **Rollback grace, new:** today `EmbeddingDao.swap` deletes the old space's vectors in the same transaction. That changes:
  - the old space becomes `RETIRED` but **keeps its vectors for 7 days**;
  - Settings → AI → On-device models gets **Roll back** for those 7 days, which reactivates the old space;
  - a daily job deletes retired vectors after 7 days.
  - The existing swap tests are updated, and a rollback test is added.
- Text memories are re-embedded with the document prefix.
- Photo keyframes that still have a stored photo get image vectors in the same space; this is P4's backfill.
- Progress shows in Settings → AI → On-device models, using the existing progress bar.

### F.3 Later (v1.5): vision-model chooser (recorded, not built now)
- **Settings:** two pickers, Text embedding and Image embedding.
- If both picks come from the same family with a shared space (as Nomic's do), one unified `EmbeddingService` with TEXT and IMAGE is used.
- Otherwise there are two disjoint services, an image space and a text space, and photo search becomes "image-space query from a text encoder of the same model". This needs that model's own text tower, as with SigLIP or CLIP.
- The interface above already allows either case: a service declares its modalities.

### F.4 Errors and limits
- **Nomic not downloaded or failing:** the text path falls back to whichever space is active (bge-small) and image vectors are skipped. P4 then runs captions only; P5 search runs over text only, with a "Photo search needs the on-device model" banner.
- **ONNX session failure:** logged with counts only. One retry, then image embedding is off for this process, and Diagnostics shows the reason.

### F.5 Tests
- `LanguageModel`: tests on the adapter around the existing providers; all existing role tests must pass unchanged.
- `EmbeddingService`: tests for the fingerprint covering family, dimension and Matryoshka truncation, for the prefixes, and for `embedImages` rejecting a text-only service.
- **Text check:** a JVM test with fake vectors covers the plumbing. A device test (`androidTest`) on the phone checks that Nomic text gets recall@5 ≥ bge-small on the existing fixture of 30 queries over memories.
- **Reference vectors:** the cosine ≥ 0.99 test from §F.1, for both text and images.
- **Photo acceptance test (words to photo, review addition):**
  - a fixture of ≥ 60 of the user's 640 px keyframes and ≥ 40 text queries;
  - ≥ 15 of the queries target **small or edge-of-frame objects** (keys on a table edge, a sign in a corner, a cup at the frame border);
  - measured: recall@3 at **768 and at 256** dimensions;
  - rule: store images at 256 only if recall@3 drops by ≤ 3 points versus 768 overall **and** by ≤ 5 points on the small/edge subset; otherwise store 768;
  - the result is recorded in the decision log.
- Re-index: the existing re-index tests are extended to a bge → Nomic space switch.

---

## C. Database v2 (decision C1; additive only)

Room version 1 → **2**, with SQLCipher kept. This is a `Migration(1, 2)` that **only adds tables**. Existing tables are untouched. The exported schema JSON goes in `schemas/`.

| Table | Columns | Purpose |
|---|---|---|
| `frame_meta` | `frame_id` PK, `session_id`, `motion`, `dwell`, `blur`, `dhash`, `phash`, `width`, `salience`, `salience_terms` (JSON), `cluster_id`, `near_dup_of`, `image_vector_space`, `created_at` | metadata from §P1, §P4 for each keyframe, durable after the inbox |
| `frame_state` | `frame_id` PK, `state` (PENDING, EXTRACTING, EXTRACTED, CLUSTERED, NEAR_DUP, FAILED, FAILED_FINAL), `attempts`, `last_error`, `updated_at` | the backlog's source of truth (§P2), no TTL |
| `frame_source` | (`memory_id`, `frame_id`) PK, `cluster_id` nullable, `role` (FACT, CLUSTER_REP, CLUSTER_MEMBER) | frame → memory provenance (§P2), the Forget walk. **Named `frame_source` because `memory_source` already exists** (memory → memory ancestry, `MemoryEntities.kt:47`). That table and its Forget traversal stay untouched. |
| `frame_vector` | (`frame_id`, `space_id`) PK, `blob`; index on `space_id` | image vectors for each keyframe. It doesn't need a memory row: skipped photos may have none, and the existing `memory_vector` requires a live memory. It holds the ACTIVE space's rows plus a RETIRED space's rows for the 7-day rollback (§F.2). |
| `frame_cluster` | `cluster_id` PK, `opened_at`, `closed_at`, `close_reason`, `rep_frame_ids` (JSON), `memory_id` nullable | §P4 clusters |

- **Tests:**
  - a `MigrationTestHelper` test from the v1 schema with real rows, which keeps every v1 row;
  - an encrypted-database migration test, extending `SqlCipherMigrationDeviceTest`;
  - Delete all and Forget cover the new tables (Delete all keeps notes and shopping, as before).
- That makes **5 new tables**. The earlier "four tables" is superseded.
- `frame_state` adds `claimed_at` (nullable) and `claim_id` (nullable, a random id for each attempt) for the extraction claim (§P2.5).
- Frames ingested before v2 have no `frame_meta` row. They're treated as `motion=UNKNOWN`, not dwell, and salience is computed from the image only.

---

## P1. Glasses capture

### P1.1 New
- **Capture mode setting** on the phone, synced to the glasses: **Smart** (the new default) or **Fixed interval**, today's 30 s–1 h.
  - In Smart mode the interval setting is hidden. The dead "extra photo on scene change" (`burstOnChange`) switch is **removed** from the UI; the field stays in the protocol, ignored.
- **The sampling session (Smart mode):**
  - Start: the HUD is visible (the Android 12 rule that a camera foreground service must start from the foreground), the glasses are worn, capture isn't paused, and battery and heat allow it.
  - **The camera follows head motion (revision 5, user decision 2026-10-15):** the camera is opened only while it can matter (§"Motion-driven camera" below). It is **not** bound for the whole session. The analysis stream uses STRATEGY_KEEP_ONLY_LATEST at a target of 640×480, through CameraX or Camera2, whichever starts faster in the Quick-look probe.
    - Each sample is downscaled to a **160 px grey thumbnail used only inside the glasses**, for the hashes, blur and motion checks. It's never stored or sent.
    - Only a keyframe is encoded: **640 px wide JPEG at q75**.
  - The session lives in the existing keep-alive `CaptureService`. The AlarmManager chain stays only for Fixed mode and as a watchdog that restarts sampling after a process death.
- **Motion state** (`MotionClassifier`, glasses):
  - Inputs: gyroscope and linear acceleration at SENSOR_DELAY_GAME (about 50 Hz), batched with `maxReportLatency` 1 s.
  - Output: a state over a sliding 1 s window.

    | State | Rule |
    |---|---|
    | `HEAD_MOVING` | gyro magnitude > 0.6 rad/s |
    | `WALKING` | linear-acceleration variance > 0.5 (m/s²)² and a step-like periodicity of 1.5–2.5 Hz |
    | `STILL` | otherwise |

    Thresholds are constants, tuned from the P1 device run.
  - A hook for P3: if a phone motion hint (§P3) says `VEHICLE` or `WALKING`, the floor is that state's cadence.
- **Motion-driven camera (revision 5).** The IMU runs all the time (cheap, no LED); the camera, and therefore its LED, is on only around motion.

  | Phase | Camera | Frames checked while open |
  |---|---|---|
  | Head moving or walking | **open** | 3 per second |
  | Just stopped: hold time **H** after motion ends | **open** | 3 per second, with a dwell keyframe at ≥ 1.0 s still |
  | Settled (after H) | **closed**, LED off | a brief check every 5 s: open → first usable frame → close |
  | Settled with no keyframe for 10 min | closed | a brief check every 15 s |

  - **Early start:** the camera opens when the head **starts to decelerate** (gyro magnitude falling below 60 % of the movement's peak), not after it stops, to hide the start-up delay.
  - **Quick look setting** (Settings → Capture; the user decision is minimum 1 s): sets H.

    | Quick look | H (hold) | While moving |
    |---|---|---|
    | 1 s | 4 s | camera open |
    | **1.5 s (provisional default)** | 2 s | camera open |
    | 2 s | 1 s | camera open |
    | 3 s | 0 | bursts: one brief check every 2 s |

    The default and the H values are confirmed or adjusted by the Quick-look probe (§Probe results 2).
  - Battery Saver or battery ≤ 20 %: brief checks every 10 s and H halved.
- **The promise (user decision; configurable through Quick look):**
  - **under normal operation**, meaning a sampling session active (worn, not paused, not in a rest period, no heat stop) and at least one usable frame (above the blur floor, not dark): **anything you look at for ≥ the Quick look time (default 1.5 s) right after moving** gets a keyframe;
  - a change in front of a **settled** head is caught within ≤ 5 s (≤ 15 s after 10 quiet minutes);
  - shorter glances are best effort.
- **Camera LED (final, 2026-10-15):** the privacy LED is lit whenever the camera is open. The app **never switches it off, suppresses it or works around it**: it's the only notice bystanders get, and frames are clear enough (640 px) to read faces and text. The user asked to explore disabling it; Claude declined.
  - LED minimisation comes **only** from opening the camera less (above).
  - **Checks:** a source-rule test (no torch, flash or LED control calls: `enableTorch`, `FLASH_MODE`, `/sys/class/leds`, any vendor LED API), and a manual device-checklist item (the LED lights while the camera is open and goes dark when settled).
- **Keyframe test** (`KeyframeGate`, replacing `FrameGuard`'s hash check; the dark-frame and battery skips stay):
  1. **Blur:** variance of the Laplacian on the 160 px thumbnail. While moving, samples are taken in windows of up to 1.5 s (2–3 samples), and only the sharpest one is considered. A sample below the blur floor is dropped.
  2. **Change:** a 64-bit dHash and a 64-bit pHash. The sample is **new** when the smaller of the two Hamming distances is > 10 against **each of the last 5 keyframes** (a ring buffer persisted in `capture_guard` prefs).
  3. **Throttle:** at least 1.2 s between keyframes. That replaces today's 2.5 s, which was tuned for 30 s capture.
- **Dwell marker:** when the state goes from `HEAD_MOVING` or `WALKING` to `STILL` and stays `STILL` for ≥ 1.0 s (immediate keyframe, which meets the ≥ 1.5 s promise with margin), the next keyframe, or the current sample if it passes the change test against the last keyframe only, is flagged `dwell=true`.
  - This is the "looked at something" signal: the keys, a sign.
  - A dwell sample skips the last-5 rule, so looking back at something counts again, but it still needs blur ≥ floor.
- **Wear check:** proximity "far" for > 5 s means **not worn**. The session pauses: the camera unbinds and the sensors switch to low rate. It resumes on "near".
- **Keyframe metadata**, as additive `pc_frame` fields: `motion` (the state name), `dwell` (bool), `blur` (float), `dhash`, `phash` (hex), `sampleSeq`, `width`, `sessionId`.

### P1.2 Indexing time
- None on the glasses.

### P1.3 Ask time
- The capture settings below sync from the phone through `HudSettings`, as additive fields, and are cached in `HudSettingsCache`.
- "Capture now" from the phone forces one keyframe, bypassing the change test (today's debug `skipDedupe` remains debug-only).

### P1.4 Per device
- **Glasses:** all of P1.1 and P1.5.
- **Phone:** the Settings → Capture UI:
  - **Capture mode** (Smart / Fixed); **Interval** (Fixed only);
  - **Pause when not worn** (on);
  - **Work/rest limits** (§P1.5);
  - the **status line**: "Sampling · walking · 12 keyframes in the last 10 min" or "Resting until 14:20 (heat)".

### P1.5 Heat and work/rest (glasses only; the phone has no work/rest timings)
- **Readings every 30 s:** `PowerManager.getCurrentThermalStatus()`, `getThermalHeadroom(30)` (recorded; it may be NaN on this HAL), and battery temperature.
- **Policy:**

  | Condition | Action |
  |---|---|
  | `THERMAL_STATUS_MODERATE`, or battery ≥ 40 °C | every cadence ×2 |
  | `SEVERE` or higher, or battery ≥ 44 °C | stop sampling (unbind the camera); HUD icon "Cooling"; resume when < `MODERATE` and battery ≤ 38 °C for 2 min |

  The 40, 44 and 38 °C values are defaults. The P1 heat probe can lower them; see §P1.7.
- **Work/rest cycle** (user decision): after **W** minutes of continuous sampling, rest **R** minutes. During rest, Smart mode falls back to a single Fixed-mode capture every 60 s, with the camera bound per capture as today.
  - Settings (user decision, 2026-10-15): **Work W ∈ {30, 40, 45, 50, 60, 75, 90} min**, default 45; **Rest R ∈ {5, 10, 15, 20} min**, default 15.
  - **Constraint:** if W > 45, then R ≥ 15. The UI disables 5 and 10 in that case; choosing W > 45 while R < 15 raises R to 15, with a short note.
  - The cycle restarts after any rest of ≥ R, including a not-worn pause.

### P1.6 Errors and limits
- **Camera bind fails:** after 3 tries the session falls back to Fixed mode at 30 s for this session, the HUD shows a small "capture degraded" mark, and Diagnostics shows the reason.
- **Sensors missing:** the state is always `STILL`, with cadence driven by scene change only.
- **Process death:** the watchdog alarm restarts the service. If the HUD isn't visible (Android 12 camera rule), sampling waits for the next HUD visit. This is today's behaviour, and it's logged.
- **Outbound buffer full:** drop-oldest, as today, but non-dwell keyframes are dropped first.

### P1.7 Tests
- **Unit:** `MotionClassifier` on recorded and synthetic IMU traces; the cadence table; `KeyframeGate` (blur picking, last-5 rule, dwell exception, throttle); the heat policy table; the work/rest timer; the wear check.
- **Replay test (JVM):** a recorded sequence of thumbnails and IMU data, captured on the glasses by a debug command, must produce the expected keyframe ids.
- **Glimpse-offset test (review addition):** synthetic sequences where a new scene is looked at for 1.0, 1.5 and 2.0 s, starting at 10 different phase offsets against the analysis clock, for each motion state.
  - Required: 100 % keyframe capture at ≥ 1.5 s, and a change in front of a still head caught within 5 s;
  - reported only: the 1.0 s capture rate.
- **Device run:** 10 min each of desk-still, walking indoors, and glancing at 5 placed objects (each must yield one dwell keyframe), then a minimised stretch.
  - Record keyframes per minute, the battery temperature curve and the heat status.
- **Heat probe (2026-10-15, throwaway branch `probe/camera-heat`):** results are added below as "Probe results" before the plan is written.

---

## P2. Phone keyframe intake

### P2.1 New
- **Process on arrival:** `FrameIngestor`, after a successful inbox write, signals a `KeyframePipeline` running in the phone's existing foreground hub service. The periodic 15-minute workers stay as a backstop for anything missed, such as frames that arrived while the process was dead.
- **Call budget** (`CallBudget`, a token bucket shared by every `LanguageModel` call, through the F.1 seam):
  - rate: 1 token per 12 s, burst 2;
  - user setting **AI call pace**: 10 s, 12 s (default) or 15 s;
  - calls the user started (Ask, the test bed) bypass the bucket but count towards it.
- **Provenance contract (review finding 4, decision C1):**
  - every image in a multi-photo VISION_EXTRACT call is labelled in the prompt with its `frame_id`;
  - the output schema requires, per fact, **`source_frame_ids` (non-empty)** and, per photo, `captions[{frame_id, caption}]`;
  - the validator rejects any id that wasn't sent, and any sent photo without a caption: the whole call counts as INVALID_OUTPUT and the frames are retried as single-photo calls;
  - each saved memory gets `frame_source` rows (§C);
  - Forget and redaction walk `frame_source`, so forgetting a frame also handles every memory that cites it, under the existing fences;
  - single-photo calls use the same schema.
- **Batching:** each VISION_EXTRACT call takes up to 3 pending keyframes, choosing **dwell keyframes first, then newest first**. The backlog drains in order as tokens allow, and nothing is dropped.
  - A backlog > 30 keyframes switches to **cluster mode** (P4) for the excess.
  - Until P4 ships, the excess just waits.
- **Fast path (decision D1; review finding 2):** today `NudgeRunGate.MIN_GAP_MS` (30 min) gates **evaluation** itself, so the fast path can't reuse it. Split it:
  - **Evaluate (decision 6a):** a new `NudgeEvaluator` considers only memories from **salient or dwell** keyframes or a **new place**.
    - The first eligible memory starts a **settle window of 5 s**; anything eligible that arrives in that window joins the same evaluation.
    - There are at least **30 s** between evaluations. Something that arrives inside the gap starts the next settle window when the gap ends.
    - Timing: about 15 s typical and about 45 s worst, from capture to decision. Cost: at most 2 evaluation calls per minute while busy.
    - **User settings** (Settings → Nudges, new rows): **Settle window** 3 / 5 / 10 / 15 s (default 5); **Minimum gap between checks** 30 / 60 / 120 s (default 30); **Minimum gap between shown nudges** (today's 30 min: 10 / 30 / 60 min); **Daily nudge cap** (today's value, exposed if it isn't already).
  - **Notify:** the existing 30-minute gap and the daily cap apply **only to showing** a nudge. An evaluation that decides "nudge" while throttled is recorded and dropped, with a reason in Diagnostics.
  - **Budget reservation:** 1 in every 5 budget tokens is reserved for nudge evaluation, so a backlog can't starve live nudges. An unused reservation is released after 60 s.
  - **Borrowing rule (review fix):** when a nudge evaluation is due and no reserved token is free, it **takes the next extraction token**. That delays extraction by one token, never more, because evaluations are at most 2 per minute.
    - Caption-queue and backfill calls can never take nudge tokens.
    - User-started asks bypass the bucket as before.
  - The periodic `NudgeWorker` stays as the backstop, with its 30-minute gate.
  - **Target**, at the defaults with no backlog, measured from keyframe capture to the nudge decision: **p50 ≤ 15 s** and **≤ 45 s** for 95 % of events.
    - This holds when the provider's p95 latency is ≤ 8 s per call (the user accepted about 8 s on 2026-10-15) and the uplink is healthy (keyframe at the phone within 3 s).
    - Slower providers shift the target by their extra latency. Diagnostics shows both numbers.
- **Diagnostics:** keyframes per hour, AI calls per hour (split by role), backlog size, mean latency from keyframe to memory, and tokens per call when the provider reports them.

### P2.2 Indexing time
- Keyframe memories are embedded as today (text). Image vectors are added in P4.

### P2.3 Ask time
- An Ask while there's a backlog: the newest ≤ 3 keyframes from the last 60 s are extracted first, using the user-started bypass, so "what am I looking at" works.

### P2.4 Per device
- **Phone only.** The glasses send `pc_frame` as before, plus the P1 metadata.

### P2.5 Errors and limits
- **Provider error:** today's retry backoff applies, and the bucket token is refunded on transport errors only.
- **Phone power and heat (user decision, revision 5; no work/rest timings on the phone):**
  - the phone pipeline runs continuously;
  - **app not in the foreground:** image vectors are batched every 60 s instead of on every arrival, while extraction keeps its budget;
  - **phone thermal status ≥ MODERATE:** background work pauses (image vectors, caption queue, backfill, non-user extraction beyond 1 call per 30 s); user-started asks keep working;
  - **≥ SEVERE:** all background processing pauses until the status is below MODERATE for 2 min. Frames stay durable in `frame_state`.
- **Battery Saver, or battery ≤ 30 % and not charging:** today's deferral rules stay. Live nudges pause; the user-started bypass still works.
- **Offline and backlog durability (review finding 7):** the backlog's source of truth is the new `frame_state` table (§C), not the 24 h-TTL job queue.
  - **Claims (review fix 4):** moving to `EXTRACTING` sets `claimed_at`. A claim older than 5 min (longer than the call timeout) counts as **abandoned**: the frame goes back to `PENDING` and `attempts` increases.
  - **Claim check at commit (review requirement, carried into the plan):** the commit transaction first runs a compare-and-set, `UPDATE frame_state SET state=… WHERE frame_id=? AND state='EXTRACTING' AND claim_id=?`. If no row matches, the attempt was abandoned and reclaimed: the whole transaction is rolled back and its output dropped, so an old attempt can never overwrite a newer claim. Tested with two overlapping attempts.
  - **Atomic commit:** the memory rows, the `frame_source` rows and the move to `EXTRACTED` or `CLUSTERED` are saved in **one** database transaction. A crash before commit leaves the claim to expire; a crash after commit leaves nothing to redo.
  - Jobs are rebuilt from `frame_state` rows in `PENDING`, `FAILED` (attempts < 5) or an abandoned `EXTRACTING` claim, at startup, on network return, and by the periodic backstop.
  - A job expiring therefore no longer loses a frame.
  - Frames stuck after 5 attempts become `FAILED_FINAL` and appear in Diagnostics, with a "Retry all" action.

### P2.6 Tests
- Unit tests for the token bucket (rate, burst, bypass, refund), the batch picking order, and the backlog threshold.
- A Robolectric test: ingest → pipeline → fake `LanguageModel` → memory saved, under the budget.
- A latency test with a fake clock.
- Provenance: the validator rejects unknown ids and missing captions; INVALID_OUTPUT falls back to single-photo calls; `frame_source` is written; Forget follows `frame_source`.
- Nudge split: evaluation timing, notification throttling, reservation release.
- Recovery: kill mid-backlog, then restart, and every frame ends `EXTRACTED`, `CLUSTERED` or `FAILED_FINAL`, none lost; also a 25 h offline simulation.
- **Doze device check (review addition):** phone screen off, unplugged, in Doze (`adb shell dumpsys deviceidle force-idle`), with keyframes arriving for 20 min. Record arrival-to-memory latency and whether expedited work was deferred or ran out of quota. If the in-service path is deferred, the plan's fallback (expedited work) must be shown to keep frames durable (no loss), even if it's slower.

---

## P4. Image index (indexing time; phone)

### P4.1 New
- **Image vector for each keyframe:** `EmbeddingService.embedImages` (Nomic vision) on the 640 px keyframe, centre-cropped to 224 px, at ingest, batched in 8s.
  - Stored in `frame_vector` (§C), in the same Nomic family as text.
- **All per-frame fields** (motion, dwell, blur, hashes, salience, cluster id, near-dup link) live in `frame_meta` (§C), so they survive the move out of the inbox.
- **Semantic dedupe:** if a keyframe's cosine similarity to any keyframe of the last 10 minutes is ≥ 0.93 (tunable), and it isn't a dwell keyframe, it's marked `near_dup_of=<id>`. It isn't sent to the AI on its own. Its photo is kept subject to P6.
- **Salience score** (0–1) for each keyframe, the sum of capped terms:

  | Term | Score |
  |---|---|
  | dwell | +0.4 |
  | text present (ML Kit on-device text recognition, Latin, ≥ 4 words) | +0.3 |
  | novelty: 1 − the highest cosine against the last 30 minutes | +0.3 × novelty |
  | watch list: highest cosine against a text-embedded list ("keys", "car", "parking sign", "receipt", "whiteboard", "document", "a person's face"; user-editable in Settings) ≥ 0.25 | +0.3 |

  A keyframe scoring ≥ 0.5 is **salient**: it always takes the fast path (P2) and is never folded into a cluster summary alone.
- **Clusters (the slow path):** non-salient keyframes are grouped into the current open cluster. A cluster closes when any of these happens:
  - the cosine drift from the cluster centroid is > 0.25 for 2 consecutive keyframes;
  - the motion state changes, as `STILL` ↔ moving or the vehicle hint;
  - it reaches 100 keyframes;
  - 5 minutes pass.

  A closed cluster picks up to 3 representatives: the medoid, the sharpest, and the most different.
- **Cluster-batch call:** one VISION_EXTRACT carries the representatives of up to 3 closed clusters, using the CLUSTER_SUMMARY prompt (new; editable in the prompt editor, with an Agent-flows step). It gives one memory per cluster, linked to every keyframe in it.

### P4.2 Indexing time: backfill and re-index
- Existing photos with stored images get image vectors, salience and clusters retroactively, in time order. This runs under the G6 battery gate.
- An embedding-space change re-runs the vectors and salience **for photos still stored**. Deleted photos keep their captions only (A1). Cluster memories are not re-summarised; they keep their text.

### P4.3 Ask time
- None (P5).

### P4.4 Per device
- **Phone only.**
- **APK cost:** ONNX Runtime (about 10–15 MB per ABI, arm64 only) and ML Kit text recognition (bundled Latin model, about 4 MB). Sizes are confirmed in the plan.

### P4.5 Errors and limits
- **Image embedding unavailable (F.4):** salience uses only dwell and text, novelty is 0, and clusters close on motion and time only.
- **Storage:** a vector of 256 dimensions as float32 is 1 KB. 10 000 keyframes need about 10 MB.

### P4.6 Tests
- Unit tests for the salience terms, the cluster-closing rules, representative picking and the semantic-dedupe threshold, using fake vectors.
- A replay test: a recorded day of keyframes and fake vectors gives the expected clusters and salient set.
- On device (`androidTest`, phone): Nomic vision gives ≥ 20 images/s at 224 px on the S931B.

---

## P6. Storage lifecycle (indexing time, at night; phone)

### P6.1 New
- **Retention for each kind of data**, in Settings → Memory → Retention:

  | Kind | Default | Choices |
  |---|---|---|
  | Photo (640 px keyframe) | 7 days | 1, 3, 7, 30, 90 days, forever |
  | Skipped photos (near-duplicates and non-representative cluster members that aren't key moments) | 1 day | 0, 1, 7 days |
  | Caption / per-frame memory | forever | 30, 90, 365 days, forever |
  | Cluster summary | forever | same |
  | Image vector | forever | same |
  | Text vector | forever | same |

  - Full-resolution originals: **never stored on the phone**, and not offered as a setting.
  - **Constraint:** a vector can't outlive its last searchable text unless "photo search without text" is wanted. The UI warns if image vectors are set shorter than captions.
- **Describe before deleting:** a photo due for deletion that has no caption memory (such as a salient one waiting, or a near-dup representative) gets a caption first.
  - These calls are batched at night, while charging, under the budget.
  - If the budget or network isn't available, the deletion waits up to 3 more days, then the photo is deleted anyway and its vector kept. That's recorded in Diagnostics.
- **Two jobs (review finding 6):**
  - **Retention job:** daily, **no charging or idle constraint** (it makes no AI calls). It deletes every photo past its deadline.
    - **Ordinary skipped photos** (not key moments) are deleted at 1 day and **bypass** describe-before-delete and the 3-day extension.
    - Any **other** photo that's past its deadline and linked to no memory is kept until **deadline + 3 days**, then deleted anyway with its vector kept. Every deletion goes through the Forget file-and-row fence.
  - **Night job:** needs a charger and an idle device. It does describe-before-delete (sorted by deadline, only for photos linked to no memory), then the P4 backfill (vectors, no LLM), under the budget.
  - The 3-day promise therefore depends only on the unconstrained retention job.

### P6.2 Ask time
- Search results whose photo has been deleted show the caption and a "photo expired" placeholder.

### P6.3 Errors and limits
- Forget and Delete all keep today's rules: wipe fences, redaction, encryption (G1 seals photos with PCAF).
- Retention deletion uses the same file-and-row fence.

### P6.4 Tests
- Retention edges for each kind of data, describe-before-delete with a budget refusal, the 3-day grace period **with the charger never connected** (the photo is still deleted on day 3), and the fence with Forget.

---

## P5. Image recall (ask time; phone and glasses screen)

### P5.1 New
- **Words-to-photo search:** `embedQuery(text)` in the shared space searches text memories, captions and image vectors together.
  - **No score merging (user decision 3):** each index returns its own top results, **6 text items and 2 photos** (constants). The salience boost (+0.05) applies only inside the photo index. The **answer LLM**, which already reads every candidate, judges relevance. No extra call and no score calibration.
  - The top photo hits feed ANSWER as images: up to 2, with a "describe on demand" call through `LanguageModel`, using the user-started bypass.
- **Answer card on the glasses:** answer text plus "📷 14:02" when a photo backed it. The phone's Ask detail shows the photo.
- **Debug view** (debug builds, plus a Diagnostics switch in release). Each memory row shows:
  - the 640 px photo or photos, motion, dwell, salience terms, cluster id and representatives, caption, cluster summary, embedding space;
  - for an Ask: every retrieved item with its score and source (text, image, salience boost) and whether it reached the prompt.

  It's built for checking stored output against what really happened, and for the v3 router and subagents.

### P5.2 Errors and limits
- **No image vectors:** text-only search with the F.4 banner.
- **Photo deleted (P6):** a caption-only hit.

### P5.3 Tests
- Merge and ranking tests with fake vectors; a debug-view Robolectric test; ask-path tests with a photo hit and with an expired photo.

---

## P3. Motion hub (phone, watch, glasses)

### P3.1 New
- The structure is copied from LiveFit (`../live-fitness-tracker`): a plain-Kotlin core (`:core:model`, `:core:services`), platform adapter modules, and a fake for each service.
- **Interface:**
  ```kotlin
  interface MotionSource {
      val device: DeviceKind             // PHONE, WATCH, GLASSES
      val state: StateFlow<MotionReading> // state ∈ STILL, WALKING, RUNNING, VEHICLE, UNKNOWN; atMs; confidence
  }
  ```
- **Adapters:**
  - **Phone:** Activity Recognition Transition API, with an accelerometer variance check as a fallback.
  - **Watch:** a new Wear OS module. It sends **only state changes** over the Wear Data Layer `MessageClient` at path `/pc/motion` (JSON, LiveFit style), never raw samples.
  - **Glasses:** the P1 `MotionClassifier` state, sent up as an additive `pc_motion` message.
- **Hub** (phone): `MotionHub` fuses the readings. Precedence: VEHICLE beats moving beats STILL. The glasses' own head motion always overrides locally.
  - It sends an additive `pc_motion_hint {state, atMs}` to the glasses on change, at most 1 per 5 s.
- **iOS/watchOS later:** the same `MotionSource` interface behind CoreMotion and WatchConnectivity adapters. No code now.

### P3.2 Tests
- Fusion precedence, hint rate limit, fakes for each adapter, and a watch round trip on the emulator pair.

---

## Decisions recorded with this spec (2026-10-15)

| Decision | By | Confidence |
|---|---|---|
| Approach A (keyframes chosen on the glasses), not phone-side selection or motion-only bursts | user | H |
| 3 specs collapsed into one phased spec (F, P1–P6) | user | H |
| 640 px stored keyframes; 160 px only inside the glasses | user | H |
| Nomic Embed v1.5 (text + vision, one space) replaces bge-small, with a re-index; no SigLIP now | user | H |
| MobileCLIP rejected: Apple research-only licence (`apple-amlr`) bans product use | Claude, from the licence text | H |
| Retention set separately for each kind of data | user | H |
| Work/rest: W 30/40/45/50/60/75/90 min, R 5/10/15/20 min, R ≥ 15 when W > 45; defaults 45/15 | user | H |
| Heat thresholds 40/44/38 °C battery | Claude, defaults pending the probe | M |
| Keyframe thresholds (Hamming > 10, last 5, 1.2 s throttle) | Claude | M (tune on device) |
| A1: a model change drops image vectors of expired photos (caption-only); 7-day rollback grace | user | H |
| B1 relaxed: 3/s moving, 1/s still, back-off to 1 per 5 s; promise ≥ 1.5 s look, ≤ 5 s for a change in front of a still head; LED never disabled | user | H |
| C1: Room v2 additive tables; current build tagged `v1-beta`, this ships as app v1.0.0 | user | H |
| D1: evaluate straight away (≤ 1 per 60 s, salient/dwell/new place) vs notify throttle; 1 in 5 budget tokens reserved | user | H |
| Nomic transforms (text LayerNorm → truncate → L2; vision L2 → truncate → L2); reference vectors at cosine ≥ 0.99 | review + model cards | H |
| Image storage dimension decided by the 256-vs-768 photo test (≤ 3 / ≤ 5 point drop) | Claude | M |
| Skipped photos: key moments (salience ≥ 0.5) go to the live caption queue; the rest are never captioned and their photo is deleted after 1 day; night LLM work is only describe-before-delete for photos with no memory | user | H |
| Salience weights, 0.5 cut-off, cluster drift 0.25, dedupe 0.93 | Claude | L (tune with the debug view) |
| 256-dimension Matryoshka storage | Claude | M |
| Opus for specs, Sonnet for code | user | H |

## Review log (continued below)
- **Revision 1 (`dc97f15`):** reviewed by the user's external reviewer. Findings 1–7, plus the photo-dimension test and the Doze gate, were all verified against the code (`NudgeWorker.kt:47` 30-minute gate, `EmbeddingDao.kt` swap deleting vectors at once, `OrchestrationScheduler.kt` 24 h TTL) and fixed in revision 2.
- **Revision 2 review:** 7 findings, all verified (`memory_source` already exists at `MemoryEntities.kt:47`; `memory_vector` needs a live memory). Fixed in revision 3: the `frame_source` name, the `frame_vector` table, the 5-min extraction claim with an atomic commit, ordinary skipped photos bypassing describe-before-delete and the 3-day extension, caption-queue aging, and the qualified wording.
  - User decisions: **3** separate dimensions, one 768 query shortened for each index, no score merging (fixed shares of 6 text + 2 photos, the answer LLM judges); **6a** 5 s settle window with a 30 s gap, configurable in Settings → Nudges.

- **Revision 3 review:** a nudge borrowing rule, "next unreserved token" for caption aging, the 45 s target tied to provider latency, the model-switch dialog separating reduced recall from lost recall (recoverable for 7 days), and a claim-id compare-and-set at commit. Fixed in revision 4.

## Open questions for the plan (not blocking the spec)
- Exact Hugging Face commits, sizes and SHA-256 for the Nomic text GGUF and the vision ONNX.
- The ONNX Runtime version, and its 16 KB page-alignment compatibility (needed, as for llama.cpp).
- Whether the foreground hub service is allowed to hold the `KeyframePipeline` during Doze; if not, an expedited WorkManager job. The §P2.6 Doze device check decides this.

## Probe results (2026-10-15, throwaway `probe/camera-heat` @ `e591c27`, not merged)
Setup:
- the glasses were on a desk, not worn, on USB: charging and at 100 % throughout, so heat readings are pessimistic and drain was **not** measured;
- 2 min idle baseline, then 30 min of CameraX `ImageAnalysis` bound continuously, a sample every 1.5 s (JPEG q75 + dHash), and accelerometer + gyroscope at SENSOR_DELAY_GAME;
- minimised (HOME) from about 11 to 21 min, then relaunched.

Results:
- **Sensors (Q1):** accelerometer and gyroscope ran at a steady 50.1–50.3 Hz from the keep-alive `CaptureService`, in the foreground and while minimised.
- **Heat (Q2):** battery temperature 30.5 → peak 31.0 °C, **+0.5 °C** (one sensor step) over 30 min. Thermal status stayed 0 (NONE).
  - `getThermalHeadroom(30)` is **NaN on every reading**, and the HAL has no temperature sensors, so **battery temperature plus thermal status are the only heat signals**.
  - The §P1.5 defaults stand. They may only be lowered after an unplugged, worn run.
- **Cost per sample:** 1 180 samples, about 20 per 30 s as intended. Convert + encode + hash took about 47 ms per sample early on and 56–67 ms from about 11 min, for an unknown reason (not heat). The camera stream ran at about 24.8 fps.
- **While minimised (Q3):** sampling and sensors kept running at full rate after HOME (process importance went 100 → 125, the foreground-service level) and after the relaunch. The camera was **not** denied.
- **Resolution:** the camera reports 480×640 (portrait), so the probe's JPEG was 480 px wide (43 KB).

Spec consequences (binding on the plan):
1. P1 must request resolution explicitly with `ResolutionSelector` (4:3, 640×480 in landscape after rotation), then assert the keyframe width is 640. A device test checks it.
2. The 160 px thumbnail should come from Y-plane subsampling, not via the JPEG encoder, to keep each sample under about 30 ms. Only keyframes are JPEG-encoded.
3. **Still to measure:** battery drain per hour unplugged, and heat while worn. This is a device check in the P1 run, for example by wireless adb, or by reading the level before and after a 30 min unplugged session.
4. Only one capture path may own the camera: Smart mode replaces the per-capture bind, and Fixed mode/rest captures must not run while a sampling session is bound. The probe needed a "probe owns the camera" guard.
