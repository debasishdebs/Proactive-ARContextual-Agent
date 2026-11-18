# tools/install-trip-build.sh

Safe, dry-run-by-default installer for the trip build. Procedure source: `docs/superpowers/plans/handoffs/v17-upgrade-recovery.md`. Still needs the user's explicit go.

```
# 1. Dry run (default): read-only checks, prints every step, changes nothing
tools/install-trip-build.sh --phone <serial> --glasses <serial> [--watch <serial>] --apks ~/Desktop/rokid/backups/apks-v110-<sha>
# 2. Real run (only after the user says go): add --go [--tag pre-v17-20261121]
```

Serials are mandatory (no auto-detect, every adb call uses `-s`). Refused: equal serials, serial not in `adb devices`, watch serial equal to phone/glasses, emulator serial with `--go` (needs `--allow-emulator`), missing APK, wrong package, downgrade (`--allow-downgrade` exists, do not use), signing cert differs from the installed one, a call active (`mCallState` must be 0, re-checked before every install).

Order: backup (force-stop, `run-as tar c .`, validated: `tar -t`, db and `db_key.xml` listed, size, encrypted header, sha256; old phone APK kept beside it) in `~/Desktop/rokid/backups/pca-phone-pre-<tag>.tar` -> `install -r` phone -> 60 s log scan (counts only) -> glasses -> optional watch. Stops at the first failure and prints the rollback. `watch-debug.apk` (same applicationId as the phone app) only ever goes to `--watch`.

Rollback: restore from the validated tar, never uninstall or `pm clear` (section 4 of the recovery doc; the script prints it with your paths).

Self-test (emulator only, throwaway data): `--self-test --phone emulator-5554 --apks <dir> [--go] [--allow-plaintext-db]`; `PCA_SELFTEST_CALLSTATE=2` simulates a call. `--allow-plaintext-db` exists only there (the emulator debug build is unencrypted).

Cannot verify: real Keystore survival, the glasses/watch launch screens, UI state after install (do the manual checks of section 3), and a signature if `apksigner` or the installed APK pull is unavailable (it warns).
