# Proactive Context: requirement traceability (as built, 2026-10-14)

Branch `feat/proactive-mvp-implementation` at `c5060c6`. Compiled read-only from the specs, the plans, the decision log, the session log, the status/backlog doc, `git log` and greps of `android-companion/**/src/main`. No secret values appear here; config keys are named only.

**Sources and shorthand**
- **DL** = `2026-10-14-decision-log.md`. Its top "Controller corrections" note overrides older lines. `DL §1 P3/T11` points to a ruling line, `DL §2` to probes, `DL §3 R1/R2/R3` to device rounds 1–3, and `DL §4` to open minors.
- **SL** = `2026-10-14-session-log.md`. **SB** = `2026-10-14-status-and-backlog.md`, where `B12` etc. are its open bugs.
- **P8T** = Phase 8 plan, section "Carry triage".
- Paths: `PH` = `android-companion/phone/src/main/java/com/rokid/context`, `GL` = `android-companion/glasses/src/main/java/com/rokid/context/glasses`, `CM`/`CH`/`CU` = `android-companion/core/{model,hud,ui}/src/main/kotlin/com/rokid/context/core/…`, `AI`/`MEM`/`ORC` = `android-companion/services/{ai,memory,orchestration}/src/main/java/com/rokid/context`, `LNK`/`VOI`/`CON` = `android-companion/services/{glasses-link,voice,connectors}/src/main/kotlin/com/rokid/context`.

**Status legend**
- **DONE**: built as specified.
- **DONE-CHANGED**: built, with a recorded ruling that changed it.
- **PARTIAL**: some of it is built. The missing part is either recorded somewhere or listed under Gaps.
- **DEFERRED**: explicitly postponed, with a pointer.
- **WONT-FIX**: explicitly declined.
- **SUPERSEDED**: replaced by a later spec.
- **GAP**: not built, not deferred, and no ruling. This label was added for this audit because none of the six statuses fits; every GAP row is also listed under Gaps.

**Row counts (168 rows):** DONE 68 · DONE-CHANGED 58 · PARTIAL 14 · SUPERSEDED 15 · DEFERRED 5 · GAP 8 · WONT-FIX 0. Requirements declined outright are in §6.3, the Phase 8 Won't-fix list.

| Section | Rows | DONE | DONE-CHANGED | PARTIAL | SUPERSEDED | DEFERRED | GAP |
|---|---|---|---|---|---|---|---|
| §1 v1 R-IDs | 55 | 29 | 24 | 2 | 0 | 0 | 0 |
| §2 MVP | 60 | 11 | 15 | 8 | 15 | 3 | 8 |
| §3 Agent flows | 17 | 12 | 5 | 0 | 0 | 0 | 0 |
| §4 On-device embeddings | 22 | 11 | 9 | 2 | 0 | 0 | 0 |
| §5 Glasses minimise | 14 | 5 | 5 | 2 | 0 | 2 | 0 |

The **Gaps** list in §7 has 19 entries: the 8 GAP rows, plus requirements inside PARTIAL rows whose missing part has no deferral and no ruling.

**Device-verified** gives the round: P0–P6 = the per-phase device checks in DL §3; R1 = 2026-10-14 01:15, R2 = 15:50, R3 = 18:11; ML = the minimised-link probe at 22:42–22:46; Drive = the user's drive builds (SL §11–13).

---

## 1. v1 redesign spec (`2026-10-12-v1-redesign-design.md`, §8 R-IDs)

| ID | Requirement (short) | Status | Where (phase/commit · main file) | Deviation / ruling | Device-verified |
|---|---|---|---|---|---|
| R-01 | Modules per §1.1, dependency rules, `:phone`/`:glasses` replace `:app`/`:glasses-app` | DONE | P1 `f67fc91`, `1428f17`…`8624386`; P3 `a874730` (glasses-link); P6 `ad6f698` (voice); P7 `c60466e` (connectors) · `android-companion/settings.gradle.kts` | — | YES (P1/T8 smoke) |
| R-02 | Live Fit pieces copied and adapted, with their tests | DONE | P1 `8fc4799`, `0f10aae`; P3 `a874730`, `308049d`, `be8c66c`; P6 `084f595`, `8b0283e`; P7 `4d90d4f`; AF `d99bb7c` (ListScreen) · `CU/components/*`, `CH/*`, `LNK/*` | Phone keeps Material Rounded icons (P8T Won't fix #6, #38) | YES (R1) |
| R-03 | Backend logic ported per §1.3; existing tests pass | DONE | P1 `764411a`, `ed485a4`, `8624386` · `ORC/work/*`, `ORC/domain/*` | Ported but **never wired** in v1: `ProviderBlockState`/`OrchestratorService`, `PolicyEngine`, `LanguageRouter`/`RetrievalPlanner`, `LocalProviderSelector`, `security/*`, `TelemetryReporter`, `ModelSlotManager`, `ModelDownloadManager` (each is referenced only by itself and its tests). See the MVP rows and Gaps | YES (P1/T8) |
| R-04 | RFCOMM/SPP removed | DONE | P3/T12 `3ba09c8` | Gated on T11 device steps 9–11 (DL §1 P3/T12) | YES (P3/T12) |
| R-05 | CXR channels incl. `pc_frame_ack`, `pc_hello` clock + pauses; version + mismatch UX | DONE-CHANGED | P1 `1428f17`; P3 `6c4cfbb`, `f1f6b82`; ML `043eca5` · `CM/Protocol.kt`, `LNK/CxrGlassesLink.kt`, `LNK/MinimisedLink.kt` | Additive `pc_hello_request` (DL §1 P3/T6); `Hello.resumedAtMs` (P3/final); `Command.Undo` (DL §4 P8 device M5 open); data also flows on the session-less CXRLink while minimised (ML rulings 1–3) | PARTIAL (R1 S-LINK; mismatch UX not run) |
| R-06 | `StateFrame` full frame, ≤ 4 Hz | DONE | P3 `d0bddc1` · `PH/phone/hub/StateBroadcaster.kt`, `StateFrameBuilder.kt` | HUD times converted to the glasses clock (DL §1 P3/T5) | YES (R1) |
| R-07 | `ContextHubService` owns link/voice/broadcast; no polling; glasses capture timing + offline buffer; pause authority exception | DONE-CHANGED | P3 `f1f6b82`, `e9ce225`; P4 `40f138b`; bg-capture `1f09708`/`a75ae2d`; ML `9e02467`/`f82b82e` · `PH/phone/hub/ContextHubService.kt`, `GL/capture/OutboundBuffer.kt` | R-07's text says a "20-frame" buffer; §1.7 was amended to 200 (`ef8fadd`); 8 MiB byte cap (P8 A1); hub = "Connected to glasses" FGS (ML); see Amendments | YES (P3/T11, R2 S-FLUSH, ML) |
| R-08 | Frame transfer: chunks, CRC, stop-and-wait, delete-after-ack, restart from chunk 0, dedupe, PTT priority | DONE | P0 spikes; P3 `6c4cfbb`, `e9ce225`; P8 A2/A3 `9f82577`, `2ef05f4`, `71459a6` · `LNK/FrameReassembler.kt`, `GL/link/FrameUplink.kt` | `SAFE_CHUNK_CHARS` 32 768, frame-level acks only (DL §2 P0); the 2 s retry backoff isn't in §1.8 (P8T Won't fix #2) | PARTIAL (P0 all; R2 D-A5 at a frame boundary only, mid-frame cut not achieved) |
| R-10 | NavHost + 3-tab PillNav + routes; onboarding gate | DONE | P4 `40f138b`, `52b0543` · `PH/phone/ui/shell/Routes.kt`, `AppShell.kt` | Bottom bar height measured, not 84 dp (DL §1 P4/T10); every route smoke-tested (P8 E1 `157f0c3`) | YES (R1–R3) |
| R-11 | Banners per §2.2, severity-ordered | DONE | P4 `756da11`; P7 A3 `a0e7086`; P8 C3 `3e394fb` · `PH/phone/ui/banner/Banners.kt` | Adds "Still paused on glasses — Resume?" (§5.5) and "Closed on glasses · capture stopped" (P8 C3); misleading within 10 min of a Resume (DL §4 P8 phone M4) | PARTIAL (R2 P7-2, P7-4; the 7 kinds not each shown) |
| R-12 | `:core:ui` tokens, type, shapes, components; app icon | DONE | P1 `8fc4799`; P4 `b4eb50b`; P5 `a542f15` · `CU/theme/PcTheme.kt`, `CU/components/*` | — | YES (R1 screenshots) |
| R-13 | Home (Ask-first): hero mic, suggestions, For you feed | DONE | P4 `592b226`; P6 `d1e0f5f`; `d53e76e` (photos-today count) · `PH/phone/ui/home/HomeScreen.kt` | Proposal cards show for nudges only, not asks (DL §4 OBS-3 open) | YES (R1) |
| R-14 | Ask detail: sources, rating, action state | DONE | P4 `aa217b7`; P7 B4 `e18a288` · `PH/phone/ui/ask/AskDetailScreen.kt` | 👎 enqueues an id-only critic review (DL §1 P7/F2) | YES (R1) |
| R-15 | Activity: status strip, Today/Week/Month, chart, tiles, day drill-down, timeline | DONE-CHANGED | P4 `45ffd70`; devfix `6433f7a`; `5db3a64` · `PH/phone/ui/activity/*` | Rows show photo capture time, with an "Earlier days" section (DL §1 devfix/batch1 BUG-1); sort menu, Latest first by default (SB §2) | YES (R2 BUG-1 PASS; sort not yet installed) |
| R-16 | Memory detail (Trust/Edit/Forget fact/moment) + Frame viewer | DONE | P4 `9b6e468`; P7 F3 `a0f4ab7`; devfix `7a08a5f` · `PH/phone/ui/memory/*`, `PH/phone/ui/frames/*` | Hidden memories read-only; pan only when zoomed (DL §1 BUG-A) | YES (R1 S-FORGET, R2 BUG-A) |
| R-17 | Settings tree: Devices, Capture & privacy, AI, Connectors, Memory & system, Diagnostics | DONE-CHANGED | P5 `43b09af`, `0ea9804`; P7 `7a0a0c8`, `517f453`, `e18a288`; AF `2197701`; ODE `cad5287` · `PH/phone/ui/settings/SettingsRoot.kt` | AI adds Agent flows and On-device models rows. Memory settings still show "Automatic clean-up arrives together with Forget." (`MemorySettings.kt:173`), although retention has been enforced since P7 F3 (stale copy) | YES (R1–R3) |
| R-18 | Glasses settings: verbosity + HudPreview, scale/position, gestures, Re-authorize | DONE-CHANGED | P5 `43b09af`, `b86129c`; GM `84c692b` · `PH/phone/ui/settings/glasses/GlassesSettings.kt` | Adds icon corner, pop-up time and overlay hint (GM spec §6). Live Fit's HUD Display screen not ported (SB §5 item 7, SL §16 Develop 6) | PARTIAL (R1 P7-5 at Max; scale/position/Re-authorize pending, DL §4 P5 pending-device T16) |
| R-19 | Onboarding wizard steps | DONE-CHANGED | P7 E `4d90d4f`, `276e53c`, `0163d16`; P8 fix `80105a1`; r3a `cd1324d`, `909ccf8` · `PH/phone/ui/onboarding/*` | AI step adds model/deployment; tested preset rebinds untouched bindings; `OnboardingGuard` (DL §1 P7/E, P8 final fix-phone I1, r3a BUG-R3-1/2) | PARTIAL (R3 P7-13 PASS with bugs; r3a fixes not re-run) |
| R-20 | One ViewModel per screen; stateless screen composables | DONE | P4/P5 throughout · `PH/phone/ui/**` | — | NO (structural) |
| R-30 | Glasses units §3.1; single capture path in `CaptureService`; guardrails | DONE-CHANGED | P3 `b61f9b2`, `49244eb`, `313c4c7`; bg-capture `1f09708` · `GL/capture/CaptureService.kt`, `CaptureAlarmReceiver.kt`, `FrameGuard.kt` | Keep-alive FGS + in-process alarm receiver (SL §11); cadence clamped 30 s–1 h (DL §1 P3/T11 cadence) | YES (R1 S-CAPTURE; Drive; ML) |
| R-31 | Pages Glance/Today/Status + overlays | DONE-CHANGED | P3 `6a821e6`; P5 `a542f15`; GM `a9c2480`, `5f9c720`; `d53e76e` · `CH/render/Pages.kt`, `Overlays.kt`, `GL/overlay/*` | Adds the exit-gate countdown, minimised icon, pop-ups and photo-sent cue (GM spec) | YES (R1 D-E1H 7 HUD refs); GM overlays PARTIAL (Drive) |
| R-32 | Rendering rules via `:core:hud` | DONE | P1 `0f10aae`; P5 `a542f15` · `CH/Hud.kt` | Filled REC dot is plan-mandated (P8T Won't fix #24) | YES (R1) |
| R-33 | Verbosity §3.4 | DONE-CHANGED | P3 `be8c66c`; P7 C4 `b4eb50b`; GM `5f9c720` · `CH/render/HudNav.kt` | While minimised, a nudge pops up unless Silent (DL §1 GM) | PARTIAL (R1 P7-5 at Max; Silent not run) |
| R-34 | Gestures + remapping §3.5 | DONE-CHANGED | P3 `be8c66c`, `aaf1ba4`; GM `0235b1d`, `d74e54b` · `GL/hud/InputRouter.kt`, `CH/DoubleTapExit.kt` | Double-tap is an exit gate with a delayed pause; an unmapped gesture uses its default (DL §1 P3/T9) | PARTIAL (P6 double-tap cancel; R1 pause; remap and 4-tap not formally run) |
| R-35 | TTS over BT; HUD-only without A2DP; offline behaviour; no raw errors | DONE-CHANGED | P6 `2dc524d`, `3259382`, `d54e93d` · `VOI/android/AndroidSpeaker.kt`, `AudioRoutes.kt` | Offline voices only; glasses answers spoken only with an A2DP output (DL §1 P6/T7) | PARTIAL (P6 spoken; R1 phone asks spoken; glasses A2DP pending) |
| R-40 | Endpoint registry: kinds, capabilities, encrypted keys, Test, `.env` seed | DONE-CHANGED | P2 `bf21e72`, `4220c47`; P3 `8ad064a`; P5 `001ea95`, `5765aa9`; r3a `cd1324d` · `AI/ai/EndpointRepository.kt`, `KeyVault.kt`, `EndpointSeeder.kt` | No key compiled into the APK; seed carries address/deployment only (DL §1 P3/T2b); Test bounded 20/60 s (P5/T17) | YES (R3 Test OK) |
| R-41 | Embeddings on Azure/OpenAI-compat/Ollama/custom/llama.cpp | DONE-CHANGED | P5 `dea99c1`, `06a1bed`; ODE `818c196`, `c3d6966` · `AI/ai/embed/EmbeddingClient.kt`, `AI/model/provider/LlamaCppNativeProvider.kt` | 64 inputs per request (P5/T9); on-device 8 per native call (ODE review I1) | NO (no embeddings endpoint on device, R2 obs; ODE device check pending) |
| R-42 | Role bindings, capability-filtered picker | DONE | P2 `4220c47`; P5 `001ea95` · `AI/ai/RoleBindingRepository.kt`, `PH/phone/ui/settings/ai/Roles.kt` | Bound-endpoint delete blocked (P5/T17) | YES (P2 smoke: 6 bindings) |
| R-43 | Prompt store, seeds, variables, one-active invariant | DONE | P5 `9160399`, `d229320` · `AI/prompt/PromptRepository.kt` | — | PARTIAL (R2 via Agent flows) |
| R-44 | Versioning toggle (default on), history/diff/restore, off = overwrite | DONE | P5 `2731c1c` · `PH/phone/ui/settings/prompts/PromptHistory.kt` | — | NO |
| R-45 | Output schema per version, defaults incl. `ProposedAction`, Reset | DONE | P5 `aef8389` · `AI/ai/contract/DefaultSchemas.kt` | — | NO |
| R-46 | `StructuredOutput` adapters, `WireSchemaCompiler`, null→absent, local validation, 1 retry + queue | DONE-CHANGED | P5 `641d4a8`, `49538ce`, `aca6a86`; r3a `5120a2d` · `AI/ai/schema/*`, `AI/ai/run/RoleRunner.kt` | Answer cap 4096; a cut reply is retried at 2× the cap, up to 8192 (DL §1 r3a OBS-R3-1) | PARTIAL (R3: INVALID_OUTPUT on ~1/3 of answers before r3a; not re-run) |
| R-47 | Test bed: inputs, model selector, metrics, 5-gate save, required variables | DONE | P5 `2731c1c`, `ea6562a` · `PH/phone/ui/settings/prompts/PromptEditor.kt`, `AI/ai/run/SaveGates.kt` | The SYSTEM prompt has only the Variables gate (AF spec §4) | PARTIAL (R2 Agent flows editor) |
| R-48 | Lexical FTS4+BM25, Semantic cosine/`VectorIndex`, Hybrid | DONE-CHANGED | P2 `36b6e34`; P5 `5792b9e`, `55c7652`; P8 B2/B3 `1dd8caf`, `4710c15` · `MEM/memory/retrieval/*` | BM25 over all matches in one cursor (DL §1 P5/T10, P8/B2) | PARTIAL (lexical answers on device; semantic never ran) |
| R-49 | Fusion WeightedScore/RRF/CombMNZ with per-strategy config | DONE | P5 `5792b9e`, `d870e6d` · `MEM/memory/retrieval/FusionStrategy.kt` | — | NO |
| R-50 | Tuning, Try it, index stats/Rebuild | DONE | P5 `d870e6d`, `4105d61` · `PH/phone/ui/settings/retrieval/RetrievalSettings.kt` | Rebuild while paused persists `rebuildPending` (P5/T11) | NO (P5 pending-device T19) |
| R-51 | Embedding space identity, canary, generations, atomic swap, fallback | DONE-CHANGED | P5 `a29ae0d`, `2fb3157`, `ff9e004`, `3bcc404` · `MEM/memory/EmbeddingSpaces.kt`, `ORC/orchestration/IndexMaintenance.kt` | Canary drift policy: 3 in a row → stop + "index unstable" (P5/T11); a moved endpoint serves no old space (P5/final I1) | NO |
| R-52 | Answer path §4.7 | DONE-CHANGED | P5 `b49249a`, `00fa0e1`; P4 `92638ef` · `ORC/orchestration/AnswerService.kt` | 500/5 000-char context caps (DL §1 P4/T5); Thinking bounded at 60 s (P6/T8) | YES (R1 S-ASK) |
| R-53 | Role contracts + semantic checks; `SchemaCompatibilityChecker` | DONE | P5 `aef8389`, `441c689`, `6aaae45` · `AI/ai/contract/*` | `endAt ≤ startAt` rejected while ActionParams repairs it (DL §4 P8 final ai declined) | PARTIAL (production decode on device) |
| R-60 | Push-to-talk ask from glasses and phone | DONE-CHANGED | P6 `8b0283e`, `aebe8f3`, `d1e0f5f`; r3b `754d201`, `93fffcf` · `GL/voice/PushToTalk.kt`, `CM/audio/EnergyVad.kt` | Adaptive VAD, 2.2 s trailing silence, 1.5 s late-tap grace (DL §1 r3b; spec §5.1 already edited) | PARTIAL (P6 PASS; R3 mostly failed → r3b, not re-run) |
| R-67 | STT capability gate, byte-count probe + guard, unavailable UX, typed fallback, on-device only | DONE-CHANGED | P6 `ad6f698`…`ef66220`, `0517c99`, `6dc417b`; `64de2b0` · `VOI/voice/ExternalAudioProbe.kt`, `VoiceGate.kt` | Pipe capacity measured; NOT_VERIFIED verdict (DL §1 P6/T5–T6); LJSpeech probe clip (SL §2) | PARTIAL (P6 probe SUPPORTED, 51 200 B pipe; R3 onboarding "Supported"; guard not provoked) |
| R-61 | NudgeEngine with gates §5.2 | DONE | P7 C3 `4e7b636`, C4 `b4eb50b` · `ORC/orchestration/nudge/*` | Text-independent gates run before the model call; caps 0/3/6/10 (DL §1 P7/C) | YES (R1 S-NUDGE) |
| R-62 | Actions per §5.3 matrix, expiry, CAS, idempotent, Undo, action_log | DONE-CHANGED | P7 `c60466e`, `28d509d`, `f2e95d5`, `83866e1`; P8 fix `1d90b8e`, `0a15f7e`; r2b `7579bd7` · `CON/connectors/actions/ActionEngine.kt` | Undo carried on the Answer; notifications-off keeps the reminder with a warning; inexact window alarm up to 10 min late (DL §1 P7/B, P8 final fix-actions I2, r2b; SB B2) | PARTIAL (R1 S-ACTION/P7-6; R2 P7-8; R3 P7-7 phone-typed; glasses undo blocked; SB B14) |
| R-63 | `ToolConnector` + `McpConnector` stub + "Coming in v2" row | DONE | P7 B4 `e18a288` · `CON/connectors/tools/*`, `ActionsSettings.kt:99` | — | NO |
| R-64 | Data sources with on-demand permissions, context only | PARTIAL | P7 C1/C2 `84db78e`, `517f453` · `CON/connectors/sources/*` | All off by default; contacts/location bounds (DL §1 P7/C1). **User notes can't be created** (see Gaps G2) | PARTIAL (R2 P7-9 with BUG-R2-3; fix not re-run) |
| R-65 | Privacy pause: triggers, durations, persistence, union merge, Resume names ids, intervals, 72 h cap, fail-closed | DONE-CHANGED | P3 `308049d`, `2f6b62d`, `5352a5d`; P7 A `54b2469`, `98f3025`, `a0e7086`, `abe8f49`; r2a `8f74377` · `PH/phone/hub/HubPauses.kt`, `GL/pause/*` | Capture-only pauses (DL §1 P7/A); 90-day resume tombstones (P7/A I2); quiet hours off ends the running pause (r2a) | PARTIAL (R1 S-PAUSE; R2 S-OFFLINE-PAUSE, P7-2, P7-3 paused half, P7-4 first half) |
| R-66 | Phone camera manual capture into the same pipeline | DONE-CHANGED | P7 C2 `517f453`; P8 `c74bb76` · `PH/phone/capture/PhoneCapture.kt` | Source is `rokid_phone`, not `phone` (DL §1 P7/C ruling 1); EXIF ignored (DL §4 P8 phone M5) | YES (R1 P7-10) |
| R-70 | Room schema §6.1; SQLCipher when encryption on | PARTIAL | P2 `36b6e34`, `39f7d27`; P5 `9dd246e`, `7f17fa0`; r2a `8800b56`; r3a `df13ae4` · `MEM/memory/db/*`, `EncryptionMigrator.kt` | Schema v1 frozen. The encryption toggle failed on device twice (R2 bind args, R3 REBUILD_INDEX); the fresh-connection fix is unverified. **Frame JPEGs are not encrypted** (Gaps G1) | PARTIAL (Room YES; D-D4 FAIL R2/R3) |
| R-77 | Forget fact/moment, extraction lock, transitive invalidation, re-summarise, redaction, proposal expiry | DONE-CHANGED | P7 F `1ea8f3b`, `cff2f88`, `a0f4ab7`, `a337d0b`; P8 fix `139090e`, `3e0a88c`, `9beea71` · `MEM/memory/forget/ForgetService.kt` | Retention ≠ Forget; VISION_EXTRACT per photo; tombstone prune at 8 d (DL §1 P7/F C1, P8 final fix-data I1/I2/C1) | YES (R1 S-FORGET) |
| R-71 | Legacy import worker §6.2 | DONE-CHANGED | P2 `1301f81`, `c78acfe`; P8 D5 `58df6d1` · `MEM/memory/legacy/LegacyImporter.kt`, `ORC/orchestration/LegacyImportWorker.kt` | No importer row for the frame inbox (DL §1 P2/T4). Automatic deletion of the legacy files after one release is not built (Gaps G4) | YES (P2/T5 counts) |
| R-72 | `UserFacingError`; no raw errors anywhere | DONE | P3 `a4c5b7f`; all phases; P8 E1 `157f0c3` · `CM/UserFacingError.kt` | — | PARTIAL (JVM smoke over every route; device screens reviewed, R1–R3) |
| R-73 | Test suites §6.4 | DONE | every task; P8 E1 · gate 3 281+ tests, 0 failures (SB header) | Flaky watch list (SB B9) | NO (JVM) |
| R-74 | Screenshot references + legacy relocation | DONE | P8 E3 `c63af46`; `8bae4ee`, `3a903df`, `f5508c9` · `docs/screens/{phone,hud}` | Root `window.xml` untracked (SB B10) | YES (R1–R3) |
| R-75 | On-device checklist script | DONE | P8 E3 `c63af46` · `tools/device-checklist.sh` (35 steps) | Some steps rely on a human verdict (DL §4 P8/E23) | YES (run R1–R3) |
| R-76 | Phase 0 spikes recorded in `docs/spikes/` | DONE | P0 `60aefdc` · `docs/spikes/2026-10-12-phase0-results.md` | — | YES (P0) |

## 2. Original MVP spec (`2026-05-28-android-orchestration-mvp-design.md`)

The v1 spec supersedes the MVP's UI and transport parts; its policy, queue and battery rules stay in force unless v1 restates them. Rows are per numbered section. IDs are `MVP-§`.

| ID | Requirement (short) | Status | Where (phase/commit · main file) | Deviation / ruling | Device-verified |
|---|---|---|---|---|---|
| MVP-1.1 | Implementation reality check (status note) | SUPERSEDED | v1 spec | — | n/a |
| MVP-2a | Option 1: single orchestrator + concurrent queues | DONE-CHANGED | baseline `fa3fde8`…`d70d19f`; ported P1 `8624386` · `ORC/work/QueueDrainWorker.kt` | WorkManager drain worker is the orchestrator; `OrchestratorService` is unused | YES (P4/T11 drain SUCCESS) |
| MVP-2b | Option 3 state machine from 2026-06-04 | DEFERRED | MVP §24 | Never started; no later plan | n/a |
| MVP-3.1 | Components: orchestrator, raw store, summarizer, summary store, tiers, critic, retry, gateway | DONE-CHANGED | P2 Room `39f7d27`; P1 ports · `MEM/memory/db/*`, `ORC/work/*` | Room replaces the file stores (v1 §6.1) | YES (P2/T5) |
| MVP-3.2 | Logical queues | DONE | `ORC/domain/queue/QueuePriority.kt` | — | YES (P4/T11) |
| MVP-4.1 | Queues concurrent by default | DONE-CHANGED | `ORC/work/OrchestrationScheduler.kt` | Unique work per queue role; one drain loop | NO |
| MVP-4.2 | Contention priority order + reserved readContext slot | DONE-CHANGED | `QueuePriority.arbitrate`; v1 Ask runs outside the queue (`ORC/orchestration/AnswerService.kt`) | The direct Ask path stands in for the reserved slot (v1 §4.7) | YES (R1 asks) |
| MVP-4.3 | CPU/thermal/low-battery pressure → FIFO deferral, 24 h TTL | PARTIAL | `QueueDrainWorker.kt:328–337` | Battery and saver only; no CPU or thermal input (Gaps G5) | NO |
| MVP-5.1 | Glasses pre-filter (dedup, battery, rules) | DONE | P1 port; P3 `49244eb` · `GL/capture/FrameGuard.kt` | Battery ≤ 10 %, dark frame, pHash Hamming ≤ 4, 2.5 s throttle | YES (Drive: DUPLICATE skips, SL §11) |
| MVP-5.2 | Ring buffer 500, auto-throttle at 75 %, hysteresis | SUPERSEDED | v1 §1.7 / `ef8fadd` · `GL/capture/OutboundBuffer.kt` | 200 frames + 8 MiB, FIFO, 72 h age cap; no throttle | YES (R2 S-FLUSH) |
| MVP-5.3a | Activity Recognition / Health Connect capture signals | DEFERRED | — | SL §16 Develop 1 (motion-aware fast capture); SB §5 item 1 | n/a |
| MVP-5.3b | Battery-only cadence fallback (30 s / 60 s) | DONE-CHANGED | P3/T11 `b61f9b2` · `GL/capture/CaptureCadence.kt` | User interval clamped 30 s–1 h (default 5 min), doubled when the glasses battery is low (DL §1 P3/T11 cadence) | YES (Drive at 30 s) |
| MVP-6a | Phone battery > 30 % → process every 15 min; ≤ 30 % not charging → defer; TTL 24 h | DONE | `ORC/work/QueueDrainWorker.kt`, `OrchestrationScheduler.kt:60` | Copy shows the real thresholds (DL §1 P7 battery copy) | NO |
| MVP-6b | Local model only when charging or battery > 70 % | GAP | only in unused `ORC/domain/policy/PolicyEngine.kt:19` | On-device embeddings run whatever the battery; `EmbeddingWorker` needs battery-not-low only (Gaps G6) | NO |
| MVP-6c | Poor/offline network: backoff retry, local if allowed, queued until TTL | DONE-CHANGED | P2 `179d9a6`; `ORC/orchestration/RetryPolicy.kt`, `RetryClassifier.kt` | Role-binding fallback endpoint replaces "try local" (v1 §4.2) | NO (DL §1 P2/T8 airplane check not run) |
| MVP-6d | Manual read bypasses schedule unless Battery Saver; explicit write immediate unless saver | DONE-CHANGED | `QueueDrainWorker.kt:328` | Queued READ/EXPLICIT_WRITE jobs are saver-gated. **The v1 direct Ask path is not saver-gated** (Gaps G7) | NO |
| MVP-7.1 | Backend-agnostic messages interface | DONE | `AI/model/ModelGateway.kt`, `AI/ai/ModelRouter.kt` | — | YES (R1) |
| MVP-7.2 | OpenAI-compatible cloud + Ollama local; open for llama.cpp | DONE | `AI/model/provider/*`; ODE `818c196` | Azure kind added; llama.cpp JNI built for embeddings | YES (Azure, R1–R3) |
| MVP-7.3 | Policy-driven routing (battery/network/saver/local) + metadata | SUPERSEDED | v1 §4.2 role bindings · `AI/ai/RoleBindingRepository.kt` | `LocalProviderSelector` unused | n/a |
| MVP-8 | ProviderBlocked: strict global block, cancel jobs, warm-start, recovery check, deduped notifications | PARTIAL | `PH/phone/ui/banner/Banners.kt` (AI_BLOCKED → Fix now), `StateFrameBuilder.kt:87` (`Health.Blocked`) | Banner and HUD health only. No global block, cancel or warm-start: `ProviderBlockState` is unused (Gaps G8) | NO |
| MVP-9.1 | Two-stage summarization | DONE | `ORC/work/QueueDrainWorker.kt` (VISION_EXTRACT → SUMMARIZE) | Extraction is per photo (P8 final fix-data I1) | YES (P4/T11; SL §13: 316 summaries) |
| MVP-9.2 | Critic every 3 h, 30 min window, 15 min step | PARTIAL | `OrchestrationScheduler.kt:230` (3 h periodic) · `ORC/work/CriticWorkerJob.kt` | No rolling-window code in `CriticWorkerJob` (Gaps G9) | NO |
| MVP-9.3 | Raw frames deleted after critic success; 24 h hard cap | SUPERSEDED | v1 §6.1 ("Frame JPEGs remain on disk"), R-16, R-77 | Kept per tier retention; Forget deletes them | n/a |
| MVP-9.4 | Critic fail → needs_review, excluded, re-summarise | DONE | `ORC/work/QueueDrainWorker.kt` (processCriticReview) | Feeds filter `reviewState='OK'` (DL §1 P4/T3) | NO |
| MVP-9.5 | Fallback: last trusted; ContextTemporarilyUnavailable + ETA; trigger priority reprocess | PARTIAL | `AnswerService.kt:45, 140` (`NoMemoriesException`) | No ETA and no reprocess trigger (Gaps G10) | NO |
| MVP-10.1 | Tiers short/medium/long + episodic tags | DONE | `MEM/memory/db/MemoryEntities.kt` (`tier`, `tags`) | — | YES (P2/T5) |
| MVP-10.2 | Retention configurable, 24 h / 7 d / 30 d | DONE | `MEM/memory/MemorySettingsStore.kt`; P7 F3 RetentionWorker | Retention never touches EXECUTED reminders (DL §1 P8 final fix-data C1) | NO |
| MVP-10.3 | Auto-promotion to long-term (composite score) + voice promotion | GAP | — | No promotion code (Gaps G11) | n/a |
| MVP-10.4 | 100 MB soft quota, 70/90 % thresholds, short-term-first eviction, Rokid-side pressure card | PARTIAL | `MemorySettingsStore.kt`, `Banners.kt:90–91` | Quota and phone banners only; no eviction and no HUD card (Gaps G12) | NO |
| MVP-10.5 | Provenance per item (persona, model, prompt version, ts) | GAP | `MemoryEntities.kt` has no such fields | v1 §6.1 omits them, but no ruling drops them (Gaps G13) | n/a |
| MVP-11.1 | Global persona, snapshot at enqueue, reframe | DONE-CHANGED | `AI/prompt/PersonaSettings.kt` (`{{persona}}` at call time); queue payloads keep `personaSnapshot` from legacy `app_config/persona` | v1 §4.3 template variable | NO |
| MVP-11.2 | Fixed shortcut intent catalog | SUPERSEDED | v1 §3.5 gestures | — | n/a |
| MVP-11.3 | Shortcut feedback cues | SUPERSEDED | v1 §5.1 toasts, §3.2 overlays | — | n/a |
| MVP-11.4 | English/Hindi/Oriya; separate input/output language; auto-detect | DONE-CHANGED | `46f223e`; `AI/prompt/PersonaSettings.kt` (ReplyLanguage AUTO), `VOI/voice/VoiceStores.kt` (speech language) | v1 §0.1 English + Hindi only | NO |
| MVP-11.5 | Multilingual embeddings, Indic fallback, per-pair override | SUPERSEDED | v1 §4.6 single EMBED binding; `LanguageRouter` unused | — | n/a |
| MVP-12.1–12.2 | Home cards; Config hub | SUPERSEDED | v1 §2.4 | — | n/a |
| MVP-12.3 | Secrets masked; secure storage | DONE | `AI/ai/KeyVault.kt`; P4 `411b1f6`, `deac654` | Write-only key field (DL §1 P4/T9) | YES (R3) |
| MVP-12.4 | Developer diagnostics toggle; verbose logging; 72 h retention | SUPERSEDED | v1 §2.4 Diagnostics (always visible) | — | n/a |
| MVP-12.5 | Limited Mode banner when optional permissions are skipped | GAP | — | v1 §0.1 also asks for "limited-mode banners"; §2.2 has none (Gaps G3) | n/a |
| MVP-13.1–13.3 | Onboarding style, order, minimum E2E gate | SUPERSEDED | v1 §2.4 onboarding (R-19) | — | n/a |
| MVP-13.4 | Mandatory/optional permissions; phone-mic fallback deferred | SUPERSEDED | v1 §2.4 Permissions step; phone mic built (R-60) | — | n/a |
| MVP-14.1 | Download manager: status, size, progress, pause/resume, Wi-Fi default, battery auto-pause | PARTIAL | ODE `eab2656`, `cad5287` · `ORC/work/LocalModelDownloadWorker.kt` | Built for embedding models (cancel keeps `.part`). No battery auto-pause (Gaps G14) | NO |
| MVP-14.2 | Verify checksum before activation; auto-retry once | DONE-CHANGED | ODE `460fdfa` · `AI/ai/local/ModelFileVerifier.kt` | Mismatch deletes `.part` and asks the user; IO retried 6× (DL §1 ODE/D) | NO |
| MVP-14.3 | A/B active/candidate slots + health-check promotion | GAP | `AI/model/lifecycle/ModelSlotManager.kt` (unused) | Gaps G15 | n/a |
| MVP-14.4 | Unhealthy local model → cloud fallback + notification | DONE-CHANGED | role fallback binding; ODE §6 lexical fallback + banner | — | NO |
| MVP-15.1 | Encryption toggle, default OFF | DONE | P5 `6741bef` · `MemorySettings.kt:183` | — | PARTIAL (D-D4 FAIL R2/R3) |
| MVP-15.2 | Enable migration: background, progress, checkpoint, retry, rollback | DONE-CHANGED | P5 `9dd246e`, `7f17fa0`; r3a `df13ae4` · `MEM/memory/db/EncryptionMigrator.kt` | Applied at next open with a crash-safe stage flag (P5/T14) | NO (D-D4 FAIL) |
| MVP-15.3 | Unmigrated data blocked from reads; 1-week TTL | SUPERSEDED | P5/T14 whole-file conversion at open | — | n/a |
| MVP-15.4 | Migration status card + popups | SUPERSEDED | v1 §1.4 (Migration card removed) | — | n/a |
| MVP-16.1–16.2 | `schema_version` per record; lazy migration | SUPERSEDED | master plan Global Constraints (Room version + Migration tests) | — | n/a |
| MVP-16.3 | Quarantine (exclude, TTL 30 d, retry, indicator) | GAP | `ORC/security/QuarantineManager.kt` (unused) | Gaps G16 | n/a |
| MVP-16.4 | Encrypted diagnostic bundle; decrypt via USB token / re-auth; screenshot block; ephemeral files; audit | GAP | `ORC/security/DiagnosticExportManager.kt` (unused); v1 Diagnostics export is plain redacted text | Gaps G16 | n/a |
| MVP-17 | Versioned config sync with retry, manual sync, conflict policy | DONE-CHANGED | P3 `pc_settings`; P5 `0ea9804` (Push settings) · `PH/phone/hub/Hub.kt` | Phone is the authority (v1 §1.6) | YES (ML: `tx pc_settings result=0`) |
| MVP-18 | Failure taxonomy | DONE-CHANGED | `ORC/orchestration/RetryClassifier.kt`, `CM/UserFacingError.kt` | Typed classes (DL §1 P7/F2) | n/a |
| MVP-19 | User-facing response rules | DONE-CHANGED | `VOI/voice/Answering.kt`, `ORC/orchestration/AskActions.kt` | Fixed copy; Thinking bounded at 60 s instead of a 15-min watchdog (P6/T8) | YES (R1–R3 toasts) |
| MVP-20 | readContext p95 ≤ 6 s; first feedback ≤ 1.5 s | GAP | — | Device: ~8.5 s glasses ask, ~8 s phone ask (DL §2 P6); no streaming; no ruling (Gaps G17) | measured, target missed |
| MVP-21 | Testing plan | SUPERSEDED | v1 §6.4 / R-73 | — | n/a |
| MVP-22 | Observability metrics list | PARTIAL | Diagnostics queues/tracker, `PipelineStatsStore`, `PcVoice: outcome=` codes (r3b) | `TelemetryReporter` unused; most listed metrics not collected (Gaps G18) | NO |
| MVP-23 | Local-only diagnostics; manual export; config export without secrets; import deferred | PARTIAL | `PH/phone/ui/settings/Diagnostics.kt` (export), `ORC/orchestration/MemoryExport.kt` | No config export (Gaps G19) | NO |
| MVP-24 | Deferred list (Option 3, persona editing, intent customisation, phone voice, config import) | DEFERRED | MVP §24 | v1 built persona editing, gesture remap and phone voice; Option 3 and config import remain deferred | n/a |

## 3. Agent flows (`2026-10-14-agent-flows-design.md` + plan)

| ID | Requirement (short) | Status | Where (phase/commit · main file) | Deviation / ruling | Device-verified |
|---|---|---|---|---|---|
| AF-1 | Two taps from Settings to a step; edit prompt/model/retrieval, test, save; one file per new flow | DONE | merge `4cb770c` · `AI/ai/flow/*`, `PH/phone/ui/settings/flows/*` | — | YES (R2) |
| AF-2.1 | Flow model: nodes, edges, kinds, facets (Prompt/Model/Retrieval/Custom) | DONE | A1 `b258e67` · `AI/ai/flow/AgentFlow.kt` | — | YES (R2) |
| AF-2.2 | Day-one flows Capture/Ask/Nudge matching execution | DONE-CHANGED | A1 `b258e67`; review `9343dd9` · `AI/ai/flow/flows/*` | Nudge edge label "if shouldNudge and proposedAction" (DL §1 AF/A); save_review→embed edge missing (DL §4 AF M6) | YES (R2) |
| AF-2.3 | LINK nodes ("→ Nudge") add/scroll to the flow | DONE | A1, D1 `50ac682` | LINK-added flow lost on process death (DL §4 AF M3) | PARTIAL (R2) |
| AF-2.4 | System preamble chip on every view | DONE | D1 `50ac682` | System chip opens a Prompt-only sheet (DL §1 AF/D) | YES (R2) |
| AF-2.5 | Registry rules (facets cover every role/key, unique ids, acyclic, ≥1 INPUT, Custom ids registered) | DONE | A1 tests | The "no PeriodicWorkRequest" anchor was narrowed to NudgeWorker (DL §1 AF/A) | n/a |
| AF-3 | `FlowLayout`: layering, side-input alignment, columns; tests | DONE-CHANGED | A2 `7e91a94`; review `9343dd9`; r2b `2c4f657` · `AI/ai/flow/FlowLayout.kt`, `PH/…/flows/EdgeRouting.kt` | Skip-layer edges routed in free lanes; Capture grid 5 columns (DL §1 AF/review I1); opens on the primary input's column (BUG-R2-1) | YES (R3) |
| AF-4.1 | Generic ListScreen port with multi-select | DONE | B1 `d99bb7c` · `PH/phone/ui/list/ListScreen.kt` | No error handling; selection not session-scoped (DL §4 AF M4/M5) | YES (R2) |
| AF-4.2 | Placement only under Settings → AI | DONE | B2 `2197701` · `SettingsRoot.kt:103` | — | YES (R2) |
| AF-4.3 | Flows list route, subtitle, Show selected, session selection | DONE-CHANGED | B2 `2197701` · `FlowsListSource.kt` | "Not set" counts toward the cap; "1 AI step" singular (DL §1 AF/B) | YES (R2) |
| AF-4.4 | Flow view: Canvas sections, cards, pills, horizontal scroll, live facet lines | DONE-CHANGED | D1 `50ac682` · `FlowView.kt`, `FlowGraph.kt` | Retrieval facet names fusion only in Hybrid (DL §1 AF/D); card clips at large font (DL §4 AF M1) | YES (R2) |
| AF-4.5 | Step sheet tabs Prompt/Model/Retrieval/Custom; no new write path | DONE | D2 `a08d4d5` · `StepSheet.kt`, `FacetEditors.kt` | Open editor closes the sheet first (DL §1 AF/D) | YES (R2) |
| AF-4.6 | Shared labels for Model, Retrieval and shared prompts | DONE | D2; review `8152476` | Spec §4 already amended for shared prompts | YES (R2 "shared by 2 steps") |
| AF-4.7 | Full-screen prompt editor; role vs SYSTEM gating; Cancel keeps draft | DONE-CHANGED | C1 `5936c2b`, `5509d3e` · `PH/…/prompts/FullScreenTextEditor.kt` | Back asks "Discard changes?" when edited (DL §1 AF/C) | YES (R2) |
| AF-5 | Errors and edge cases (Not set, missing prompt, unknown id, lazy) | DONE | D1/D2 | — | NO |
| AF-6 | Unit, Compose and smoke tests | DONE | gates 3031/3041 (DL §2) | Sheet touch covered only on device (DL §1 AF/D) | n/a |
| AF-7 | Delivery: streams A/B/C then D | DONE | `143d71b`, `da338c2`, `9e6cd8b`, `4cb770c` | — | n/a |

## 4. On-device embeddings (`2026-10-14-on-device-embeddings-design.md` + plan)

| ID | Requirement (short) | Status | Where (phase/commit · main file) | Deviation / ruling | Device-verified |
|---|---|---|---|---|---|
| ODE-2.1 | llama.cpp arm64-v8a via NDK + CMake, submodule pinned | DONE-CHANGED | N1 `818c196`; `93ed4ea`, `9fed70d` · `AI/../cpp/CMakeLists.txt`, `services/ai/build.gradle.kts` | b11618 @ `4dd6dd4`, NDK 28.2.13676358, `c++_static`, armv8-a baseline (DL §1 ODE/N) | NO |
| ODE-2.2 | JNI shim, five externals; `nativeEmbed` pooled and L2-normalised | DONE-CHANGED | N1 `818c196`; review `af208fc` · `llama_jni.cpp`, `LlamaCppNativeProvider.kt` | `nativeLoadModel(path, forEmbedding)` creates the embed context (DL §1 ODE/review I2); output matches `llama-embedding` (DL §2) | NO (host check only) |
| ODE-2.3 | Only arm64; others say "On-device models need a 64-bit ARM phone." | DONE | N1; U1 | Page uses `Build.SUPPORTED_ABIS`, not `isNativeAvailable()` (DL §4 ODE M7) | NO |
| ODE-2.4 | APK grows ~3–5 MB; no model in APK | DONE-CHANGED | `93ed4ea`, `c3d6966` | Stripped `.so` 4.5 MB at -O3 (DL §2 ODE native size) | n/a |
| ODE-2.5 | Tooling pinned in Gradle | DONE | `services/ai/build.gradle.kts`, `phone/`, `glasses/` | NDK pinned in all three (DL §4 ODE/N CLOSED) | n/a |
| ODE-3 | Catalog: one pinned bge-small entry; e5 later | DONE | D1 `460fdfa` · `AI/ai/local/LocalModels.kt` | e5-small deferred (SL §16 Develop 4) | NO |
| ODE-4.1 | Unique work per model, UNMETERED default, mobile-data override | DONE-CHANGED | D2 `eab2656`; review `b7a5e9b` · `ORC/data/models/LocalModelStore.kt` | Switch re-enqueues with REPLACE (DL §1 ODE/review I3) | NO |
| ODE-4.2 | HTTP Range resume from `.part` | DONE | D2 `eab2656` · `ORC/work/LocalModelDownloadWorker.kt` | 416 / short body treated as corruption (DL §4 ODE M3) | NO |
| ODE-4.3 | Progress via WorkManager | DONE | D2 | — | NO |
| ODE-4.4 | Verify size + SHA-256, atomic rename, mismatch message | DONE | D1/D2 | — | NO |
| ODE-4.5 | Models dir excluded from backups | DONE | `eab2656`, `6439310` · `phone/src/main/res/xml/*_rules.xml` | — | n/a |
| ODE-4.6 | Free-space check ≥ bytes × 1.2 | PARTIAL | D2 | Ignores bytes already in `.part` (DL §4 ODE/D) | NO |
| ODE-4.7 | Logs id + reason code only | DONE | D2 | No logcat test (DL §4 ODE/D) | n/a |
| ODE-5.1 | Page on generic ListScreen; rows and statuses | DONE | U1 `cad5287`, `867fe9c` · `PH/phone/ui/settings/ai/LocalModelsSource.kt` | — | NO |
| ODE-5.2 | Download / Use / Delete with confirms; progress overlay with cancel | DONE-CHANGED | U1 | Overlay says "Keep the app open" while it continues in the background (DL §4 ODE/U) | NO |
| ODE-5.3 | Wi-Fi-only note + "Allow mobile data" | DONE-CHANGED | U1 · `SettingsDeps.kt` `AppConfigMobileData` | Stored in excluded `app_config`, so a restore resets it to off (DL §1 ODE/review merge) | NO |
| ODE-5.4 | Use for Embeddings: endpoint `local_<id>` + bind EMBED → reindex | DONE-CHANGED | U1 | Id sanitised to `local_bge-small-en-v1_5-q8` (DL §1 ODE/U) | NO |
| ODE-5.5 | Delete: unbind first, keywords-only warning | DONE | U1 | — | NO |
| ODE-6.1 | Load on first use, release after 60 s idle, serialised and timed | DONE-CHANGED | integration `867fe9c`; review `c3d6966` | 8 texts per timed call (DL §1 ODE/review I1); a query waits behind a reindex batch (DL §4 ODE M6) | NO |
| ODE-6.2 | Load failure → ProviderConfigException → lexical fallback + "couldn't load" | DONE | N1; review `af208fc` | Endpoint Test shows "Address or key missing" for a load failure (DL §4 ODE M8) | NO |
| ODE-6.3 | Two consecutive load failures → "Couldn't load — re-download" | DONE-CHANGED | integration | Count kept in memory, not persisted (DL §1 ODE/integration) | NO |
| ODE-7 | Unit / worker / Compose / native / device tests | PARTIAL | gate 3209/0 (DL §2) | The device check is pending (SB §3 On-device models) | NO |

## 5. Glasses minimise + icon (`2026-10-14-glasses-minimise-icon-design.md` + plan)

| ID | Requirement (short) | Status | Where (phase/commit · main file) | Deviation / ruling | Device-verified |
|---|---|---|---|---|---|
| GM-1.1 | App always opens as the full timer HUD; no icon mode | DONE | merge `d1bf157` · `GL/MainActivity.kt` | SL §12 (user choice) | YES (Drive) |
| GM-1.2 / T1 | Double-tap → 3 s "Double-tap again to exit"; second minimises; else pause | DONE-CHANGED | T1 `0235b1d`; review `d74e54b` · `CH/DoubleTapExit.kt`, `GL/hud/InputRouter.kt` | Arms under plain toast / splash / nudge; a mid-window Confirm/Listening/Answer cancels (DL §1 GM); fast 4-tap fix (SL §12 I1) | PARTIAL (Drive report 1; 4-tap not recorded) |
| GM-1.3 / T3 | Minimised: task to back + corner icon with shrinking ring; paused glyph | DONE-CHANGED | T3 `a9c2480`; `d53e76e` · `GL/overlay/MinimisedIcon.kt` | Ring driven by `armed_at_ms` (DL §1 GM); ✓ photo-sent cue on the ring | PARTIAL (Drive; ML minimised) |
| GM-1.4 / T4 | While minimised: pop-ups; Confirm auto-opens and returns; glasses taps need reopen | DONE-CHANGED | T4 `5f9c720` · `GL/overlay/PopupOverlay.kt`, `GL/MainActivity.kt` | 600 ms vote guard after auto-open; Android Views (DL §1 GM) | NO |
| GM-1.5 / T2 | Icon corner + pop-up duration settings | DONE | T2 `0cac4d9`, `84c692b` · `CM/Protocol.kt`, `GlassesSettings.kt` | — | NO |
| GM-1.6 | Overlays keep their own double-tap; a remapped DOUBLE_TAP turns the gate off | DONE | T1 tests | M9: a late double-tap re-arms the gate (DL §4 GM) | NO |
| GM-1.7 | §1.7 exception: only a Confirm may come over another app | DONE-CHANGED | T4 `5f9c720`; ML `043eca5` | "Open on glasses" while minimised → phone pop-up, no appStart (DL §1 ML I2) | PARTIAL (ML step 4 not run) |
| GM-2 | `ExitGate`/`DoubleTapExit` pure state machine | DONE | T1 · `CH/DoubleTapExit.kt` | — | n/a |
| GM-3 | Icon window flags, 48 dp, ring, corner, overlay permission fallback + toast, keep-alive owner | DONE-CHANGED | T3 `a9c2480` | Manifest must declare SYSTEM_ALERT_WINDOW (appops alone did nothing, SL §12); icon doesn't return after a keep-alive restart (DL §4 GM M5) | PARTIAL (Drive) |
| GM-4 | Pop-up window (top-center, ≤ 3 lines, `popupSeconds`, replaces); Confirm while minimised (`EXTRA_RETURN_TO`) | PARTIAL | T4 `5f9c720` | The "Confirm on your phone" pop-up is not built (DL §4 GM minors) | NO |
| GM-5 | Investigate notifications/calls over the full HUD | DEFERRED | — | Not investigated (DL §4 GM minors); device item in SB §3 | PARTIAL (a call showed on the drive) |
| GM-6 | Phone settings rows; additive HudSettings sync | DONE | T2 `84c692b` | Unknown IconCorner drops `pc_settings` (DL §4 GM M8) | NO |
| GM-7 | v2: glasses asks while minimised (AI key, wake word, phone shortcut) | DEFERRED | spec §7 | SL §16 Develop 2; SB §5 item 2 | n/a |
| GM-8 | Unit, Robolectric, protocol and phone tests; device list | PARTIAL | gate 3247 (DL §2) | Device list not recorded as run (DL §3 minimise) | NO |

---

## 6. Phase 8 carry triage: final state of every item

### 6.1 Included (task)

| Carry | Task | Final state |
|---|---|---|
| 72 h cap on glasses clock; no 4 MB cap; disk-full drops newest; dir fsync | A1 | CLOSED `8b13193` (uptime age, 8 MiB cap, evict oldest). Device D-A1/D-A2 not run |
| Orphan `.part`; parent dir not fsynced | A4 | CLOSED `cc74402` |
| Unreadable JPEG deleted; lost kick; crash accounting; clear during ack; poison drop | A2 | CLOSED `9f82577` |
| `bufferedFrames` one too high; post-schedule hello buffered=1 | A2 | CLOSED (hello after every flush). OBS-1/B6 (no hello until the next frame after a phone restart) is a separate open item |
| Reassembler caps; ledger init/fsync/`.tmp`; edge tests | A3 | CLOSED `2ef05f4`, `71459a6` |
| `MainActivity.onCreate` reads the ledger on Main | A3 + D-A3 | CLOSED (lazy ledger). D-A3 PASS partial in R1 (StrictMode not in build) |
| Frame mid-wipe orphans JPEG/row | A4 | CLOSED `cc74402`, `ef2a5d0`, `7e0727a`. D-A4 PASS R3 |
| Archived-but-failed ingest never retried | A4 | CLOSED |
| "synthetic"/"screenshot" full-path match | A4 | CLOSED (file name only; also D5) |
| Streaming export; asks capped 10 k | B1 | CLOSED `867e28a`. Large-export device check pending |
| Lexical paging re-reads doclists | B2 | CLOSED `1dd8caf` |
| Vector paging at scale | B3 | CLOSED `4710c15` |
| IN lists unchunked | B4 | CLOSED `05bb016`. API 29 device check pending |
| SurfaceTexture release | C1 | CLOSED `19b9fc5`. D-C1 PASS R2 |
| `stopSelf` without startId; refused-start path | C1 | CLOSED. DL §4 P8 device M7 (a refused second startForeground) is open |
| Foreground fallback keeps pulling the HUD forward | C2 | CLOSED `86b81c5` (cap 3). D-C2 not run |
| Rokid AssistServer force-stop stops capture | C3 | MITIGATED `3e394fb` (MISSED re-arm, toast, banner). Force-stops still happen: SB B13 open |
| Connect throwing never closes the SDK session | C4 | CLOSED `ed3ef0a` (SessionSlot) |
| `GlassAppWatcher` leaks `CXRLink` on bind failure | C4 | CLOSED. D-C4 not run |
| `release()` doesn't replay connection state | C4 | CLOSED (SharedBridge replays) |
| Broadcaster: a throwing `onError` kills the loop | C5 | CLOSED `b494b0d` |
| Dead resolver/factory; keyset-only file; key copied back; silent retry; stale KDoc | D1 | CLOSED `96e28c1`. Stale KDoc left (DL §1 P8/D). Related: restored `app_config_secret*.bak` files (SB B12, HIGH, open) |
| `EndpointSeeder` swallows `CancellationException` | D1 | CLOSED |
| `EndpointRepository.delete` throws; orphan key; save not atomic | D2 | CLOSED `5636276`, `854e8d2`. DL §4 P8 ai M4 (previous key read outside the lock) is open |
| Base64 redaction drops JNI lines | D3 | CLOSED `0ab2e4d` |
| `keyFor()` masks the error; exact-match redaction | D3 | CLOSED |
| BuildConfig guard name-only | D3 | CLOSED (value guard in `phone/build.gradle.kts`) |
| SQLCipher passphrase not zeroed; `loadLibrary` every call; path untested | D4 + D-D4 | CLOSED `12f4a30`. D-D4 FAIL R2 and R3; fix `df13ae4` unverified |
| `EncryptedSharedPreferences` created per call | D4 | CLOSED |
| Legacy import: lost confidence, no retry, per-row, `readLines`, path match, rethrow, Main block | D5 | CLOSED `58df6d1` |
| WATCH: intermittent 30-min gate stall | E2 | CLOSED `c63af46` watchdog + `d137b17` 2 GB heap (SL §7). D-E2 (3× gate) not run |
| Device checks: Hi Rokid long-press; backgrounded keeps session; transformable in scroll; getInstance before BT grant | D-C3, D-C4, D-E1, D-A3 | D-C3 PASS approx R1 (remainder pending); D-C4 not run; D-E1 FAIL R1 → BUG-A fixed, PASS R2; D-A3 PASS partial R1 |

### 6.2 Covered elsewhere (no Phase 8 work)

| Carry | Where | Final state |
|---|---|---|
| `AppConfigStore.get` on Main decrypts prefs | Already fixed (`getWithoutSecret`) | CLOSED |
| `QuietHours` minutes not validated | Phase 7 | CLOSED (M12, 0..1439 both sides) |
| First-audio-after-idle 1.19 s stall | Phase 6 T8 | Tolerated, pinned |
| Quick pause→resume; cached `enabled`; resync races | Phase 6 T4 | CLOSED (`3cfae98`, requested state). DL §4 P6/T4 minors remain |
| "offline" while CONNECTING | Phase 6 T2 | CLOSED (`8c1ffcb`) |
| `togglePause` on Main; merged pause keeps frames; unknown origin; QUIET_HOURS ids; interval transactions; `PauseState` | Phase 7 | CLOSED except "unknown origin → PHONE", which DL §4 P3/T5 still lists open |
| µs rebuild race | Phase 6 T14 | Still listed open in DL §4 P5/T11 (one redundant rebuild) |
| `AskTextTest` literals; `\r`; Mn look-alikes; raw text under RetrievedContext | Phase 6 T13 + Phase 5 data blocks | CLOSED (`8613c13`). DL §4 P5/T6 Mn invisibles still listed |
| KDoc indent; CodeBox stale records; slider/hint; stale Test; `lastRun`; 5 s bounds; backfill ids; reentrant lock | Phase 6 T15 | CLOSED (`1332563`, `18fc018`) |
| `humanize` one level; OPENAI_COMPAT/llama.cpp rulings; schema carries; `SAFE_PATH`; empty coverage; queued native call; untick guard | Phase 6 T14 | CLOSED (`a8c2350`, `115ed8e`). DL §4 P5/T1–T9 lines kept as history |
| Old-session messages re-arm `listenAccepted` | Phase 6 T3 | CLOSED (`00f10c0`) |
| Long-press ENTER emits Tapped | Phase 6 T2 | CLOSED (`8c1ffcb`) |
| `AskDao.upsert` resets rating | Already fixed (`AskDao.complete`) | CLOSED |
| Cancellations consume the 8-attempt cap; no RETRY cap | Already fixed (`QueueRuntimeStore`, `RetryPolicy`) | CLOSED |
| `PERMANENT_TEXT` in 5xx bodies; RetryClassifier duplicates ProviderErrors | Phase 7 | CLOSED by type-first classes (DL §1 P7/F2) |
| db-journal / `app_config_secret` backup gap | `03376c5`, `b3d092c` | CLOSED for `shared_prefs`. SB B12: `files/app_config_secret.xml.bak.*` copies are not excluded (open, HIGH) |
| `MemoryDao.recent/all` include INVALIDATED; no FKs; 👎 payload | Phase 7 | CLOSED (F1/F2) |
| `LegacyConfigBridge` syncs/debounce/old key; `onClearApiKey` | Removed in Phase 5 | Obsolete |

### 6.3 Won't fix in v1 (71 items)

Unless a note says otherwise, every item is **WONT-FIX, unchanged**.

| # | Item | Final state |
|---|---|---|
| 1 | "With flush suspended" spike wording | WONT-FIX |
| 2 | 2 s retry backoff not in §1.8 | WONT-FIX (wording; see Amendments) |
| 3 | `:glasses:lintDebug` ChromeOS check | WONT-FIX |
| 4 | `AppConfigDefaultsTest` global mutation | WONT-FIX |
| 5 | `memory/` route highlights Activity | WONT-FIX |
| 6 | HomeScreen Rounded icons | WONT-FIX |
| 7 | FrameViewer zone read once | WONT-FIX |
| 8 | China Hi Rokid variant note | WONT-FIX |
| 9 | `PromptTemplateRepository` fallback | Obsolete (class gone) |
| 10 | `Protocol.kt:6` KDoc | WONT-FIX |
| 11 | `SwipeClassifier.kt:10` citation | WONT-FIX |
| 12 | `Wire` accepts `"v":"1"` | WONT-FIX |
| 13 | Blocking `execute()` ignores cancellation | WONT-FIX |
| 14 | `frameIdOf` commas | WONT-FIX (DL §4 P2/final "comma-path CSV still open") |
| 15 | p95 +46–110 ms wording | WONT-FIX |
| 16 | Post-reconnect halving single observation | WONT-FIX |
| 17 | WorkManager vs `Application.onCreate` race | WONT-FIX |
| 18 | §3.6 "flush waits for session open" wording | WONT-FIX (now addressed by the minimised-link amendment) |
| 19 | Cold-start 84 dp one frame | WONT-FIX |
| 20 | No permission rationale branch | WONT-FIX |
| 21 | Hint flashes on first launch | WONT-FIX |
| 22 | Untested shell gating / nav arg / CloseGuard | WONT-FIX (E1 route smoke added) |
| 23 | HUD presenter mutates state in composition | WONT-FIX |
| 24 | Filled REC dot | WONT-FIX |
| 25 | Redundant dependency, deprecation, interim polling | WONT-FIX |
| 26 | Pause stress test non-deterministic | WONT-FIX |
| 27 | Unknown battery fails open | WONT-FIX (still open in DL §4 P3/T11) |
| 28 | `PendingIntent` deprecation in a test | WONT-FIX |
| 29 | Legacy `FIRE_PULSE` broadcast alarm | WONT-FIX |
| 30 | `RfcommGoneTest` scope, wording, imports | WONT-FIX |
| 31 | `DatabaseFactory.inMemory` main-thread queries | WONT-FIX |
| 32 | Test fakes | WONT-FIX |
| 33 | `FrameCodec.join` size check | WONT-FIX |
| 34 | Non-discriminating FTS test; stale comment | WONT-FIX |
| 35 | 32 dp target, chart a11y, label mismatch, EXIF | WONT-FIX (EXIF reopened for phone captures: DL §4 P8 phone M5) |
| 36 | `ModelErrorMapper` phrase match | WONT-FIX |
| 37 | Deferral text not mapped; `"500"` assertion | WONT-FIX |
| 38 | `Basics.kt` AutoMirrored.Rounded | WONT-FIX |
| 39 | No FK role_binding→endpoint | WONT-FIX |
| 40 | No RED run, thin SchemaTest | WONT-FIX |
| 41 | `Pages.kt` OUTDATED as offline | WONT-FIX |
| 42 | No-arg `OpenAiCompatProvider()` | WONT-FIX |
| 43 | AI_ASSIST shown as PAUSED_BT | WONT-FIX |
| 44 | JSON without `v` → mismatch | WONT-FIX |
| 45 | Token prefs read on Main | WONT-FIX |
| 46 | No `CxrGlassesLink` tests | WONT-FIX (SessionSlot extracted) |
| 47 | `setRating` Unit; chatty Flow; `addIgnore` | WONT-FIX |
| 48 | `GlassesSessionPolicy` imports SDK enum | WONT-FIX |
| 49 | `MemoryEdits.memories` unused; NBSP | WONT-FIX |
| 50 | Thin `RepositoriesTest`; flag before build | WONT-FIX |
| 51 | Loose `skipped>=3`; fixtures | WONT-FIX |
| 52 | Reassembler allocations; ledger rewrite; `setWritable` test | WONT-FIX |
| 53 | PAUSED_BT line; StorageUsage tests | WONT-FIX |
| 54 | `AiHealthRule` tie | WONT-FIX |
| 55 | Duplicate frameId stats; `recent()` order; over-fetch | WONT-FIX |
| 56 | Sequential once-test | WONT-FIX |
| 57 | Voice guard ~9 s on unpinned 16 KiB pipe | WONT-FIX (P6 ruling) |
| 58 | Cancel/double-tap/NEEDS_REVIEW test gaps | WONT-FIX |
| 59 | `take()` splits surrogate pairs | WONT-FIX |
| 60 | ConnectException text; unescaped model; chat probe not one-token; … | WONT-FIX |
| 61 | Leading-zero octets; Ollama redirects; pre-validation rows | WONT-FIX |
| 62 | `onLinkState` unguarded; hello-before-ready | WONT-FIX |
| 63 | Rating before enqueue; non-atomic double tap; static source cards | WONT-FIX (source cards now live, `e18a288`) |
| 64 | Bridge-thread callbacks; OUTDATED tests; `cache.write` | WONT-FIX |
| 65 | Exited `phoneMessage` updates liveness | WONT-FIX |
| 66 | Fallback repeats; "failed: null"; init order; `runBlocking` | WONT-FIX |
| 67 | Empty hint flash; highlight lag; extreme ISO date; Today/Yesterday; empty tiles | WONT-FIX |
| 68 | TTS `onInit(false)` leak; old watchdog `stop()` | WONT-FIX |
| 69 | Pager reset; autoplay stops at the end | WONT-FIX |
| 70 | Bridge logs every rejected input | WONT-FIX (moot) |
| 71 | Overlay equality; timer under toast; nav idle | WONT-FIX |

### 6.4 Carries sent to Phase 8 that its triage does not list

- **Frame JPEG encryption at rest.** The Phase 5 plan (Task 14 design and its final notes) says "Frame JPEGs are not encrypted at rest in this phase — recorded for Phase 8". Phase 8's triage has no such line, and the code has none. → Gaps G1.
- **`purgeNonRokidMemories` on every launch.** The Phase 7 plan's ownership table sends it to "Phase 8 carry list". It is not in the triage, but DL §4 (P7/F → P8) records it as open. Code: `PH/MainActivity.kt:42`. Recorded open, so not silent.

---

## 7. Gaps (not built, not deferred, no ruling)

**G1 — Frame JPEGs encrypted when encryption is on** (v1 §6.1, R-70).
- *Spec:* "Frame JPEGs remain on disk (encrypted when enabled)."
- *Exists:* Encrypt memory converts only `context.db` (SQLCipher, `EncryptionMigrator.kt`). JPEGs in `files/rokid_archive` and `rokid_inbox` stay plaintext. They are excluded from backups (`03376c5`).
- *Missing:* any encryption of frame files (no `EncryptedFile` or cipher use for JPEGs). The re-encrypt or decrypt step when the toggle flips. Read paths for thumbnails, viewer and VISION input. The Phase 5 → Phase 8 carry (see 6.4).

**G2 — User notes as a data source** (v1 §5.4: "Notes (shopping list + user notes)").
- *Exists:* the `note` table and `NoteDao.recent()` (read only). `NotesSource` reads the shopping list plus up to 5 notes.
- *Missing:* any way to create a note. There is no UI, no `insert`, and no action type, so the "user notes" half always comes back empty.

**G3 — Limited-mode banner** (v1 §0.1 "health/limited-mode banners"; MVP §12.5).
- *Exists:* the §2.2 banners (AI blocked, glasses, protocol, deferred, paused, index, storage).
- *Missing:* a persistent "Limited mode" banner when optional permissions are skipped (calendar, contacts, location, notifications, mic). Today each screen shows its own notice instead.

**G4 — Legacy files deleted after one release** (v1 §6.2).
- *Exists:* `LegacyImporter.LEGACY_FILES` are deleted only by Delete all (`MemoryWipe.kt:87`).
- *Missing:* the scheduled clean-up "one release" later. No task or backlog item owns it. It is not due yet.

**G5 — CPU/thermal pressure deferral** (MVP §4.3).
- *Exists:* battery-saver and ≤ 30 % battery deferral (`QueueDrainWorker.kt:328–337`).
- *Missing:* CPU-load or thermal-status (`PowerManager.getCurrentThermalStatus`) inputs.

**G6 — Local model allowed only when charging or battery > 70 %** (MVP §6).
- *Exists:* the rule, but only in the unused `PolicyEngine.kt:19`.
- *Missing:* enforcement. On-device llama.cpp embeddings (query and reindex) run at any battery level. `EmbeddingWorker` asks only for battery-not-low.

**G7 — Battery Saver gate on manual asks** (MVP §6: "Manual readContext bypasses schedule unless Android Battery Saver is ON").
- *Exists:* the saver gate on queued READ/EXPLICIT_WRITE jobs.
- *Missing:* the same gate on the v1 direct Ask path (`AnswerService`, voice and typed). The only related ruling ("Ask stays available while the pipeline is paused", DL §1 P4/final) is about pause, not Battery Saver.

**G8 — ProviderBlocked strict global block** (MVP §8.2–8.5).
- *Exists:* the AI_BLOCKED banner with Fix now (`Banners.kt`) and `Health.Blocked` on the HUD.
- *Missing:*
  - blocking capture, ingest, read, write, summarizer, critic and retry;
  - cancelling pending jobs with reason ProviderBlocked;
  - auto-retriggering system pipelines after recovery;
  - the 10-min warm start;
  - deduped notifications.
- `ProviderBlockState`/`OrchestratorService` are ported but never constructed.

**G9 — Critic rolling window (30 min window, 15 min step)** (MVP §9.2).
- *Exists:* a 3 h periodic `CriticWorkerJob` (`OrchestrationScheduler.kt:230`).
- *Missing:* no window or step logic in `CriticWorkerJob`.

**G10 — Query fallback ETA and priority reprocess** (MVP §9.5).
- *Exists:* `NoMemoriesException("Context temporarily unavailable")` mapped to fixed copy.
- *Missing:* an ETA when one is known, and "trigger immediate priority reprocessing for affected topic/window".

**G11 — Auto-promotion to long-term + voice promotion** (MVP §10.3).
- *Exists:* the tier field. Explicit "remember this" is stored as EXPLICIT_REMEMBER.
- *Missing:* the composite-score promotion (frequency, recency spread, emphasis, critic confidence) and a voice "promote" trigger.

**G12 — Quota eviction and the Rokid-side pressure card** (MVP §10.4).
- *Exists:* the 100 MB soft quota setting and 70/90 % phone banners.
- *Missing:* short-term-first eviction that protects important long-term memories, and any memory-pressure indicator on the HUD.

**G13 — Provenance per memory** (MVP §10.5).
- *Exists:* nothing. `memory` has no persona, model/backend or prompt-version fields, and `summary_trace` holds only state and note.
- *Missing:* the audit metadata. v1 §6.1's schema omits it, but no ruling records the drop.

**G14 — Download auto-pause at ≤ 30 % battery** (MVP §14.1).
- *Exists:* `LocalModelStore` constraints cover network type only.
- *Missing:* a battery constraint (`setRequiresBatteryNotLow` or a ≤ 30 % / not-charging pause).

**G15 — A/B model slots with health-check promotion** (MVP §14.3).
- *Exists:* `ModelSlotManager.kt` (unused).
- *Missing:* active/candidate slots, the health-check promotion flow and the failure dialog. The on-device model is used directly once verified.

**G16 — Quarantine and encrypted diagnostic export/decrypt** (MVP §16.3–16.4).
- *Exists:* `QuarantineManager.kt` and `DiagnosticExportManager.kt` (both unused). Diagnostics exports a plain, redacted text file.
- *Missing:*
  - quarantine of records that fail migration (30 d TTL, retry, indicator);
  - the encrypted bundle;
  - USB-token or biometric decrypt;
  - screenshot blocking;
  - ephemeral decrypted files and a decrypt audit.

**G17 — Answer latency targets** (MVP §20).
- *Spec:* readContext p95 ≤ 6 s; first feedback ≤ 1.5 s, then stream the rest.
- *Measured:* ~8.5 s for a glasses ask and ~8 s for a phone ask (DL §2 P6). Answers don't stream, and Thinking is bounded at 60 s.
- *Missing:* no ruling accepts the miss, and no p95 measurement exists.

**G18 — Observability metrics** (MVP §22).
- *Exists:* Diagnostics queues/tracker, `PipelineStatsStore` counters, `PcVoice: outcome=` codes.
- *Missing:* most of the listed metrics, including:
  - retry backoff stages, TTL expiries and JobStuck counts;
  - local fallback usage and GlassPolicyBlocked counts;
  - ConnectionError recovery latency;
  - activity-state distribution and promotion counts;
  - ProviderBlocked duration;
  - migration/quarantine states and model slot health.
- `TelemetryReporter` is unused.

**G19 — Config export excluding secrets** (MVP §23).
- *Exists:* memory export (JSON) and diagnostics export.
- *Missing:* an export of the app's configuration (endpoints without keys, bindings, prompts, settings). Import is explicitly deferred; export was in MVP scope.
