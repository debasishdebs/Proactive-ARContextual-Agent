# Retention v3: keep text facts, expire only heavy storage (design, 2026-11-24)

Author: Opus (designer / RCA). Branch `feat/v101-ask-eval`. Status: **proposal, needs rulings K1-K8 (§10)**.
Inputs: Codex `reviews/2026-11/codex-retention-r1.md` (9 findings) and `codex-retention-r2.md` (9 findings, 6 P1 after the lineage fix), `handoffs/retention-redaction-analysis-2026-11-24.md`, `2026-11-24-retention-no-loss-design.md` (v2), and the code at `543f1031`.
Measured on a scratch copy of `~/Desktop/rokid/backups/phone-dump-2026-11-24/x/databases/context.db` (`sqlite3` `dbstat`; nothing on the phone was touched).

The user's words: "short term memories only have 1 day retention but even then it should have moments, summaries over text and then removed instead of blind removal; if a memory got removed and contributed to a card/mark/moment/nudge it was important, why remove it". The user's settings: Long-term 365 days, Photos Forever, cap 2000 MB.

## 0. Decision

**Adopt, with changes.**

1. Retention never deletes a memory row again: not by tier, not by caption days, not by scene-summary days. `ForgetService.expire(ids)` and `ForgetExecutor.expire` lose their only caller, and the `redact=false` text cascade goes with them.
2. Retention acts only on storage nothing cites by id:
   - photos: JPEG files and `frame` rows (skipped photos, `photo_days`, and a new quota eviction);
   - image vectors;
   - the vectors of SHORT facts once the recall window ends (the "demote" step).
3. **The changes:**
   - (a) **Demote, don't delete.** After the recall window a SHORT fact loses its text vector and leaves default retrieval and Home. It stays for provenance, Moments, cards, nudges, answers and questions about a time window. Keeping the vectors too would make the brute-force vector scan, and the DB, grow without bound (§4).
   - (b) **A durable expired-photo marker**, written in the same transaction as the photo expiry. "Expired" versus "forgotten" then becomes data, not an inference from `frame_meta`.
   - (c) **The clean-up pieces are separated:**
     - the file-retry sweep always runs;
     - Pause covers only the deleting steps;
     - quota eviction is bounded, runs oldest first, and never touches text.
   - (d) Text is deleted only by an explicit user action. **Auto-delete details** is an optional opt-in. It runs the existing user-Forget path, which redacts, withdraws and rebuilds, with honest wording. No third "storage-only text delete" mode exists, and that mode is where every lineage bug came from.

**Why:**
- Text is the cheap part: about 1.3 KB per fact without its vector, about 9% of the daily growth (§4). The cap is reached by photos, not by text.
- The user's rule ("if it contributed, it was important") is satisfied by construction. Nothing a nudge, answer, Moment, mark, pin or card cites ever disappears behind its back.
- 9 of the 18 Codex findings go away structurally, not by more lineage code. The rest are photo-side and close with one marker plus three small changes (§3).
- **Rejected:** v2 (re-anchor + digest + lineage). It keeps a storage-only text delete, which needs durable lineage. Lineage then needs closure in both directions, seed scoping and compaction (r2-1, r2-2, r2-8). That is an open-ended correctness surface for about 1.7 MB/day of savings.

## 1. What is measured (dump of 24 Nov 03:45; DB 106.7 MB = 26,042 pages of 4 KB, freelist 0)

| Object | KB | Note |
|---|---|---|
| `frame_vector` | 32,756 | 8,170 image vectors × 3,072 B (768-dim). About 4 KB per keyframe with overhead. |
| `memory_vector` (+ 2 indexes) | 24,068 (+3,220) | 16,929 text vectors × 1,024 B (256-dim). **About 1.61 KB per memory.** |
| `memory` (+ 3 indexes) | 7,840 (+3,048) | About 460 B per row; caption text averages 126 chars, a SHORT fact 59-65 chars. |
| `frame_source` (+ 2 indexes) | 4,244 (+5,956) | 22,693 rows, about 450 B each. SHORT facts have 1.27 rows each (4,299 / 3,381). |
| `memory_fts_*` (FTS4) | 2,316 | About 136 B per memory. |
| `frame_meta`, `frame`, `frame_state` | 3,772 / 1,736 / 1,436 | Per photo: kept by this design. |
| `moment` + `moment_part` (+ indexes) | 716 + 1,172 (+984) | |
| `forget_tombstone` (+ index) | 752 (+772) | 10,352 rows. Only 9 belong to legacy frames without `frame_meta`, and 9 `frame` rows lack `frame_meta`. |
| `nudge`, `ask`, `canvas_meta` | 156 / 72 / 8 | |

- **Memories by tier and type.**
  - LONG: caption 13,213; cluster_summary 315; place 20.
  - SHORT: object 1,449; place 838; person 700; event 333; note 61.
  - So 3,381 SHORT facts survive (23-24 Nov). MEDIUM has 0 rows. `memory_source` has 0 rows.
- **Capture rate (by `frame_meta.createdAt`, IST).**
  - Keyframes: 10,766 over 24 Oct-23 Nov, which is **about 360/day**. Trip days ran 783 to 1,178.
  - SHORT facts per keyframe: 3,173 / 856 on 23 Nov = **3.7**. That gives **about 1,330 facts/day** on average and about 3,700/day on trip days.
- **Cost per SHORT fact.**
  - Without its vector: about 1.26 KB (row 410 B, indexes 180 B, FTS 100 B, `frame_source` 570 B).
  - The vector adds 1.61 KB, for **2.87 KB** in total.
- **Photos.** 197 MB of JPEGs for 4,657 photos (`photos.tar`), so 42 KB each and **about 15 MB/day** on average. `core.tar` (the DB) is 108 MB.

## 2. Semantics after the change

| Concept | Today | v3 |
|---|---|---|
| `memory.tier` | Retention window per tier | **Recall hint.** The values are not changed (no enum change; SHORT stays on extracted facts). |
| "Short-term 48 h" | Deletes SHORT facts 48 h after capture | Renamed **"Quick recall: 48 h"**, with choices 12 / 24 / 48 / 72 h / 7 d. Within the window a fact is in default Ask (text + vector) and Home. After it, the fact is **demoted**, see the next row. |
| A demoted fact | — | Its text vector is deleted. It is excluded from default lexical and semantic pools and from Home lists, where it is folded under its photo or Moment as "+N details". It is **still** shown in Moment and photo detail, Activity's "show details", and the sources of nudges, answers and cards. It is **still** found by time-window questions ("what did I see at Golconda on 21 Nov": `lexicalCursorWindow`). |
| Medium-term / Long-term | Deletes MEDIUM / LONG rows after N days | Removed from the screen: they no longer delete anything. The prefs keys are kept and ignored, so a downgrade reads sane values. LONG memories (captions, place, summaries) are never demoted. |
| Caption / Scene summary days (per kind) | Delete captions / cluster summaries after N days | Folded into Auto-delete (K4). Until then they are **ignored**, whatever value is stored. The user has both at Forever. |
| Photos / Skipped photos / Image vectors | Delete after N days (storage-only since r1) | Unchanged meaning. Every photo expiry now writes the expiry marker (§3.2). |
| Soft limit (MB) | Banner only | **Cap.** Over the cap, quota eviction removes the oldest eligible photos until usage is at 90% of the cap. It never removes text or a photo of explicit importance, and nothing younger than 14 days (K6). |
| "Pause clean-up" (Developer) | Skips everything, including the file retry | Skips the deleting steps: photo expiry, vector demotion and image vectors (quota eviction per K6). The **FrameSweep** retry, which finishes deletes that already committed, including a user Forget's JPEGs, always runs (r2-7). |
| Auto-delete details (new, K4) | — | Off / 90 / 365 days. **Off by default.** When on, it runs as a **user Forget** of the memories older than N days, through the existing `removeDescriptions` path: rows, vectors and edges go, derived memories are invalidated and rebuilt, and citing nudges and answers are redacted with the wording "Removed by your auto-delete setting". It is never a storage-only delete. |

**Settings copy** (`MemorySettings.retentionNote`, `SHORT_TERM_FACTS_NOTE`) is replaced by:

> "Details from your photos stay in quick recall for 48 h. After that they are kept for your Moments, cards, nudges and answers, and for questions about a day, but leave everyday search. Nothing you saw is deleted by clean-up; only Forget (or Auto-delete, if you turn it on) removes text. Photos follow the Photos setting and the storage cap."

## 3. Codex findings, one by one

**Status key:**
- **GONE**: cannot occur once text is never expired.
- **CLOSED**: already fixed by r1 and unaffected.
- **REMAINS**: photo-side; fixed in §3.2.

| # | Finding | v3 status | Why / fix |
|---|---|---|---|
| r1-1 | Photo expiry drops Moments / Scenes / radius | CLOSED (r1 `expireFrameRows`) | Keep the storage-only photo path. |
| r1-2 | Memory expiry of legacy frames drops holding and outputs | **GONE** | No memory expiry. |
| r1-3 | Legacy orphan CARRIED cascade | CLOSED (`expireOrphanFrames`) | It gains the marker (r2-6). |
| r1-4 | Later Forget loses provenance (edges deleted) | **GONE** | Edges and rows are never deleted by retention. A later Forget walks the live graph, as it always did. |
| r1-5 | Expiry withdraws suggestions | **memory side GONE**; photo side REMAINS | `SourceLiveness.goneMemories` never sees an expired fact. Photos use the marker (r2-3, r2-6). |
| r1-6 / 7 / 8 | MomentRepair overwrite / Scene resume / bounds | CLOSED | Keep as the one-shot repair for 19-22 Nov. Retention no longer breaks Moments. |
| r1-9 | Nudge preview hides missing | CLOSED | Still needed when a source is user-forgotten. |
| r2-1 | Sequential expiry loses closure | **GONE** | No lineage is written. |
| r2-2 | Photo lineage seeded from unrelated photo B | **GONE** | No lineage. The `lin:` read is removed (§5, WP1). |
| r2-3 | Forget fact keeps `frame_meta`, so the photo is read as expired | REMAINS | The **expiry marker**: forgotten = tombstoned and not marked. Every user Forget that tombstones a photo clears its marker in the same transaction. |
| r2-4 | Forget after photo expiry has no `ts`, so no radius cleanup | REMAINS | The capture time is read from `frame.ts`, then `frame_meta.capturedAtMs`, then `frame_meta.createdAt`, then `marker.ts` (the marker carries `ts` for legacy frames). Applied in the service, executor and planner, so the preview equals the delete. |
| r2-5 | Place card history `frameId` nulled on expiry | REMAINS | Expiry sets `photoGone=1` only and keeps `frameId` as private lineage. A Forget still matches `deleteCardLogForFrames`. |
| r2-6 | Legacy expiry (no `frame_meta`) read as Forget | REMAINS | The marker. Inference from `frame_meta` is removed. |
| r2-7 | Pause skips the file retry; no bounded storage | REMAINS | FrameSweep runs before the Pause check. Quota eviction is its own step (K6). |
| r2-8 | Lineage never compacted | **GONE** | Nothing is recorded. Existing `lin:` keys are purged once (K8). |
| r2-9 | Pin sweep clears the link of a Moment whose photo expired | REMAINS | `photoMomentsOf` counts a second as live when its photo is live **or marked expired**. A user Forget clears the marker, so the link goes. |

**Score:** 9 findings go away (r1-2, r1-4, the memory half of r1-5, r2-1, r2-2, r2-8, and the text halves that made r1-3 and r1-5 dangerous). 4 stay closed. 6 remain, all photo-side, and need about 4 small mechanisms.

### 3.2 The remaining mechanisms (minimal and robust)

**(M1) Expiry marker.**

- **Format.** A `canvas_meta` row `rx:f:<frameId>` → `{"at":<expiryMs>,"ts":<captureMs|null>}`. There is no Room change (v17). It holds ids and times only, no text.
- **Written** inside the same `db.withTransaction` as the tombstone and the `frame` row delete:
  - `ForgetService.expireFrameRows` (which covers both `expirePhotosTx` and `expireOrphanFramesTx`);
  - `ForgetExecutor.expirePhotos` / `expireOrphanFrames`;
  - quota eviction (which reuses `expirePhotos`).
- **Cleared** in the same transaction by **every** user path that tombstones or erases a photo:
  - `cascade` with `lockFrames && redact`, legacy and executor;
  - `forgetMomentTx` / `executor.forgetPhoto`;
  - `MomentForget` range / Scene;
  - `forgetSelection` (through the above);
  - `removeDescriptions` (no lock, so it clears nothing);
  - Auto-delete (`removeDescriptions`: no photo is touched).

  One helper, `ExpiryMarks.clear(frames)`, is called next to every `insertTombstone` / `PhotoFamily.lock` on a user policy. A golden test enumerates the entry points.
- **Read** by `ProvenanceGate.forgottenFrames(ids)`, which becomes `tombstoned − marked`:
  - The `keyframeIds` inference goes.
  - It fails closed: if the marker read throws, every tombstoned id counts as forgotten, which matches today's catch in `SourceLiveness`.
  - The same `marked` set feeds `MomentDao.photoMomentsOf` / `firstFrameOf` for pin liveness (r2-9), and the `ts` fallback (r2-4).
- **Bounded.** `FrameSweep.pruneTombstones` also deletes the `rx:f:` keys of the tombstones it prunes. A tombstone is pruned only when no row, memory or `frame_source` remains: true for skipped photos, never for a described photo, whose facts are kept. Worst case is about 80 B per expired photo, against the 42 KB saved.
- **Wiped.** `WipeDao` (Delete all) adds `DELETE FROM canvas_meta WHERE key LIKE 'rx:%'`, next to the `lin:` line at `WipeDao.kt:56`. Export and backup: `canvas_meta` is already outside export, and `context.db` is excluded from backup and transfer. `DeletionGraphInventoryTest` lists the new key family.
- **Why not a flag in `forget_tombstone`:**
  - A schema change means v18.
  - `insertTombstone` is `IGNORE`, so a later Forget could not overwrite it.
  - A sign hack on `createdAt` would break `pruneTombstones` and FrameSweep.

**(M2) Capture-time fallback.** `ForgetDao.captureTsOf(ids)` uses `COALESCE(frame.ts, frame_meta.capturedAtMs, frame_meta.createdAt)`, then the marker `ts`. It replaces:
- `listOfNotNull(frame?.ts)` at `ForgetService.kt:86`;
- the `dao.frame(it)?.ts` mapping in `dropCanvasOutputs` (`:300`);
- the matching lines in `ForgetExecutor` (forgetPhoto, `dropCanvasOutputs`);
- `ForgetPlanner.kt:188,256`.

**(M3) Place history.** A new `PlacesDao.markCardLogPhotoGone(frameIds)`: `UPDATE place_card_log SET photoGone = 1 WHERE frameId IN (...)`.
- `PlaceCardHistory.onPhotosExpired` calls it instead of `unlinkCardLogFrames`, which is deleted.
- The history UI must not load an image when `photoGone=1` (check the renderer; it currently relies on `frameId == null`).
- No places.db migration.

**(M4) Pause / quota split.** `RetentionWorker.retain()` runs in this order:
1. `FrameSweep.sweep` (always);
2. quota eviction (per K6: independent of Pause, or under it);
3. `if (pause) return` with the counts-only line;
4. demote, skipped photos, linked photos, image vectors, legacy orphans;
5. Auto-delete, if on.

Quota eviction:
- **Selection.** `ForgetDao.quotaPhotosDue(minAgeMs, limit)`: oldest `frame.ts` first, keyframes only, described (the `DESCRIBED_BY_EXTRACTION` rule, so describe-before-delete holds), not tombstoned.
- **Exclusions.** It excludes the photos of explicit importance: a `type='place'` memory, a mark within the radius, a pin reference Moment, a photo cited by an EXECUTING/EXECUTED proposal's origin.
- **Usage measurement.** `StorageUsage.usedBytes` is moved into the memory module, or passed in as a lambda so the module stays free of orchestration.
- **Bounds.** It evicts in chunks of 200 until usage ≤ 90% of the cap, or nothing is eligible, or the round cap is hit. When stuck it logs `quota over, nothing evictable` and shows the banner.

## 4. Storage, size and latency estimates (user rate: 360 keyframes/day, 1,330 facts/day; trip about 3×)

| Horizon (from now) | Facts kept (text only) | Facts kept **with** vectors (not proposed) | DB total (v3) | Photos (Forever) | Footprint v3 |
|---|---|---|---|---|---|
| 1 month | +40k facts, **+51 MB** | +115 MB | about 107 + 72 baseline + 51 = **230 MB** | +450 MB, **about 650 MB** | **about 0.9 GB** |
| 12 months | +485k facts, **+610 MB** | +1.39 GB | about 107 + 880 + 610 = **1.6 GB** | +5.5 GB | about 7.1 GB, but **the 2000 MB cap binds at about day 88 (around 20 Feb 2027), sooner with trips** |

- **Baseline** (captions, image vectors, photo rows: what is kept today anyway) is about 2.4 MB/day. Facts add about 1.7 MB/day, photos about 15 MB/day. **Text is about 9% of growth**, and quota eviction acts on photos.
- **FTS.** About 136 B per doc, so 12 months adds about 70 MB in FTS4 segments. Automatic merges keep the segment count low. No action.
- **Backups and dumps.**
  - `core.tar` follows the DB size: about 230 MB at 1 month, about 1.6 GB at 12 months.
  - The JSON memory export grows about 300 B per fact, about 150 MB per year.
  - Android backup excludes `context.db`, so it is unchanged.
  - Long term, the image vectors (4 KB per keyframe, about 1.4 MB/day) are the cheapest thing to cap after photos. The existing `image_vector_days` covers that.
- **Latency: why the vectors must go at demotion.**
  - `BruteForceVectorIndex.scan` reads **every** live vector of the space in pages of 500 for every semantic query (`VectorIndex.kt:51-72`). Today that is 16.9k × 1 KB, about 17 MB per query.
  - Keeping fact vectors for 12 months: about 630k vectors, about 650 MB read per Ask. That is seconds. Not acceptable.
  - With demotion: about 130k caption + summary vectors per year (about 135 MB per query at 12 months). That is a **pre-existing** scaling limit, independent of this change. Note it for a later fix (a time-sliced scan or an ANN index); this design does not make it worse.
- **Lexical.** `LexicalRetriever` computes BM25 in Kotlin for **every** FTS match of an OR query (`LexicalRetriever.kt:18-39`). The default cursors get the predicate `AND (m.tier != 'SHORT' OR m.createdAt >= :recallSince)`, so the Kotlin work is bounded by recent facts plus LONG. The window cursor stays unfiltered (its window bounds it).
- **Quality.**
  - The caption of the same photo already carries its facts in about 126 chars. With demoted facts out of the default pools, top-k is not crowded by four near-duplicates per photo.
  - Inside the recall window, today's behaviour is unchanged.
  - Time-scoped questions gain: before, the facts were simply gone after 24-48 h.
  - The existing recency boost (3-day half-life) stays. A tier multiplier is not needed once the pools are separated.

## 5. Exact changes per file

**WP1: no text expiry (S).** Files: `ForgetService`, `ForgetExecutor`, `RetentionWorker`, `ForgetDao`, `EmbeddingDao` / `EmbeddingSpaces`.

- **`RetentionWorker.kt`**
  - `Retention.runPass`: delete step 2 (tier windows, `:148-159`) and step 3 (caption / cluster summary, `:161-168`).
  - Add step **2'** (demote), behind Pause:
    - `dao.demotableFactIds(nowMs - s.recallHours*HOUR, CHUNK)`: SHORT, `type NOT IN ('caption','cluster_summary')`, older than the window, has a vector;
    - then `withTransaction { embeddingDao.deleteVectorsFor(ids) }`.
  - `plan()` / `Plan`: replace the `shortDue / mediumDue / longDue / captionDue / clusterSummaryDue / nudgesKept / answersKept` fields with `demoteDue`, `quotaDue` and `autoForgetDue`. The template stays literal, counts only.
  - The `done` line drops `memoriesExpired` / `nudgesRedacted` / `answersRedacted` and adds `demoted` and `quotaEvicted`.
  - `retain()` gets the order from §3.2 M4.
- **`ForgetService.kt`**
  - Delete `expire(ids)` and `expireTx`.
  - In `cascade`: delete the `!redact` lineage `record` block (`:206-209`) and the `lineage` reach / consume (`:204-205`, `:262`), and drop `lineage.expired` from `cited` and `dropHolding`.
  - `redact=false` then has no caller. Remove the parameter, or keep it with `require(redact)`. Proposed: remove it.
  - In `expireFrameRows`: write `ExpiryMarks.mark(id, frame.ts)`.
  - Add `ExpiryMarks.clear` next to the tombstone at `:84` and `:228`.
  - Apply the `captureTsOf` fallback (M2).
- **`ForgetExecutor.kt`**
  - Delete `expire()` (`:146-150`) and the lineage lines in `cascade`.
  - Mark in `expirePhotos` / `expireOrphanFrames`.
  - Clear in `PhotoFamily.lock` under a user policy, or at the call sites.
  - Apply the `ts` fallback.
- **`ExpiredLineage.kt`**: delete. **`WipeDao.kt:56`**: keep the `lin:` wipe and add `rx:`.
- **One-shot purge (K8).** `DELETE FROM canvas_meta WHERE key LIKE 'lin:%'`, under a `canvas_meta` done marker. No field device has any (the candidate ships with Pause ON, and rc.1.0.0 predates lineage); the device test checks for 0.
- **`ForgetPlanner.kt` / `Families.kt`**: remove `Policy.Retention` for the memory family (`Families.kt:260` retention fate); keep `Policy.RetentionPhotos`; apply the `ts` fallback at `:188,:256`.
- **`ForgetDao.kt`**
  - Drop `expiredIds` / `countExpired` / `expiredIdsOfType` / `countExpiredOfType` / `countNudgesCitingExpired` / `countAsksCitingExpired`.
  - Add `demotableFactIds`, `countDemotable`, `captureTsOf` and `quotaPhotosDue`.
- **Embedding eligibility (`EmbeddingDao` live / eligible queries, `EmbeddingSpaces.coverage`)**
  - Exclude demoted SHORT facts (`tier='SHORT' AND createdAt < :recallSince`), so the backfill never re-embeds them and coverage logs stay true.
  - This is a query rule, so there is no per-memory marker and the rule moves with the setting.
  - Widening the window later re-embeds them: acceptable and model-free if the local embedder is used. Check the bound embedder's cost before allowing it.

**WP2: marker and the photo-side fixes (S).** Files: new `memory/forget/ExpiryMarks.kt` (put / clear / marked / ts, chunked at 400, ids only), `ProvenanceGate.forgottenFrames`, `MomentDao.photoMomentsOf` / `firstFrameOf`, `FrameSweep` (prune markers with tombstones), `WipeDao`, `PlacesDb.kt` (+`markCardLogPhotoGone`, −`unlinkCardLogFrames`), `PlaceCardHistory.onPhotosExpired`, the place-history renderer (`photoGone` check). **Note:** `PinMomentLinks` needs no change; it reads `photoMomentsOf`.

**WP3: retrieval (S).** Files: `MemoryDaos.kt` / `EmbeddingDao` lexical and live cursors (the demote predicate on the default cursors, not on the window cursors), `MemoryRetriever` (pass `recallSince`), Home / Activity lists (fold demoted facts under their photo or Moment), Moment and photo detail (show all facts, unchanged).

**WP4: quota and pause (S-M).** Files: `RetentionWorker` (order, quota step), `ForgetDao.quotaPhotosDue`, a storage-usage lambda, the banner copy in `StorageUsage`, the Developer Pause copy.

**WP5: settings and copy (S). Auto-delete is M if built now.**
- **`MemorySettingsStore.kt`**
  - Add `recallHours` under a NEW key `recall_hours` (choices `[12,24,48,72,168]`). When the key is absent it reads `short_hours`, so an existing 48 stays 48. `short_hours` is never written with a value the candidate's `clean()` would reject (§7). **No migration step.**
  - `mediumDays` / `longDays` / `captionDays` / `clusterSummaryDays` stay readable and are ignored.
  - Add `autoForgetDays` (key `auto_forget_days`). An absent key means FOREVER, which means off.
- **`MemorySettings.kt` (phone UI)**: rename the rows, hide Medium / Long, change the notes (§2) and the Auto-delete row, which has a preview count and a confirm dialog.
- **Auto-delete run**: `service.removeDescriptions(ids)` in chunks, with a wording parameter. The `REDACTED` text is chosen by the caller: "Removed by your auto-delete setting". After it: `ForgetListeners` `onUserForget` (Suggestions sweep) and MomentRepair for the touched days.

## 6. Privacy

- **Facts are kept longer than the user's configured "48 h".** This is a semantic change to a privacy setting, so it must be visible:
  - the new labels and copy (§2);
  - a one-time notice on the first launch of the build: "Clean-up no longer deletes the details of your photos. Forget removes them; you can turn on Auto-delete in Settings → Memory";
  - a release note.

  No silent reinterpretation.
- **Explicit Forget is unchanged and still complete.**
  - Because rows and edges survive, a Forget sees the full graph. It is **stronger** than in v2, which depended on lineage.
  - Explicit photo Forget still redacts and withdraws. The marker is cleared in the same transaction, so a forgotten photo can never be read as "expired".
- **Auto-delete details (K4)** is the hard "delete text after N days" option. It is safe because it **is** a user Forget:
  - it shows the redacted wording;
  - it withdraws suggestions;
  - it rebuilds Moments and summaries.

  It never creates expired-but-cited dangling ids, so no lineage is needed. Its limit, stated in the copy: a photo's caption (LONG) and the photo itself follow their own settings, so Auto-delete removes the *details*, not everything about the photo.
- **Demotion is not deletion.** A demoted fact is still on the phone, in Export and in a manual dump. The copy says so.
- **Logs and markers** hold ids, times and counts only.

## 7. Migration and the 26 Nov 00:50 run

- **Room stays v17.** places.db stays v2. Only new `canvas_meta` key families (`rx:`), new prefs keys and query changes. No enum change.
- **Downgrade to the current candidate:**
  - The candidate would see `short_hours=72/168` as invalid; `clean()` maps it to 24, which is a hazard. **Mitigation: write the new recall value to a NEW key `recall_hours`** and leave `short_hours` at its last legal value. Pause stays ON in the candidate in any case.
  - `rx:` keys are ignored by older builds.
- **Ships with Pause ON until this lands.**
  - The phone runs rc 1.0.0, which has no Pause: the 26 Nov ~00:50 run will expire the 23 Nov SHORT facts unless the Pause-ON candidate is installed first. This design does not change that deadline.
  - The v3 build can default Pause OFF (K7), because the only deleting steps left are the ones the user configured: skipped photos, quota (photos Forever) and demotion (vectors only).
- **First run of v3.**
  - Demotion of every SHORT fact older than the window: about 3.4k vectors, one chunked pass.
  - Purge of `lin:` (K8).
  - No text is deleted.

## 8. Tests (golden world)

**World** (memory + orchestration, JVM Robolectric):
- keyframes P1-P6 with SHORT facts, captions and a cluster summary, plus a summary edge chain A→B;
- legacy frame L (no `frame_meta`);
- nudges citing facts, with a `dependsOn` chain;
- answers citing facts and `frame:P1`;
- Moments and Scenes;
- a mark within the radius of P2;
- a pin on the Moment of P3;
- a place memory and card-history row on P4;
- an EXECUTED proposal;
- Suggestions records citing `mem:`, `frame:`, `pin:` and `mark:`.

Every test runs with `ForgetV2.testOverride = true` and `= false`.

| Test | Asserts |
|---|---|
| `RetentionKeepsTextTest` | With `recall=12h`, the old windows set to 30 d, and the clock advanced by 400 days: the memory row count and every nudge / answer / proposal row stay byte-equal; Moments and Scenes count unchanged; 0 `lin:` keys; plan `demoteDue` equals the run's `demoted`. |
| `DemoteTest` | Demoted facts have no vector, are excluded from the default lexical and semantic pools and Home, and are included in the window cursor and Moment detail. The backfill does not re-embed them (coverage eligible excludes them). A caption is never demoted. |
| `ExpiryMarkTest` | The marker is written in the expiry transaction (an injected failure leaves no marker and no tombstone). `forgottenFrames`: expired P → ∅. Forget fact of P (`frame_meta` kept) → {P} (r2-3). Expired legacy L → ∅ (r2-6). Expired then user-forgotten P → {P}. A marker read failure → all tombstoned. |
| `ExpiryMarkEntryPointsTest` | Every user path that tombstones (fact, photo, range, Scene, selection) clears the marker: an enumerated list, failing on a new unlisted path. |
| `SuggestionsSurviveExpiryTest` | Photo expiry plus an unrelated user Forget, then `ProactiveForgetListener.sweep`: the record citing the expired photo stays; the one citing the forgotten photo is withdrawn with its surfaces. |
| `ForgetAfterExpiryRadiusTest` | Expire P2, then Forget P2: the mark inside the radius goes, `rangeForgotten` carries the span, the glasses commands are queued; the planner preview equals the delete (r2-4). Same for legacy L, using the marker `ts`. |
| `PlaceHistoryExpiryTest` | Expire P4: the row stays with `frameId` and `photoGone=1`, and no image renders. Forget P4: the row is deleted (r2-5). |
| `PinLinkAfterExpiryTest` | Expire P3, then the pin sweep: the link is kept. Forget P3: the link is cleared (r2-9). |
| `PauseIndependenceTest` | Pause ON: no photo expiry and no demotion. FrameSweep still deletes a user-forgotten JPEG left on disk. Quota behaves per K6 (r2-7). |
| `QuotaEvictionTest` | Over the cap: the oldest described, eligible photos go until usage ≤ 90%. Place / mark / pin / accepted-proposal photos, undescribed photos and photos under 14 d are never evicted. Text is untouched and markers are written. When stuck: a log line and the banner. |
| `AutoForgetTest` (if K4 = now) | Off by default. When on: redaction with the auto-delete wording, suggestions withdrawn, Moments rebuilt, the preview count equals the run, no photo touched. |
| `ForgetClosureRegressionTest` | (r2-1, r2-2 as guards) A user Forget of photo A after any number of retention runs reaches B-derived answers through the live edges; Forget A never redacts answers citing photo B's facts. |
| `SettingsMigrationTest` | Prefs `short_hours=48, medium=14, long=365, photo=FOREVER`, then upgrade: `recall_hours=48`, no deletion on the first run, and the candidate's `clean()` still reads a legal `short_hours`. |
| `RetrievalScaleBench` (instrumented, not CI-gating) | A synthetic 12-month DB (about 600k memories, 130k with vectors): Ask retrieval p95 and DB size against the §4 numbers. |
| **Device test** (required before hand-off) | Emulator restored from the 24 Nov dump, user settings, clock advanced 3 days, then run retention: 0 memories deleted, 0 redactions, 0 `lin:` keys, Moments for 23-24 Nov present, demoted count about 3.4k. Then the same on the phone after a fresh dump. |

**Tests to delete or invert:**
- the tier-expiry cases in `RetentionWorkerTest` and the r1/r2 lineage tests (`ExpiredLineage*`);
- the "expiry keeps the anchor" Moment test, which becomes `PinLinkAfterExpiryTest`.

## 9. Effort and order

| Order | WP | Effort | Note |
|---|---|---|---|
| 1 | WP1 + WP2 (one worktree: `ForgetService`, `ForgetExecutor`, `RetentionWorker` overlap) | S + S | Removes about 150 lines of the lineage and expiry paths; the marker adds about 60. |
| 2 | WP3 retrieval (parallel worktree) | S | |
| 3 | WP4 quota / pause | S-M | Needs K6. |
| 4 | WP5 copy + settings; Auto-delete if K4 = now | S (M with Auto-delete) | |
| 5 | Device test on the emulator from the dump, then the phone | S | Never skipped. |

- **Overall: M**, about 2 Sonnet days plus one review pass: Codex sol at medium, or Opus at low effort if the Codex quota is out.
- **Lands before the Sikkim install freeze (before 1 Dec).** Until then the candidate keeps Pause ON.

## 10. Rulings needed from the user

- **K1** Adopt "retention never deletes text; only Forget, or Auto-delete, does"? *Proposed: yes.*
- **K2** Rename Short-term to **Quick recall** (12 / 24 / 48 / 72 h / 7 d). After it, details lose their vector and leave everyday search and Home, but are kept for Moments, cards, nudges, answers and day questions. *Proposed: yes; keep the user's 48 h.*
- **K3** Remove the Medium / Long rows (they no longer delete anything)? *Proposed: yes.*
- **K4** Offer **Auto-delete details** (Off / 90 / 365 d), run as a user Forget, with redaction and its own wording. Build now, or after the trip? Does it also cover captions and scene summaries (replacing their per-kind rows)? *Proposed: after the trip, covering all text; default Off.*
- **K5** Is the one-time notice wording in §6 acceptable?
- **K6** Make the 2000 MB cap **hard** (evict the oldest eligible photos, ≥ 14 days, never explicit importance, never text)? Should it run even while Pause is ON? *Proposed: hard; it runs under Pause too, because it is the storage bound.*
- **K7** Default "Pause clean-up" OFF in the v3 build? *Proposed: yes, once the device test passes.*
- **K8** Purge any `lin:` keys on the first v3 run (expected 0 on the phone; checked read-only before install)? *Proposed: yes.*
