# v16 to v17 upgrade: backup, verify, recover

Applies to the phone app `com.rokid.context` when the build that carries `MIGRATION_16_17` (one additive column, `ask.sourceStates TEXT DEFAULT NULL`) is installed over a v16 database. Proxy ruling V1 (2026-11-17): conditional YES, backup alone is not enough. Installing still needs the user's explicit go.

## Hard rules (every step below keeps them)

- Never uninstall the app. Never "Clear data" / `pm clear`. Never rotate or delete the Keystore-backed database key. Never use a destructive migration fallback (the app has none; do not add one).
- Install only with `adb install -r` of an APK signed with the SAME signature as the installed one (the Keystore master key and `db_key` preferences belong to that app and signature; a different signature = a different identity = the database becomes unreadable).
- v16 binaries cannot open a v17 database (Room refuses a downgrade). So "install the old APK again" is NOT a rollback once the new build has opened the database. The rollback is restore-from-backup, then reinstall.

## The key (read this once)

The database is SQLCipher-encrypted. Its 32-byte passphrase is created once by `DbKeyStore` and kept in `EncryptedSharedPreferences` (`db_key.xml`, AES256-GCM, wrapped by an Android Keystore master key). The backup tar contains the encrypted database file and `db_key.xml`, but the Keystore master key never leaves the device. So a backup restores only onto the SAME installation (same device, same app signature, Keystore intact). It is a protection against a failed migration or a bad file, not against uninstalling or wiping the phone.

## 1. Backup (before installing)

```
mkdir -p ~/Desktop/rokid/backups
adb exec-out run-as com.rokid.context tar c . > ~/Desktop/rokid/backups/pca-phone-pre-<tag>.tar
```

- Use a real tag, e.g. `pre-v17-20261121`. Needs the debuggable build currently on the phone (`run-as`); if `run-as` is refused, stop and tell the user: do not install.
- Check it: `tar tf ~/Desktop/rokid/backups/pca-phone-pre-<tag>.tar | grep -E 'databases/context\.db|shared_prefs/db_key\.xml'` must list both, and `ls -l` shows a file of plausible size (the same order as `databases/context.db`). Also copy the original photos folder the same way if it is outside `run-as .` (it is under `files/`, so it is included).
- Record: `adb shell dumpsys package com.rokid.context | grep -E 'versionName|versionCode|signatures|Signing'` into the tag's notes (the APK you will reinstall on rollback must match).
- Keep the previous APK file next to the backup.

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

Do not clear data and do not uninstall.

1. Stop the app: `adb shell am force-stop com.rokid.context`.
2. Reinstall the PREVIOUS APK with the same signature. It cannot open a v17 file, so first put the v16 data back:
   - `adb exec-out run-as com.rokid.context sh -c 'rm -f databases/context.db databases/context.db-wal databases/context.db-shm'`
   - `cat ~/Desktop/rokid/backups/pca-phone-pre-<tag>.tar | adb shell run-as com.rokid.context tar x` (restores `databases/`, `shared_prefs/db_key.xml`, `files/`; the master key in the Keystore was never touched).
   - `adb install -r <previous.apk>` (same signature), open the app, run the checks of section 3 (items 1, 2, 4, 5).
3. If the restore does not open either: STOP. Do not delete anything else, do not rotate keys. Pull the current state first (`adb exec-out run-as com.rokid.context tar c . > ~/Desktop/rokid/backups/pca-phone-broken-<tag>.tar`) and ask for help; the tar of the broken state plus the pre-install backup is everything needed.

## 5. If the wrong thing happens

- "App opens empty": do NOT continue to use or capture (a fresh database would be created under a new key and the old one is orphaned). Force-stop, follow section 4.
- "Keystore invalidated" (new screen lock / security change wiped the master key): the database and `db_key.xml` are unreadable by design. Nothing in this document can recover it; the backup would restore the file, but not the master key. This is why section 2 forbids changing the lock screen or credentials around the install.
