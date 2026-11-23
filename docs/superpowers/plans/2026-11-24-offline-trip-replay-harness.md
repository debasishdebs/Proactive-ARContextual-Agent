# Offline trip-replay harness for Place Guide, pins, marks and Moments: design and test plan (2026-11-24)

Designer: Opus. Implementer: Sonnet, in small sequential steps (section 10). Reviewer: Codex at medium effort, or Opus at low effort when the Codex quota is out.
Branch `feat/v101-ask-eval`, `android-companion/`. This plan changes no app source. All new code is test code plus `tools/replay/`.

## 0. Why, and the rules

The user cannot go back to the places of the 21-23 Nov Hyderabad trip. This harness lets them check on the Mac what Place Guide would do with the real trip data, under the current HEAD gates and under each proposed fix (F1-F5).

Hard rules:
- **R1 Real data never enters git.** Real frames, captions, pins, coordinates and reports are kept only under `~/Desktop/rokid/backups/` (dir 700, files 600). The repo holds only synthetic, anonymised fixtures (section 7).
  - `tools/replay/build_bundle.py` refuses an `--out` inside a git work tree.
  - The real-data test refuses a `REPLAY_OUT` inside the repo.
- **R2 No secrets.** Nothing opens `db_key.xml`, `endpoint_keys.xml`, `rokid_link.xml` or `proactive_shadow.xml`. `context.db` is plain SQLite (verified) and is always copied to a temp directory before it is opened read-only.
- **R3 No network and no photo upload by default.** The optional real-model stage (section 5.4) is OFF unless the user turns it on for one run. It takes at most 5 chosen frames. It says in plain words when photos leave the Mac.
- **R4 Never invent a position.** Between media fixes, the position is interpolated only under the rule in section 4.1. Otherwise it is UNKNOWN. A position taken from a pin or a site is labelled ASSUMED on every row that uses it, and is never used for GPS backfill.
- **R5 Read-only phone.** `adb` may only be used to read. Nothing is installed or written on the phone.

## 1. Decision: Kotlin JVM test driving the real `PlaceSampler`, plus Python for data preparation only

**Recommended.** The harness is a set of Kotlin unit tests in `services/orchestration/src/test/java/com/rokid/context/work/frames/place/replay/`. They construct the real `PlaceSampler` (`services/orchestration/src/main/java/com/rokid/context/work/frames/place/PlaceSampler.kt`) through its constructor seams. These are the same seams `PlaceSamplerTest` uses:

| Seam | Replay fake |
|---|---|
| `store: PlaceFrameStore` | `ReplayFrameStore` |
| `fix: suspend () -> PlaceFix?` | `ReplayFixSource` |
| `candidates` | `ReplayPinPack` |
| `match` | `ReplayMatcher` |
| `clock` / `elapsed` | the replay clock |
| `networkUp` / `backend` | `true` / `BackendChoice.ONLINE` |
| `admit`, `photoAllowed` | `{ true }` |
| `zones` | `{ emptyList() }` (no pause zones existed, gap-analysis A.4) |
| `zoneBlocked` | false |
| `log` / `stats` | collectors |
| `flight: PlaceFlight` | shared, one per run |

Why Kotlin and not Python:
- **The gate code is not copied.** The replay uses the real code for all of these:
  - the gate order and the reason tokens;
  - `RECENT_MS` / `SKEW_MS`, `MAX_FIX_AGE_MS` / `MAX_FIX_ACCURACY_M`, the zone check on the fix;
  - `Geo.candidates` (300 m edge gap, 8 + 3 pin slots) and `cutKeepingPins`;
  - DEDUPE 120 s / 30 m, `MIN_CALL_GAP_MS` on `elapsed`, NONE / CONTRACT;
  - `BackendThresholds.minConfidence`, and the `fallback()` NEARBY / `ambiguous_second_within_100m` logic.
  A Python reimplementation would drift silently with every HEAD change. It could not act as the acceptance test when F1-F5 are later built into `PlaceSampler`.
- **Overlays become acceptance tests.** The F-overlays in section 6 are test-side what-ifs. When a fix is built for real, its overlay is deleted. The same replay must then give the same per-place verdict, so the overlay is the executable spec.
- `PlaceFlight.lastAttempt` is `internal`. A test in the same module can read and reset it, which is how F3 is simulated exactly.

Python is used only where Kotlin is the wrong tool:
- `tools/replay/build_bundle.py` (**written**, see section 3) reads SQLite, the prefs XML and the GPS CSV, and writes plain TSV. It avoids `org.json`, which is stubbed in JVM unit tests.
- The opt-in real-model stage (section 5.4) needs the Mac's model tooling, not the app's `AiGraph`.

Known fidelity limits, which must be printed in every report header:
- **BUSY is not reproduced.** The fakes return at once (production: 60 BUSY over the log window). Optional step 9 adds a simulated call latency on virtual time.
- **Pokes are approximate.** A poke is emitted for every `frame_meta` row at its `createdAt`. This approximates `KeyframeGraph.signal()` (`KeyframeGraph.kt:198-202`), which fires once per committed frame.
- **Media EXIF has no accuracy.** Accuracy comes from the scenario (section 4.2), never from the data.
- **Tombstones are replayed by time.** `tombAt` makes a frame invisible only after its tombstone time. Retention tombstones made after the trip have no effect on the replay.
- **Pins are replayed, not the pack.** The trip's pack is lost: `places.db` is encrypted and is not read (R2). So the wd Salar Jung, Golconda and Ramoji entries exist in the replay only as ASSUMED sites (section 4.4).

## 2. Inputs (all already on the Mac)

| Input | Path | Use |
|---|---|---|
| Dump DB | `~/Desktop/rokid/backups/phone-dump-2026-11-24/x/databases/context.db` (copied first) | `frame_meta` (createdAt, capturedAtMs, dwell, motion, sessionId, nearDupOf), `frame` (photo present), `forget_tombstone` (createdAt), `memory` caption/object text per frame, `memory` "Looked at X" place records, `moment_part` (kind FRAME) for backfill |
| Pins | `.../x/shared_prefs/place_pins.xml` | 9 pins. JSON keys `id,name,kind,lat,lon,r,parent,note`. All are `kind=monument` with no parent. Radii: home 10 m; Founders 15; Bronze, Ivory, SalarJung Museum, Antera 20; Golconda L&S 25 |
| Frames | `.../ph/files/rokid_archive/*.jpg` | Real-model stage only, max 5 frames |
| GPS track | `~/Desktop/rokid/backups/trip-gps/trip_media_gps.csv` | 189 fixes. The 12 videos take their time from the file name (`\d{8}[_-]\d{6}`). Source is `CAMERA` or `HIROKID`; a Hi Rokid image's GPS is the phone's, so its trust is lower and it is tagged as such |
| Candidates | `docs/superpowers/plans/handoffs/pin-moment-candidates-2026-11-24.md` | truth spans (section 4.5) |
| Phone log | `/tmp/pcalogs.txt` (22 Nov 18:34 to 24 Nov 03:32) | calibration only (section 8). **Copy it to `~/Desktop/rokid/backups/` now: `/tmp` is cleared on reboot.** |

## 3. Bundle builder (written and run once)

`tools/replay/build_bundle.py --dump <dump> --gps <csv> --out ~/Desktop/rokid/backups/replay-bundle-2026-11-24`

It prints counts only. A run to the scratch directory gave:
- `frames=3126 dwell=1955 withPhoto=2700 withText=2272`
- `fixes=189 pins=9 pinTimes=LOG:1,INFERRED:3,UNKNOWN:5`
- `historical=10`

A refused run (out dir inside the repo) creates nothing.

Files (TSV, epoch ms UTC):

| File | Columns / content |
|---|---|
| `frames.tsv` | `frameId createdAt capturedAt dwell motion sessionId tombAt photo nearDup text`. `text` is the trip's own caption/object text, at most 400 characters |
| `fixes.tsv` | `tMs lat lon src file` |
| `pins.tsv` | `id name kind lat lon radiusM parentId activeFrom activeSrc` |
| `historical.tsv` | the 10 trip "Looked at X" records: frame, name, confidence |
| `truth.tsv` | template, editable by the user and never overwritten |
| `sites.tsv` | empty template for ASSUMED pack sites |

Pin creation time (`activeSrc`):
- **LOG:** Antera, 23 Nov 22:00:51.6. PROVEN from the log.
- **INFERRED:** a media fix lies within 2 m of the pin, and the earliest such fix is taken as the creation time. Three pins qualify: Bronze 17:34:18, Ivory 17:51:34, Golconda L&S 19:26:04. The Bronze card at 17:34:36 is consistent with this.
- **UNKNOWN:** the other 5 pins.

Coverage per truth span, from the bundle (dwell frames / with a media fix ≤60 s away / with an interpolation-capable fix pair ≤5 min each side / with photo):

| Span | Dwell | Fix ≤60 s | Interp ≤5 min | Photo |
|---|---|---|---|---|
| SJ entry 17:09:30-17:13 | 8 | 3 | 0 | 8 |
| SJ Founders 17:19-17:31:30 | 33 | **0** | 0 | 33 |
| SJ Bronze 17:32-17:40 | 17 | 16 | 13 | 17 |
| SJ Ivory 17:50-17:54 | 10 | 10 | 10 | 10 |
| SJ exterior 18:04-18:10 | 6 | 4 | 3 | 6 |
| Golconda L&S 19:20-20:10 | 81 | 27 | 34 | 81 |
| Ramoji 22 Nov 18:08-21:15 | 288 | **0** | 0 | 288 |
| Antera 23 Nov 21:50-23:57 | 294 | **0** | 0 | 294 |
| OneGold 21 Nov 22:41-23:59 | 164 | 15 (phone videos 23:08-23:47) | 8 | 164 |

SJ exterior frames, with the distance in time to the nearest media fix before / after:

| Frame | Before | After |
|---|---|---|
| 18:04:41 | 37 s | 67 s |
| 18:05:17 | 73 s | 31 s |
| **18:05:50** | **1 s** | **3 s** |
| 18:06:17 | 19 s | 72 min |
| 18:08:29 | 151 s | 70 min |
| 18:09:01 | 183 s | 69 min |

## 4. Harness model

### 4.1 Position from the track (`ReplayTrack`)
- **causal(t):** the newest fix at or before t; its age is `t - fixT`.
- **near(t, N):**
  - If a fix lies within N on both sides, the two are ≤ 200 m apart and the implied speed is ≤ 2 m/s: linear interpolation (`src=INTERP`).
  - Else, if only one side lies within N: that fix (`src=NEAREST`).
  - Else: UNKNOWN.
  - Default N = 5 min. N is a parameter, swept 2/5/10 min.
- UNKNOWN means `fix()` returns null. The sampler then logs NO_FIX `fix_unavailable`, and the table shows `pos=UNKNOWN`.

### 4.2 Fix scenarios (`FixScenario`, the `PlaceFix` given to `fix()`)

| Scenario | Position | `ageMs` | `accuracyM` | Purpose |
|---|---|---|---|---|
| `AS_RECORDED` | causal(t) | real age | 15 (EXIF has none; flagged `acc=assumed`) | What a phone that fixed only when the user took a photo would give |
| `OUTDOOR_10M` | near(t, N) | 3 000 | 10 | Best case: a good GPS fix at every attempt |
| `INDOOR_60M` | near(t, N) | 3 000 | 60 | Indoor reality (fails `MAX_FIX_ACCURACY_M` 50) |
| `STALE` | near(t, N) | max(61 000, real) | 10 | Fix-age gate |
| `ASSUMED_PIN(acc)` | the target pin/site centre for the span's frames, else near(t, N) | 3 000 | acc (10 or 60) | Antera and Ramoji only. Every row is labelled ASSUMED and kept out of backfill |

### 4.3 Frame store (`ReplayFrameStore : PlaceFrameStore`)
This is the same query as `FrameMetaDao.newestDwell` (`FrameDaos.kt:400`), evaluated as of replay time `now`:
- the dwell row with the greatest `createdAt` in `[since, now]`, ties broken by `frameId`;
- not tombstoned at `now` (`tombAt == -1 || tombAt > now`);
- capture time `capturedAt` (as in `RoomPlaceFrameStore`).

`tombstoned(id)` uses the same rule. The store records the last frame it returned, so every outcome can be attributed to a frame.

### 4.4 Candidates (`ReplayPinPack`)
- Active pins at `now`, as `UserPlace(...).toEntity()`.
- `ActivationMode`:
  - `AS_CREATED` (default): LOG/INFERRED pins from `activeFrom`, UNKNOWN pins always on, flagged.
  - `ALWAYS`: the "next visit" question.
- Plus the rows of `sites.tsv` when `--sites` is on. They are ASSUMED and only for calibration (section 8). The user fills them by hand, or, in step 8, they are derived from the median of media fixes in a span, radius 150 m, kind from `PlaceKinds.ALL`.
- Result: `Geo.candidates(active, lat, lon)`, then `Geo.expandSubPlaces` (children = active pins whose `parentId` was chosen), wrapped as `PlaceCandidates(list, CellOutcome.FRESH)`. This is `PlacePack.candidates` without Room.
- Nothing else is in the pack. Overpass holds no restaurants or venues (gap-analysis B.0).

### 4.5 Truth (`truth.tsv`, editable; `TruthSpans`)

| Label | Target | Window (IST) | Source | Testable |
|---|---|---|---|---|
| SJ_ENTRY | SalarJung Museum | 21 Nov 17:09:30-17:13 | candidates doc | yes |
| SJ_FOUNDERS | Salar jung Founders gallery | 17:19-17:31:30 | candidates + user (weak) | yes |
| SJ_BRONZE | Bronze Gallery SalarJung | 17:32-17:40 | candidates | yes |
| SJ_IVORY | Ivory Room SJM | 17:50-17:54 | candidates | yes |
| SJ_EXTERIOR | SalarJung Museum | 18:04-18:10 | user | yes |
| GOLCONDA_LS | Golconda Fort Light & sound show | 19:20-20:10 | user | yes |
| RAMOJI | SITE:Ramoji Film City | 22 Nov 18:08-21:15 | user | assumed fix only |
| ANTERA_FACADE | Antera | 23 Nov 22:00:30-22:00:50 | log + candidates | assumed fix only |
| ANTERA | Antera | 23 Nov 21:50-23:57 | user | assumed fix only |
| ONEGOLD | - | 21 Nov 22:41:34-23:59 | gap analysis | **no** |

## 5. Match stages (`ReplayMatcher`, plugged into the `match` lambda)

The sampler gives `(frameId, lines)` with lines `"N. name | kind"`. The stage returns `PlaceMatchResult(PlaceMatchOutput(choice, conf), delivered = true)`. Every stage also records the lines in `requests.tsv`, which feeds section 5.4.

1. **`StubNone`:** always `"NONE"`, 0.9. This is the floor: no identification at all.
2. **`Historical`:** for the 10 real "Looked at X" frames, the index of the line whose name equals the recorded name (pins by name, sites by name) and the real confidence. Every other frame gets NONE.
   - This is the trip's real model as far as it is known.
   - A recorded name missing from the lines gives `CONTRACT` and the table shows `hist_name_not_in_candidates`.
3. **`Oracle`:** a perfect identifier.
   - If the frame lies inside a truth span and the target is among the lines, it returns that index at 0.9. Otherwise NONE.
   - "Target not among candidates" is shown as its own token (`truth_not_in_candidates`). That is the key signal that a gate or the pack, not the model, lost the card.
4. **`Recorded` (real model, opt-in):**
   - It reads `real_match.tsv` (frameId, linesHash, choice, conf, backend), written by `tools/replay/real_match.py` (step 10, not written yet).
   - The script is OFF by default. It runs only with `--enable-real-model`, a `--frames` file of at most 5 frame ids, and a typed confirmation `SEND <n> PHOTOS`.
   - Backends:
     - `local` (default): image-text similarity on the Mac (SigLIP2 / Nomic tooling as in `tools/nomic_reference.py`). The photo never leaves the Mac; only public model weights are downloaded, once.
     - `remote`: the `place_match` prompt with the exact `lines`, against an endpoint from `tools/llm/.env`. Before sending, it prints `THESE <n> PHOTOS WILL LEAVE THIS MAC AND GO TO <host>`.
   - A frame or `lines` that is not in the file falls back to Oracle, and the cell is starred.
   - Keys are never printed.
5. **`CaptionText` (F5, section 6):** matches the trip's stored caption/object text. It makes no new model call.

## 6. Proposed-fix overlays (test-side what-ifs)

Each overlay changes exactly one thing around the real sampler.

| Fix | Overlay | Parameters |
|---|---|---|
| **F1** pin-first card | `PinFirstRule.decide(fix, activePins, visitState)`: a pure function, evaluated at every poke that has a fix (whatever the sampler said). It fires when exactly one active pin has `dist + acc <= r + slack`, `age <= 60 s` and `acc <= 25 m`. It fires once per pin per visit: the visit ends after the fix leaves `r + 50 m` for 10 min, or after 60 min. Two or more qualifying pins give `ambiguous(n)`, never a guess. | `slack` 10 m, `maxAcc` 25 m, visit 60 min |
| **F2** relaxed indoor fix | A `ReplayFixSource` wrapper. When the scenario fix would fail the age or accuracy gate, the frame is STILL/dwell, and a good fix (`acc <= 50`) exists ≤ N min earlier in the same session: it returns that good fix with `ageMs = 0`, flagged `F2`. | N = 5 / 10 / 20 min |
| **F3** dedupe not consumed by weak frames | Snapshot `flight.lastAttempt` before each poke. If the outcome is NONE, LOW_CONFIDENCE, CONTRACT or FAILED, restore it. Variant F3b: keep it, but shorten its window to 30 s (`at = now - DEDUPE_MS + 30_000`). | variant a / b |
| **F4** retry on pin creation | At each pin's `activeFrom` (AS_CREATED), one extra poke at `now = activeFrom + 1 s`. It uses a fresh `PlaceSampler`, so `handled` is empty and SEEN is bypassed, the same `PlaceFlight`, and `lastAttempt = null` (pin creation is an explicit user act). Shown as a `+F4` row. | none |
| **F5** ID by signage text | The `CaptionText` matcher. It normalises (lowercase, alphanumeric tokens, `salarjung` → `salar jung`) and matches a candidate when a distinctive name token of 4 or more letters, outside the stop list {museum, gallery, room, fort, show, light, sound, kitchen, bar}, appears in the frame's text. Confidence 0.8. Otherwise NONE. Variant F5-nofix: the same, against active pins within 2 km of the last known position, for frames with no fix. | stop list, distance |
| **ALL** | F1 + F2(10 min) + F3a + F4 + F5 together | |

## 7. Output: per-place timeline (on the Mac only)

**Runner.** `TripReplayRunner.run(bundle, RunSpec(stage, scenario, activation, overlays, N))`:
- Sorts all `frame_meta` rows by `createdAt`.
- For each row it sets `now = elapsed = createdAt`, calls `sampler.poke()` and then `runCurrent()`.
- It collects a `ReplayRow`: poke time, frame returned, motion, fix (src, Δt, scenario, acc), `PlaceOutcome` name, and the `reason=` token parsed from the `place attempt` line, which is the authoritative token.
- It also records the card (photo or nearby, place id) and the F1 decision.

**Report.** `ReplayReport` writes `REPLAY_OUT/<runStamp>/`:
- `summary.md`: the fidelity limits, an acceptance verdict per place, and outcome histograms per run.
- `place-<LABEL>.md`: one table per place.
- `attempts.log`: the raw `place attempt` lines. Feed it to `tools/place-attempt-report.sh --file` for the usual counts.
- `requests.tsv`.

Per-place table (pokes whose returned frame lies in the span; consecutive SEEN / NO_FRAME rows are collapsed to `×n`):

| IST | Frame (last 6) | Motion | Pos (src Δt) | HEAD outcome · reason | F1 | F2 | F3 | F4 | F5 | ALL | Truth |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 18:04:41 | …a1b2c3 | STILL | CAMERA 37 s | NONE · model_none | card SalarJung Museum | = | = | - | NONE | MATCHED | SJ_EXTERIOR |
| 18:05:50 | … | STILL | INTERP 1 s | DUPLICATE · same_place_recently | (visit) | = | MATCHED · matched | - | … | … | SJ_EXTERIOR |

(The cells above are illustrative only; the run fills them. `=` means the same as HEAD.)

One table is produced per (stage × scenario). The default set is:
- Oracle × {AS_RECORDED, OUTDOOR_10M, INDOOR_60M, STALE} × AS_CREATED;
- Historical × OUTDOOR_10M (calibration);
- StubNone × OUTDOOR_10M;
- ALWAYS activation for the next-visit view.

## 8. Acceptance criteria per place

All criteria are machine-checked in `summary.md`. "Card" means MATCHED or NEARBY published in the replay, or F1 `card`.

**Calibration first (harness fidelity), Historical × OUTDOOR_10M × AS_CREATED with `--sites` (ASSUMED wd sites for SJ / Golconda / Ramoji):**
- The replay reproduces MATCHED for SJ 17:09:59 and 17:12:05, Bronze 17:34:36 and Golconda 19:21:05.
- Ramoji 18:11:03 is MATCHED.
- **Known divergence, to be printed, not to fail the run:** Ramoji 18:12:23 becomes DUPLICATE, because a constant assumed fix is within 30 m; the real phone moved.
- If calibration fails, every verdict is marked UNTRUSTED.
- Qualitative check against `pcalogs.txt` for Antera 22-23 h: under INDOOR_60M, STALE_FIX and DUPLICATE dominate after 22:01, and both facade frames give NO_CANDIDATES before 22:00:51 and then SEEN. The exact counts cannot be compared, because the old build did not log accuracy.

**Criteria per place:**

| Place | HEAD expectation (to be confirmed by the run) | Pass criterion for the fix set |
|---|---|---|
| **Salar Jung exterior**, Sat 21 18:04-18:10 | The 18:04:41 attempt sets `lastAttempt`. Under Stub / Historical: NONE, then 18:05:50 is DUPLICATE (gap-analysis case 1) | Oracle + F3 (alone) gives ≥1 SalarJung Museum card with a frame in 18:04-18:10 under AS_RECORDED and OUTDOOR_10M. F1 gives exactly one SJ card for the visit, never a room card from an exterior fix. Zero wrong-place cards in every scenario |
| **Salar Jung rooms** (Founders 17:19-17:31, Bronze 17:32-17:40, Ivory 17:50-17:54) | Bronze and Ivory have fixes ≤60 s; Founders has none (UNKNOWN, 22-minute media gap) | (a) Calibration Bronze 17:34:36 reproduced. (b) Oracle × OUTDOOR_10M: ≥1 card for Bronze and for Ivory in their spans. (c) INDOOR_60M at HEAD: STALE_FIX `fix_inaccurate`, no card. With F2(10 min): a card only when the reused fix lies inside that room pin. (d) **No wrong-room card in any scenario.** F1 must say `ambiguous` rather than pick, because the pins are 28-84 m apart with r 15-20 m. (e) Founders is reported "not testable by location (no fix within N)". F5 may pass it only if its captions carry the name (unlikely: room text is not the pin name) |
| **Golconda L&S**, Sat 21 19:20-20:10 | The pin was INFERRED as created 19:26:04. Before then only an ASSUMED wd Golconda site can match | AS_CREATED + F4: a pin card within 90 s of 19:26:04 when a dwell frame exists in [−90 s, 0]. F1 + F3: exactly one L&S card per visit and **no more than 1 card in the 38 seated minutes** (no card storm). ALWAYS + OUTDOOR_10M + Oracle: ≥1 card by 19:25 |
| **Ramoji**, Sun 22 evening | 288 dwell frames, 0 media fixes within 15 min (phone photos 17:18-17:51, frames from 18:08). No pin. The Bahubali set visit lies inside gap 2: no frames | **Only ASSUMED.** With `--sites` and ASSUMED_PIN(10): reproduce the 18:11:03 card (calibration). The set is reported "not testable: no frames" |
| **Antera**, Mon 23 22:00-23:57 | No media fix near (nearest 23 Nov fix is 8.5 km away at 21:07). Handling: ASSUMED_PIN at the Antera pin centre. OUTDOOR acc 10 for ANTERA_FACADE; INDOOR acc 60 after 22:01. Pin active from 22:00:51.6 (LOG) | **F4 is the primary criterion:** the `+F4` poke at 22:00:52.6 picks frame 22:00:43 or 22:00:48 (≤90 s) and gives an Antera card. F1 (facade, acc 10) gives one card. Under INDOOR_60M, HEAD gives no card (as on the trip); F2 cannot help (no earlier good fix): report "needs indoor policy (device)". Every Antera row is labelled ASSUMED |
| **OneGold** | No pin. Entrance inside the phone pause 20:33-22:41, so no entrance frames. No venue in the pack | **Not testable.** The report prints `ONEGOLD: not testable (no pin; entrance in pause; venue never in pack)`. The video fixes 23:08-23:47 are used only for backfill |

## 9. GPS backfill for existing Moments (secondary)

Script: `tools/replay/backfill_gps.py` (step 11). It is a dry run only and writes `~/Desktop/rokid/backups/replay-out/backfill-<date>.tsv`.

**Input.** Moments with a FRAME part (`moment_part.kind='FRAME'` joined to `frame_meta.capturedAtMs`). The first photo frame is used, as `MomentDao.photoFrameOf` does.

**Rule** ("only pull what you can, do not force"):
- Use the near(t, N) rule from section 4.1. Default N = 5 min, or 2 min when the frame's motion is not STILL.
- Write: `momentId, capturedAt, lat, lon, uncertaintyM, src(INTERP|NEAREST), dtS`, with `uncertaintyM = 15 + 1.4 m/s × |Δt|`.
- Otherwise the row is blank. A pin or site centre is never used, and a row is never filled from an ASSUMED position.

**Measured coverage.** Of 1 153 trip Moments, a fix lies within 2 / 5 / 10 min for 91 / 128 / 191 of them.

**Applying it to the phone is a later, separate step:**
- It needs the "location with each Moment" field (trip-analysis fix C: local only, privacy class, inside the Forget cascade, `fixSource=MEDIA_BACKFILL`).
- It needs a one-shot developer import that the user starts. The file is validated, then deleted.
- Not now (R5).

## 10. Regression pack in the repo (synthetic only)

Resources live in `services/orchestration/src/test/resources/replay/synthetic/`. The files are the same TSV formats.

Anonymisation rules:
- Invented origin (lat 10.0, lon 20.0).
- Offsets that reproduce the trip shapes, not the trip coordinates.
- Times from `T0 = 1_000_000_000_000`.
- Frame ids `s0001`…
- Pin ids that are valid `user:` UUIDs, generated.
- Generic names: "Site A", "Room A1"…

Guard test: `SyntheticFixtureGuardTest.syntheticFixturesHoldNoRealData`. It checks that:
- every coordinate is within 5 km of the origin;
- no id matches `^\d{13}_rokid_`;
- no text contains a deny-list of trip words (Salar, Golconda, Antera, Ramoji, OneGold, Hyderabad, Jubilee);
- there are no jpg files.

`TripReplaySyntheticTest` (always runs, no environment variable needed):
- `siteExterior_weakFirstFrameConsumesDedupe_atHead`
- `siteExterior_f3_cardInsideWindow`
- `nestedRoomPins_indoorFix_neverShowsAWrongRoom` (all scenarios × overlays)
- `nestedRoomPins_f1_ambiguousInsteadOfGuess`
- `seatedShow_f1f3_oneCardPerVisit_noStorm`
- `pinCreatedAfterFacade_headSeesNoCandidatesThenSeen`
- `pinCreatedAfterFacade_f4_retriesFrameInView`
- `indoorAfterOneGoodFix_f2_reusesWithinNMinutesOnly`
- `noPinNoCandidates_reportedNotTestable`
- `gpsGapBeyondN_isUnknownNotInvented`
- `interpolation_onlyWithin5MinEachSideAnd200m`
- `assumedPositionRows_areLabelledAndExcludedFromBackfill`
- `historicalStage_reproducesRecordedMatches`
- `oracle_reportsTruthNotInCandidates`
- `realModelStage_isOffByDefault_andNeverReadsPhotos`

`ReplayTrackTest`:
- `causalNeverUsesFutureFix`
- `nearestOneSideWithinN`
- `speedAbove2msIsUnknown`

`TripReplayRealDataTest` (skipped unless `REPLAY_BUNDLE` is set):
- `replayTrip_writesTimelinesAndVerdicts`
- `refusesOutputInsideRepo`

## 11. What cannot be tested offline, and the device checks that remain

| Not testable offline | Why | Device check (effort) |
|---|---|---|
| The glasses showing the pop-up (PopupGate, overlay permission, screen interactive, 12 s / head motion) | Glasses UI and screen state; the glasses never report a skip (gap-analysis C4, D7) | Walk to a pin with the glasses minimised, screen on and off, and log the glasses-side outcome (0.5 day after D7 exists; 1 h manual today) |
| BT "glasses nearby" wake / AutoStop recovery | Broadcast plus Doze plus the bonded address (C1) | Power-cycle the glasses after 25 min away; expect hub start within 1 min (1 h) |
| Glasses battery ≤10 % stop and drain rate | Hardware | A battery run with `tools/battery-run.sh` (2-3 h, passive) |
| Indoor GPS accuracy and age in real rooms; whether F2's "last good fix" is in the right room | Real radio conditions; EXIF has no accuracy | Debug build logging `accB` / `fixAgeB` (the HEAD `place attempt` line) for 30 min indoors (mall or restaurant) and 15 min outdoors (1 h + log pull) |
| A real model's NONE rate on dim or night frames | Model behaviour; the user's photos are not sent by default | Opt-in `real_match.py` on at most 5 frames, or the next trip's logs |
| Mark (MARK Moment) capture end to end | The old build logged nothing; marks are glasses gesture → phone | Press mark 3× on the device and check the MARK Moments and log lines (20 min) |
| Card-to-HUD latency, BUSY under real call latency | Network and timing | Partly with step 9 (simulated latency); the real value only on device |

## 12. Implementation order for Sonnet

Rules for every step:
- Small, sequential steps.
- Run Gradle only one build at a time, always through `tools/gate.sh <log> --no-daemon :services:orchestration:testDebugUnitTest --tests '*replay*' --rerun`, and wait for `EXIT=`. Never two Gradle runs at once; never `pkill`.
- Commit after each green step.
- Test-only code; no `build.gradle.kts` change.

| # | Step | Files | Effort |
|---|---|---|---|
| 1 | Bundle model and TSV reader (`ReplayBundle`, `ReplayFrame`, `ReplayFix`, `ReplayPin`, `TruthSpan`, `HistoricalMatch`) plus the synthetic resources plus `SyntheticFixtureGuardTest` | `replay/ReplayBundle.kt`, `resources/replay/synthetic/*.tsv` | S (1-2 h) |
| 2 | `ReplayTrack` (causal / near / interp / UNKNOWN) and `FixScenario`, plus `ReplayTrackTest` | `replay/ReplayTrack.kt` | S (1-2 h) |
| 3 | `ReplayFrameStore`, `ReplayPinPack` (AS_CREATED / ALWAYS, `--sites`) | `replay/ReplayFakes.kt` | S (1-2 h) |
| 4 | Matchers: StubNone, Historical, Oracle, CaptionText, plus `requests.tsv` | `replay/ReplayMatchers.kt` | S (1-2 h) |
| 5 | `TripReplayRunner` (poke loop, row capture, reason parse) plus the first 6 synthetic tests (HEAD behaviour) | `replay/TripReplayRunner.kt`, `TripReplaySyntheticTest.kt` | M (2-4 h) |
| 6 | Overlays F1-F5 and ALL, plus the remaining synthetic tests | `replay/WhatIf.kt` | M (3-4 h) |
| 7 | `ReplayReport` (timelines, verdicts, histograms, fidelity header), plus `TripReplayRealDataTest` (env-gated, refuses repo output). The user runs: `REPLAY_BUNDLE=… REPLAY_OUT=~/Desktop/rokid/backups/replay-out tools/gate.sh …` | `replay/ReplayReport.kt`, `TripReplayRealDataTest.kt` | M (2-3 h) |
| 8 | `build_bundle.py --derive-sites` (ASSUMED sites from the media-fix median) and the calibration verdicts | `tools/replay/build_bundle.py` | S (1 h) |
| 9 | Optional: simulated call latency on virtual time (BUSY fidelity) | runner | S (1-2 h) |
| 10 | Optional, needs the user's go per run: `tools/replay/real_match.py` (local default, remote with an explicit warning, at most 5 frames) and the `Recorded` matcher | `tools/replay/real_match.py` | M (3 h) |
| 11 | `tools/replay/backfill_gps.py` dry run | `tools/replay/backfill_gps.py` | S (1 h) |

**Review:** one Codex (medium) review of steps 1-7 together, then a second for 8-11. Check above all R1-R4: no real data in the diff, no network, no secret paths.

**Total:** about 2-3 days for steps 1-8 and 11; steps 9 and 10 are optional (about 0.5-1 day).

## 13. Open points for the user

1. Confirm or edit `truth.tsv` spans. Founders is the weakest (17:19 vs 17:26).
2. Allow ASSUMED wd sites (`--sites`) for calibration. Positions are derived from your own media fixes; no network is used.
3. Real-model stage: local only, or allow remote for up to 5 frames on one named run?
4. Copy `/tmp/pcalogs.txt` into `~/Desktop/rokid/backups/` before the next reboot.
5. Photo retention (`photo_days=7`) deletes the 21 Nov photos from about 29 Nov. The replay needs the JPEGs only for the real-model stage (the bundle already holds the text), but pin-to-Moment attachment needs them on the phone.
