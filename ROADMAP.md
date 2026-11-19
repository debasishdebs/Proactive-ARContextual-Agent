# PCA roadmap

Snapshot for branch `v0.9.8`: the roadmap as it stood at **v0.9.8-beta**. Later versions are shown as not done. The live roadmap is `ROADMAP.md` on the default branch `v1.0`.

Legend: `[x]` ✅ done and released/tagged · `[ ]` ⚪ ongoing (in progress, not tagged yet) · `[ ]` ❌ not done / TBD

Trip dates that shape the order: Charminar 22 Nov, Ramoji 23 Nov, install freeze 26 Nov, Sikkim/Darjeeling 1–6 Dec, Sri Lanka 23–31 Dec.

## Released

Scopes below come from the git tag messages and the feature commits between tags.

- [x] ✅ **v0.5.0-beta**: baseline phone app and glasses relay.
- [x] ✅ **v0.7.0-beta**: wave 1, motion-aware keyframe capture and image memory.
- [x] ✅ **v0.9.0-beta**: slice 3a: capture plan, traces, replay/sweep, publish gates, marks, deletion, wear settings, watchdog.
- [x] ✅ **v0.9.1** (branch only, no tag): canvas fixes (a glasses-only pipeline opens in the editor), Moment-lite review fixes, doctor tool fixes.
- [x] ✅ **v0.9.2-beta**: built-in Default capture pipeline in the canvas; glasses trace durability (crash-safe publish, ordered epoch wipe, trace recovery off the main thread).
- [x] ✅ **v0.9.3-beta**: canvas composites (Facts from frames, Critic, Save memory), starter pipeline with glasses lane and phone flow, palette grouped by flow family, Photo node settings; Moment and Scene summary derive step (off by default).
- [x] ✅ **v0.9.4-beta**: Summary node with confidence, Aggregate family (`agg.window`, `gate.compare`), starter Moment-summary pipeline.
- [x] ✅ **v0.9.5-beta**: one DataOpGate makes export and delete mutually exclusive; exports carry only valid memories; the Forget dialog names a running export; per-arm photo settings for A/B; Forget radius, watchdog and Scene rebuild settings.
- [x] ✅ **v0.9.6-beta**: marks touch capture (the glasses take the mark's photo, with a bounded pause exception and an explicit fallback with its error reason; undo forgets it); Room v15; Playground vs Run and Save; one-time purge of invalid Moments.
- [x] ✅ **v0.9.7-beta**: one text backend for every surface (voice, chat, glasses text); Forget scope Photo/Moment/Scene (Moment and Scene only with Developer mode); developer sensor recording on the glasses.
- [x] ✅ **v0.9.8-beta**: Query and Nudge as canvas pipelines on a shared RagCore; live Nudge triggers (moment, scene, signal, schedule) with phone, glasses or caller delivery.
- [ ] ❌ **v0.9.9-beta**: hub AutoStop and battery wake path; Forget for answers, nudges and photo-less marks; the first Wear OS watch module (motion sender, phone receiver).
- [ ] ❌ **v1.0.0-rc1**: per-photo delivery proof on memories (Room v16) and the suspect-photo list; a privacy sweep so a Forget clears HUD answers, open lists and reminders everywhere.
- [ ] ❌ **v1.0.0**: first release (phone + glasses + watch); session-fenced HUD answers; "not worn (estimated)" fallback; on-device index build log milestones.

## v1.x: stability, privacy, Place Guide, answer quality

- [ ] ❌ **v1.1** (trip build, installed on the devices as candidate `18c1b76a`; not tagged yet): stability and privacy; Place Guide (pins, packs, Wikipedia/Wikidata/OSM places, glasses cards, left/centre/right placement, pause zones that fail closed); Ask quality (time window, ordering, safety questions); Forget lifecycle fixes; not-worn fallback; shadow-eval route (Developer only); link diagnostics tools; install helper with validated backup and rollback.
- [ ] ❌ **v1.1 + opt-in street map** (staged, awaiting install): MapTiler/OSM tiles for the Pause zones editor, off by default, explicit consent, capped, cleared by Delete all.
- [ ] ❌ **v1.2**: multi-select Forget with the range confirmation (design); derived-signal try-it preview (built, Developer only, default OFF).
- [ ] ❌ **v1.3**: Activity pagination (list + day/hour breakdown); multilingual/Indic retrieval (code behind a default-OFF flag); Nexus spike and plugin plan (design done, code behind OFF flags).
- [ ] ❌ **v1.4 line: Place Guide intelligence** (numbering proposed):
  - [ ] ❌ **1.4.1** trip logging: 300 MB / 60-segment on-device log, Detailed log switch (default ON), one decision line per Place Guide attempt, runtime diagnostics (in verification).
  - [ ] ❌ **1.4.2** Pin to Moment: every pin carries a reference Moment; Forgetting that Moment (explicit Forget only) also forgets the pin after a confirmation that names it; suggested Moment for existing pins (in progress).
  - [ ] ❌ **1.4.3** model resolver: one call path that picks online or offline backend from settings and network ("offline only" privacy mode).
  - [ ] ❌ **1.4.4** semantic ranking: location, then embedding score of the pin's Moment, then the vision model only for borderline cases; thresholds tuned on an eval.
  - [ ] ❌ **1.4.5** offline backend: small on-device vision model behind the resolver; Wi-Fi-only/data-saver mode for roaming (target before Sri Lanka, hard cutoff 20 Dec).
  - [ ] ❌ **1.4.6** pack Moments: online and offline pack places turned into Moments so every source uses one matching path.
- [ ] ❌ **v1.x chores**: real release signing key (after Sri Lanka), GitHub `release` environment, nightly photo-describe job zone fence (needs a ruling), Room v18 rulings as they arise.

## Nexus plugin line (priority P2 until after the trips; may move to v3)

Code for the first eight tasks is built behind OFF flags in `:nexus-port`, `:nexus-bridge` and the phone `nexus/` package. A version means the slice is switched on, device-verified and released.

- [ ] ❌ **v1.5**: nudges, place cards, answers and mark confirmations on the Nexus HUD; host switch "Show cards in Nexus"; coexistence status screen. Gate: glasses link coexistence test (half-day, proposed 7–10 Dec).
- [ ] ❌ **v1.6**: assist button asks PCA (speech in via Nexus STT, answer back as a card; refused inside zones). Gate: speech quality, battery, heat.
- [ ] ❌ **v1.7**: spoken answers (TTS to glasses/earbuds); history wipe verified on hardware.
- [ ] ❌ **v1.8**: four action-only skills (status, pause, resume, mark).
- [ ] ❌ **v2.0**: Nexus plugin declared stable: signed bridge release on GitHub, 15-item device checklist, install/uninstall procedure, key rotation lineage.

## v2.x canvas line

- [ ] ❌ **v2.1** live end-to-end test on the canvas (run a pipeline over stored or live traces, see every node's output).
- [ ] ❌ **v2.2** model nodes on top of the resolver (online/offline), classifier and router presets, rule/condition node sets.
- [ ] ❌ **v2.3** detection nodes: tier (a) "list objects and people" prompt preset, tier (b) on-device detector (person, object); recognition of identity stays unbuilt (needs its own spec and threat model).
- [ ] ❌ **v2.4** natural-language tools (`tool.draft`): describe a tool in plain English, generated and verified in an isolated container, shipped as a reviewed patch.
- [ ] ❌ **v2.5** canvas nodes that call other plugins' skills; watch-run route; scene-building flows.

## v3

- [ ] ❌ **v3.0** proactive agent loop on the phone (retrieval core + voice + other plugins' skills as tools, writes behind per-operation approval); planner and agent-loop canvas nodes; optional phone-side camera path.

## Rokid-Coder (separate repository, tracked here only for coexistence)

Rokid-Coder is a fork of the Rokid Nexus hub that shows Claude Code and Codex sessions on the glasses. It lives in its own repository (`debasishdebs/Rokid-Coder`). The rows below are a summary, not the source of truth.

- [ ] ❌ **Plans 1–4** (agentd on the laptop, phone and glasses apps, launchers, the first user feedback round): done device-free.
- [ ] ❌ **1.0 release candidates** (rc.11 built, CI green, draft release, nothing published): hardware checks on phone and glasses first, then publish.
- [ ] ❌ **1.0**: published release; macOS and Windows where feasible.
- [ ] ❌ **1.1**: Windows support at any cost; follow-ups from the hardware checks.
- [ ] ❌ **PCA + Rokid-Coder link lease**: a design for arbitrating the shared glasses link, only if the half-day coexistence measurement shows contention (field evidence so far shows none).

## Not planned for now

Accessibility polish (v2/v3 at most), answer-latency work (about 8 s is accepted until v2), LiveFit (separate app, no PCA dependency).
