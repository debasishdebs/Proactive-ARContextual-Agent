# SDD ledger — plan: docs/superpowers/plans/2026-10-21-wave-b-context-hub.md
Spec: docs/superpowers/specs/2026-10-21-wave-b-context-hub-design.md (reachable). Worktree /Users/debasishkanhar/Desktop/rokid/pca-ctx/int, branch ctx/int = int/2026-10-21 @6f6cc2b (wave 2 through Task 11 round 0) + battery/integration (A3) merged at ada2281.
Models: Sonnet implementers, Opus reviewers. Codex plan review BLOCKED (account can't use Sol models) — Opus-only reviews.
## Pre-flight scan
| Pair / task | Produces → consumes | Finding |
|---|---|---|
| T1 → T2..T11 | wire types, evidence types, policy table, Channels.MOTION_CONTEXT | consistent |
| T2 | pure fusion engine | consumes T1 |
| T3,T4,T5 | phone sources (activity/steps/airplane; speed; car BT/Android Auto) | new deps play-services-location 21.3.0, androidx.car.app 1.4.0 (T3) |
| T6 | hub + settings + channels | consumes T2–T5; touches glasses-link |
| T7 | UI/permissions | consumes T6 |
| T8 | INTENT_CLASSIFY prompt + Room v5 | Ruling: v5 only valid if wave 2 (v4) is in this branch — yes (ctx/int has Room v4 from wave 2 Task 1); wave 2 tasks 11-fix may add nothing to Room — cost: renumber if it does |
| T9 | intent sampler → fusion | consumes T8, T2 |
| T10 | watch receiver behind a fake | consumes T1/T2 |
| T11 | glasses ContextGate + heartbeat | consumes T1, T6 channel; edits SamplingSession (A3 merged) |
| T12 | device, user present | Ruling: not dispatched |
| Branch topology | wave 2 Task 11 fix commits (f04b50a on int/2026-10-21) land after this branch point | merge int/2026-10-21 into ctx/int before the final review of this wave — cost: one merge |
Task 1: dispatched (BASE bc71db1)
Task 1: complete (commits bc71db1..991eb2e, review clean)
Task 1: minor (deferred): WatchMotionCodec NaN branch unreachable via JSON (non-finite → null, not clamped)
Task 2: dispatched (BASE 991eb2e)
Task 2: review: spec ok; Important: (1) out-of-order event drop (clock back → stuck DRIVING/airplane), (2) single speed spike lifts veto + local veto, (3) held vehicle starts a new episode. Rulings (safe direction): arrival-order application; fastConfirmed = 2 readings >= 25 km/h within 15 s; held-only vehicle fact never starts an episode. fix round 1 dispatched
Task 2: complete (commit a613ee9, reviewed)
Plan amended 2026-10-21 evening (Opus planner): WATCH-FIRST fast path — order 6 (registry-shaped, deps 1+2 only) ∥ 11 → 10 (WatchSource registers) → 7a (apply switch, watch + cert pin, state line, diag line) → `git merge int/2026-10-21` (f04b50a pending) → fast gate (gate-wave-b-fast.log) → Task 12 step 0. Deferred: 3 → 4 → 5 → 7b, 8 → 9 (8 only after the merge confirms DB v4), full gate, Task 12 1–10. Watch alone (+ glasses hints) yields UNKNOWN / WALKING / REST / VEHICLE_PASSENGER only; no DRIVING (no nudge suppression) or FLIGHT until Tasks 4/5/8/9. EvidenceSink moved T3→T6; `ContextHubService.wantLocation` wiring moved T6→T4. Blocker to flag: the LiveFit forwarder (spec §8, other repo) is needed for the on-device watch smoke.
Task 2: re-review round 1: 3 items addressed; NEW Important: fastConfirmed 15 s unreachable at 30 s sampling. Ruling: fastConfirmGapMs=45_000 + a slow reading between fast ones resets; Task 4 (deferred) requests a quick follow-up fix after a fast reading. fix round 2 dispatched. Minor handed to Tasks 5/6: sources must deliver in order, never replay buffered history (a stale older 'connected' event re-pins the car link).
Task 2: complete (commits 991eb2e..22893f2, 2 fix rounds)
Plan: fast-path amendment committed (Codex findings folded, ctx_hub_state, epoch/refresh, hard caps, revocation). Codex plan review done: .superpowers/codex/waveb-plan-review.md
Task 6: dispatched (BASE e76bf12)
Plan r2 (same evening): Codex plan review folded with controller rulings — fast path (T6/T7a/T10/T11): prefs file `ctx_hub_state` (not `context_sources`, owned by SourceSettingsStore; backup/extraction/export excluded), unconditional glasses walking veto (SPEED exception removed, plan + spec §9.1), FQ manifest names + merged-manifest test, explicit revocation (UNKNOWN / apply=false before silence; fusion rebuild + replay), one process-wide hub tied to Hub lifecycle, one serial executor + atomic intake, KeyframeGate hardCap for context caps (manual capture exempt), fresh envelope on reconnect + in-session older-atMs drop, watch epoch + rate coalescing + 10-min refresh (watchTtlMs 25 min), confidence clamp. 9 folded, 11 deferred (listed in plan "Deferred review findings"). User car rule recorded (plan deferred section + spec §6.5): listed BT device = vehicle episode; image intent only inside episodes, adds vehicle type; open: 2-wheeler policy, engine's extra vehicle evidence.
USER RULINGS (2026-10-21 night): (1) Intent/vehicle classification NEVER pauses or throttles capture by itself (user wants to glance at shops while riding); two-wheeler driver = no pause. (2) Only the driver-vs-passenger classifier for now, output rendered on the canvas to verify the pipeline; user will compose the policy DAG on the canvas tomorrow (head movement -> threshold -> hub motion -> frequency by activity -> capture -> intent -> intent-specific prompt). (3) Airplane-mode episode idea dropped. Task 11 capture-policy table must not reduce capture from intent alone; only motion-hub context caps (already specified) apply, and the walking veto stays.
Task 6: implemented (daaa48e..cf4b7d0), review dispatched
Task 11: implemented 958f866, review dispatched
Task 6: fix round 1 e8c244d (Opus re-review dispatched). Note: LocalModelsPageTest.rowsShowSizeLanguagesRamAndState flaked once in full phone run (not Task 6). Task 10 dispatched.
Task 6: complete (daaa48e..e8c244d, 1 fix round, Opus re-review approved). Parked: drain() batching so a flooding source can't starve Tick/Reconcile/Stop (Minor N1); quick stop/start resend of old context (N2); dead Cmd.Ev branch; comment that revoke relies on link.send not suspending. Fold N1 into a post-Task-10 hub cleanup commit.
Task 11: complete (cf4b7d0..cb088d9, 1 fix round, Opus re-review approved). Parked Minor A (resetLocalMotion leaves lastWalkAtMs up to 15 s; smart not volatile), Minor B (forced hint lost if first send fails / Smart not running at open; phone view late, capture protected).
Task 10: implemented 69ac32c (no RED run), review dispatched. Task 7a dispatched; it must pin liveFitCertSha256 when Watch source turned on.
Task 7a: implemented 51853af, review dispatched; Task 10 fix 3083091 re-review dispatched
Task 10: complete (cb088d9..3083091, 1 fix round, Opus re-review approved). Parked minors: pendingAppliedOnceOnly should advance clock 2 s between fires; provider class comment on caller-first; watchEpoch not in line(); intake comment omits null-json check.

Final review r3 (2026-10-22): I2 fixed (fusion ignores watch ASLEEP while a fresh glasses hint has 0 <= stillFrac5m < 0.85; LiveFit MAX_AGE_MS 30 -> 15 min, lf-motion/int a09525d). I3 ruled by the user and applied: VEHICLE_PASSENGER and VEHICLE_DRIVING rows = UNKNOWN row (DRIVING keeps nudges off); LEDGER: Task 2 image-assisted vehicle start (vehicleImage) and image-assisted REST (0.70) must be gated or removed BEFORE Task 9 lands (not implemented). Task 12 step 0 rewritten (12 checks; watch never sends IN_VEHICLE).
USER RULING 2026-10-22: wave B Task 8's v5 migration also carries the wave 3 capture-time column (one migration). Ordering: wave B 3,4,5,7b,8,9 on ctx/int; wave 3 and canvas slice 2 run as parallel streams after v5 lands (see wave 3 spec §9).
Task 8: implemented dd2bac5 (v5: intent cols + capturedAtMs), review dispatched; Task 9 dispatched
Task 8: complete (7cecf12..dd2bac5, Opus review approved, 9 minors; 2,4,5,6,8,1 folded into Task 9 addendum; skipped: newestUnlabelled index, v1 assertNullCols, inert flow node). SqlCipherMigrationDeviceTest v5 asserts compile only: run on device next session.
Task 9: implemented dd2bac5..71c6f25 (fusion imageAssist default off; IntentSampler; ImageIntentSource; Task 8 addendum), review dispatched
Task 9: fix round 1 fed7504; re-review dispatched
- 2026-10-22 merged w3g/int (db92d80) into ctx/int, merge 85c4a90: no conflicts, glasses/glasses-link/core-model/phone unit tests green, Room still v5, PROTOCOL_VERSION still 1.
Tasks 3,5,4 implemented a9059202..cc5e2c44 (3fec93e8, 45d8c57f, cc5e2c44); review dispatched; Task 7b dispatched
- 2026-10-22 merged w3b/int (8540c9f2) into ctx/int, merge 976c4d5d: conflicts only MemorySettings.kt (Delete all text combined) and SettingsDestinations.kt (imports union); Room v5, PROTOCOL_VERSION 1; gate-wave-b-6 EXIT=0, 4115 tests 0 failures; build 6 APKs in handoff-waveb-6.
