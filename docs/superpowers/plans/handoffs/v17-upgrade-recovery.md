# v16 to v17 upgrade: backup, verify, recover

Applies to the phone app `com.rokid.context` when the build that carries `MIGRATION_16_17` (one additive column, `ask.sourceStates TEXT DEFAULT NULL`) is installed over a v16 database. Proxy ruling V1 (2026-11-17): conditional YES, backup alone is not enough. Installing still needs the user's explicit go.

## Hard rules (every step below keeps them)

- Never uninstall the app. Never "Clear data" / `pm clear`. Never rotate or delete the Keystore-backed database key. Never use a destructive migration fallback (the app has none; do not add one).
- Install only with `adb install -r` of an APK signed with the SAME signature as the installed one (the Keystore master key and `db_key` preferences belong to that app and signature; a different signature = a different identity = the database becomes unreadable).
- v16 binaries cannot open a v17 database (Room refuses a downgrade). So "install the old APK again" is NOT a rollback once the new build has opened the database. The rollback is restore-from-backup, then reinstall.

## The key (read this once)

The database is SQLCipher-encrypted. Its 32-byte passphrase is created once by `DbKeyStore` and kept in `EncryptedSharedPreferences` (`db_key.xml`, AES256-GCM, wrapped by an Android Keystore master key). The backup tar contains the encrypted database file and `db_key.xml`, but the Keystore master key never leaves the device. So a backup restores only onto the SAME installation (same device, same app signature, Keystore intact). It is a protection against a failed migration or a bad file, not against uninstalling or wiping the phone.

## 1. Backup (before installing)

The app must NOT be running while it is archived: a live SQLCipher database in WAL mode changes under `tar` (a checkpoint or a write between two reads gives a file that passes a size check and is still inconsistent). So:

1. Not during a call. Stop the app (a force-stop is allowed: it is not an uninstall and keeps all data): `adb shell am force-stop com.rokid.context`. Confirm it is down: `adb shell pidof com.rokid.context` prints nothing. Do not open the app again until the backup is validated (a widget, an alarm or a sync can restart it: if `pidof` prints a pid, force-stop again and re-take the backup).
2. Take the snapshot with the app stopped. The tar must include the `-wal` and `-shm` files next to `databases/context.db` (they hold committed pages that are not in the main file yet; `tar c .` includes them):
   ```
   mkdir -p ~/Desktop/rokid/backups
   adb exec-out run-as com.rokid.context tar c . > ~/Desktop/rokid/backups/pca-phone-pre-<tag>.tar
   ```
   Use a real tag, e.g. `pre-v17-20261121`. Needs the debuggable build currently on the phone (`run-as`); if `run-as` is refused, stop and tell the user: do not install.
3. Validate the snapshot BEFORE relying on it (any failure = take it again with the app stopped; do not install):
   - `tar tf ~/Desktop/rokid/backups/pca-phone-pre-<tag>.tar > /dev/null && echo TAR_OK` (the archive reads to its end; a truncated stream fails).
   - `tar tvf ~/Desktop/rokid/backups/pca-phone-pre-<tag>.tar | grep -E 'databases/context\.db|shared_prefs/db_key\.xml'` lists both, and shows any `context.db-wal` / `context.db-shm` that existed (a non-empty `-wal` is normal after a stop).
   - Sizes: `ls -l` of the tar is the same order as the live `adb shell run-as com.rokid.context ls -l databases/` (main file + -wal + -shm) plus `files/`; a tar of a few KB is a failed snapshot.
   - Header readable: extract `databases/context.db` into a temp dir (`tar xf ... -C <tmp> ./databases/context.db`) and check `head -c 16 <tmp>/databases/context.db | xxd` is not the plain text `SQLite format 3` (the file is encrypted; 16 bytes of random-looking data = intact and still sealed) and its length is a multiple of the page size (4096, `stat -f%z`). A plaintext header or a zero-length file means a bad snapshot or an unencrypted install: stop and ask.
   - Record the tar's `shasum -a 256` next to the notes.
4. Also copy the original photos folder the same way if it is outside `run-as .` (it is under `files/`, so it is included).
5. Record: `adb shell dumpsys package com.rokid.context | grep -E 'versionName|versionCode|signatures|Signing'` into the tag's notes (the APK you will reinstall on rollback must match).
6. Keep the previous APK file next to the backup.

## 2. Install

`adb install -r <new.apk>` only (never `uninstall`, never `-r` after `pm clear`). Open the app once on the phone and wait for the first screen. The migration runs at first database open (it is a single `ALTER TABLE`; no row is rewritten).

## 3. Verify after install (all must hold; otherwise go to section 4)

1. The app opens to Home with the existing asks, memories and photos visible (Activity list counts match what you saw before).
2. Developer / Diagnostics shows no database error, and the log has no `SQLiteException`, `file is not a database`, `Migration didn't properly handle`, `IllegalStateException` from Room: `adb logcat -d | grep -iE 'room|sqlcipher|migration|not a database'`.
3. Ask one test question: it answers and the new ask appears (its `sourceStates` is filled; older asks keep NULL and show as before).
4. Search a word you know is in an old memory: it is found.
5. Force-stop and open the app again, then reboot the phone once and open it again: still opens (reopen with the Keystore key works).
6. Optional, from the backup on the laptop (read-only): nothing to do with the phone; keep the tar until the trip is over.

Pre-flight evidence already collected: `EncryptedMigrationV16to17DeviceTest` (androidTest, emulator only) creates a real SQLCipher v16 file from the exported `16.json`, seeds every table, runs `MIGRATION_16_17`, and asserts row counts and content, FTS search, `ask.sourceStates` NULL on old rows, `PRAGMA integrity_check`, two reopens through Room with the production factory, the file never plaintext, and that a wrong key cannot open it. See its result in the commit message / handoff log.

## 4. Rollback (only if section 3 fails)

Do not clear data and do not uninstall. Not during a call.

1. Stop the app: `adb shell am force-stop com.rokid.context` (confirm `adb shell pidof com.rokid.context` prints nothing).
2. PRESERVE THE BROKEN STATE FIRST. Nothing is deleted or replaced until this is done and validated:
   - `adb exec-out run-as com.rokid.context tar c . > ~/Desktop/rokid/backups/pca-phone-broken-<tag>.tar` (the app is stopped, so this snapshot is consistent; it includes `-wal` and `-shm`).
   - Validate it like section 1 step 3 (`tar tf`, the listing, sizes, the header). If it cannot be taken or does not validate: STOP, replace nothing, ask for help.
   - Also keep an on-device copy next to the live data, which restores without a laptop: `adb shell run-as com.rokid.context sh -c 'mkdir -p quarantine-<tag> && cp -p databases/context.db* quarantine-<tag>/ && cp -p shared_prefs/db_key.xml quarantine-<tag>/'` and check `ls -l` lists the same files and sizes. (`quarantine-<tag>` is a sibling of `databases/` inside the app's private directory; the app ignores it.)
3. Only now put the v16 data back. Reinstalling the old APK cannot open a v17 file, so the database files are replaced by the validated pre-install backup:
   - Move, never delete, the broken files out of the way: `adb shell run-as com.rokid.context sh -c 'mkdir -p quarantine-<tag>/replaced && for f in databases/context.db databases/context.db-wal databases/context.db-shm; do [ -e $f ] && mv $f quarantine-<tag>/replaced/; done; true'`.
   - `cat ~/Desktop/rokid/backups/pca-phone-pre-<tag>.tar | adb shell run-as com.rokid.context tar x` restores `databases/` (with its `-wal` / `-shm`), `shared_prefs/db_key.xml` and `files/`; the master key in the Keystore was never touched. (This overwrites `db_key.xml` and `files/` entries with the pre-install copies; the broken versions are in the tar and the quarantine folder from step 2.)
   - `adb install -r <previous.apk>` (same signature), open the app, run the checks of section 3 (items 1, 2, 4, 5).
4. If the restore does not open either: STOP. Do not delete anything else, do not rotate keys. The broken-state tar, the quarantine folder and the pre-install backup are everything needed; put the quarantined files back with `mv` if asked, and ask for help.

## 5. If the wrong thing happens

- "App opens empty": do NOT continue to use or capture (a fresh database would be created under a new key and the old one is orphaned). Force-stop, follow section 4.
- "Keystore invalidated" (new screen lock / security change wiped the master key): the database and `db_key.xml` are unreadable by design. Nothing in this document can recover it; the backup would restore the file, but not the master key. This is why section 2 forbids changing the lock screen or credentials around the install.
