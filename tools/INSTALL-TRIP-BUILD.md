# tools/install-trip-build.sh

Safe, dry-run-by-default installer for the trip build. Procedure source: `docs/superpowers/plans/handoffs/v17-upgrade-recovery.md`. Still needs the user's explicit go.

```
# 1. Dry run (default): read-only checks, prints every step. Writes NOTHING (no temp dir, no file on the host, nothing on the phone) and pulls NOTHING
tools/install-trip-build.sh --phone <serial> --glasses <serial> [--watch <serial>] --apks ~/Desktop/rokid/backups/apks-v110-<sha>
# 2. Real run (only after the user says go): add --go [--tag pre-v17-20261121]
```

Serials are mandatory (no auto-detect, every adb call uses `-s`). Refused: equal serials, serial not in `adb devices`, watch serial equal to phone/glasses, emulator serial with `--go` (needs `--allow-emulator`), missing APK, wrong package, downgrade (`--allow-downgrade` exists, do not use), signing cert differs from the installed one, a call active. The script FAILS CLOSED: any uncertainty refuses with exit 2.

- Signature: `apksigner` missing, installed-APK pull failing or an unreadable certificate all REFUSE. Only override: `--accept-unverified-signature` (printed prominently); with `--go` on a non-emulator it additionally needs `--i-understand-no-signature-check`. A certificate mismatch is never overridable. The dry run does not pull the installed APK: it prints "signature check will run with --go".
- Call guard: `dumpsys telephony.registry` must succeed and EVERY `mCallState=<n>` line must be exactly `mCallState=0` (anchored); none, junk, differing values or an adb failure refuse. Re-checked before the backup and every install.
- Backup: `am force-stop` must succeed, then `pidof` must prove the process is gone (exit 1 and empty output; any adb/transport error refuses; a few retries). SHA-256 digests are checked (64 hex) and every metadata/APK write is checked. Disk preflight: free space in the backup dir and temp dir must be at least 3x the app data size (`run-as du`). The tar, the previous APK and the metadata are `0600`.
- Failure messages track the phase (`preflight|backup|installing|installed`). Before the install step starts it says "No change was made to the phone: leave phone data untouched" and that any backup is unvalidated (do not restore from it). Restore steps are printed only after a validated backup AND the install started; they are non-destructive first (quarantine copy before anything is replaced).

Order: backup (force-stop, `run-as tar c .`, validated: `tar -t`, db and `db_key.xml` listed, size, encrypted header, sha256; old phone APK kept beside it) in `~/Desktop/rokid/backups/pca-phone-pre-<tag>.tar` -> `install -r` phone -> 60 s log scan (counts only) -> glasses -> optional watch. Stops at the first failure and prints the rollback. `watch-debug.apk` (same applicationId as the phone app) only ever goes to `--watch`.

Rollback: restore from the validated tar, never uninstall or `pm clear` (section 4 of the recovery doc; the script prints it with your paths).

Self-test (emulator only, throwaway data): `--self-test --phone emulator-5554 --apks <dir> [--go] [--allow-plaintext-db]`; `PCA_SELFTEST_CALLSTATE=2` simulates a call. `--self-test`, `--allow-plaintext-db` and `PCA_SELFTEST_CALLSTATE` are hard-refused on any non-`emulator-*` serial (the emulator debug build is unencrypted). `--self-test` first runs the refusal fixtures; `--fixtures` runs only those (function-level overrides, no adb, no device): call guard, force-stop/pidof, SHA-256, disk, signature, argument rules.

Cannot verify: real Keystore survival, the glasses/watch launch screens, UI state after install (do the manual checks of section 3), and a signature without the explicit override flag (it refuses).
