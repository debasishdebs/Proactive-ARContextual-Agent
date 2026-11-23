# Place Guide fixes F1-F7: build spec for overnight autonomous building (2026-11-24)

Designer: Opus. Implementer: Sonnet, small ordered steps (section 4). Reviewer: one Codex pass at medium effort at the end. If the Codex quota is out, Opus at low effort reviews instead (never Sonnet, never skipped).
Branch `feat/v101-ask-eval`, `android-companion/`. Inputs: `2026-11-24-offline-trip-replay-harness.md` (the harness, the acceptance tool), `handoffs/trip-gap-analysis-opus-2026-11-24.md` (fix list D), `handoffs/trip-analysis-2026-11-24.md`, and the real replay run B0 (`~/Desktop/rokid/backups/replay-out/run-2026-11-24T05-04-38/`, private; this doc quotes counts only).
Line numbers are at HEAD of this branch on 2026-11-24.

## 0. Rules for every step

- **User rulings (fixed):**
  - Pin-first (location-first) cards for the user's own pins are wanted.
  - Pause logic is untouched: the phone pause, the explicit pause and its reminders are out of scope.
  - A place card at confidence 0.54-0.58 must never be dropped silently.
  - The glasses show a card only as a pop-up of at most 12 s while the screen is on (`PopupGate.kt:7`). So every card must also be kept and recoverable on the phone.
- **No phone writes and no installs.** `adb` may target the emulator only, always with `-s emulator-NNNN` (run `adb devices` first). Never touch Rokid-Coder or its worktrees.
- **Real data never enters git.** The replay bundle and reports stay under `~/Desktop/rokid/backups/` (dir 700, files 600). Commit messages and handoffs quote counts only: no names, coordinates, frame ids or pin ids.
- **Gradle:**
  - Run one Gradle build at a time, machine-wide, across every worktree: `tools/gate.sh <log> --no-daemon <task> --rerun`. Wait for `EXIT=`. Never `pkill`.
  - Commit after each green step, with the attribution line from the session reminder.
- **Logs:** literal text, outcome names, fixed reason tokens, buckets and counts. Never a place name, note, coordinate, frame id or photo. Allowed ids are the ones the `place attempt` line already carries: `wd:`, `osm:`, `user:<uuid>`.
  - Every new field of the sampler goes on the existing `place attempt` line (`PlaceSampler.kt:205`), not on a new line. `PlaceSamplerTest.kt:323` pins that the sampler logs only `place outcome=` and `place attempt outcome=` lines.
- **Privacy class:**
  - Pin-first (F1) and text match (F5b) make no model call and send no photo.
  - Positions are held in memory only (as `PlaceFlight.lastAttempt` is today). They are never stored or logged, except in the opt-in Moment location table (section 7).
- **Pin P1 issues are open** (`docs/superpowers/reviews/2026-11/codex-pin-r1.md`, 5 P1). No step here changes how a pin or its Moment link is stored. F4 only listens for "a pin was saved".

## 1. What the baseline says (B0, counts only)

B0 is the real replay at HEAD, Oracle stage, as created. "Cards" means MATCHED cards for the span's target.

| Span | AS_RECORDED | OUTDOOR_10M | INDOOR_60M | ASSUMED_PIN_10M | ALWAYS |
|---|---|---|---|---|---|
| SJ_ENTRY | 0 | 2 | 0 | 2 | 2 |
| SJ_FOUNDERS | 0 | 0 | 0 | 0 | 0 |
| SJ_BRONZE | 5 | 2 | 0 | 2 | 3 |
| SJ_IVORY | 1 | 1 | 0 | 1 | 2 |
| SJ_EXTERIOR | 1 | 2 | 0 | 2 | 2 |
| GOLCONDA_LS | 5 | 10 | 0 | 10 | 10 |
| RAMOJI | 0 | 0 | 0 | 71 | 0 |
| ANTERA_FACADE | 0 | 0 | 0 | 0 | 0 |
| ANTERA | 0 | 0 | 0 | 51 | 0 |
| ONEGOLD | not testable | | | | |

**Other baseline facts:**
- **Wrong-place cards:**
  - HEAD has 0 in every run.
  - The F1 overlay has 1 in each of OUTDOOR_10M, ASSUMED_PIN_10M and ALWAYS. In each case a neighbouring room pin was carded inside SJ_BRONZE.
  - The calibration run (Historical with ASSUMED sites) has 3. These are the trip's own model choices against site names, not a harness failure.
- **Calibration:** 5 of 6 reproduced, TRUSTED. The one miss is the allowed `same_place_recently` divergence.
- **StubNone × OUTDOOR_10M:** 28 model calls, 0 cards.
- **Invariants in every run:** `NO_FRAME` 169 and `STALE_FRAME` 13.
- **Golconda / Ramoji / Antera card counts (10 / 71 / 51)** are a card storm. A perfect identifier is re-carded every 120 s once DEDUPE lapses (`PlaceSampler.kt:311-315`). The trip's real model hid this by answering NONE.
- **The `ALL` column is a harness artefact.** `CaptionTextOrOracle` (`ReplayReport.kt:169-178`) uses the caption matcher whenever a frame has any text, and that text almost never names a pin. So ALL shows 0 where the oracle alone shows cards. Step H1 fixes this.

**Caption and prompt facts:**
- 2,272 of 3,126 trip frames have caption text.
- No caption has a pin name inside quotes. A distinctive pin-name token appears in 0 to 5 captions per pin, and those hits are object words (statues, carvings), not signage. The restaurant pin's name appears in 0 captions, although the board was readable. The extract prompt never asks for sign text.
- The phone's active `VISION_EXTRACT` prompt is the shipped default (fingerprint `df262c37…`, checked on a temp copy of the dump, since deleted). So a seed upgrade reaches it through `PromptDefaults.PREVIOUS`.

**Confidence floors:**
- `BackendThresholds.ONLINE_MIN = 0.5` and `OFFLINE_MIN = 0.7` (`core/model/src/main/kotlin/com/rokid/context/core/net/ModelBackend.kt:76-85`). They are applied at `PlaceSampler.kt:353-354`.
- So 0.54 and 0.58 pass online, and the trip's cards at those values were published.
- The silent losses are elsewhere:
  1. the offline floor of 0.7 (no on-device model exists yet);
  2. `LOW_CONFIDENCE`, which leaves no trace a user can see;
  3. the glasses pop-up gate, which skips while the screen is off (`MainActivity.kt:253`, only a glasses-local counter);
  4. the 12 s phone expiry (`Hub.kt:666-672`);
  5. replacement by a newer card.

**Per-attempt log (F7):**
- The HEAD decision line exists. `Trace` (`PlaceSampler.kt:171-182`) is logged once per attempt by `finish()` (`PlaceSampler.kt:201-206`, the line at `:205`), with ids, buckets, reason tokens, the dedupe age and distance buckets, the call gap and card/cardWhy/reveal.
- It is on HEAD only; the phone runs `18c1b76a`.
- `tools/place-attempt-report.sh` parses it as key=value, so new fields are additive.

## 2. Decisions

| Item | Default | Kill switch | Rationale |
|---|---|---|---|
| F1 pin-first card | **ON** (inside Place Guide, whose master switch stays OFF by default) | Developer flag `placePinFirst` | User ruling. It uses no model call and no photo, only the fix the sampler already took. It only reaches the user's own pins (radius ≥ 15 m), from a fresh fix of 25 m accuracy or better. Close pins give one honest "several pins" card, never a single guessed room. |
| F2 last-good-fix reuse | **ON** | `placeFixReuse` | It only feeds the candidate list: the photo model still decides, and F1 ignores a reused fix. Bounded to 10 min, accuracy that grows with age, STILL with no movement and the same session. `fixSrc=` on the line measures it on the next trip. |
| F3 dedupe v2 | **ON** | `placeDedupeV2` | Fixes case 1 (a weak first frame blocks the good one). Weak answers get a short back-off window; one card per place while the wearer stays put stops the storm. Calls are bounded (section 5). |
| F4 retry on pin creation | **ON** | `placeRetryOnPin` | An explicit user act. At most one attempt per new pin, and only when the new pin is among the candidates. |
| F5a signage in the extract prompt | **ON**, through the seed upgrade | none (prompt editor) | Only installs whose prompt is still the shipped default move. Edited prompts are untouched (`PromptDefaults.kt:6-9`). |
| F5b quoted-sign match against pins | **ON** | `placeTextMatch` | Text only and pins only. It needs a known recent position. A late caption becomes a history row, not a glasses card. |
| F6 card history on the phone | **ON** | `placeCardHistory` | A phone-only list in the encrypted `places.db`, wired into Forget. |
| F6c glasses report shown or skipped | **OFF** | glasses setting | A protocol and glasses change. It must be verified on the device first. |
| "Possible" rows for LOW_CONFIDENCE | **ON** | part of `placeCardHistory` | User ruling: never dropped silently. Phone only and labelled "possible · not shown". The online floor stays at 0.5. |
| Moment location | **OFF** (user setting) | n/a | A privacy class. A later, separate build (section 7). |

All flags live in `DeveloperFlags`, which sits in `services/orchestration/.../orchestration/forget/ForgetShadow.kt` (the `placeShadowScore` pattern at `:28-36`). They are stored in prefs, read synchronously, and default ON except F6c.

## 3. Overnight order

Three worktrees, one per track. All of track A touches `PlaceSampler.kt`, so it is sequential. Gradle runs take turns across the tracks.

| Order | Track A: sampler (worktree A) | Track B: history (worktree B) | Track C: prompt (worktree C) |
|---|---|---|---|
| 1 | H0-H5 harness prep, baseline B1 | B1 places.db v2 + `PlaceCardHistory` + Forget wiring | C1 F5a seed + SeedAssetsTest |
| 2 | S1 F7 fields + seams | B2 phone UI (Place Guide list, Home row, detail, show again) | |
| 3 | S2 F3 dedupe v2 | B3 Hub delivery state | |
| 4 | S3 F4 retry on pin / cell | | |
| 5 | S4 F2 fix reuse | | |
| 6 | S5 F1 pin-first + clusters | | |
| 7 | S6 LOW_CONFIDENCE "possible" | | |
| 8 | merge B and C into A; S7 F5b text match; final real replay R-final | | |
| 9 | one review pass; emulator checks E1-E5; handoff | | |

Not overnight: F6c (glasses), and section 7 (Moment location and backfill import).

Estimates (Sonnet): track A about 9-11 h, track B about 6-8 h, track C about 1 h. Report any slip at once in the handoff file `docs/superpowers/plans/handoffs/place-fixes-overnight-2026-11-25.md` (resume section at the top, updated after every step).

**Test tasks:**
- Place unit tests: `:services:orchestration:testDebugUnitTest --tests '*Place*'`.
- Replay, synthetic (always runs): `--tests '*replay*'`.
- Places DB: `:services:memory:testDebugUnitTest --tests '*Place*'`.
- Prompts: `:services:ai:testDebugUnitTest --tests '*SeedAssets*'`.
- Phone: `:phone:testDebugUnitTest --tests '*PlaceGuide*' --tests '*Home*' --tests '*Hub*'`.
- Real replay: `REPLAY_BUNDLE=~/Desktop/rokid/backups/replay-bundle-2026-11-24 REPLAY_OUT=~/Desktop/rokid/backups/replay-out tools/gate.sh <log> --no-daemon :services:orchestration:testDebugUnitTest --tests '*TripReplayRealDataTest*' --rerun`.

## 4. Steps

### H0. Bundle in place (no code)
- The B0 bundle exists only in a session scratchpad under `/private/tmp`, which is temporary.
- Rebuild it:
  - Command: `tools/replay/build_bundle.py --dump ~/Desktop/rokid/backups/phone-dump-2026-11-24 --gps ~/Desktop/rokid/backups/trip-gps/trip_media_gps.csv --derive-sites --out ~/Desktop/rokid/backups/replay-bundle-2026-11-24`
  - Then `chmod 700` the dir and `600` the files.
- **Check:** the printed counts are `frames=3126 dwell=1955 … pins=9 historical=10`.
- Copy `/tmp/pcalogs.txt` to `~/Desktop/rokid/backups/` if it is still there.

### H1-H5. Harness prep (test code only, `.../place/replay/`)
| # | Change | File / function |
|---|---|---|
| H1 | `ALL` = the caption matcher **only when it has a hit**, else the Oracle. Today any text means caption-only. `CaptionText` sets `note="caption_no_hit"` on a miss, and the combiner falls through to the Oracle on that note. | `ReplayReport.CaptionTextOrOracle`, `ReplayMatchers.CaptionText` |
| H2 | A card is `card=shown` parsed from the attempt line, not the outcome name. Needed once S2 can return MATCHED with `card=suppressed`. Add the outcomes `PIN_FIRST` and `TEXT` (S5, S7). Calibration counts a MATCHED with `cardWhy=already_shown_here` as reproduced. | `ReplayRow.isCard`, `ReplayReport.calibrate` |
| H3 | A cluster decision (`pinFirst=cluster(n)`) is wrong only when the span's target pin is not a member. | `ReplayReport.wrongCards` |
| H4 | New scenario `INDOOR_MEDIA_GOOD`: a good fix (acc 15, real age) when a media fix lies ≤ 60 s before t (causal); else near(t, N) at acc 60, age 3 s; else UNKNOWN. This is the "some good fixes, mostly bad" reality that F2 needs. | `ReplayTrack.FixScenario`, `ReplayFixSource.fixAt` |
| H5 | `summary.md` gains `calls=<n>` per run (rows with `call=made`), `pinCards=`, `clusterCards=`, `suppressed=`. A new `acceptance.tsv` (run, overlay, span, cards, wrong, calls, pinCards, clusterCards) lets steps compare numbers by script. | `ReplayReport.summary`, `ReplayReport.write` |

**Tests:**
- `TripReplaySyntheticTest` stays green.
- Add `allCombiner_fallsBackToOracleOnCaptionMiss` and `indoorMediaGood_givesGoodFixOnlyNearMediaFix`.
- `SyntheticFixtureGuardTest` stays green: new synthetic names are "Site A", "Room A1"…, never trip words.

**Then run the real replay = B1.** B1 must equal B0 except:
- the ALL column (H1);
- the new INDOOR_MEDIA_GOOD runs, whose HEAD numbers become the F2 baseline.

Record B1's `acceptance.tsv` path in the handoff.

### S1. F7: confirm the decision line, add the fields and the seams (no behaviour change)
- **File:** `services/orchestration/src/main/java/com/rokid/context/work/frames/place/PlaceSampler.kt`.
- **Trace fields** (`:171-182`), default `-`. Each one goes on the line in `finish()` (`:205`), after `thrV=` and before `shadowFields(t)`:
  - `fixSrc=fresh|reused|none`
  - `fixRealAgeB=` (the bucket of the reused fix's real age; new buckets `le5min`, `le10min`)
  - `pinFirst=-|off|none|card|cluster(<n>)|visit|small_radius|not_fresh`
  - `weakWin=-|<s>`
  - `retry=-|pin_created|pin_not_near|cell_retry`
  - `hist=-|ok|fail|off`
  - `textHit=-` (unused by the sampler; F5b logs its own line)
- **Constructor seams** (defaults keep every current test unchanged):
  - `history: suspend (PlaceHistoryEvent) -> Unit = {}`
  - `pinFirstOn: () -> Boolean = { false }`, `fixReuseOn: () -> Boolean = { false }`, `dedupeV2On: () -> Boolean = { false }`, `retryOn: () -> Boolean = { false }`
  - `pinsNear: (List<PlaceEntity>) -> List<PlaceEntity>` (identity; S5 uses the pins inside `found.places`)
  - Production (`PlaceSamplers.create`, `PlaceSamplers.kt:88-115`) passes the `DeveloperFlags` getters.
- **`PlaceHistoryEvent`:** `(kind: HistoryKind, placeId, title, members: List<String>, frameId: String?, cardId: String?, confPct: Int?)`.
  - `HistoryKind`: PHOTO, NEARBY, PIN, PIN_CLUSTER, TEXT, POSSIBLE.
  - Kept in the place package. It holds no coordinates.
- **`PlaceFrame`** (`:32`) gains `motion: String? = null` and `sessionId: String? = null`.
  - `RoomPlaceFrameStore.newestDwell` (`PlaceSamplers.kt:34-38`) fills them from `FrameMetaEntity.motion` / `.sessionId`.
  - `ReplayFrameStore` fills them too.
- **`PlaceFrameStore`** gains `suspend fun movedSince(sinceReceivedMs: Long): Boolean = false`.
  - Room: a new `FrameMetaDao` query `SELECT EXISTS(SELECT 1 FROM frame_meta WHERE createdAt >= :since AND motion IN ('WALKING','VEHICLE'))`. It is a query only, with no schema change.
  - The replay store implements it over `frames.tsv`.
- **Tests:**
  - `PlaceSamplerTest.attemptLine_newFieldsPresentAndContentFree`: every new field is present; no digit run of 5 or more outside ids and durations; no synthetic name; the line stays under 1 KiB with 12 ids.
  - Update the two shadow tests that check the line start (`PlaceSamplerShadowTest.kt:178,204`) only if they break.
- **Replay:** B1 is unchanged byte for byte in its outcome counts (the flags are off).

### S2. F3: dedupe v2 (weak answers do not consume, one card per place while staying)
- **Where:** `attempt()`.
  - Today `flight.lastAttempt` is set at `:323` before the call. It is therefore consumed by NONE, CONTRACT, LOW_CONFIDENCE, FAILED and even NOT_ADMITTED (the admit check comes after it, at `:331`).
- **Behaviour** (when `dedupeV2On`):
  1. **Card dedupe.** `flight.lastAttempt` is set only when a card was published (MATCHED, NEARBY, PIN_FIRST single), in `commit()`/`fallback()` after `publish()` returns true. The 120 s / 30 m check at `:311-315` is unchanged and reads `lastAttempt` as before.
  2. **Weak back-off.** A NONE, CONTRACT or LOW_CONFIDENCE outcome (a call was made) sets `flight.weak = Weak(lat, lon, at, streak)`.
     - The window is `30 s × 2^(streak-1)`, capped at 120 s.
     - The streak resets when the fix moves more than 30 m, after any card, or after 10 min without a weak outcome.
     - Inside the window and within 30 m: `DUPLICATE`, `reason=weak_backoff`, `weakWin=<s>`.
     - FAILED (a non-genuine failure), NOT_ADMITTED and DROPPED set nothing: no call reached a verdict, so the next frame may try.
  3. **Once per place while staying.** `flight.lastCard: Map<placeId, CardAt(lat, lon, at)>` holds at most 64 entries, in memory only; entries older than 30 min are purged.
     - In `commit()` before `publish()` (`:400`), and in `fallback()` before `:443`: if the same place had a card less than 30 min ago and the fix is within `30 m + fix.accuracyM` of that card's fix, the card is **suppressed**.
     - Suppressed means: the memory is kept and revealed (keep = true), `card=suppressed`, `cardWhy=already_shown_here`, outcome MATCHED / NEARBY, and no history row.
     - It applies to every card kind (S5, S7).
     - At process start the map is seeded from the history store: rows of the last 30 min, position unknown. "Unknown" suppresses by place and time alone.
- **Edge cases:**
  - A reused fix (S4) counts like any fix.
  - A zone or a fence close during suppression changes nothing: the existing `stopped()` checks run first.
  - `PlaceFlight` is process-wide (`PlaceSamplers.flight`), so a hub restart keeps the state.
- **Tests** (`PlaceSamplerTest`, new):
  - `weakNone_doesNotConsumeCardDedupe`
  - `notAdmitted_setsNoWindow`
  - `weakBackoff_doublesTo120sThenResetsOnMove`
  - `samePlaceWithin30m_cardSuppressedMemoryRevealed`
  - `samePlaceAfter30min_cardsAgain`
  - `movedAway_cardsAgain`
  - `seededFromHistory_suppressesAfterRestart`
- **Replay acceptance** (Oracle, vs B1). The rule numbers point to the list under section 5.
  - Wrong = 0 in every run.
  - SJ_EXTERIOR: the exterior frame whose media fix lies 1 s / 3 s away is **attempted** (not DUPLICATE) under AS_RECORDED and OUTDOOR_10M. Cards ≥ 1 in both.
  - OUTDOOR_10M: SJ_IVORY ≥ 2. SJ_ENTRY ≥ 1. SJ_BRONZE ≥ 1.
  - GOLCONDA_LS: ≤ 2 cards in every run where B1 > 0, and ≥ 1. RAMOJI ASSUMED_PIN_10M: 1-7 (from 71). ANTERA ASSUMED_PIN_10M: 1-5 (from 51).
  - StubNone × OUTDOOR_10M: calls ≤ 42 (1.5 × 28), cards 0.
  - Calibration: TRUSTED, ≥ 5 reproduced (after H2).
  - **Must not change:** NO_FRAME 169 and STALE_FRAME 13 in every run; the INDOOR_60M and STALE runs stay at 0 cards.
- **Delete the F3 overlay** (`TripReplayRunner.kt:85-88`, `Overlays.f3`). The F3 column then reads "built".

### S3. F4: retry the frame in view on pin creation, and after a pack miss
- **API:** `PlaceSampler.retryRecent(reason: RetryReason)` (`PIN_CREATED`, `CELL_RETRY`).
  - It works like `poke()` (`:186-195`), with a one-shot `bypass` consumed by the next attempt.
  - The bypass skips the `handled` check (`:292`), the card dedupe and the weak window for that attempt only. Every other gate is unchanged: the zone, fix and fence checks, the call gap and `admit()`.
  - If the single-flight is busy, one retry is queued and run when the current attempt ends. If the attempt is RATE_LIMITED, one delayed retry is scheduled at the end of the gap (≤ 20 s), through `fence.launch`.
  - At most one pending retry.
- **Pin not near.** For PIN_CREATED, the attempt continues only if the new pin id is in `found.places`. Otherwise: `NO_CANDIDATES`, `reason=retry_pin_not_near`, no call. This covers pins made on the map, far away.
- **Pack miss.** In `attempt()`, when `found.places.isEmpty()` (`:310`) and `found.outcome` is FAILED, OFFLINE or PARTIAL, the frame is **taken out of `handled`**, so the next poke within 90 s may try it again once the cell is fetched (`retry=cell_retry` on that attempt). FRESH or FETCHED and empty stays handled.
- **Wiring:**
  - `PlaceGraph` gains `val pinCreated = MutableSharedFlow<String>(extraBufferCapacity = 1)` (`PlaceGraph.kt`).
  - `PlacePinning.create` (`PlacePinning.kt:99-117`) emits the new id after `save` returns true, only for a `Saved` result. Imports, edits and restores never emit.
  - `HubGraph.kt:201`, where the sampler is made, collects it and calls `retryRecent(PIN_CREATED)`. The collector is cancelled with the hub.
- **Edge cases:**
  - A pin made inside a zone is refused before save, so nothing is emitted.
  - With Place Guide off, `retryRecent` returns at once (`enabled()`).
  - Delete all during the retry is fenced by the generation.
  - Two pins within 1 s give one retry (the buffer holds one).
- **Tests:**
  - `PlaceSamplerTest.retryOnPin_bypassesSeenOnce`
  - `retryOnPin_notNear_noCall`
  - `retry_respectsZoneAndCallGap_delayedOnce`
  - `packFailed_frameNotHandled_retriedNextPoke`
  - `packFreshEmpty_staysHandled`
  - `PlacePinningTest.create_emitsPinCreatedOnlyOnSave`
- **Replay:** change the `+F4` event to call the real `retryRecent(PIN_CREATED)` on the main sampler. Remove the fresh-sampler hack at `TripReplayRunner.kt:73`.
- **Replay acceptance:**
  - ANTERA_FACADE under ASSUMED_PIN_10M: ≥ 1 card from the retry, using a facade frame ≤ 90 s old.
  - OUTDOOR_10M: SJ_BRONZE ≥ 2 and SJ_IVORY ≥ 2. The F4 overlay gave 4 and 2 before the S2 suppression.
  - Wrong = 0.
  - ANTERA under INDOOR_60M stays 0, reported "needs indoor policy (device)".

### S4. F2: reuse the last good fix while dwelling
- **State:** `PlaceFlight` gains `@Volatile internal var good: GoodFix?` = `(lat, lon, accM, atElapsed, sessionId, receivedAtMs)`.
  - It is held in memory only, cleared by `generation()` changes and by `close()`.
  - It is updated whenever a fix passes the strict gate (`:296`).
- **Reuse rule** (when `fixReuseOn`). It replaces the NO_FIX / STALE_FIX returns at `:294` and `:296-299` only when **all** of these hold:
  1. `good != null` and `elapsed() - good.atElapsed <= FIX_REUSE_MAX_MS` (10 min).
  2. `f.motion == "STILL"` (`MotionState.STILL`), and `f.sessionId != null && f.sessionId == good.sessionId`.
  3. `!store.movedSince(good.receivedAtMs)`: no WALKING or VEHICLE frame since the good fix (HEAD_MOVING is allowed).
  4. `effAcc = good.accM + 4 m × minutes since good` must be ≤ `MAX_FIX_ACCURACY_M` (50).
  5. If the current fix exists and is finite, `distance(current, good) <= current.accuracyM + effAcc`. Otherwise `STALE_FIX`, `reason=fix_reuse_conflict`.
  - Then `fx = PlaceFix(good.lat, good.lon, ageMs = 0, accuracyM = effAcc)`, with `fixSrc=reused` and `fixRealAgeB=`.
- **The zone check uses `effAcc`** (`:301`), which is the more cautious choice. F1 refuses a reused fix (S5).
- **Reasons** when the reuse is refused: `fix_reuse_moved`, `fix_reuse_session`, `fix_reuse_not_still`, `fix_reuse_too_old`, `fix_reuse_inaccurate`, `fix_reuse_conflict`. Otherwise the original reason stays.
- **Edge cases:**
  - A good fix inside a zone is never stored as good: the zone check on the fix comes first.
  - A wall-clock step does not affect the reuse: the age is on `elapsed`.
  - Indoors with no good fix in 10 min: as HEAD.
- **Tests:**
  - `fixReuse_stillSameSessionWithin10min`
  - `fixReuse_blockedByWalkingFrame`
  - `fixReuse_blockedOtherSession`
  - `fixReuse_accuracyGrowsAndExpires`
  - `fixReuse_conflictWithFarCurrentFix`
  - `fixReuse_zoneUsesEffectiveAccuracy`
  - `fixReuse_neverUsedByPinFirst` (after S5)
- **Delete the F2 overlay** (`ReplayFixSource` `f2Ms`, `ReplayTrack.kt:83-86`).
- **Replay acceptance:**
  - INDOOR_MEDIA_GOOD: cards ≥ B1 + 1 in at least two of GOLCONDA_LS, SJ_EXTERIOR, SJ_BRONZE, SJ_IVORY. GOLCONDA_LS stays ≤ 2 (S2).
  - Wrong = 0 everywhere.
  - **Must not change:** INDOOR_60M and STALE stay at 0 cards (there was never a good fix, so F2 never invents accuracy).
  - OUTDOOR_10M: NO_FIX may only go down.
  - SJ_FOUNDERS: report only. A card there must have `fixSrc=reused` and must not be wrong.

### S5. F1: pin-first card, clusters for close pins
- **Where:** in `attempt()`, after `candidates()` (`:308-310`) and before the DEDUPE block (`:311`). Pins are the `UserPlace.isUserId` entries of `found.places`, with distances.
- **Pure decision:** `PinFirst.decide(fix, fixSrc, pins, visits, now)`, in a new file `PinFirst.kt` in the place package, unit-tested alone.
  - **Eligible pins:** `radiusM >= PIN_FIRST_MIN_RADIUS_M` (15). The 10 m pins are object or reference pins (the 3 home pins share one point); they never make a location card (`pinFirst=small_radius`).
  - **Fix:** `fixSrc == fresh`, `ageMs <= 60 s`, `accuracyM <= 25`. Otherwise `pinFirst=not_fresh`.
  - **Inside:** `dist + acc <= r + 10`.
  - **Neighbours:** active eligible pins whose edge lies within `acc + 30 m` of the fix (`dist - r <= acc + 30`).
    - Exactly one inside and no other neighbour: **single** card.
    - Otherwise, if some pin is inside: **cluster** of the inside pin plus its neighbours.
  - **Visits:** state per pin (and per cluster: the key is the sorted member ids).
    - A visit starts at the card.
    - It ends after 10 min outside `r + 50 m`, or after 3 h (`PIN_VISIT_MAX_MS`).
    - Within a visit there is no second card for the pin or for any cluster that contains it (`pinFirst=visit`).
    - Held in `PlaceFlight`, in memory, seeded from history rows of the last 3 h at start.
- **Card text** (`PlaceCards`, `PlaceCard.kt:70-120`). There is no protocol change: `PlaceCardInfo` carries title, line and source label as text.
  - `PlaceCards.pin(p, frameId)`: title = the pin name; line = the user's note (`userLine`) or "Your pin"; source "Your pin · here".
  - `PlaceCards.cluster(members, frameId)`: title = `"<label> · several pins"`; line = up to 3 member names joined by " · " (≤ 140); source "Your pins · here".
  - New `PlaceConfidence.PIN("pin")` for both. The glasses render it like PHOTO, because the wire label is the text.
  - **The cluster label** is a pure function, `ClusterLabel.of(names, parentNames)`:
    1. Split each name on non-alphanumerics and on lower-to-upper case changes, lowercase it, keep tokens of 3 or more letters, and drop stop words {museum, gallery, room, hall, fort, show, light, sound, kitchen, bar, the, and, of}.
    2. Shared tokens are those in at least 2 names and in at least half of them. The label is the shared tokens in the order of the nearest pin's name, title-cased.
    3. Else, if all members share one `parentId`, the label is that site's name.
    4. Else "Your pins".
  - This gives the user's wording, "<site> · several pins", without a guess.
- **Flow:**
  - **Single:** `publish()` (fenced, `:429-435`), then history PIN, `lastAttempt` and `lastCard` are set (S2), and the attempt returns the new `PlaceOutcome.PIN_FIRST`, `reason=pin_first`, `call=none`.
    - No model call, no photo upload, no memory written.
    - `ProactiveTrigger.onPlaceArrival` is not called, because it fires from `reveal()`.
  - **Cluster:** the decision is kept and the normal model path runs. In the `attempt()` wrapper, if that path published no card (any outcome but MATCHED/NEARBY with `card=shown`), the cluster card is published at the end (fenced; S2 suppression applies), with history PIN_CLUSTER and `pinFirst=cluster(n)`.
    - So the room the model can confirm wins, and otherwise the user sees "several pins". Never a silent pick.
  - `PlaceOutcome.PIN_FIRST` is appended to the enum at `:81` (additive; `stats` only counts it). Grep for exhaustive `when`s over `PlaceOutcome` and add the branch.
- **Allowed without network, admit or data saver:** pin-first needs neither the network nor a call, so it runs offline, under Offline only, with the data saver on and when `admit()` would refuse. It still never runs inside a zone: every earlier gate holds.
- **Tests (`PinFirstTest`, synthetic names only):**
  - `single_inside_fresh_card`
  - `smallRadius_neverCards`
  - `reusedFix_neverCards`
  - `acc30_notFresh`
  - `twoRoomsClose_clusterNotRoom` (pins 30-80 m apart, r 15-20)
  - `cluster_label_sharedToken` (e.g. "Kestrel House", "Kestrel Founders gallery", "Bronze Gallery KestrelHouse", "Ivory Room KH" → "Kestrel · several pins")
  - `cluster_label_parent`
  - `cluster_label_fallback`
  - `visit_once_then_after10minOutside_again`
  - `visit_cap3h`
  - `cluster_modelMatchedRoom_noClusterCard`
  - `cluster_modelNone_clusterCard`
  - `offlineOnly_pinFirstStillCards_noCall`
  - `zone_blocksPinFirst`
- **Delete the overlay** `PinFirstRule` (`WhatIf.kt:35-63`). The F1 column reads `pinFirst=` from the line (H2/H3).
- **Replay acceptance:**
  - **Wrong = 0 in every run.** The 1 neighbouring-room card of the overlay must become a cluster card or nothing.
  - SJ_* spans: no single pin-first card for a room pin when another room pin is a neighbour; cluster cards are allowed.
  - GOLCONDA_LS: exactly 1 pin-first card per visit in AS_RECORDED, OUTDOOR_10M, ASSUMED_PIN_10M and ALWAYS. Total cards ≤ 2.
  - ANTERA_FACADE + ANTERA under ASSUMED_PIN_10M: exactly 1 pin-first card in the visit.
  - StubNone × OUTDOOR_10M: PHOTO cards 0 (must not change); pin and cluster cards allowed; calls ≤ B1's, because a single pin-first card makes no call.
  - Historical × OUTDOOR_10M: the recorded match is still reproduced.

### S6. LOW_CONFIDENCE is never silent
- **Keep the floor:** `BackendThresholds` stays as it is (`VERSION=1`, online 0.5).
- **Add tests:**
  - `PlaceSamplerTest.online054_and058_publish`: MATCHED, `card=shown`.
  - `offlineFloor_documented`: offline 0.6 gives LOW_CONFIDENCE.
- **At `:354`** (when `placeCardHistory` is on), a LOW_CONFIDENCE answer with a valid number writes a history row: kind POSSIBLE, the chosen place, `confPct`, the frame id, `delivery=NOT_SENT_POSSIBLE`. No glasses card and no memory. The attempt line gains `hist=ok`.
- It does not consume the card dedupe (S2 rule 2 applies).
- **Tests:**
  - `lowConfidence_writesPossibleRowOnly`
  - `possibleRow_forgottenWithFrame` (B1)
- **Replay:** no change in counts (the harness has no low-confidence stage). A synthetic test covers it.

### B1. Card history store (`places.db` v1 → v2)
- **Why `places.db`:**
  - It is SQLCipher, not in any backup (`backup_rules.xml`), and rebuilt from the pins.
  - It is independent of the memory tiers: nudges and answers expire with SHORT 24 h, place memories with LONG 30 d.
  - Changing `context.db` would need the v18 migration, which is section 7.
- **Table** (`services/memory/src/main/java/com/rokid/context/memory/place/PlacesDb.kt:95`, version 1 → 2): `place_card_log`, with indexes on (at) and (frameId).

  | Column | Type | Content |
  |---|---|---|
  | `id` | TEXT PK | |
  | `at` | INTEGER NOT NULL | |
  | `placeId` | TEXT NOT NULL | |
  | `title` | TEXT NOT NULL | ≤ 80 |
  | `kind` | TEXT NOT NULL | the HistoryKind |
  | `members` | TEXT | cluster ids, comma-separated |
  | `frameId` | TEXT | |
  | `confPct` | INTEGER | POSSIBLE only |
  | `cardId` | TEXT | |
  | `delivery` | TEXT NOT NULL | PENDING, SENT, NOT_SENT_LINK, NOT_SENT_HOST, NOT_SENT_ZONE, NOT_SENT_POSSIBLE, NOT_SENT_LATE; later SHOWN and SKIPPED_<why> |
  | `photoGone` | INTEGER NOT NULL DEFAULT 0 | |

  - **No coordinates and no card line.** The line is re-read from the pack or the pin by `placeId` when shown.
  - `MIGRATION_PLACES_1_2` is additive (CREATE TABLE + indexes) and added in `PlacesDatabase.build`. Export schema `2.json`. No destructive fallback.
- **`PlaceCardHistory`** (place package, orchestration):
  - `add(event)`, `markDelivery(cardId, state)`, `recent(limit)`, `get(id)`, `delete(id)`.
  - `prune()`: rows older than 90 days, or more than 500 rows. It runs at `add`, at most once per day.
  - `seedRecent(sinceMs)` feeds S2 and S5.
  - Every failure is logged and swallowed, and never blocks a card.
- **Forget wiring:**
  - `ForgetListener` (`services/memory/.../forget/ForgetListener.kt`) gains `suspend fun onPhotosExpired(frameIds: List<String>) = onFramesForgotten(frameIds)`, plus `ForgetListeners.photosExpired`. Every existing listener behaves as before.
  - `ForgetService.notifying` (`ForgetService.kt:245-256`) routes the ids queued by `expirePhotos` to `photosExpired`, and those from user Forgets to `framesForgotten`. `Pending` gets an `expired: Boolean`.
  - **The history listener** is registered in `PlaceGraph.init` next to the existing one (`PlaceGraph.kt:31-35`):

    | Event | What happens to history rows |
    |---|---|
    | user photo Forget (`onFramesForgotten`) | rows with that frame are **deleted** |
    | photo retention (`onPhotosExpired`) | `frameId=NULL`, `photoGone=1`; the row is **kept** (expiry-independent) |
    | `onRangeForgotten` | rows with `at` in the range are deleted |
    | `onDeleteAll` | all rows are deleted; `PlaceGraph.deleteAll` (`:78-84`) also clears them |
    | pin removal (`PlaceGraph.savePins`, ids that disappear) | rows of that pin, and cluster rows that contain it, are deleted |
    | memory expiry (SHORT / LONG) | nothing |

- **Never exported, never backed up.** Update `docs/superpowers/plans/2026-11-20-forget-coverage-map.md` with one row.
- **Logs:**
  - `place history ready rows=<n>` (once, at the first open)
  - `place history add kind=<K> delivery=<D> rows=<n>`
  - `place history prune removed=<n>`
  - `place history forget frames=<n> removed=<n>`
  - `place history expire frames=<n> unlinked=<n>`
  - `place history range removed=<n>`
  - `place history cleared reason=delete_all removed=<n>`
  - `place history pin removed=<n>`
- **Tests** (memory and orchestration):
  - `PlacesMigration1To2Test` (MigrationTestHelper; pins and cells survive)
  - `history_add_prune90d_cap500`
  - `userForget_deletesRow`
  - `photoExpiry_unlinksKeepsRow`
  - `range_deletes`
  - `deleteAll_clears`
  - `pinDeleted_deletesItsRows`
  - `existingListeners_stillGetFramesForgottenOnExpiry` (default delegation)
  - `historyNeverInExport`

### B2. Phone UI
- **Place Guide screen** (`phone/src/main/java/com/rokid/context/phone/ui/settings/place/PlaceGuideSettings.kt`, state at about `:425-429`; control `phone/.../place/AndroidPlaceGuideControl.kt`): a "Recent place cards" section, newest first.
  - Each row: title, time, kind label (photo match / your pin / several pins / nearby · not checked / sign read / possible · not shown) and delivery (sent to glasses / not sent: glasses not connected / not sent: Nexus shows the HUD / not sent: pause zone / possible · not shown / too late for the glasses).
- **Home** (`phone/.../ui/home/HomeScreen.kt`, `HomeViewModel.kt`): one "Last place card" row when a row is less than 6 h old. Tapping it opens the list.
- **Detail:**
  - The line, re-read by `placeId` (gone: "This place is no longer in your pack").
  - The photo thumbnail only when the frame is live (`ProvenanceGate`).
  - "Open Moment" when the frame is live, and "Delete from history".
  - **"Show on glasses again"** publishes a copy through `PlaceGraph.port.publish` (new cardId, new history row with `kind` unchanged).
    - It is disabled, with the reason, when Place Guide is off, a zone is approached or active, the glasses are not connected, or a PHOTO row's photo is gone.
    - The tap is a user act: a new delivery entry, not a re-identification.
- **Edge cases:** empty state; a list longer than 500 is impossible; time shown in the phone's zone; a Forget during display refreshes the list.
- **Tests:**
  - `PlaceGuideHistoryTest` (list states, show-again enabled or disabled per reason)
  - `HomeLastPlaceCardTest`

### B3. Delivery state on the phone
- **`Hub.kt`:**
  - `onPlaceCard` (`:666-672`) and the frame send (`:660-661`, `actions.onShown(frame.overlay)`): when a sent STATE frame carries the place overlay with a `cardId`, call `history.markDelivery(cardId, SENT)`.
  - At the 12 s expiry, or a replacement, a row still PENDING becomes NOT_SENT_LINK (`!link.carriesData`), NOT_SENT_HOST (`forHost()` dropped it, Nexus host) or NOT_SENT_ZONE (`placeOverlay()` returned null, `:690-697`).
- A glasses dismissal (`onPlaceDismissed`, `:679`) is **not** proof that the card was shown: the glasses also send it when the overlay changes (`MainActivity.kt:266-269, 318-322`). It changes nothing.
- **Tests:** `HubPlaceDeliveryTest` (sent, link down, host Nexus, zone).

### C1. F5a: ask the extractor for sign text
- **`services/ai/src/main/assets/prompts/seeds/write_context_batched.txt`:** add one rule line after the caption rule:
  `- If the name of a shop, restaurant, venue or place is readable on a sign, board or name plate, put that name in the caption in double quotes, exactly as written. Copy no other text (no screens, documents, cards, number plates or name badges).`
- **Upgrade path** (`PromptDefaults.kt:18-25`):
  - Add the current fingerprint `df262c3711776a9a2a48b6f7b5a433ebceb4b0328b7879533a7b75bac1d4d8da` to `PREVIOUS[VISION_EXTRACT]`.
  - Commit a copy of today's seed as `services/ai/src/test/resources/prompts_old/vision_extract_seed_v2.txt`.
- **`SeedAssetsTest.shippedVisionDefaultIsPinnedAndThePreProvenanceOneUpgrades`** (`:66-76`):
  - Pin the new fingerprint.
  - Recompute the v2 fingerprint from the committed copy plus `DefaultSchemas.text(VISION_EXTRACT)`, and assert it is in `PREVIOUS`.
  - Keep the v1 assertion.
  - Assert that the new seed contains "double quotes" and "Copy no other text".
- **Privacy:** captions already carry readable text (posters, shuttle signs). This narrows it to place names and forbids screens and documents. The schema is unchanged.
- **Check:** grep for any caption length cap or post-processing (`caption` in `PipelineRecords` / `VisionExtract*`) and keep it.

### S7. F5b: quoted-sign match against pins (after merge)
- **Hook:** after the VISION_EXTRACT captions of a batch are committed. Find the commit point with `graphify query "caption commit VISION_EXTRACT"`; it is next to where `KeyframeGraph` signals (`KeyframeGraph.kt:198-202`).
  - It calls `PlaceTextMatch.onCaptions(frameIds)` on the place scope, fenced like the sampler (zone blocked = nothing read).
- **Rule:**
  - Frames captured ≤ 10 min ago with a caption.
  - Take the quoted spans (straight or curly double quotes, 2-80 characters), and compare in compact form (lowercase alphanumerics).
  - Candidates are active pins within 2 km of `flight.good`, and only if that good fix is ≤ 30 min old. Without one: `result=no_fix`, never a match.
  - **Hit:** the compact pin name is inside a quoted span, or a distinctive pin token (≥ 4 letters, not a stop word) equals a token of a quoted span.
  - **Exactly one pin:**
    - If `now - capturedAt <= 2 min` and `!movedSince(capturedAt)`: a glasses card (`PlaceCards.pin`, source "Sign read · your pin"), history TEXT, S2 suppression and S5 visits apply.
    - Else: a history row only, `delivery=NOT_SENT_LATE`.
  - Two or more pins: `ambiguous`, nothing shown.
- **Log:** `place text match frames=<n> quoted=<n> hits=<n> result=card|history|ambiguous|none|no_fix|off`.
- **Tests:**
  - `quotedSign_onePinNear_card`
  - `quotedSign_late_historyOnly`
  - `unquotedObjectWord_noMatch` (the "bronze statue" case)
  - `noRecentFix_noMatch`
  - `twoPins_ambiguous`
  - `zone_readsNothing`
  - `replay_captionStage_quotedOnly`
- **Replay:** the CaptionText stage switches to quoted spans only (ReplayMatchers). On the trip data that gives 0 hits, 0 cards and 0 wrong. **This must not change any other count.** The gain comes only with the new prompt, on the next trip.

### R-final, review, handoff
- **Real replay** with all flags ON and the overlays deleted:
  - Every criterion of S2-S7 holds at once.
  - Wrong = 0 in all runs; calibration TRUSTED.
  - ONEGOLD stays "not testable".
  - Quote the counts in the handoff.
- **One review pass** (Codex medium; Opus low as the fallback) over A, B and C together. Focus:
  - no position stored or logged;
  - the fence and zone checks around every new publish;
  - the Forget table;
  - migration safety;
  - the prompt upgrade path.
- **Build the debug APKs on the Mac only.** Do not install them on the phone.

## 5. Acceptance summary (real replay, Oracle unless noted; B1 = after H1-H5)

| Span | Run | B1 | After all steps | Moved by |
|---|---|---|---|---|
| All | all | wrong 0 | **wrong 0** (hard) | S5 removes the overlay's 1 |
| SJ_EXTERIOR | AS_RECORDED, OUTDOOR_10M | 1, 2 | ≥ 1 each; the exterior frame with the near-simultaneous fix is attempted | S2 |
| SJ_IVORY | OUTDOOR_10M | 1 | ≥ 2 | S2, S3 |
| SJ_BRONZE | OUTDOOR_10M | 2 | ≥ 2 | S3 |
| SJ_ENTRY | OUTDOOR_10M | 2 | ≥ 1 | S2 may suppress the second |
| SJ rooms | any | single pin-first 0 | single pin-first 0; cluster allowed | S5 |
| SJ_FOUNDERS | any | 0 | report only; any card has `fixSrc=reused`, none wrong | S4 |
| GOLCONDA_LS | every run with B1 > 0 | 5-10 | 1-2 total, exactly 1 pin-first per visit | S2, S5 |
| RAMOJI | ASSUMED_PIN_10M | 71 | 1-7 | S2 |
| ANTERA_FACADE | ASSUMED_PIN_10M | 0 | ≥ 1 | S3 |
| ANTERA (+facade) | ASSUMED_PIN_10M | 51 | 1-5 total, exactly 1 pin-first | S2, S5 |
| ANTERA | INDOOR_60M | 0 | 0, "needs indoor policy (device)" | none |
| GOLCONDA_LS, SJ_EXTERIOR, SJ_BRONZE, SJ_IVORY | INDOOR_MEDIA_GOOD | B1 | ≥ B1 + 1 in at least two of them | S4 |
| All | INDOOR_60M, STALE | 0 | **0** (must not change) | none |
| All | StubNone × OUTDOOR_10M | 28 calls, 0 cards | ≤ 42 calls, PHOTO cards 0 | S2, S5 |
| Calibration | Historical + sites | 5/6 TRUSTED | ≥ 5 TRUSTED | H2 |
| All | all | NO_FRAME 169, STALE_FRAME 13 | unchanged | none |
| ONEGOLD | all | not testable | not testable | none |

## 6. Emulator vs real device

**Emulator** (an API 34 Google APIs image, so the fused provider takes `adb -s emulator-5554 emu geo fix <lon> <lat>`; note the order is longitude first):
- **Data rules:**
  - Copy from `~/Desktop/rokid/backups/phone-dump-2026-11-24/` only `place_pins.xml` (and, for section 7 only, the WAL-complete `context.db`).
  - Never copy `db_key.xml`, `endpoint_keys.xml`, `rokid_link.xml` or `proactive_shadow.xml`. So no model is bound on the emulator, and no photo can leave the Mac.
  - Use pin centres read locally. Never write them into a doc or a log.
- The emulator has no glasses and no frame source, so F1-F4 cannot fire end to end there. They are covered by the JVM tests and the replay.

| # | Check | Pass |
|---|---|---|
| E1 | Install the B0-era build, run-as copy `place_pins.xml`, open Place Guide (`places.db` v1, pins mirrored). Then `install -r` the new build. | Log `place history ready rows=0`; the pins count is unchanged; no crash; Place Guide shows "No place cards yet" |
| E2 | Mock a fix at a copied pin with accuracy 5 m. Create a pin "Test A" in Place Guide. | `pin created count=` then a `place attempt` line with `retry=pin_created` and outcome `NO_FRAME` (no glasses frames): the F4 wiring is proven |
| E3 | Create a pin on the map far from the mock fix | `retry=pin_not_near`, or no attempt; no call |
| E4 | Delete the test pin; run Delete all; toggle each new Developer flag | `place history pin removed=`, `place history cleared reason=delete_all`; flags persist; no crash |
| E5 | Settings › Prompts on a fresh install | VISION_EXTRACT shows the new seed. On the phone the upgrade applies because the dump's active prompt is the shipped default (section 1); an edited prompt would stay |

**Real device** (only after the user's go, backup-first as in `handoffs/v17-upgrade-recovery.md` §1, even though `context.db` does not change; `places.db` does):

| # | Check |
|---|---|
| D1 | An isolated pin outdoors (make a 20 m test pin at a landmark). Walk in: one card on the glasses with the screen on, a history row "your pin · sent", and the line `call=none pinFirst=card`. Leave for 10 min and return: one more card. |
| D2 | At home (three 10 m pins): no pin-first card (`pinFirst=small_radius`). |
| D3 | Indoors at a café for 20 min: lines show `fixSrc=reused` while seated and `fix_reuse_moved` after walking. Count STALE_FIX against the trip's 19 %. |
| D4 | Make a pin at a shop facade while looking at it: a card within about 2 s and a history row. |
| D5 | The glasses screen off when a card comes: the history row stays "sent · not confirmed", and "Show on glasses again" works with the screen on. |
| D6 | Look at a shop board for 30 s: the Memory screen caption has the name in quotes; with a pin of that name nearby, a "sign read" row appears. |
| D7 | Glasses pairing and install exercised for real before handing over. The glasses APK is unchanged unless F6c is built. |

## 7. Later, separate build: location with each Moment (Room v18), and the GPS backfill

Not overnight. It needs the user's go for the install.

**Design:**
- **Table** `moment_location`, additive, Room `context.db` v17 → v18 (`ContextDatabase.kt:24`, a new `MIGRATION_17_18` after `MIGRATION_16_17` at `:353`, added in both `DatabaseFactory.kt:13,23` lists). The migration is a single CREATE TABLE.

  | Column | Type |
  |---|---|
  | `anchorId` | TEXT PK |
  | `anchorKind` | TEXT NOT NULL (FRAME or MARK) |
  | `lat` | REAL NOT NULL |
  | `lon` | REAL NOT NULL |
  | `accuracyM` | REAL NOT NULL |
  | `fixAgeS` | INTEGER NOT NULL |
  | `src` | TEXT NOT NULL (FIX, REUSED, MEDIA_BACKFILL) |
  | `savedAt` | INTEGER NOT NULL |

- **Why a separate table keyed by the anchor, not a column on `moment`:** `MomentBuilder` rewrites the `moment` row on every build (`MomentBuilder.kt:134`), but the fix exists at capture time, before the Moment does. A Moment reads its location through its photo frame (`MomentDao.photoFrameOf`) or its mark ref.
- **Write path:**
  - At frame ingest, when Settings › Capture & privacy › **Save location with Moments** is ON (default **OFF**).
  - The fix is fresh (≤ 60 s, ≤ 100 m), or an S4-reused fix ≤ 2 min old (`src=REUSED`).
  - A fix the zone check blocks is never saved.
- **Privacy:**
  - Local only. Never sent to a model or put in a prompt. Never logged: only `moment loc saved src=<S> accB=<b>` / `moment loc skipped reason=off|no_fix|zone|inaccurate`.
  - Never exported: export code skips the table, and a test asserts it.
  - Never backed up (`context.db` is already excluded, `backup_rules.xml`).
- **Forget cascade,** in the same transactions:
  - frame Forget, Moment Forget and range Forget delete the rows;
  - Delete all deletes all rows;
  - photo retention keeps the row as long as `frame_meta` lives;
  - turning the setting OFF offers "Delete saved locations".
- **Tests:**
  - `Migration17To18Test` (MigrationTestHelper, plus the 1→18 chain)
  - `momentLocation_offByDefault`
  - `forgetCascade_frameMomentRangeDeleteAll`
  - `notInExport`
  - `zoneFix_neverSaved`
  - `rebuild_keepsLocation`

**Migration notes and the backup-first rule:**
- Follow `handoffs/v17-upgrade-recovery.md` exactly:
  - force-stop, then a tar with `-wal`/`-shm`;
  - validate it (TAR_OK, the encrypted header check, `shasum -a 256`);
  - keep the previous APK;
  - install with `adb install -r` only, same signature;
  - no uninstall, no `pm clear`, no destructive fallback.
- **v17 binaries cannot open v18,** so the rollback is restore-from-backup plus reinstall.
- **Emulator dry run first** (this is where the dump copy is used):
  1. Install the v17 build.
  2. With the app stopped, copy the dump's WAL-complete `context.db` (plain, as on the phone) into `databases/`.
  3. Open the app once.
  4. `install -r` the v18 build.
  5. Pass: the counts of `frame_meta`, `memory`, `moment`, `moment_part` and `prompt` are unchanged (sqlite3 on a pulled temp copy, counts only); `moment_location` exists with 0 rows; the setting is OFF.
  - Copy no key files.

**GPS backfill rule** (from the harness plan §9; `tools/replay/backfill_gps.py` is not written yet):
- **Match:** a Moment's photo-frame capture time against the media GPS track.
  - Interpolate only when there is a fix within N on both sides, the two are ≤ 200 m apart, and the speed is ≤ 2 m/s (`INTERP`). Else take the one side within N (`NEAREST`). Else leave it blank.
  - N = 5 min, or 2 min when the frame's motion is not STILL.
- **Uncertainty:** `15 + 1.4 m/s × |Δt|`.
- **Never used:** a pin or site centre, an ASSUMED position, or a phone-camera image's GPS tagged with lower trust without its flag.
- **Coverage:** of 1,153 trip Moments, 91 / 128 / 191 have a fix within 2 / 5 / 10 min.
- **Process:**
  1. A dry-run TSV is written to `~/Desktop/rokid/backups/replay-out/`.
  2. Applying it needs the v18 table, the setting ON and a one-shot Developer import the user starts.
  3. Every row is validated: the Moment is still alive and has no live FIX row. Rows are written `src=MEDIA_BACKFILL`, and an existing FIX row is never overwritten.
  4. The file is deleted after the import.
  - It uses `frame_meta.capturedAtMs`, which survives photo retention.

## 8. Open points for the user

1. Pin-first radius floor of 15 m: do your 10 m pins (home objects) ever need a location card?
2. Card history keeps 90 days and 500 rows. Is that right?
3. After the device check, should a skipped place card show once when the glasses screen next turns on (F6c, within 60 s)?
4. The photo retention of 7 days still deletes the 21 Nov photos from about 29 Nov. Card history and Moment locations survive it, but the photo thumbnails do not.
