# PCA roadmap

Status as of **2026-11-24**. One row per version. Versions after 1.0.0 are plans, not promises; dates are targets.

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
- [x] ✅ **v0.9.9-beta**: hub AutoStop and battery wake path; Forget for answers, nudges and photo-less marks; the first Wear OS watch module (motion sender, phone receiver).
- [x] ✅ **v1.0.0-rc1**: per-photo delivery proof on memories (Room v16) and the suspect-photo list; a privacy sweep so a Forget clears HUD answers, open lists and reminders everywhere.
- [x] ✅ **v1.0.0**: first release (phone + glasses + watch); session-fenced HUD answers; "not worn (estimated)" fallback; on-device index build log milestones.

## v1.x: stability, privacy, Place Guide, answer quality

- [ ] ⚪ **v1.1** (trip build, installed on the devices as candidate `18c1b76a`; not tagged yet): stability and privacy; Place Guide (pins, packs, Wikipedia/Wikidata/OSM places, glasses cards, left/centre/right placement, pause zones that fail closed); Ask quality (time window, ordering, safety questions); Forget lifecycle fixes; not-worn fallback; shadow-eval route (Developer only); link diagnostics tools; install helper with validated backup and rollback.
- [ ] ⚪ **v1.1 trip candidate `4436d1a5`** (verified 15/15, staged, awaiting install; adds the street map, trip logging and pin-to-Moment below): MapTiler/OSM tiles for the Pause zones editor, off by default, explicit consent, capped, cleared by Delete all.
- [ ] ⚪ **v1.2**: multi-select Forget (built behind the Developer flag `forgetV2`, default OFF, planner/executor with preview and acknowledgement; needs a worn check) and the Forget pipeline redesign (step 1 BUILT: `ForgetSource`, the policy table with a test that fails when a data family has no row, `PolicyPlan`; nothing wired yet; one flow for every Forget: source, selector, plan, one confirmation dialog, policy table; design in `docs/superpowers/plans/2026-11-20-forget-pipeline-redesign.md`, build after the Sikkim trip); multi-select and range Forget on that flow; derived-signal try-it preview (built, Developer only, default OFF).
- [ ] ⚪ **v1.3**: Activity pagination (built: keyset paging, 100k perf test; worn check pending); multilingual/Indic retrieval (code behind a default-OFF flag); Nexus spike and plugin plan (design done, code behind OFF flags).
- [ ] ⚪ **v1.4 line: Place Guide intelligence** (numbering proposed):
  - [ ] ⚪ **1.4.1** trip logging: 300 MB / 60-segment on-device log, Detailed log switch (default ON), one decision line per Place Guide attempt, runtime diagnostics (in the staged candidate).
  - [ ] ⚪ **1.4.2** Pin to Moment: every pin carries a reference Moment; Forgetting that Moment (explicit Forget only) also forgets the pin after a confirmation that names it; day picker for attaching Moments to existing pins (in the staged candidate; review READY after five rounds).
  - [ ] ⚪ **1.4.3** model resolver (BUILT and verified in the staged train `166d3fd4`, five review rounds READY, not installed; Model privacy setting Automatic/Offline only, fails closed): one call path that picks online or offline backend from settings and network ("offline only" privacy mode). Must also fix the router fallback that ignores endpoint kind and the unknown-network-counts-as-online check. About 1-1.5 days (design: `docs/superpowers/plans/2026-11-20-place-guide-resolver-and-semantic-ranking.md`).
  - [ ] ⚪ **1.4.4** semantic ranking (slices A and B1 BUILT, shadow-only, flag off by default, in the staged train `eac23a5c`; SigLIP 2 shadow embedder behind `placeShadowSiglip` and the calibration harness BUILT 2026-11-25, thresholds NOT SET until the calibration run, see `docs/superpowers/plans/handoffs/decisions-1.4.4-2026-11-25.md`): location, then embedding score of the pin's Moment, then the vision model only for borderline cases; thresholds tuned on an eval. Shadow mode first (scores logged, cards unchanged). Image scoring needs an image-text embedder: add a second, separate space (primary SigLIP 2 base patch16-224, Apache-2.0; fallback SigLIP v1 base; MobileCLIP/MobileCLIP2 excluded: research-only weights), kept only if it beats Nomic on the home pins and monuments; text space unchanged. Embedder milestones T (text tokenizer, 80/80 golden parity) and V (vision tower: preprocessing bit-exact vs HF, ONNX cosine >= 0.9999999999999986 vs python onnxruntime; `pooler_output` selected by name; the int8 model depends on batchmates so one image per run; JVM warm ~54 ms/image) are BUILT on the branch and not wired; remaining: text tower session + catalog/download + space registration + eval. Embedder work about 8-10.5 agent-days per the integration spike (`docs/superpowers/plans/2026-11-20-siglip2-integration-spike.md`: ~382 MB download, ONNX Runtime 1.31 opset 14 fits, BPE tokenizer 1-1.5 days in pure Kotlin, output 1 `pooler_output` not output 0; cheapest first milestone = vision tower only, 4-5 days), after the 26 Nov freeze; ranking logic 2-2.5 days (`docs/superpowers/plans/2026-11-20-image-embedder-comparison.md`).
  - [ ] ❌ **1.4.5** offline backend: small on-device vision model (shortlist Qwen3-VL-2B, SmolVLM2-2.2B; llama.cpp `mtmd`) behind the resolver. About 8.5 agent-days to a benchmarkable build plus a device session; speed is the main risk (15-35 s estimated until a dotprod/i8mm library build). Starts only after the embedding models are finalised (1.4.4: text embedder kept, plus a CLIP-family image-text embedder added). Starting models: Qwen3-VL-2B or SmolVLM2. Target before Sri Lanka, hard cutoff 20 Dec (`docs/superpowers/plans/2026-11-20-offline-vision-model-exploration.md`).
  - [ ] ⚪ **data saver** (slices 1 and 2 BUILT and verified in the staged train `eac23a5c` (counters, optional daily caps, Send once), review rounds READY; slice 2 P2 fix round in progress; needs the user's answers and an install before Sikkim)
  - [ ] ⚪ **1.4.7 trip fixes (24 Nov, built on the branch, in verification and Codex review, not installed)**: hub auto-start from the glasses ACL connect and watch motion (guarded, never over a manual stop or a phone pause; the glasses address is learned so the Bluetooth wake can fire); retention no longer redacts or drops anything on system expiry (nudges, answers, Moments, Scenes), with a Developer "Pause clean-up" switch (default ON for this build), "expired" vs "forgotten" wording, counts-only plan/result logs and a one-shot Moment and Scene repair; pin/Forget fixes from five Codex and Opus rounds (confirmed pin ids bound to every Forget, strict pin export, durable owed pin deletions); partial place-cell backoff (5 min to 1 h, was 24 h) and faster Overpass failover; map zones screen phase P0 (seeded areas and pins on the map, draw a box and seed it with an estimate, remove areas, per-request privacy re-check); extract prompt asks for visible sign names; offline trip replay harness (real frames, pins and GPS through the real sampler).
  - [ ] ⚪ **1.4.8 place fixes (in progress overnight 24-25 Nov)**: pin-first location card with an ambiguity rule, fix reuse while dwelling, dedupe that weak answers cannot consume, retry when a pin is created, card history built, off by default pending redesign (places.db v2, 90 days; "show on glasses again"; Developer flag `placeCardHistory`).
  - [ ] ⚪ **train16 (bb49cb5b, 24 Nov 17:40; verified 15/15, staged, NOT installed)**: everything in 1.4.7 and 1.4.8 above except place card history (built, OFF by default, redesign into context.db v18 after Sikkim) plus mark rounds 2-3 (canvas run prune safe against a backwards clock; mark-Forget cleanup notices in UI and voice; photo-file cleanup failures reported as pending), Offline-only privacy rounds 2-3 (every place request gated per request and on the IO thread, VPN LAN refused under Offline only, keys read after the guard), map zones phase P1 behind the Developer flag `mapZonesV2` (rectangle/circle, handles, Pause/Seed sheet, overview, TalkBack actions; device gestures untested).
  - [ ] ⚪ **1.4.9 map zones P1-P3** (P1 built behind `mapZonesV2`, needs a device test; seed-area rename/Show cells need places.db names): full-height screen with circle, handles, Pause/Seed type sheet, offline map download with size estimate (needs a ruling on MapTiler terms), and a Moments density layer once Moments carry a location. UPDATE 25 Nov: P2 leftovers and P3 density layer BUILT behind `mapZonesP23` (fdfb03d1; offline download cancelled; seed-area rename parked for a places.db v4 name column); device test still pending.
  - [x] ✅ **Moment location (Room v18, optional setting, local only)** BUILT 25 Nov on `feat/v101-ask-eval` (WP1-WP9: location row, recorder, import route, adaptive GPS backfill, card history in context.db, offline notice, network wake; verified 15/15, Codex round 1 fixed; not installed). Real-DB migration rehearsal and GPS import still to do.
  - [ ] ⚪ **1.4.6** pack Moments: BUILT 25 Nov behind `packMoments` (default OFF, shadow only, text only; commit e0f7c54f). A reference image per pack place needs a v19 table (after the trip). Owner questions in `docs/superpowers/plans/2026-11-25-pack-moments-design.md`.
- [ ] ❌ **v1.x chores**: real release signing key (after Sri Lanka), GitHub `release` environment, nightly photo-describe job zone fence (needs a ruling), Room v18 rulings as they arise.

## Nexus plugin line (priority P2 until after the trips; may move to v3)

Code for the first eight tasks is built behind OFF flags in `:nexus-port`, `:nexus-bridge` and the phone `nexus/` package. A version means the slice is switched on, device-verified and released.

- [ ] ❌ **v1.5**: nudges, place cards, answers and mark confirmations on the Nexus HUD; host switch "Show cards in Nexus"; coexistence status screen. Gate: glasses link coexistence test (half-day, proposed 7–10 Dec).
- [ ] ❌ **v1.6**: assist button asks PCA (speech in via Nexus STT, answer back as a card; refused inside zones). Gate: speech quality, battery, heat.
- [ ] ❌ **v1.7**: spoken answers (TTS to glasses/earbuds); history wipe verified on hardware.
- [ ] ❌ **v1.8**: four action-only skills (status, pause, resume, mark).
- [ ] ❌ **v2.0**: Nexus plugin declared stable: signed bridge release on GitHub, 15-item device checklist, install/uninstall procedure, key rotation lineage.

## v2.x canvas line

- [ ] ⚪ **v2.1** live end-to-end test on the canvas (DESIGNED 24 Nov: `docs/superpowers/plans/2026-11-24-canvas-v2-1-live-e2e-and-v2-2-model-nodes-design.md`: Test run over a stored day/window/trace or a bounded live tap, per-node inspector, dry-run only, no Room change; about 11-14 agent-days after Sikkim, only the live tap needs the glasses) (run a pipeline over stored or live traces, see every node's output).
- [ ] ⚪ **v2.2** (designed in the same doc) model nodes on top of the resolver (online/offline), classifier and router presets, rule/condition node sets.
- [ ] ❌ **v2.3** detection nodes: tier (a) "list objects and people" prompt preset, tier (b) on-device detector (person, object); recognition of identity stays unbuilt (needs its own spec and threat model).
- [ ] ❌ **v2.4** natural-language tools (`tool.draft`): describe a tool in plain English, generated and verified in an isolated container, shipped as a reviewed patch.
- [ ] ❌ **v2.5** canvas nodes that call other plugins' skills; watch-run route; scene-building flows.

## v3

- [ ] ❌ **v3.0** proactive agent loop on the phone (retrieval core + voice + other plugins' skills as tools, writes behind per-operation approval); planner and agent-loop canvas nodes; optional phone-side camera path.

## Rokid-Coder (separate repository, tracked here only for coexistence)

Rokid-Coder is a fork of the Rokid Nexus hub that shows Claude Code and Codex sessions on the glasses. It lives in its own repository (`debasishdebs/Rokid-Coder`). The rows below are a summary, not the source of truth.

- [x] ✅ **Plans 1–4** (agentd on the laptop, phone and glasses apps, launchers, the first user feedback round): done device-free.
- [ ] ⚪ **1.0 release candidates** (rc.11 built, CI green, draft release, nothing published): hardware checks on phone and glasses first, then publish.
- [ ] ❌ **1.0**: published release; macOS and Windows where feasible.
- [ ] ❌ **1.1**: Windows support at any cost; follow-ups from the hardware checks.
- [ ] ❌ **PCA + Rokid-Coder link lease**: a design for arbitrating the shared glasses link, only if the half-day coexistence measurement shows contention (field evidence so far shows none).

## Known backlog called out for v1.9 / v2

- [ ] ❌ **Glasses app closes on the glasses** (about 23 short capture gaps of 1-8 min over the 21-23 Nov trip, roughly 10% of capture time; the phone reopens it after ~8 s but at most about twice an hour). Needs a glasses-side reason log before it can be fixed. Not scheduled before v1.9.
- [ ] ❌ **Retention P2**: re-point dependents to a durable record before expiry, exempt place-card, mark and pin photos, a user-visible retention preview.
- [ ] ❌ **Explicit phone pause** is not changed (user ruling): no reminder or prompt.
- [ ] ❌ **Glasses pop-up delivery**: cards show only as a pop-up (12 s) when the glasses screen is on; the glasses do not report skipped pop-ups. Card history on the phone is the interim answer.

## Not planned for now

Accessibility polish (v2/v3 at most), answer-latency work (about 8 s is accepted until v2), LiveFit (separate app, no PCA dependency).

## Versioning (agreed 25 Nov 2026)
Feature batches are minor releases, fixes are patches (1.x.y). Release branches `rel-1.x` are pushed to GitHub when verified and reviewed (`tools/dev/push_clean.sh rel-1.x rel-1.x`).
| Version | Contents |
|---|---|
| 1.5.0 | date-range filter on lists, MapTiler street map key |
| 1.6.0 | Room v18: card history, Moment location, GPS backfill, offline notice on the zones map, network-return trigger |
| 1.7.0 | test place, photo/URL import, embedding match |
| 1.8.0 | Retention P2, map zones P1 |
| 1.9.x | canvas line (old v2.1-v2.5) |
| 1.10.0 | offline vision model (old 1.4.5) |
| 1.11.x | Nexus plugin line (old v1.5-v2.0 rows below are Nexus plugin versions; 2.x is reserved for after the 2.0 release) |
| 3.0 | proactive agent loop |
