# Layered place confirmation: "what am I looking at", not "what is nearby" (design, 27 Nov 2026 night)

Status: DESIGN ONLY. Nothing built, nothing installed. Owner reads this. Counts, thresholds and file references only: no coordinates, no place names, no keys.
"Verified" = read in the code at the given file:line. "ASSUMPTION" = not checked, must be confirmed by the step that builds it.

## 0. The idea in one paragraph

A card goes to the glasses only when the photo from the glasses has been CONFIRMED as a place. Confirmation is a plain threshold decision made by one of three layers, tried in order. Layer 1 compares the photo with stored reference photos of the nearby places on the phone (fast, works offline). If Layer 1 is sure, it shows the card. If it is only half sure, it narrows the list of candidates and hands them to Layer 2 (the online vision model, as today) or, without a network, to Layer 3 (the phone's own caption model, which can only say "Maybe"). A place that is merely nearby by GPS is written to the card history in Activity with the reason, and is not sent to the glasses. Every attempt is recorded so the owner can count right, wrong and missed cards himself and set the thresholds on his Mac.

## 1. What exists today (verified)

| Piece | Where | State |
|---|---|---|
| No fresh GPS fix = no card, no fallback | `PlaceSampler.kt:400-419` (outcomes `NO_FIX`, `STALE_FIX`) | Built, keep as is |
| Online vision call over a numbered candidate list, confidence floor, low confidence = phone-only `POSSIBLE` row | `PlaceSampler.kt:482-509` | Built (Layer 2) |
| No network, Offline only, or Data saver = offline path; genuine call failure = nearby card | `PlaceSampler.kt:474-478`, `:494`, `:605-641` | Built; this is what the new policy changes |
| Pin-first card (a single pin the fix is inside: location only, no photo) | `PlaceSampler.kt:443-455`, `:659-671` | Built; see decision D7 |
| Offline tier: SmolVLM2-500M caption, bge-small text rank, one "Maybe" card at cosine >= 0.55, margin only counted, 1 caption a minute | `OfflineTier.kt:21-81`, `:98-101`; wiring `PlaceSamplers.kt:170-190` | Built behind flags `offlineTier` + `offlineCaption` + user switch, all default OFF (`ForgetShadow.kt:59-69`) |
| Image scorer: max cosine over a place's reference vectors | `CandidateScorer` (memory/retrieval), used by `PlaceShadow.kt:69-70` | Built, shadow only |
| Pin reference vectors from the pin's Moment photo, in memory, thermal-gated | `PlaceSamplers.kt:204-212` (`PinReferenceVectors`) | Built, shadow only |
| Pin Scene centroids | `PinSceneCentroids.kt:11-27`; producer flag `sceneCentroids` default OFF (`ForgetShadow.kt:84-88`) | Built, shadow only (`CentroidShadow.kt`, flag `placeShadowCentroid` default OFF) |
| Accept and reject thresholds | `MatchThresholds.kt:14` `VERSION = 0`, every value NOT SET (`:23-35`), margin 0 | Nothing is ever accepted today |
| Calibration maths (accept at 95 and 98 percent precision, margin sweep, centroid/medoid) | `MatchCalibration.kt:94-110` | Built (Mac harness `MatchCalibrationRealDataTest`) |
| The 500 / 800 milli "medium" placeholders | `CentroidShadow.kt:43-47` | Log flag only. On the real Nomic image scores (same-place and other-place both sit around 800-950 milli) these numbers mean nothing; they are NOT reused below |
| Seeded place row: name, kind, parent, position, radius, 3-sentence intro, source | `PlacesDb.kt:21-40`, places.db Room v3 (`:147`), SQLCipher, excluded from backup (`backup_rules.xml:21-24`) | Installed (s30) |
| `place_ref` (caption, imageUrl, author, licence, capturedAt, bytes) | `PlacesDb.kt:67-76`, created in `MIGRATION_PLACES_2_3` (`:167-173`) | Exists, empty: nothing writes it (only `PlacePack.putRefs`, no caller). It has NO column for image bytes or a vector |
| Wikipedia calls of the seed | `PlaceSources.kt:84` (`prop=extracts|pageprops|coordinates`), `PlaceBulkSource.kt:145-146` (`prop=extracts`) | NO `pageimages`, no Commons call anywhere: the seed receives no image today |
| HTTP client of the seed | `PlaceHttp.kt:43-49` (text only, 400 ms gap per host, 4 MB cap, polite User-Agent `:183`), shared limiter `PlaceNetGuard.kt:12-16` (429/503 pause per server group) | Built |
| Safe image downloader (manual place photo) | `RefPhoto.kt:43-46`, `ImageFetcher` `:162` (https only, public addresses, size cap, JPEG check) | Built |
| Card history | `place_card_log` in context.db v18 (`PlaceCardLog.kt:18-32`): id, at, placeId, title, kind, members, frameId, confPct, cardId, delivery (TEXT), photoGone. Kinds `PHOTO, NEARBY, PIN, PIN_CLUSTER, TEXT, POSSIBLE, MAYBE` (`PlaceCardHistory.kt:17`) | Built, in the dump, Forget-covered. No label column, no score column |
| Place to Moment links | `place_link` in context.db v18 (`PlaceLink.kt:14-24`): placeId, momentId, atMs, anchorId (the frame), role (`CARD`; `USER` reserved) | Built, Forget-covered |
| Model privacy | `ModelBackend.kt:12-14`: `AUTO`, `OFFLINE_ONLY` | Built |
| Delete all keeps seeded places (resets recency only) | `PlacePack.kt:522-526`; family `PLACE_PACK` tables `place, cell, area, place_ref` (`Families.kt:397-398`) | Built |

Conflict to clear first: owner round 4 (26 Nov) said "no reference image for pack places, ever" and round 6d said "fetch the image, caption it, keep the text, delete the image". Layer 1 offline needs a stored picture or at least its vector. See decision D1.

## 2. The pipeline

```
fresh GPS fix?  no -> nothing (NO_FIX / STALE_FIX, as today)
   |
candidates = places near the fix (today's list, pins kept)
   |
LAYER 1  photo vs reference photos, on the phone (any network state)
   |-- HIGH: exactly one place at or above HIGH and ahead of the runner-up by MARGIN -> card "Matched" (memory saved, like Layer 2)
   |-- MEDIUM: places between FLOOR and HIGH (or two or more at HIGH) -> they become the candidate list for Layer 2/3
   |-- below FLOOR: that place is dropped from the list; places with no reference stay in the list
   |
check mode + network + Data saver:
   |-- online allowed -> LAYER 2 vision model over the narrowed list (as today)
   |        confirmed: its chosen place with confidence >= the existing floor -> card
   |        low confidence -> POSSIBLE row (as today); "none" -> NO_MATCH row
   |        genuine call failure -> Layer 3 in Automatic mode, else HELD row
   |-- offline, Offline only, Data saver blocks the photo, or the call failed -> LAYER 3 caption rank
   |        cosine >= cut-off -> card labelled "Maybe <place>"
   |        below -> HELD row
   |
nothing confirmed and one nearest place within 100 m -> NEARBY row in Activity with the reason (HELD_*), NOT on the glasses
```

"Confirmation", per layer, in plain terms:
- Layer 1: the similarity score (cosine x 1000, "milli") of the photo against the place's best reference photo is at or above HIGH, and it leads the next place by at least MARGIN.
- Layer 2: the vision model picked one numbered candidate and its stated confidence is at or above the existing floor (`BackendThresholds.minConfidence`, unchanged).
- Layer 3: the cosine between the photo caption and the place's name text is at or above the cut-off (0.55 now). It is a guess, so the card says "Maybe".

## 3. Layer 1 in detail

### 3.1 Two thresholds and a margin (owner addition 1)

Per reference kind, three numbers, all in milli:
- HIGH: accept and show at once.
- FLOOR: below it the embedding has evidence AGAINST the place; it is removed from the candidate list. Between FLOOR and HIGH the place is a candidate for Layer 2/3.
- MARGIN: the accepted place must lead the best other place by this much. Two or more places at HIGH = no accept, all of them go to Layer 2/3 as candidates (owner round 5).

Reference kinds (different photo sources have different score ranges, so they get their own numbers):
- OWN: a photo the owner took with the glasses (pin reference Moment, a confirmed glance, see 3.3).
- OWN_CENTROID: the pin's Scene centroid, when the producer has written one.
- WEB: the public lead photo of a seeded place.

A place's score is the max over all its references; if ANY reference reaches its kind's HIGH, the whole place is accepted (a building photographed from several sides). Max is the default; top-2 mean is not built (no data says it helps).

Example calibration (from the existing Mac runs, `handoffs/calibration-1.4.4-real-2026-11-26.md`, Nomic, the active on-phone space):

| Kind | HIGH | FLOOR | MARGIN | Basis |
|---|---|---|---|---|
| OWN | 945 | 846 | 20 | accept at 98% precision 945 (real) / 946 (proxy); reject-below 846-852; margin first guess 20 (unmeasured) |
| OWN_CENTROID | 967 | 879 | 20 | accept at 98% 967 / 954; reject-below 879-885 |
| WEB | 945 | 0 (off) | 20 | NO data. Same HIGH as OWN (a web photo scoring that high against a glasses frame is strong evidence); FLOOR off because web photos score lower for the right place (other angle, light, season) and a floor would throw the right place away |

The owner's target is 95% precision. The reports printed only the 98% values; the harness already computes the 95% value and the margin for 95% (`MatchCalibration.kt:104-110`). Step A3 reruns it on the same pull and replaces the table values by the 95% ones if they are not lower than the 98% value minus 15 milli (a guard against a tiny sample). These are EXAMPLE values; the owner recalibrates from the export (section 7).

Where he edits them: Settings > Place Guide > "Photo match thresholds" (shown with Developer mode on), nine number fields (3 kinds x HIGH / FLOOR / MARGIN), clamped to 500-999 milli (margin 0-200), with "Reset to defaults". Same pattern as the existing Maybe cut-off field. `MatchThresholds` gets the defaults and `VERSION = 1`, so the shadow lines show the same bands.

### 3.2 Several frames on the query side (owner addition 2)

The owner looks at a thing for a few seconds; the glasses take several frames. One frame can be blurred or half turned.
- Take the dwell frame (embedded now, as the shadow does) plus up to 4 other frames of the same glasses session from the 20 s before it that ALREADY have a vector in the active space (`frame_vector`; nothing extra is embedded during an attempt).
- "Little scene change" = each extra frame has cosine >= 920 milli to the dwell frame AND the glasses did not move in that window (`frameMetaDao().movedSince`, `PlaceSamplers.kt:43`). No scene-break detection is used. Frames carry no GPS of their own (verified in the calibration note), so the fix age rule of the attempt covers the place.
- Query = the normalised mean of the kept vectors (default). Developer choice "best of k" scores each frame and takes the best; it is more eager and therefore less precise, so it is not the default.
- The log line says how many frames were used (`k=`).
- Example values (20 s, 4 frames, 920) are first guesses; step A3 measures what the mean of neighbouring probes does on the same pull.

### 3.3 Several references per place, and new references from confirmed glances (owner addition 3)

References of a place, all scored with max:
1. WEB: the seeded lead photo (section 4).
2. OWN: the pin's reference Moment photo (existing `PinReferenceVectors`) and the manual place photo (it is a reference Moment already).
3. OWN: glances that were CONFIRMED, stored as `place_link` rows with a new role `REF` (no schema change: role is text, `PlaceLink.kt:14`). The anchor frame's existing `frame_vector` is the reference vector: no new vector is stored.
4. OWN_CENTROID: the pin's Scene centroid when present.

Loop guard (so a place cannot confirm itself into a run of wrong cards): a glance becomes a `REF` only when
- the owner tapped "Right" on its history row, OR
- Layer 2 confirmed the place AND Layer 1 scored that same place at or above FLOOR (two independent signals agree).
A Layer 1 accept alone NEVER adds a reference. At most 5 `REF` links per place (oldest goes). `CARD` links (any card, including location-only ones) are never used as references. A `REF` goes with every Forget of its Moment or frame (existing `place_link` choke points) and with "Wrong" tapped later on the same row. Links to a seeded place that was deleted are swept at start-up (same pattern as `PlaceRefSweep.kt`).

### 3.4 When a place has no vector yet

Places whose photo is not fetched or not embedded yet simply have no reference: they stay in the candidate list for Layer 2/3 and are counted in the log (`noRef=`). A risk: if the right place has no reference and a neighbour has one, the neighbour could be accepted. The guard is HIGH itself (it compares the photo with that neighbour's own picture); the count `noRef` lets the owner see how often this situation occurs.

### 3.5 Cost

One image embed per attempt (the dwell photo) plus reading at most 16 stored vectors. ASSUMPTION: 0.3-0.6 s on the phone CPU for Nomic vision; the existing shadow already does exactly this embed. Budget 3 s; on timeout or heat (thermal MODERATE or above, `PhonePowerPolicy.kt:33-42`) Layer 1 is skipped with a token and the attempt goes on to Layer 2/3.

## 4. Reference photos for seeded places (the big gap)

### 4.1 What to fetch
- One lead photo per seeded place, from English Wikipedia's page image (`prop=pageimages`, `piprop=name`, free licences only). Added to the SAME batched calls the seed already makes after the intro fetch (`PlaceBulkSource.kt:145-146` intro call, `PlaceSources.kt:84` pages call): no extra request for the page image name.
- One Commons metadata call per 50 file names (`prop=imageinfo`, url, size, mime, sha1, licence, author, attribution flag, with a thumbnail width), then one image download per place from the Wikimedia upload host.
- Thumbnail width 330 px (ASSUMPTION: a standard Wikimedia thumbnail step; verify the current thumbnail-size policy when building B2). Cap 150 KB per image; JPEG only (maps, logos and flags are almost always SVG or PNG, so this also drops most non-photos). Decode check, re-encode if larger than 336 px on the long side.
- Only seeded places that passed the class rules, and not area-like kinds (town, district, region): their page image is a map or a skyline. Wikidata P18 is not used in this round (the bulk SPARQL paging depends on one row per item and class; adding an image column risks it).
- Pacing: 1 request per second to the upload host, through `PlaceNetGuard` as its own server group (a media 429 must not stop the text seed). User-Agent: the existing one (`PlaceHttp.kt:183`, app name, version, contact). ASSUMPTION: Wikimedia's current rules for automated media downloads (User-Agent policy, rate limits on the upload host) allow 1 request per second with this agent; B2 reads the current policy pages before the first live call.
- Wi-Fi only for the photos (recommended, decision D3), whatever the global setting, and never under Offline only (`PlaceHttp.kt` privacy gate). The text seed keeps its own rule.
- Runs as a second, resumable background phase after the seed ("Reference photos: 412 of 980"), so places are usable at once. It also picks up areas that were seeded before this build (backfill), so the trip areas do not need to be seeded again.

### 4.2 Where to store (the smallest schema change)

`place_ref` cannot hold a picture or a vector (no blob column, caption is NOT NULL and means "reference caption"), and the vector tables of context.db are keyed by frame or memory and read by photo search, so a place vector there would leak into search results. So: places.db v3 -> v4, ONE additive table, nothing else touched.

```
place_image(
  placeId TEXT PRIMARY KEY,   -- the seeded place
  status TEXT NOT NULL,       -- OK, NONE (no free photo), SKIPPED (kind, mime, licence, size), FAILED
  tries INTEGER NOT NULL,
  jpeg BLOB,                  -- the thumbnail, null unless OK
  width INTEGER, height INTEGER, sha1 TEXT,
  filePage TEXT, author TEXT, licence TEXT, licenceUrl TEXT,   -- credit, kept for attribution
  space TEXT, dim INTEGER, vector BLOB, embeddedAt INTEGER,    -- vector of the ACTIVE space; replaced on a space change
  fetchedAt INTEGER NOT NULL, bytes INTEGER NOT NULL)
```

- `MIGRATION_PLACES_3_4` = that one CREATE TABLE; schema export `4.json`; Room migration test (plain) plus `EncryptedPlacesMigrationV3to4DeviceTest` copied from the existing v2->v3 one; emulator rehearsal from the installed s30 state (procedure of handoff 12u).
- Deletes: `deleteOrphanImages()` next to `deleteOrphanRefs()` in every place-delete transaction; `PLACE_PACK` family and `DeletionGraph` list the table. Delete all: unchanged behaviour, seeded places and their photos stay (public data, not personal), fetched places go and their photos with them.
- Backup and transfer: places.db is already excluded as a whole; no rule changes. Never in a settings export.
- Images stay on the phone. They are used for matching only and never sent anywhere, except the owner's own dump by exception (section 7, decision D10).
- Licences: Commons photos are mostly CC BY, CC BY-SA, CC0 or public domain. Private matching on one phone is personal use; the credit (author, licence, file page) is stored for each photo and shown in the history detail of a Layer 1 card ("Reference photo: author, licence, Wikimedia Commons"). Not legal advice; risk is low because nothing is redistributed.

### 4.3 When to embed
- A background worker embeds photos with status OK and no vector for the active space, one at a time, with the same image embedder the Moments use (the active space, today Nomic; `PlaceSamplers.kt:244-247`). Resumable (the row is the state), progress in Settings, a counts log line.
- Gates: phone charging (decision D4) AND thermal below MODERATE (`BackgroundWork.IMAGE_VECTORS`, `PhonePowerPolicy.kt:9`), batches of 25 with a short pause. Developer button "Prepare reference photos now" ignores charging, not heat.
- ASSUMPTION: 0.3-0.6 s per image, so about 5-10 minutes per 1,000 photos and 1-2 hours per 10,000. Measured on the phone in step C.

### 4.4 Size (ASSUMPTIONS until B2 logs the real numbers)

Per place with a photo: thumbnail about 30 KB + vector 1-3 KB (256 or 768 floats, depending on the stored dimension of the active space) + credit 0.3 KB, about 33 KB. Not every place has a free photo; guess 50 percent.

| Seeded places | Photos (50%) | Storage | Download |
|---|---|---|---|
| 1,000 | 500 | about 17 MB | about 15 MB, about 9 min at 1 per s |
| 10,000 | 5,000 | about 165 MB | about 150 MB, about 1.5 h at 1 per s |

Cap: 250 MB of photos in total (default, decision D2); above it new photos are SKIPPED and Settings says so. A deleted area frees rows but the encrypted file only shrinks after a vacuum (not built; note only).

Honest expectation: a Wikipedia lead photo is usually a wide outside view in good light; a glasses frame is a close, angled, often inside view. Expect LOW recall from WEB references (it works best at the front of a monument, from the classic angle), while precision stays high because HIGH is strict. Layers 2 and 3 carry the rest, and confirmed glances (3.3) add the owner's own angles over the trip.

## 5. Confirmed-only policy and the settings (owner addition 5)

Settings > Place Guide:
- "Cards on glasses: only when the photo matches" (default ON). Help: "A card appears on the glasses only when the photo from your glasses matches the place, on the phone or by the online check. An offline guess shows as 'Maybe'. Places that are only nearby are listed in Activity > Place cards with the reason."
- "How to check the photo": "Automatic" (default: on the phone first, then online when the network and Data saver allow, then the offline guess; also when an online call fails), "Online only" (never the offline guess), "On the phone only" (never online; same as Model privacy Offline only, which always wins). Recommendation: three modes, where Automatic is the "hybrid" the owner described (decision D8).
- "Offline place guess (Maybe)": default ON when the caption model is downloaded; otherwise greyed with "Needs the offline photo model (546 MB). Download it on Wi-Fi in Settings > Models."
- "Reference photos for seeded places": default ON, "Wi-Fi only, prepared while charging", progress and size.

Settings > Diagnostics > Developer, one switch per layer, each independent (owner addition 5):
- `placeL1Photo` Layer 1 (default ON; kill switch);
- `placeL2Vision` Layer 2 (default ON = today; OFF = never call the vision model for places);
- `offlineTier` + `offlineCaption` Layer 3 (both default ON now; the user switch above still applies);
- `placeMultiFrame` (default ON), `placeRefPhotos` fetch and embed (default ON), `placeGlanceRefs` new references from confirmed glances (default ON);
- the confirmed-only switch is the user setting above (no second copy).
The shadow flags stay as they are (OFF).

No silent skip: every attempt that reaches the candidate stage writes one history row (section 6). Reasons for a held card are tokens in the existing `delivery` text column (no schema change): `HELD_OFFLINE`, `HELD_DATA_SAVER`, `HELD_ONLINE_ONLY_NO_NETWORK`, `HELD_CALL_FAILED`, `HELD_NO_MODEL`, `HELD_NOT_CONFIRMED`. Activity shows them as "Not sent to glasses: no network" and so on. A held row never counts as shown (it must not feed the once-per-place rule or the dedupe seeding, `PlaceSampler.kt:758-761`).

## 6. Evaluation: so the owner can count TP, FP and FN (owner addition 4)

What exists: `place_card_log` rows for shown cards and `POSSIBLE` rows, with placeId, kind, frameId, confPct, delivery; the dump already exports place cards per Moment (`handoffs/dump-api-2026-11-26.md`). What is missing: rows for attempts that showed nothing, the numbers of each layer, the candidate list, and a label.

Without a schema change (step A):
- One `place_card_log` row per attempt that reaches the candidate stage. New kinds (text column): `IMAGE` (Layer 1 accept), `NO_MATCH` (nothing confirmed and nothing nearby). Existing kinds keep their meaning; `NEARBY` rows now usually carry a `HELD_*` delivery. `confPct` holds the deciding number (Layer 1 score / 10, Layer 2 confidence, Layer 3 cosine x 100).
- Activity hides `NO_MATCH` rows unless "Show all attempts" (Developer) is on.
- Log line per attempt, counts and buckets only: `place layers v=1 l1=<accept|medium|below|noref|skip_*> k=<n> cand=<n> withRef=<n> noRef=<n> score=<bucket> margin=<bucket> l2=<made|skipped_*|none> l3=<maybe|below|off|skip_*> card=<shown|held_*|none>`.

With one more small schema change (step B3, decision D9): context.db v18 -> v19, two additive columns on `place_card_log`:
- `eval TEXT` (JSON, at most 2 KB): layer that decided, frames used, candidate ids, per-candidate Layer 1 best score and reference kind, margin, Layer 2 choice and confidence, Layer 3 score and margin, thresholds version.
- `label TEXT`: `RIGHT`, `WRONG`, `NOT_A_PLACE`, set by one tap on the history row (three small buttons in the row detail). `RIGHT` on a confirmed card also creates the `REF` link of 3.3.
Both go with the row on every existing delete path (Forget, Delete all, retention), so Forget coverage needs no new choke point. The dump adds the two fields to place cards; the import carries them.

How the counts work: TP = a shown card (IMAGE, PHOTO, MAYBE) labelled Right. FP = a shown card labelled Wrong or Not a place. FN = a row with nothing shown (NO_MATCH, POSSIBLE, HELD NEARBY) labelled Wrong (there was a place and it was missed). Per layer from `kind` and `eval`.

If B3 slips, the owner labels on the Mac from the dump (photo + row) and recomputes Layer 1 scores there with the same model (his ruling: vectors are generated on the Mac, not exported).

## 7. What the owner can export (section h)

- Existing: frames and photos, Moments with location (owner exception), place cards per Moment.
- New, step B3: `eval` and `label` on place cards.
- New, step B2 (decision D10): an opt-in dump object type `place_ref` (NOT in the default set): seeded place id, name, kind, position and radius (public pack data, by exception like pin coordinates), photo status, credit, and the thumbnail at `/api/dump/place_refs/<id>/photo`. Vectors stay locked (ruling 26 Nov). With this, the Mac can rebuild every candidate list and every Layer 1 score.

## 8. Privacy (section i)
- Logs: counts, buckets and fixed tokens only, never a place id, name, coordinate, caption, URL, author or file name (`LogContentScanTest` covers the new literals).
- Captions of Layer 3 stay in a local variable, as today (`OfflineTier.kt:15-17`).
- Personal data (attempts, labels, glance references) lives only in context.db rows that Forget already covers. places.db stays public-only: the photos are public pictures, no visit is stored there.

## 9. Build order (Sonnet agents, parallel worktrees, one review)

Step A starts NOW and needs no schema change. Steps B add the schema work. Cut lines say what ships if B is late.

| Step | What | Depends | Size |
|---|---|---|---|
| A1 | Pure code: `PhotoConfirm` decider (HIGH / FLOOR / MARGIN per kind, max over references, two at HIGH = candidates, narrowed list rule), `MultiFrameQuery` (window, similarity, mean or best of k), `PhotoMatchConfig` with defaults and clamps; golden tests on fixed vectors for every threshold edge | none | 3 h |
| A2 | Sampler and policy: Layer 1 hook before the online/offline branch (`PlaceSampler.kt:465-478`) behind an interface A1 fills; references from `PinReferenceVectors`, manual photo, `place_link` REF and centroids; held rows and `HELD_*` tokens; `IMAGE` and `NO_MATCH` kinds; check modes; genuine call failure -> Layer 3 in Automatic; layer switches; Layer 3 defaults ON when the model is present; `place layers` log line; Settings fields and wording; "Right" -> REF link only after B3 | A1 interface (agreed in this doc) | 6 h |
| A3 | Mac only: rerun the harness on the 26 Nov pull for the 95% accept, the reject-below and the margin for 95%; mean-of-neighbours query effect on the same probes; WEB sanity check: lead photos of the seeded places of the home area against all glasses frames of the last week (precision at HIGH: how many false accepts) | none | 3 h |
| B1 | places.db v4 `place_image`: entity, DAO, migration, `4.json`, plain migration test, encrypted device test file, orphan delete on every place delete, family and deletion graph lists | none | 3 h |
| B2 | Fetch and embed: `pageimages` in the two existing calls, Commons metadata call, byte download through `PlaceHttp` (upload host only, cap, JPEG check), photo phase worker (Wi-Fi, resumable, backfill, progress), embed worker (charging and heat gates), Layer 1 reads WEB vectors, opt-in dump `place_ref` items; fake HTTP for Wikipedia, Commons and the image host; fake embedder | B1 | 5 h |
| B3 | context.db v19: `eval` and `label` columns, migration, `19.json`, encrypted device test, label buttons, REF link on Right, dump and import fields | A2 | 4 h |
| C | One Codex review (gpt sol, medium) of A+B together; fix round; `verify_final.sh`; emulator rehearsal of BOTH migrations from the installed s30 state with encrypted databases; staged build; install only on the owner's go | all | 1 day incl. waits |

Tests: golden thresholds (A1), sampler table tests for every layer path and every held token (A2), fake HTTP and fake embedder (B2), Room migration tests plus the two encrypted device tests and the emulator rehearsal (B1, B3), inventory tests (`ForgetPolicyTest`, `ProvenanceChokePointTest`, `PlaceLinkSourceRulesTest`, `LogContentScanTest`, `BackupRulesTest`) green.

Review: one Codex pass at medium on the full diff; if the Codex quota is out, Opus at low effort (never Sonnet, never skipped).

Timeline (trip starts Tue 1 Dec 05:00):
- Fri 27 night: A1, A2, A3 start in parallel (A2 codes against the A1 interface). B1 can start at the same time (separate database file).
- Sat 28: A merged by midday; B2 and B3 in parallel in the afternoon and evening. Optional (decision D11): install an A-only build on Saturday evening to collect real attempts at home landmarks a day earlier.
- Sun 29: Codex review in the morning, fixes, verify, emulator rehearsal of both migrations, staged build; install on go in the late afternoon; reference photos for the trip areas fetched on Wi-Fi and embedded overnight on the charger.
- Mon 30: device checks (Layer 1 accept at a seeded landmark, a held row with no network, Layer 3 Maybe in airplane mode, a label and the REF link, migration logs clean); go/no-go.
- Cut lines, Sunday 18:00: B3 not green -> ship without `eval`/`label` (labels on the Mac). B1/B2 not green -> ship A only (Layer 1 for pins and glances, the policy, Layer 3); seeded places then rely on Layers 2 and 3.

## 10. Risks
- Migration on the installed encrypted databases (places v4, context v19): additive only, rehearsed on the emulator from the real installed version, encrypted device tests; no destructive fallback exists, so a failed migration would stop the app. This is why the cut lines exist.
- Thresholds from tiny samples (42 probes, 30 none frames, no WEB data): precision could be lower than measured. Mitigations: strict HIGH, margin, the "two at HIGH = no accept" rule, kill switch, owner labels from day one.
- Self-confirmation: guarded by 3.3 (only Right or two-signal agreement, cap 5).
- Wikimedia limits and licences: polite pace, own limiter group, free licences only, credit stored, nothing redistributed.
- Storage and data: capped at 250 MB, Wi-Fi only, size shown before and during.
- Battery and heat: embedding only on the charger and below MODERATE heat; Layer 1 at attempt time is one embed; Layer 3 is rate-limited to one caption a minute and releases the model.
- Fewer cards: the policy shows fewer cards than today by design; held rows keep them visible in Activity.

## 11. Owner decisions
- D1 Lift the "no pack-place images" ruling: store one thumbnail per seeded place for offline matching (recommended yes).
- D2 Keep the thumbnail after its vector is made, cap 250 MB (recommended yes; the other choice is vectors only, which needs the network again after a model change).
- D3 Photos fetched on Wi-Fi only, whatever the global setting (recommended yes).
- D4 Embedding only while charging (plus the Developer "now" button) (recommended yes).
- D5 Example thresholds of 3.1 (OWN 945/846/20, OWN_CENTROID 967/879/20, WEB 945/off/20), replaced by the A3 95% values under the guard rule (recommended yes).
- D6 Multi-frame defaults: 20 s, up to 4 frames, similarity 920, mean (recommended yes).
- D7 Pin-first cards (location only, your own pins): keep them on the glasses (recommended) or hold them in Activity like nearby cards.
- D8 Three check modes (Automatic = hybrid, Online only, On the phone only) instead of four (recommended).
- D9 context.db v19 with `eval` and `label` on the card history (recommended yes, with the Sunday cut line).
- D10 Opt-in dump `place_ref` items with public position and the thumbnail, by exception (recommended yes).
- D11 Install an A-only build on Saturday evening for an extra day of real data (needs a quick review of A first; owner's call).
- D12 Turn the Scene centroid producer (`sceneCentroids`) on by default so pins get OWN_CENTROID references (recommended yes; it is derived data, never device-checked).
- D13 Layer 3 on by default once its model is present, cut-off 0.55 (example value from 10 probes and 4 none frames; it kept 5 of 10 right and 0 of 4 wrong), margin only counted (recommended yes).
- D14 Glances become references only through Right or two-signal agreement, cap 5 per place (recommended yes).
