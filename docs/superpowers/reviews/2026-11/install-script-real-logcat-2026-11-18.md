# install-trip-build.sh against real logcat, 2026-11-18

Emulator only (emulator-5554, MyEmulator, Android 14); every adb call used `-s emulator-5554`. Script commit 16be8115 plus one fix.

## Layout (real capture) vs the awk assumptions
Real line: `         1795007373.462     0     0 I Machine model: linux,ranchu`. Epoch is field 1, pid field 2, tid field 3: matches the script. Separators `--------- beginning of main` carry no timestamp and are skipped by the `^[0-9]+\.[0-9]+$` test. Empty and multi-word tags (`I         : text`, `I printk  : text`) parse the same. A multi-line message (stack trace, tab-indented `at ...` lines) is emitted as one full record per line, each with its own pid, so pid filtering needs no continuation handling. No layout mismatch.

## Bug found and fixed (false FAIL on a healthy run)
First run: `phone app restarted during the 60 s window (no pid before the window)`, exit 3, on a healthy app. Cause: `am start` returns before the process is forked, so the immediate `pidof` was empty. Fix: `am start -W` (line 458). Also the stray `ERROR: unexpected failure at line 460` noise disappeared. A fixture with real-captured, anonymised lines was added (181 fixtures pass).

## Runs (v1.0.0 phone, Encrypt memory ON, header ciphertext, apks-v110-f1dcf1fb, `--self-test --go --allow-emulator`)
- Healthy: backup OK (tar validated, entries=40, db_key present), `phone install: Success`, `pid_before='6473' pid_after='6473'`, `real_error_count=0 benign_allowlisted_count=15`, `BENIGN-ONLY`, `phone verified`, rc 0.
- Injected failure A (`log -p e` FATAL + `Process: com.rokid.context, PID: 1234` + SQLiteException during the window): FAIL rc 3 ("2 pids for com.rokid.context in the snapshot").
- Injected failure B (FATAL from another pid, plus `SQLiteException: no such table` naming the package): `real_error_count=1`, FAIL rc 3 ("real_errors=1"). A bare FATAL line of an unrelated pid without the package is not counted (by design).
- There is no scan-only mode, so the failures were injected during real script runs.

## logcat -G 16M
Succeeds on the emulator (rc 0): main/system/crash/kernel buffers report 16 MiB. The coverage check passed in all runs (kernel lines are stamped at boot, older than install start).

## Notes
The emulator held a schema-17 DB from smoke 2, so installing v1.0.0 over it crashed (`A migration from 17 to 16 was required but not found`, a downgrade artefact, not a script issue); I uninstalled and reinstalled v1.0.0 fresh. Emulator stopped with `emu kill`.
