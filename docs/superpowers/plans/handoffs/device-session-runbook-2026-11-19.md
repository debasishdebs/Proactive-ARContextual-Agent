# Device session runbook, 2026-11-19 (about 23:30, real phone + real glasses)

Merged from: trip-install-checklist-2026-11-18, device-checklist-2026-11-17 (A, C, G), nexus-device-checklist (S2 4-8, 9-16), trip-day-quickref-2026-11-18, tools/INSTALL-TRIP-BUILD.md (install helper and post-install-check.sh), v17-upgrade-recovery, needs-user-2026-11-19. Docs-only: nothing here was executed when it was written. Commands are copied from those docs/scripts; anything not found in them is marked UNVERIFIED.

Placeholders: `<PHONE>` = phone serial (Samsung SM-S931B), `<GLASSES>` = glasses serial (USB 1901092545029120 or a wireless endpoint), `<CAND>` = `~/Desktop/rokid/backups/apks-v110-cand-18c1b76a` (candidate), `<DEFAULT>` = `~/Desktop/rokid/backups/apks-v110-f1dcf1fb` (conservative fallback), `<TAG>` = e.g. `pre-v17-20261119`.

## UPDATE 2026-11-20: next install (two staged builds). Every step below needs the devices: **device needed**
The 19 Nov session is done: the devices run **18c1b76a** (installed 19 Nov 22:54, validated backup `~/Desktop/rokid/backups/pca-phone-pre-trip-20261119-2254.tar`). Sections 0-12 stay as the procedure; for the next install read `<CAND>` as one of:
- **`<CONS>` = `~/Desktop/rokid/backups/apks-v110-cand-4436d1a5`** (git 4436d1a5, verified 15/15, review READY): 18c1b76a + street map (opt-in), 300 MB trip log with Detailed log, pin-to-Moment. **Recommended before Charminar (22 Nov).**
- **`<TRAIN>` = `~/Desktop/rokid/backups/apks-v110-train-166d3fd4`** (git 166d3fd4, verified 15/15, review READY): `<CONS>` + roaming data saver slice 1 + Model privacy (Automatic / Offline only) + Forget policy table (types only, not wired). Recommended after the data saver questions are answered and a day of use, **before Sikkim (1 Dec)**.
- Both: Room v17 unchanged, no new permission, `glasses-debug.apk` differs only in the shared log writer (optional), `nexus-bridge-debug.apk` byte-identical (do not install for the trip), no watch APK. Read the folder's `README.txt` first.

Install (device needed, only on your explicit "go", one build at a time):
1. [ ] Dry run, writes nothing: `tools/install-trip-build.sh --phone <PHONE> --glasses <GLASSES> --apks <CONS or TRAIN>`. Check the section 2 list (identities, packages, no downgrade, call state 0, backup path, order backup -> phone -> glasses).
2. [ ] Real run: same command plus `--tag pre-<cons|train>-<YYYYMMDD-HHMM> --go`. The helper takes the **validated backup first** (tar, db + `db_key.xml`, encrypted header, sha256), then installs the **phone first**, scans 60 s, then the glasses. Exit codes as in section 3; never add override flags.
3. [ ] `tools/post-install-check.sh --phone <PHONE> --glasses <GLASSES> --apks <CONS or TRAIN>` (section 4), then section 5 (first launch).
4. [ ] Then section 13 (new-feature tests) for the build you installed. Going from `<CONS>` to `<TRAIN>` later is the same procedure with a new tag and a new backup.

## Order of the session (about 2.5 h)
Sections 0-2 (pre-flight, identify, dry run): 15 min. Section 3 (backup + install, only after you say "go"): 15 min. Section 4 (post-install check): 10 min. Section 5 (first launch): 15 min. Section 6 (Place Guide seed + Card position test): 30 min. Section 7 (first worn card test, section G): 30 min. Section 8 (pause zones): 10 min. Section 9 (Nexus S2): 30 min, only if time is left. Section 11 (collect logs): 10 min. Section 10 (rollback) is only used if something fails. Can be skipped, in this order of preference: 9 (Nexus S2: move to the 7-10 Dec half-day), then 8, then the card-edge measurements in 6 (keep the quick LEFT/CENTRE/RIGHT look), then 7 (worn test: needs a calm 30 min and you wearing the glasses). Never skip 0-5: they decide whether the build is safe for 22 Nov (install freeze 26 Nov). STOP and ask Claude on any REFUSED, FAIL, exit 3, "app opens empty" or "not a database".

Time log: start ____ end ____

## 0. Pre-flight (5 min)
- [ ] NEVER run `connectedDebugAndroidTest` while the real phone or glasses are attached: the task installs and runs on every attached device, and the Nexus real-hub test force-stops the hub (it now skips itself unless the device is an emulator, but do not rely on that).
- [ ] Laptop awake (no sleep) and on the charger; repo at `~/Desktop/rokid/proactive-context-agent`.
- [ ] Phone unlocked, screen on, USB debugging authorised for this Mac, > 50% battery or charging. Do NOT change the screen lock or credentials (Keystore risk, v17-upgrade-recovery section 5).
- [ ] Glasses awake, > 50%, on USB or Wi-Fi adb. (Earlier today the glasses showed as `192.168.68.105:5555` OFFLINE and the phone was not listed: re-check.)
- [ ] No phone call active, none expected.
- [ ] Rokid-Coder hub and its glasses app STOPPED (one glasses link at a time; PCA and Rokid-Coder do not run together). Do not touch Rokid-Coder worktrees or installer; never use the Rokid-Coder installer for PCA packages.
- [ ] Mac free disk >= 3x the phone app data size (the helper checks; disk was at 100% earlier, `~/Desktop/rokid/backups` holds about 23 GB: delete stale folders only if the helper refuses on space).
- [ ] Build decision (tick one):
  - [ ] CANDIDATE `<CAND>` (git 0fbd6057, verified 15/15): adds Card position, Sikkim/Darjeeling/Sri Lanka presets, curated landmarks, v3.1 delivery code (all switches OFF). Needed for sections 6 (Card position) and 7.
  - [ ] DEFAULT `<DEFAULT>` (f1dcf1fb): fallback if the candidate has any problem. No Card position test, no delivery code.
- [ ] Never in this session: `watch-debug.apk` on the phone (same applicationId, replaces the phone app), `adb uninstall`, `pm clear`, Suggestions delivery switches outside section 7, the optional `nexus-bridge-debug.apk` (do not install for the trip; only in section 9).

## 1. Connect and identify (5 min)
```
adb devices -l
```
- [ ] Phone serial written down: `<PHONE>` = ______________ (model should read SM-S931B).
- [ ] Glasses serial written down: `<GLASSES>` = ______________ (USB 1901092545029120 or an `ip:port`/`adb-...-tls-connect` endpoint).
- [ ] Both show `device` (not `offline` or `unauthorized`). If `offline`: replug USB / re-run `adb connect <ip>:5555` (UNVERIFIED: standard adb, not in the docs) and re-run `adb devices -l`.
- [ ] A physical device may appear twice (USB serial and a wireless `adb-<serial>-<suffix>._adb-tls-connect._tcp` endpoint). Pass exactly ONE serial per device; the helper resolves identities and refuses aliases. Prefer USB for the backup.
- [ ] No watch connected, or if connected it is NOT passed anywhere. Skip the watch for this trip. Never run install without `-s`.
- [ ] Call state (must be 0): `adb -s <PHONE> shell dumpsys telephony.registry | grep mCallState`

## 2. Dry run (changes nothing; 5 min)
```
cd ~/Desktop/rokid/proactive-context-agent
tools/install-trip-build.sh --phone <PHONE> --glasses <GLASSES> --apks ~/Desktop/rokid/backups/apks-v110-cand-18c1b76a
```
(Use the `apks-v110-f1dcf1fb` path instead for the fallback.) It writes no file and pulls nothing. Read every line:
- [ ] Phone and glasses identities distinct (hardware serial, android_id, model, fingerprint).
- [ ] APK package names correct (phone `com.rokid.context`, glasses `com.rokid.context.glasses`), versionCode not a downgrade.
- [ ] Call state 0.
- [ ] The exact backup path is printed (`~/Desktop/rokid/backups/pca-phone-pre-<tag>.tar`) and the install order (backup, phone, glasses).
- [ ] Says "signature check will run with --go" (the dry run does not pull the installed APK).

| Result | Meaning / action |
|---|---|
| Plan printed, exit 0 | Good. Wait for your "go". |
| `REFUSED: ...` (exit 2) | A guard or an uncertainty stopped it; NOTHING changed. Typical causes: equal serials or aliases of one device, serial not in `adb devices`, missing APK, wrong package, downgrade, signing cert differs from the installed one, a call active, adb error. Do not add override flags (`--allow-downgrade`, `--accept-unverified-signature`, `--allow-emulator`). Copy the message to Claude and stop. |

Record the dry-run output into the session notes (section 11).

## 3. Backup + real install: ONLY after you say "go"
User go: [ ] said "go" at ____ for build [ ] CAND [ ] DEFAULT.
- [ ] Optional safety net: start a continuous log capture in a second terminal BEFORE installing: `adb -s <PHONE> logcat -b all -v threadtime -v epoch > ~/Desktop/rokid/backups/logcat-<TAG>.txt` (from INSTALL-TRIP-BUILD.md; stop it with Ctrl+C in that terminal only).
- [ ] Phone not in a call. Do not open PCA on the phone during the backup (the helper force-stops it first).

```
tools/install-trip-build.sh --phone <PHONE> --glasses <GLASSES> --apks ~/Desktop/rokid/backups/apks-v110-cand-18c1b76a --tag <TAG> --go
```
Order enforced by the script: preflight and signature check, phone backup (force-stop proven, `run-as tar c .`, validated: tar listing, db and `db_key.xml` present, size, encrypted header, sha256; saved as `~/Desktop/rokid/backups/pca-phone-pre-<TAG>.tar` with the old phone APK beside it), `install -r` phone, 60 s log scan, then glasses install and its scan.

| Exit | Meaning | Do |
|---|---|---|
| 0 | Installed and verified | Go to section 4. Write the tar path and its sha256 (printed) here: ______ |
| 2 | REFUSED, nothing changed (a later guard, e.g. call, identity change, signature mismatch, backup validation failed, disk) | Do not retry with override flags. If the message says a backup was taken but not validated, do NOT restore from it. Tell Claude. |
| 3 | Installed but NOT verified (log scan FAIL, logs too short, app restarted, chatty/clock issue). For the phone the glasses step is NOT run ("glasses NOT installed"); for the glasses it says "glasses installed but verification FAILED" | Do the manual checks: open the app, section 4 and `adb -s <PHONE> logcat -d \| grep -iE 'room\|sqlcipher\|migration\|not a database'`. Restore from the backup ONLY if the app fails to open its data (section 10). If the phone is fine and glasses were not installed, ask Claude before re-running the helper. |
| other non-zero | An install or verification step failed | Follow the rollback steps the script prints (section 10). Never uninstall the PCA phone app. |

- [ ] Backup tar exists: `ls -l ~/Desktop/rokid/backups/pca-phone-pre-<TAG>.tar` and `shasum -a 256` recorded.

## 4. Post-install check (10 min, read-only)
```
tools/post-install-check.sh --phone <PHONE> --glasses <GLASSES> --apks ~/Desktop/rokid/backups/apks-v110-cand-18c1b76a
```
Exit 0 = no FAIL (read every WARN and UNKNOWN); exit 1 = at least one FAIL; exit 2 = refused/usage. It prints counts, hashes and names only.

| Status | Meaning |
|---|---|
| PASS | Check holds (e.g. installed sha256 equals the staged APK, same pid 10 s apart, no FATAL/ANR/SQLite-Room errors, safety switches OFF). |
| WARN | Needs a human look but is not a failure. |
| FAIL | Stop: do not trust the install (wrong sha, runtime permission missing, crash/migration counts, a safety default ON such as `deliver_hud`, forget_v2, multilingual_ask, deliver_phone, deliver_nexus). Section 10 if the app does not open its data; otherwise send the output to Claude. |
| UNKNOWN | Could not be determined (e.g. no aapt/apksigner, non-debuggable build so `run-as` is not possible). Check by hand. |

Expected WARNs: battery-optimisation exemption missing (only if not granted; on this phone it was granted before, so a WARN here is worth noting); battery below 50%; clock skew above 120 s; free storage below 2 GB. Expected UNKNOWN: glasses link state (not observable read-only: confirm on the phone in section 5).
- [ ] Summary line: PASS __ WARN __ FAIL __ UNKNOWN __ (exit code __). Paste the full output into the session notes.

## 5. First launch on the phone (15 min)
Rules: v17 migration (adds `ask.sourceStates`) runs on first open; there is no downgrade.
- [ ] App opens to Home without wiping: existing asks, memories, photos visible; Activity list counts match what you saw before ("app opens empty": STOP, do not capture, force-stop, section 10).
- [ ] Onboarding does not appear or does not erase anything.
- [ ] Settings > Memory shows encryption still ON.
- [ ] Settings > Diagnostics (turn Developer mode on there): hub state ______, glasses link up? [ ] yes [ ] no, any "restart" lines ______.
- [ ] Settings > Capture schedule: background exemption still granted; indexing battery level unchanged.
- [ ] Permissions: notifications, Bluetooth (nearby devices), location (all the time / precise if asked), camera/microphone if asked. Note any prompt: ______.
- [ ] Ask a question about today: it answers. Ask a "what time did I last ..." question: it states the last state seen with the time.
- [ ] Search a word from an old memory: found.
- [ ] Force-stop and reopen: still opens. (Reboot test: do at the end; Section 11 note whether the hub starts after boot without opening the app: UNVERIFIED on a real phone.)
- [ ] Safety defaults OFF in the UI: Suggestions (all three delivery switches), Place Guide, forgetV2/multi-select Forget, Nexus host = PCA.
- [ ] Activity pagination: scroll the Activity list far down: no crash, rows keep loading. Notes: ______.
- [ ] Log check if anything looks odd: `adb -s <PHONE> logcat -d | grep -iE 'room|sqlcipher|migration|not a database'`.

## 6. Place Guide (30 min; internet needed; leave the switch OFF while seeding)
Settings > Place Guide (Developer mode on).
- [ ] Tap "Seed this area" (Hyderabad preset: Charminar/Golconda/Qutb Shahi Tombs/Salar Jung; 17.34-17.41 N, 78.38-78.52 E). Then "Seed Ramoji Film City (23 Nov)".
- [ ] Read each result line. Meaning: cells ok = good; "seeded without OSM extras" = usable, fewer extras; failed (retried) = tap seed again (retries those cells). A hotel/airport Wi-Fi captive-portal page can make a cell partial/failed: retry on mobile data.

| Seed | cells ok | without OSM extras | failed | places count | rejected=N | coordsDisagree=N |
|---|---|---|---|---|---|---|
| Hyderabad | | | | | | |
| Ramoji | | | | | | |
| Retry (if any) | | | | | | |

- [ ] Expected: `coordsDisagree` above 0 is normal (Wikipedia and Wikidata coordinates differ by more than 1 km: the place is dropped, not shown). Birla Mandir (7.9 km apart) will NOT appear, by design: not a bug. Anything else missing: note which.
- [ ] Check the Charminar / Golconda / Qutb Shahi Tombs / Salar Jung / Ramoji entries exist in the place count or list (UNVERIFIED where the list is shown: look in Settings > Place Guide).
- [ ] Switch Place Guide ON only for the card test below (and only on site on 22 Nov for the trip). Cards show at HUD level MEDIUM/MAX; offline = name + distance "nearby, not checked".
- [ ] "Pin a place here" needs a fresh location fix; pins stay on the phone, never backed up. Test once if outside; delete it afterwards.

### Card position test (candidate build only; Settings > Place Guide > Card position)
Wear the glasses, HUD MEDIUM or MAX, Place Guide ON, stand near a seeded place (or, if indoors, use whatever place card the build can show: UNVERIFIED how to force a card indoors; if none appears, record "no card" and run this at Charminar). Try each position once. Known: side-card body is cut with "..." after about 12 words; side offsets were never tuned on real glasses; the FOV was not measured.

| Position | Card seen? | Left edge cut off? | Right edge cut off? | Title (2 lines) readable? | Body (4 lines) readable, "..." ok? | Source label readable? | Indoor / outdoor sunlight readability (1-5) | Verdict |
|---|---|---|---|---|---|---|---|---|
| LEFT | | | | | | | | |
| CENTRE | | | | | | | | |
| RIGHT | | | | | | | | |

Real field of view (the glasses' lit display area), record in mm or by screenshot:
- [ ] Hold a ruler 30 cm in front of one eye (or a ruled grid) and mark where the card's left and right edges and top/bottom fall: LEFT card: left edge ____ mm, right edge ____ mm; CENTRE: ____ / ____; RIGHT: ____ / ____. Total visible width ____ mm at ____ cm distance.
- [ ] Screenshot/photo through the lens saved as `card-LEFT.jpg`, `card-CENTRE.jpg`, `card-RIGHT.jpg` (phone camera held to the lens is fine).
- [ ] Which position reads best? ______ Is the side card too wide (70%) or too narrow? ______
- [ ] Leave Card position at the one you chose (default CENTRE).

## 7. First worn card test (device-checklist section G; 30 min)
Supervised, synthetic content, stationary, outside any zone, glasses worn, no call. Passing this does NOT turn on everyday delivery (needs 7 worn shadow days and deliberate activation). Candidate build only (delivery code). Enable only the switches the test needs (Settings > Suggestions, Developer mode; three delivery switches: phone, HUD, Nexus; Nexus stays OFF here: UNVERIFIED which of the three you need: HUD for glasses cards).
- [ ] Switches turned ON at ____: [ ] deliver_phone [ ] deliver_hud [ ] deliver_nexus (leave OFF).

| Check | Pass | Notes |
|---|---|---|
| Card readable (title, body, source label) at MEDIUM/MAX; no suggestion card below the allowed verbosity | | |
| Expires within 12 s | | |
| Head-motion dismissal after 2.5 s; popup and minimised overlay both withdrawn | | |
| Refused when unworn | | |
| Refused when paused (double-tap on glasses or Settings > Pause) | | |
| Refused in a zone (see section 8 zone) | | |
| Refused when driving/vehicle uncertain (cannot be forced here: record "not tested") | | |
| Refused when budget exhausted (record "not tested" if not reachable) | | |
| No later backlog after a refusal | | |
| Withdrawn on zone entry, on source Forget, on switch-off (also an in-flight card) | | |
| Quiet-hours boundary ends the card on the glasses clock; unknown offset draws no quiet-bounded card; reconnect with changed offset recomputes | | |
| Dismissal / snooze / never survive an app restart; delivery fails closed when storage fails | | |

Also glasses basics while worn: [ ] app starts, pairs, capture runs; [ ] double-tap pause/resume; [ ] voice mark + undo; [ ] mark photo refused inside a zone; [ ] take them off 10+ minutes: capture stops (not-worn fallback); [ ] case connect/disconnect: hub recovers within a few minutes (Settings > Diagnostics logs) and glasses are not relaunched during a call.

END OF TEST (mandatory):
- [ ] ALL THREE delivery switches turned back OFF at ____. Verify: `tools/post-install-check.sh --phone <PHONE> --glasses <GLASSES> --apks ~/Desktop/rokid/backups/apks-v110-cand-18c1b76a` shows PASS for safety defaults (deliver_phone, deliver_hud, deliver_nexus OFF).
- [ ] Place Guide switched back OFF if you do not want it on until 22 Nov.

## 8. Pause zone checks (10 min)
Settings > Privacy > Pause zones.
- [ ] Add a zone around your own street (typed bounds): chip shows Outside; Home does not show Paused.
- [ ] Add a TINY zone around where you stand: chip Inside; Home shows "Paused: you're in a pause zone"; a glasses mark photo is refused; Place Guide card and suggestion card refused/withdrawn; Ask refused while a zone is approached/enforced (`askZoneFence` ON).
- [ ] Step OUTSIDE the tiny zone (zones cannot be removed while inside; a zone pause cannot be resumed from inside). Chip Outside again; capture resumes.
- [ ] Delete both test zones. Settings > Pause shows nothing paused; Home no longer says Paused.
- [ ] Note APPROACH behaviour and whether screen-off keeps working: ______ (real GPS transitions were emulator-verified only).
- [ ] If a zone is configured and there is no location fix, capture pauses (by design): note if seen.

## 9. Nexus S2 half-day (ONLY if time allows; otherwise move to 7-10 Dec)
What the 2026-11-19 emulator smokes already proved (do not re-check beyond a glance): real hub v2.6.0 debug on the emulator: discovery and Pending, step 3 (untick three, Allow, "Approved"), skills catalog accepted (no "turned off"), host switch to Nexus enabled and "Waiting for Nexus", history wipe on host-off answered OK, hub restart re-registers approved with no prompt, bridge uninstall (shows "not installed" only from build 5bd9fc6f+ fix 183ee884: the candidate has it). Release build, signing form and trust decision with throwaway keys (nexus-bridge-release-smoke-2026-11-19.md). NOT yet seen: any real HUD publish, the hub's answer to a real publish (NOT_GRANTED / CAPABILITY_NOT_AVAILABLE / NOT_REGISTERED in the first ~2 s after hub restart), any rendering, per-operation skills consent (needs the Assistant plugin), speech. The S1.5b file `nexus-publish-real-hub-2026-11-19.md` did NOT exist when this runbook was written: if it exists now, read it first.

GO/NO-GO before starting: [ ] sections 0-5 clean; [ ] at least 45 min left; [ ] backup tar from section 3 still present; [ ] glasses > 50%. If any is NO, skip to section 11.

Setup (once):
- [ ] Rokid-Coder hub and glasses app closed. Coexistence note: Rokid-Coder (Nexus fork) and PCA do NOT run together (one CXR/bus session per phone). Record which side drops whenever both are up.
- [ ] Nexus phone hub (`com.anezium.rokidbus.phone`, upstream v2.6.0 or newer) and its glasses app installed per upstream instructions (UNVERIFIED here: no command given in the checklist; see `nexus-install-uninstall.md`). Install only with `adb -s <PHONE>`.
- [ ] `nexus-bridge-debug.apk` (from `<CAND>`; no launcher icon, find under Settings > Apps) installed on the phone, bridge signed by the same key as the PCA phone app (a mismatch shows "untrusted bridge"). Exact install command: `adb -s <PHONE> install -r <CAND>/nexus-bridge-debug.apk` (form assumed from the helper's `install -r` rule: UNVERIFIED, not scripted).
- [ ] In the Nexus hub approve the PCA bridge plugin. For least privilege untick Speech to text, Text to speech and Replace the glasses assistant BEFORE tapping "Allow on glasses" (the hub shows five toggles; "Offer skills" is harmless). PCA Settings > Rokid Nexus (needs Developer mode) should read "approved".

Steps and what to record:

| Step | Do | Record | Stop/go |
|---|---|---|---|
| S2.4 | PCA link up (capture running), then start the Nexus hub link | Exactly what happens: PCA drops / hub drops / both work. Which side dropped: ______ | STOP and note if both cannot hold the session; do not proceed to 5-8 beyond a glance. This is the key result (a failed S2 blocks 1.5 to 2.0; Option B is shelved, upstream BUSSPEC keeps release hubs closed to third-party glasses apps). |
| S2.5 | Hub first, then PCA connects | Same record | Same |
| S2.6 | Disconnect/reconnect each side | Does the other recover without a manual restart? Watch PCA hub-recover and watchdog logs (the hub restart fix must not fight the Nexus hub) | Go on only if no manual restart is needed or you note exactly what was needed |
| S2.7 | A PCA place card and a Nexus notice at the same time; double-tap pause/resume; capture camera not blocked | Layering, gesture, camera result | Go |
| S2.8 | PCA HUD host = "Nexus": PCA popups stop (nudge, answer, place card), cards appear once via Nexus; switch back: everything returns once, nothing doubled | Doubling? Missing? | Go. Switch the host back to PCA at the end: [ ] done |

If time remains (the 9-16 steps, each stands alone; skip freely):
- [ ] S2.9 Nudge, place card, mark confirmation, capture-state pin on the Nexus HUD with expected text (at most 1024 chars), Sources footer on answers.
- [ ] S2.10 Withdraw: Forget a photo whose card shows, Delete all, pause (phone and glasses), enter a zone, switch the host off: each hides the card within about one tick and wipes the Nexus notification history (hub keeps notices up to 24 h: confirm the history screen is empty). Kill the PCA process while a card shows: the bridge hides it. (Do NOT kill anything on the Mac; use the phone's force-stop, not any pkill.) Hung-hub residual: note any stall longer than 2 s and how long the hub took to unblock.
- [ ] S2.11 Untrusted bridge (needs a bridge signed with another key): skip unless prepared.
- [ ] S2.12-14 (S3) Assist button speech English and Hinglish, TTS only to glasses/earbuds, 35-minute battery/heat run (glasses temperature, phone battery drop): normally the 7-10 Dec half-day.
- [ ] S2.15 skills status / pause_capture / resume_capture (refuses inside a zone) / mark_moment (refuses in zone/not worn); none returns memory text.
- [ ] S2.16 Release signing and publish: NOT for tonight (real key after Sri Lanka).

After S2: [ ] remove or disable the Nexus bridge/hub if you want the pure PCA trip state (uninstall steps for the hub/bridge only: `nexus-install-uninstall.md`; NEVER uninstall the PCA phone app); [ ] host switch back to PCA; [ ] closed the Nexus hub before restarting Rokid-Coder or running PCA capture on 22 Nov.

## 10. Rollback (only if section 3 verification or section 5 fails)
Hard rules: never uninstall the PCA phone app, never "Clear data" / `pm clear`, never rotate or delete the Keystore key; `adb install -r` of the SAME-signature APK only; not during a call. A v16 binary cannot open a v17 database: reinstalling the old APK is not a rollback by itself; the rollback is restore-from-backup, then reinstall. The glasses can be rolled back by `install -r` of `~/Desktop/rokid/backups/apks-v100*` (1.0.0) (command form UNVERIFIED, no script path).
1. [ ] Stop the app and confirm it is down:
   ```
   adb -s <PHONE> shell am force-stop com.rokid.context
   adb -s <PHONE> shell pidof com.rokid.context
   ```
   (`pidof` must print nothing; if it prints a pid, force-stop again.)
2. [ ] PRESERVE THE BROKEN STATE FIRST:
   ```
   adb -s <PHONE> exec-out run-as com.rokid.context tar c . > ~/Desktop/rokid/backups/pca-phone-broken-<TAG>.tar
   ```
   Validate: `tar tf ~/Desktop/rokid/backups/pca-phone-broken-<TAG>.tar > /dev/null && echo TAR_OK`, then `tar tvf ... | grep -E 'databases/context\.db|shared_prefs/db_key\.xml'` lists both, size is not a few KB. If it cannot be taken or does not validate: STOP, replace nothing, ask for help.
   On-device copy:
   ```
   adb -s <PHONE> shell run-as com.rokid.context sh -c 'mkdir -p quarantine-<TAG> && cp -p databases/context.db* quarantine-<TAG>/ && cp -p shared_prefs/db_key.xml quarantine-<TAG>/'
   ```
   and check `ls -l` lists the same files and sizes.
3. [ ] Move (never delete) the broken files aside:
   ```
   adb -s <PHONE> shell run-as com.rokid.context sh -c 'mkdir -p quarantine-<TAG>/replaced && for f in databases/context.db databases/context.db-wal databases/context.db-shm; do [ -e $f ] && mv $f quarantine-<TAG>/replaced/; done; true'
   ```
4. [ ] Restore the validated pre-install backup, then reinstall the previous APK (same signature; the previous phone APK was saved next to the backup tar by the helper):
   ```
   cat ~/Desktop/rokid/backups/pca-phone-pre-<TAG>.tar | adb -s <PHONE> shell run-as com.rokid.context tar x
   adb -s <PHONE> install -r <previous.apk>
   ```
   Open the app and re-run the section 5 checks (opens with data, no database error in the log, search finds an old memory, force-stop + reopen).
5. [ ] If the restore does not open either: STOP. Delete nothing else, rotate no keys. The broken-state tar, the quarantine folder and the pre-install backup are everything needed; `mv` quarantined files back if asked; ask Claude.
- "App opens empty": do not use or capture (a fresh DB would be created under a new key); force-stop and follow steps 1-5.
- "Keystore invalidated" (screen lock/security change): the database is unreadable by design; the backup restores the file but not the master key. Do not change lock screen or credentials around the install.

## 11. What to send back to Claude
Collect into one folder: `mkdir -p ~/Desktop/rokid/backups/session-20261119 && cd ~/Desktop/rokid/backups/session-20261119`.
- [ ] Dry-run output, install output with its exit code, and post-install-check output (copy from the terminal or run the commands with `| tee dryrun.txt` / `install.txt` / `postcheck.txt`: `tee` is UNVERIFIED but plain shell).
- [ ] Backup tar path and sha256: `shasum -a 256 ~/Desktop/rokid/backups/pca-phone-pre-<TAG>.tar`.
- [ ] Phone and glasses version info: `adb -s <PHONE> shell dumpsys package com.rokid.context | grep -E 'versionName|versionCode|signatures|Signing'` and `adb -s <GLASSES> shell dumpsys package com.rokid.context.glasses | grep -E 'versionName|versionCode'`.
- [ ] Log snapshot (phone, then glasses): `adb -s <PHONE> logcat -d -b all -v threadtime -v epoch > phone-logcat.txt` and `adb -s <GLASSES> logcat -d -b all -v threadtime -v epoch > glasses-logcat.txt` (`-d`, `-b all`, `-v threadtime -v epoch` taken from the helper's own logcat usage; contents may include private text, look before sending). If the continuous capture of section 3 ran, send `~/Desktop/rokid/backups/logcat-<TAG>.txt` instead.
- [ ] Migration/crash grep: `adb -s <PHONE> logcat -d | grep -iE 'room|sqlcipher|migration|not a database'`.
- [ ] Screenshots (phone): Settings > Diagnostics, Settings > Place Guide result lines (both seeds), Pause zones screen, Home with and without Paused. Screen capture command UNVERIFIED: `adb -s <PHONE> exec-out screencap -p > diagnostics.png`. Photos through the lens: `card-LEFT/CENTRE/RIGHT.jpg`.
- [ ] The filled tables in sections 5-9 of this file (save a copy: `cp` this runbook to `~/Desktop/rokid/backups/session-20261119/`).
- [ ] Answers: build installed (CAND/DEFAULT), exit codes, any REFUSED text, which side dropped in S2, card position verdict and FOV mm, whether the hub started after a reboot without opening the app, battery/heat if observed, anything surprising.
- [ ] Leave state: Place Guide ON/OFF ____, all three delivery switches OFF, no test zones left, Nexus host = PCA, Rokid-Coder untouched (restart it only after PCA checks are done and note that PCA must be closed first for any Rokid-Coder use and vice versa).
- [ ] Not decided tonight (rulings from needs-user): nightly photo-describe job zone fence (a/b/c, default a), Forget F2/forgetV2/Room v18, multilingual Ask.

## 12. After the trip: how stable was the link? (read-only, 1 minute)
`tools/link-health-report.sh --phone <PHONE> --date <MM-DD>` per trip day. Look for hours with many `sess-term`/`connecting` together with a long `max-gap-s`; note what else was active then (Rokid-Coder's Monitor sessions, calls). Send the table to Claude: it decides whether any link arbitration is worth building.

## 13. New-feature tests (worn / on device), 2026-11-20. Every item: **device needed**
Before you start: Settings > Diagnostics > **Detailed log ON** (default), so every line below reaches `pca.log` and logcat. Live view: `adb -s <PHONE> logcat -v time | grep -E 'PcPlace|PcData|PcPrivacy|OfflineOnly|OnlineTiles|PcDiag'` (the logcat may hold private text from other apps: do not send it unread). Results go in the session notes.

### 13a. Pin editor (both builds). Settings > Place Guide > Your pinned places
- [ ] **New pin requires a Moment**: "Pin a place here" (fresh fix needed). The editor offers "Attach latest Moment" (latest within 30 min) or "Pick from recent Moments"; with no Moment it says "No Moment in the last 30 minutes" / "No Moment yet: capture one with the glasses first".
- [ ] **Day picker**: "Pick from recent Moments" > pick a day > list of that day's Moments ("No Moments on this day." on an empty day); pick one, Save. The row shows "Moment <time>".
- [ ] **Save without a Moment**: tap it; a confirmation "Save without a Moment?" appears; confirm. The pin is saved and shows **"Needs a Moment"** ("No reference Moment. The pin still works by location.").
- [ ] **Existing pins** (made before this build): show "Needs a Moment" and still match by location. Attach their Moments with the day picker (needs-user item 2: the approximate times of the 3 home pins).
- [ ] **Forget dialog names pins**: Forget a TEST Moment that a TEST pin uses. The dialog names the pin and the button reads "Forget Moment and pin(s)". Cancel once (nothing changes), then confirm: the Moment and the pin are gone. Log: `PcPlace pin deleted with its forgotten moment count=1`.
- [ ] **Expiry only de-links**: not forceable on a real phone in one session; note "not tested". (Log when it happens: `pin moment link cleared by removal count=N`.)
- [ ] **Delete all dialog names pins**: ONLY on a throwaway state, never on trip data: read the Settings > Memory > Delete all confirmation and check it says "also deletes N pins" with the names; then **Cancel**. Do not confirm Delete all on the real phone.
- [ ] Look for in the log: any `PcPlace pin ... failed`, `pin arming not restored`, `pin removal could not be recorded` (W lines): send them to Claude.

### 13b. Trip log (both builds)
- [ ] Settings > Diagnostics > **Detailed log** switch is ON by default; text says up to about 300 MB, never uploaded. Turn it off and on once; no crash.
- [ ] After a short Place Guide walk (or on the trip), read the logs on the Mac (debuggable build, read-only):
  - `tools/place-attempt-report.sh --phone <PHONE> --date <MM-DD>` (or `--since "MM-DD HH:MM"`; `--pin-map <file>` prints your own labels next to ids on the laptop only). Look for: outcomes and reasons (`nearby_card`, `data_saver`, `offline_only_no_local_model`, `call_failed_genuine`, `same_place_recently`), call timings, confidence, how many attempts made a call.
  - `tools/link-health-report.sh --phone <PHONE> --date <MM-DD>` (section 12).
- [ ] `PcDiag` lines present: `app start`, `battery bucket=`, `network online=... metered=... roaming=...`, `heartbeat` once an hour, `permission state` at start.
- [ ] Note the size of `files/logs` after a full day (`adb -s <PHONE> shell run-as com.rokid.context du -sh files/logs`: command form UNVERIFIED) and the battery drop with Detailed log on.

### 13c. Street map (both builds). Settings > Privacy > Pause zones
- [ ] Off by default: the zones map shows the grid.
- [ ] Turn on "Show a street map": the first-use explanation appears with **Cancel as default**; Cancel leaves it off. Turn on again and accept.
- [ ] **Provider**: the explanation and the map attribution name the provider really used. No MapTiler key was configured for the staged builds (UNVERIFIED), so expect "OpenStreetMap" (debug build without key). To get MapTiler, a key must be put in `android-companion/local.properties` (`PCA_MAP_TILES_KEY=...`) and the APK rebuilt: needs-user item 7.
- [ ] Pan and zoom: tiles load; "Centre on me" moves to your position (one fix on tap). Leave the screen: requests stop.
- [ ] Turn the switch off: the map goes back to the grid (the tile cache is deleted).
- [ ] Log: `OnlineTiles` lines only on problems (`backing off`, `session cap reached`, `refused a redirect`); never a tile number or URL.

### 13d. Data saver (TRAIN only). Settings > Data saver
- [ ] Default mode reads "Wi-Fi and home mobile data (default)"; "Small requests on roaming" ON; "I'm abroad" OFF.
- [ ] **Status line** on each network (write down the exact "Now: ..." text): home Wi-Fi ("Photos upload normally"); home mobile data, Wi-Fi off ("Now: Home mobile data", photos upload); **confirm home mobile data still describes photos**: capture a few frames on mobile data and check they get captions.
- [ ] **"I'm abroad" ON** on home mobile data: "Mobile data, treated as roaming (I'm abroad)", "Photos wait for Wi-Fi"; capture a few frames: "Photos waiting for Wi-Fi now: N" rises, "Blocked today" counts rise; Place Guide shows the nearby card. Back on Wi-Fi: the waiting photos are described. Turn "I'm abroad" OFF again.
- [ ] **Wi-Fi only** mode on mobile data: photos wait. Switch back to the default.
- [ ] **Android Data Saver** (Android Settings > Connections > Data usage > Data saver; Samsung menu path UNVERIFIED) ON while on mobile data: "Android Data Saver is on for this app"; OFF again.
- [ ] **VPN** on over Wi-Fi: still free Wi-Fi (the Wi-Fi underneath is judged). VPN on over mobile data: home mobile data. Note what you see.
- [ ] **Hotspot**: phone on another phone's hotspot (metered Wi-Fi): reads as home mobile data (or roaming with "I'm abroad").
- [ ] **Captive portal** (hotel/airport Wi-Fi before login): "Unknown network, treated as roaming"; after 10 min "Wi-Fi without an internet check for over 10 minutes". Seeding refused.
- [ ] **Airplane mode**: "Now: Offline", "Nothing can leave the phone".
- [ ] **Seed** on mobile data: refused ("seed refused: not free wifi" in the log); on home Wi-Fi it works.
- [ ] Log: `PcData net cost=FREE|HOME_MOBILE|ROAMING|UNKNOWN|RESTRICTED|OFFLINE`, `PcData blocked kind=PHOTO what=UPLOAD_QUEUED reason=roaming`, `photo uploads open`. A `network watch unavailable` W line: send to Claude.
- [ ] Real roaming cannot be tested at home: switch "I'm abroad" ON before Sri Lanka.

### 13e. Model privacy (TRAIN only). Settings > Capture & privacy > Model privacy
- [ ] Default **Automatic**. First open may show "Loading…" briefly, never a wrong mode.
- [ ] Switch to **Offline only**: "Saving…" then the explanation; no "Couldn't save this choice". Force-stop and reopen: still Offline only.
- [ ] Under Offline only: ask a question (refused or answered only by an on-device/home model; note the wording); capture frames (they wait, not counted as "Blocked today"); Place Guide at a seeded place shows the **nearby card** ("Place Guide: needs an on-device model" on the screen); "Seed this area" refused; street map shows no new tiles; endpoint test of a cloud endpoint refused.
- [ ] Switch back to **Automatic**: waiting photos are described; Place Guide matches again.
- [ ] **'Reset' notice**: not forceable without editing app files; note "not tested" (it reads "Model privacy settings were reset: check them").
- [ ] **Nothing leaves the phone** (optional, laptop): a proxy test proves little here, because Offline only ignores the system proxy by design. Better: put the phone on the Mac's Internet Sharing Wi-Fi and capture on the Mac (`sudo tcpdump -i bridge100 -n` : interface name UNVERIFIED), then under Offline only ask, capture and open Place Guide: there must be no connection to your cloud endpoint's address or to Wikipedia/Overpass/tile hosts. Other apps' traffic will show too; filter by those addresses. Do the same under Automatic as the baseline.
- [ ] Log: `PcPlace place attempt ... mode=OFFLINE_ONLY backend=none reason=offline_only_no_local_model call=skipped_offline_only`; `OfflineOnly offline_only_refused` (and `_dns`, `_route` if a name or route was refused); `PcData seed refused: offline only`; any `PcPrivacy privacy write failed`: send to Claude.
- [ ] Leave the mode on **Automatic** for the trip unless you decide otherwise.

### 13f. Send back
The filled 13a-13e boxes, the two report outputs, the exact status-line texts, and any W/E lines from the tags above.
