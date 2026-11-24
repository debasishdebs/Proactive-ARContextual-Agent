# Emulator rehearsal: train13 cdef4c7e over the restored phone data (2026-11-24)

Emulator only (emulator-5554, AVD MyEmulator, Android 14, started with -wipe-data). No real device touched, no Gradle. Counts and findings only (no personal text, coordinates or pin names). Screenshots: session scratchpad `emu13/`.

Procedure: old build `apks-v110-cand-18c1b76a` (sha256 e50ef2ba81e2... = SHA256SUMS) installed, launched, onboarding permission step reached, force-stopped. Restored via run-as from the 2026-11-24 phone dump: `context.db` (plaintext, user_version 17) and prefs memory_settings, place_pins, pause_zone_state, hub_settings, app_config, persona_settings, place_guide. NOT copied: db_key, endpoint_keys, rokid_link, proactive_shadow, places.db*, photos. Then `install -r` of `apks-v110-train13-cdef4c7e` (sha256 425a9290f1ce... = SHA256SUMS, versionCode 4 both, same signing cert 61a8318261cb).

| # | Check | Result |
|---|-------|--------|
| 1 | Old build starts on restored context.db | PASS: user_version 17, memory 16929 / frame 4661 / moment 1328 (= dump), 0 fatal |
| 2 | install-trip-build.sh --self-test dry run | PASS: fixtures 229 passed 0 failed; "DRY-RUN complete: nothing was changed" (phone versionCode 4 -> 4) |
| 3 | install -r train13 over old build | PASS: Success, installed sha256 = staged, cert equal |
| 4 | ~7 min runtime: FATAL / ANR / SQLite-Room errors (logcat all buffers) | PASS: 0 / 0 / 0; pid 6513 alive throughout |
| 5 | Room opens restored db, no destructive migration | PASS: user_version 17; memory 16929 / frame 4661 / moment 1328 / pause_interval 37 / forget_tombstone 10352 unchanged after launch, browsing and forced jobs |
| 6 | RetentionWorker "retention paused" line | NOT OBSERVED: worker is ENQUEUED (periodic 24 h, Pause clean-up default ON in code). `cmd jobscheduler run -f` (ids 14, 37, 38) was accepted and WorkManager rescheduled it, but no RetentionWorker line reached pca.log or logcat. Table counts identical before/after (nothing deleted). Needs a real-device look at pca.log after the first daily run |
| 7 | places.db | PASS (opened): file created and encrypted (random header), `PcPlace user places synced count=9`. v2 / place_card_log not inspectable (encrypted); not verified here |
| 8 | Settings > Memory strings | PASS: "Quick recall" card and "Storage cap" card render; Storage shows "102 MB of 500 MB" |
| 9 | Retention settings preserved | PASS: app shows Quick recall 24 h, Photos 7 days, Skipped photos 1 day, Image vectors Forever, Storage cap 500 MB, matching memory_settings.xml in the dump. NOTE: the dump holds 24 h / 7 days / 500 MB, not 48 h / Forever / 2000 MB; if the phone was meant to hold the latter, it was changed after the dump or not yet saved |
| 10 | Home one-time notice banner | PASS: carousel page 3 of 3, "Clean-up no longer deletes the details of your photos. Forget removes them." with "Got it" |
| 11 | Pause zones map screen (Settings > Privacy > Open pause zones) | PASS: opens, description "0 pause zones, 0 seed areas, 9 pins"; pins drawn as a purple dot on the tile-less grid (9 pins clustered into one visible dot at default zoom). Pin editor itself not opened |
| 12 | tools/post-install-check.sh --phone emulator-5554 --apks train13 | PASS=28 WARN=1 FAIL=1 UNKNOWN=2, identical to train12 smoke |

post-install-check non-passes (expected): FAIL runtime perms 1 granted / 9 denied (emulator test never granted them); WARN battery exemption not whitelisted; UNKNOWN detailed log (ran 6 min, needs 70); UNKNOWN glasses not checked. pins total=9, db user_version=17, integrity_check ok, forbidden shapes pairs=0 http=0 @=0.

Observations
- Settings > Memory text says the storage cap "also runs while Pause clean-up is on", but `RetentionWorker.retain` skips `runQuota` when Pause clean-up is ON (comment "runs only while Pause is OFF"). With defaults the cap therefore does not delete; the UI text and the code disagree (low risk now, wording or behavior to settle).
- Onboarding reappears on each cold start (known, same as train12).
- pca.log shows an EmbeddingWorker "ProviderConfigException NOT_SET" retry loop (no embedding model bound on the emulator; expected).
- Emulator shut down afterwards.
