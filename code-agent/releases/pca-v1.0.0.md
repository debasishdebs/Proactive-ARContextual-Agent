## v1.0.0

Debug-signed; no APKs are attached (release signing is still pending). Phone database is Room v16, unchanged from v1.0.0-rc1 (no migration; still back up before every `adb install -r`, never uninstall). Versions: phone and glasses versionCode 4, watch 41, versionName `1.0.0`.

**What it is**
- v1.0.0-rc1 plus three changes: the glasses never keep a stale answer after a Forget, the on-device memory index logs its lifecycle, and Home shows every active banner. See the v1.0.0-rc1 and per-version notes for the rest of the train.

**Glasses answers follow Forget**
- Forgetting a photo, or the question behind an answer, clears the answer card on the phone and on the glasses. Each answer carries the id of its question; the phone withdraws it and the glasses drop it, including an answer that arrives late from an older session. An answer on the glasses also expires after 5 minutes.

**Index logging**
- The memory index build, its progress, pauses and readiness are written to `pca.log` as milestones, even with diagnostics off. Logging never re-reads gates and never throws.

**Home banners**
- All active banners show as a swipeable pager with page dots; the pager keeps your page when banners change.

**Reviews**
- Codex rounds on the index logging (three), the HUD fix (two) and the final candidate; the last round found no P1. Installed on the phone and glasses with a clean start; the watch can stay on rc1 (the protocol is unchanged).

**Known issues**
- Glasses wear detection: with the glasses' Wear detection off (or the firmware pinning the proximity sensor to worn), "not worn" is never detected, so AutoStop only stops when the glasses disconnect. Turn Wear detection on and reboot the glasses. The no-motion fallback moved to v1.1.
- Release signing is pending.
- Carried over from v1.0.0-rc1: over-linking on Forget (memories only, never photos); a broad Suspect descriptions list; provider content-filter refusals leave some photos undescribed; two occasionally flaky tests; "Delete this mark" is not a voice command; and the v0.9.9 carry-overs listed there.
