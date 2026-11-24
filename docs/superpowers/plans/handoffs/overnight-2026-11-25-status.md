# Overnight status (resume section first) - UPDATED 2026-11-24 17:45 IST

## STATE NOW (supersedes the 15:40 block below)
- 21:55 UPDATE (LATEST): branch tip 6a78899e = TRAIN20, verified 15/15 rc=0 (verify-final.txt start 21:27). NOT staged as an APK folder (disk free 1.9 GB): its APKs are in android-companion/{phone,glasses,nexus-bridge}/build/outputs/apk/debug/ right now (copy to ~/Desktop/rokid/backups/apks-v110-train20-6a78899e after the user frees disk; shasum + README like train19). Staged and verified folders: train16-bb49cb5b (RECOMMENDED for the Thursday 00:50 data protection), train17, train18, train19-65e1e34f. Data saver Codex rounds r1-r4 done: remaining design-level limits (FREE->costly flip between samples without per-request network binding; LAN exemption classified from the URL) are in open-backlog-2026-11-21.md 'Data saver known limits after Codex r4'. Roaming mitigation: 'I'm abroad' on + Wi-Fi only for photos. Install plan: train16 tonight (dry run first, --go on the user's go), train20 before Sikkim (1 Dec 05:00) after a home roaming dry run. BLOCKERS (user): disk space (rm command for 38 old apks dirs + later train16-18), devices reconnected, the go.
- 19:50 UPDATE: LATEST FULL BUILD = train17 (git aaca794a main code = tip 6137b37c), staged ~/Desktop/rokid/backups/apks-v110-train17-aaca794a (README, SHA256SUMS): 14/15 tasks rc=0 in verify-final.txt (start 19:12) + orchestration rc=0 on 6137b37c after a one-line test classification. Adds data saver fixes (Codex data saver review, 7 P1), installer watch-APK guard (247 fixtures), shadow-off fence, suspect sweep recheck. Codex data saver r2 re-check running (codex-datasaver-r2.md). Disk free 3.9 GB: install backup needs ~1.7 GB; the user must free space (rm list given). Install of train17 (or 16) still waits for the user's go, devices reconnected; deadline Thu 26 Nov 00:50 for the retention protection.
- 18:20 UPDATE: Codex re-check of train16 (codex-train16-recheck.md): mark/mp/map earlier P1s CLOSED; 5 new findings: #1 mark_cleanup_owed not wiped (FIXED on the branch in 00b07719, not in train16 APKs; trivial hygiene), #2-#5 false-DONE wording gaps for photo-file cleanup (pre-existing; backlog P2). Emulator rehearsal of train16 BLOCKED: Mac disk 97% full (6.6 GB free; emulator needs ~7.2 GB; install backup needs ~1.6 GB): the user must free space (rm list of 38 superseded apks-v110-* dirs given in chat). adb devices showed no phone/glasses at 18:00. Install of train16 still needs: disk space, devices reconnected, the user's explicit go. Branch tip after mark-r4 = train16 + 00b07719 (a 'train17' build is only needed if the user wants the hygiene fix; verification needs disk space first).
- LATEST FULL BUILD: train16 at git bb49cb5b, staged at ~/Desktop/rokid/backups/apks-v110-train16-bb49cb5b (README, SHA256SUMS), verified 15/15 rc=0 (verify-final.txt start 17:17). NOT INSTALLED. train14/15 are superseded. Branch feat/v101-ask-eval tip = bb49cb5b + docs.
- The user's phone (adb-RZCY902F76K-nmSM2g._adb-tls-connect._tcp) and glasses (1901092545029120, USB) were both connected at 16:35; installer dry run (train14) clean. WAITING FOR THE USER'S EXPLICIT "go" to run tools/install-trip-build.sh --go with train16 (or 15). DEADLINE: before Thu 26 Nov 00:50 IST. Trip Tue 1 Dec 05:00.
- In flight at 17:45: Codex re-check of mark r3 / mp r3 / map P1 r2 (docs/superpowers/reviews/2026-11/codex-train16-recheck.md); emulator rehearsal of train16 (agent a12792d5f5a24d040, doc handoffs/emulator-smoke-train16-2026-11-24.md, touches scratchpad/emu_done when done).
- Remaining queue: card history into context.db (Room v18, design 2026-11-24-card-history-in-context-db-design.md; bundle moment_location; after Sikkim), GPS backfill tool tools/replay/backfill_gps.py (secondary), retention v3 Auto-delete (K4, later), seed-area names (places.db v2 names, R9), map offline tile download (MapTiler terms R1), FallbackParkCap persistence, glasses-app-closes backlog, Codex units never re-reviewed (shadow/siglip/diag, ds1-3, tools).
- The laptop slept 06:32-12:03 on 24 Nov (battery); keep it on power.

# (older block) UPDATED 2026-11-24 15:40 IST

## STATE NOW
- Branch feat/v101-ask-eval tip cdef4c7e (+ docs): full verification 15/15 rc=0 (verify-final.txt start 15:12). Candidate staged (NOT INSTALLED): ~/Desktop/rokid/backups/apks-v110-train13-cdef4c7e (README, SHA256SUMS). Installed on the user's phone: 18c1b76a (19 Nov) with retention settings changed by the user/me to 48h/14d(medium)/photos Forever/long 365/cap 2000 MB.
- DEADLINE: install the candidate BEFORE Thu 26 Nov 00:50 IST (the old build's nightly run expires 23 Nov facts and drops Moments). The user needs to say go; phone connected; installer tools/install-trip-build.sh dry run first, --go only on the user's explicit go. Trip: Tue 1 Dec 05:00 (Sikkim/Darjeeling 1-6 Dec); the old '26 Nov install freeze' is dropped (Andaman plan).
- In flight: emulator rehearsal agent (ab5be69f979617627, branch emu13, result doc handoffs/emulator-smoke-train13-2026-11-24.md); Codex place r4 re-check (docs/superpowers/reviews/2026-11/codex-place-fixes-r4.md).
- Laptop slept 06:32-12:03 (battery): nothing ran then. Keep it on power.
- Accepted residuals (documented, not fixed): retry-supersede deleting trailing facts (pre-existing in 18c1b76a, backlog P2); leftover JPEG sweep under Pause (rows already deleted by the old build).
- Older Codex units still NOT READY and unfixed: mark (2 P1), mp/Offline-only (2 P1; feature not used), see open-backlog-2026-11-21.md.
- Next: read emulator + Codex r4 results; fix any P1 (Sonnet in worktree, run_wt.sh lock); rebuild/stage train14 if code changes; update ROADMAP rows; ask the user for the install go (before Wed night).

## (older notes below)

Goal (user): fixes planned, overnight, maps + pins + v2 groundwork, fully tested; emulator if no device; Opus for design/plan/RCA, Sonnet for build/test, Codex sol for reviews. 8 GB Mac: ONE Gradle job at a time (run_wt.sh lock in the session scratchpad), never the emulator while Gradle runs. No install on the phone without the user's explicit go.

## On feat/v101-ask-eval now (tip after b40dc2f9 + docs)
pin fixes r1-r3, partial-cell backoff, hub auto-start (acl/watch), retention hotfix (9ff8c9f0, 9b3c7184, 01e5ee87, bcd0490c), replay harness (4c02167a..2473c338), map zones P0 (1e34ab93, 64560bfb).
Verification of b40dc2f9: 13 of 15 rc=0 at 05:35 (phone tests + assemble still running).

## Codex verdicts (docs/superpowers/reviews/2026-11/)
- codex-pin-r2-r1: fixed in b40dc2f9 (needs a Codex r2 re-check).
- codex-retention-r1: NOT READY, 6 P1 (photo expiry/legacy/carried/lineage/suggestions/repair overwrite) -> agent branch ret-p1 in progress; candidate must ship with Pause clean-up ON by default until ret-p1 is merged and re-reviewed.
- codex-map-p0-r1: NOT READY, 1 P1 (seed path re-check per request) -> agent branch map-p0-fix.
- codex-mark-r1 / codex-mp-r1: NOT READY (old trip-build units), logged in open-backlog-2026-11-21.md, not fixed yet.

## Agents (worktrees under .claude/worktrees)
Track A place-fixes-A (sampler F1-F4, F7), Track B place-fixes-B (card history, places.db v2), Track C place-fixes-C (extract prompt signage), ret-p1, map-p0-fix. Merge each by cherry-pick onto feat/v101-ask-eval after its report; then full verification (verify-final.sh in the scratchpad, recreate from this list if gone: core:model, core:hud, core:capture, core:ui, services:ai, memory, connectors, glasses-link, voice, orchestration, nexus-port, nexus-bridge, glasses, phone tests + assembleDebug phone/glasses/nexus-bridge); Codex review of every unit; Opus reviewer only if Codex quota is out.

## Deadlines and asks for the user
- Retention: the 26 Nov ~00:50 run will drop 23 Nov facts/Moments on the phone unless a candidate with the fix (or Pause clean-up ON) is installed before it. Needs the user's install go.
- Photo retention on the phone was set by me to forever / long-term 365 / cap 2000 MB (user accepted).
- Open rulings: R1-R8 retention design, R1-R11 map design, place-fixes open points (defaults taken).
- Rokid-Coder: hands off.

## Next steps after the agents report
1. merge + verify + Codex review; 2. build candidate APKs (release signing as trip builds; see tools/install-trip-build.sh) with Pause clean-up default ON; 3. emulator rehearsal (Gradle idle first): upgrade over the installed 18c1b76a with the dump's databases, check places.db v2 migration, retention paused log line, hub auto-start logs; 4. stage in ~/Desktop/rokid/backups/ with README + SHA256SUMS; 5. update ROADMAP.md (v1.4.x rows: map zones P0, hub auto-start, retention fixes, place fixes; v1.9/v2 backlog: glasses app closes on glasses ~10% capture loss, P2 retention re-pointing, Moment location field Room v18).

**Card history decision (train14):** place card history is OFF by default in train14 (Developer flag `placeCardHistory`, default false, also false on a prefs error; no row is written, the Place Guide list and Home "Last place card" are hidden, "Show on glasses again" is disabled). Every Forget / Delete all deletion path still runs regardless of the flag, so rows from earlier runs are deleted. Redesign: move place_card_log into context.db (Room v18, additive) so Forget deletes it in the same transaction, removing the owed-obligation machinery (see reviews/2026-11/codex-place-fixes-r4.md).
