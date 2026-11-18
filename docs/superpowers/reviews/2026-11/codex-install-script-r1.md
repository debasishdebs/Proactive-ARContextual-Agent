At `84142129`, I found five P1 issues:

- **P1 — Signature verification fails open.** [Lines 153–160](/Users/<user>/Desktop/rokid/proactive-context-agent/tools/install-trip-build.sh:153) warn and continue when `apksigner`, APK pulling, or certificate extraction fails. Refuse installation unless an explicit override authorizes unverified signatures. Confirmed with mocked functions.

- **P1 — Call guard accepts unparseable/non-idle output.** [Line 132](/Users/<user>/Desktop/rokid/proactive-context-agent/tools/install-trip-build.sh:132) matches a substring. Both `mCallState=0junk` and `mCallState=0 mCallState=2` pass; failed `adb` commands producing idle-looking output also pass. Parse every state strictly and check command success. Empty output correctly refuses.

- **P1 — Backup can proceed without confirmed force-stop.** [Lines 189–192](/Users/<user>/Desktop/rokid/proactive-context-agent/tools/install-trip-build.sh:189) ignore force-stop errors and interpret an empty, failed `pidof` query as “stopped.” This can permit a live, inconsistent DB/WAL snapshot. Distinguish “no process” from query failure and refuse on uncertainty.

- **P1 — SHA-256 validation fails open.** [Line 106](/Users/<user>/Desktop/rokid/proactive-context-agent/tools/install-trip-build.sh:106) masks `shasum` failure through `awk`; [line 205](/Users/<user>/Desktop/rokid/proactive-context-agent/tools/install-trip-build.sh:205) reports “backup OK” with an empty hash. Confirmed with a mocked hashing failure. Require successful hashing and a valid digest before installing; failed backup metadata/APK writes also need explicit handling.

- **P1 — Backup failure prints unsafe restoration instructions.** [Lines 94–98](/Users/<user>/Desktop/rokid/proactive-context-agent/tools/install-trip-build.sh:94) instruct moving live databases and restoring `BACKUP_TAR` even when failure occurred because that archive was incomplete or invalid. Track backup validation and installation progress; before installation, instruct leaving phone data untouched.

Other checklist gaps: no disk-space preflight or enforced `0600`; only `set -u`; dry-run writes temporary files and pulls APKs despite “changes nothing.” The documentation overstates these guarantees.

Device operations use quoted `-s` arguments; only `adb devices` omits it. Equal-serial/watch routing checks and `--go` gates are present. `run-as` failure aborts installation. Self-test/plaintext options reject ordinary real-device serials. No evident pairing-code printing.

Reviewed statically with isolated mocks; no devices contacted.

NOT READY