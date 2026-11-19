# Needs the user (skipped on purpose, recorded 2026-11-19 while the user was away ~1.5 h)
Everything below was NOT done because it needs the user, the real devices, or a ruling. Work continued on everything else.

## A. Install (blocks all device checks)
- [ ] Plug in phone + glasses (adb), say which build: DEFAULT apks-v110-f1dcf1fb or CANDIDATE apks-v110-cand-18c1b76a (README in each folder). Then: dry run (tools/install-trip-build.sh), then an explicit "go". Never auto-installed.

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

## H. v2.0 release: the steps only you can do (device-free prep is finished: docs/superpowers/plans/2026-11-19-path-to-v2.0.md)
Done and verified WITHOUT devices: Nexus code T1-T8 reviewed (Codex v2 r6 READY); bridge release build, signing form and trust decision on the emulator with throwaway keys (reviews/2026-11/nexus-bridge-release-smoke-2026-11-19.md); CI signing form rehearsed; install/uninstall procedure and release-notes draft written.
Left, in order:
1. [ ] S2 half-day with the real phone + glasses (nexus-device-checklist.md steps 4-8): do PCA's link and the Nexus hub link coexist? Option B is shelved, so a failed S2 blocks 1.5 to 2.0 (decide then).
2. [ ] S3 (speech quality, battery, heat; steps 12-14), cards rendering on the glasses (steps 9-11), skills (step 15).
3. [ ] After Sri Lanka: create the REAL signing key + lineage (docs: release/README.md, key-rotation-rehearsal), put its digest in release/signer-sha256.txt (the committed pin is a placeholder: CI fails on it on purpose) and in PcaSignerTrust.PINNED (empty today), create the GitHub Environment `release` with required reviewers and its secrets.
4. [ ] Bump versions at release time (phone/glasses 1.0.0, bridge 2.0.0-dev) and run the release workflow manually with with_bridge=true (a tag push never builds the bridge).
5. [ ] Publish to GitHub only after steps 1-4 and the device checklist (nexus-device-checklist.md step 16).

## I. Added 2026-11-20 (user only; none blocks the trip candidate)
- Data saver (design `plans/2026-11-20-roaming-data-saver-design.md` section 6, 11 questions): defaults (photos Wi-Fi only; small text on roaming), limits (proposed 10 MB and 30 photo uploads a day on mobile), spoken questions after the limit, "describe" on roaming (ask or wait), "I'm abroad" manual or dated, which SIM in Sri Lanka (local SIM reports "not roaming"), hotspot/pocket Wi-Fi, Delete all resets counters, place cards on roaming (drop proposed), VPN use, glasses count. Slice 1 is built with the conservative defaults until you answer.
- Pin times: approximate times of your 3 home pins from the Activity stream (to attach their Moments with the new day picker).
- Privacy nod: logged place ids (wd:/osm:) form a place-level trail in the local log; distance/UTC-offset buckets are logged. Keep or hash?
- Offline vision model: shortlist Qwen3-VL-2B / SmolVLM2-2.2B, about 8.5 agent-days plus one device session (`plans/2026-11-20-offline-vision-model-exploration.md`): go/no-go and when.
- Forget pipeline redesign (`plans/2026-11-20-forget-pipeline-redesign.md`): confirm it is built after Sikkim.
