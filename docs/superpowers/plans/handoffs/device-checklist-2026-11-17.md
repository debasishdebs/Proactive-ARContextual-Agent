# Device checklist for when the user is back (written 2026-11-17, branch feat/v101-ask-eval)

Everything below needs the real phone, glasses or watch, or a decision only the user can make. Nothing has been installed or pushed. Emulator results are in `docs/superpowers/reviews/2026-11/emulator-smoke-2026-11-17.md`.

## A0. WATCH APK HAZARD (found in emulator smoke 2)
`watch-debug.apk` has the SAME applicationId (`com.rokid.context`) as the phone app. Installing it on a phone REPLACES the phone app (same signature). Only ever `adb -s <watch serial> install` it on the watch. Always pass -s with the serial; never run install without -s when several devices are attached.

## A. Before any install (phone is on the eval build 394667e0, glasses 1.0.0, watch 1.0.0-rc1)
1. Confirm no call is active (`adb shell dumpsys telephony.registry | grep mCallState` must be 0).
2. Phone backup, procedure in `docs/superpowers/plans/handoffs/v17-upgrade-recovery.md` (force-stop first, tar with -wal/-shm, validate the tar, record sha256). Store under `~/Desktop/rokid/backups/`.
3. Staged APKs: re-run the full verification and rebuild right before the install (the folder `~/Desktop/rokid/backups/apks-v110-*` holds older builds); old 1.0.0 APKs stay staged for rollback of the GLASSES (the phone DB moves to Room v17, so a phone rollback = restore the backup + same-signature APK, never uninstall).
4. Say "go" explicitly. Never uninstall the phone app (SQLCipher key lives in the Keystore).

## B. Rulings that need the user (Codex proxy answers are in `docs/superpowers/decisions/2026-11-17-codex-proxy-rulings*.md`; confirm or change)
- F2: may a fact Forget leave its cited photo extractable? (currently v1: photo is tombstoned)
- Whether a rebuilt photo may stay undescribed while Battery Saver, heat or offline gates block extraction.
- Turn Forget v2 (`forgetV2`) on for real use? It needs the worn test and a clean shadow run first. Default OFF.
- Default Query pipeline: keep opt-in `dated-query` (default plan hash f5782e0d8610) or promote after an eval at >= 50 percent.
- Suspect sweep scope (only user-marked suspects now).
- Place Guide: keep OFF by default; switch it on for the 22 Nov field test (Settings > Place Guide, Developer mode).
- Ask in a pause zone: refused while a zone is approached/enforced (setting `askZoneFence`, default ON).
- Nexus: smaller 2.0, PCA stays default HUD, GitHub only, half-day with glasses 7-10 Dec.

## C. Things only the real devices can verify
Phone:
- Encrypted v16->v17 upgrade on the real database (the emulator test passed with a seeded encrypted DB; do the backup first).
- Background start of the hub from BOOT_COMPLETED / Bluetooth ACL on Android 12+ (needs the battery exemption, already granted), glasses BT address learning (name contains "rokid").
- Real GPS zone transitions incl. APPROACH, screen-off and background behaviour (emulator verified OUTSIDE -> INSIDE -> OUTSIDE only).
- Seed Hyderabad with internet before 22 Nov: Settings > Place Guide > "Seed this area" (Charminar/Golconda preset). Check the Wikidata class Ids and give a Wikimedia User-Agent contact (`DEFAULT_USER_AGENT`).
- Pause zones: add your own zones on the map (Settings > Privacy > Pause zones); confirm the Jarawa preset boundary if you keep it; Sikkim/Darjeeling border areas need boundaries you verify.
Glasses:
- Place card at MEDIUM/MAX: 12 s expiry, head-motion dismiss after 2.5 s, refusal inside a zone, minimised popup withdrawal.
- Mark photo refusal inside a zone and camera closing mid-hold.
- zoneApproach local pause when the link drops near a zone.
- Worn-test list from the handoff (voice mark + undo, double-tap pause/resume, pause/resume capture replies, glasses voice ask, camera-busy mark, Forget a marked photo, Activity taps, watch WALKING, 35-min X7/X10 gate, second M1 trace) and Task 38 (14-item device checklist), LIVE guards test (`tools/M1-RUN.md`).
Watch: update to the v1.1 build (optional, protocol unchanged).
Activity pagination: scroll to row 1,500 and the 100k timing check on the phone.
Multi-select Forget (A7-A9, Developer only, needs `forgetV2` ON, default OFF): flag OFF shows no menu and a long press does nothing. With it ON on a COPY of the data or a throwaway profile: long-press a moment, select this hour / day, preview the dependency counts, acknowledge above 200, refuse above 5,000, then check the rows are gone and Ask shows "Answered from a forgotten fact". Time the ids-only day selection and the plan of 5,000 roots against the laptop numbers in `SelectionPerfTest` (Robolectric, 2026-11-17: ids-only read of 5,000 = 10 ms, plan of 5,000 roots = 541 ms, forget of 5,000 roots = 885 ms; plain memories, no descendants). Do not run it on the real data until the user rules.

## D. Chores
- Move GitHub `main` to v1.x (ask first); rotate the credentials that were hard-coded in the kg repo.
- Release signing (key rotation) after Sri Lanka, with a spare-install rehearsal.
- Sikkim/Darjeeling zone presets and a Sri Lanka pass (photography rules, camera fees) once boundaries are known.

## E. Quota note
Codex quota is low until about 21 Nov: reviews run at medium effort in batches (`feedback-codex-effort-budget` memory).


## F. Key rotation (release signing, planned after Sri Lanka, 1.3.0): verified on the emulator, device checks still owed
Verified 2026-11-18 on the API 34 emulator (`docs/superpowers/reviews/2026-11/key-rotation-rehearsal-2026-11-18.md`): `apksigner rotate` + `apksigner sign --next-signer ... --lineage <file> --rotation-min-sdk-version 28` installs over a debug-signed install WITHOUT uninstall and keeps the data directory and the encrypted DB. Device checks owed before the real rotation:
- Run it on a device BELOW API 33 (the glasses, minSdk 28; the phone if it runs API 29-32): older Android only sees the old-key block unless the flag is used.
- Confirm Keystore survival on the real phone (the encrypted DB must open after the rotated update) and take the phone backup first.
- After BOTH apps rotate: check the phone/watch Data Layer match and whether Rokid's CXR link checks signatures.
- Always sign with the ONE lineage file: a wrong lineage over an already-rotated install silently overwrites the device's stored lineage. Debug-signed builds can no longer update a rotated install.


## G. First worn card test (B3; proxy ruling 4 in docs/superpowers/decisions/2026-11-19-codex-proxy-rulings-4.md, needs the user's confirmation)
Supervised, synthetic content, stationary, outside any zone. Enable only the switches the test needs, switch them OFF afterwards. Passing it does NOT turn on everyday delivery (that still needs seven worn shadow days and deliberate activation).
- [ ] Card readable (title, body, source label) at MEDIUM/MAX; no suggestion card below the allowed verbosity.
- [ ] Expires within 12 s; head-motion dismissal after 2.5 s; popup and minimised overlay both withdrawn.
- [ ] Refused when unworn, paused, in a zone, driving/vehicle uncertain, budget exhausted; no later backlog.
- [ ] Withdrawn on zone entry, source Forget, switch-off (also an in-flight card).
- [ ] Quiet-hours boundary ends the card on the glasses clock; unknown clock offset draws no quiet-bounded card; reconnect with changed offset recomputes.
- [ ] Dismissal/snooze/never survive an app restart; delivery fails closed when storage fails.
- [ ] Place card placement: try LEFT/CENTRE/RIGHT (Settings > Place Guide > Card position, Developer mode), note clipping and the real field of view.
