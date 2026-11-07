# 2026-11-08 quality fixes: semantic search off, CAPTION_QUEUED backlog

Branch `feat/v099-watch`. Evidence: `quality-eval` report-2 (2026-11-08 01:40) and the phone log of 2026-10-31 15:45 to 2026-11-07 21:29
(`phone_pca.log.1` + `phone_pca.log`, 80,689 lines). Frozen items are untouched: PROTOCOL_VERSION 1, TRACE_SCHEMA 1, plan hash f5782e0d8610,
Room v15 (no schema change, no migration).

## A. Semantic search is never available: SETUP (no Embeddings model), plus a code bug (it degraded silently)

### Evidence
- `POST /api/retrieve/debug`: `semanticUnavailable` on 200 of 200 queries in SEMANTIC and in HYBRID, mode used LEXICAL every time.
- Phone log over 7 days: **0** lines from `EmbeddingWorker`, `IndexMaintenance` or `SemanticRetriever`. That rules out: a run failing (`index run failed` /
  `index config failure`), the on-device battery gate (`on-device index work waits for a charger`), the pause path (`pipeline paused: index work
  left for resume`), drift (`canaries drifted`), and an embed exception at query time (`semantic search unavailable (...)`).
- The night job logs the outcome of its `IndexMaintenance.run()`: `NightIndex PcNight described=99 left=451 backfilled=IDLE` (11-01 23:30) and
  `described=120 left=262 backfilled=IDLE` (11-06 01:08). The only way `run()` returns `IDLE` is that `embedders.bound()` is null: there is
  **no EMBED role binding** (`IndexMaintenance.kt` line 202).
- Why it is unbound: `EndpointSeeder` and onboarding bind every role except EMBED (`if (role == Role.EMBED ...) continue`). EMBED is bound only when the
  user downloads Nomic Embed and picks it for Embeddings, or binds an embeddings endpoint by hand. Neither happened.
- With no binding, no embedding space is ever created, so `EmbeddingSpaces.queryable()` is null and `SemanticRetriever.search` returns null on every
  query (lexical fallback, R-51). That is by design. **The bug:** nothing told the user. The Home banner hid the state (`IndexState.of` returned NONE
  when `embedBound` was false). The Ask path and `/api/retrieve/debug` only had a boolean. The one hint was the Settings > Retrieval summary "Not set".

### Classification
(2) A prerequisite the user has to set up, which the app hid. It is not a vector or space-version mismatch, and the worker was not stuck: the worker
ran and did nothing because nothing was bound. It is not (3) either: there is no space to backfill.

### What the user must do (pick one)
1. On-device, no cost: Settings > AI > On-device models > download **Nomic Embed**, then **Use for Embeddings**. The index builds in the background, but
   only while the phone is charging or above 70 % battery (Gaps G6). Home shows "On-device search paused to save battery" while it waits.
2. Remote: Settings > Model endpoints > add an endpoint with the Embeddings capability (for example an Azure OpenAI `text-embedding-3-small`
   deployment), then bind **Embeddings** to it in the roles list.
Either way `EmbeddingWorker` is enqueued on the binding change, builds a BUILDING space, then swaps it to ACTIVE once every memory has a vector.

### Fixes (code)
- `SemanticRetriever.searchOrOff` returns the reason: `SemanticOff` = NO_INDEX, INDEX_BUILDING, INDEX_STALE, MODEL_CHANGED (the endpoint moved, so
  `forSpace` is null), QUERY_FAILED. `RetrievalResult.semanticOff` carries it, and it is set exactly when `semanticUnavailable` is.
- Ask: `AskOutcome.Answered.notice` = "Semantic search is off: <reason>. This answer used keyword matches only." Home shows it under the answer
  (`reply`). It is never sent to the model, never stored and never mirrored to the HUD.
- Home banner: new `IndexState.EMBED_NOT_SET` = "Semantic search is off · no Embeddings model", with **Set up** linking to Model endpoints. It does
  not show when the user chose Lexical mode.
- Settings > Retrieval > Try it: "Semantic search is off: <reason> — showing lexical results".
- `/api/retrieve/debug` adds `semanticOff` (the reason token) alongside `semanticUnavailable`. The field is additive and the route is unchanged.
  `tools/quality-eval.py` tallies the reasons.

## B. 262 frames stuck in CAPTION_QUEUED: DATA BACKLOG behind a scheduling gate that rarely opens (code fix: catch-up run)

### Evidence
- All 262 are night-queue photos (`lastError = describe_before_delete`). The night job's own count matches exactly: `PcNight ... left=262` on 11-06
  is the same 262 that report-2 found on 11-08. These rows are excluded from the live loop (`captionQueued()` filters them out), so **only
  `NightIndexWorker`** drains them, and that is by design.
- `NightIndexWorker` is a 24 h periodic job with `setRequiresCharging(true).setRequiresDeviceIdle(true)`. In the log it ran on **2 of 8 nights**
  (11-01 23:30 and 11-06 01:08). `PcNight gate closed` was logged 0 times, so whenever it did run, its bulk gate was open.
- Each run is bounded (9 min, 60 calls of up to 3 photos): 39 BACKFILL claims for 99 photos on 11-01, and 64 claims for 120 photos on 11-06
  (00:49:50 to 01:08:19). At about 110 photos a run and 2 runs a week, the queue (550 on 11-01) cannot drain while the job queues more every
  night. Ages of 315 to 539 h fit that.
- The vision HTTP 400s are **not** the blocker. All 26 `vision failed, retrying as text: ProviderHttpException(kind=REJECTED, http=400)` are
  daytime live EXTRACT calls (11-02 18:19 to 11-05 20:13, 26 of about 1,567 EXTRACT claims, 1.7 %). None falls inside a night drain window.
- Not dead-lettered: `describe_failed` parking needs 5 failed attempts, and no drain stopped on failures.
- Not the hub stop: the worker was scheduled (it ran twice). What blocks it is the device-idle + charger constraint.

### Fixes (code)
- **Catch-up run** (`OrchestrationScheduler.ensureNightIndex`): a second unique periodic job, `night_index_catch_up`, every 6 h, needing a charger and
  battery not low, without the device-idle constraint. It runs the same bounded `NightIndex` (bulk gate, stop on a capture burst, 9 min,
  60 calls). It is a no-op unless photos are waiting **and** no night run has completed in 30 h (`NightCatchUp.due`). A pause cancels it, as it
  does the night job. Expected throughput is about 180 photos per charger session, so 262 drain in about 2 charging sessions.
- Diagnostics: new line "Photos waiting to be described: N · described while charging, up to 180 a run" (`kf_describe_waiting`).

### The vision HTTP 400 (is it ours?)
- The frames are about 20 KB JPEGs (`ingestFile size=20033`), well within the provider's limits. The 400s come in clusters (12 between 11-03 00:18
  and 01:32), which points to content and away from format. **The cause is not proven.** The log line carried only the kind and the HTTP code.
- Fix: `ProviderHttpException.reason` maps the error body to a fixed token (`content_filter`, `image_rejected`, `too_long`, `format_unsupported`,
  `model_not_found`), and `SafeLog.describe` appends `reason=...`. The body itself is still never logged. The next device log decides: `content_filter`
  is the provider's policy, while `image_rejected` is ours (then look at the encoder).
- **Open decision (not changed):** a VISION_EXTRACT call whose photo is refused is retried **as text without the photo**
  (`RoleRunner`: `requireImages` is true only for INTENT_CLASSIFY). That can save a memory guessed without seeing the photo. Making VISION_EXTRACT
  `requireImages` would turn those 26 into retried and then FAILED_FINAL frames. That is a product call, so it is left to the controller.

## Verification (the controller installs and reruns)
1. Rebuild and install. On Home, the banner "Semantic search is off · no Embeddings model" should show (mode Hybrid). Ask something: the
   "Semantic search is off: no Embeddings model is set..." line should appear under the answer.
2. `tools/quality-eval.sh retrieval --out <file> -n 100 --seed 7` **before** binding a model: SEMANTIC and HYBRID should still show `semantic unavailable on 200 of 200`, now
   with `(reasons {'NO_INDEX': 200})`. That proves the diagnosis.
3. Bind Embeddings (step A.1 or A.2), keep the phone on a charger until Settings > Retrieval shows the index ACTIVE, then rerun the same command.
   Expected: SEMANTIC and HYBRID `mode used {'SEMANTIC': 200}` / `{'HYBRID': 200}`, `semantic unavailable on 0 of 200`, and HYBRID paraphrase
   R@5 / MRR@10 above LEXICAL's 0.900 / 0.785 (the tfidf rows can stay about the same). On the real asks: `fallback` falls from 5 of 5, and the
   output 'I don't know' share (30 %) falls.
4. Backlog: after the phone has been on a charger for a few hours (not necessarily idle), the log shows `NightIndexWorker PcNight catchUp left=N` and then
   `PcNight described=... left=...`. Diagnostics "Photos waiting to be described" falls. In quality-eval capture, `pending frames` and
   "Moments without memory" (186) fall toward 0 over 2 or 3 charging sessions.
5. A vision 400 now logs `reason=<token>` (or no reason when the body is not recognised).

## Tests added or changed
- `MemoryRetrieverTest.everyDegradeSaysWhy` covers every SemanticOff, the Lexical choice (no reason), and a healthy run (no reason).
- `RetrieveDebugTest`: `semanticOff = NO_INDEX`. `AnswerServiceTest`: the Ask notice.
- `NightIndexWorkerTest`: catch-up constraints (charger, battery not low, no idle, 6 h, cancelled by a pause) and `NightCatchUp.due`.
- `SafeLogTest`: the reason token, and the body never logged. `BatteryPauseBannerTest`: EMBED_NOT_SET, and Lexical suppresses it (one old assertion
  changed from NONE to EMBED_NOT_SET on purpose). `RetrievalSettingsTest`: the new Try it notice. `DiagnosticsTest`: `kf_describe_waiting`.
- `BackupRulesTest`: the new `night_index` prefs file (the last night-run time, device state) is excluded from backup and device transfer, as `space_retired_at` is. `LogContentScanTest` passes (`PcNight catchUp left=N`).
