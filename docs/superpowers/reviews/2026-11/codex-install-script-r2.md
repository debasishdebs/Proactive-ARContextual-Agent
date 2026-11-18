At `02411846`, two P1 fail-open paths remain:

- **P1 — Marker parser failures can bypass confirmed-stop protection.** [Lines 94–100](／Users/debasishkanhar/Desktop/rokid/proactive-context-agent/tools/install-trip-build.sh:94) leave parsing assignments unchecked and finish with `return 0`. Mocking `sed` to fail while pidof returns diagnostic text plus `__PCA_RC=1` makes `stop_app` announce “confirmed gone.” Backup can proceed without proof of quiescence. Explicitly check every parsing assignment; conditional invocation suppresses reliance on `set -e`.

- **P1 — Failed log retrieval passes post-install verification.** [Lines 336–343](/Users/<user>/Desktop/rokid/proactive-context-agent/tools/install-trip-build.sh:336) use `grep … || true`, hiding adb/pipeline failures. With a running PID and both logcat reads returning 7, the mock reports zero errors and succeeds. Migration failures can go undetected. Check log retrieval separately from counting matches.

Original five-P1 recheck:

- Signature verification: closed; uncertainty refuses without explicit override, mismatch always refuses.
- Call guard: closed; anchored parsing rejects malformed, conflicting and spaced active states; CRLF idle states pass.
- Force-stop confirmation: transport/remote-status handling improved, but parser-error bypass above remains.
- Hashing and backup writes: original defects closed; validated digests and checked writes precede installation.
- Unsafe restoration guidance: closed; validated-backup and installation-phase gates control restore instructions.

Other checks: quoted device arguments and role routing are present; real-serial self-test/environment overrides refuse; phase tracking remains accurate across later failures. Backup validation precedes every install and cleanup retains the archive. Disk checks, `0600`, strict shell options and ordinary dry-run write/pull avoidance are implemented.

Documentation overstates universal fail-closed behavior. Its unconditional “previous APK kept” claim also excludes the explicit unverified-signature path.

`bash -n` passed. Used isolated function mocks; **no adb ran**.

NOT READY