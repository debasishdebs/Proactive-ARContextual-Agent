# Needs the user (skipped on purpose, recorded 2026-11-19 while the user was away ~1.5 h)
Everything below was NOT done because it needs the user, the real devices, or a ruling. Work continued on everything else.

## A. Install (blocks all device checks)
- [ ] Plug in phone + glasses (adb), say which build: DEFAULT apks-v110-f1dcf1fb or CANDIDATE apks-v110-cand-55fa38eb (README in each folder). Then: dry run (tools/install-trip-build.sh), then an explicit "go". Never auto-installed.

## B. Rulings (proxy answers exist: decisions/2026-11-19-codex-proxy-rulings-4.md; confirm or change)
- [ ] Forget F2 (cited photo tombstone: keep), forgetV2 (OFF through both trips), Room v18 (stay on v17), v3.1 delivery (supervised first worn test only).
- [ ] Multilingual retrieval: design specs/2026-11-19-multilingual-retrieval-design.md: turn the query condenser on in default Ask as v1.3.1 after the 26 Nov freeze? (also needs: condense endpoint/privacy, who curates a lexicon, pass bar).
- [ ] Nexus Option B transport: shelve unless S2 fails (specs/2026-11-19-nexus-option-b-transport-design.md); run the 0.25 day B0 desk check now?

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
