# Proactive Context — decision and evidence log

Date: 2026-10-14. Compiled read-only from the SDD ledgers and reports under `.superpowers/sdd/` (Phases 0–8, P7×P8 integration, the Phase 8 final review, device rounds 1–3, devfix branches, agent flows, on-device embeddings, glasses minimise and the minimised link). Every source path below is relative to `.superpowers/sdd/`. No secret values are reproduced; where a source quotes one, it is paraphrased.

Format of section 1: `Phase/area — decision — why — cost if wrong — source file`. Groups follow phase/feature order. Section 4 marks an item **CLOSED** only where a later report says it was fixed; "fixed in code" means a fix report exists but no device round confirmed it.

> **Controller corrections (2026-10-14, 23:00). These override the sections below.**
> - **Minimised link was device-verified, 22:42–22:46.** Probe: `rx pc_frame`, `tx pc_frame_ack result=0`, `tx pc_settings result=0`, and a `pc_state` heartbeat about every 10 s, all with the Rokid launcher in front. Option A is GO. Frames were ingested every 30 s with the glasses minimised and the phone on its home screen, and `ContextHubService` showed `isForeground=true`. Steps 5–7 of the minimised-link script (hand-back without duplicates, real exit, swipe from recents) are still not run. Section 3's "SKIP / not recorded" for steps 1–4 is superseded.
> - **devfix/r3a (`e77c4f0`), devfix/r3b (`2e49c18`), on-device embeddings (`7d37580`), bg-capture (`a75ae2d`), glasses-minimise (`d1bf157`), minimised-link (`f82b82e`) and activity-sort-cue (`87846a4`) are all MERGED** into feat/proactive-mvp-implementation. Read "fixed in code" in section 4 as merged but not yet device-confirmed, unless the session log says otherwise.
> - The narrative of this session, with decision confidence and the deferred develop/improve/decide lists, is in `2026-10-14-session-log.md`. Current state is in `2026-10-14-status-and-backlog.md`.

## 1. Rulings and decisions

### Phase 0 — Spikes (CXR probe)
- P0/T1 send result — treat `sendCustomCmd` as OK when its SessionResult says code=OK (brief assumed Int/Boolean) — brief's send() reported every send as failed — none, spike only — `2026-10-12-v1-phase0-spikes/progress.md`
- P0/T2 BT drop — added a 20 s BT-drop run on top of the planned 5 s run (user approved adb BT toggle) — exercise the retain→Flush-retained path — none — `2026-10-12-v1-phase0-spikes/progress.md`
- P0/T3 glasses states — drove states with adb KEYCODE_BACK / KEYCODE_SLEEP instead of the user swiping/removing glasses — same states, faster — wear-sensor removal untested — `2026-10-12-v1-phase0-spikes/progress.md`
- P0/T5 §5.1.1 STT injection detection — kept the pipe back-pressure method though only the positive case was observable — capacity reasoning — a phone that ignores EXTRA_AUDIO_SOURCE might not be detected; Phase 6 to unit-test the guard with a non-reading fake — `2026-10-12-v1-phase0-spikes/progress.md`
- P0/final I1 send gate — gate sending on session-open / activity-not-exited, not "resumed" — display-sleep re-run showed onPause+onStop with heartbeats still flowing — n/a (fixed) — `2026-10-12-v1-phase0-spikes/progress.md`
- P0/final I2 reconnect — adopt Live Fit policy: no auto-reconnect on GLASSES_EXIT; GlassAppWatcher + explicit phone action — auto-reconnect steals the glasses foreground — n/a — `2026-10-12-v1-phase0-spikes/progress.md`
- P0/final I3 production STT guard — >2 s fed and <64 KiB consumed = injection not working; non-blocking writes; short utterances rely on the onboarding probe — make §5.1.1 implementable — n/a — `2026-10-12-v1-phase0-spikes/progress.md`
- P0/final — CXR-only transport with a bigger glasses buffer (200 frames ≈ 4 MB) instead of an RFCOMM fallback (user decision) — simplicity — frames older than ~200 captures dropped while the glasses app stays closed — `2026-10-12-v1-phase0-spikes/progress.md`
- P0/final — 200-frame buffer is persisted in app storage — 4 MB in RAM is lost on glasses process death — Phase 3 writes frames to disk (small extra work) — `2026-10-12-v1-phase0-spikes/progress.md`

### Phase 1 — Restructure
- P1/T0 baseline — also commit reference/, src/, tools/ (user decision) — user asked — extra files in baseline — `2026-10-12-v1-phase1-restructure/progress.md`
- P1/T0 credentials — hardcoded credentials in reference/language_models.py moved to environment variables, values kept only in the untracked env file (user instruction) — keep secrets out of git — commented-out code needs env vars — `2026-10-12-v1-phase1-restructure/progress.md`
- P1/T1 — baseline not re-run (T0 ran the identical command on the same commit) — duplicate work — none — `2026-10-12-v1-phase1-restructure/progress.md`
- P1/T3 — added missing kotlinx.serialization encodeToString import in Wire.kt — compile fix — none — `2026-10-12-v1-phase1-restructure/progress.md`
- P1/T7 — accepted MemoryRecordStoreTest passing first run (characterisation test, no RED possible) — test pins existing behaviour — test may be weaker than assumed — `2026-10-12-v1-phase1-restructure/progress.md`
- P1/T8 test command — verify with `testDebugUnitTest` + `:core:model:test` rather than `./gradlew test` (release Compose tests lack ui-test-manifest host) — consistent with earlier tasks — release-variant unit tests stay red if anyone runs `test` (fixed at final review) — `2026-10-12-v1-phase1-restructure/progress.md`
- P1/T8 device smoke — Run Smoke tap not confirmed on phone (user was using it) — don't tap under the user — a Run Smoke regression not caught on device — `2026-10-12-v1-phase1-restructure/progress.md`
- P1/final — reviewer's declined items (unused Room annotations, Azure key in APK BuildConfig, summarizer DEFERRED by REPLACE, Run Smoke untapped, unused KSP plugin) left to Phase 2 — pre-existing — Phase 2 must actually remove the APK-baked key — `2026-10-12-v1-phase1-restructure/progress.md`

### Phase 2 — Data layer
- P2 attribution — subagent commits carry their own model's Co-Authored-By trailer — accurate attribution — cosmetic trailer mismatch — `2026-10-12-v1-phase2-data-layer/progress.md`
- P2/T1 FTS — MemoryDao.insert uses @Upsert instead of plan's @Insert(REPLACE) — REPLACE orphans external-content FTS rows → false search hits — none — `2026-10-12-v1-phase2-data-layer/progress.md`
- P2/T3 DbKeyStore — check commit() result and detect DB mode from the SQLite header when the per-file flag is missing — plan-verbatim code could make the DB permanently unreadable — small extra code — `2026-10-12-v1-phase2-data-layer/progress.md`
- P2/T4 importer — no importer row for the frame inbox (transient queue drained by ingest into Room via T5; embedding is Phase 5) — spec §6.2 reading — pending inbox frames ingested by the worker, same result — `2026-10-12-v1-phase2-data-layer/progress.md`
- P2/T6 backup — exclude encrypted prefs files AND the Room DB (+wal/shm) together from backup/data-extraction — Keystore master key is gone after restore → prefs throw / DB unreadable — no cloud backup of memories/keys — `2026-10-12-v1-phase2-data-layer/progress.md`
- P2/T6 cleartext — scoped cleartext networkSecurityConfig for LAN/localhost Ollama/llama.cpp deferred to Phase 5 — AI settings UI owns it — local endpoints unusable until Phase 5 — `2026-10-12-v1-phase2-data-layer/progress.md`
- P2/T7 backup — also exclude ai_seed.xml / legacy_import.xml markers — restoring them without the DB blocks re-seed/re-import — one idempotent re-seed after restore — `2026-10-12-v1-phase2-data-layer/progress.md`
- P2/T7 errors — ModelCallException.cause keeps raw provider throwable; log only the redacted e.message, never the stack trace — classification may need the type — a future stack-trace log could expose an echoed key — `2026-10-12-v1-phase2-data-layer/progress.md`
- P2/T8 — airplane-mode device check not run (user on phone) — airplane mode cuts calls — offline retry unverified on device (unit-tested) — `2026-10-12-v1-phase2-data-layer/progress.md`
- P2/T9 config sync — text-field edits skip sync; ~600 ms debounced sync on IO after typing; taps sync immediately — per-keystroke main-thread Room+Keystore writes and partial values overwriting the working registry — registry lags screen by ~0.6 s — `2026-10-12-v1-phase2-data-layer/progress.md`
- P2/final — fix Important (frameId truncation + same-ms frame drop) plus save atomicity (seeder re-puts missing key) and KDoc — cheap, prevents Azure jobs failing after a crash — none — `2026-10-12-v1-phase2-data-layer/progress.md`
- P2/final carries — Phase 3: UserFacingError for queue trace notes, retry cap, Config Test via EndpointTester (P5); Phase 5: scoped cleartext, FTS rebuild after VACUUM, SQLCipher tests, MemoryGraph main-thread guard, binding temperature/maxTokens — scope — n/a — `2026-10-12-v1-phase2-data-layer/progress.md`
- P2/final — master-plan wording fixed: DAOs exist only for frame/memory/trace/endpoint/role_binding; later phases add DAOs without schema bump — accuracy — n/a — `2026-10-12-v1-phase2-data-layer/progress.md`

### Phase 3/4 controller rulings
- P4 interim Ask — typed Ask uses legacy READ_CONTEXT_ANSWER prompt until Phase 5 AskService — keeps Home/Ask demoable — throwaway code — `phase34-controller-notes.md`
- P3/P4 device practice — `adb install -r` only when Proactive Context is not foreground; never launch/tap while the user uses the phone — session practice — app restarts under the user — `phase34-controller-notes.md`
- P3–P8 — plans not held for user review ("run all remaining phases w/o me") — user instruction — rework of a plan the user would have changed — `phase34-controller-notes.md`

### Phase 3 — CXR link
- P3 devices — only Phase 3 uses devices during the parallel P3/P4 run (shared applicationId) — avoid collisions — Phase 4 device checks later — `2026-10-12-v1-phase3-cxr-link/progress.md`
- P3/T2b — inserted task to remove the Azure key and all other keys from the APK (user request) — key extractable from APK — n/a — `2026-10-12-v1-phase3-cxr-link/progress.md`
- P3/T3 — explicit ArrayList loop instead of brief's non-compiling buildList — compile — none — `2026-10-12-v1-phase3-cxr-link/progress.md`
- P3/T3 session token — Hi Rokid session token stays in plain "rokid_link" prefs (Live Fit design, never logged) — authorises only the local glasses link; KeyVault would couple to :services:ai — token readable on a rooted phone — `2026-10-12-v1-phase3-cxr-link/progress.md`
- P3/T4 admission — capture time > now + 5 s skew → Hold and request a fresh hello (no clamping to now) — clamping lets paused frames through (privacy fail-open) — frames briefly held — `2026-10-12-v1-phase3-cxr-link/progress.md`
- P3/T4 clock — refresh the offset on every hello; atomic offset; serialise decide→ingest→ledger per frameId; elapsedRealtime for reassembly — backward clock jumps misclassify frames — rare frame loss after a mid-session time change — `2026-10-12-v1-phase3-cxr-link/progress.md`
- P3/T5 pauses — same-id merge keeps most restrictive; tombstone closed pauseIds; add/resume in one transaction; reconcile orphan intervals — spec §5.5 fail-closed — none — `2026-10-12-v1-phase3-cxr-link/progress.md`
- P3/T5 HUD countdowns — convert nextCaptureAtMs/lastSentAtMs to glasses clock, else omit — offset — countdown off by clock offset — `2026-10-12-v1-phase3-cxr-link/progress.md`
- P3/T6 — additive channel `pc_hello_request` + HelloRequest (no version bump) — request fresh hello on Hold — unused channel — `2026-10-12-v1-phase3-cxr-link/progress.md`
- P3/T6 — PauseDao.widenStart + HubPauses.reanchor (no schema change) — re-convert pre-offset pauses — none — `2026-10-12-v1-phase3-cxr-link/progress.md`
- P3/T6 fix round — guard onStored/Room exceptions (low-storage crash), mtime before rename, fresh offset per session, onReady via broadcaster (≤4 Hz), guarded onDestroy — process crash risk — none — `2026-10-12-v1-phase3-cxr-link/progress.md`
- P3/T7 — updated legacy glasses MainActivity onNewIntent signature for activity 1.9.2 — compile — none — `2026-10-12-v1-phase3-cxr-link/progress.md`
- P3/T7 pause race (re-graded Important) — locally cleared ids tombstoned and ignored by mergeFromPhone until the phone stops listing them — wearer's resume silently undone by next phone push — a phone re-pause in the window needs a new pauseId — `2026-10-12-v1-phase3-cxr-link/progress.md`
- P3/T7 exit latch — onActivityExit latches; only next onActivityStart/create reopens — spec §1.7 send gate — n/a — `2026-10-12-v1-phase3-cxr-link/progress.md`
- P3/T8 — test scope backgroundScope→TestScope accepted — production scoping still tested — none — `2026-10-12-v1-phase3-cxr-link/progress.md`
- P3/T8 fix round — in-flight frame at pause is CLEARED (not sent ~19 s and ingested); flush crash guard + poison drop — R-65 buffer drop; crash loop — none — `2026-10-12-v1-phase3-cxr-link/progress.md`
- P3/T9 gestures — unmapped gesture falls back to GestureDefaults; NONE only when explicitly mapped — partial map must not disable nav/pause — a gesture meant to be disabled by omission stays default — `2026-10-12-v1-phase3-cxr-link/progress.md`
- P3/T9 — tap breaks a double-tap pair; ConfirmInput clears pending after a vote — no repeat/invisible votes — none — `2026-10-12-v1-phase3-cxr-link/progress.md`
- P3/T10 — lifecycle wiring, overlay voting and exception handlers beyond the brief — carried rulings — none — `2026-10-12-v1-phase3-cxr-link/progress.md`
- P3/T10 interim — legacy PulseKeepAliveService ignores glasses pause until T11; don't ship between T10 and T11 — sequencing — none (not shipped) — `2026-10-12-v1-phase3-cxr-link/progress.md`
- P3/T10 — SharedBridge: one process-wide CXRServiceBridge, channels subscribed once — CXRServiceBridge has no unsubscribe/close; per-activity bridges leak native subscriptions — none — `2026-10-12-v1-phase3-cxr-link/progress.md`
- P3/T10 — black windowBackground — grey flash on the additive display — none — `2026-10-12-v1-phase3-cxr-link/progress.md`
- P3/T11 BT-drop — stays pending for user consent (Phase 0 consent was one-off; toggling drops other BT devices) — consent — BT buffering verified later (passed with consent) — `2026-10-12-v1-phase3-cxr-link/progress.md`
- P3/T11 capture — pause during the up-to-8 s capture window discards the frame; battery ≤10% early skip; clock-backwards guards; SCHEDULE_EXACT_ALARM moved out of the RFCOMM manifest block — §5.5 — none — `2026-10-12-v1-phase3-cxr-link/progress.md`
- P3/T11 cadence — CaptureCadence.MAX_INTERVAL_MS = 1 h (intervals clamped 30 s–1 h) — bounds the stale-schedule rule — users can't set >1 h intervals — `2026-10-12-v1-phase3-cxr-link/progress.md`
- P3/T12 — RFCOMM removal blocked until T11 device steps 9–11 passed — removal gate — n/a — `2026-10-12-v1-phase3-cxr-link/progress.md`
- P3/final — re-send Command.Resume for tombstoned ids on session open; foreground fallback only if our activity is started/resumed or foreground in last 60 s and not exited; log exception class only for Malformed — offline Resume lost; HUD pulled forward; memory text in logcat — background capture on firmware needing the fallback silently skips (logged) — `2026-10-12-v1-phase3-cxr-link/progress.md`
- P3/final — unconfirmed resumes travel in Hello.resumedAtMs (additive); 10-min tombstone expiry removed — offline resume — mixed-version pause divergence (phone and glasses ship together) — `2026-10-12-v1-phase3-cxr-link/progress.md`
- P3/final carries — Phase 7: phone-origin pauses both directions; Phase 4 T11: HubGraph glassesStatus swap, verbosity via updateSettings, requestReconnect starts service, late BLUETOOTH_CONNECT grant; Phase 5: cancellations consume the 8-attempt cap, loose ModelErrorMapper match; Phase 6: old-session listenAccepted, long-press ENTER tap, "offline" while CONNECTING; Phase 8: glasses clock 72 h cap, no 4 MB cap, disk-full drops newest, dir fsync, orphan .part, SurfaceTexture, Rokid force-stop — scope — n/a — `2026-10-12-v1-phase3-cxr-link/progress.md`

### Phase 4 — Phone shell
- P4 devices — Phase 4 device checks deferred to post-merge integration (T11) — Phase 3 owned devices — later discovery of device-only UI issues — `2026-10-12-v1-phase4-phone-shell/progress.md`
- P4/T2 icons — Outlined instead of plan's Rounded Pause/PlayArrow — Outlined-icons constraint — none — `2026-10-12-v1-phase4-phone-shell/progress.md`
- P4/T2 — stale thumbnail on path change re-graded Important and fixed — recycled rows showed wrong photo — none — `2026-10-12-v1-phase4-phone-shell/progress.md`
- P4/T3 feeds — filter reviewState='OK', hiding NEEDS_REVIEW memories — matches legacy "trusted memories"; review queue is Phase 5 — low-confidence memories invisible until Phase 5 — `2026-10-12-v1-phase4-phone-shell/progress.md`
- P4/T3 — editText becomes a transactional field-level edit — unguarded read-modify-write lost concurrent trust/INVALIDATED — none — `2026-10-12-v1-phase4-phone-shell/progress.md`
- P4/T4 banner — Resume shown only when !pipelineEnabled; hub-paused shows no action until Phase 7 — plan's Resume was a no-op — none — `2026-10-12-v1-phase4-phone-shell/progress.md`
- P4/T4 — drop PipelineControl cached early-return guard — legacy toggles bypass it; scheduling is idempotent — extra no-op reschedule — `2026-10-12-v1-phase4-phone-shell/progress.md`
- P4/T4 flake — LegacyPipelineControlTest flake fixed later (SynchronousExecutor + no-op WorkerFactory, 20/20) — a flaky gate erodes later tasks — occasional red gate — `2026-10-12-v1-phase4-phone-shell/progress.md`
- P4/T5 — orchestration tests read prompts from phone assets via a test-only sourceSets line — prompts live in :phone until Phase 5 — test-only coupling — `2026-10-12-v1-phase4-phone-shell/progress.md`
- P4/T5 prompt bounds — 500-char per-memory and 5 000-char total context caps; neutralise "{{"/"}}" in user/persona/memory text; single-pass PromptComposer — template re-expansion / injection — answers see less context — `2026-10-12-v1-phase4-phone-shell/progress.md`
- P4/T7 — fixed brief test weekday and off-screen assertion (tests only) — brief error — none — `2026-10-12-v1-phase4-phone-shell/progress.md`
- P4/T7 — re-query key includes zone id; DayViewModel gets shared clock flow — zone captured once — none — `2026-10-12-v1-phase4-phone-shell/progress.md`
- P4/T7 flake — DayTest flake fixed with RuleChain main→compose and a sweep of other tests — red gate — none — `2026-10-12-v1-phase4-phone-shell/progress.md`
- P4/T8 — Outlined icons; hidden (INVALIDATED) memory read-only (Edit/Trust disabled); bounded image pan — correctness — none — `2026-10-12-v1-phase4-phone-shell/progress.md`
- P4/T9 keys — UI state never holds the stored API key; key field write-only ("Key saved" placeholder, empty keeps key, explicit "Clear key") — keys-never-displayed; fixes Critical (first-time entry impossible, backspace erased key) — fresh installs must type the key — `2026-10-12-v1-phase4-phone-shell/progress.md`
- P4/T9 — Test does not run on a failed pre-test sync (short fixed text + NOT_APPLIED) — misleading results — none — `2026-10-12-v1-phase4-phone-shell/progress.md`
- P4/T10 — in-flight ask continues across tab switches; cancelled only when Home is popped — answer ready on return — one extra model call if abandoned — `2026-10-12-v1-phase4-phone-shell/progress.md`
- P4/T10 — BluetoothPermission.kt + bluetoothMissing hint added — carried requirement — none — `2026-10-12-v1-phase4-phone-shell/progress.md`
- P4/T10 — guarded back press, measured bottom-bar height (brief's 84 dp ~14 dp short), single Home, purges once — UI bugs — none — `2026-10-12-v1-phase4-phone-shell/progress.md`
- P4/T11 — requestReconnect only starts the service when not started; setVerbosity via PhoneDeps — reconnect raced hub collectors — none — `2026-10-12-v1-phase4-phone-shell/progress.md`
- P4/final — API-key field gets password keyboard / no autocorrect (re-graded Important) — keyboards may learn the key — one small fix — `2026-10-12-v1-phase4-phone-shell/progress.md`
- P4/final — FrameViewer extreme-date crash re-graded Important and fixed — crash — one small fix — `2026-10-12-v1-phase4-phone-shell/progress.md`
- P4/final declined — glasses keep capturing while phone paused (Phase 7), non-rokid_ memory purge (Phase 5), secrets prefs plaintext fallback on Keystore failure (Phase 5/8) — scope — privacy gap until those phases — `2026-10-12-v1-phase4-phone-shell/progress.md`
- P4/final — no AI while paused; Resume control fixed — pause semantics — none — `2026-10-12-v1-phase4-phone-shell/progress.md`
- P4/final — queued jobs keep 24 h TTL across a pause (carry: pause should freeze TTL) — pre-existing — summaries of a few frames lost after a >24 h pause — `2026-10-12-v1-phase4-phone-shell/progress.md`
- P4/final — pause checked once per drain run (≤7 more calls); pausePipeline cancels unique work — simplicity — a few calls after pause — `2026-10-12-v1-phase4-phone-shell/progress.md`
- P4/final — Ask stays available while the pipeline is paused — explicit user action, not background — user may expect Ask disabled — `2026-10-12-v1-phase4-phone-shell/progress.md`
- P4/T11 device — stale com.rokid.context + probe uninstalled from glasses (user approved) — cleanup — none — `2026-10-12-v1-phase4-phone-shell/progress.md`

### Phase 5 — AI settings
- P5 preflight — F1–F26 accepted as proposed (F26: same-model Rebuild drops the old space at build start) — spec-minimal fixes to plan text — rework in the named task — `2026-10-12-v1-phase5-ai-settings/progress.md`
- P5 gate — every task runs the full Global Gate before its final commit (F3) — catch cross-module breaks — slower tasks — `2026-10-12-v1-phase5-ai-settings/progress.md`
- P5 carries placed — pause freezes queue TTL → T13; rokid_mem_ ids survive purges → T13; secrets plaintext fallback removed in T21 — Phase 4 carries — n/a — `2026-10-12-v1-phase5-ai-settings/progress.md`
- P5 global rule — no unescaped '}' in Regex literals (AndroidRegexRulesTest), widened in T3 to plain-string Regex/toRegex — device ICU crash found in Phase 4 — n/a — `2026-10-12-v1-phase5-ai-settings/progress.md`
- P5/T1 — fix keyless seed keeping stored key; trailing-dot host; request-time "no key over http"; reject a test-only global flag that disabled the key guard — security — none — `2026-10-12-v1-phase5-ai-settings/progress.md`
- P5/T3 — schema validator hardening (10 items) — hostile model output must not crash or bloat retries — none — `2026-10-12-v1-phase5-ai-settings/progress.md`
- P5/T3 → T5/T18 — reject any $ref cycle in user-edited schemas — multi-branch recursive schemas cost b^64 (hang) — n/a — `2026-10-12-v1-phase5-ai-settings/progress.md`
- P5/T4 — regrade GBNF minors to fix (exponential ref reuse, optional const not nullable, empty enum, escaping untested) — schemas user-editable in T18 — one fix round — `2026-10-12-v1-phase5-ai-settings/progress.md`
- P5/T5 — fix non-$defs/root refs, pointer matching, narrowing, empty oneOf — same reason — one fix round — `2026-10-12-v1-phase5-ai-settings/progress.md`
- P5/T6 — placeholder regex escaped for ICU; injection hardening, exact restore; Cf stripping only on a detection copy so Devanagari ZWJ/ZWNJ survive — Hindi text and injection safety — none — `2026-10-12-v1-phase5-ai-settings/progress.md`
- P5/T7 — broken schema → INVALID_OUTPUT retryable with no corrective call; model text out of instruction area; no memory content in logcat — injection/privacy — none — `2026-10-12-v1-phase5-ai-settings/progress.md`
- P5/T8 — swap retires all other ACTIVE/STALE spaces; putChecked dim + live space; blob length %4; NaN guards — vector integrity — n/a — `2026-10-12-v1-phase5-ai-settings/progress.md`
- P5/T9 — embedding batches chunked at 64 inputs for every kind; owned native scope + await timeout + mutex; 404 tags → null revision — key sent to a space's old host, no chunking — more requests per rebuild — `2026-10-12-v1-phase5-ai-settings/progress.md`
- P5/T10 — plan-mandated unranked LIMIT 1000 overridden: BM25 over all matches with bounded heap; semantic failures fall back to lexical — spec ranks by BM25 — slower lexical on huge stores — `2026-10-12-v1-phase5-ai-settings/progress.md`
- P5/T10 — semantic retrieval keeps reviewState OK only — trust rules — n/a — `2026-10-12-v1-phase5-ai-settings/progress.md`
- P5/T11 — Rebuild while paused persisted as rebuildPending, honoured after resume (saved at enqueue) — pause semantics — rebuild delayed until resume — `2026-10-12-v1-phase5-ai-settings/progress.md`
- P5/T11 canary drift — re-probe (noisy → keep), real drift → 24 h hold + streak, 3 in a row → stop + "index unstable" — bounds paid calls — slower recovery from a real model change (manual Rebuild resets) — `2026-10-12-v1-phase5-ai-settings/progress.md`
- P5/T11 — pause cancels running index work; tighter NOISY; unstable fingerprint-scoped — privacy — none — `2026-10-12-v1-phase5-ai-settings/progress.md`
- P5/T12 — question-path hardening before Phase 6 voice; Main-thread prefs read fixed — cost if wrong: none — `2026-10-12-v1-phase5-ai-settings/progress.md`
- P5/T13 — TTL freeze via paused_at_ms + extendTtl on resume; legacy pause without stamp → full TTL from resume — jobs expired across a pause — none — `2026-10-12-v1-phase5-ai-settings/progress.md`
- P5/T13 — empty EXPLICIT_REMEMBER facts → store raw input as one MEDIUM note — user words never lost — an unstructured note instead of facts — `2026-10-12-v1-phase5-ai-settings/progress.md`
- P5/T13 — critic pending-reprocess check stays substring — payload carries no ids — a short fact's reprocess delayed — `2026-10-12-v1-phase5-ai-settings/progress.md`
- P5/T14 — fix post-swap SQLCipher rollback (mode/file mismatch → crash every launch), delete temp journals, neutral main-thread message — crash loop — none — `2026-10-12-v1-phase5-ai-settings/progress.md`
- P5/T14 → T20 — vacuum persists a "rebuild needed" FTS marker (in PRAGMA application_id) and rebuilds at next open — crash between VACUUM and FTS rebuild misaligns index — n/a — `2026-10-12-v1-phase5-ai-settings/progress.md`
- P5/T15 — CodeBox keeps an emitted-text record (32) to tell echoes from external changes — dropped keystrokes with async parent state — n/a — `2026-10-12-v1-phase5-ai-settings/progress.md`
- P5/T15 — accepted limitation: parent rejecting an edit without changing value doesn't resync — indistinguishable from lag — field shows unaccepted text until next parent change — `2026-10-12-v1-phase5-ai-settings/progress.md`
- P5/T16 — spec beats brief: Keystore off Main, no per-drag-frame slider pushes, labelled 36 dp position grid, a11y labels — Threads rule — none — `2026-10-12-v1-phase5-ai-settings/progress.md`
- P5/T16 → T21 (HARD) — AppConfigStore.get() unreachable on Main (closed in T21: only LegacyKeySeed on a background thread) — Keystore on Main — n/a — `2026-10-12-v1-phase5-ai-settings/progress.md`
- P5/T17 — bound-endpoint delete blocked; SettingsDeps lazy AI; Test time budget (20 s/60 s) in EndpointTester; blank key keeps stored key; capability untick can't break bindings — spec requires bounded Test — none — `2026-10-12-v1-phase5-ai-settings/progress.md`
- P5/T18 — 64 KB schema cap, 300 ms debounce, checks on Default; SchemaRejected so no JVM text reaches editor — schema check on Main per keystroke — very large schemas rejected — `2026-10-12-v1-phase5-ai-settings/progress.md`
- P5/T19 — UNSTABLE gated on bound embedder; streak reset only on user Rebuild; rebuild off Main; flaky PromptEditorTest fixed (wait for !saving) — correctness — none — `2026-10-12-v1-phase5-ai-settings/progress.md`
- P5/T20 Delete all — wipe generation token, cancel work, clear queue, transactional drop; delete legacy logs/exports (keep cxr_received_frames.txt); fence all memory-text producers; Delete all works even if import failed — memories returned after Delete all — none — `2026-10-12-v1-phase5-ai-settings/progress.md`
- P5/T20 test hang — Gradle test timeout 10 min + started/failed logging — recurring 30-min orchestration stall — n/a — `2026-10-12-v1-phase5-ai-settings/progress.md`
- P5/T21 — plaintext secret migrate-then-delete; provider body not logged/exported; keyless legacy plaintext key cleared; app_config_secret.xml excluded from backups; key filter patterns — secrets hygiene — none — `2026-10-12-v1-phase5-ai-settings/progress.md`
- P5/T21 — mark* guard skips EXPIRED only — only claimed rows are marked in production — a late mark could touch a non-RUNNING non-expired row (unreachable) — `2026-10-12-v1-phase5-ai-settings/progress.md`
- P5/final — frame JPEGs, queue log, legacy logs and exports excluded from backups (B1) — people wouldn't expect photos / plaintext memory text in cloud backup — frames not restored on a new phone — `2026-10-12-v1-phase5-ai-settings/progress.md`, `final-fix-report.md`
- P5/final — test-run stall attributed to concurrent Gradle runs in one worktree; one Gradle run per worktree, keep 10-min timeout — reproduced corruption — stall recurs (timeout names the test) — `2026-10-12-v1-phase5-ai-settings/progress.md`
- P5/final I1 — Embedders.forSpace returns null when the endpoint's kind or normalised URL differs from the space's (lexical fallback); embed-endpoint save triggers reindex — no query/memory text to the old host — n/a — `2026-10-12-v1-phase5-ai-settings/final-fix-report.md`
- P5/final I2 — MemoryWipe.deleteAll runs NonCancellable (not an app scope) — Back must not abort a wipe halfway — n/a — `2026-10-12-v1-phase5-ai-settings/final-fix-report.md`
- P5/final I3 — pause/resume tap runs on a serial IO dispatcher (FIFO); hub callback path left as-is — no prefs/queue work on Main — n/a — `2026-10-12-v1-phase5-ai-settings/final-fix-report.md`
- P5/final carries to Phase 7 — thumbs-down creates trusted summary via old reprocess path; Ask/test bed call AI while paused (allowed); RetryClassifier wording match; TestBed NUDGE real retrieval — scope — n/a — `2026-10-12-v1-phase5-ai-settings/progress.md`
- P5/T20/T21 carries to Phase 8 — streaming export; dead ModelProviderResolver and ModelGatewayFactory; keyset-only legacy secret file opens Keystore each start; legacy key copy-back; silent endpoints.save retries; base64 redaction drops JNI descriptor lines; stale KDoc — scope — n/a — `2026-10-12-v1-phase5-ai-settings/progress.md`

### Phase 6 — Voice answers
- P6 preflight — F1–F23 accepted as proposed; F16–F18 doc amendments (EnergyVad in :core:model; §5.1.1 pack assumed installed on API 31–32/query failure; R-67 onboarding probe → 7) in T1's commit — spec-minimal — doc churn — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/progress.md`
- P6 F23 — keep GlassesRuntime.pcmSourceFactory as @VisibleForTesting seam despite no-test-flag rule — bridgeFactory precedent — one mutable test seam in main — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/progress.md`
- P6 review policy — no re-review for test-only rounds with mutant evidence or one-line fixes with RED — speed — a weak test / small — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/progress.md`
- P6/T2 — orphan key-up after activity recreate is not a tap — false taps — small — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/progress.md`
- P6/T5 pipe capacity — capacity is a constructor parameter measured on device (fill scratch O_NONBLOCK pipe to EAGAIN), thresholds relative to capacity — plan's 64 KiB assumption wrong (page-slot pipes; 16 KiB pages let a non-reader pass) — probe verdicts wrong on device — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/progress.md`
- P6/T5 — verdict NOT_VERIFIED when undecidable; pin F_SETPIPE_SZ(65536) then re-measure; skip probe if cap > fed/2 — avoid false UNSUPPORTED on 16 KiB-page phones — voice shows "not verified" on some phones — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/progress.md`
- P6/T5→T6 — EPIPE before minimum feed = UNSUPPORTED (superseded in T6: with read end held, peerClosed means own I/O fault → not verified, never stored UNSUPPORTED) — direct evidence vs own fault — a broken pipe shows "not verified" — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/progress.md`
- P6/T6 — failed/pack/permission finals → not verified; verify each feed pipe via F_GETPIPE_SZ, fail closed — transient error became permanent UNSUPPORTED; unchecked pin failed open — none — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/progress.md`
- P6/T7 TTS privacy — embedded/offline voices only, filter also requires !isNetworkConnectionRequired; answer text never sent to a network voice — privacy — some languages HUD-only on phones without an offline voice — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/progress.md`
- P6/T7 — SpeakerCore takes AudioFocus; <queries> TTS_SERVICE; one failed TTS init no longer disables speech for the process — reliability — n/a — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/progress.md`
- P6/T8 — Thinking bounded at 60 s → "That took too long — try again" (generation timer, not withTimeout) — unbounded wait — slow local models cut off at 60 s — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/progress.md`
- P6/T8 — "20 s overall" = 20 s audio cap + 6 s final wait (≤26 s) — plan wording — 6 s over the plan's wording — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/progress.md`
- P6/T9 — regrade tap-discards-last-chunk and toast-outranks-listening double-tap to Important; local cancel sends Cancel to phone — dropped speech / wrong action — none — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/progress.md`
- P6/T9 — session-close path sends no Cancel — §1.7 send gate; phone linkLost already drops the listen — phone answers a half-heard question once if link stayed up while session closed — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/progress.md`
- P6/T9 — watchdog sends Cancel + onProblem before close() — ordering — small — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/progress.md`
- P6/T10 — after pack download only refresh(); probe at next foreground resume — no probe/FGS from background — one extra resume needed — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/progress.md`
- P6/T10 — phoneMic treated as off until prefs load — never start an ask the user turned off — a very early tap does nothing — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/progress.md`
- P6/T10 — micHeld feeds the gate: glasses ask blocked with "Open Proactive Context on phone to use voice"; guard skipped while mic not held; mic FGS type only from resumed activity; hub start moved onCreate→first onStartCommand — silent recognizer after sticky restart → stored UNSUPPORTED — one extra phone open after background restart — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/progress.md`
- P6 FAST MODE — T11–T14 in parallel worktrees; only Critical/Important fixed in-task, minors to ledger — speed — minors accumulate — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/progress.md`
- P6/T11 — leaving Home cancels a LISTENING phone ask; a THINKING ask completes, is saved and spoken — don't lose an almost-done answer — answer spoken after user left Home — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/progress.md`
- P6 devfix — ON_STOP cancels a LISTENING phone ask (f92642a) — back on root only stops the activity on Android 12+ — n/a — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/progress.md`
- P6/T15 R2 — CodeBox echo drops its own (oldest matching) entry and older; restore to an exact earlier duplicate ignored until next echo — clear-on-match wiped newer edits — rare ignored restore — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/progress.md`
- P6/T6 open question — voice_probe.pcm made with macOS `say` (personal non-commercial licence) ships in the APK — keep for personal use or replace — licence — see §5 — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/progress.md`
- P6/T3 — SessionInbound.open(session, setup) closes the session if setup throws (instead of moving `s` out of the try) — unit-testable without the SDK — none — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/task-3-report.md`
- P6/T4 R1 — PipelineControl test uses TestScope not backgroundScope (advanceUntilIdle never ran background work under coroutines-test 1.9) — brief test failed against correct code — none — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/task-4-report.md`
- P6/T5 — kept kernel-like page merging in the SlotPipe fake; reader-SUPPORTED test for 16 KiB pages uses 4 slots (pinned 64 KiB) — real kernel behaviour — n/a — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/task-5-report.md`
- P6/T6 asset — voice_probe.pcm copied byte-for-byte from the Phase 0 spike (134 142 bytes, spoken phrase + 3 s silence), not regenerated — same bytes Phase 0 verified on the phone — licence question open — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/task-6-report.md`
- P6/T6 — new pure ProbeFeed (pacing/drain/skip) and a constructor-injected Posix seam for AndroidPipeSink — JVM-testable; Robolectric stubs Os.write — n/a — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/task-6-report.md`
- P6/T6 — a kernel that refuses F_SETPIPE_SZ(65536) gets no glasses voice (sessions Failed, probe NOT_VERIFIED) — fail-closed rule — voice unavailable on such kernels — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/task-6-report.md`
- P6/T7 — SpeakerCore takes AudioFocus as a required dependency (GAIN_TRANSIENT_MAY_DUCK); denied focus speaks nothing; becoming-noisy stops speech (no fallback to phone speaker); explicit USAGE_MEDIA/CONTENT_TYPE_SPEECH — Phase 0 says HUD-only without A2DP — wired-headset unplug also stops phone-ask speech — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/task-7-report.md`
- P6/T7 — glasses answer spoken only when an A2DP output exists (SCO does not count); any A2DP output counts (earbuds/car) — Phase 0 route — speech could go to a non-glasses A2DP device — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/task-7-report.md`
- P6/T7 — failed TTS init shuts the engine down and retries on next speak; engine ids "<callerId>#<seq>"; watchdog max(30 s, 10 s + 125 ms/char); speaker logs fixed event names only — reliability/privacy — n/a — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/task-7-report.md`
- P6/T9 — mic released when start() throws; tap keeps the in-flight chunk (only cancel discards); double-tap while recording always Cancel; 2 000 ms read() watchdog; Cancel on activity exit while recording; watchdog sends Cancel and toast before close() — mic leaks / lost speech — none — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/task-9-report.md`
- P6/T10 — language watcher has no drop(1) and init doesn't refresh on its own — stale gate (ENGLISH evaluated while HINDI stored) — n/a — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/task-10-report.md`
- P6/T10 — HudAskText.forPhone maps unknown failures to fixed copy (never r.message) — no raw errors on HUD/Home — n/a — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/task-10-report.md`
- P6/T10 — every resume re-checks voice (HubResumeHook); background start takes connectedDevice only on every API level; FGS re-entered only on type change; in-front travels in the start intent — while-in-use mic rules — n/a — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/task-10-report.md`
- P6/T10 — a refused mic FGS type is retried at most once per resume — avoid loops — a refusal a second try would fix waits for the next resume — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/task-10-report.md`
- P6 devfix — cancel via the Home destination's LocalLifecycleOwner ON_STOP, not ProcessLifecycleOwner (new dependency, ~700 ms debounce, app-wide); ON_PAUSE (e.g. mic dialog) doesn't cancel — Home-scoped rule — n/a — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/devfix-cancel-report.md`

### Phase 7 — Nudges, actions, sources, pause, Forget, onboarding
- P7 plan — lean plan (850 lines): Stream 0 then A/B/C/F/E in parallel — speed — merge overlap — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/progress.md`
- P7/S0 — deterministic proposal id prop_${origin}_${originId} (one proposal per origin row) — re-run ask duplicated proposals — none — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/progress.md`
- P7/S0 — Graph API owners may add members (SourcesGraphApi, ActionsGraphApi); C app manifest block optional — parallel streams — merge overlap — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/progress.md`
- P7/A — may add SettingsDeps.pauses (one additive member) for Privacy — needed — merge overlap (keep both sides) — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/progress.md`
- P7/B lock screen — proposal/reminder notifications VISIBILITY_PRIVATE with a generic public version; actions require auth on API 31+ — lock-screen exposure — one unlock to accept a suggestion — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/progress.md`
- P7 battery copy — shows the code's real thresholds (phone defer ≤30 %, glasses stop 10 %), pinned by test — honesty — copy differs from plan's 20 % — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/progress.md`
- P7 process — agents never run `gradlew --stop`; stagger gates — kills sibling worktrees' gates (143/144 kills) — n/a — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/progress.md`
- P7/F retention — expiry deletes only expired rows (no descendant invalidation); user Forget still cascades — Critical C1: cascade gave derived summaries ~24 h life — derived summaries outlive sources by their own tier window (intended) — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/progress.md`
- P7/F — may make additive edits to AnswerService (write-time Forget fence) and ActionDaos (proposal scrub) — privacy — small merge overlap — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/progress.md`
- P7/A — legacy-off migration keeps backlog held until first explicit Resume — I1: migration processed held backlog — one extra tap after upgrade — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/progress.md`
- P7/A — capture pauses are capture-only; processing of captured frames continues (regraded M2) — scope — summaries run during quiet hours — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/progress.md`
- P7 — no "pause everything" AI mode in v1 (LegacyPipelineControl unreachable); ResummarizeWorker during capture pause accepted — scope — AI work during pause — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/progress.md`
- P7 integration — follow-up: cancel reminder alarms on Forget + receiver re-check — privacy — n/a — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/progress.md`
- P7/S0 — AnswerService.sources keeps type ContextSources, adapted from the provider (later gets the ask's nowMs additively) — plan didn't change that param — n/a — `archive/pca-p7/p7/stream-0-report.md`
- P7/S0 — HubPauses parameter order (dao, quietHours, zone, now) with now last — trailing-lambda callers would bind to zone — none — `archive/pca-p7/p7/stream-0-report.md`
- P7/S0 — manifest markers are begin/end comment pairs; Stream C owns two blocks (permissions outside, providers inside <application>) — manifest structure — n/a — `archive/pca-p7/p7/stream-0-report.md`
- P7/S0 — ProposalDao not edited; extra reads on ActionDao; ProposalRepository.addIgnore kept for tests; overlay precedence voice Listening > actions > voice overlay > nudge — ownership — n/a — `archive/pca-p7/p7/stream-0-report.md`
- P7/S0 fix — failing proposal intake logs class name and answers with proposalId=null (Cancellation rethrown); Hub refreshes activePauses even when not started — I1/ask must stay saved — n/a — `archive/pca-p7/p7/stream-0-report.md`
- P7/integration — a nudge whose sources were Forgotten mid-flight is dropped (not redacted), nothing written — no placeholder card on Home — n/a — `archive/pca-p7/p7/integration-report.md`
- P7/integration — redacted proposals never execute (marked FAILED(UNKNOWN)); BootReceiver skips them; Forget/retention cancel proposal and reminder notifications and armed reminder alarms; ReminderReceiver re-reads the row (goAsync) and posts nothing unless EXECUTED and unredacted — privacy — n/a — `archive/pca-p7/p7/integration-report.md`
- P7/integration — RecoverOnce guard: first ActionsGraph.get() and install() recover once — I-1 recover() double-executed (duplicate calendar event) — n/a — `archive/pca-p7/p7/integration-report.md`
- P7/integration — HomeRoute mic-grant probe only while RESUMED; LocalOpenActionsSettings in shell; SettingsDeps.actions lazy; QuietWindow moved to :core:model and used by NudgeGates — integration TODOs — n/a — `archive/pca-p7/p7/integration-report.md`
- P7/A1-a DST gap — a quiet-hours time inside a DST gap moves to the transition instant (02:30–03:30 on spring-forward → 03:00–03:30); a window wholly inside the gap is empty — ZonedDateTime.of would collapse the window — n/a — `archive/pca-p7-a/p7/stream-a-report.md`
- P7/A1-b — a live QUIET_HOURS record suppresses minting on zone change (later: overlap match, remainder not re-minted) — converge, more restrictive — n/a — `archive/pca-p7-a/p7/stream-a-report.md`
- P7/A2-a — a provisional id missing from an exact push is removed — else a phone timed pause seen inexactly stays indefinite forever — n/a — `archive/pca-p7-a/p7/stream-a-report.md`
- P7/A2-b — hello leaves out provisional records — else the phone's most-restrictive union makes its own timed pause indefinite — n/a — `archive/pca-p7-a/p7/stream-a-report.md`
- P7/A2-c/d — a pc_state pause list is an inexact push; each side adopts the other's live QUIET_HOURS record so a Resume on either side ends the occurrence on both — consistency — n/a — `archive/pca-p7-a/p7/stream-a-report.md`
- P7/A3 — PausePipelineControl over a small PauseHub interface (plan signature as secondary ctor); taps FIFO on IO; CapturePauses.control is a process-wide singleton — testability, one lastResumeAtMs — n/a — `archive/pca-p7-a/p7/stream-a-report.md`
- P7/A3-b — Diagnostics "requires pipeline" reads the pause control (reports paused while any capture pause is active) — consistency — n/a — `archive/pca-p7-a/p7/stream-a-report.md`
- P7/A fix I2 — resumed pause intervals older than 7 d collapse to zero-length tombstones kept 90 days — resumed id resurrected after >7 days — n/a — `archive/pca-p7-a/p7/stream-a-report.md`
- P7/A fix M2 — new PipelineControl.processingEnabled follows the legacy flag; capture pauses/quiet hours don't stop processing — capture-only pauses ruling — n/a — `archive/pca-p7-a/p7/stream-a-report.md`
- P7/A fix M3/M12 — notification Resume after process death resumes stored ids; quiet-hours minutes range-checked 0..1439 on phone and glasses — robustness — n/a — `archive/pca-p7-a/p7/stream-a-report.md`
- P7/B — ActionEngine extra ctor params zone and audit; executors in B1; settings via ActionsGraph.current() then ActionsGraph.ready StateFlow; ProposalCard uses collectAsState; ProposalUi.undone; notifications tag=proposalId id 7301 — ownership/shell rules — n/a — `archive/pca-p7-b/p7/stream-b-report.md`
- P7/B matrix — past due → EXPIRED; disabled type → SUPPRESSED; confirm on → PENDING (expires 60 s ASK / 600 s NUDGE; HUD confirm for ASK, NUDGE only at MAX verbosity); confirm off → auto-execute with 10 s undo — plan matrix — n/a — `archive/pca-p7-b/p7/stream-b-report.md`
- P7/B — undo window held in memory (schema frozen); recover() only at install in the hub process — no schema change — Undo not offered after process death — `archive/pca-p7-b/p7/stream-b-report.md`
- P7/B fix — in-flight claim set before EXECUTING insert; undo/expiry windows measured from handling time; empty shopping lists rejected; deleted calendar events not reused; calendar DENIED_FOREVER offers app settings — I-1/I-2 — n/a — `archive/pca-p7-b/p7/stream-b-report.md`
- P7/B reminders — exact alarm when allowed, else setWindow(10 min); notifications off logs NOTIFICATIONS_OFF and still returns Done — OS limits — reminder up to 10 min late — `archive/pca-p7-b/p7/stream-b-report.md`
- P7/C1 — all sources off by default; nudgesPerDay 0/3/6/10 (default 6); source lines capped at 1 500 chars; contacts only for a question, ≤3 lines, digits stripped, numbers/emails never read; location ≤30 min old fix, Geocoder bounded 3 s, label cached 10 min — privacy and bounds — n/a — `archive/pca-p7-c/p7/stream-c-report.md`
- P7/C ruling 1 — phone frame source is `rokid_phone` (file rokid_phone_<ts>.jpg), not `phone` — PipelineRecords rejects/purges sources not starting with rokid — n/a — `archive/pca-p7-c/p7/stream-c-report.md`
- P7/C ruling 2 — additive edits outside C's set (RokidFrameInbox placeLabel, SettingsDeps sources/capture, SettingsDestinations) — only allowed place — merge overlap — `archive/pca-p7-c/p7/stream-c-report.md`
- P7/C ruling 3 — a successful single-path ingest counts towards imported (enqueues the summarizer) — a phone capture must be summarised — n/a — `archive/pca-p7-c/p7/stream-c-report.md`
- P7/C — text-independent nudge gates (OFF, QUIET_HOURS, CAP_REACHED from local midnight) run before the model call; DUPLICATE = SHA-256 of collapsed lower-cased text over 24 h; run gate 30 min; unique work nudge_engine KEEP — saves model calls; cap safe only because runs don't overlap — n/a — `archive/pca-p7-c/p7/stream-c-report.md`
- P7/C — capture notices are NoticeText; CaptureNowControl.None.paused = true; NudgePort reads MemoryGraph lazily on IO — fail-closed / no Main DB — n/a — `archive/pca-p7-c/p7/stream-c-report.md`
- P7/C fix — summarizer job names only this run's frames; Capture now re-reads pauses on IO at prepare and finish (read-only, fail-closed); contacts filtered by name source (no email/number-named contacts); shot survives process death — 4 Important — n/a — `archive/pca-p7-c/p7/stream-c-report.md`
- P7/C — location note: geocoder may look the place up online — disclosure — n/a — `archive/pca-p7-c/p7/stream-c-report.md`
- P7/E — Hindi = ReplyLanguage.HINDI (plan's PersonaSettings.language doesn't exist); recognizer language unchanged — real model — n/a — `archive/pca-p7-e/p7/stream-e-report.md`
- P7/E — AI step adds Model/Deployment (and Azure API version); Test saves draft endpoint onboarding_<preset>, Continue binds unbound chat roles — Test needs a model — a failed Test leaves a saved unbound endpoint — `archive/pca-p7-e/p7/stream-e-report.md`
- P7/E — OnboardingDeps from PhoneDeps; markCompleteDurably (commit on IO); Phase 4 users start at HOME — upgrade path — n/a — `archive/pca-p7-e/p7/stream-e-report.md`
- P7/E fix — probePending: mic grant while not in front probes on resume; password keyboard + toString redaction of the key; key cleared on leaving the AI step — I1/I2 — n/a — `archive/pca-p7-e/p7/stream-e-report.md`
- P7/F1 Forget — one transaction: recursive closure (cycle-safe), delete F with vectors and edges, invalidate descendants, tombstone frames, redact citing asks/nudges, expire proposals; IN lists chunked at 500 — spec Forget cascade — n/a — `archive/pca-p7-f/p7/stream-f-report.md`
- P7/F2 — reprocess payloads v2 carry ids only; stale payloads end FAIL "Skipped: sources changed" and never retry; 👎 enqueues an id-only critic_review on the PRIORITY_REPROCESS queue (no enum change) — forgotten text must never reach a model — n/a — `archive/pca-p7-f/p7/stream-f-report.md`
- P7/F2 — RetryClassifier classes StalePayload/EmptyQuestion/NoMemories as FAIL by type before wording rules — wording mis-classification — n/a — `archive/pca-p7-f/p7/stream-f-report.md`
- P7/F ruling 1 — re-summarised ids are rokid_mem_rs_<uuid> — MainActivity purgeNonRokidMemories deletes non-rokid_ ids on every launch — n/a (purge is a Phase 8 carry) — `archive/pca-p7-f/p7/stream-f-report.md`
- P7/F rulings 2–5 — Toast "Forgotten" instead of snackbar; ensureRetention from scheduleCritic(); ResummarizeWorker not gated on the pipeline pause; RetentionWorker not gated on pause — no SnackbarHost / file ownership / stored data only — AI work during pause — `archive/pca-p7-f/p7/stream-f-report.md`
- P7/F rulings 6/7/9 — Forget fence covers in-flight extraction (appendFact drops facts for tombstoned frames / vanished sources); remaining-JPEG refs checked inside the transaction; WAL checkpoint after each Forget — privacy — n/a — `archive/pca-p7-f/p7/stream-f-report.md`
- P7/F fix C1 — retention uses ForgetService.expire (no descendant invalidation); Resummarizer keeps original createdAt — derived summaries lived ~24 h — n/a — `archive/pca-p7-f/p7/stream-f-report.md`
- P7/F fix I1–I8 — write-time ask fence (redacted ask, no intake); proposal params scrubbed to REDACTED_PARAMS and log detail cleared; FrameSweep at startup + retention; queue terminal text scrubbed; putChecked refuses missing/INVALIDATED; batch frame fence; 👎 marks as user_review trace rows (schema v1); resummarize skips failures, RETRY capped at 5 runs — privacy — n/a — `archive/pca-p7-f/p7/stream-f-report.md`
- P7/F fix — ForgetStartup.run in MainActivity's once-per-launch IO block (outside pipeline check) — review asked for app start — merge touch with Stream A — `archive/pca-p7-f/p7/stream-f-report.md`

### Phase 8 — Hardening (ledger entries)
- P8 schedule — streams A,B in parallel, C next, D after P6 T15, E last — overlap with P6/P7 — n/a — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/progress.md`
- P8/A fence — unknown fence fails closed (retryable ack, glasses keep frames) — ENOSPC fail-open — frames wait on glasses until clock/fence recover — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/progress.md`
- P8/A I5 — revert to plan: drop earlier-boot frames >1 h in the future (hold rule created data-loss and privacy holes) — supersedes the "held not dropped" ruling — backlog lost when the glasses clock is wrong after reboot (device-check RTC) — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/progress.md`
- P8/A — I2 inbox snapshot at mark; I4 generation check + mutex on rename-back; FAILED rename-back fenced — wipe races — none — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/progress.md`
- P8/D — rejected seed discarding the legacy key accepted — an unusable key in an unmanaged store is worse — user re-enters key once — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/progress.md`

### Phase 8 — Hardening, Stream A (frame storage edges)
- P8/A1 — within one boot, buffered-frame age is uptime-based (elapsedAtCaptureMs + bootId); unknown boot id ("" on error) falls back to wall clock — glasses clock jumps aged frames wrongly — n/a — `archive/pca-p8-a/p8/stream-a-report.md`
- P8/A1 — byte cap never evicts the last remaining frame (one JPEG over 8 MiB kept); storage-full: evict oldest, retry once — keep newest data — one oversize frame stays — `archive/pca-p8-a/p8/stream-a-report.md`
- P8/A1 — buffer key still sorts by capturedAtMs wall clock (FIFO across a backward jump follows wall clock) — outside plan — noted, not fixed — `archive/pca-p8-a/p8/stream-a-report.md`
- P8/A2 — crash charged to a not-yet-dropped frame ends the flush (next kick retries); crash/NACK/missing-JPEG drops let the flush continue; uncharged crash in oldest() ends the flush; pending-kick handoff under the uplink lock; hello after every flush that sent or dropped — fixes Phase 3 uplink minors — n/a — `archive/pca-p8-a/p8/stream-a-report.md`
- P8/A3 — ledger load failures (IOException/SecurityException) start empty with one log line; failed rename deletes .tmp; reassembler bounded (5th concurrent frame evicts stalest; oversize/bad-index chunk → NACK) — durability — n/a — `archive/pca-p8-a/p8/stream-a-report.md`
- P8/A4 — FrameIngestor wipeLock/wipe fence params placed before onStored (trailing-lambda callers) — compatibility — none — `archive/pca-p8-a/p8/stream-a-report.md`
- P8/A4 — InboxResult.FAILED only when appendFrame throws; false from appendFrame treated as DUPLICATE — none of its causes is worth a retry — n/a — `archive/pca-p8-a/p8/stream-a-report.md`
- P8/A4 — WipeFence.markStarted writes atomically and logs (Delete all never fails on it); wipe_started_at outlives the wipe; a WIPED frame is ledgered before its ack — durable fence — n/a — `archive/pca-p8-a/p8/stream-a-report.md`
- P8/A fix I1 — same-boot fence re-based on uptime (not clamped); other-boot wall value trusted while ≤ now + 60 s, else clamped and rewritten — clamping would wipe-drop recent frames / every post-reboot frame — n/a — `archive/pca-p8-a/p8/stream-a-report.md`
- P8/A fix I3 — fence that can't be recorded is Unknown: store nothing, send no ack, glasses keep the frame; error surfaces via hub error state — fail closed — frames wait on glasses — `archive/pca-p8-a/p8/stream-a-report.md`
- P8/A fix I2/I4 (round 2) — inbox snapshot at the fence mark; cancelled/failed ingest renamed back under WipeFence.mutex unless generation moved (then deleted) — wipe races — n/a — `archive/pca-p8-a/p8/stream-a-report.md`
- P8/A fix I5 (round 2) — revert hold rule: earlier-boot frames > 1 h in the future dropped as unknown age — hold created data-loss and privacy holes — frames lost after a reboot with a wrong glasses clock — `archive/pca-p8-a/p8/stream-a-report.md`
- P8/A — FRAME_DIRS order (rokid_inbox, rokid_archive) so Delete all walks the inbox first — wipe ordering — n/a — `archive/pca-p8-a/p8/stream-a-report.md`

### Phase 8 — Streams B (performance) and C (lifecycle)
- P8/B1 — streaming export: JsonWriter on IO, keyset pages of 500 by rowid, write .json.part then rename, old exports deleted only after rename; "memories.all() never called" enforced structurally (ExportDao only); export order becomes rowid — memory at scale (3 copies; asks capped 10k) — n/a — `archive/pca-p8-b/p8/stream-b-report.md`
- P8/B2 — lexical search reads one cursor per query into a bounded top-k, ensureActive every 256 rows — ~11× re-read cost of paging — n/a — `archive/pca-p8-b/p8/stream-b-report.md`
- P8/B3 — VectorCodec.decodeInto rejects blob size ≠ dim×4 without allocation; EmbeddingDao left unchanged (not B's file) — wrong-dimension/corrupt blobs — n/a — `archive/pca-p8-b/p8/stream-b-report.md`
- P8/B4 — IN lists de-duplicated and chunked at MAX_IN = 500; byIds re-sorted by ts — old SQLite 999-variable limit — n/a — `archive/pca-p8-b/p8/stream-b-report.md`
- P8/C1 — CaptureSession owns unbind + texture release (idempotent); refused foreground start schedules failure cadence and stopSelf(startId) — Phase 3 carries — n/a — `archive/pca-p8-c/p8/stream-c-report.md`
- P8/C2 — ForegroundFallback stops launching after 3 launches without a capture — HUD pulled forward on persistent camera failure — n/a — `archive/pca-p8-c/p8/stream-c-report.md`
- P8/C3 — ensureScheduled returns FRESH/ARMED/MISSED; MISSED re-arms at 1 s with a one-time "Capture resumed" toast (plan said arming unchanged; its test needed ~1 s); banner "Closed on glasses · capture stopped" — Rokid force-stop stops capture — toast timing — `archive/pca-p8-c/p8/stream-c-report.md`
- P8/C4 — SessionSlot closes each CXR session exactly once (weak identity set), extra log param; clearIf also closes an SDK-closed session (assumed harmless); GlassAppWatcher disconnects a built CXRLink on bind failure; SharedBridge replays lastConnected — session leaks — a re-fired close callback (stale, ignored) — `archive/pca-p8-c/p8/stream-c-report.md`
- P8/C5 — StateBroadcaster guards onError (cancellation rethrown, Error propagates) — a throwing handler killed the loop — n/a — `archive/pca-p8-c/p8/stream-c-report.md`

### Phase 8 — Streams D (security/privacy) and E (delivery)
- P8/D R-D1a — a legacy seed rejected by validation is marked done (and its legacy key retired) — an unusable key in an unmanaged store is worse — user re-enters the key once (e.g. http endpoint they would have fixed to https) — `archive/pca-p8-d/p8/stream-d-report.md`
- P8/D R-D1b–e — legacySecretHasKey parses XML names without opening the Keystore; dead ModelProviderResolver and ModelGatewayFactory deleted (ModelGateway.kt kept for live types); stale [get] KDoc in SchedulerConfigReadsTest left — Phase 5 carries — n/a — `archive/pca-p8-d/p8/stream-d-report.md`
- P8/D R-D2a — a failed edit row write restores the earlier key (never loses it); delete removes key first and never throws for vault failures; orphan keys swept at startup on the ai-seed thread — key lifecycle — n/a — `archive/pca-p8-d/p8/stream-d-report.md`
- P8/D fix I1 — EndpointRepository write Mutex over save and the orphan sweep; put+upsert+rollback NonCancellable; only a no-I/O config rejection marks the seed done, other IllegalArgumentExceptions retry — sweep raced a concurrent save (silent key loss) — n/a — `archive/pca-p8-d/p8/stream-d-report.md`
- P8/D3 — ModelRouter.safe masks raw, URL-encoded and Base64 (padding trimmed) key forms, longest first; key read once before the call — redaction leftovers — n/a — `archive/pca-p8-d/p8/stream-d-report.md`
- P8/D R-D3a (security) — tightened LogReader base64 path rule (segments may only end in digits, at least one '/') — the plan's identifier-path rule let real random Base64 keys through (mutant showed a random test key kept) — a JNI line with a mid-segment digit (e.g. Api21Impl) is dropped — `archive/pca-p8-d/p8/stream-d-report.md`
- P8/D3 — Gradle guard: BuildConfig values with embedded credentials (user:pass@, access_token=, subscription-key=, client_secret= in any position) fail the build, naming the variable only — Phase 3 carry — n/a — `archive/pca-p8-d/p8/stream-d-report.md`
- P8/D R-D4a–c — SQLCipher factory gets its own passphrase copy, ours zeroed in finally; migrator zeroes its key; library loaded once (retry after failure); DbKeys read-or-create under a lock (one key) — passphrase hygiene — n/a — `archive/pca-p8-d/p8/stream-d-report.md`
- P8/D R-D5a — isRealPhoto tests the payload and File(path).name, not the full path — parent-folder names purged real photos — n/a — `archive/pca-p8-d/p8/stream-d-report.md`
- P8/D5 — legacy import streamed in chunks of 500 per transaction; blank/non-numeric confidence imports as 0.0; ensureLegacyImported on Main returns null without the lock; failures logged (class only), worker MAX_RUNS = 3 — Phase 2 carries — n/a — `archive/pca-p8-d/p8/stream-d-report.md`
- P8/E1 — AllRoutesSmokeTest: every route (32 of 33 Routes consts; NEW_ENDPOINT is an id value) renders in the real NavHost without a crash or raw error text; KNOWN_BROKEN ledger fails if a listed route renders cleanly; 15 HUD page/verbosity/overlay cases — smoke coverage — n/a — `archive/pca-p8/p8-e1-report.md`
- P8/E2/E3 — tools/gate.sh watchdog (stall → jstack dump, EXIT=124), tools/device-checklist.sh (35 steps; adb only for devices/logcat/screencap; prints match booleans, never log lines), relocate-legacy-screens.sh; docs/screens/legacy ignored — delivery and safety — n/a — `archive/pca-p8/p8-e23-report.md`

### Phase 7 × Phase 8 integration and Phase 8 final review
- P7×P8 I1 / ruling 1 — Delete all redacts (not deletes) non-open proposals, empties action_log detail and cancels their alarms and notifications — accepted reminders fired after Delete all showing forgotten text; keep createdRef for calendar undo — id-only rows survive — `archive/pca-p8/p7p8-integration-report.md`
- P7×P8 ruling 2 — shopping items and notes are kept on Delete all — user lists; spec §6.1.1 doesn't list them — an accepted shopping line from a deleted ask stays — `archive/pca-p8/p7p8-integration-report.md`
- P7×P8 ruling 3 — action_log details emptied globally on Delete all, transitions kept — no user text in transitions — nothing of note — `archive/pca-p8/p7p8-integration-report.md`
- P7×P8 ruling 4 — origin fence as a post-check decorator (OriginFencedIntake), not a guard in ProposalRepository.create — an insert-time check breaks ~35 tests without origin rows — a mid-flight executor could still create an external calendar event — `archive/pca-p8/p7p8-integration-report.md`
- P7×P8 I3 — phone capture ingestSinglePath fenced on MemoryWipe.generation — race with Delete all left a row — n/a — `archive/pca-p8/p7p8-integration-report.md`
- P8 final review process — four area reviews (ai, data, device, phone) of 03376c5..phase8/hardening; prior rulings not re-raised unless user effect is worse; fixes TDD with "Ruling:" lines for declined findings — consistency — n/a — `archive/pca-p8/final-review/brief.md`, `fix-common.md`
- P8 final fix-actions I1 — ActionEngine respond and propose run NonCancellable from claim to terminal mark — a cancelled card scope left rows EXECUTING and reminders dropped — an executor that hangs can't be cancelled (all are local) — `archive/pca-p8/final-review/fix-actions-report.md`
- P8 final fix-actions I2 — notifications off: keep the armed reminder and warn on the card ("Added, but notifications are off" + Turn on), WARN/NOTIFICATIONS_OFF log row — not a failure; refusing would discard a valid reminder — shown as Added-with-warning instead of FAILED — `archive/pca-p8/final-review/fix-actions-report.md`
- P8 final fix-data C1 — retention leaves EXECUTING/EXECUTED proposals' params, alarms and notifications alone; PENDING still expires; user Forget/Delete all unchanged — retention silently cancelled accepted reminders — n/a — `archive/pca-p8/final-review/fix-data-report.md`
- P8 final fix-data I1 — VISION_EXTRACT runs once per photo; each fact stored on and fenced by the frame that produced it; reprocess fenced on its frames — Forget moment missed facts tied to the newest frame — up to 3 model calls per batch instead of 1 — `archive/pca-p8/final-review/fix-data-report.md`
- P8 final fix-data I2 — tombstone pruning: delete only when no frame row, no memory, no JPEG and older than 8 days (7-day glasses resend window + 1 day queue TTL); set-based sweep — unbounded tombstones, ~200k queries per cold start after a year — a frame re-sent >8 days after Forget could be re-extracted if FrameAdmission's window grows — `archive/pca-p8/final-review/fix-data-report.md`
- P8 final fix-device I1 — while recording, a tap is always FinishListening; a Confirm arriving during recording is held and its 15 s window restarts afterwards — a "send" tap approved an unread reminder — n/a — `archive/pca-p8/final-review/fix-device-report.md`
- P8 final fix-device I2 — a phone Toast (no undo) outranks the synthetic Listening and ends the local glasses ask (ptt.cancel, no Cancel sent) via PhoneAskWatch — refused/failed glasses asks vanished silently — n/a — `archive/pca-p8/final-review/fix-device-report.md`
- P8 final fix-phone I1 — a picked-and-tested onboarding preset rebinds roles whose binding is "untouched" (seeder/onboarding endpoint, first model, no tuning); Settings edits kept — seeder bindings silently overrode the choice — a Settings binding identical to the seed default is moved — `archive/pca-p8/final-review/fix-phone-report.md`
- P8 final fix-phone I2 — Capture now uses read-only HubPauses.pausedNow() (occurrence + resumed-interval rules, DST-safe) — stayed disabled after quiet hours were resumed — n/a — `archive/pca-p8/final-review/fix-phone-report.md`
- P8 final fix-phone I3 — Hub pausePhone/resume do DB work on IO then push pc_settings on the hub scope — CXR sends from IO broke the Phase 3 threading rule — n/a — `archive/pca-p8/final-review/fix-phone-report.md`

### Agent flows (Settings → AI → Agent flows)
- AF/A — Nudge edge nudge→propose labelled "if shouldNudge and proposedAction" — NudgeEngine returns NotNeeded before proposing — slightly longer label — `agent-flows/stream-a-report.md`
- AF/A — "no PeriodicWorkRequest in orchestration" anchor narrowed to NudgeWorker only — other workers are legitimately periodic — weaker anchor — `agent-flows/stream-a-report.md`
- AF/A — FlowLayout.columns = grid width incl. side lanes; side chains get outer lanes nearer their join; non-ASCII titles as escapes — layout — visual only — `agent-flows/stream-a-report.md`
- AF/B — production stand-in DayOneFlowSummaries (later replaced by AgentFlowSummaries); SETTINGS_FLOWS live; "1 AI step" singular; "Not set" counts toward the 2+k cap; Show selected uses load order — needed to render before A merged — counts differ until swapped — `agent-flows/stream-b-report.md`
- AF/C — full-screen editor open flag in rememberSaveable outside lazy items; Back = Cancel (later: asks "Discard changes?" when edited) — survive recreation, never lose draft — Back-expecting-Done loses full-screen edits — `agent-flows/stream-c-report.md`, `stream-d-report.md`
- AF/D — Retrieval facet names fusion only in Hybrid; Open editor closes the sheet first; System chip opens a Prompt-only sheet; FlowViewModel gains registry/seed; tests click via semantics OnClick and route Back through ShadowDialog — spec wording / Robolectric limits — touch inside the sheet covered only by device — `agent-flows/stream-d-report.md`
- AF/review I1 — skip-layer edges routed down free lanes (EdgeRouting, detour lane if needed), labels placed clear of nodes and drawn after them; Capture grid grows to 5 columns — graph misrepresented the trusted path as linear — Capture one column wider — `agent-flows/final-review.md`, `review-fixes-report.md`
- AF/review I2 — Prompt tab shows "Prompt <label> · shared by N steps" + "Used by"; AgentFlows.nodesUsing(PromptKey); spec §4 amended (prompt shared when several steps use the same key) — editing a shared CRITIC/SUMMARIZE prompt silently changed another step — n/a — `agent-flows/final-review.md`, `review-fixes-report.md`

### Device-run fixes (devfix branches)
- devfix/memory-scroll BUG-A — ZoomableFrame canPan only when zoomed (scale > Zoom.MIN) — unzoomed photo ate drag-to-scroll — n/a — `device-run-report.md`, `devfix-batch1-report.md`
- devfix/batch1 BUG-1 ruling — a row shows its photo's capture time (fallback createdAt), earlier-day photos in a leading "Earlier days" section; Today membership still by extraction time — a memory is about the moment in the photo — rows no longer follow extraction order — `devfix-batch1-report.md`
- devfix/batch1 OBS-2 — new code-only log `reminder armed; code=` (no title/time); P7-10 marker `append result=true frameId=rokid_phone_` — checklist false misses — n/a — `devfix-batch1-report.md`
- devfix/batch1 shopping — keep swipe-to-remove (existing pattern) with visible background, hint, TalkBack Delete and Undo snackbar; delete guarded once per row — users saw only a tick box; double delete — n/a — `devfix-batch1-report.md`
- devfix/r2a BUG-R2-2 — drop stray `arrayOf<Any>()` vararg (Kotlin passed it as one bind arg); conversion steps behind an Sql seam; failures logged as class + FailReason code only, never e.message — root cause proven from bytecode — real SQLCipher path still device-only — `devfix-r2a-report.md`
- devfix/r2a BUG-R2-6 — quiet hours off resumes every stored QUIET_HOURS record by name; changing the window keeps the running pause; Privacy shows one row per occurrence carrying all ids (both copies kept in storage) — dropping the glasses copy would make it unresumable — offline glasses keep their copy until sync — `devfix-r2a-report.md`
- devfix/r2b BUG-R2-5 — Answer carries the undo (additive Overlay.Answer.undoProposalId); window restarts once when first shown (≤20 s total); toast wraps to 2 lines; a phone-typed auto-add also gets the HUD undo — §5.3/§2.4 — window up to 20 s — `devfix-r2b-report.md`
- devfix/r2b BUG-R2-4 — auth timeout emits AuthTimedOut and retries up to 3× after 2, 5, 10 s; real verdicts never retried; Reconnect/session start resets the budget — no auto-retry after cold start — n/a — `devfix-r2b-report.md`
- devfix/r2b BUG-R2-3 ruling — all-day events shown as UTC date span; listed after timed events while any timed event remains today; ±1 day query slack — "today at 05:30" — n/a — `devfix-r2b-report.md`
- devfix/r2b BUG-R2-1 — flow graph horizontal scroll starts at the primary input's column — opened scrolled right — n/a — `devfix-r2b-report.md`
- devfix/r2b SEC-1 — call LogUtil.setLogLevel(ERROR) at link start in every build; vendor note docs/rokid-sdk-token-logging.md (no token); our logs/export drop `token:` lines; R8 -assumenosideeffects not done (minify off, wouldn't cover debug) — nothing in the SDK can turn the token lines off — token still in logcat — `devfix-r2b-report.md`
- devfix/r3a BUG-R3-1 ruling — 8 more prefs excluded from backup (onboarding, app_config, memory_graph, index_maintenance, pipeline_stats, rokid_link, rokid_bridge, voice_probe); user preference files restorable; build fails if a new prefs file has no ruling; OnboardingGuard re-runs onboarding only if complete + no usable ANSWER endpoint + empty DB — reinstall skipped onboarding with no key — a user with data but no key keeps Home — `devfix-r3a-report.md`
- devfix/r3a BUG-R3-2 — masked key field for a keyless build seed (vault only); Azure form prefilled with build address, API version 2024-10-21 and deployment — no way to add a key in the wizard — n/a — `devfix-r3a-report.md`
- devfix/r3a D-D4 — two connections: export on one, FTS rebuild on a fresh open that reads the schema from disk — sqlcipher_export copies virtual tables as raw rows the exporting connection doesn't know ("no such table") — real round trip still device-only — `devfix-r3a-report.md`
- devfix/r3a OBS-R3-1 — detect finish_reason=length; retry a cut reply with 2× the cap (max 8192); default cap 4096; null-content refusal and blank answer retried; rejection log names stage and cut flag — reasoning tokens on gpt-5.4 count against a 500 cap — more tokens per call — `devfix-r3a-report.md`
- devfix/r3b BUG-R3-3 — adaptive EnergyVad (speech ≥ max(80, 3× floor), floor = median RMS of first 300 ms capped 400, re-judged when floor drops); trailing silence 2.2 s (was 1.5 s); a tap within 1.5 s of auto-end is the send; spec §5.1 step 2 updated — Mac speaker never crossed RMS 300; 1.5 s cut asks — noisy-room behaviour unverified — `devfix-r3b-report.md`
- devfix/r3b — VoicePipeline logs one `PcVoice: outcome=<CODE> source=…` per ask (15 codes, no user text) and `listen end GLASSES` — silent failures indistinguishable from hangs — n/a — `devfix-r3b-report.md`

### On-device embeddings (ode)
- ODE/D — Http.downloadClient() follows redirects but never https→http, no call timeout (20 s connect, 60 s read) — pinned HF resolve URL redirects to a CDN; modelClient refuses redirects — a redirect could reach another https host (SHA pinning covers it) — `ode/stream-d-report.md`
- ODE/D — download worker takes only a catalog model_id (no URL in WorkManager input); non-verify/space errors map to "Download failed — try again"; IO and 408/429/5xx retry (exponential 30 s, 6 attempts) — safety — a third fixed string — `ode/stream-d-report.md`
- ODE/D — cancel keeps the .part (resume); delete removes file and .part; filesDir/models excluded from backups — resume / restore safety — partial file stays until Delete — `ode/stream-d-report.md`
- ODE/N — llama.cpp pinned as submodule tag b11618 (commit 4dd6dd4b…), CPU-only static build, NDK 28.2.13676358, arm64-v8a only, 16 KB page alignment, 5 exported JNI symbols — reproducible native build — n/a — `ode/stream-n-report.md`
- ODE/N — externals return nullable and Kotlin maps to ProviderConfigException; user-safe message "On-device models need a 64-bit ARM phone." — no crash, spec wording — n/a — `ode/stream-n-report.md`
- ODE/N — c++_static STL — avoid libc++_shared clash with CXR SDK libs — ~1 MB duplicate libc++ later — `ode/stream-n-report.md`
- ODE/N — baseline armv8-a (no dotprod/i8mm) — runs on every arm64 incl. A53/A55 — slower q8 matmuls on newer cores — `ode/stream-n-report.md`
- ODE/U — endpoint id local_<model id> with non-[A-Za-z0-9_-] replaced (local_bge-small-en-v1_5-q8) — EndpointRules rejects '.' — one rename — `ode/stream-u-report.md`
- ODE/U — backward-compatible ListScreen extensions (header, moreActionsFor, changes flow, onCancel, list_confirm tag); "Allow mobile data" stored in app_config key local_models_allow_metered; per-row primary actions by state; removing only a fallback triggers no reindex — spec needs; settings rules — UI rework / one missed reindex in a rare setup — `ode/stream-u-report.md`
- ODE/integration — process-wide EmbedModelCache: load on first use, release after 60 s idle, reload on path/size/mtime change, release at once on embed failure — spec §6 (N loaded per call) — n/a — `ode/integration-report.md`
- ODE/integration — load-failure count in memory per model path (not persisted); idle cache for embed only — smallest seam; generate unused — restart needs two more failures before "Couldn't load — re-download" — `ode/integration-report.md`
- ODE/review I1 — -O3 in defaultConfig (all variants); on-device embeds go 8 texts per timed call (NATIVE_BATCH) rather than scaling the timeout — debug ggml was -O0; 64-text batch could pass 60 s — ~8× more JNI round trips — `ode/review-fixes-report.md`
- ODE/review I2 — nativeLoadModel(path, forEmbedding) creates the embed context and returns 0 on failure (counted as a load failure; no 6th JNI symbol) — OOM at context creation never raised the load-failure UI — none known — `ode/review-fixes-report.md`
- ODE/review I3 — LocalModelStore.setAllowMetered REPLACEs queued/running work with the new constraint (.part kept); no "Waiting for Wi-Fi" text — switch didn't reach live work — overlay shows "Downloading" while waiting — `ode/review-fixes-report.md`
- ODE/review I4 — HTTP call cancelled with the coroutine, stop check after each read and before write, per-model-file in-process Mutex (not FileChannel.tryLock) — stopped worker appended to the replacement's .part — a second-process writer could interleave (none exists) — `ode/review-fixes-report.md`
- ODE/review merge — merged feat (round 3) into on-device-embeddings; BackupRulesTest pins the mobile-data switch in excluded app_config — keep both sides — a restore brings the switch back off — `ode/review-fixes-report.md`

### Glasses minimise + icon
- GM — Task 3 tests written right after the code (coordinator allowed once for the user's drive) — priority — tests fit code rather than spec — `glasses-minimise-report.md`
- GM — double-tap exit gate arms under plain toast, pause splash or nudge; off under overlays with their own double-tap or while recording; a Confirm/Listening/Answer mid-window cancels with no pause — avoid instant accidental pause — a double-tap on a plain toast waits 3 s — `glasses-minimise-report.md`
- GM — countdown ring runs from armed_at_ms to the next capture — real delay varies with battery/backoff — ring full until first arm — `glasses-minimise-report.md`
- GM — while minimised, a Nudge pops up unless verbosity is Silent (reviewer: product call) — spec §4 — Medium users see nudge pop-ups over Maps — `glasses-minimise-report.md`, `glasses-minimise/final-review.md`
- GM — phone "Open on glasses" detected as relaunch within 5 s of session reopening while minimised (reviewer rejected as built: event order probably reversed, I2) — glasses can't tell Rokid relaunch from launcher tap — HUD pulled over Maps — `glasses-minimise-report.md`, `glasses-minimise/final-review.md`
- GM — Confirm inputs ignored 600 ms after auto-open; re-minimise when the phone clears/replaces the Confirm; overlays drawn as Android Views — gesture tail must not vote; no lifecycle owner — a very fast vote needs a second tap — `glasses-minimise-report.md`
- GM review I1 — fast 4-tap: DoubleTapDetector settle guard (600 ms) swallowed taps 3–4 so capture paused instead of minimising; fix suggested: settle only the other key kind — no report confirms the fix (a merge commit message, db8dfea, mentions one) — `glasses-minimise/final-review.md`

### Minimised link (Option A)
- ML investigation — root cause: phone-side CxrSessionImpl moves the session to Terminating(GLASSES_APP_EXIT) whenever any other package is top-resumed on the glasses, then closes after the 5 s grace; no flag avoids it — SDK bytecode + logcat — n/a — `minimised-link-investigation.md`
- ML investigation — choose Option A (session-less CXRLink as two-way data link, never appStart) over Option B (wearer-started sync that briefly covers Maps) — only route that keeps transfer without bringing the HUD forward — Hi Rokid might not forward to a never-opened client (probe step 1 decides) — `minimised-link-investigation.md`
- ML — spec §1.7/§3.1 amend: frames may be sent while the CUSTOM_APP session is open or the background link is up and the app not exited; a phone reconnect never reopens the HUD — design — n/a — `minimised-link-investigation.md`
- ML ruling 1 — GLASSES_EXIT → MINIMISED at once; no glasses message within CONFIRM_MS = 10 s of bind (or bind fails) → CLOSED_ON_GLASSES, link stays bound to watch for reopen — SDK reports minimise and exit the same way — real exit shows MINIMISED ≤10 s — `minimised-link-report.md`
- ML ruling 2 — no early bind on onSessionTerminating — SessionInbound holds one key; early bind cuts inbound during grace — 5 s gap (acks make it safe) — `minimised-link-report.md`
- ML ruling 3 — a failed background bind is tried once per stay out of the foreground — avoid loops — n/a — `minimised-link-report.md`
- ML I2 — while MINIMISED, manualConnect does nothing; "Open on glasses" sends a 5 s pop-up "Running in the background · open it on your glasses"; Confirm still opens the HUD from the glasses side — appStart would steal the foreground (spec §1.7) — n/a — `minimised-link-report.md`
- ML — MINIMISED keeps the clock offset and pc_state heartbeats (phoneLinked=true); voice told link lost (push-to-talk foreground-only); LinkState.MINIMISED additive, not on the wire — design — n/a — `minimised-link-report.md`
- ML — reuse ContextHubService (connectedDevice FGS, START_STICKY) as the "nearby device"; notification "Connected to glasses" for CONNECTED and MINIMISED; LiveFit CompanionDeviceManager approach not built — FGS already up whenever the link exists — n/a — `minimised-link-report.md`
- ML — PcLinkProbe logs (debug builds) counts and codes only, never payloads — go/no-go evidence without leaking data — n/a — `minimised-link-report.md`

## 2. Probes, spikes and measured facts

### Phase 0 spikes (phone SM-S931B, Hi Rokid global app, RG glasses SDK 32; client-l 1.1.2, cxr-service-bridge 1.5)
- CXR API shape — `CxrSession.sendCustomCmd` returns SessionResult(code=OK, data=null, message=null), not Int/Boolean; auth ok, session up ~2.5 s after auth — `2026-10-12-v1-phase0-spikes/results-notes.md`
- Ping RTT phone→glasses→phone — 127 (warm-up), 34, 41, 61, 60 ms; median 60 ms; 5/5 pong — `2026-10-12-v1-phase0-spikes/results-notes.md`, `progress.md`
- Glasses clock offset — glasses ahead of phone by ~107–144 ms — `2026-10-12-v1-phase0-spikes/results-notes.md`
- CXR message size limit — G→P probe_data 4…256 KB all 5/5 received, CRC intact, rc=0; no limit hit up to 256 KB; SAFE_CHUNK_CHARS = 32768 (plan cap) — `2026-10-12-v1-phase0-spikes/results-notes.md`
- Frame chunking — 20 396 B JPEG → 27 196 Base64 chars → 4 chunks @8K / 2 @16K / 1 @32K; single-frame delivery 143–262 ms; 0 timeouts/nacks — `2026-10-12-v1-phase0-spikes/results-notes.md`
- Per-chunk acks — not needed (no missing/CRC-failed frames without them; they add ~40–80 ms) — `2026-10-12-v1-phase0-spikes/results-notes.md`
- NACK/drop/dedupe — corrupted frame NACK→resend→ingested (312 ms); always-corrupt frame dropped after 2 NACKs, others delivered; lost-ack → 5 s timeout, resend, phone dedupes by frameId (7194 ms) — `2026-10-12-v1-phase0-spikes/results-notes.md`
- Backlog throughput — 20 frames @16K: 20/20, 0 retries, 3835 ms total, per-frame min 137 / median 166 / max 306 ms (~5.2 frames/s, ~106 KB/s) — `2026-10-12-v1-phase0-spikes/results-notes.md`
- BT drop 5 s — session paused BT_DISCONNECTED 0.05 s after off, resumed by itself ~2.3 s after BT on; in-flight frame delivered on attempt 3; 20/20 sent, 0 lost, 0 double-ingested — `2026-10-12-v1-phase0-spikes/results-notes.md`
- BT drop 20 s — flush stopped retained=16 after 3 timeouts; "Flush retained" delivered 16/16 in 5495 ms; 0 lost, 0 dupes — `2026-10-12-v1-phase0-spikes/results-notes.md`
- Post-reconnect throughput — per-frame time ~2× (median ~330 vs ~165 ms) and one 3.7 s stall (single observation) — `2026-10-12-v1-phase0-spikes/results-notes.md`, `progress.md`
- Audio vs flush — PTT audio (3200-byte chunks every 100 ms) alone p50 ~100 / p95 138–164 ms; during flush p95 210–251 ms; ratio 1.3–1.8× (< 2× must-suspend threshold) but §1.8 suspension adopted; audio never lost (96–97 chunks); flush slows 3.8 → ~5.0 s; first-run max 1189 ms outlier — `2026-10-12-v1-phase0-spikes/results-notes.md`
- Background sending — glasses activity in front: flowing (maxGap 2039 ms); BACK to launcher: GLASSES_APP_EXIT then GLASSES_EXIT after 5 s grace, glasses keep sending rc=0 but app session no longer delivers → SILENT LOSS (rc=0 ≠ delivered); phone re-Connect brings activity back; display asleep with activity resumed: flowing (22 hb / 44 s, re-run 7→24 in 34 s, maxGap 2105 ms, onPause+onStop logged) — `2026-10-12-v1-phase0-spikes/results-notes.md`
- TTS route — phone TextToSpeech is heard in the glasses over the existing BT A2DP link (Glasses_9120, AAC); glasses-side TTS init=-1, no engine → Phase 6 TTS is phone-only — `2026-10-12-v1-phase0-spikes/results-notes.md`
- STT — API 37, recognition + on-device recognition available; EXTRA_AUDIO_SOURCE pipe injection works (written 134142/134142, no 64 KiB plateau); onResults final = null even with complete partials → keep last non-empty partial; hi-IN on-device not probed — `2026-10-12-v1-phase0-spikes/results-notes.md`
- Pipe capacity — 64 KiB plateau is the "not reading" signal; production guard >2 s fed and <64 KiB consumed — `2026-10-12-v1-phase0-spikes/progress.md`

### Phases 1–8, features and device rounds: measured values
- P1/T8 glasses smoke — capture → guardrails → 27 KB frame sent, phone acked, FRAME_INGEST SUCCEEDED; summarizer DEFERRED after REPLACE cancellation (pre-existing) — `2026-10-12-v1-phase1-restructure/progress.md`
- P2/T5 upgrade import — Room memory 105 (= legacy 105), frame 99 (= 99), summary_trace 132/134 (2 excluded by rule), memory_fts 105, TRUSTED traces 104 (= Home "Trusted Memories: 104") — `2026-10-12-v1-phase2-data-layer/progress.md`
- P2 smoke — seeding created an Azure endpoint (https) + 6 role bindings → gpt-5.4 — `2026-10-12-v1-phase2-data-layer/progress.md`
- P3/T2b — phone+glasses debug APK dex grep for key names = 0 (worktree without env file; re-check on main build) — `2026-10-12-v1-phase3-cxr-link/progress.md`
- P3/T11 — hello offsetMs=172; BT off 39 s: 3 buffered, PAUSED_BT→CONNECTED by itself, 3 delivered, 0 dupes — `2026-10-12-v1-phase3-cxr-link/progress.md`
- P4 trial merge — P3@49244eb + P4@52b0543: 3 additive conflicts, combined gate 896 tests / 0 failures; T11 gate 910/0 then 932/0; dex key scan 0/20 — `2026-10-12-v1-phase4-phone-shell/progress.md`
- P4/T11 device — PromptComposer regex with unescaped '}' crashed class init on Android ICU (every summary failed, Home "AI unavailable"), fixed c2a682a; after fix frame ingested, QueueDrainWorker SUCCESS, Home "● Capturing · next 4:20", 0 FATAL — `2026-10-12-v1-phase4-phone-shell/progress.md`
- P5 gates — final suite 1714/0 with --rerun-tasks, Room schema v1 confirmed; final-fix gate 1040 debug unit tests (phone 282, glasses 102, ai 249, orchestration 174, memory 126, core/model 27, core/hud 56, core/ui 24), 0 failed — `2026-10-12-v1-phase5-ai-settings/progress.md`, `final-fix-report.md`
- P5/T10 perf — lexical paging ~11× slower than one pass at 50k common-term matches (0.2 s desktop) — `2026-10-12-v1-phase5-ai-settings/progress.md`
- P5/T8 scale — loading whole vector space would OOM at ~50k memories → stream pages with bounded top-k heap — `2026-10-12-v1-phase5-ai-settings/progress.md`
- P6 pipe capacity on device — F_SETPIPE_SZ pin accepted, measured capacity = 51 200 bytes (4 KiB pages); probe fed 166 142 / accepted 166 142 → SUPPORTED (build 988cea9) — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/progress.md`
- P6 voice latency — glasses ask answered and spoken in ~8.5 s; phone-mic ask ~8 s — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/progress.md`
- P6/P8 gates — Phase 6 combined gate 2190 tests / 0 failures (ddec82d); Phase 8 streams merged gate 2412 / 0 (679afab); Phase 7 integration gate 1724 tests — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/progress.md`
- P6 root cause — back on the root activity only stops (doesn't finish) the activity on Android 12+, so dispose/onCleared don't fire — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/progress.md`
- P6/T5 pipe models — page-slot fake capacities: 4 KiB × 16 = 51 200; 16 KiB × 4 = 64 000; 16 KiB × 16 = 256 000; probe feed 166 142 bytes (134 142 clip + 1 s tail); SUPPORTED needs accepted − capacity ≥ fed/2; decidable only when capacity ≤ fed/2 (83 071) and ≤ clip — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/task-5-report.md`, `task-6-report.md`
- P6/T6 probe asset — voice_probe.pcm 134 142 bytes (~38 142 bytes speech + 3 s zeros), SHA-256 prefix 09dbcaeb… — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/task-6-report.md`
- P6/T1 VAD — EnergyVad RMS threshold 300 (amp 424 ≈ RMS 299.5 silent, 425 ≈ 300.2 speech), 5 s no-speech, 15 s cap (unmeasured on Rokid mic) — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/task-1-report.md`, `progress.md`
- P7 integration gate — at 1827cb6: phone 517, ai 268, orchestration 247, glasses 149, memory 148, voice 134, glasses-link 75, core:hud 63, connectors 57, core:model 40, core:ui 26; 0 failures (cdd84ce: connectors 58, orchestration 248) — `archive/pca-p7/p7/integration-report.md`
- P8/B — 50k common-term lexical search ~0.86 s (old paged ~1.3 s) under Robolectric — `archive/pca-p8-b/p8/stream-b-report.md`
- P8/E1 gate — 2894 test executions, 0 failures (phone 532, glasses 185, ai 282, orchestration 286, memory 172, voice 134, glasses-link 90, connectors 58, core/hud 64, core/model 43, core/ui 26) — `archive/pca-p8/p8-e1-report.md`
- P8/E23 — device checklist has 35 steps: S-LINK…S-FORGET (9, spec §6.4), D-A1…D-E2 (13), P7-1…P7-13 (13) — `archive/pca-p8/p8-e23-report.md`
- P8 final fix gates — fix-device 2900 tests / 0 failures; fix-phone 2898 / 0; fix-data and fix-actions green; P7×P8 integration green at c74bb76 — `archive/pca-p8/final-review/fix-*-report.md`, `archive/pca-p8/p7p8-integration-report.md`
- ODE model — bge-small-en-v1.5 q8 GGUF: 36 806 944 bytes, SHA-256 matches catalog (prefix ec38e8da…); bert, CLS pooling, 384 dims, context 512; our JNI output identical to llama-embedding (L2 norm 1.0) — `ode/stream-n-report.md`
- ODE native size — libllama_jni.so 9 793 136 bytes in the AAR (-O0, stripped); debug APK unstripped 67.5 MB before ndkVersion pin; 9 793 504 bytes stripped in phone-debug.apk (APK 101 746 516 bytes); 4 548 704 bytes after -O3 — `ode/stream-n-report.md`, `integration-report.md`, `review-fixes-report.md`
- ODE gates — integration green; review-fixes 3209 tests / 0 failures — `ode/review-fixes-report.md`
- Feature gates — agent-flows 3031 then 3041 tests; glasses-minimise 3247; minimised-link 3281; devfix/batch1 2940; r2a 3064; r2b 3079; r3a 3128; r3b 3121 — all 0 failures — respective reports
- Minimised-link root cause (investigation) — phone CxrSessionImpl ends the session ~5 s after any other package is top-resumed on the glasses; glasses keep sending (pc_hello 2837 bytes seen while minimised) and the phase-0 run (b) saw delivery to a bound client during the grace; the session-less CXRLink binds without appStart and registers a custom-command callback on every bind — `minimised-link-investigation.md`
- Minimised-link probe — PcLinkProbe built (debug builds; rx/tx counts and codes only); go/no-go criteria: `tx cmd=pc_hello_request result=0` then `rx cmd=pc_hello` within ~2 s while another app is in front. **No report on disk records the device result** (the reports say "No device was used") — `minimised-link-report.md`
- Device facts from rounds — glasses listening window ~4.4–5.4 s; frames ~16 KB; reminder alarm visible in dumpsys alarm as RTC_WAKEUP; backup tars 2.68 MB / 3.16 MB / 3.78 MB — `device-run-2-report.md`, `device-run-3-report.md`

## 3. Device test results

### Per-phase device checks before the device-test rounds
- P0 spikes — all Task 1–4 device runs matched Expected (ping 5/5, all sizes 5/5, BT drops 0 lost) — `2026-10-12-v1-phase0-spikes/progress.md`
- P1/T8 — PASS phone install + Home renders; PASS glasses capture→frame acked→FRAME_INGEST SUCCEEDED; SKIP Run Smoke tap — `2026-10-12-v1-phase1-restructure/progress.md`
- P2/T5, P2 smoke — PASS upgrade install, import counts match legacy; SKIP UI visual check; SKIP airplane-mode retry (T8) — `2026-10-12-v1-phase2-data-layer/progress.md`
- P3/T11 — PASS glasses checks (single alarm, buffering, pause gate + buffer clear across restart, dedupe, buffer survives reinstall/force-stop); FAIL→fixed alarm not re-armed after force-stop (313c4c7); PASS Step 9 frames end-to-end, Step 10 BT off 39 s, Step 11 force-stop/no reconnect — `2026-10-12-v1-phase3-cxr-link/progress.md`
- P3/T12 — PASS 0 RokidBridgeService, ContextHubService running, CAPTURE_NOW → hello + frame ingested, no FATAL — `2026-10-12-v1-phase3-cxr-link/progress.md`
- P4/T11 Step 11 — FAIL→fixed PromptComposer ICU regex crash (c2a682a) and hello-before-re-arm "next now"; then PASS frame → summary → Home "Capturing · next 4:20", 0 FATAL — `2026-10-12-v1-phase4-phone-shell/progress.md`
- P6 device (2026-10-13 20:56, build 988cea9) — PASS pipe pin, capacity 51200, probe SUPPORTED; PASS glasses ask answered and spoken (~8.5 s); PASS double-tap cancel; 0 FATAL — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/progress.md`
- P6 device (21:19, build 77090bf) — PASS phone-mic ask spoken (~8 s); FAIL back gesture while LISTENING did not cancel → devfix f92642a — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/progress.md`

### Round 1 — 2026-10-14 01:15–02:33 IST, build b961170 (35 steps)
- Counts — PASS 15 · PARTIAL 2 · FAIL 1 · SKIP 17 — `device-run-report.md`
- S-LINK PASS — link=CONNECTED, hello; HUD REC/phone icon/100%/NEXT — `device-run-report.md`
- S-CAPTURE PASS — capture-now: delivered, flush done, frame ingested — `device-run-report.md`
- S-ASK PASS (answer only) — glasses ask answered on HUD; not spoken (glasses asks speak only with an A2DP output); phone asks spoken — `device-run-report.md`
- Back-fix f92642a PASS — BACK while LISTENING cancels; BACK while THINKING still answers — `device-run-report.md`
- S-NUDGE PASS — nudge shownOn=toast, Glance one-liner, phone feed + detail — `device-run-report.md`
- S-ACTION / P7-6 PASS — simultaneous HUD tap and phone "No thanks" → one outcome, one alarm at 18:00; reboot re-arm not tested — `device-run-report.md`
- S-PAUSE PASS; S-FORGET PASS (fact gone, siblings kept; moment gone with memories and JPEG) — `device-run-report.md`
- P7-1 PARTIAL — hello buffered=0 only after the next frame (OBS-1) — `device-run-report.md`
- P7-5 PASS at Max (Silent not run); P7-10 PASS; P7-11 PASS (partial); P7-12 PASS (partial, Delete all not run); D-A3 PASS (partial; StrictMode not in build); D-C3 PASS (approx., am force-stop); D-E1H PASS (7 HUD refs) — `device-run-report.md`
- P7-7 PARTIAL — auto-add works; undo double-tap missed the toast — `device-run-report.md`
- D-E1 FAIL — memory-detail photo eats drag-to-scroll (BUG-A, fixed on devfix/memory-scroll 7a08a5f, not merged in round 1) — `device-run-report.md`
- Bugs found — BUG-1 Activity Today "No photo" for earlier-day frames; BUG-2 9 legacy memories store raw JSON; OBS-1 no pc_hello after phone-side restart until next frame; OBS-2 checklist markers wrong; OBS-3 Home shows proposal cards only for nudges; OBS-4 raw ISO time / "6 AM or PM?" in answers — `device-run-report.md`

### Round 2 — 2026-10-14 15:50–17:04 IST, build 4cb770c
- Counts — PASS 14 · PARTIAL 1 · FAIL 1 · SKIP 9 — `device-run-2-report.md`
- BUG-A fix PASS (drag on photo scrolls in landscape); BUG-1 fix PASS (earlier-day rows show photos) — `device-run-2-report.md`
- S-OFFLINE-PAUSE PASS; P7-2 PASS ("Still paused on glasses — Resume?"); S-FLUSH PASS (3 then 6 frames, once each, retained=0) — `device-run-2-report.md`
- D-A5 PASS at frame boundary — flush interrupted retained=9, hello buffered=9 exact, 10 unique ingests; mid-frame cut not achieved (~16 KB frames) — `device-run-2-report.md`
- P7-8 PASS ("Calendar permission needed"); P7-9 PASS with bug (all-day event as "today at 05:30", BUG-R2-3); P7-4 PASS (glasses self-paused offline, Home "Quiet hours"); P7-3 PASS (resume half not waited); D-C1 PASS (camera revoke: one denied line, no FATAL) — `device-run-2-report.md`
- P7-7 PARTIAL — undo toast replaced by Answer overlay after ~3 s (BUG-R2-5) — `device-run-2-report.md`
- D-D4 FAIL (graceful) — encryption ON failed at restart (EncryptionMigrator IllegalArgumentException), data intact, switch back OFF (BUG-R2-2) — `device-run-2-report.md`
- SKIP — D-A4/P7-12 Delete all (would cancel the 18:00 reminder), D-C2, D-A1, D-A2, D-C4, P7-13, D-C3, S-ASK TTS — `device-run-2-report.md`
- Agent flows PASS (1 UI bug: graph opens scrolled right, BUG-R2-1) — `device-run-2-report.md`
- Bugs found — BUG-R2-2 (high) encryption never turns on; SEC-1 (high, Rokid SDK) CXR SDK logs the auth token in plaintext on every connect; BUG-R2-5 (medium) undo toast cut short; BUG-R2-3 (medium) all-day events; BUG-R2-4 (medium, intermittent) NOT_AUTHORIZED after cold start with no auto-retry; BUG-R2-1 (low) flows graph scrolled right; BUG-R2-6 (low) quiet hours off leaves running pause, listed twice — `device-run-2-report.md`
- Observations — Embeddings role has no endpoint (no semantic half); calendar answer says "Proposed calendar event attached" while the tool is disabled; glasses listening window ~4.4 s cuts off speech — `device-run-2-report.md`

### Round 3 — 2026-10-14 18:11–18:55 IST, build d137b17 (phone fresh install)
- Counts — PASS 7 · PASS-with-bugs 2 · FAIL 1 · BLOCKED 1 — `device-run-3-report.md`
- D-A4 PASS — 4 frames buffered BT off, Delete all, BT on: hello buffered=4, 4× `dropped: captured before Delete all`, 0 ingested, flush sent=4 retained=0 — `device-run-3-report.md`
- P7-12 PASS — Home feed empty, Activity 0/0/0/0, storage 0 MB, 0 pending reminder alarms (Call Mom had already fired at 18:10, so the alarm-cancel path wasn't exercised) — `device-run-3-report.md`
- P7-6 reboot — "Call Mom" reminder fired at 18:10 (noted in round 3) — `device-run-3-report.md`
- P7-13 onboarding PASS with bugs — wizard only ran after resetting onboarding.xml (BUG-R3-1); voice probe "Supported"; glasses "Connected ✓"; key added in Settings (Test OK 2991 ms) (BUG-R3-2) — `device-run-3-report.md`
- Typed ask PASS (first try INVALID_OUTPUT, retry answered); glasses ask PASS partial — 7 tries: 2 answered with cut transcripts ("What is", "And"), 1 INVALID_OUTPUT, 4 no speech (BUG-R3-3) — `device-run-3-report.md`
- D-D4 FAIL (graceful) — `encryption change failed (SQLiteException, reason=REBUILD_INDEX)`; bind-args fix confirmed (ATTACH and EXPORT pass); data readable both restarts — `device-run-3-report.md`
- P7-7 phone-typed PASS — HUD shows the answer with "Added · double-tap undo"; double-tap `undo asked`, item removed (BUG-R2-5 fixed) — `device-run-3-report.md`
- P7-7 glasses ask BLOCKED (BUG-R3-3); Agent flows PASS (graph opens on the main column, BUG-R2-1 fixed) — `device-run-3-report.md`
- Bugs found — BUG-R3-1 (high) reinstall skips onboarding (Auto Backup restores onboarding.xml and other prefs); BUG-R3-2 (medium) wizard can't add a key to the build seed; BUG-R3-3 (high) most glasses voice asks get no answer and the phone logs no outcome; OBS-R3-1 (medium) ~2/6 answers INVALID_OUTPUT — `device-run-3-report.md`
- Environment — Rokid AssistServer `glassLegStatusChange spread[false]` force-stopped the glasses app (temple-fold sensor); phone onAiKeyDown → PAUSED_BT 10 s — `device-run-3-report.md`

### Minimised-link check (device script in `minimised-link-report.md`)
- Counts — PASS 0 · FAIL 0 · SKIP 7 — no report on disk records running the 7-step script (both minimised-link reports say no device was used); the go/no-go probe result is therefore not recorded — `minimised-link-report.md`, `minimised-link-investigation.md`
- Steps pending — 1 probe go/no-go; 2 frames while minimised (incl. Rokid navigation in front); 3 heartbeat ≥60 s with no `session closed: phone silent`; 4 I2 "Open on glasses" pop-up, no appStart; 5 hand-back with no double ingest; 6 real exit → CLOSED_ON_GLASSES within ~10 s; 7 nearby service survives phone HOME + swipe from recents — `minimised-link-report.md`
- Related glasses-minimise device checks (4-tap minimise, icon over Maps, ring, pop-ups, Confirm auto-open/re-minimise, icon corner, notifications over HUD) — not recorded as run — `glasses-minimise-report.md`

## 4. Deferred minors and known gaps, still open

### Phase 0
- P0 — p95 rise should read +46–110 ms — `2026-10-12-v1-phase0-spikes/progress.md`
- P0 — first-audio-after-idle 1.19 s stall not carried to Phase 6 — `2026-10-12-v1-phase0-spikes/progress.md`
- P0 — 2 s retry backoff not in spec §1.8 — `2026-10-12-v1-phase0-spikes/progress.md`
- P0 — post-reconnect throughput halving is a single observation — `2026-10-12-v1-phase0-spikes/progress.md`
- P0 — §3.6 offline line should say flush waits for session open — `2026-10-12-v1-phase0-spikes/progress.md`
- P0 — "with flush suspended" case = "alone", not stated — `2026-10-12-v1-phase0-spikes/progress.md`
- P0 — China Hi Rokid variant not marked not-observed — `2026-10-12-v1-phase0-spikes/progress.md`
- P0 — wear-sensor (glasses physically removed) state never tested; display sleep used as proxy — `2026-10-12-v1-phase0-spikes/results-notes.md`
- P0 — Hindi (hi-IN) on-device STT not probed — `2026-10-12-v1-phase0-spikes/results-notes.md`

### Phase 1
- P1 — Protocol.kt:6 KDoc should say new enum values / sealed subtypes / required fields are incompatible — `2026-10-12-v1-phase1-restructure/progress.md`
- P1 — QuietHours doesn't validate minutes in 0..1439 — `2026-10-12-v1-phase1-restructure/progress.md` — **CLOSED** — range-checked 0..1439 on phone and glasses (`archive/pca-p7-a/p7/stream-a-report.md`, M12)
- P1 — Wire accepts "v":"1" string — `2026-10-12-v1-phase1-restructure/progress.md`
- P1 — theoretical WorkManager-before-Application.onCreate EnvDefaults race; no test asserts manifest names ContextApp — `2026-10-12-v1-phase1-restructure/progress.md`
- P1 — AppConfigDefaultsTest mutates global EnvDefaults without reset — `2026-10-12-v1-phase1-restructure/progress.md`
- P1 — PromptTemplateRepository in :services:ai reads prompt assets shipped in :phone (silent fallback) — `2026-10-12-v1-phase1-restructure/progress.md`
- P1 — SwipeClassifier.kt:10 cites Live Fit "spec §6.3" (should be §3.5) — `2026-10-12-v1-phase1-restructure/progress.md`
- P1 — :glasses:lintDebug fails on the PermissionImplies-UnsupportedChromeOsHardware lint check — `2026-10-12-v1-phase1-restructure/progress.md`
- P1 — Run Smoke tap never confirmed on device — `2026-10-12-v1-phase1-restructure/progress.md`

### Phase 2
- P2/T1 — DatabaseFactory.inMemory allowMainThreadQueries in main source set — `2026-10-12-v1-phase2-data-layer/progress.md`
- P2/T1 — MemoryDao.recent/all include INVALIDATED while between/search exclude — `2026-10-12-v1-phase2-data-layer/progress.md`
- P2/T1 — no FKs/cascades on memory_source/memory_vector (Forget must delete transactionally) — `2026-10-12-v1-phase2-data-layer/progress.md` — **CLOSED** — Forget deletes vectors and edges in one transaction (`archive/pca-p7-f/p7/stream-f-report.md`, F1)
- P2/T1 — SQLCipher passphrase not zeroed; loadLibrary every call; SQLCipher path untested — `2026-10-12-v1-phase2-data-layer/progress.md` — **CLOSED** — passphrase copies zeroed, library loaded once (`archive/pca-p8-d/p8/stream-d-report.md`, D4); real SQLCipher path still device-only
- P2/T1 — Upsert regression test non-discriminating under Robolectric; stale "// REPLACE" comment MemoryDaoTest.kt:48 — `2026-10-12-v1-phase2-data-layer/progress.md`
- P2/T2 — SchemaTest checks table names only; EndpointDao/RoleBindingDao delete/upsert-overwrite untested — `2026-10-12-v1-phase2-data-layer/progress.md`
- P2/T2 — no FK role_binding.endpointId→endpoint — `2026-10-12-v1-phase2-data-layer/progress.md`
- P2/T3 — byIds/deleteByIds unchunked IN (>999 vars); thin RepositoriesTest; flag written before build; EncryptedSharedPreferences created per call — `2026-10-12-v1-phase2-data-layer/progress.md` — **PARTLY CLOSED** — IN lists chunked at 500 (`archive/pca-p8-b/p8/stream-b-report.md`, B4) and DbKeys creates prefs once (`archive/pca-p8-d/p8/stream-d-report.md`, D4); thin tests still open
- P2/T4 — non-numeric confidence line skipped (lost); importer worker no try/catch→retry; per-row inserts; whole-file readLines; loose/missing importer tests — `2026-10-12-v1-phase2-data-layer/progress.md` — **CLOSED** — streamed 500-row chunks, 0.0 for bad confidence, worker retries ×3 (`archive/pca-p8-d/p8/stream-d-report.md`, D5)
- P2/T5 — "synthetic"/"screenshot" matched on the full lower-cased path (a parent dir with the word would purge real JPEGs) — `2026-10-12-v1-phase2-data-layer/progress.md` — **CLOSED** — file name only (`archive/pca-p8-a/p8/stream-a-report.md` A4; `archive/pca-p8-d/p8/stream-d-report.md` R-D5a)
- P2/T5 — duplicate frameId append no-op; recent() ordering; 2× over-fetch; failing importAll rethrows from every facade; facade test gaps — `2026-10-12-v1-phase2-data-layer/progress.md`
- P2/T5 — ensureLegacyImported once-test is sequential not concurrent; eager import holds importLock (main-thread facade call can block at startup) — `2026-10-12-v1-phase2-data-layer/progress.md` — **PARTLY CLOSED** — on Main ensureLegacyImported returns null without the lock (`archive/pca-p8-d/p8/stream-d-report.md`, D5); concurrency test still sequential
- P2/T6 — endpoint tester/input edge cases (humanize regex, ConnectException text, swallowed CancellationException, unescaped model/apiVersion, commas in model names, host edge cases, 90 s chat probe, no EncryptedKeyVault test) — `2026-10-12-v1-phase2-data-layer/progress.md`
- P2/T6 — leading-zero octets pass isLocalHost; OllamaProvider follows redirects; rows stored before validation not re-checked — `2026-10-12-v1-phase2-data-layer/progress.md`
- P2/T7 — router/seeder minors (repeated fallback pair, "failed: null" message, fallback==primary allowed, swallowed cancellation, init order, runBlocking on ai-seed thread, db-journal / app_config_secret backup gap) — `2026-10-12-v1-phase2-data-layer/progress.md`
- P2/T7 — keyFor() in failure path can mask the original error; redaction exact-match only (URL-encoded/partial keys not masked) — `2026-10-12-v1-phase2-data-layer/progress.md` — **CLOSED** — key read once; raw, URL-encoded and Base64 forms masked (`archive/pca-p8-d/p8/stream-d-report.md`, D3)
- P2/T8 — PERMANENT_TEXT phrases match inside 5xx bodies; RetryClassifier duplicates ProviderErrors rules; cancellation increments attemptCount; no attempt cap on RETRY — `2026-10-12-v1-phase2-data-layer/progress.md`
- P2/T9 — overlapping LegacyConfigBridge syncs can finish out of order (needs Mutex); debounce cancelled on leaving screen within 600 ms — `2026-10-12-v1-phase2-data-layer/progress.md`
- P2/final — frameIdOf no longer sanitises (comma path breaks CSV); EndpointRepository.delete can throw from vault.remove; orphan key on crash; archived-but-failed ingest never retried — `2026-10-12-v1-phase2-data-layer/progress.md` — **PARTLY CLOSED** — delete never throws, orphan keys swept (`archive/pca-p8-d/p8/stream-d-report.md`, D2); failed ingest returns to inbox (`archive/pca-p8-a/p8/stream-a-report.md`, A4); comma-path CSV still open
- P2/T8 — offline (airplane-mode) retry not verified on device — `2026-10-12-v1-phase2-data-layer/progress.md`

### Phase 3
- P3/T1 — FrameCodec.join doesn't check chunks.size == chunkCount; bad-Base64 test lacks padding/truncation cases — `2026-10-12-v1-phase3-cxr-link/progress.md`
- P3/T2 — ModelErrorMapper matches "was deleted"/"no model set" anywhere; cancellations/deferrals consume the 8-attempt cap; deferral text not UserFacingError-mapped — `2026-10-12-v1-phase3-cxr-link/progress.md`
- P3/T2b — key guard covers phone module only; name-only (values with embedded creds not rejected); no-arg OpenAiCompatProvider() has empty key — `2026-10-12-v1-phase3-cxr-link/progress.md` — **PARTLY CLOSED** — Gradle guard now rejects values with embedded credentials (`archive/pca-p8-d/p8/stream-d-report.md`, D3)
- P3/T3 — AI_ASSIST pause shown as PAUSED_BT; old-session late messages can re-arm listenAccepted; JSON without v → VersionMismatch; prefs read on Main; GlassAppWatcher leaks CXRLink on bind failure; no CxrGlassesLink tests — `2026-10-12-v1-phase3-cxr-link/progress.md` — **PARTLY CLOSED** — per-session inbound stops old-session messages (`archive/pca-p6/2026-10-12-v1-phase6-voice-answers/task-3-report.md`); GlassAppWatcher disconnects on bind failure (`archive/pca-p8-c/p8/stream-c-report.md`, C4)
- P3/T3 — GlassesSessionPolicy imports SDK SessionErrorCode — `2026-10-12-v1-phase3-cxr-link/progress.md`
- P3/T4 — reassembler has no cap on concurrent partials/chunk length; ledger durability/edge tests missing — `2026-10-12-v1-phase3-cxr-link/progress.md` — **CLOSED** — bounded partials, oversize → NACK, ledger load/fsync hardening (`archive/pca-p8-a/p8/stream-a-report.md`, A3)
- P3/T5 — broadcaster catch(Exception) lets Errors kill the loop; AiHealthRule maxBy tie nondeterministic; unknown origin → PHONE silently — `2026-10-12-v1-phase3-cxr-link/progress.md` — **PARTLY CLOSED** — onError guarded (`archive/pca-p8-c/p8/stream-c-report.md`, C5)
- P3/T5 — orphan closed at now(); tombstones pruned at 7 d; active() expiry close+delete not one transaction — `2026-10-12-v1-phase3-cxr-link/progress.md` — **CLOSED** — atomic PauseDao.expire; 90-day resume tombstones (`archive/pca-p7-a/p7/stream-a-report.md`)
- P3/T6 — parent dir not fsynced after rename; orphan .part files never swept; startForeground-refused path — `2026-10-12-v1-phase3-cxr-link/progress.md` — **CLOSED** — dir fsync, orphan .part sweep (`archive/pca-p8-a/p8/stream-a-report.md`, A4); refused start handled (`archive/pca-p8-c/p8/stream-c-report.md`, C1)
- P3/T6 — onLinkState unguarded; hello-before-ready discards fresh offset (self-heals) — `2026-10-12-v1-phase3-cxr-link/progress.md`
- P3/T7 — bridge-thread callbacks unserialised; missing OUTDATED/hello-request tests; cache.write on bridge thread — `2026-10-12-v1-phase3-cxr-link/progress.md`
- P3/T7 — exited phoneMessage still updates liveness; tombstones pruned only in merge — `2026-10-12-v1-phase3-cxr-link/progress.md`
- P3/T8 — unreadable JPEG deleted; lost kick race; no 4 MB byte cap; disk-full drops newest; 72 h cap on glasses wall clock; no dir fsync; test gaps — `2026-10-12-v1-phase3-cxr-link/progress.md` — **CLOSED** — uptime age, byte cap, evict-oldest on full disk, dir fsync, kick handoff, transient read keeps frame (`archive/pca-p8-a/p8/stream-a-report.md`, A1/A2)
- P3/T8 — crash in oldest() charged to previous id; transient crashes accumulate; clear during ack wait noticed after timeout; poison drop ends flush — `2026-10-12-v1-phase3-cxr-link/progress.md` — **CLOSED (`archive/pca-p8-a/p8/stream-a-report.md`, A2)**
- P3/T9 — overlay equality by data; phone overlay timer under local toast; nav idle ignores overlays; long-press ENTER emits Tapped — `2026-10-12-v1-phase3-cxr-link/progress.md` — **PARTLY CLOSED** — long-press is not a tap (`archive/pca-p6/2026-10-12-v1-phase6-voice-answers/task-2-report.md`)
- P3/T10 — presenter mutates state during composition; "offline" while CONNECTING; filled REC dot; redundant foundation dep; nextCaptureAt polling; backgrounded-not-finishing keeps session open — `2026-10-12-v1-phase3-cxr-link/progress.md` — **PARTLY CLOSED** — "connecting" no longer shown as offline (`archive/pca-p6/2026-10-12-v1-phase6-voice-answers/task-2-report.md`)
- P3/T10 — release() doesn't replay connection state to a later controller — `2026-10-12-v1-phase3-cxr-link/progress.md` — **CLOSED** — SharedBridge replays lastConnected (`archive/pca-p8-c/p8/stream-c-report.md`, C4)
- P3/T11 — unknown battery fails open; stopSelf without startId; SurfaceTexture not released on bind failure; foreground fallback on persistent camera failure — `2026-10-12-v1-phase3-cxr-link/progress.md` — **PARTLY CLOSED** — CaptureSession release, stopSelf(startId), fallback cap 3 (`archive/pca-p8-c/p8/stream-c-report.md`, C1/C2); unknown battery still fails open
- P3/T11 — togglePause on main may wait on a JPEG write; phone-merged pause doesn't clear already-buffered glasses frames — `2026-10-12-v1-phase3-cxr-link/progress.md` — **CLOSED** — merge clears buffer; togglePause clears on IO (`archive/pca-p7-a/p7/stream-a-report.md`, A2)
- P3/T11 — one capture with HUD backgrounded > 5 min (background camera FGS on RG firmware) unproven — `2026-10-12-v1-phase3-cxr-link/progress.md`
- P3/T12 — legacy broadcast-form FIRE_PULSE alarm not cancelled; RfcommGoneTest scans .kt/.xml only — `2026-10-12-v1-phase3-cxr-link/progress.md`
- P3 → P8 — Rokid AssistServer force-stops the glasses app (closing = force-stop, capture stops until reopened) — `2026-10-12-v1-phase3-cxr-link/progress.md` — **MITIGATED** — MISSED re-arm, toast and "Closed on glasses · capture stopped" banner (`archive/pca-p8-c/p8/stream-c-report.md`, C3); force-stop still seen in round 3

### Phase 4
- P4/T1 — FakeGlassesStatusSource counter not thread-safe; Fixtures.jpg() writes non-JPEG bytes — `2026-10-12-v1-phase4-phone-shell/progress.md`
- P4/T2 — 32 dp pause target; bar chart not TalkBack-reachable; labels/values length mismatch; EXIF orientation ignored; test gaps — `2026-10-12-v1-phase4-phone-shell/progress.md`
- P4/T2 — Phase 1 Basics.kt uses AutoMirrored.Rounded icons (Outlined rule) — `2026-10-12-v1-phase4-phone-shell/progress.md`
- P4/T3 — AskDao.upsert whole-row can reset rating; chatty table-level Flow invalidation; IN(:ids) unchunked; sanitizer misses \r / NBSP; MemoryEdits.memories param unused — `2026-10-12-v1-phase4-phone-shell/progress.md`
- P4/T4 — setEnabled not synchronised; PAUSED_BT capture line untested; StorageUsage happy-path test only — `2026-10-12-v1-phase4-phone-shell/progress.md`
- P4/T5 — raw memory text under RetrievedContext (forged rule lines); asking flag not reset on cancel; take() can split surrogate pairs; test gaps — `2026-10-12-v1-phase4-phone-shell/progress.md`
- P4/T6 — rating written before an unguarded enqueue; non-atomic double-tap enqueue; source cards don't observe memory changes; 👎 critic payload must exclude INVALIDATED text (Phase 7) — `2026-10-12-v1-phase4-phone-shell/progress.md` — **PARTLY CLOSED** — 👎 payload is ids only (`archive/pca-p7-f/p7/stream-f-report.md`, F2); Ask detail observes each memory (`archive/pca-p7-b/p7/stream-b-report.md`, B4)
- P4/T7 — empty hint flash; period highlight lags; Today/Yesterday title stale across midnight; empty tiles clickable — `2026-10-12-v1-phase4-phone-shell/progress.md`
- P4/T8 — transformable inside verticalScroll may fight scroll (device check); pager reset; autoplay stops at last frame — `2026-10-12-v1-phase4-phone-shell/progress.md`
- P4/T9 — pending debounced sync lost if screen closes within 600 ms; typedKey not cleared after persist; no re-entry guard on Test; registry may hold old key until next sync — `2026-10-12-v1-phase4-phone-shell/progress.md`
- P4/T10 — no permission rationale branch; hint flashes on first launch; LegacyPipelineControl caches enabled once per process; cold-start 84 dp fallback for one frame — `2026-10-12-v1-phase4-phone-shell/progress.md`
- P4/T11 — bufferedFrames can stay 1 too high until next hello; MainActivity.onCreate builds hub on main — `2026-10-12-v1-phase4-phone-shell/progress.md` — **CLOSED** — hello after every flush that sent or dropped (`archive/pca-p8-a/p8/stream-a-report.md`, A2)
- P4/final — AppConfigStore.get on main; Routes memory/ always highlights Activity; HomeScreen Rounded icons; FrameViewer reads zone once — `2026-10-12-v1-phase4-phone-shell/progress.md`
- P4/final — queued jobs' 24 h TTL can expire during a >24 h pause — `2026-10-12-v1-phase4-phone-shell/progress.md` — **CLOSED** — pause freezes TTL (`2026-10-12-v1-phase5-ai-settings/progress.md`, T13)
- P4/T11 — post-schedule hello can report buffered=1 off by one until next capture — `2026-10-12-v1-phase4-phone-shell/progress.md` — **CLOSED** — hello after flush (`archive/pca-p8-a/p8/stream-a-report.md`, A2)

### Phase 5
- P5/T1 — EndpointTester.humanize checks cause one level only — `2026-10-12-v1-phase5-ai-settings/progress.md`
- P5/T2 — OPENAI_COMPAT sends only max_completion_tokens; llama.cpp native ignores temperature/maxTokens; missing-usage tolerance untested — `2026-10-12-v1-phase5-ai-settings/progress.md`
- P5/T3 — enum/const compare numbers as text (1.0≠1); NullToAbsent ignores siblings of anyOf/oneOf — `2026-10-12-v1-phase5-ai-settings/progress.md`
- P5/T4 — stripped keywords not described; schema-valued additionalProperties forced false; $ref sibling description dropped; #/definitions refs dangle for Azure — `2026-10-12-v1-phase5-ai-settings/progress.md`
- P5/T5 — decode relies on schema bounds; empty-union message hardcodes "proposedAction"; POINTER ignores ~0/~1 escapes — `2026-10-12-v1-phase5-ai-settings/progress.md`
- P5/T6 — Mn invisibles (U+034F, variation selectors) can make marker look-alikes; source-scan tests depend on working dir — `2026-10-12-v1-phase5-ai-settings/progress.md`
- P5/T7 — SAFE_PATH lets ≤60 chars of a model-chosen map key into the exception message — `2026-10-12-v1-phase5-ai-settings/progress.md`
- P5/T8 — empty memory table counts as 100% coverage (untested) — `2026-10-12-v1-phase5-ai-settings/progress.md`
- P5/T9 — queued native call not cancelled after caller timeout; nativeScope has no close — `2026-10-12-v1-phase5-ai-settings/progress.md`
- P5/T10 — lexical paging re-reads FTS doclists per page (~11× slower at 50k matches); BM25 stats re-read per page (Phase 8 perf) — `2026-10-12-v1-phase5-ai-settings/progress.md` — **CLOSED** — one cursor per query (`archive/pca-p8-b/p8/stream-b-report.md`, B2)
- P5/T11 — µs race setRebuildPending vs REPLACE enqueue (one redundant rebuild); unstable state clears only on next run — `2026-10-12-v1-phase5-ai-settings/progress.md`
- P5/T12 — AskTextTest uses raw invisible U+2028/2029 literals — `2026-10-12-v1-phase5-ai-settings/progress.md`
- P5/T13 — KDoc indent nit in AppConfigStore — `2026-10-12-v1-phase5-ai-settings/progress.md`
- P5/T15 — stale emitted-record entries can mask an external restore — `2026-10-12-v1-phase5-ai-settings/progress.md`
- P5/T16 — slider local value resets if scale changes mid-drag; saved-key hint flashes in — `2026-10-12-v1-phase5-ai-settings/progress.md`
- P5/T17 — key edits don't clear a stale Test result; untick guard reads usages() outside the bindings lock — `2026-10-12-v1-phase5-ai-settings/progress.md`
- P5/T18 — lastRun assigned inside a retryable update lambda; edit→revert shows "✓ Ran" with no result — `2026-10-12-v1-phase5-ai-settings/progress.md`
- P5/T19 — off-Main tests bounded by 5 s real-time timeout; no test that Try it cancels on leave — `2026-10-12-v1-phase5-ai-settings/progress.md`
- P5/T20 — historical backfill enqueue carries pre-wipe frame ids; reentrant settings lock nit — `2026-10-12-v1-phase5-ai-settings/progress.md`
- P5/final — frame arriving mid-wipe can orphan JPEG/row; blocking execute() ignores cancellation — `2026-10-12-v1-phase5-ai-settings/progress.md`
- P5/final — quick pause→resume double tap toggles from stale `enabled` (second tap lost) — `2026-10-12-v1-phase5-ai-settings/progress.md` — **CLOSED** — PipelineControl tracks the requested state (`archive/pca-p6/2026-10-12-v1-phase6-voice-answers/task-4-report.md`)
- P5 pending-device — regexes on ICU / seed assets from APK (T6); battery-not-low + continuation over large backlog (T11); >24 h pause drain, VISION_EXTRACT one memory per fact (T13); SqlCipherMigrationDeviceTest 2/2 (T14); HUD unchanged (T15); StrictMode Keystore off Main, Re-authorize, HUD/cadence reach glasses (T16); editor scroll/chip wrap/real Test (T17/T18); Retrieval screen, Hybrid fallback, Rebuild while paused (T19); export share sheet, Delete all with frames, encryption toggle, review queue (T20); backup/restore exclusions (final) — `2026-10-12-v1-phase5-ai-settings/progress.md`, `final-fix-report.md`

### Phase 6
- P6/T1 — EnergyVad 5 s no-speech, 15 s cap, RMS 300 unmeasured on the Rokid mic (calibrate on device); ICU \s also matches NBSP — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/progress.md`
- P6/T2 — Pages.kt status glyph labels OUTDATED as offline — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/progress.md`
- P6/T3 — connect throwing after session = s never calls s.close() on the SDK session (Phase 8) — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/progress.md` — **CLOSED** — SessionSlot closes each session exactly once (`archive/pca-p8-c/p8/stream-c-report.md`, C4)
- P6/T4 — resync path untested; setEnabled launch outside lock; resync copies published not persisted flag — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/progress.md`
- P6/T5 — guard fires ~9 s on unpinned 16 KiB pipe; legit recognizer closing read end <2 s flagged (accepted) — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/progress.md`
- P6/T7 — synchronous onInit(false) from a fake leaks engine; old utterance's watchdog stop() can end a newer one — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/progress.md`
- P6/T10 — VoiceCapabilityService publish() read-then-write race on micHeld; misleading copy when mic refused from front; stale onCreate doc in HubActivityHook — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/progress.md` — **still open per `archive/pca-p8/final-review/review-device.md` M1**
- P6/T12 — Check again with identical caps leaves "Checking voice…" until next change — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/progress.md`
- P6/T13 — duplicate collapse in AnswerService; AskText doc points at old list — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/progress.md`
- P6/T14 — CalendarEvent blank title/maxLength not re-checked after decode; Azure drops $ref sibling description; EndpointEditor capability check outside lock; no test for paused EmbeddingWorker token path — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/progress.md`
- P6 WATCH — glasses PushToTalkTest stalled-read test (aStalledReadIsCancelled…) flaky under load (make watchdog clock injectable) — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/progress.md` — **still open per `archive/pca-p8/final-review/review-device.md` M2**
- P6 — voice_probe.pcm licence question unanswered (see §5) — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/progress.md`
- P6/P8 pending-device — pipe/O_NONBLOCK/EPIPE/ticker/SpeechPacks (T6); TTS focus with music/call, becoming-noisy on A2DP drop, Hindi offline voice (T7); AudioRecord startup, chunk timing, VAD thresholds, glasses mic prompt, BACK as double-tap, stuck read release, CXR send under lock (T9); Rokid RTC across reboot, boot_id readable, dir fsync, ENOSPC eviction, wipe with glasses backlog (P8 A); D-C4 close-after-SDK-closed (P8 C) — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/progress.md`
- P6/T4 — if the PipelineControl scope were cancelled before a launched coroutine started, requested would never resync (production scope never cancelled) — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/task-4-report.md`
- P6/T6 — AndroidPipes.capacity() cached per process (runtime pipe-max-size change missed) — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/task-6-report.md`
- P6/T7 — same caller id spoken twice back-to-back: a late onStop could end the second early (low risk) — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/task-7-report.md`
- P6/T7 → T16 — does the Samsung Google TTS report English/Hindi voices as embedded and installed? If English is network-only, every answer is HUD-only — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/task-7-report.md`
- P6/T9 — stop() takes effect only after the blocked read() returns (pc_listen_end can lag a tap by ~100 ms); watchdog-abandoned read thread stays blocked until HAL returns — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/task-9-report.md`
- P6/T10 — Hub still calls settings.read() (SharedPreferences) on Main; manifest check depends on AGP intermediate path; FallbackSuspected while mic not held shows "not supported" for that one ask — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/task-10-report.md`
- P6/T10 pending-device — mic FGS from resumed activity, connectedDevice fallback on sticky restart, glasses voice with phone locked, startForeground re-entry after late RECORD_AUDIO grant, micHeld after resume on API 30–33 and 34+ — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/task-10-report.md`
- P6 devfix — device re-verification of ON_STOP cancel (repro: `listening PHONE` then phone cancel, no `answered PHONE`) — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/devfix-cancel-report.md`

### Phase 7
- P7 WATCH — AppShellTest.leavingHomeCancelsAnInFlightAsk flaky under load; AndroidRegexRulesTest false positive on \p{Nd} — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/progress.md` — **PARTLY CLOSED** — \p{..} escapes skipped (`archive/pca-p7/p7/integration-report.md`, d5639ee); AppShellTest flake still open
- P7/B — undo state in-memory only; recover() only at install; BootReceiver drops past-due reminders; setWindow not allow-while-idle; toastTimer not thread-safe; undo/rearm race — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/progress.md` — **PARTLY CLOSED** — RecoverOnce on get() and install (`archive/pca-p7/p7/integration-report.md`); past-due reminders still dropped on boot (`archive/pca-p8/final-review/review-ai.md` M1)
- P7/F — invalidatedDerived(200) can split parent/child; LIKE scan per cited id; frame resend after Forget re-inserts photo; tombstones never pruned; no secure_delete — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/progress.md` — **PARTLY CLOSED** — tombstones pruned after 8 days (`archive/pca-p8/final-review/fix-data-report.md`, I2); LIKE scans still open (`review-data.md` M4)
- P7/C — quiet-hours gate should share Stream A's QuietWindow; place label stamped at worker time; calendar zone fixed at build; all-day/cancelled events — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/progress.md` — **PARTLY CLOSED** — QuietWindow shared by NudgeGates (`archive/pca-p7/p7/integration-report.md`, item 9)
- P7/A — DST overlap end uses earlier offset; glasses no-RTC quiet-hours miss; exact/inexact push ordering window; duplicate quiet-hours rows in Privacy; misleading "still paused on glasses" banner within 10 min — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/progress.md` — **PARTLY CLOSED** — DST gap handled (A1-a); "still paused on glasses" banner still open (`archive/pca-p8/final-review/review-phone.md` M4)
- P7/S0 — CAS row-count check in ProposalRepository not caught by a mutant (transaction already serialises; defence in depth) — `archive/pca-p7/p7/stream-0-report.md`
- P7/integration — RecoverOnce wiring checked only by a source rule (ActionsGraph singleton can't be reset) — `archive/pca-p7/p7/integration-report.md`
- P7/integration — VoiceGraph.onForeground itself not guarded on RESUMED (only HomeRoute caller is) — `archive/pca-p7/p7/integration-report.md`
- P7/integration — glasses QuietHoursClock still has its own copy of the quiet-hours math — `archive/pca-p7/p7/integration-report.md`
- P7/integration — full-size camera shots in cache/capture are not swept (deleted by ingest finally / prepare) — `archive/pca-p7/p7/integration-report.md`
- P7/A — battery copy: spec/plan say 20 % phone threshold, code enforces 30 % — someone must decide which is right — `archive/pca-p7-a/p7/stream-a-report.md`
- P7/B — calendar summary on Confirm shows start only (no end time) — `archive/pca-p7-b/p7/stream-b-report.md`
- P7/A, P7/B pending-device — Stream A device checks 1–4, Stream B device checks 6–8 — `archive/pca-p7-a/p7/stream-a-report.md`, `archive/pca-p7-b/p7/stream-b-report.md`
- P7/C — nudge cap count and insert are not atomic (safe only while unique work KEEP prevents overlap) — `archive/pca-p7-c/p7/stream-c-report.md`
- P7/C pending-device — device checks 5 (nudge toast and Glance), 9 (sources), 10 (Capture now) — `archive/pca-p7-c/p7/stream-c-report.md`
- P7/E — AppShellTest edited outside Stream E's set to walk the wizard — `archive/pca-p7-e/p7/stream-e-report.md`
- P7/E pending-device — OnboardingRoute permission launchers / FGS mic type not covered (device check 13) — `archive/pca-p7-e/p7/stream-e-report.md`
- P7/F — batched VISION_EXTRACT fact is stored against the newest frame only (later Forget of an older contributing frame doesn't reach it) — `archive/pca-p7-f/p7/stream-f-report.md`
- P7/F — not mutant-tested: rate Mutex, APPEND_OR_REPLACE policy, retention checkpoint and hub-refresh hook; F3 tests run only after implementing (mutant-only record) — `archive/pca-p7-f/p7/stream-f-report.md`
- P7/F → P8 — MainActivity purgeNonRokidMemories on every launch — `archive/pca-p7-f/p7/stream-f-report.md`

### Phase 8 (from the Phase 6 ledger and streams)
- P8/A — non-ENOSPC IOException evicts; ledger trim before write; FAILED file no attempt cap; public seams; uplink stuck if scope cancelled pre-start; reassembler 32 MiB bound — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/progress.md`
- P8/A — slow-clock-at-wipe corrected only by reboot lets pre-wipe frames pass (pre-existing hole) — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/progress.md`
- P8/B — stale .json.part never swept; export pages not in one transaction; first moveToNext FTS pass uncancellable; chunk size 500 vs 900; chunked deletes not atomic; JsonWriter throws on NaN confidence — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/progress.md`
- P8/C — SharedBridge replay outside lock; false "Capture resumed" toast (grace 5 s < WINDOW_MS 8 s); stopSelf(newest startId) on refused FGS; fallback count not reset on skipped frames; WeakHashMap equality — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/progress.md`
- P8/D — KeyVault.refs decrypts all values to list names; 40-hex lowercase tokens kept by the log rule; Base64 Basic-auth masking partial; deleted imported memory re-inserted by worker retry after facade import failure — `archive/pca-p6/2026-10-12-v1-phase6-voice-answers/progress.md`

### Phase 8
- P8/A — no test for the in-lock markStarted retry succeeding after a failed first write (ENOSPC can't be simulated) — `archive/pca-p8-a/p8/stream-a-report.md`
- P8/A — if the marker file can't be created and the process dies before space frees, the next process sees the previous fence (best-effort hole) — `archive/pca-p8-a/p8/stream-a-report.md`
- P8/A — no test for a throwing byIds in the cancel path; one elapsed time in deleteAll and rename-under-lock are review-only — `archive/pca-p8-a/p8/stream-a-report.md`
- P8/A — FIFO order across a backward wall-clock jump follows wall clock — `archive/pca-p8-a/p8/stream-a-report.md`
- P8/A pending-device — boot_id readable on Rokid and phone; FileChannel dir force works; NTP/manual clock jump with frames buffered; full disk evicts oldest; Delete all while glasses hold pre-wipe frames (acked wiped_drop); buffered count after NACK drops; Rokid RTC across reboot (frames dropped if clock >1 h wrong) — `archive/pca-p8-a/p8/stream-a-report.md`
- P8/B — 50k lexical budget is a JVM regression bound only (no RED possible) — `archive/pca-p8-b/p8/stream-b-report.md`
- P8/B pending-device — large real export (share sheet, size, flat heap); lexical/vector latency at 50k on device; API 29 SQLite 999-variable limit with >999-id Forget/Delete — `archive/pca-p8-b/p8/stream-b-report.md`
- P8/C — SharedBridge replay calls the new listener synchronously in setConnectionListener (a connected handler may send hello during controller setup) — `archive/pca-p8-c/p8/stream-c-report.md`
- P8/C — SurfaceTexture released twice on the normal path (plan calls it harmless) — `archive/pca-p8-c/p8/stream-c-report.md`
- P8/C pending-device — D-C4 failed watcher bind disconnects; CaptureService refused-start wiring — `archive/pca-p8-c/p8/stream-c-report.md`
- P8/D — a rejected legacy seed loses the legacy key (user must re-enter after fixing URL) — `archive/pca-p8-d/p8/stream-d-report.md`
- P8/D — MemoryGraph.kt shared edit wider than planned (facadeImportFailed, importLegacyForWorker, importOnce) — merge note — `archive/pca-p8-d/p8/stream-d-report.md`
- P8/D — SchedulerConfigReadsTest still has a stale [get] KDoc — `archive/pca-p8-d/p8/stream-d-report.md`
- P8/D pending-device — D-D4 real SQLCipher open, reopen and migration with zeroed copies — `archive/pca-p8-d/p8/stream-d-report.md`
- P8/E23 — no log exists for wiped_drop ack, "Capture resumed" toast, Ask answer/TTS, nudge success, StrictMode — those checklist steps rely on a human verdict — `archive/pca-p8/p8-e23-report.md`
- P8/E23 — PromptEditorTest versioning test (savingCreatesVersionTwo…) timed out once again (flaky; previously "fixed" in P5/T19) — `archive/pca-p8/p8-e23-report.md`
- P8 final ai M1 — BootReceiver re-arms only future reminders; a reminder due while the phone was off is never shown — `archive/pca-p8/final-review/review-ai.md`
- P8 final ai M2 — CalendarEvent.title not re-checked after decode; schema maxLength (80/40) not re-checked for stored rows — `archive/pca-p8/final-review/review-ai.md`
- P8 final ai M3 — LocationSource caches no negative geocode result; a hung Geocoder ties up one IO thread per call — `archive/pca-p8/final-review/review-ai.md`
- P8 final ai M4 — EndpointRepository reads previous key outside the writes lock (interleaved saves can restore a stale key) — `archive/pca-p8/final-review/review-ai.md`
- P8 final ai M5 — undo allowed 10 s after a confirmed execution (spec says auto only); undoUntil map grows per executed action — `archive/pca-p8/final-review/review-ai.md`
- P8 final ai M6 — fired-reminder notification has no content intent — `archive/pca-p8/final-review/review-ai.md`
- P8 final ai M7 — CalendarExecutor.undo reports success when 0 rows deleted — `archive/pca-p8/final-review/review-ai.md`
- P8 final ai M8 — ModelRouter maskForms comment overstates Basic-auth coverage — `archive/pca-p8/final-review/review-ai.md`
- P8 final ai declined — RoleContracts rejects endAt ≤ startAt while ActionParams repairs it (a model mistake fails the whole answer); calendar source reads all calendars; no timeouts on calendar/contacts queries; multi-process assumption unverified — `archive/pca-p8/final-review/review-ai.md`
- P8 final data M1 — cancelled drain can leave a frame row after Delete all (STORED-after-wipe cleanup not NonCancellable) — `archive/pca-p8/final-review/review-data.md`
- P8 final data M2 — Forget/retention leave an older export JSON holding forgotten text — `archive/pca-p8/final-review/review-data.md`
- P8 final data M3 — periodic critic loads every summary_trace row to find user_review rows — `archive/pca-p8/final-review/review-data.md`
- P8 final data M4 — retention redaction runs LIKE full scans per cited id inside the write transaction (seconds of lock) — `archive/pca-p8/final-review/review-data.md`
- P8 final data M5 — queue-drain frame ingest has no place label or frame list (falls back to recentFrames(10), duplicate facts) — `archive/pca-p8/final-review/review-data.md`
- P8 final data declined — retention ignores "trusted"; uncited retrieved memories not redacted; context.db.migrating/.old not in backup exclusions; failed 👎 review leaves memories in NEEDS_REVIEW for good — `archive/pca-p8/final-review/review-data.md`
- P8 final device M1 — VoiceCapabilityService.publish() micHeld read-then-write race (Phase 6 deferred minor still not done) — `archive/pca-p8/final-review/review-device.md`
- P8 final device M2 — PushToTalk watchdog uses System.nanoTime/Thread.sleep (flaky test still timing-dependent) — `archive/pca-p8/final-review/review-device.md`
- P8 final device M3 — glasses app allowBackup="true" with no exclusion rules while buffering camera JPEGs in filesDir/outbound (also P7×P8 M5) — `archive/pca-p8/final-review/review-device.md`, `archive/pca-p8/p7p8-integration-report.md`
- P8 final device M4 — glasses-minted quiet-hours record never clears the buffer (only phone-pushed ones do) — `archive/pca-p8/final-review/review-device.md`
- P8 final device M5 — Command.Undo is a new sealed subtype (older phone decodes Malformed; undo silently does nothing on mismatched installs) — `archive/pca-p8/final-review/review-device.md`
- P8 final device M6 — ledger trim not rolled back on a failed write — `archive/pca-p8/final-review/review-device.md`
- P8 final device M7 — a refused second startForeground during an in-flight capture can stop the service — `archive/pca-p8/final-review/review-device.md`
- P8 final device declined — provisional phone timed pauses indefinite on glasses until exact push; separate QUIET_HOURS ids per side; CXR send under lock on Main; LONG_PRESS_MS 500 vs real Rokid taps; forgotten answer on HUD up to 30 s — `archive/pca-p8/final-review/review-device.md`
- P8 final phone M1 — turning quiet hours off or changing the window doesn't end tonight's QUIET_HOURS record — `archive/pca-p8/final-review/review-phone.md` — **fixed in code** — `devfix-r2a-report.md` (BUG-R2-6)
- P8 final phone M2 — onboarding coroutines unguarded (a throw crashes every launch); continueAi has no in-flight guard — `archive/pca-p8/final-review/review-phone.md`
- P8 final phone M3 — Shopping list shows "Nothing on your list" while the actions graph isn't ready — `archive/pca-p8/final-review/review-phone.md`
- P8 final phone M4 — "Still paused on glasses — Resume?" shown for any pause within 10 min of a Resume — `archive/pca-p8/final-review/review-phone.md`
- P8 final phone M5 — phone captures ignore EXIF orientation (portrait stored sideways) — `archive/pca-p8/final-review/review-phone.md`
- P8 final phone M6 — ProposalCard builds the actions graph on Main during composition — `archive/pca-p8/final-review/review-phone.md`
- P8 final phone M7 — proposal notification has no content intent — `archive/pca-p8/final-review/review-phone.md`
- P8 final phone M8 — Nudge detail reads cited memories once per nudge emission (not live) — `archive/pca-p8/final-review/review-phone.md`
- P8 final phone M9 — failed/abandoned onboarding Test leaves a saved endpoint and key — `archive/pca-p8/final-review/review-phone.md`
- P8 final phone declined — ContextHubService stopSelf after refused startForeground may still raise "did not call startForeground"; PausePipelineControl.enabled reads true briefly at start — `archive/pca-p8/final-review/review-phone.md`
- P8 final fix-device — a refusal with exactly the same text as a toast already showing at tap time isn't detected (recording continues) — `archive/pca-p8/final-review/fix-device-report.md`
- P8 final fix-actions — DeleteAllFenceTest.queuedJobsAreGoneAndThePipelineResumes failed once in release variant (QueueDrainWorker still RUNNING) — `archive/pca-p8/final-review/fix-actions-report.md`
- P7×P8 M1 — export omits nudges, proposals, shopping items and notes — `archive/pca-p8/p7p8-integration-report.md`
- P7×P8 M2 — Delete all doesn't call ForgetActions.dataRemoved() (glasses today list clears on next heartbeat) — `archive/pca-p8/p7p8-integration-report.md`
- P7×P8 M3 — Delete all doesn't empty cache/capture — `archive/pca-p8/p7p8-integration-report.md`
- P7×P8 M4 — OriginFencedIntake wiring has no source-rule test — `archive/pca-p8/p7p8-integration-report.md`
- P7×P8 M6 — PhoneCapture.enqueueIngest work untagged (pause doesn't cancel; harmless with I3) — `archive/pca-p8/p7p8-integration-report.md`

### Device-run bugs and feature minors
- BUG-A memory-detail photo eats scroll — CLOSED (merged 739d55f; round 2 PASS) — `devfix-batch1-report.md`, `device-run-2-report.md`
- BUG-1 Today rows "No photo" — CLOSED (round 2 PASS) — `devfix-batch1-report.md`, `device-run-2-report.md`
- BUG-2 9 legacy memories store raw JSON — open (one-off cleanup/reprocess suggested) — `device-run-report.md`
- OBS-1 no pc_hello after phone-side app restart until next frame (~1.5 min) — open — `device-run-report.md`
- OBS-2 checklist markers — CLOSED — `devfix-batch1-report.md`
- OBS-3 Home shows proposal cards only for nudges (Ask card only in Ask detail; HUD Confirm 15 s vs proposal ~63 s) — open — `device-run-report.md`
- OBS-4 answers with raw ISO time / "6 AM or PM?" — open (model/prompt) — `device-run-report.md`
- BUG-R2-1 flows graph scrolled right — CLOSED (round 3 PASS) — `devfix-r2b-report.md`, `device-run-3-report.md`
- BUG-R2-2 encryption never turns on — vararg bind bug CLOSED (round 3 shows ATTACH/EXPORT pass); REBUILD_INDEX failure fixed in code on devfix/r3a (two connections), not merged per report, device unverified — `devfix-r2a-report.md`, `device-run-3-report.md`, `devfix-r3a-report.md`
- BUG-R2-3 all-day calendar events — fixed in code (devfix/r2b), not re-checked on device — `devfix-r2b-report.md`
- BUG-R2-4 NOT_AUTHORIZED after cold start, no retry — fixed in code (devfix/r2b), not re-checked on device — `devfix-r2b-report.md`
- BUG-R2-5 undo toast cut short — CLOSED for phone-typed asks (round 3 PASS); glasses-ask path BLOCKED by BUG-R3-3 — `device-run-3-report.md`
- BUG-R2-6 quiet hours off leaves running pause / listed twice — fixed in code (devfix/r2a); closes P8 final phone M1; offline glasses keep their own copy until sync — `devfix-r2a-report.md`
- SEC-1 Rokid CXR SDK logs the auth token in plaintext on every connect — open (vendor; nothing in SDK can disable it; R8 strip not done) — `device-run-2-report.md`, `devfix-r2b-report.md`
- BUG-R3-1 reinstall skips onboarding (Auto Backup restores prefs) — fixed in code (devfix/r3a), not merged per report, device unverified — `devfix-r3a-report.md`
- BUG-R3-2 wizard can't add a key to the build seed — fixed in code (devfix/r3a), device unverified — `devfix-r3a-report.md`
- BUG-R3-3 glasses voice asks mostly unanswered, no outcome logged — fixed in code (devfix/r3b adaptive VAD + outcome logs), not merged per report, device unverified — `devfix-r3b-report.md`
- OBS-R3-1 ~1/3 answers INVALID_OUTPUT — fixed in code (devfix/r3a truncation retry), device unverified — `devfix-r3a-report.md`
- Round 2 obs — Embeddings role has no endpoint (no semantic half of hybrid search) — open — `device-run-2-report.md`
- Round 2 obs — calendar answer says "Proposed calendar event attached" while the tool is disabled — open — `device-run-2-report.md`
- devfix/r2a — glasses offline when quiet hours turned off keep their quiet record until next sync/expiry — open — `devfix-r2a-report.md`
- devfix/r3a — glasses APK prefs (hud, capture_guard, capture_scheduler) backup out of scope — open — `devfix-r3a-report.md`
- devfix/batch1 — Today membership still by extraction time (yesterday's photo can show under Today as "Earlier days") — accepted cost — `devfix-batch1-report.md`
- Phone backups — round-1/2/3 tars can't be restored onto the current install (new Keystore) — `device-run-3-report.md`
- AF minors — M1 fixed card height clips facet lines at large font; M2 combine runs on Main; M3 LINK-added flow lost on process death; M4 ListScreen no error handling; M5 list selection not session-scoped; M6 save_review→embed edge missing; M7 RetrievalViewModel keeps polling stats — open — `agent-flows/final-review.md`, `review-fixes-report.md`
- AF ledger — ListScreen skips first ON_RESUME reload; edge label can sit over a node (addressed by I1 fix); facet-line tag collides with two Custom facets; view placeholder had no header (replaced) — `agent-flows/stream-b-report.md`, `stream-d-report.md`
- AF — agent-flows branch "Not merged into feat" at review-fixes time — `agent-flows/review-fixes-report.md`
- GM minors — "Confirm on your phone" pop-up not built; onStart→onNewIntent order assumed; spec §5 (notifications/calls over full HUD) not investigated; icon removed only on finish — `glasses-minimise-report.md`
- GM review I2 — "Open on glasses" while minimised probably still pulls the HUD over Maps (order-dependent heuristic) — superseded on the phone side by minimised-link I2 (no appStart while MINIMISED); glasses heuristic not re-reported — `glasses-minimise/final-review.md`, `minimised-link-report.md`
- GM review M1 tickGate checks raw frame overlay; M2 undo toast tells wearer to double-tap while minimised; M3 blocked background start silent on Android 12; M4 gate unreachable at Silent; M5 icon doesn't return after keep-alive restart; M6 no feedback without keep-alive; M7 overlay WindowManager from Application context; M8 unknown IconCorner drops pc_settings; M9 late double-tap re-arms gate; M10 non-ASCII in comments — open — `glasses-minimise/final-review.md`
- GM declined — auto-open Confirm while glasses display asleep (no turnScreenOn); one toast per minimise without permission — `glasses-minimise/final-review.md`
- ML ruling 6 — after a phone process death, sticky restart's connectOnce from Idle can't know the glasses are minimised and would appStart (needs persisted state) — open — `minimised-link-report.md`
- ML risks — Hi Rokid update may change undocumented forwarding; throughput/stalls after BT drop while minimised untested; battery over hours bound — open — `minimised-link-investigation.md`
- ML follow-up — move HudController ownership from MainActivity to GlassesRuntime/CaptureService so an OS-destroyed background activity doesn't detach the uplink — open — `minimised-link-investigation.md`
- ODE/D — free-space check ignores bytes already in the .part (also final review M2) — open — `ode/stream-d-report.md`, `ode/final-review.md`
- ODE/D — delete() vs worker rename window (also final review M4) — open — `ode/stream-d-report.md`, `ode/final-review.md`
- ODE/D — no logcat test for the "id + reason code only" rule; HttpClientRulesTest never seen RED in-stream (later RED shown in integration) — `ode/stream-d-report.md`, `ode/integration-report.md`
- ODE/N — APK .so unstripped because :phone/:glasses had no ndkVersion — CLOSED (final review: ndkVersion pinned in :services:ai, :phone, :glasses; stripped .so 9.8 MB, later 4.5 MB at -O3) — `ode/stream-n-report.md`, `ode/final-review.md`, `ode/review-fixes-report.md`
- ODE/N — a GGML_ASSERT/abort (e.g. true OOM in ggml) still kills the process; only a device check can probe it — open — `ode/stream-n-report.md`, `ode/integration-report.md`
- ODE/N — first native configure adds ~1–2 min to a clean gate — `ode/stream-n-report.md`
- ODE/U — overlay says "Keep the app open" although the download continues in the background (also final review M11) — open — `ode/stream-u-report.md`, `ode/final-review.md`
- ODE/U — metered network with the switch off: overlay waits indeterminate until Wi-Fi or Cancel — open — `ode/stream-u-report.md`
- ODE/integration — deleting a cached model keeps its mmap up to 60 s (also final review M5) — open — `ode/integration-report.md`, `ode/final-review.md`
- ODE final M1 — JNI reads n_embd floats where llama.cpp sizes outputs by n_embd_out (future models could read out of bounds) — open — `ode/final-review.md`
- ODE final M3 — clean short body or 416 treated as corruption (whole .part thrown away); Content-Range total not compared — open — `ode/final-review.md`
- ODE final M6 — a query embed waits behind a running reindex batch — partly addressed by I1 (8-text native batches) — `ode/final-review.md`, `ode/review-fixes-report.md`
- ODE final M7 — page decides ABI support from Build.SUPPORTED_ABIS instead of isNativeAvailable() — open — `ode/final-review.md`
- ODE final M8 — endpoint Test shows "Address or key missing" for an on-device load failure — open — `ode/final-review.md`
- ODE final M9 — worker tests miss Range edge cases (200 to Range, Content-Range mismatch, 416, https→http refusal, oversize .part) — open — `ode/final-review.md`
- ODE final M10 — clone without the llama.cpp submodule fails with a cryptic CMake error — open — `ode/final-review.md`
- ODE declined — runAttemptCount across constraint stops; stale FAILED WorkInfo first emission; RUNNING EmbeddingWorker (KEEP) may miss a binding change; release .so size vs 3–5 MB estimate unmeasured; no bge query prefix — `ode/final-review.md`
- ODE — on-device-embeddings branch "Not merged into feat" at review-fixes time; device check pending — `ode/review-fixes-report.md`
- GM review I1 fast 4-tap pauses instead of minimising — open per reports (no fix report on disk; merge commit db8dfea's message mentions a fix) — `glasses-minimise/final-review.md`

## 5. Needs-user steps still not done

Exact instructions as written in the device and devfix reports. Items a later round passed are tagged in brackets.

### From round 1 (`device-run-report.md`) — status per later rounds noted in brackets
- S-ASK TTS — "pair the glasses as a Bluetooth audio (A2DP) output of the phone, then ask from the glasses; the answer should be spoken."
- S-OFFLINE-PAUSE — "turn phone Bluetooth off until Home says 'Glasses offline'. Double-tap the temple, turn Bluetooth on, then check the HUD stays PAUSED and the glasses log has `capture skipped: paused`. Double-tap to resume." [PASS in round 2]
- S-FLUSH / D-A5 — "Bluetooth off for 2–3 intervals (HUD Status Queue > 0), then on, and watch Activity fill. Repeat, switching Bluetooth off again mid-flush. Check there's no duplicate and the Queue count is exact." [PASS in round 2, frame boundary only]
- D-A1 — "link down with frames buffered, set the glasses clock +2 days, reconnect, and check capture times. Then restore automatic time."
- D-A2 — "fill glasses storage to < 1 MB free, capture, and check nothing crashes. Remove the filler afterwards."
- D-A4 — "link down, buffer 5 frames, then Settings > Memory > Delete all (wipes ALL memories), and reconnect. Expect `dropped: captured before Delete all`."
- D-C1 / D-C2 — "revoke Camera for the glasses app in glasses Settings > Apps mid-capture (D-C2: with the HUD in the background). Then re-grant it." [D-C1 PASS in round 2]
- D-C3 remainder — "close the app the Rokid way (AssistServer), long-press Hi Rokid, and check whether a backgrounded activity keeps the session."
- D-C4 — "uninstall and reinstall the glasses app 10×, comparing `dumpsys activity services | grep -c cxr` before and after."
- D-D4 — "Settings > Memory > encryption on, restart, check Activity, encryption off, restart. Data should be intact both ways." [FAIL in round 2: BUG-R2-2]
- P7-2 — "Bluetooth off, glasses double-tap pause, phone Resume, Bluetooth on. Expect 'Still paused on glasses - Resume?'." [PASS in round 2]
- P7-3 — "Bluetooth off, phone Pause 'For 30 minutes', Bluetooth on. HUD paused; both sides resume at +30 min." [paused half PASS in round 2]
- P7-4 — "quiet hours starting in 2 min with Bluetooth off. Glasses self-pause; after reconnect Home shows 'Quiet hours'; the next night gets a new pause id." [first part PASS in round 2]
- P7-6 reboot — "reboot the phone before 18:00 and check the 'Call Mom' reminder still fires."
- P7-8 — "revoke the phone Calendar permission, enable Calendar events, then ask for a calendar add. Expect 'Calendar permission needed'." [PASS in round 2]
- P7-9 — "enable Calendar + Location in Data sources and ask 'what is my next meeting'." [PASS with BUG-R2-3 in round 2]
- P7-12 Delete all — "run it only if wiping the real memory is acceptable."
- P7-13 / onboarding screenshot — "fresh install, run the wizard, and capture `onboarding-first-step.png`."
- D-E2 — "run `tools/gate.sh` 3× on the merged tip." (no device needed)

### From round 2 (`device-run-2-report.md`)
- P7-6 reboot (17:45 instructions) — "1. Reboot the phone. 2. After boot, unlock it and check that `adb -s <phone> shell dumpsys alarm | grep ReminderReceiver` still shows 18:00. At 18:00, check that the 'Call Mom' reminder fires (P7-6 reboot). 3. Replug the glasses USB if you need them on adb."
- D-A4 / P7-12 — "Delete all: run it after 18:00, because it cancels armed reminders."
- D-D4 — "re-test once BUG-R2-2 is fixed."
- P7-3 — "check the auto-resume at +30 min."
- P7-7 — "shopping undo double-tap, after BUG-R2-5."
- D-C2 — "(camera failure in the background)."
- D-A1 — "the glasses clock is manual today, so decide what to restore it to."
- D-A2 and D-C4 — still to run.
- P7-13 — "fresh install / onboarding."
- D-C3 — "Hi Rokid long-press."
- S-ASK TTS — "via A2DP."
- P7-4 — "check for a new pause id the next night."
- Glasses adb — "the glasses are not listed by `adb devices` until you replug the USB cable."

### From devfix reports (device rechecks requested)
- D-D4 (`devfix-r2a-report.md`) — "1. Settings → Memory → Encrypt memory ON, then force-stop and launch. The switch should stay ON and memory should still be readable. 2. Turn it OFF, then force-stop and launch again. Memory should still be readable." [round 3 still FAIL at REBUILD_INDEX → see r3a]
- D-D4 (`devfix-r3a-report.md`) — "Turn encryption ON with data present. Expect no `encryption change failed`, the switch stays ON, and data plus search survive 2 restarts. Turn it OFF again: same checks. Also run `SqlCipherMigrationDeviceTest` (androidTest, `:services:memory`)."
- BUG-R3-1 reinstall (`devfix-r3a-report.md`) — "Run `adb uninstall` + `adb install` with Auto Backup data present for the app (or use `bmgr restore`). Expect: the first launch shows Welcome … For the defence on its own: plant `onboarding.xml` (v1_complete=true) on a fresh install with no key, then launch. Expect Welcome after a brief Home … An existing set-up user (key + data) must still land on Home."
- BUG-R3-2 (`devfix-r3a-report.md`) — "On a fresh install, the AI step 'Use Azure OpenAI' shows a masked API key field, and Test is disabled until you type. Enter the key, Test: expect 'Works · N ms' and the field gone … The manual 'Azure OpenAI' preset shows the build address, `2024-10-21` and the deployment. Never screenshot the key field while it has text."
- OBS-R3-1 (`devfix-r3a-report.md`) — "Make about 10 typed and glasses asks. Count `AnswerService: answer failed: kind=INVALID_OUTPUT`, and for each `RoleRunner ... reply rejected (attempt N, stage=..., cut=...)` line, record the stage and cut flag."
- BUG-R3-3 (`devfix-r3b-report.md`) — "1. Glasses ask spoken softly at a normal distance, and one with a ~2 s pause mid-question (expect `ptt: auto-end END_OF_SPEECH` ~2.2 s after the last word, then `PcVoice: outcome=ANSWERED source=GLASSES`). 2. Slow speech (`say -r 110`) followed by an end-tap (expect `ptt: tap after auto-end taken as send`). 3. Tap and say nothing (ends ~5 s; `listen end GLASSES` then `outcome=NO_SPEECH` or `NO_FINAL`; HUD 'Couldn't hear that'). 4. A noisy room: noise alone must not keep the listen open; speech still ends ~2.2 s after it stops."
- P7-7 typed-ask undo (`devfix-r2b-report.md`) — "This needs a device recheck (P7-7)." [round 3 PASS for phone-typed]
- Phone-ask cancel (`archive/pca-p6/2026-10-12-v1-phase6-voice-answers/devfix-cancel-report.md`) — "Repeat the repro and check that the log shows `listening PHONE` followed by a phone cancel, with no `answered PHONE`." [round 1 PASS]


## Wave 1 — motion-aware keyframe capture (2026-10-15): rulings and device facts
Spec `2026-10-15-keyframe-capture-image-memory-design.md` (rev 8 + later edits), plan `plans/2026-10-15-keyframe-capture-wave1.md`, device checklist `2026-10-15-wave1-device-checklist.md`, status/backlog §"Wave 1". Built on branch `wave1/integration`, merged into feat as `dc0bfce` (tag `v1.0.0-rc1`; `v1-beta` = the build before).
**Rulings made while executing (controller, with the cost if wrong):**
- Parallel implementer worktrees despite the skill default (user instruction); Gradle throttled to 2 concurrent runs (8 GB RAM) — slower feedback.
- One Opus review per agent chunk + a final whole-branch review; fix passes by ONE fixer per area; T0 added `KeyframeOut`; G3/P6 split into sub-tasks.
- Subagents commit with the Sonnet 5.5 trailer. Nomic is catalogued but never auto-bound (the active-embedder switch waits for the on-device reference-vector check). Edited VISION_EXTRACT prompts degrade to single-photo calls (Diagnostics warns); extraction captions are saved long-term.
- Keyframe size: long side ≤ 640 px, never upscaled (camera gives 480×640). Claim abandonment 15 min (spec text updated) + all EXTRACTING claims released at process start. Wear check uses the phone SDK `wearingStatus` forwarded as `pc_wear`, never the Android proximity sensor (stuck at 5.0 far on these glasses). Non-dwell keyframes capped (≥ 3 s apart, ≥ 6 s while moving); backlog > 40 PENDING sheds the oldest non-dwell frames to NEAR_DUP/'shed_backlog'.
**Device facts (2026-10-15):** Rokid AssistServer treats our app as the `third_app` scene and force-stops it on other scenes (phone_call, message, mobile_music, glasses removal); a real incoming call did NOT kill the minimised app (retest 1, ~60 s capture pause, auto-resumed); camera start-up p50 ≈ 390 ms, p95 ≈ 414 ms; heat +0.5 °C / 30 min; Doze 20 min OK (p50 12 s, 0 stuck); glasses logcat is noisy and also holds secrets — read only our tags via an extended filter (docs/rokid-sdk-token-logging.md) or the app's `files/capture_diag.log`.
**Incident:** one diagnostic grep printed a Rokid device key (full) and the start of the device secret into the chat transcript; scrubbed locally, filter list extended, memory note added. Rokid pairing credentials may need re-pairing to rotate.
**Open / deferred:** wave 2–4 (image index, storage lifecycle, image recall, motion hub), 1 s-glance promise unverified on a real device beyond retest 1 (15 dwell keyframes captured), battery drain per hour unmeasured, LED visual check, Nomic reference vectors on device + photo 256-vs-768 test, WorkRest not persisted across process death, 'Open on glass' notification for full-screen closes reused existing button, skipped-photo 1-day retention (P6) not built.
