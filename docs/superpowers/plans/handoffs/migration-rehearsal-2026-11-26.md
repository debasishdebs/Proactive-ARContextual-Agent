# Migration rehearsal on a real DB copy (2026-11-26)

Source: the read-only phone pull `pca-phone-pull-20261126-2123.tar` (never modified). Only `databases/*` and `shared_prefs/*` were extracted, into a 0700 folder outside git. Counts and timings only: no row contents, coordinates or names.

## 1. File check

| File | Format | Rehearsable on the host |
|---|---|---|
| context.db | plain SQLite ("SQLite format 3" header, `sqlite3 -readonly` works), WAL mode, `user_version` 17, 4096-byte pages, 31488 pages (about 129 MB) | yes |
| places.db | NOT plain SQLite: the first 16 bytes are random (SQLCipher, Keystore-bound key) | NO. Cannot be opened off the phone |

So the places.db v2 -> v3 part was NOT rehearsed. Its migration is one statement (`DROP TABLE IF EXISTS place_card_log`, per the v18 build spec); it is covered only by synthetic and chain tests. Which means no evidence exists on the real places.db, including its `place_card_log` row count.

## 2. context.db before (copy, WAL replayed)

`user_version=17`, `integrity_check=ok`, 58 tables, 101 index entries.

Rows: action_log 11, ask 111, canvas_label 9, canvas_meta 9, canvas_pipeline 11, canvas_pipeline_version 1, canvas_replay 5, canvas_trace 6, cleanup_outbox 35, embedding_space 1, endpoint 2, forget_tombstone 10284, frame 5633, frame_cluster 378, frame_meta 16020, frame_source 28224, frame_state 16029, frame_vector 9142, memory 20512, memory_fts 20512, memory_vector 20512, moment 5622, moment_part 17410, moment_tombstone 7, nudge 140, object_audit 18710, pause_interval 39, pause_record 1, prompt 10, prompt_version 14, proposal 6, role_binding 7, scene 1759, scene_part 5622, summary_trace 2337, room_master_table 1. All other tables are 0 rows (canvas_* run/score/sweep tables, memory_source, moment_sensor, moment_trace_dep, note, object_derived, shopping_item). FTS shadow tables follow `memory_fts`. `place_card_log` and `moment_location` do not exist at v17.

## 3. Real migration run

Test: `MigrationV17to18RealCopyRehearsalTest` (memory module, test tree only, Robolectric). Skips unless `REHEARSAL_DB_DIR` is set, refuses a path inside a git tree, refuses a non-plain file, copies the files to a scratch sub-folder and migrates the copy through Room with the full 1..17 -> 18 chain (`MIGRATION_17_18` last) plus the app's `openCheck` callback.

Results (all asserted):
- first open (migration): **149 ms** wall on this 129 MB DB (the host JVM; a phone will be slower, expect low seconds).
- `user_version` 18, `integrity_check` ok.
- All 58 pre-existing tables: identical row counts.
- New tables `place_card_log` and `moment_location`: present, 0 rows. The migration copies nothing from places.db; it only creates the two tables and four indexes (`index_place_card_log_at`, `index_place_card_log_frameId`, `index_moment_location_atMs`, `index_moment_location_anchorId`). Index entries 101 -> 107 (4 named plus 2 automatic primary-key indexes).
- Second open: 4 ms, no change (no-op), still 18 and ok.

Not covered by this run: the SQLCipher path (the phone's context.db may be encrypted at runtime by the app; this copy was plain), `EncryptedMigrationV17to18DeviceTest`, and the places.db migration.

## 4. Rollback

A v18 file cannot be opened by the v17 app and there is no downgrade migration. Rollback is: quarantine the v18 files, restore the validated backup tar's context.db and places.db, `install -r` the previous APK (see `handoffs/v17-upgrade-recovery.md` section 4); data written after the install is lost. A migration that fails half way leaves the file at v17 with its rows (`MigrationV17to18Test.brokenMigrationRollsBackToV17`). Not re-run here.

## 5. Verdict

**GO for context.db v17 -> v18 on the evidence of this copy**: schema and counts are preserved on the owner's real data, quickly, and idempotently.

**NOT proven (do not read this as a full GO)**: places.db v2 -> v3 (encrypted, no real-data evidence; low risk, one DROP IF EXISTS) and the encrypted context.db path. Remaining before Sunday:
1. Emulator rehearsal: `EncryptedMigrationV17to18DeviceTest` and the places v2 -> v3 chain.
2. Installer dry run (`tools/install-trip-build.sh` with a fresh backup tar, `post-install-check.sh` expecting 18 and 0/0 for the new tables).
3. Take a fresh validated backup immediately before the install (this one is from 25 Nov).
