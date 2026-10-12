# Proactive Context v1.0 — Master Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. This master plan does not contain implementation steps itself — it indexes the per-phase plans, fixes the interfaces between them, and maps every spec requirement (R-ID) to a phase task. Execute phase plans in order.

**Goal:** Ship v1.0 of the phone + Rokid glasses apps redesigned in the Live Fit design language, on CXR, with the AI/retrieval/voice/nudge/action features in the spec.

**Architecture:** Live Fit-style multi-module Gradle build under `android-companion/` (`:phone`, `:glasses`, `:core:*`, `:services:*`). The phone is the hub and single source of truth (except capture pause, which is fail-closed on both sides); the glasses are a CXR renderer + capture/mic/input device. Existing backend logic is moved into modules, not rewritten; UI is rebuilt.

**Tech Stack:** Kotlin 2.0.21, AGP 8.13.2, Gradle 8.13, Jetpack Compose (BOM 2024.09.00; Material3 on phone, foundation-only on glasses), Navigation Compose 2.8.0, WorkManager 2.9.1, Room 2.6.1 + KSP 2.0.21-1.0.27, SQLCipher `net.zetetic:sqlcipher-android:4.6.1`, kotlinx-serialization 1.7.3, OkHttp 4.12.0, Rokid CXR (`com.rokid.cxr:client-l:1.1.2` phone, `com.rokid.cxr:cxr-service-bridge:1.5` both), Robolectric 4.13 for JVM UI/DB tests.

**Spec:** `docs/superpowers/specs/2026-10-12-v1-redesign-design.md` (read it first; this plan argues from it — where they disagree, the spec wins).

## Global Constraints

- Phone: `minSdk 29`, `targetSdk 35`, `compileSdk 36`. Glasses: `minSdk 28`, `targetSdk 32`, `compileSdk 36` (Rokid OS fixed).
- Glasses UI: Compose **foundation only — no Material**; Material **Outlined** icons only (`material-icons-extended`).
- `:core:*` depend on nothing app-specific; `:glasses` depends only on `:core:model` and `:core:hud`; services never depend on `:phone`.
- Phone theme is **light only**. Tokens exactly: Mint `#14C3A2`, MintDeep `#0E9F86`, Sky `#3D8BFF`, Ink `#15181D`, InkSoft `#6B7280`, Line `#ECEFF3`, Surface `#FFFFFF`, SurfaceSoft `#F6F8FA`; HUD green `#3CFF6E`, brightness 1.0 / 0.6 / 0.35.
- Every CXR JSON payload carries `v: PROTOCOL_VERSION`. Glasses → phone payloads are text in `Caps` strings (Base64 for binary); there is no bytes argument on the glasses side.
- No raw exception/socket text in any UI (phone or HUD); all errors go through `UserFacingError` (spec §6.3).
- No audio leaves the phone for transcription; phone voice uses `createOnDeviceSpeechRecognizer` only.
- Language scope: English is primary; Hindi is the only additional language target. Hindi voice is optional and capability-gated. Retrieval validation covers English, Hindi, and mixed English/Hindi text.
- Capture pause is fail-closed: nothing resumes capture implicitly (spec §1.7, §5.5).
- Secrets: `.env` is git-ignored; API keys live in EncryptedSharedPreferences, never in Room or logs.
- Room: schema version 1 is frozen at the end of Phase 2 Task 2; every later schema change bumps the version and ships a `Migration` with a `MigrationTestHelper` test (no destructive migration outside tests).
- Commit after every task; messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Gradle commands run from `android-companion/` with `ANDROID_HOME=/Users/debasishkanhar/Desktop/rokid/android-sdk`.

## Review Focus

Inputs/conditions the spec implies but does not spell out; each is pinned by a test in the owning task:

1. **Legacy data that is malformed, partial, or imported twice** (crash mid-import, re-run) — expected: bad lines skipped and counted, no duplicates, import resumes. Owner: Phase 2 Task 4.
2. **Endpoint input with whitespace, trailing slash, missing scheme, mixed-case host, or a key with surrounding spaces** — expected: normalised or rejected with a clear message; never stored broken. Owner: Phase 2 Task 6.
3. **English, Hindi/Devanagari, and mixed English/Hindi memory text in lexical search** — expected: a word typed by the user finds memories containing it; Devanagari combining marks stay within word tokens, and punctuation/emoji are ignored. Owner: Phase 2 Task 1.
4. **Timezone/DST/clock changes** (user travels; phone and glasses clocks differ) — expected: Today/Week grouping uses the device's current zone at query time on capture instants; pause/quiet-hours math uses offset-corrected instants. Owner: Phase 2 Task 3 (day-bucket query) + Phase 3/7 (pause, quiet hours — tests listed in this plan's phase table).
5. **Low storage** while writing frames/DB — expected: write fails cleanly with a `UserFacingError` ("Phone storage full"), glasses keep the frame buffered (no ack), nothing half-written. Owner: Phase 3 (frame ingest).

---

## Phase plans

| Phase | Plan file | Status | Depends on |
|---|---|---|---|
| B Baseline commit | Phase 1 Task 0 in `2026-10-12-v1-phase1-restructure.md` | written | — (**first**; everything else starts from its SHA) |
| 0 Spikes | `2026-10-12-v1-phase0-spikes.md` | written | B (runs in its own worktree `../pca-spike`) |
| 1 Module restructure + core modules | `2026-10-12-v1-phase1-restructure.md` | written | B (main checkout; may run in parallel with 0) |
| 2 Data layer | `2026-10-12-v1-phase2-data-layer.md` | written | 1 |
| 3 CXR link + glasses app | to be written after Phase 0 results | — | 0, 2 |
| 4 Phone shell | to be written after Phase 2 | — | 2 (+3 for live glasses state; fixtures otherwise) |
| 5 AI + Settings | to be written after Phase 4 | — | 2, 4 |
| 6 Voice + answers | to be written after Phase 0 + 5 | — | 0, 3, 5 |
| 7 Nudges, actions, sources, pause, onboarding | to be written after 6 | — | 3, 5, 6 |
| 8 Hardening | to be written after 7 | — | all |

Task references are `Phase.Task` (e.g. 2.6 = Phase 2 Task 6); every detailed task also carries a `**Requirements:**` line. Phases 3–8 get their own detailed plan (same header/format, bite-sized TDD steps) when their prerequisites are done, because their concrete values (CXR message limit, TTS route, STT behaviour, final DAO shapes) come from earlier phases. Each later plan MUST cite R-IDs per task and MUST cover the R-IDs assigned to it below.

## Requirement coverage

| R-ID | Phase.Task | Notes |
|---|---|---|
| R-01 | 1.1–1.8 (+ new modules created in 3, 5, 6, 7) | `:phone`, `:glasses`, `:core:model/ui/hud`, `:services:ai/memory/orchestration` in P1; `:services:glasses-link` P3; `:services:voice` P6; `:services:connectors` P7 |
| R-02 | 1.4, 1.5 (theme, components, Swipe/DoubleTap) · 3 (CxrGlassesLink, AuthActivity, GlassAppWatcher, HudController, HudNav, ConfirmOverlay) · 6 (PushToTalk, EnergyVad, AndroidOnDeviceStt, SpeechPacks) · 7 (setup wizard scaffold) | |
| R-03 | 1.0 (baseline) · 1.1–1.8 · 2.5, 2.7–2.9 | existing 45 tests keep passing (43 after 2.2 removes the dead-entity tests); legacy queue, Config screen and fallback behaviour preserved |
| R-04 | 3 | RFCOMM removal after CXR frames flow |
| R-05 | 1.3 (types, channels, version) · 3 (wiring + mismatch UX) | |
| R-06 | 1.3 (type) · 3 (`StateBroadcaster` ≤ 4 Hz) | |
| R-07 | 3 | `ContextHubService`; polling loop removal completes in 4 |
| R-08 | 0.2–0.3 (measured) · 3 (implemented) | chunk size and ack mode from Phase 0 |
| R-10 | 4 | |
| R-11 | 4 | |
| R-12 | 1.5 (tokens + base components) · 4 (FrameThumb, BarChart, StatusStrip, ConfidenceChip, app icon) · 5 (CodeBox, VersionStrip, HudPreview) | |
| R-13, R-14, R-15, R-16 | 4 (R-15 day buckets from 2.3) | R-16 cascade semantics from R-77 (7) |
| R-17, R-18 | 5 (Settings tree, glasses settings) | |
| R-19 | 7 | |
| R-20 | 4, 5 | |
| R-30, R-31, R-32, R-33, R-34, R-35 | 0.3–0.4 (background send, TTS route measured) · 1.3–1.4 (R-32/R-34 primitives) · 3 (R-30–R-34) · 6 (R-35 TTS) · 3 (R-35 offline) | |
| R-40 | 2.6 (registry, keys, Test) · 2.7 (seed) · 2.9 (legacy Config bridge) | UI in 5 |
| R-41 | 2.6 (EMBEDDINGS capability, kinds, embedding probe) · 5 (embedding calls) | |
| R-42 | 2.7 | picker UI in 5 |
| R-43, R-44, R-45, R-46, R-47 | 5 | |
| R-48 | 2.1 (FTS, English/Hindi normaliser) · 5 (BM25, cosine, hybrid) | |
| R-49, R-50, R-51, R-52, R-53 | 5 | |
| R-60 | 6 | |
| R-61, R-62, R-63, R-64, R-65, R-66 | 7 (R-65 `PauseRecord`/`QuietHours` types from 1.3) | |
| R-67 | 0.4 (probe) · 6 (gate + UX) | |
| R-70 | 2.1–2.3 | encryption toggle migration in 5 (Settings → Memory) |
| R-71 | 2.4–2.5 | |
| R-72 | 2.8 (typed model errors, short trace messages) · 3 (`UserFacingError` introduced) · all later phases | |
| R-73 | every task (TDD) · 8 (UI smoke suite completion) | |
| R-74 | 8 | |
| R-75 | 8 | |
| R-76 | 0.1–0.5 | |
| R-77 | 7 | |

## Cross-phase interfaces (fixed here so phases agree)

**Modules and packages.** Moved legacy code keeps its existing Kotlin package (e.g. `com.rokid.context.work`) to avoid churn. New code uses:

| Module | Gradle namespace | New-code package |
|---|---|---|
| `:phone` | `com.rokid.context` | `com.rokid.context.phone` |
| `:glasses` | `com.rokid.context.glasses` | `com.rokid.context.glasses` |
| `:core:model` | (JVM) | `com.rokid.context.core.model` |
| `:core:ui` | `com.rokid.context.core.ui` | `com.rokid.context.core.ui` |
| `:core:hud` | `com.rokid.context.core.hud` | `com.rokid.context.core.hud` |
| `:services:ai` | `com.rokid.context.services.ai` | `com.rokid.context.ai` |
| `:services:memory` | `com.rokid.context.services.memory` | `com.rokid.context.memory` |
| `:services:orchestration` | `com.rokid.context.services.orchestration` | `com.rokid.context.orchestration` |
| `:services:glasses-link` (P3) | `com.rokid.context.services.glasseslink` | `com.rokid.context.link` |
| `:services:voice` (P6) | `com.rokid.context.services.voice` | `com.rokid.context.voice` |
| `:services:connectors` (P7) | `com.rokid.context.services.connectors` | `com.rokid.context.connectors` |

**Dependency direction:** `orchestration → ai → memory`; `orchestration → memory`; `phone → all services`; `memory` defines `Embedder` (implemented in `ai`) so it never depends on `ai`. The single Room database (`ContextDatabase`) lives in `:services:memory`; other services use its DAOs.

**Service locators (no DI framework in v1):** `MemoryGraph.get(context)` (P2.2), `AiGraph.get(context)` (P2.5/2.6). Later phases add `HubGraph` in `:phone`.

**Key types produced by Phases 1–2** (exact names; later phases consume them):
- `:core:model` — `PROTOCOL_VERSION`, `Channels`, `Wire`, `StateFrame`, `CaptureState`, `PauseRecord`, `PauseOrigin`, `Health`, `NudgeLine`, `MemoryLine`, `Overlay`, `HudSettings`, `Verbosity`, `HudPosition`, `Gesture`, `GestureAction`, `Command`, `Hello`, `FrameMeta`, `FrameAck`.
- `:core:ui` — `PcColors`, `PcTheme`, `SoftCard`, `IconChip`, `TextChip`, `ChipRow`, `SectionLabel`, `ScreenHeader`, `GradientHeader`, `PillNav`, `PillNavItem`, `SegmentedSwitch`, `MetricTile`, `GradientCircleButton`, `GradientButton`, `Banner`, `BannerTone`.
- `:core:hud` — `Hud`, `Glyph`, `Label`, `Scaled`, `OutlinedBox`, `HudToast`, `SwipeClassifier`, `SwipeKey`, `Swipe`, `DoubleTapDetector`.
- `:services:ai` — `ProviderSettings`, `ModelProvider`, `ModelImageInput`, `EndpointKind`, `Capability`, `Endpoint`, `EndpointRepository`, `KeyVault`, `UrlNormalizer`, `EndpointTester`, `Role`, `RoleBinding`, `RoleBindingRepository`, `ModelRouter` (incl. `generateFromFrames`), `ModelCallException`, `ProviderErrors`, `ProviderBuilder`, `ProviderFactory`, `EndpointSeeder` (`seedIfNeeded`, `syncFromLegacy`), `SeedState`, `EndpointTester`/`TestResult`, `EnvDefaults`, `AiGraph`.
- `:services:memory` — `ContextDatabase`, all entities/DAOs in spec §6.1, `SearchNormalizer` (English/Hindi word normalisation), `FrameRepository`, `MemoryRepository`, `TraceRepository`, `DayBucket`, `MemoryGraph` (incl. `ensureLegacyImported`), `LegacyImporter`, `ImportReport`, `DbKeyStore`, `Embedder` (interface only).
- `:services:orchestration` — `LegacyImportWorker`, `RetryClassifier`, `LegacyConfigBridge` (transitional, deleted in 5); legacy facades `MemoryRecordStore` (in memory), `FrameStreamStore`, `SummaryTraceStore` (transitional, deleted in Phase 4).
