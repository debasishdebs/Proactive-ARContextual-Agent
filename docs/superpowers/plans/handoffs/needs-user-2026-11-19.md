# Needs the user (skipped on purpose, recorded 2026-11-19 while the user was away ~1.5 h)
Everything below was NOT done because it needs the user, the real devices, or a ruling. Work continued on everything else.

## A. Install (blocks all device checks)
- [ ] Plug in phone + glasses (adb), say which build: DEFAULT apks-v110-f1dcf1fb or CANDIDATE apks-v110-cand-aedb2d5f (README in each folder). Then: dry run (tools/install-trip-build.sh), then an explicit "go". Never auto-installed.

## B. Rulings (proxy answers exist: decisions/2026-11-19-codex-proxy-rulings-4.md; confirm or change)
- [ ] Forget F2 (cited photo tombstone: keep), forgetV2 (OFF through both trips), Room v18 (stay on v17), v3.1 delivery (supervised first worn test only).
- [ ] Multilingual retrieval: design specs/2026-11-19-multilingual-retrieval-design.md: turn the query condenser on in default Ask as v1.3.1 after the 26 Nov freeze? (also needs: condense endpoint/privacy, who curates a lexicon, pass bar).
- [x] Nexus Option B transport: B0 desk check DONE 2026-11-19: NOT possible as documented (upstream BUSSPEC L260-264 keeps release glasses hubs closed to third-party glasses apps until provisioning exists): shelve Option B, skip the B1 spike; reopen only if S2 fails AND upstream ships provisioning (specs/2026-11-19-nexus-option-b-transport-design.md section 6).

## C. Hardware / worn checks
- [ ] First worn card test (device-checklist-2026-11-17.md section G), incl. side-card position LEFT/CENTRE/RIGHT and clipping/FOV.
- [ ] Nexus S2/S3 half-day (7-10 Dec proposed).
- [ ] Background hub start from boot/Bluetooth, real GPS zone transitions, battery/heat, encrypted DB upgrade on the real phone, Activity pagination on the phone (laptop numbers in reviews/2026-11/activity-pagination-timing-2026-11-19.md), Overpass reachability on the phone network, hotel/airport Wi-Fi captive portals.
- [ ] Shadow week: after 7 worn days read plans/handoffs/shadow-week-eval-guide.md.

## D. Chores
- [ ] GitHub main move to v1.x (ask first); rotate credentials hard-coded in the kg repo; create the GitHub Environment `release` before any CI signing; real key rotation after Sri Lanka.
- [ ] ~/Desktop/rokid/backups holds ~23 GB (old APK folders marked STALE/SUPERSEDED, emulator backups); disk was at 100% earlier. Delete what you do not need.

## E. Codex quota (found 2026-11-19 13:25)
- The Codex weekly usage limit is reached; it resets 21 Nov 2026 21:36. Reviews done by Codex this session: all up to the batch review (codex-batch-2026-11-19.md, READY). NOT yet Codex-reviewed: the Hyderabad coordinate rule (9d69e05a) and the 7 new landmark ids (b07969ad). An independent Claude review (not Codex) was run instead: scratchpad/review-hyd-claude.md (result recorded in the handoff). Re-run Codex on `git diff 0b71d96b..9d69e05a` after 21 Nov 21:36.

## F. Rokid-Coder on the same glasses/phone (noted 2026-11-19 15:58, user is installing rc.12)
- Rokid-Coder (Nexus fork) and PCA do NOT run together: one CXR/bus session per phone (nexus-device-checklist.md Setup step 1, BUSSPEC). Before a PCA capture day (22 Nov): close/disable the Rokid-Coder hub and its glasses app first, check Settings > Diagnostics in PCA shows the glasses link up, and note whether PCA's link drops when the Rokid-Coder hub starts. This is exactly the S2 coexistence question, so what you see is useful: write down which side drops.
- Install order for the trip: PCA phone + glasses via tools/install-trip-build.sh (dry run first); do NOT use the Rokid-Coder installer for PCA packages. Both apps are separate packages (PCA: com.rokid.context / com.rokid.context.glasses).
- 2026-11-19 15:57: glasses visible on adb as 192.168.68.105:5555 but OFFLINE; phone not listed. The install dry run needs both online.

## G. RULING NEEDED: should the nightly photo-describe job honour pause zones? (found 2026-11-19, Opus review of the S1/S2 zone fixes)
- Fact: NightIndexWorker (nightly describe/index of already-captured photos, cloud vision calls) has NO zone check (predates the S1/S2 fixes). The overnight suspect sweep NOW does: it blocks whenever zones are configured and the hub has not judged you outside every zone and approach band.
- If I add the same check to NightIndexWorker: strict privacy (no upload while you are in or near a zone) BUT if home is a pause zone, the nightly indexing would never run while charging at home, and would not run in a process where the hub never started. If I leave it: photos captured outside a zone can be described (uploaded) later while you are inside a zone (e.g. at night at home).
- Options: (a) keep as is (no zone check on the night job); (b) fence it like the sweep (strict; likely stops indexing at home); (c) fence it but only block while a zone is INSIDE/APPROACH is judged (not for cold start), i.e. allow when the hub has not run. Default until you rule: (a), unchanged behaviour.
- Also noted: the suspect sweep (marked suspects, Diagnostics) now runs only on nights the hub is up and has judged you outside every zone; removals needing no model call still run every night.
