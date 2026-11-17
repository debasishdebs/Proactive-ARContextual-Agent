# Emulator smoke 2, 2026-11-17

Branch feat/v101-ask-eval. Build under test: the staged APKs in `backups/apks-v110-c75c5f55/` (phone-debug, glasses-glasses-debug, watch-debug). Emulator `MyEmulator` (Android 34 google_apis arm64, headless, 1080x2400 @420dpi after `wm` override). Every adb call used `-s emulator-5554`; no real device was touched (a real device was attached to the host the whole time and was never addressed). No app code was changed.

## Verdict

| Goal | Result |
|---|---|
| 1 Upgrade v1.0.0 (encrypted) to c75c5f55 with `install -r` | PASS, no crash, no Room/Migration/SQLite/SQLCipher error |
| 2 New screens | PASS, no crash; 4 minor UI findings, 1 behaviour note (below) |
| 3 Zone at Charminar + mock fix + pin | PASS: pin refused, Ask answers "Paused", Home Paused |
| 4 Watch APK on emulator | Installs and launches (but see Finding 5: it replaces the phone app) |
| 5 logcat | 0 `FATAL EXCEPTION`, 0 ANR, 0 StrictMode, 0 `start_refused`/`hub_recover` |

No crash was found (the Pause zones crash from smoke 1 is gone: the screen renders on 1080x2400 and rotates fine).

## 1. Upgrade path (encrypted)

1. Uninstalled both packages, `wm size reset`, installed `apks-v100-final-candidate-20261113/pca-phone-debug-1.0.0.apk` (versionCode 4), onboarded (permissions, glasses/watch/AI skipped).
2. Settings > Memory > Encrypt memory ON (the "Turns on after Proactive Context restarts" hint shows), force-stop, relaunch: `context.db` header became random bytes (`89a3 d1a5 d0f6 e934 ...`), inode 376952.
3. `adb install -r` of c75c5f55 `phone-debug.apk` (versionCode 4 equal to v1.0.0, same signature): Success. After the upgrade `context.db` kept the same inode and size (688128) and the header still ciphertext; the WAL grew (24752 to 65952 bytes), so the app opened and wrote to it. The upgraded app showed the Welcome screen again (the known OnboardingGuard behaviour, DB empty and no AI endpoint), then Home/Activity/Settings worked.
4. Logcat after the upgrade: no FATAL, no `Migration`, `Room`, `SQLiteException`, `InvalidKey`. Same limit as smoke 1: the version, rows and v17 schema cannot be read under SQLCipher, so "v17 reached" is inferred.
5. Separately, an unencrypted fresh install of the same c75c5f55 APK (used for seeded data, below) opened with no error.

## 2. New screens, evidence

- Home: renders, quick-ask chips, "Glasses not authorized" banner, Paused state in a zone, Resume button.
- Activity (Today): tiles, Marks hint, Evening timeline; Week/Month tabs; Sort menu; Frames/Memories/Answers lists.
- Activity "By time" (seeded data, see Method): `Today - 6 moments`, `18:00  6` hour row, Expand/Collapse day and hour, "No activity 00:00-18:00" gap rows.
- Multi-select (forgetV2 enabled in `pc_dev.xml` on the emulator only): long-press a row gives "1 selected / Done" and a checkbox column; the Selection menu has Select this page, Clear, Forget selected (N); in the By time view it has Select this hour (6), Select this day (6). Dialog: "Forget 6 selected? This cannot be undone. Forgetting these also affects: 6 memories (6 removed), 2 answers (2 rebuilt)". The dialog and the selection survive a rotation. Confirm: memories gone, both asks `redacted=1` with `sourceStates {"v":1,"state":"SOURCE_FORGOTTEN"}`, answer text "Removed - a source was forgotten".
- Answers list rows read "Today 18:16 - Answered from a forgotten fact"; the answer page shows the question, "Answered from a forgotten fact" and an Ask again button. Tapping Ask again with no AI endpoint: a toast, no crash, row unchanged (graceful; the toast text itself was not captured by the screenshot).
- Ask UI (Home, typed): offline/no data: "I don't have anything on that yet - I'll look again in your next photos"; inside a pause zone: "Paused: you're in a pause zone" (Ask zone fence ON). Fence switch OFF/ON toggled, no crash.
- Settings > Privacy > Pause zones: map renders with no tiles, +/-/Fit, Draw a box, form (Name, S/N/W/E, Buffer 300). Added "Charminar" S 17.3600 N 17.3630 W 78.4730 E 78.4760: banner `Zone "Charminar" added.`, list card `Charminar - Buffer 300 m`, enable switch, chip `Inside: capture is paused` (no fix: fail closed). Jarawa preset button (UNVERIFIED label): `Jarawa preset added...`, card `Jarawa reserve (Andaman Trunk Road) - Buffer 5.0 km - preset, UNVERIFIED`, chip `Outside, 1624.8 km from the box` once a Hyderabad fix arrived, button then says "The Jarawa preset is already in your list."
- Settings > Place Guide (Developer on): switch, `0 places in the pack (0 KB)`, `0.0 MB of N MB used`, `0 seeded, 0 fetched, 0 pinned`, budget picker 25/50/100/200 (each choice updates the "of N MB" line; `place_guide.xml` budgetMb written), Clear fetched places, pins list, Seed this area form and the Hyderabad preset (NOT tapped: it fetches from Wikipedia/Wikidata/OSM, so the network seed was left unexercised).
- Pin a place here: dialog fields Name, Kind (Monument), Radius 60, Part of (No site), Note (140 chars), Cancel/Save. Outside a zone (the emulator's default fix 39.237,-123.150, California) the pin was saved: card `Test_pin - Monument, 60 m - Your note: note_x`, Edit/Delete; Delete works. Log `PcPlace pin created count=1`, later `pin refused zone`.
- Settings > Diagnostics > Developer card: Developer mode, `Pause zones also pause Ask` (the askZoneFence switch), Record raw glasses sensors (Off), Keep canvas pairing (dropdown Off/1 day/3 days/7 days; the "1 day" text and explanation change, back to Off OK), Photo search dimension test (debug). Context hub card: `State UNKNOWN`, counters 0.

### Pin, zone, mock location (goal 3)

Zone Charminar ENABLED, app restarted (cadence HIGH), `emu geo fix 78.4747 17.3616`. Result within 20 s: Home shows `Paused`; Pause zones chip `Inside: capture is paused` for Charminar and `Outside, 1624.8 km` for Jarawa (so exactly one zone INSIDE; `pause_zone_state.xml` memo agrees: zone INSIDE, jarawa OUTSIDE). Place Guide, `Pin a place here`: red text `A pause zone is active or near, or its state is unknown: no place can be pinned here.`; no dialog opened and `PcPlace pin refused zone` was logged (this is the refusal before the dialog; the save-time recheck was not reachable). The Place Guide sampler stayed idle: no sampler/place lines other than the pin refusal while inside, and `place_guide.xml` enabled=true. Ask in the zone: "Paused: you're in a pause zone".

## Findings (none is a crash; nothing fixed)

1. MINOR, UI, Pin a place here dialog is lost on rotation. Repro: Place Guide, Pin a place here, type a name, rotate (`settings put system user_rotation 1`): the dialog closes and typed text is gone; no crash. The Forget dialog and the selection survive rotation, so the pin dialog is the odd one (cause not investigated; likely state not saved across recreation). Impact low (an unsaved draft on a settings dialog).
2. MINOR, UI cosmetic, Pause zones map: the Jarawa label is drawn one character wide at the right edge when the box is near the canvas edge (wrapped vertically "Ja/ra/wa/res/er/ve/(A"), overlapping the 92E tick label. Also, when the map is zoomed to a small box the longitude tick labels overlap each other (e.g. `78.469 78.470 78.471` run together). Repro: add the Jarawa preset (default viewport) and scroll the map into view; for the overlap zoom with + on the Charminar zone.
3. MINOR, UI: on Activity > Answers > an answer (and likely other previews), the bottom bar highlights Home, not Activity.
4. INFO (known, by design), Welcome/onboarding re-runs on every cold start while there is no usable AI endpoint and no memories (OnboardingGuard). It cost one `onb` pass per launch in this smoke, so it also shows how a tester with no endpoint experiences it. Not a bug of this build.
5. INFO/HAZARD: `watch-debug.apk` has applicationId `com.rokid.context`, versionCode 41, minSdk 30 (the Wear OS variant of the same package). `adb install` of it on the phone emulator returned Success and REPLACED the phone app (the phone then had no `MainActivity`, only `.watch.MainActivity`, "PCA Motion"; data kept). Its screen came up (`PCA Motion`, "Unknown", "Permission needed", Allow motion, "Send motion to phone") with no crash. To get the phone app back I had to `install -r -d` the phone APK (a downgrade from 41 to 4; debug builds allow it). Any real-phone sideload of the watch APK would do the same, so the watch must only ever be installed to a watch device.
6. No-bug notes about the emulator: (a) the emulator's boot fix (California) is accepted by the pin as "fresh" because the app's own 10 s HIGH_ACCURACY request keeps receiving it, so the pin saved legitimately outside the zones; (b) the geo-fix works only after a restart with cadence HIGH (as in the smoke-1 addendum); (c) the first run seeded `memory` rows were deleted at every cold start because their ids did not start with `rokid_` (PipelineRecords purgeNonRokidMemories); not a bug, a seeding artefact. After using ids that stayed alive in the running app, the Memories list, Moments list, By time and Forget flows were driven with the rows inserted while the app ran.

## Method notes (what "seeded" means here)

For the data-driven screens a fresh unencrypted install of the same APK was used (an encrypted DB cannot be written with `sqlite3`). Rows were inserted with the on-device `sqlite3` via `run-as` (emulator only): 6 `memory` rows (ids `seedmem1-6`, which the app deleted at the next cold start because they lacked the `rokid_` prefix, so they were re-inserted while the app was running), 20 `moment` + `moment_part` MARK rows, 2 `ask` rows citing seedmem1/2 with LIVE sourceStates. `pc_dev.xml` was written on the emulator with `developer_mode` and `forget_v2` true. Nothing was sent anywhere; no AI endpoint was configured.

## Logs

Windows collected: upgrade, UI tour, seeded flows, zone flow, watch/glasses. Counts over all windows: `FATAL EXCEPTION` 0; `ANR in` 0; `StrictMode` 0; `start_refused` 0; `hub_recover` 0. The `AndroidRuntime` lines are only the `uiautomator dump` helper starting. `PcZone`: `zone pause added state=INSIDE reason=NO_FIX` (x3, fail-closed at start), `zone pause ended n=1` (x2, after the first fix), repeating `zone publish failed cls=IllegalStateException` (the glasses sink, no link, as in smoke 1). `PcPlace`: `user places synced`, `budget set evicted=0` (x4), `pin created count=1`, `pin refused zone`. Hub: `ContextHub wear unknown`, `PcWatchdog watchdog hub down, starting it`, `Background started FGS: Allowed ... ContextHubService`, `PcExit ... exempt=n hubLive=y`. No secrets, tokens or pairing codes appear in this report (the app was never paired).

## Could not be verified here

Real glasses pairing/CXR, Bluetooth, camera and capture, photo-backed Frames/Moments (only MARK-type moments and memories were seeded), the by-time pagination with more than one page, the Forget ack tick (it did not show for 6 items; the dialog had no acknowledgement control, so that may only appear for larger or riskier selections), a real answer from an AI endpoint ("Answered from a forgotten fact" then Ask again giving a new answer), Seed this area and Seed Hyderabad (needs network), the save-time re-check of a pin after the zone changes, APPROACH transitions and background location, Place Guide card display on the glasses, upgrade with real user data rows, the Pause zones screen on small 320x640 screen after the fix, the Wear OS app on a real watch.

## Cleanup

Emulator stopped with `adb -s emulator-5554 emu kill`; `wm size/density` reset; rotation put back to auto. No timers or background processes left.
