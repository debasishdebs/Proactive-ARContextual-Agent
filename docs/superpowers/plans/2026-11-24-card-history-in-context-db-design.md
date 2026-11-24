# Place card history in context.db (Room v18): design (2026-11-24)

Designer: Opus. Status: PROPOSAL, needs the user's rulings in section 11. No source changed by this document.
Inputs read: Codex place-fixes r1-r4, place-fixes build spec (B1-B3, section 7), `PlaceCardHistory.kt`, `PlacesDb.kt`, `PlaceGraph.kt`, `ForgetService.kt`, `MomentForget.kt`, `ScopedForget.kt`, `ForgetExecutor.kt`, `Families.kt`, `ForgetPolicy.kt`, `DeletionGraph.kt`, `WipeDao.kt`, `CanvasSlice3Dao.kt`, `MemoryWipe.kt`, `ForgetActions.kt`, `DataOpGate.kt`, `DatabaseFactory.kt`, `ContextDatabase.kt` (v17), the schema export dirs, `v17-upgrade-recovery.md`, `tools/INSTALL-TRIP-BUILD.md`, `tools/post-install-check.sh`, `backup_rules.xml` / `data_extraction_rules.xml`.
Line numbers are of `cdef4c7e` / `b3b487a2` (paths from `android-companion/services/` unless noted).

## 0. Decision in one paragraph

Move `place_card_log` into `context.db` as Room v18 (one additive CREATE TABLE plus two indexes, explicit `MIGRATION_17_18`, exported `18.json`, no destructive fallback). Make it a first-class Forget family, `PLACE_HISTORY`. Every Forget source then deletes or unlinks its rows INSIDE the transaction that already deletes the photo, the range or everything else. This removes the owed-token machinery behind every Codex P1 in r1-r4. Pin removal needs NO durable obligation of any kind: the history rows are deleted before the pin store is written, under an in-memory "removing" fence, and a failed delete refuses the pin removal. Insertion is guarded inside its own Room transaction by the durable `frame_forget_rev` fence that every Forget already moves. **Timing: technically acceptable, but NOT for the window before Sikkim.** Keep the history OFF through Sikkim (train14 = v17, history OFF, installed before the 26 Nov 00:50 retention deadline). Ship v18 as a post-Sikkim train, ideally in the SAME v18 migration as `moment_location` (build spec section 7). There is also a gated fast path if the user wants history in Sikkim (section 9).

## 1. Why the current design keeps failing review

`places.db` is a second SQLCipher database, so no Forget transaction can include it. Each deletion therefore became a post-commit listener (`PlaceGraph.kt:41-47`) that had to record a durable "owed" token (`PlaceCardHistory.kt:86-171`, prefs key `history_deletions_owed`). Every review round then found a new gap in that bookkeeping:
- r1 P1-4/5: swallowed failures, no obligation.
- r2 P1-1..4: wipe restart loop, DONE before the cascade, fence and arming order, unchecked `commit()`.
- r3 P1-1/2 and P2-3: DONE before the pin cascade, "Everything was deleted", an unbounded token set.
- r4 P1-1: unguarded `add()`. P1-2: the shared `scopeFailed` race. P1-3/P1-4: compaction drops survivors and non-covered tokens. P1-5: the MemoryWipe typed result.

Every one of these comes from the state "committed in context.db, still owed in places.db". With a single database, that state cannot exist.

The privacy surface does not change. `context.db` is SQLCipher under the same `db_key`, and it is excluded from backup and transfer exactly like `places.db` (`backup_rules.xml`: `exclude database context.db` + `-wal` / `-shm`, the same in both sections of `data_extraction_rules.xml`). It is not in any export (section 7, test `historyNeverInExport`).

## 2. Schema: entity, DAO, migration

### 2.1 Entity (moved, unchanged)
`PlaceCardLogEntity` moves from `memory/.../place/PlacesDb.kt:61` to a new file, `memory/.../db/PlaceCardLog.kt`. It keeps the same class name, table, columns and indexes:

```kotlin
@Entity(tableName = "place_card_log", indices = [Index("at"), Index("frameId")])
data class PlaceCardLogEntity(
    @PrimaryKey val id: String, val at: Long, val placeId: String, val title: String, val kind: String,
    val members: String?, val frameId: String?, val confPct: Int?, val cardId: String?, val delivery: String,
    @ColumnInfo(defaultValue = "0") val photoGone: Int = 0,
)
```

`ContextDatabase.kt:9` gets `entities += PlaceCardLogEntity::class`, `version = 18` (`:24`) and `abstract fun placeCardLogDao(): PlaceCardLogDao`.

### 2.2 DAO `PlaceCardLogDao` (in `memory/.../db/PlaceCardLog.kt`)
- **Reads:** `recentCardLog(limit)`, `cardLogSince(since)`, `cardLog(id)`, `cardLogByCard(cardId)`, `cardLogCount()`, `cardLogFrameIds()`. These move verbatim from `PlacesDb.kt:130-139`.
- **Delivery updates:** `setCardDelivery`, `setCardDeliveryIfPending`. These also move verbatim.
- **Insert:** `@Insert(onConflict = ABORT) put(row)`. It is only ever called inside the guarded transaction (section 6).
- **Deletes:**
  - `deleteCardLog(id)`
  - `deleteCardLogForFrames(frameIds)`
  - `deleteCardLogBetween(from, to)`
  - `deleteAllCardLog()`
  - `deleteCardLogBefore(cutoff)`
  - `trimCardLogTo(keep)`
  - NEW `deleteCardLogForPins(pinIds: List<String>)`: the `:146` predicate, applied per id inside one `@Transaction`.
- **Unlink:** `markCardLogPhotoGone(frameIds)` (`:142`).

### 2.3 Migration
Add it after `MIGRATION_16_17` (`ContextDatabase.kt:353`). The DDL is byte for byte the one already proven in `MIGRATION_PLACES_1_2` (`PlacesDb.kt:165-167`). It must still be diffed against the generated `18.json` before merge.

```kotlin
/** v17 -> v18: the phone-only place card history moves into context.db (one CREATE TABLE, two indexes; no row of any existing table is touched). */
val MIGRATION_17_18: Migration = object : Migration(17, 18) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `place_card_log` (`id` TEXT NOT NULL, `at` INTEGER NOT NULL, `placeId` TEXT NOT NULL, `title` TEXT NOT NULL, `kind` TEXT NOT NULL, `members` TEXT, `frameId` TEXT, `confPct` INTEGER, `cardId` TEXT, `delivery` TEXT NOT NULL, `photoGone` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_place_card_log_at` ON `place_card_log` (`at`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_place_card_log_frameId` ON `place_card_log` (`frameId`)")
    }
}
```

- Add it to BOTH lists in `DatabaseFactory.kt` (`:13` `build` and `:23` `buildDeferred`).
- No `fallbackToDestructiveMigration*` anywhere, and none on downgrade either.
- Export `services/memory/schemas/com.rokid.context.memory.db.ContextDatabase/18.json` and commit it.

### 2.4 places.db
`PlacesDatabase` (`PlacesDb.kt:155`) drops the entity, so its Room identity hash changes and it MUST move to version 3. Otherwise you get "Room cannot verify the data integrity" on every phone with a v2 file.
- `MIGRATION_PLACES_2_3` runs `DROP TABLE IF EXISTS place_card_log`. Its indexes go with it.
- `MIGRATION_PLACES_1_2` stays, so a v1 phone (18c1b76a has places.db v1) chains 1 -> 2 -> 3.
- Export `PlacesDatabase/3.json`. No destructive fallback.
- The `cardLogDao` accessor leaves `PlacePack.kt:98`.
- Alternative if the user prefers no places.db change: keep the dead entity in places.db v2 forever. This is not recommended (section 11, R5).

## 3. The family, the policy row and the inventories

- **`Ref.kt:12`:** `Family.PLACE_HISTORY("placehistory")`.
- **`ForgetPolicy.kt:24`:** `ForgetChild.PLACE_HISTORY(Family.PLACE_HISTORY)`.
- **`Families.kt`:** a new `PlaceHistoryFamily` object, added to `Families.ALL`. It has:
  - `tables = listOf("place_card_log")` and `selfDeletes = true`.
  - `selfDelete(ids, ERASE)`: deletes rows by row id. That is the user's "Delete from history", reached through the executor only if ever planned. Any other fate throws.
  - `eraseFrames(db, frameIds)`: chunked 500, `deleteCardLogForFrames`.
  - `unlinkExpired(db, frameIds)`: chunked, `markCardLogPhotoGone`.
  - It has no `dependents` (a leaf). There is no planner edge from PHOTO in this step: the history is not shown in a Forget preview or confirmation hash. Add one entry to `DesignDivergence.kt` saying that the planner does not enumerate it and that the step code deletes it by frame and time.
- **`PlacePackFamily.tables`** (`Families.kt:334`) loses `place_card_log`.
- **`DeletionGraph.TABLES`:** the `place_card_log` entry (`DeletionGraph.kt:92`) moves out of the places.db group into the context.db group. Its fate changes from `SCRUBBED` to `WIPED`, with this why: "phone-only place card history; user Forget of the photo, any user range (also a photo's radius) and Delete all delete its rows in the same transaction; photo retention sets photoGone and keeps frameId; pin removal deletes the pin's rows before the pin store is written; 90 days / 500 rows".

The policy row goes in `ForgetPolicy.rows` after `PLACE_CARD`. Every cell is CODE evidence, re-read at merge:

| Source | Action | Evidence (the code that does it) |
|---|---|---|
| user (single, multi, range, scope) | FORGET | `ForgetService.dropCanvasOutputs` (FS:285) and `ForgetExecutor.dropCanvasOutputs` (FE:261): rows of the forgotten photos. `CanvasSlice3Dao.forgetRangeTx` (:85): rows with `at` in every user span (range Forget, scoped spans, the photo radius, the canvas range). |
| deleteAll | FORGET | `WipeDao.wipeMemory`: `DELETE FROM place_card_log` |
| expiry | DE_LINK | FS:155 `expireFrameRows`, FE:167 `expirePhotos`, FE:187 `expireOrphanFrames`: `photoGone = 1`, the `frameId` is kept (private lineage: a later user Forget of the photo still deletes the row) |
| sweep | KEEP | FE:160 / FS:127 `removeDescriptions`: `lockFrames = false`, so `dropCanvasOutputs` is never reached |
| purge | KEEP | IMP:69 `forgetFacts` of orphan memories: no frame row and no capture time, so no frame and no span reach the step |

`ForgetPolicy.sideDeletes` gets three entries. `ForgetPolicyTest.everySideDeleteCallerIsListedAndEveryListedCallerExists` scans for these call tokens:
- `PLACE_HISTORY_PIN_REMOVAL`: explicit = true, `.deleteCardLogForPins(`, caller `PlaceCardHistory.kt`. Cell: PLACE_HISTORY F.
- `PLACE_HISTORY_ROW_DELETE`: explicit = true, `.deleteCardLog(`, caller `PlaceCardHistory.kt`. Cell: F (the B2 "Delete from history").
- `PLACE_HISTORY_PRUNE`: explicit = false, `.deleteCardLogBefore(` and `.trimCardLogTo(`, caller `PlaceCardHistory.kt`. Cell: F (90 d / 500 rows).

The existing `PIN` row is unchanged. A pin that a Forget deletes (PML cascade) goes through `PlaceGraph.savePins`, which runs `PLACE_HISTORY_PIN_REMOVAL`.

## 4. Forget-side deletions, per path (the exact lines)

Both the legacy path (`forgetV2` OFF, the production default: `ForgetShadow.kt:66`) and the executor path (`forgetV2` ON) are wired. They share the same three choke points. Six call sites carry the same statements.

| # | Path | Where | What is added, in the same transaction |
|---|---|---|---|
| 1 | User photo Forget, legacy (single, scoped PHOTO, multi-select, the photos of a range, a fact Forget's locked photos, the "expired photo with nothing left" branch) | `ForgetService.dropCanvasOutputs` FS:285-299, right after the `pendingFrames.add` at :296 | `if (!expired) PlaceHistoryFamily.eraseFrames(db, frameIds)` |
| 2 | Same, executor | `ForgetExecutor.dropCanvasOutputs` FE:261-273, after :269 | `if (!expired) PlaceHistoryFamily.eraseFrames(c.db, frameIds)` |
| 3 | Every user time span: range Forget (MomentForget legacy :269, executor FE:309 via `TraceFamily.eraseRange` FAM:208), scoped MOMENT / SCENE spans, the photo radius (FS:292, FE:265), the canvas range (`CanvasSlice3Forget.forgetRange` -> `MomentForget.forgetRange`) | `CanvasSlice3Dao.forgetRangeTx` :85-95 (already `@Transaction`, nested in the caller's) | a new `@Query("DELETE FROM place_card_log WHERE at BETWEEN :from AND :to") deletePlaceHistoryBetween` called after `deleteLabelsBetween` |
| 4 | Photo expiry, legacy | `ForgetService.expireFrameRows` FS:155-174, after the loop (:168) | `PlaceHistoryFamily.unlinkExpired(db, expiredIds)` |
| 5 | Photo expiry, executor | `ForgetExecutor.expirePhotos` FE:167-184 (after :179) and `expireOrphanFrames` FE:187-202 (after :198) | `PlaceHistoryFamily.unlinkExpired(c.db, expiredIds)` |
| 6 | Delete all | `WipeDao.wipeMemory` :80-90 (`@Transaction`) | `@Query("DELETE FROM place_card_log") placeHistory()`, called with the memory tables |

Notes:
- **Why `forgetRangeTx` is safe as a choke point:** today it has no non-user caller. Its callers are listed in row 3, and retention never emits spans: no caller passes `expired = true` to `dropCanvasOutputs`, and `expireFrameRows` runs no radius. Add a KDoc line to it: "a user Forget only: deletes place history in the span". The policy row cites it, so any new caller shows up in review.
- **Range boundaries:** `MomentForget` rounds outward to whole seconds, so the new delete set is the old listener set or wider by less than 1 s at each end. This matches every other range child. It is recorded as an intended divergence in the golden test, not hidden (R6).
- **Delete all:** this now deletes every row present at the wipe commit. The old `a:<clock>` token deleted rows `at <= clock`. A row inserted after the commit is one written under the new authority (section 6), so the two are equivalent.
- **Pin removal:** see section 5. It is not a Room-family Forget, because pins live in SharedPreferences.

## 5. Pin removal without any obligation

Pins live in `place_pins` prefs, and every write goes through `PlaceGraph.savePins` (`PlaceGraph.kt:142`; PinMomentLinks `:81, :212, :265, :278` all call it).

The new order inside `savePins(context, list)`:
1. `before = pins().ids` and `gone = before - list.ids`. If `gone` is empty, behave as today.
2. `PlaceGraph.removing.addAll(gone)`. This is a process-wide in-memory set, ids only, never logged. Then `fence.trip(); port.clear()`, as today.
3. **Delete first:** run a Room transaction with `placeCardLogDao().deleteCardLogForPins(gone)`.
   - On failure: `removing.removeAll(gone)` and return `false`. The pin is NOT removed, and the UI says "Could not remove the pin, try again".
   - The Forget pin cascade (`PinMomentLinks.explicitStore`, with `onNotRemoved` at PML:101) already keeps its own durable arming and retries the pin at every start. That retry now also retries the history delete. No new store is needed.
4. Write the prefs (`pins.write(list)`).
   - On failure: `removing.removeAll(gone)` and return `false`. The pin stays, but its history rows are already gone. This is fail closed: we lose some history, we never leak a row. Log `place history pin removed=<n> pin_kept`.
5. Clear `removing.removeAll(gone)`. From here `pinActive` is false. Then `replaceUserPlaces(list)` runs as today.

**Why nothing can be orphaned:**
- An insertion checks `id !in removing && pinActive(id)` inside its own Room transaction (section 6), and SQLite serialises it against the delete in step 3.
- An insertion that committed before step 3 is deleted by step 3.
- One that begins after step 2 is refused.
- If the process dies between steps 3 and 4, the pin is still in prefs, so the state is consistent and the pin's later rows are legitimate.
- If it dies after step 4, nothing is pending.

**Lazy deletion at read time is rejected:**
1. Pin ids are reused. The settings export keeps pin ids (`UserPlace.kt:66, :206`), so re-importing an older export would bring hidden rows of a removed pin back.
2. A corrupt or unreadable pin store (`UserPinStore.corrupt`) would make deletion decisions from a bad read.

No read-time filter is added either, because the invariant above makes it unreachable. A test asserts the invariant instead.

## 6. Insertion: one guarded transaction (fixes r4 P1-1 by construction)

`PlaceHistoryEvent` gains a NON-null `authority: HistoryAuthority(rev: Long, generation: Long, fenceEpoch: Long)`.
- It is captured at the START of an attempt, before the frame, fix or pins are read: `rev = canvasSlice3Dao().frameForgetRev()` (`CanvasSlice3Dao.kt:490`), `generation = MemoryWipe.generation.get()`, `fenceEpoch` = the sampler's current fence epoch.
- An unguarded `add` no longer compiles. The callers are `PlaceSamplers.kt:117` and `:132` (sampler and text match), the S6 POSSIBLE row, and show-again (`AndroidPlaceGuideControl`, which captures at the start of the replay).

`PlaceCardHistory.add(e)` becomes `db.withTransaction { ... }` on `context.db` and refuses (log `place history add refused reason=<rev|gen|fence|pin|frame|off>`) unless all of these hold:
1. `frameForgetRev() == e.authority.rev`. This durable counter is bumped in the transaction of every photo Forget (`deleteSliceOutputsForFrames` :727), every range (`forgetRangeTx` -> `moveFrameForgetFence` :87) and Delete all (`WipeDao` `slice3BumpForgetRev`). Photo expiry does not move it, which is correct: expiry keeps rows.
2. `generation` and `fenceEpoch` are unchanged (today's in-memory guard).
3. If `frameId != null`: there is no `forget_tombstone` for it unless the expiry marker (`ExpiryMarks`) exists. In that case the row is written with `photoGone = 1`.
4. Every user-pin id in `placeId + members` passes `!in removing && pinActive`.
5. The flag `placeCardHistory` is on.

Then `put(row)` runs, and prune (`deleteCardLogBefore` + `trimCardLogTo`, at most once a day) runs in the same transaction.

**Serialisation** comes from SQLite, not from a Kotlin mutex:
- Room runs every `withTransaction` on its serial transaction executor and takes the write lock (WAL: `BEGIN IMMEDIATE`).
- A Forget transaction and an insert transaction can never interleave. Either the insert commits first (the Forget then deletes the row) or the Forget commits first (the insert then sees a new `rev` and refuses).
- The `DataOpGate` is unchanged. A Forget still takes `DataOpGate.shared.delete`. The insert does NOT take the gate, because it neither needs to wait for a Forget nor should it delay one; correctness comes from the database.
- The `writeLock` mutex (`PlaceCardHistory.kt:51`) is removed.

**Hub delivery (B3):** `markDelivery` and `markIfPending` become single Room UPDATEs on `context.db`.
- They are failure-swallowed, as today.
- An UPDATE of a deleted row affects 0 rows and can never resurrect it.
- The bounded early-state map (`:191-193`) stays in memory and holds ids only. It is applied only by a guarded `add`.

**Quota eviction (K6):** `photoIds()` reads `context.db`. It still throws when the read fails, and the caller then evicts nothing.

## 7. What happens to existing data

- **Real phones:**
  - The phone runs 18c1b76a: context.db v17, places.db v1, no history table.
  - Train13 (places.db v2, history ON) was never installed.
  - Train14 ships history OFF, so `add()` returns at `enabled()`: zero rows in places.db. But its listener still arms owed tokens on every Forget, because `historyListener` -> `oweAll` is not gated by the flag. So train14 can leave a `history_deletions_owed` key in `place_guide` prefs.
- **The v18 build:**
  1. `MIGRATION_PLACES_2_3` drops the empty table (or 1 -> 2 -> 3 on a v1 file).
  2. At startup (`ForgetActions` startup, replacing the `:369` settle) it removes the `history_deletions_owed` key once. That is a single `edit().remove(KEY).commit()`, and the startup line goes after Sri Lanka.
  3. Rows are not copied across databases. Any row on an emulator or test device that ran train13 is dropped, which is privacy safe (history lost, nothing leaked).
- **Export:** the new table is not added to `ExportDao` or `MemoryExport`. The `historyNeverInExport` test moves to the `context.db` export path.
- **Backup:** no change needed (`context.db` is already excluded).

## 8. Removal list (the owed machinery)

- **`PlaceCardHistory.kt`:**
  - The `writeLock` mutex.
  - `oweAll`, `arm`, `compactAfterArming`, `sameKindCovered`, `compactStored`, `armPinRemoval`, `exec`, `hasOwed`, `retryOwed`, `beginScope`, `scopePending`, `scopeFailed`.
  - `range`, `coversByTime`, `covers`, `OP_*`, `ARM_TRIES`.
  - `onFramesForgotten`, `onPhotosExpired`, `onRangeForgotten`, `clearAll`, `onPinRemoved`.
  - The classes `HistoryDeletionPending`, `HistoryOwed`, `InMemoryHistoryOwed` and `PrefsHistoryOwed`.
  - What remains is `add` (guarded), `markDelivery`, `markIfPending`, `recent`, `get`, `delete`, `seedRecent`, `prune`, `photoIds`, `deletePins` and `changes`, about 120 lines.
- **`PlaceGraph.kt`:**
  - `historyListener` (:41-47) and its registration (:54). `cardListener` stays.
  - `historyOwed`, `settleHistory`, `beginHistoryScope`, `historyScopePending`, `pinsRemoved`, `owedPrefs`.
  - The arming in `afterPinWrite`, which is replaced by the section 5 order.
  - The places.db existence logic in `existingHistory` and `historyIfOpen`. `context.db` always exists; Home still reads without creating anything.
  - The history half of `deleteAll` (:183-187).
  - `attach` / `appContext`, if nothing else uses them.
- **`ForgetActions.kt`:**
  - `settleHistory` at :213 and :369.
  - The history half of `cleanupFailed` (:222-227). Only the export-cache rule remains.
  - `beginHistoryScope()` at :252 and :304.
  - The "recheck after explicit" for history at :304-306.
- **`MemoryWipe.kt`:**
  - `beginHistoryScope` at :122.
  - The `HistoryDeletionPending` catch at :197-201.
  - The history `cleanupFailed` call at :205-206.
- **r4 P1-5 is fixed here, since this file changes anyway.** `WipeResult` reports cleanup pending as `leftovers > 0 || pinSettle(context, pinOp) == null`, and `MemorySettings.kt:135` never says "Everything was deleted" in that case.
- **`PlacesDb.kt`:** the entity and DAO lines :61, :128-148, the v2 entity list and `cardLogDao`. `MIGRATION_PLACES_1_2` is kept for the chain (section 2.4).
- **Tests:**
  - The token, compaction and scope cases of `PlaceCardHistoryTest`.
  - `PlaceHistoryForgetOutcomeTest`, rewritten to "history can never make a Forget or Delete all pending".
  - The history-pending parts of `MemoryWipeTest`.
  - `PlacesMigration1To2Test`, which stays as part of the 1 -> 2 -> 3 chain test.
- **Docs:** one row in `2026-11-20-forget-coverage-map.md`. B1's "Why places.db" paragraph in the build spec is marked superseded by this document.

## 9. Timing: v18 now, or the history OFF until after Sikkim

**Facts:**
- Train14 (v17, history OFF) must be on the phone before Thu 26 Nov 00:50 for the retention fix. That install is independent of this design and must not wait for it.
- Departure is Tue 1 Dec 05:00 for Sikkim/Darjeeling (1-6 Dec). Sri Lanka is 23-31 Dec.
- `versionCode` is fixed at 4 (`phone/build.gradle:48`), so the installer's downgrade guard never triggers. The real downgrade barrier is Room: train14 or 18c1b76a opening a v18 `context.db` throws "A migration from 18 to 17 was required but not found". There is no fallback, by rule.
- Rollback after v18 is therefore restore-from-backup only, following `v17-upgrade-recovery.md` section 4: preserve the broken state, quarantine, restore the validated `pca-phone-pre-<tag>.tar`, then `install -r` the previous APK. Everything captured between the install and the restore is lost.
- The same applies to places.db v3: the tar restores `databases/` whole.

**Assessment:**
- The migration is the lowest-risk class: a CREATE TABLE on a new name, like v17's single ALTER that passed.
- The risk is not the migration. It is a second schema-bumping install within about 3 days of train14, carrying privacy-critical Forget wiring that needs a READY Codex verdict. The only rollback costs data, and none is possible from Sikkim without the laptop.
- The value of looking back at place cards during Sikkim is modest. The glasses cards themselves work without history.

**Recommendation:** keep the history OFF through Sikkim. Build v18 after it, install between about 8 and 18 Dec, before Sri Lanka, and bundle `moment_location` (build spec section 7) into the SAME `MIGRATION_17_18` so there is one schema bump before Sri Lanka, not two (R2).

**Fast path, only if the user rules for history in Sikkim.** All of these gates must hold, otherwise it slips to after Sikkim:
1. Codex (medium effort; Opus at low effort if the Codex quota is out) gives READY by Sat 28 Nov 18:00.
2. The 15/15 verification passes.
3. The emulator rehearses v17 -> v18 over a dump of the real phone databases: the history works, Forget works, Delete all works, and the reopen and reboot checks pass.
4. `tools/post-install-check.sh:800` expects `user_version=18`, with its fixture updated.
5. The install happens no later than Sun 29 Nov evening, with a fresh validated tar taken by `install-trip-build.sh --go`.
6. The user accepts that any rollback after that loses the data captured since the install.

## 10. Tests, effort, order and risks

**Tests (memory module unless noted):**
- **`MigrationV17to18Test`** (Robolectric `MigrationTestHelper`, pattern `MigrationV16to17Test.kt`):
  - Create v17 from `17.json`, seed every table, run `MIGRATION_17_18`.
  - Assert the table, its columns and both indexes against `18.json`, all row counts unchanged, `integrity_check` ok.
  - Assert the migration body is exactly one CREATE TABLE and two CREATE INDEX (the regex check at `:73`).
- **`EncryptedMigrationV17to18DeviceTest`** (androidTest, emulator; pattern `EncryptedMigrationV16to17DeviceTest`): a real SQLCipher v17 file, migrate, two reopens through the production factory, never plaintext, a wrong key fails.
- **`PlacesMigration2To3Test`** and the 1 -> 3 chain: pins, cells and areas survive, the table is gone.
- **`FamilyInventoryTest`:**
  - `place_card_log` is owned by `PLACE_HISTORY` in `context.db`.
  - The places.db tables are `{place, cell, area}` (update `:25`, `:62`, `:66`).
  - `DeletionGraphInventoryTest`: the entry is WIPED.
- **`ForgetPolicyTest` / `ForgetPolicyConformanceTest`:** the new row, three side deletes and the caller scan.
- **`GoldenDeleteSetTest`**, run with `forgetV2` OFF and ON:
  - Rows deleted for: a photo Forget, the photo's radius, a range, a scoped MOMENT/SCENE span, a multi-select and a fact Forget's locked photo.
  - Expiry sets `photoGone = 1` and keeps `frameId`. A later user Forget of the expired photo deletes the row.
  - `removeDescriptions` and the purge keep the rows.
  - Delete all clears them.
  - The intended divergence: rounded range boundaries.
- **`WipeDaoTest`:** the table is empty after `wipeMemory`.
- **Concurrency** (orchestration, real in-memory Room, `Dispatchers.IO`, `HistoryInsertVsForgetTest`):
  1. Authority captured, then the photo Forget commits, then the insert is refused (rev).
  2. The insert commits, then the Forget deletes the row.
  3. Delete all between capture and insert: refused.
  4. A range Forget: refused.
  5. Pin removal:
     - An insert before the removal is deleted.
     - During the removal it is refused (`removing`).
     - A prefs write failure leaves the pin, its rows are gone and `savePins` returns false.
     - A Room delete failure leaves the pin and its rows, returns false, and leaves the PML arming intact.
  6. 200 random interleavings of inserts against Forgets running under `DataOpGate.shared.delete`. Invariant: no row with a user-tombstoned frame, no row of a removed pin, no row with `at` inside a committed user range.
- **`HubPlaceDeliveryTest`** (B3, against `context.db`):
  - SENT, NOT_SENT_LINK, NOT_SENT_HOST and NOT_SENT_ZONE are set.
  - An early state is applied only by a guarded add.
  - `markIfPending` never overwrites SENT.
  - An update after a Forget affects 0 rows.
- **Other:** `historyNeverInExport` (moved), `BackupRulesTest` (unchanged, passes), `LogContentScanTest` (no id or title in the new log lines), `PlaceCardHistoryTest` (add with prune at 90 d / 500 rows; refused reasons), and the one-time `history_deletions_owed` key removal.

**Effort** (Sonnet implements, one worktree, sequential, since the same modules overlap):
| Work package | Contents | Hours |
|---|---|---|
| WP1 | Schema, DAO, migrations, schema export, migration tests | 2 |
| WP2 | Family, policy, inventories, the 6 Forget sites, WipeDao, golden tests | 2.5 |
| WP3 | `PlaceCardHistory` rewrite, authority plumbing at the 4 callers, `savePins` order, removal list, r4 P1-5, Hub, tests | 3 |
| WP4 | `post-install-check` 18, installer README note, coverage-map row | 0.5 |

Then the 15/15 verification (about 1.5 h on the 8 GB Mac, one Gradle job at a time), the emulator rehearsal (1 h), and one Codex review pass plus one fix round. **Total: about 1 working day of build plus about 1 day of review, so ready about 26-27 Nov at the earliest.**

**Order:**
1. Train14 v17 is installed first, independently.
2. WP1 -> WP2 -> WP3 -> WP4.
3. Full verification.
4. Codex single review pass.
5. Emulator v17 -> v18 rehearsal over the real dump.
6. Stage train15 (README, SHA256SUMS).
7. The user's install go.

**Risks:**
1. The migration DDL differs from Room's generated schema, so the open fails. Mitigation: the DDL is copied from `18.json`, the `MigrationTestHelper` validates it, and the encrypted device test runs.
2. There is no downgrade: rollback is a tar restore that loses the data since the install (section 9).
3. A missed v1 or v2 site would leave rows behind. Mitigation: the golden tests run with the flag both OFF and ON, plus the policy evidence.
4. Fail-closed `rev` refusals: a card in flight while any Forget commits gets no history row (cosmetic; logged).
5. A future non-user caller of `forgetRangeTx` would delete history. Mitigation: the KDoc and the policy row.
6. The places.db v3 migration fails, so Place Guide does not open. Mitigation: the chain test and the emulator rehearsal.
7. `post-install-check` still expects 17 and gives a false FAIL. Mitigation: WP4.
8. `moment_location` becomes a separate v19 if it is not bundled now (R2).

## 11. What the user must rule

- **R1. Timing.** (a) History OFF through Sikkim; v18 after Sikkim, before Sri Lanka (RECOMMENDED). Or (b) the fast path with the six gates of section 9, accepting that a rollback loses data since the install.
- **R2. Bundling.** Put `moment_location` (build spec section 7) into the same v18 migration (recommended with R1 a). Or keep v18 for the history only and make `moment_location` v19.
- **R3. `frame_forget_rev` refusal.** A history row is skipped for a card that was in flight while any Forget committed (fail closed). Accept?
- **R4. Pin removal order.** The history is deleted BEFORE the pin write.
  - If the pin write then fails, the pin stays without its history.
  - If the history delete fails, the pin removal is refused with "try again".
  - No owed obligation is kept. Accept?
- **R5. places.db.** Drop the empty table with places.db v3 (recommended). Or keep a dead v2 table forever.
- **R6. Range boundaries.** History rows follow every other range child: rounded outward to whole seconds, which is up to 1 s wider than the listener today. Accept?
- **R7. The install go** for whichever train results. As always, it is never assumed.
