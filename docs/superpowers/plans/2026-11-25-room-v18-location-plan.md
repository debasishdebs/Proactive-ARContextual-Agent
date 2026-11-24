# Room v18 of context.db: card history + Moment location + GPS backfill (plan, 2026-11-25)

Designer: Opus. Status: PLAN, needs the owner's approval and the six answers in section 9. No code changed. Base design: `2026-11-24-card-history-in-context-db-design.md` (called CH below; its sections 2-8 stand unless this plan says otherwise). Earlier sketch: `2026-11-24-place-fixes-build-spec.md` section 7 (this plan supersedes its schema). Read at tip `be39c384`. Paths are under `android-companion/` unless noted. CH's line numbers come from `cdef4c7e`/`b3b487a2`, so WP0 re-reads them.

## 1. Goals and non-goals
**Goals.** (1) One schema bump, context.db v17 -> v18, with two additive tables: `place_card_log` (CH) and `moment_location`. (2) Card history becomes a context.db Forget family, so the owed-token machinery goes away (CH 3-8). (3) New Moments get an OPTIONAL location, taken from a fix the phone already holds near the capture time. (4) An idempotent, owner-started import fills past Moments from `tools/replay/backfill_gps.py`. It only ever fills empty rows. (5) Backup first, a rehearsal on a copy of the real data, and a hard fallback: if v18 is not verified by **Mon 30 Nov 20:00 IST**, ship without it.

**Non-goals.** No new location stack: no new permission, no new location request, no background location, no geofence. No ALTER of any existing table. No AutoMigration. No change to `MomentBuilder`'s output, `contentHash` or `bindingKey`. The location never goes into a prompt, a model call, an export, a backup or a log. No map or Moment-detail UI for the location in v18 (the density layer comes later). Canvas v2.x and Nexus are out of scope.

## 2. Exact schema (hand-written `MIGRATION_17_18`, after `MIGRATION_16_17` in `memory/.../db/ContextDatabase.kt:353`)
```sql
-- (a) card history: byte-identical to CH 2.3 / MIGRATION_PLACES_1_2
CREATE TABLE IF NOT EXISTS `place_card_log` (`id` TEXT NOT NULL, `at` INTEGER NOT NULL, `placeId` TEXT NOT NULL, `title` TEXT NOT NULL, `kind` TEXT NOT NULL, `members` TEXT, `frameId` TEXT, `confPct` INTEGER, `cardId` TEXT, `delivery` TEXT NOT NULL, `photoGone` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`));
CREATE INDEX IF NOT EXISTS `index_place_card_log_at` ON `place_card_log` (`at`);
CREATE INDEX IF NOT EXISTS `index_place_card_log_frameId` ON `place_card_log` (`frameId`);
-- (b) Moment location: one optional row per Moment second
CREATE TABLE IF NOT EXISTS `moment_location` (`momentId` TEXT NOT NULL, `atMs` INTEGER NOT NULL, `anchorKind` TEXT NOT NULL, `anchorId` TEXT NOT NULL, `lat` REAL NOT NULL, `lon` REAL NOT NULL, `accuracyM` REAL NOT NULL, `fixAgeMs` INTEGER NOT NULL, `source` TEXT NOT NULL, `savedAt` INTEGER NOT NULL, PRIMARY KEY(`momentId`));
CREATE INDEX IF NOT EXISTS `index_moment_location_atMs` ON `moment_location` (`atMs`);
CREATE INDEX IF NOT EXISTS `index_moment_location_anchorId` ON `moment_location` (`anchorId`);
```
- **`momentId`** is `MomentId.ofMs(captureMs)`, the hex of the capture second (`memory/.../moment/MomentId.kt:12`). It is deterministic, so the row can be written at ingest, before the Moment row exists. That is why this is a side table keyed by the second and not a column on `moment`, which the builder rewrites on every build.
- **`anchorKind`** is `FRAME` or `MARK`. **`anchorId`** is the `frame_meta.frameId` or the `canvas_label.labelId`. **`atMs`** is the capture time.
- **`source`** is `FIX` (a fresh fix the sampler or zone stream held), `LAST_KNOWN` (the system's last known fix) or `MEDIA_BACKFILL`. **`fixAgeMs`** is `|fix time - capture time|`.
- **There is no foreign key and no trigger.** The Moment is a rebuildable projection. Writes use INSERT OR IGNORE: the first writer wins, so a backfill never overwrites a live fix.
- **Entity `MomentLocationEntity` and `MomentLocationDao`** go in a new file, `memory/.../db/MomentLocation.kt`. The DAO has `of(ids)`, `count()`, `insertIfAbsent`, `deleteForMoments`, `deleteForAnchors`, `deleteBetween(from,to)`, `deleteAll`.
- **Also:** `version = 18` (`:24`); the migration added to BOTH lists in `DatabaseFactory.kt:13,23`; `schemas/.../ContextDatabase/18.json` exported and committed; the DDL above diffed against `18.json` before merge.
- **places.db goes to v3** with `MIGRATION_PLACES_2_3` = `DROP TABLE IF EXISTS place_card_log` and the 1 -> 2 -> 3 chain, as CH 2.4. No `fallbackToDestructiveMigration*` anywhere.

## 3. Where each piece plugs in
**Card history.** Exactly CH 2-8: the `PLACE_HISTORY` family, the policy row, the six Forget choke points, the `savePins` order, the guarded insert and the removal list.

**Moment location: Forget wiring.** A new leaf family `MOMENT_LOCATION` (`forget/lifecycle/Families.kt`, `Ref.kt`, `ForgetPolicy.kt`). It is NOT added to `MomentFamily.tables`: `MomentDao.remove` (`MomentDao.kt:62`) also runs for builder removals, and those must not destroy a capture fact that cannot be rebuilt. It reuses CH's choke points, in the same transactions:
- photo Forget, legacy and executor (`ForgetService.dropCanvasOutputs`, `ForgetExecutor.dropCanvasOutputs`): `deleteForAnchors(frameIds)` + `deleteForMoments(seconds of those frames)`.
- every user span (`CanvasSlice3Dao.forgetRangeTx`): `deleteBetween`.
- single mark delete (`CanvasSlice3Dao` `deleteLabelRow` path, `:125`): `deleteForAnchors(labelId)`.
- Delete all (`WipeDao.wipeMemory`, next to `momentSensor()` at `:75`): `deleteAll`.
- **Defence in depth:** the same transactions also run `DELETE FROM moment_location WHERE momentId IN (SELECT momentId FROM moment_tombstone)`.
- Photo retention KEEPS the row while its `frame_meta` row or mark lives (Q4). The orphan frame expiry deletes it with the frame row.
- `DeletionGraph.TABLES` (`forget/DeletionGraph.kt`, context.db group) gets `w("moment_location", ...)`. `FamilyInventoryTest` and `DeletionGraphInventoryTest` fail until it is listed.

**New Moments: the recorder.** A new `MomentLocationRecorder` (orchestration `canvas/moment/MomentLocationRecorder.kt`, pure logic, every input injected). It is poked from `KeyframeGraph.signal()` (`work/frames/KeyframeGraph.kt:200-204`, next to the place poke) and after a mark insert.
- **Each poke:** for frame or mark seconds of the last 2 min that have no row, pick a held fix with `|fix - capture| <= 60 s` and accuracy <= 100 m, then INSERT OR IGNORE.
- **Fix sources, all existing, read-only, no request made**, in order:
  1. `PlaceSamplers.flight.good` (`work/frames/place/PlaceSamplers.kt:84`, `PlaceSampler.kt:~411`). It has already been judged against the zones.
  2. `ZoneController.lastFix()` (`phone/.../zone/ZoneController.kt:213`, elapsed clock), the stream `TripLocation` feeds while a zone exists. `PlaceFixProvider.fresh()` reads the same fix, but its fallback is a one-shot HIGH_ACCURACY request, so the recorder never calls it.
  3. `LocationSource.lastKnown` (`connectors/.../sources/LocationSource.kt:67`, NETWORK then PASSIVE, wall clock). `Fix` gains a nullable `accuracyM`; a fix without accuracy is skipped.
  - The hub's `loc` evidence (`SpeedSource`) keeps no fix, so it is not a source.
- **Wiring:** in `phone/.../hub/HubGraph.kt` next to `placeFixes` (`:201`).
- **Refused, with a counts-only log `moment loc skipped reason=...`, when:**
  - the setting is OFF;
  - the `placeZoneBlocked` predicate holds (`HubGraph.kt:202`: zone approach/enforcing or zone pause records);
  - `ZoneEvaluator.blocksPoint(zones, lat, lon, acc)` blocks the point (unreadable zones = blocked);
  - a pause interval covers the capture time;
  - the frame or the second is tombstoned;
  - the CH-style authority (`frame_forget_rev` + `MemoryWipe.generation`) changed between capture and insert.
- Offline only does not stop the local save (no call is made). It does still forbid any consumer that would send the location (section 6).
- **Setting:** Settings › Capture & privacy › "Save location with Moments", default OFF (Q2). Turning it OFF offers "Delete saved locations" (a `sideDeletes` entry, explicit).

**Readers.** In v18 the only readers are the `MomentLocationDao` reads and a Developer count line. `Moment` may later carry `location: MomentLocation? = null`, read outside `contentHash` like `SensorSummary` (`MomentResolver.kt:51`). An absent row is the normal case everywhere.

**Backfill import (adb-assisted, owner-started).**
- `backfill_gps.py` gains `--emit-import`, which writes `moment-loc-import.tsv` with the columns `momentId, atMs, anchorKind, anchorId, lat, lon, accuracyM(=15+1.4*|dt|), fixAgeMs, method`.
- The output folder stays outside git (the script already refuses a git tree) and is never committed.
- `adb push` it to the app-specific external dir (`/sdcard/Android/data/<pkg>/files/import/`).
- Developer › "Import Moment locations" (`MomentLocationImport`) validates every row in chunked guarded transactions:
  - the 8-hex id; finite coordinates;
  - the Moment exists now, and its anchor frame or mark still exists;
  - it is not tombstoned;
  - no row is present yet;
  - no zone or pause blocks it.
- It writes `source=MEDIA_BACKFILL`. It deletes the file on success AND on failure, and shows counts only: candidates, written, skipped by reason.
- A second run writes 0.

## 4. Work packages (Sonnet builds with TDD; Opus designs and arbitrates; Codex reviews once at medium effort, or Opus at low effort if the Codex quota is out, never Sonnet)
| WP | Owner | Contents | Hours | Depends |
|---|---|---|---|---|
| WP0 | Opus | Re-read every CH and this plan's line refs at the tip; freeze the DDL, both policy rows and the sideDeletes; one-page build spec for Sonnet | 1 | owner OK |
| WP1 | Sonnet | Both entities and DAOs, `MIGRATION_17_18`, places v3, `18.json` + `PlacesDatabase/3.json`, migration tests (section 5 G1) | 2.5 | WP0 |
| WP2 | Sonnet | Families `PLACE_HISTORY` + `MOMENT_LOCATION`, policy, inventories, the choke points + mark delete + WipeDao, golden tests (flag OFF and ON) | 3 | WP1 |
| WP3 | Sonnet | CH 5-8: `PlaceCardHistory` rewrite, authority plumbing, `savePins` order, removal list, r4 P1-5, Hub delivery | 3 | WP2 |
| WP4 | Sonnet | `MomentLocationRecorder` + wiring + setting + "Delete saved locations" + source-rule test | 2.5 | WP1 (parallel worktree to WP2/3; rebase onto WP2 before merge) |
| WP5 | Sonnet | `--emit-import` in `backfill_gps.py` (+ fixtures), `MomentLocationImport` + Developer action, tests | 2 | WP4 |
| WP6 | Sonnet | `tools/post-install-check.sh:833` expects 18 plus the new-table counts; installer README; coverage-map row; HANDOFF | 0.5 | WP1 |
| V | Sonnet | `verify_final` 15/15 (one Gradle job at a time, 8 GB Mac) | 1.5 | all |
| R | Codex | One review pass over the whole diff + one fix round (Sonnet) + re-check | 4 wall | V |
| E | Sonnet + Opus | Real-copy migration test + emulator rehearsal (G4, G5) | 2 | R |

About 15 h of build plus about 8 h of verification, review and rehearsal.

**Calendar (IST):**
- Wed 25: WP0 and WP1. WP2 and WP4 run overnight.
- Thu 26: WP3, WP5, WP6. V runs overnight.
- Fri 27: R and its fix round. **Stage the fallback build by Fri 18:00.**
- Sat 28: E.
- Sun 29 ~18:00: the owner's install, then the post-install checks, the import and a short walk.
- Mon 30 20:00: hard go/no-go. Mon is buffer.

## 5. Verification gates (each must be green before the next step)
- **G1 unit (Robolectric):**
  - `MigrationV17to18Test` (pattern `MigrationV16to17Test`): seed every v17 table from `17.json`, migrate, validate against `18.json`; counts unchanged; `integrity_check` ok; the body regex is exactly 2 CREATE TABLE + 4 CREATE INDEX.
  - A **negative case**: a broken migration throws and the file stays `user_version=17` with its rows intact.
  - `PlacesMigration2To3Test` + the 1 -> 3 chain.
  - Inventory, policy and conformance tests; `GoldenDeleteSetTest` with location rows (photo, Moment, Scene span, range, multi, fact-locked photo, mark delete, Delete all, expiry KEEP).
  - Recorder: off by default, zone fix never saved, stale or inaccurate fix skipped, tombstoned second refused, authority refused, a rebuild keeps the row, `MomentBuilder` output identical with and without a row.
  - Import: idempotent, never overwrites, gone/tombstoned/zone refused, file deleted.
  - `momentLocationNeverInExport`, `historyNeverInExport`, `LogContentScanTest` (no coordinates or ids in logs).
  - `MomentLocationSourceRulesTest`: the DAO and entity are referenced only from an allow-list, never from `services/ai`, prompt builders, `PayloadSources`, Ask sources, export or the canvas API.
  - `backfill_gps.py --fixtures`.
- **G2:** `verify_final` 15/15 rc=0, plus `EncryptedMigrationV17to18DeviceTest` on the emulator (the SQLCipher path, two reopens, wrong key fails).
- **G3 Codex READY** with no open P1.
- **G4 real-copy migration (host, unattended):**
  - Extract `context.db` (+ `-wal`/`-shm`) from the backup tar into a scratch dir outside git.
  - Record the counts of `frame_meta, frame, memory, moment, moment_part, scene, canvas_label, forget_tombstone, prompt`.
  - Run the production `MIGRATION_17_18` through Room on that copy.
  - Assert the counts are equal, `user_version=18`, `integrity_check` ok, both new tables empty, and the open time.
  - Delete the scratch dir.
- **G5 emulator rehearsal (`MyEmulator`; SDK at `~/Desktop/rokid/android-sdk`):**
  1. Install train20 (v17). With the app stopped, place the plain copy of `context.db`.
  2. Open the app, then `install -r` v18.
  3. `post-install-check` reports 18, with the counts equal.
  4. Photo Forget, Moment Forget and range Forget work; Delete all on a second copy.
  5. Reopen and reboot.
  6. The import writes about the dry-run count; a second run writes 0.
  7. Wipe the emulator data afterwards: real data must not linger.
- **G6 on the phone, after the owner's go:**
  - `install-trip-build.sh --go` takes a validated tar (TAR_OK, sha256) and the previous APK.
  - `adb install -r` only.
  - `post-install-check`: `user_version=18`, the counts equal the pre-install copy, the new tables have 0 rows, logcat is clean (`room|sqlcipher|migration|not a database`).
  - Then the setting is turned ON, the import runs (counts) and a walk follows: new Moments gain rows, and a Forget of one drops the count.

**How a failed migration is detected and rolled back:**
- Room runs `migrate()` and its schema validation inside the open's upgrade transaction. A throw or an identity mismatch therefore rolls the whole thing back and Room throws at the first open. This is proven by the G1 negative case and to be confirmed in G5.
- **Detection:** the app shows a DB error, logcat shows the Room exception, and `post-install-check` reads `user_version`.
- **17 = rolled back:** `install -r` the fallback APK. No data is lost.
- **18 but broken behaviour:** v17 cannot open v18, so follow `handoffs/v17-upgrade-recovery.md` section 4: quarantine, restore the validated tar (context.db + places.db), `install -r` the previous APK. Data since the install is lost, which is why the install is Sun evening, not earlier than G5.

## 6. Privacy rules applied
- **Pause zones and pauses:** the same predicates as Place Guide, fail closed. A zone fix is never saved, live or backfilled.
- **Offline only:** no call is involved. The source-rule test guarantees that nothing can send the location.
- **Forget:** every user Forget and Delete all deletes the rows in the same transaction, plus the tombstone sweep. The setting OFF offers deletion.
- **Export and backup:** not in `ExportDao`/`MemoryExport` (test). `context.db` is already excluded in `backup_rules.xml` and `data_extraction_rules.xml`.
- **Logs:** tokens and counts only.
- **Real GPS data:** stays in `~/Desktop/rokid/backups/` and in the app-specific dir, and is deleted after the import. Never committed.
- **Policy rows** (evidence re-read in WP0): MOMENT_LOCATION = user FORGET, deleteAll FORGET, expiry KEEP (DE_LINK only when the frame row itself goes), sweep KEEP, purge KEEP.

## 7. Risks and the fallback
1. **Scope.** This is the largest privacy-wiring change in the shortest window. Mitigation: the schema is frozen first and behaviour sits behind flags, so if WP3 is not READY by Fri 18:00 it is cut. History then stays OFF in places.db, the empty context.db table ships unused, and a later train needs no schema bump.
2. **Migration DDL drift** from `18.json`: covered by G1 and G2.
3. **No downgrade:** see section 5.
4. **The real DB is unusual** (590 MB, WAL, a repaired Moment set): G4 and G5 cover it.
5. **places.db is SQLCipher with a Keystore-bound key,** so it cannot be rehearsed on real data. Only synthetic and chain tests exist, and its migration is a single DROP IF EXISTS.
6. **`LAST_KNOWN` may be another app's stale or coarse fix:** the ±60 s and 100 m limits apply, and `source` is stored so readers can filter.
7. **The backfill accuracy at a 5 min gap is up to ~435 m** (Q6).
8. **Laptop:** 8 GB RAM and one Gradle job at a time. It slept on battery on 24 Nov, so keep it on power. 23 GB free now, enough for the tar extract and the emulator.
9. **Codex quota:** if it is out, Opus reviews at low effort.

**Fallback (decided now):**
- If any of G1-G5 is not green by **Mon 30 Nov 20:00**, the owner installs the fallback instead: the v17 tip (date-range filter + map key, no v18). It is built and verified 15/15 and staged by Fri 27 18:00.
- If v18 is installed and G6 fails, section 5's rollback applies.
- Nothing is installed after Mon 23:00.

## 8. What can run unattended overnight
- **Can run unattended:** the WP2/WP4 and WP3/WP5 Sonnet builds in their own worktrees (TDD, local commits only); `verify_final` (serialised); G4 on a host copy; `EncryptedMigrationV17to18DeviceTest` and the G5 steps on the emulator, if it boots headless; the dry-run re-run of `backfill_gps.py` on a fresh copy; Codex review.
- **Never unattended:** anything on the phone or glasses (backup, install, import, walk), the owner's rulings, the install go.

## 9. Open questions for the owner (recommended default in bold)
1. **Card history scope:** **full CH in v18, auto-cut to schema-only if not READY by Fri 18:00** / schema-only now / history to v19.
2. **"Save location with Moments" default:** **OFF in code, you turn it ON after the install** / ON by default.
3. **Live match rule:** **±60 s, ≤100 m, held fixes only (no new location request, no battery cost)** / also allow the one-shot request Place Guide uses.
4. **Photo retention:** **keep the location while the Moment's frame row or mark lives** / delete it when the photo file expires.
5. **Import route:** **`adb push` to the app-specific dir + Developer "Import Moment locations"** / a token-paired dev HTTP route like `/api/moments/repair`.
6. **Backfill window:** **5 min (128 of 1,328 in the pre-repair dry run; re-run on the post-repair DB, about 5k Moments), stored with its accuracy** / 2 min (91, tighter) / 10 min (191).

## 10. Not verified (be aware)
- **The backup is pre-repair.** `pca-phone-pre-repair-20261125-0130.tar` was taken BEFORE the 01:40 Moment repair, so it holds about 1.8k Moments, not 5k. The final G4/G5 copy and the dry run need the fresh tar of the install-time backup, or a new pull, which needs the owner and the device.
- **Not opened:** I did not open the tar or recount the DB. The 19k / 5k / 5k counts are as reported. `context.db` being plain SQLite comes from the replay harness doc (R2, "verified" on the 24 Nov dump). If it is SQLCipher now, G4 and G5 cannot open a host copy.
- **Assumed, not tested:** that PASSIVE last-known reflects fixes from the fused provider; the AVD's API level and whether it boots headless; and that every mark-delete and orphan-frame path is listed (WP0 re-derives them at the tip).
- **Still to prove:** Room's rollback on a failed validation is standard behaviour, but G1 and G5 must still show it.
