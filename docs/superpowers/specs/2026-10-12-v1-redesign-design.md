# Proactive Context v1.0 — Phone + Glasses Redesign (Design Spec)

- **Date:** 2026-10-12
- **Status:** Approved in brainstorming (sections 1–6), pending written-spec review
- **Supersedes (UI + transport parts of):** `2026-05-28-android-orchestration-mvp-design.md` (its policy, queue and battery rules remain in force unless restated here)
- **Design reference:** Rokid Live Fit — `/Users/debasishkanhar/Desktop/rokid/live-fitness-tracker` (branch `design/livefit-v1-v2`, HEAD `4529735`), esp. `docs/superpowers/specs/2026-10-05-livefit-v1-design.md` §6 and `design-refs/mock/`
- **Brainstorm mockups:** `.superpowers/brainstorm/79603-1791796256/content/` (`phone-home.html`, `activity-tab.html`, `glasses-hud.html`, `settings.html`)

> **How to use this doc.** Sections 1–6 are the design. Section 8 is a numbered requirements checklist (R-xx). The implementation plan MUST reference R-IDs per task, and every R-ID must be covered by at least one task. When this doc and the plan disagree, this doc wins until amended.

---

## 0. Intent

### 0.1 What the user asked for
- Revamp phone and Rokid glasses apps to be visual and polished, following Live Fit's design patterns.
- Adopt Live Fit's architecture: CXR transport; phone pushes a `StateFrame`; glasses render pages.
- One spec for both apps (they are functionally interlinked).
- Add: push-to-talk queries, proactive cards (nudges), onboarding wizard, privacy pause, spoken answers (TTS), memory timeline, gesture mapping, health/limited-mode banners.
- Settings must cover: model endpoints, models per role, connectors (data sources + actions/tools), prompts (with test bed, versioning, output schema / guided decoding), retrieval (lexical / semantic / hybrid with selectable fusion), embedding models on open-source/custom endpoints.
- Target is **v1.0**. MCP / custom tools are v2.0.
- Language scope: **English is primary; Hindi is the only additional language target**. Hindi voice is optional and subject to the on-device speech capability gate (§5.1.1).

### 0.2 Product (unchanged)
Battery-first proactive memory assistant: glasses capture sparse frames → phone extracts memories → memories answer contextual questions and drive proactive nudges.

### 0.3 Success criteria
1. Phone and glasses UIs visibly follow the Live Fit design language (tokens, components, HUD rules in §2.3 / §3.3).
2. End-to-end on real hardware: capture → memory → ask by voice on glasses → spoken + on-HUD answer with sources on phone.
3. A nudge reaches the HUD; a proposed action is confirmed on HUD and executed.
4. A prompt can be edited, tested on a real frame, saved as a new version, and restored.
5. Retrieval mode and fusion strategy are switchable and observably change "Try it" results.
6. No raw exception/socket text appears in any UI.

### 0.4 Out of scope (v1.0)
MCP / custom tool connectors (interface only), background phone-camera capture, cloud sync, dark theme, multi-user, language-specific tuning beyond English and Hindi.

---

## 1. Architecture & protocol

### 1.1 Modules (under `android-companion/`)

| Module | Responsibility |
|---|---|
| `:phone` | Phone app (Compose + M3, NavHost, ViewModels + StateFlow). Hosts `ContextHubService` (foreground) owning glasses link, voice, orchestration, state broadcast. Replaces `:app`. |
| `:glasses` | Glasses APK. Compose **foundation only** (no Material), minSdk 28, targetSdk 32. Renderer + capture + mic + input. Replaces `:glasses-app`. |
| `:core:model` | Shared protocol: `StateFrame`, `HudPage`, `HudSettings`, `Overlay`, `Command`, channel names, `PROTOCOL_VERSION`. Pure Kotlin + kotlinx-serialization. |
| `:core:ui` | Phone design system (§2.3). |
| `:core:hud` | Glasses design system + input primitives (§3.3, §3.5). |
| `:services:glasses-link` | Phone-side CXR-L session (`com.rokid.cxr:client-l`), auth gate, reconnect, `GlassAppWatcher`. |
| `:services:voice` | Audio intake (glasses PTT chunks + phone mic), on-device STT (`SpeechRecognizer` / `createOnDeviceSpeechRecognizer`), `EnergyVad`, TTS. |
| `:services:ai` | Endpoint registry, role bindings, providers (ported), prompt/schema store + versions, `StructuredOutput` adapters, test runner. |
| `:services:memory` | Room DB, memory tiers (ported), FTS, `VectorIndex`, retrievers, `FusionStrategy`. |
| `:services:orchestration` | Ported workers (ingest, summarizer, critic, queue drain), battery policy, `NudgeEngine`, `LegacyImportWorker`, `ReindexWorker`. |
| `:services:connectors` | Data sources, built-in actions, `ToolConnector` interface + `McpConnector` stub. |

Rules: `:core:*` depend on nothing app-specific. `:glasses` depends only on `:core:model` and `:core:hud`. Services never depend on `:phone`. Gradle Rokid Maven repo: `https://maven.rokid.com/repository/maven-public/`.

### 1.2 Code reuse from Live Fit
Copy and adapt (rename package, keep tests): theme + components (`Theme.kt`, `Components.kt`, `GlassesIcon.kt`), `PillNav`, setup wizard scaffold, `CxrGlassesLink`, `AuthActivity`/`AuthGate`, `GlassAppWatcher`, glasses `HudController` skeleton, `Hud` tokens / `Glyph` / `Label` / `Scaled`, `ConfirmOverlay`, `SwipeClassifier`, `DoubleTap`, `HudNav`, `PushToTalk`, `EnergyVad`, `AndroidOnDeviceStt`, `SpeechPacks`.

### 1.3 Code ported from this repo (moved, not rewritten)
`model/provider/*` (OpenAiCompat, Ollama, LlamaCppNative, LocalProviderSelector), `ModelProviderResolver`, `ProviderBlockState`, `work/*` (FrameIngestWorker, SummarizerWorkerJob, CriticWorkerJob, QueueDrainWorker, OrchestrationScheduler), queue + battery policy, `MemoryRecordStore` tier logic, `retrieval/LanguageRouter`, `security/*`, glasses capture guardrails (dark-frame, pHash, battery ≤10%, throttle, JPEG q62 @ 360px).

### 1.4 Removed
Raw RFCOMM/SPP: `glasses/RokidBluetoothProtocol.kt`, `bridge/RokidBridgeService.kt`, `bridge/RokidConfigPushClient.kt`, glasses `CompanionConfigServer.kt`. `MainActivity` god-composable and 1.8 s polling loop. XML glasses layout + drawables. Unused stub screens (Live, Status, Queues, Memory table, Migration card) — folded per §2.

### 1.5 CXR channels

| Dir | Channel | Payload |
|---|---|---|
| P→G | `pc_state` | `StateFrame` JSON (see 1.6) |
| P→G | `pc_settings` | `HudSettings` JSON |
| G→P | `pc_frame` | Caps: metadata JSON (`frameId`, `chunkIdx`, `chunkCount`, `crc32`, capture ts on glasses clock, luma, phash, battery) + one Base64 JPEG chunk as a second Caps string (see 1.8). Glasses-side `CXRServiceBridge.sendMessage(channel, Caps)` has no bytes argument; Base64-in-Caps is the path Live Fit verified on device for `lf_audio`. |
| P→G | `pc_frame_ack` | `{frameId, ok, reason?}` — glasses delete a buffered frame only after `ok=true` |
| G→P | `pc_cmd` | `Command` JSON: `Tap`, `DoubleTap`, `Swipe(dir,long)`, `Confirm(proposalId, yes)`, `Pause(pause: PauseRecord)`, `Resume(clears: Set<pauseId>)`, `PageChanged(page)`, `ListenStart`, `Cancel` |
| G→P | `pc_audio` | PCM 16 kHz mono chunks, Base64 in a Caps string (3 200-byte chunks, as Live Fit) |
| G→P | `pc_listen_end` | end-of-utterance marker |
| G→P | `pc_hello` | glasses protocol version, battery, buffer size, glasses wall-clock ms (for offset), local `PauseRecord`s (§5.5) |

Every JSON payload carries `v: PROTOCOL_VERSION`. On mismatch the glasses show "Update Proactive Context"; the phone shows a banner.

### 1.6 `StateFrame` (phone is the single source of truth)
```kotlin
@Serializable data class StateFrame(
  val v: Int,
  val seq: Long,
  val capture: CaptureState,        // RUNNING | PAUSED(records: List<PauseRecord>) | DEFERRED(reason) | BLOCKED(reason)
  val nextCaptureAtMs: Long?,
  val intervalSec: Int,
  val glassesBatteryPct: Int?,
  val phoneLinked: Boolean,
  val aiHealth: Health,             // OK | DEGRADED(shortText) | BLOCKED(shortText)
  val queueDepth: Int,
  val lastSentAtMs: Long?,
  val latestNudge: NudgeLine?,      // one-liner for Glance
  val today: List<MemoryLine>,      // top 8, newest first
  val overlay: Overlay?,            // Listening(partial) | Answer(q, text, speaking) | Nudge(text) | Confirm(proposalId, title, detail) | PausedSplash | Toast(text)
  val error: String?                // short human text only (§6.3)
)
```
Broadcast on change, throttled to ≤ 4 Hz; full frame (not deltas).

### 1.7 Lifecycle
`ContextHubService` (phone, foreground) owns: CXR session, voice pipeline, nudge engine trigger, `StateBroadcaster`. ViewModels observe repository/hub `StateFlow`s. Capture *timing* lives on the glasses (`CaptureScheduler`) so it survives link drops; glasses buffer up to 200 frames (≈ 4 MB at ~20 KB/frame, persisted in app storage so a process restart keeps them) while offline or while the glasses app is closed, and flush when the session is open again; at ~165 ms/frame a full buffer flushes in ≈ 35 s (≈ 70 s right after a BT reconnect). Frames are **sent only while the CUSTOM_APP session is open**, i.e. the glasses `MainActivity` has not exited (it may be paused/stopped by display sleep — phase 0 verified delivery then). When the activity exits, the phone session closes (`GLASSES_EXIT`) and glasses-side `sendMessage` still returns 0 while messages are dropped; rc=0 is also returned during a BT pause, so only `pc_frame_ack` proves delivery. The glasses track session-open locally (set on the first phone message of a session, cleared in `onDestroy`/exit) and buffer whenever it is closed. **Reconnect policy (as Live Fit):** `ContextHubService` does **not** auto-reconnect on `GLASSES_EXIT` (that would reopen the activity and steal the foreground from a user who pressed Back or from Hi Rokid); it starts `GlassAppWatcher` and reconnects when the user reopens Proactive Context on the glasses, or on an explicit phone action (e.g. Home "Open on glasses"). BT drops pause/resume the session automatically (~2 s after BT returns).

**State authority exception — capture pause.** The phone is the authority for everything in `StateFrame` *except* whether capture is allowed: pause is enforced locally on the glasses as well and reconciled by the most-restrictive-wins rule in §5.5. Nothing (reconnect, stale `StateFrame`, settings push) can resume capture implicitly.

### 1.8 Frame transfer (glasses → phone)
- Frames whose Base64 form exceeds the safe per-message Caps string size (`SAFE_CHUNK_CHARS` = 32 768; phase 0 saw no limit up to 256 KB) are split into chunks with `frameId`, `chunkIdx`, `chunkCount`, `crc32` of the whole JPEG.
- Acks are frame-level only; per-chunk acks are not needed for flow control (phase 0). Throughput ≈ 165 ms per 20 KB frame, roughly halving right after a BT reconnect.
- Phone reassembles per `frameId`; incomplete frames are discarded after 10 s without a new chunk; CRC mismatch → `pc_frame_ack{ok=false}` → glasses resend once, then drop.
- Glasses send one frame at a time (stop-and-wait on `pc_frame_ack`, 5 s timeout → retry from chunk 0, max 3 attempts, then keep in buffer for next flush).
- Interrupted flush (link drop mid-frame): un-acked frame stays in buffer; next flush restarts it from chunk 0. Phone dedupes by `frameId`, so a frame acked-but-ack-lost is not ingested twice.
- **PTT priority:** while `Listening`, frame flush is suspended; `pc_audio` is never queued behind frame chunks. Flush resumes after `pc_listen_end` + answer delivered.

---

## 2. Phone app

### 2.1 Navigation
- NavHost, edge-to-edge. Start = `onboarding` until complete, then `home`.
- Floating **PillNav** (Live Fit style), 3 tabs: **Home · Activity · Settings**. Hidden on onboarding and full-screen viewers. Content bottom padding 84 dp; snackbars 96 dp from bottom.
- Routes: `onboarding`, `home`, `ask/{id}`, `activity?period=`, `day/{date}`, `memory/{id}`, `frames/{date}?start=`, `settings`, `settings/glasses`, `settings/phone-inputs`, `settings/capture`, `settings/privacy`, `settings/endpoints`, `settings/endpoints/{id}`, `settings/roles`, `settings/prompts`, `settings/prompts/{id}`, `settings/prompts/{id}/history`, `settings/retrieval`, `settings/persona`, `settings/sources`, `settings/actions`, `settings/shopping`, `settings/memory`, `settings/diagnostics` (+ sub: queues, tracker, logs).

### 2.2 Global banners
Shown under the header on Home and Activity; one at a time, highest severity first:
1. AI provider blocked → **Fix now** (deep-link to endpoint)
2. Glasses disconnected → **Reconnect**
3. Protocol mismatch → **Update glasses app**
4. Capture deferred (low battery) — amber
5. Capture paused — slate → **Resume**
6. Index rebuild needed / running
7. Memory storage ≥ 70 % (amber) / ≥ 90 % (coral)

### 2.3 Design system (`:core:ui`) — matches Live Fit
- **Light only.** Tokens (`PcColors`): Mint `#14C3A2`, MintDeep `#0E9F86`, Sky `#3D8BFF`, Ink `#15181D`, InkSoft `#6B7280`, Line `#ECEFF3`, Surface `#FFFFFF`, SurfaceSoft `#F6F8FA`. Chip pairs (bg/fg): Mint `E3F8F3/0E9F86`, Sky `E6F0FF/2F6FE0`, Coral `FFEAE5/E5583B`, Amber `FFF4DC/D08A00`, Violet `F0EAFF/7550E0`, Rose `FFE8F1/D63F7A`, Slate `EEF1F5/55606E`. HeaderGradient `DDF7F0 → E8F1FF → FFFFFF`. Primary action gradient Mint → Sky.
- **Type:** system font; headlineMedium 26 sp Bold (-0.3 sp), titleLarge 20 SemiBold, titleMedium 16 SemiBold, bodyMedium 14, labelMedium 12 Medium; big numbers 40–44 sp Bold.
- **Shapes:** cards 20 dp, chips 12 dp, pill nav 32 dp, fields/dialogs 28 dp, primary round buttons CircleShape.
- **Spacing:** screen h-padding 16 dp, header 20 dp, tile gap 12 dp, card padding 14–20 dp, section label start 20 / top 20 / bottom 8.
- **Components:** `SoftCard`, `IconChip`, `TextChip`, `ChipRow`, `SectionLabel`, `ScreenHeader`, `GradientHeader`, `PillNav`, `SegmentedSwitch`, `MetricTile`, `GradientCircleButton`, `GradientButton`, `Banner`, `StatusStrip`, `ConfidenceChip`, `FrameThumb`, `BarChart` (Canvas), `CodeBox` (monospace), `VersionStrip`, `HudPreview` (black 3:4, renders `:core:hud` pages from a fixture `StateFrame`).
- App icon + adaptive icon (glasses glyph on Mint→Sky).

### 2.4 Screens
**Home (Ask-first)** — mock `phone-home.html` option B.
- Gradient header: "Proactive Context", subline `● Capturing · next 4:12` (or Paused/Deferred), ⏸/▶ button.
- Hero SoftCard: 84 dp gradient mic, "Ask about your day", hint "or tap your glasses", 3 suggested-question chips (generated from recent memories; static fallbacks).
- **For you** feed: nudges and past asks, newest first; tap → `ask/{id}` or nudge detail.
- Asking from phone uses phone mic; answer appears inline and is mirrored to HUD when linked.

**Ask detail** — question, answer, "Based on" source cards (thumb + memory text + confidence), 👍 / 👎 (👎 enqueues critic review of cited memories), proposed action + its state.

**Activity** — mock `activity-tab.html` option B + status strip.
- Thin `StatusStrip` on top for every period: `● Capturing · next 4:12 · 🔋82%` + mini ⏸.
- `SegmentedSwitch` Today / Week / Month.
- Today: tiles (Frames, Memories, Answers, Nudges) + timeline (FrameThumb + memory text + ConfidenceChip), grouped Morning/Afternoon/Evening.
- Week/Month: `BarChart` frames per day, totals tiles, day rows → `day/{date}` (timeline for that day).

**Memory detail** — frame (pinch-zoom), facts, tier, confidence, review state; actions **Trust**, **Edit**, **Forget fact**, **Forget moment**. Deletion semantics are defined in §6.1.1.

**Frame viewer** — full-screen swipe across a day's frames with play/pause (replaces current slideshow).

**Settings** — mock `settings.html` (with corrections). Grouped ChipRows:
- **Devices**
  - *Rokid Glasses:* link status, battery, Re-authorize; Verbosity `SegmentedSwitch` Silent/Medium/Max with live `HudPreview`; HUD scale + position (3×3); Gesture mapping (§3.5).
  - *Phone camera & mic:* enable phone mic for Ask; "Capture now" with phone camera.
- **Capture & privacy**
  - *Capture schedule:* interval, burst-on-change toggle.
  - *Privacy:* pause (indefinite / 30 min / 1 h), quiet hours, battery policy (thresholds shown; read-only where locked by DECISIONS.md).
- **AI**
  - *Model endpoints:* list → editor (§4.1) with Test.
  - *Models by role:* picker per role filtered by capability (§4.2).
  - *Prompts:* list with **"Keep prompt versions" toggle in this screen's header** (default on) → editor (§4.3–4.5).
  - *Retrieval:* §4.6.
  - *Persona & language.*
- **Connectors**
  - *Data sources:* Calendar, Contacts, Notes, Location toggles (§5.4).
  - *Actions & tools:* Reminder, Shopping list, Calendar event toggles, each with "Always confirm" (default on); Shopping list viewer; **MCP servers — "Coming in v2"** (disabled row).
- **Memory & system**
  - *Memory:* retention windows (24 h / 7 d / 30 d defaults), quota (100 MB soft), encryption, Export, Delete all (double confirm).
  - *Diagnostics:* queues (restyled QueueScreen), summarizer/critic tracker (restyled), worker runs, logs, export, manual config sync.

**Onboarding** (Live Fit setup scaffold: gradient bg, top progress, 96 dp chip, centered text, primary button): Welcome → Permissions (mic, notifications, calendar optional later) → Glasses (CXR authorize/pair) → AI endpoint (preset pick, key, Test) → Persona & language → Gestures (defaults shown) → Done.

### 2.5 State
One ViewModel per screen; screens are `@Composable fun XScreen(state: XUiState, on…: callbacks)`. No business logic in composables. Repositories expose `Flow`s from Room; hub exposes `StateFlow<HubState>`.

---

## 3. Glasses app

### 3.1 Units
| Unit | Responsibility |
|---|---|
| `MainActivity` | Single activity, FLAG_KEEP_SCREEN_ON, hosts `HudRoot`, forwards key events to `InputRouter`. |
| `HudController` | CXR bridge (`CXRServiceBridge.subscribe` / `sendMessage`); exposes `StateFlow<StateFrame>`, `StateFlow<HudSettings>`; sends `Command`, frames, audio. |
| `CaptureService` | Foreground camera service — **only** capture path. Contains `CaptureScheduler` (AlarmManager), `FrameGuard` (dark-frame, pHash dedupe, battery ≤ 10 %, throttle), JPEG encoder (q62, 360 px), `OutboundBuffer` (cap 200 ≈ 4 MB on disk, FIFO drop-oldest); flushes only while the session is open (§1.7). |
| `InputRouter` | Key events → `SwipeClassifier` + `DoubleTap` → gesture → mapped action (per `HudSettings.gestures`). |
| `PushToTalk` | Mic capture → `pc_audio` chunks → `pc_listen_end`. |
| `HudNav` | Page/scroll state machine, 6 s idle → Glance. |
| `ui/` | `GlancePage`, `TodayPage`, `StatusPage`; overlays `Listening`, `AnswerCard`, `NudgeToast`, `ConfirmOverlay`, `PausedSplash`, `Toast`. |

### 3.2 Pages & overlays — mock `glasses-hud.html`
- **Glance (default):** status row (REC dot / ❚❚, link glyphs, battery), outlined box: NEXT + countdown (large tabular digits), latest nudge one-liner. Bottom-anchored.
- **Today:** recent memories (time at 60 %, text at 100 %), outlined highlight on the selected row; Scroll mode via `HudNav`.
- **Status:** capture interval, phone link, AI health, queue depth, last sent — short human text.
- **Listening:** pulsing ring + mic glyph, live partial transcript, hint "tap to send · double-tap cancel".
- **Answer:** "YOU ASKED" (60 %) + question; answer in outlined box in lower band; "🔊 speaking · swipe to dismiss".
- **Nudge:** outlined toast "HEADS UP" + text; auto-hide 8 s.
- **Confirm:** Live Fit `ConfirmOverlay` ✓/✕; auto-hides after 15 s without voting (proposal stays answerable on phone — §5.3).
- **Paused:** splash 3 s, then ❚❚ persists in status row.
- Page dots at bottom.

### 3.3 Rendering rules (from Live Fit)
`Hud.Green = #3CFF6E`; black = transparent; outlines not fills, strokes ≥ 2 dp (highlight 3–4 dp); brightness hierarchy PRIMARY 1.0 / SECONDARY 0.6 / TERTIARY 0.35; tabular digits (`tnum`); Material **Outlined** icons only; centre kept clear (cards in lower band); `Scaled(scale, position)` wrapper — default scale 0.4 BottomCenter for pages, overlays at 1.0; corner radii 12–16 dp overlays, 6 dp badges.

### 3.4 Verbosity
- **Silent:** REC dot only; overlays only for user-initiated flows (Listening, Answer, Confirm, Paused). Confirm can therefore only arise from an Ask; nudge-proposed actions surface on the phone only (full matrix in §5.3).
- **Medium (default):** Glance page shown when idle; nudges only as Glance one-liner.
- **Max:** Medium + auto-shown Nudge toasts + Status page in swipe rotation.

### 3.5 Gestures
Defaults: **tap = ask**, **double-tap = pause/resume** (cancel while Listening; dismiss on Answer), **swipe = next/prev page**, **long-press reserved** for "Hi Rokid". Remappable in Settings → Glasses → Gestures among actions: Ask, Pause/Resume, Next page, Prev page, Dismiss, None. Vertical swipes not sensed.

### 3.6 TTS & offline
- TTS: phone `TextToSpeech`, played through glasses speaker via the existing BT A2DP route (verified in phase 0). YodaOS has no TTS engine (glasses `TextToSpeech` init fails), so there is no glasses-side fallback: without the BT audio route the answer is HUD-only (text is already in `StateFrame.overlay`).
- Link down: capture continues into buffer; Glance shows "📱 offline" (dotted battery ring per Live Fit); tap shows Toast "Phone not connected"; flush on reconnect.
- Never render raw errors; only `StateFrame.error` short strings or local mapped strings.

---

## 4. AI layer, prompts, retrieval

### 4.1 Endpoint registry (`endpoint` table)
Fields: `id`, `name`, `kind` (`AZURE_OPENAI | OPENAI_COMPAT | OLLAMA | LLAMACPP_LOCAL`), `baseUrl`, `apiVersion?`, `models: List<String>`, `keyRef` (key stored in EncryptedSharedPreferences), `capabilities: Set<CHAT, VISION, EMBEDDINGS, JSON_SCHEMA>`.
- **Test:** one-token chat (or one embedding for embedding-only); shows latency + short result.
- Embedding support per kind: Azure/OpenAI-compat `/embeddings`; Ollama `/api/embed` (e.g. `nomic-embed-text`, `bge-m3`); custom = any OpenAI-compatible URL; llama.cpp local embeddings.
- First launch seeds a default endpoint from `.env` BuildConfig values.

### 4.2 Roles (`role_binding` table)
Roles: `VISION_EXTRACT`, `EXPLICIT_REMEMBER`, `SUMMARIZE`, `CRITIC`, `ANSWER`, `NUDGE`, `EMBED`. Binding = endpoint + model + params (temperature, maxTokens). Picker lists only endpoints with the needed capability (VISION_EXTRACT→VISION, EMBED→EMBEDDINGS, others→CHAT).

### 4.3 Prompts & versions (`prompt`, `prompt_version` tables)
- One prompt per role (except EMBED) + a shared `SYSTEM` preamble. Seeds from `assets/prompts/`: `system_global`→SYSTEM, `write_context_batched`→VISION_EXTRACT, `write_context_explicit`→EXPLICIT_REMEMBER, `re_summarize`→SUMMARIZE, `critic_validation`→CRITIC, `read_context_answer`→ANSWER; `NUDGE` new.
- `prompt_version`: `promptId`, `version`, `template`, `outputSchema` (JSON Schema, nullable), `createdAt`, `note`, `testedOk`. Exactly one active version per prompt.
- **Versioning on (default):** Save → new vN+1, activated. History: view, diff (line diff of template + schema), restore (= copy into new version).
- **Versioning off:** Save overwrites the active version in place; existing history kept read-only.
- Template variables: `{{persona}}`, `{{language}}`, `{{recent_context}}`, `{{retrieved_memories}}`, `{{context_sources}}`, `{{question}}`, `{{now}}`. Unknown variables flagged in editor.

### 4.4 Output schema & guided decoding
- Editor section "Output schema": JSON Schema text, pre-filled with the role's default; **Reset to default**. Version stores template + schema together.
- `StructuredOutput` adapter per kind: Azure/OpenAI `response_format: {type: json_schema, strict: true}`; Ollama `format: <schema>`; llama.cpp schema→GBNF grammar; fallback (no `JSON_SCHEMA` capability) = schema appended as instructions.
- **Canonical vs wire schema.** The stored (canonical) schema is full JSON Schema and is the only thing validation uses. Each adapter compiles it via `WireSchemaCompiler.compile(canonical, kind) → WireSchema` into the subset that backend accepts; the wire schema only steers decoding.
  - **Azure/OpenAI strict:** every object gets `additionalProperties: false` and lists **all** properties in `required`; each optional property becomes required with type `[T, "null"]` (nullable); `oneOf` → `anyOf` (discriminated unions keep their `type` `const`/single-value `enum`); root stays a plain object (union only below root); unsupported keywords are stripped — strings `minLength`/`maxLength`/`pattern`/`format`, numbers `minimum`/`maximum`/`exclusive*`/`multipleOf`, arrays `minItems`/`maxItems`/`uniqueItems`/`contains`, objects `minProperties`/`maxProperties`/`patternProperties`/`propertyNames` — and each stripped constraint is appended in words to that property's `description` (e.g. "1–80 characters"). `$defs`/`$ref` kept only if supported, else inlined.
  - **Ollama / llama.cpp:** same nullable/required normalisation and `oneOf → anyOf`; keywords the grammar converter doesn't support are stripped into `description` the same way.
  - **Limits:** compiled wire schema must satisfy the target kind's limits (Azure/OpenAI: nesting depth ≤ 5, ≤ 100 properties total, enum values within limit). `SchemaCompatibilityChecker` compiles the draft for every endpoint currently bound to that role and blocks Save if any compile fails or exceeds limits (error names the endpoint + rule).
  - **Decode path:** response → convert `null` for originally-optional properties back to *absent* → validate against the **canonical** schema (all stripped constraints enforced locally) → role contract decoder → semantic checks (§4.4.1). Failure → the single retry with the validation error, as above.
  - Test bed shows the compiled wire schema (read-only tab) alongside the canonical one.
- All outputs validated against schema; on failure: one retry including the validation error; then job → retry queue (existing policy).
- Default schemas: VISION_EXTRACT / EXPLICIT_REMEMBER `{facts:[{text,type,confidence,tags[]}]}`; SUMMARIZE (current format); CRITIC `{verdict: pass|fail, reasons[]}`; ANSWER `{answer, sources:[memoryId], proposedAction?: ProposedAction}`; NUDGE `{shouldNudge, text, why, sourceIds[], priority, proposedAction?: ProposedAction}`.
- `ProposedAction` (shared JSON Schema `$defs`, `oneOf` discriminated by `type`; identical in ANSWER and NUDGE):
  - `REMINDER {title: string (1–80), dueAt: string (ISO-8601 local date-time, must be in the future), note?: string (≤ 200)}`
  - `SHOPPING_ADD {items: [{name: string (1–40), qty?: number > 0, unit?: string (≤ 10)}] (1–10 items)}`
  - `CALENDAR_EVENT {title: string (1–80), startAt: ISO-8601 local date-time, endAt?: ISO-8601 (> startAt; default startAt + 30 min), location?: string (≤ 80)}`
  - Decoded into Kotlin `sealed interface ProposedAction`; the phone assigns a `proposalId` (UUID) on receipt.

### 4.4.1 Role contracts
- Each role has a fixed **contract** = the Kotlin decoder type the pipeline consumes (e.g. `AnswerOutput`, `NudgeOutput`, `ExtractOutput`, `CriticOutput`, `SummaryOutput`) plus semantic checks. The contract, not the user's schema, is the source of truth.
- The user-editable schema must be a **refinement** of the contract's base schema. Allowed: edit `description`s/titles; tighten constraints (`maxLength`, `minItems`/`maxItems`, numeric bounds, `enum` subsets for `type`/`priority`); add *optional* extra properties (decoded into an `extras: JsonObject`, ignored by the pipeline). Not allowed: removing, renaming or retyping contract properties; making contract-required properties optional; removing `ProposedAction` variants' required fields.
- `SchemaCompatibilityChecker` validates refinement statically in the editor (inline errors; Save disabled while incompatible).
- Semantic checks per role (run in test bed and production): ANSWER/NUDGE source ids ⊆ retrieved/input ids; `confidence ∈ [0,1]`; `ProposedAction` dates parse and are in the future; NUDGE `text` non-empty when `shouldNudge`.

### 4.5 Test bed
- Inputs: sample frame (picker over recent frames), free text, or canned question (ANSWER/NUDGE use real retrieval).
- Model selector (defaults to role binding). **Run** executes the *draft* template + schema.
- Output panel: raw output, latency, tokens, schema-validation result.
- **Save as vN & activate** enabled only when **all** hold for the current, unmodified draft: (1) schema passes `SchemaCompatibilityChecker` (§4.4.1); (2) a test run completed; (3) its output validated against the draft schema; (4) it decoded through the role's contract decoder; (5) it passed the role's semantic checks. The output panel shows each gate as ✓/✕. Any edit re-disables. Discard reverts draft.
- Templates are checked too: required variables per role (e.g. ANSWER needs `{{question}}` and `{{retrieved_memories}}`) must be present.

### 4.6 Retrieval (`:services:memory`)
- `LexicalRetriever`: Room FTS4 `memory_fts` over a normalised `searchText` (NFKC, lowercase, punctuation/symbols/emoji → space); BM25 computed in Kotlin from `matchinfo`. English and Hindi use word tokens, with Devanagari combining marks preserved inside words. Validation covers English, Hindi, and mixed English/Hindi text.
- `SemanticRetriever`: embed query via EMBED binding; brute-force cosine over `memory_vector` BLOBs behind `VectorIndex` interface (swappable later).
- `HybridRetriever`: both, merged by `FusionStrategy`:
  - `WeightedScore` — min-max normalised, semantic/lexical weight slider (default 60/40).
  - `RRF` — `k` configurable (default 60).
  - `CombMNZ` — sum of normalised scores × number of retrievers that hit.
  - Strategy chosen via dropdown; each shows its own config.
- Shared tuning: top-k (default 8), recency boost toggle + half-life (default 3 days), min confidence.
- Settings → Retrieval: mode `SegmentedSwitch` Lexical / Semantic / Hybrid; fusion dropdown + config (Hybrid only); embedding binding shortcut; index stats + **Rebuild**; tuning; **Try it** search showing ranked hits with scores.
- **Embedding space identity.** Vectors belong to an `embedding_space`, never to a bare model name. Fingerprint = SHA-256 over the canonical JSON of: endpoint `kind`; normalised `baseUrl` (scheme + host lowercased, default port removed, path case preserved, no trailing slash); `apiVersion`; deployment/model name; model revision/digest when the backend exposes it (Ollama `digest`); requested `dimensions` (if set) and returned `dim`; normalisation (L2 applied by us: yes/no); document/query input prefixes or task types (e.g. `search_document:` / `search_query:`); truncation/max input tokens. Endpoint display name and key are excluded.
- **Canary check.** At space creation we embed 3 fixed canary strings and store their vectors. On app start (max once per 24 h) and before any rebuild swap, canaries are re-embedded; cosine < 0.995 for any canary → space marked `STALE` (catches a silently updated model behind the same name) → rebuild banner.
- **Generations.** `embedding_space.state ∈ BUILDING | ACTIVE | STALE | RETIRED`; at most one `ACTIVE` and one `BUILDING`. `SemanticRetriever` reads vectors only from the `ACTIVE` space and embeds the query with that same space's config; it never reads `BUILDING` rows.
- **Reindex.** EMBED binding change (fingerprint differs) or `STALE` → banner + `ReindexWorker` writes into a new `BUILDING` space. Swap to `ACTIVE` happens in one Room transaction only when coverage = 100 % of eligible memories; the old space becomes `RETIRED` and its vectors are deleted. During build: if the old `ACTIVE` space's endpoint is still reachable and not `STALE`, semantic keeps using it; otherwise semantic falls back to lexical (Hybrid → lexical only), shown in Retrieval "Try it" and the banner. New memories created during a build are embedded into both spaces.

### 4.7 Answer path
Question → retriever (current mode) → ANSWER prompt with top-k memories (+ frame refs) and `{{context_sources}}` → guided decode → `ask` row → HUD overlay + TTS + phone feed; `proposedAction` → §5.3.

---

## 5. Flows

### 5.1 Ask (push-to-talk)
1. Tap on glasses → `ListenStart` + `pc_audio` stream; HUD Listening with partials (from phone STT via `StateFrame.overlay`).
2. End: tap again, or 1.5 s silence (`EnergyVad`) → `pc_listen_end`. Double-tap cancels.
3. Phone: on-device STT final → answer path (§4.7) → Answer overlay + TTS → `ask` row in Home feed.
4. Phone hero mic: same path, phone mic.
5. Swipe dismisses Answer. Errors → short Toast ("Couldn't hear that", "AI unavailable").

### 5.1.1 STT capability gate
Glasses-audio STT feeds our PCM into the recognizer via `RecognizerIntent.EXTRA_AUDIO_SOURCE` (Live Fit `AndroidOnDeviceStt`). Phone `minSdk` stays 29, so this is gated:

| Capability | Requirement | Check |
|---|---|---|
| `GLASSES_VOICE` | API ≥ 33 **and** `SpeechRecognizer.isOnDeviceRecognitionAvailable` **and** language pack installed **and** external-audio verified | `VoiceCapabilityProbe` |
| `PHONE_VOICE` | API ≥ 31 **and** `SpeechRecognizer.isOnDeviceRecognitionAvailable` **and** language pack installed; always created via `createOnDeviceSpeechRecognizer`. The ordinary (possibly network-backed) `SpeechRecognizer` is **never** used. | same probe |
| Pack download | API ≥ 34 (Live Fit `SpeechPacks`); below 34 → deep-link to system speech settings | — |

- **External-audio verification:** the recognizer must not silently fall back to the phone mic. The probe (onboarding + after OS/app updates) streams a paced ≥ 96 KB (≥ 3 s, above the 64 KiB pipe capacity) PCM feed through the `ParcelFileDescriptor` pipe and counts bytes the recognizer reads (writes block near 64 KiB when nobody reads); if it reads < 50 % of the feed, or a session returns results while pipe reads stay ~0, `GLASSES_VOICE` = unsupported. In every production session a guard aborts (never answers from phone-mic audio) and re-probes capability when partials/results arrive while < 64 KiB has been consumed after > 2 s of fed audio; writes to the pipe are non-blocking/timed so a non-reading recognizer cannot hang the feeder. Utterances shorter than ~2 s fit in the pipe buffer and rely on the onboarding probe.
- **Final result:** on-device `onResults` may return a null/empty `RESULTS_RECOGNITION` after complete partials (phase 0); the transcript is then the last non-empty partial.
- **Unavailable UX:**
  - Glasses tap = Ask → HUD Toast "Voice not supported on this phone" (or "Download speech pack on phone"); gesture map greys out Ask with reason.
  - Phone Home hero: mic works only via `PHONE_VOICE`; otherwise mic shows disabled state + "Type a question" field (typed Ask uses the same answer path). No audio ever leaves the phone for transcription.
  - Settings → Devices → Phone camera & mic shows a Voice status row: Supported / Needs speech pack (Download / Open settings) / Needs Android 13+ for glasses voice.
  - Onboarding Permissions step includes the probe and shows the same status.
- Cloud transcription fallback is **out of scope for v1**.

### 5.2 Nudges
- `NudgeEngine` worker: after each summarizer pass, max every 30 min.
- Input: recent memories, enabled data sources (e.g. next calendar event), last 24 h of nudges.
- NUDGE prompt → `{shouldNudge, text, why, sourceIds[], priority, proposedAction?}` (§4.4).
- Gates: quiet hours; daily cap (default 6, configurable); dedupe hash over 24 h; verbosity (Silent → phone feed only; Medium → Glance one-liner; Max → toast).

### 5.3 Actions (v1 built-ins)
- Source: `proposedAction` in ANSWER or NUDGE output (typed per §4.4). Phone assigns `proposalId` and writes a `proposal` row (`state = PENDING`, `origin = ASK | NUDGE`, `expiresAt`).
- **Behaviour matrix** (per action type; settings in Connectors → Actions):

| Action enabled | Always confirm | Phone surface | HUD surface | Execution |
|---|---|---|---|---|
| Off | — | Suggestion text on the card + "Enable in Settings" link; no buttons | Nothing (answer text only) | Never; proposal stored as `SUPPRESSED` |
| On | On (default) | Confirm card (✓/✕) on Ask detail / nudge card + notification action | Confirm overlay **iff** origin = ASK (any verbosity), or origin = NUDGE and verbosity = Max. Otherwise none. | On ✓ only |
| On | Off | Result card "Added · Undo" (10 s) | Toast "Added · double-tap undo" (10 s) iff the HUD would have shown a Confirm per the row above; else none | Immediately; Undo deletes the created reminder/item/event by id |

- **Expiry (phone-owned):** `expiresAt` = +60 s for ASK, +10 min for NUDGE. The HUD overlay auto-hides after 15 s **without voting**; the proposal remains answerable on the phone until `expiresAt`, then → `EXPIRED`. No surface ever converts a timeout into ✓.
- **Single execution:** all responses (HUD `Confirm(proposalId, yes)`, phone tap, notification action) go to `ProposalRepository.resolve(proposalId, decision)`, a Room transaction doing compare-and-set `PENDING → EXECUTING | DECLINED`. First writer wins; later responses are no-ops and the surface re-renders the final state (`StateFrame.overlay` cleared, phone card shows "Already added" / "Declined" / "Expired"). Expiry uses the same CAS (`PENDING → EXPIRED`). Executors are idempotent on `proposalId` (stored on the created reminder/item/event) so a crash between `EXECUTING` and `EXECUTED` is retried safely on restart.
- Execution: Reminder → in-app AlarmManager notification; Shopping list → Room `shopping_item`; Calendar event → `CalendarContract` insert (needs WRITE_CALENDAR, requested on enable). Failure → `FAILED` + UserFacingError.
- Every proposal + state transition → `action_log`.
- `ToolConnector { listTools(); invoke(name, args) }` interface; `McpConnector` stub, not registered; Settings row "Coming in v2".

### 5.4 Data sources
Toggles with on-demand permission: Calendar (read, next 24 h), Contacts (name matching), Notes (shopping list + user notes), Location (coarse place label on frames). Injected as `{{context_sources}}` for ANSWER/NUDGE. Never sent to glasses.

### 5.5 Privacy pause
- Triggers: glasses double-tap (default map), Home/Activity ⏸, quiet hours, hub notification action.
- Durations: indefinite / 30 min / 1 h.
- Effect: `CaptureService` stops scheduling and **drops** buffered frames; HUD REC → ❚❚ (+3 s splash); phone slate banner + Resume.

**Ownership & offline rules (fail-closed):**
- A pause is a `PauseRecord {pauseId (UUID), origin: GLASSES | PHONE | QUIET_HOURS, createdAt, untilMs? (null = indefinite)}`. Capture is allowed only when the set of *active* records is empty. Each side persists its set (glasses: DataStore; phone: Room) so pauses survive process death and reboot.
- **Glasses-local enforcement:** a double-tap pause is applied on the glasses immediately and persisted locally, whether or not the phone is linked. `CaptureService` checks the local set before every capture.
- **Quiet hours offline:** the schedule is part of `HudSettings` and cached on the glasses; the glasses evaluate it on their own clock and synthesise a `QUIET_HOURS` record locally. No phone link is needed.
- **Timed pauses** expire independently on each side using `untilMs` (the phone converts with the clock offset measured from `pc_hello`).
- **Closed intervals are retained.** When a record ends (expiry or Resume) it is not deleted: the phone stores a `pause_interval(pauseId, origin, startMs, effectiveEndMs)` with `effectiveEndMs` = the phone-clock time the pause actually stopped applying (expiry time, or Resume time — for a pause first learned on reconnect, the time of the Resume that cleared it). Open intervals have `effectiveEndMs = null`. Intervals are kept 7 days. Glasses drop any buffered frame older than 72 h, so every flushed frame falls inside the retained history window.
- **Reconnect reconciliation:** on `pc_hello` the phone merges sets by **union** (most-restrictive wins) and pushes the merged set back in `StateFrame`/`HudSettings`. A record disappears only through (a) its own expiry or (b) an explicit `Resume(clears)` that names its `pauseId`.
- **Resume never clears unknown pauses:** a Resume issued from either side carries only the `pauseId`s that side currently knows. If the merge then reveals a pause the user had not seen (e.g. paused on glasses while offline, resumed on phone), capture stays paused and the phone shows "Still paused on glasses — Resume?" to get an explicit second Resume.
- **Frames captured while the phone believed capture was paused** (phone paused, glasses offline and unaware): on flush, the phone drops any frame whose capture time (offset-corrected) falls inside **any** retained `pause_interval` — open or closed — acks it `ok=true, reason=paused_drop` so the glasses delete it, and never ingests it. Fail closed: frames are held (not ingested) until a `pc_hello` clock offset exists; a frame older than the oldest retained interval history is dropped.

### 5.6 Input devices
Glasses (default capture + PTT). Phone mic (Ask from phone). Phone camera: manual "Capture now" only (enters the same ingest pipeline with `source=phone`).

---

## 6. Data, migration, errors, testing, delivery

### 6.1 Room schema (KSP; optional SQLCipher bound to encryption toggle)
`frame(id, ts, path, source, payload, luma, phash, placeLabel)` (`payload` = legacy metadata excerpt) · `memory(id, frameId?, text, type, tags, tier, confidence, reviewState, trusted, createdAt, derived: Boolean)` + `memory_fts` (FTS4) · `memory_source(memoryId, sourceMemoryId)` (provenance for derived/summary memories) · `embedding_space(id, fingerprint, configJson, dim, state, canaryJson, createdAt)` · `memory_vector(memoryId, spaceId, blob)` PK `(memoryId, spaceId)` · `forget_tombstone(frameId, createdAt)` · `endpoint` · `role_binding` · `prompt` · `prompt_version` · `ask(id, ts, question, answer, sourcesJson, rating, source, redacted)` · `nudge(id, ts, text, why, sourceIdsJson, priority, hash, shownOn, redacted)` · `proposal(id, origin, originId, type, paramsJson, state, expiresAt, resolvedBy?, createdRef?)` · `action_log(id, ts, proposalId, transition, detail)` · `pause_record` · `pause_interval(pauseId, origin, startMs, effectiveEndMs?)` · `shopping_item(…, proposalId?)` · `note` · `summary_trace`. Frame JPEGs remain on disk (encrypted when enabled).

### 6.1.1 Forget semantics
Two user actions, both behind a confirm dialog that states the scope:
- **Forget fact** — removes one memory: its row, FTS row, vectors in all spaces, and `memory_source` edges. The frame is marked `extractionLocked = true` (via `forget_tombstone(frameId)`): reprocessing, backfill ("Reprocess old photos") and critic re-summarisation **never run extraction on that frame again**, so the fact cannot return in any wording; the frame's remaining memories stay as they are. The frame JPEG is deleted **only if no remaining memory references it**. (Explicit "remember" by the user is unaffected.)
- **Forget moment** — removes the frame JPEG, the `frame` row and **all** memories with that `frameId` (each handled as Forget fact), plus a frame-level tombstone.

Cascade to dependants (same Room transaction, then background work):
- **Derived memories — transitive.** Compute the full descendant closure of the forgotten memories over `memory_source` (recursive CTE; A → B → C ⇒ both B and C). In the same transaction every descendant is hidden from retrieval, FTS and the HUD (`reviewState = INVALIDATED`). Background work then, in topological order, re-summarises each invalidated derived memory from its remaining non-forgotten, non-invalidated sources into a **new** memory id (old id deleted, never reused), or deletes it if no sources remain. Re-summarisation inputs never include invalidated text.
- **Asks and nudges** citing any memory in the forgotten set **or its descendant closure**: `answer`/`text` replaced with "Removed — a source was forgotten", `redacted = true`, sources stripped. Any `PENDING` proposal from them → `EXPIRED`. Because re-summarised memories get new ids, old citations stay redacted.
- **HUD:** next `StateFrame` omits the memory from `today`.
- **Summary traces** referencing the memory keep only ids, no text.
- **Delete all** (Settings → Memory) = Forget moment for every frame + drop all derived memories, asks, nudges, vectors, tombstones.

### 6.2 Legacy migration
`LegacyImportWorker` (once): `MemoryRecordStore`, `FrameStreamStore`, `SummaryTraceStore`, frame inbox → Room; then embed in background; mark done. Legacy files retained one release, then deleted.

### 6.3 Error handling
`UserFacingError` mapper: provider / link / schema / permission failures → `{title, hint, action}` (e.g. "AI key rejected" → Fix now; "Glasses offline" → Reconnect). Persistent conditions → banners (§2.2). Full detail → Diagnostics logs only. Glasses receive only short strings.

### 6.4 Testing
- **Unit:** fusion strategies (fixtures), BM25, cosine, prompt versioning rules (save / overwrite-when-off / restore / one-active invariant), schema validate + retry, StructuredOutput request shape per kind, `SchemaCompatibilityChecker` (allowed vs forbidden edits), role contract decoders + semantic checks, save-gating gates, `ProposedAction` decoding, proposal CAS (concurrent HUD/phone/expiry → exactly one terminal state, one execution), action matrix, pause merge/resume-clears/offline-drop rules, embedding fingerprint + canary staleness + generation swap (no mixed-space reads), Forget fact/moment cascades + extraction lock + transitive closure (A→B→C) invalidation and redaction, WireSchemaCompiler per kind (golden wire schemas + null→absent round-trip), closed pause interval filtering, frame chunk reassembly/CRC/dedupe, STT capability probe decisions, nudge gating, SwipeClassifier / DoubleTap / HudNav (ported), `StateFrame` serialization round-trip + version check, existing worker/policy/provider tests relocated.
- **Room:** in-memory DAO + FTS tests; migration import test with fixture legacy files.
- **Compose UI:** smoke per screen (Home, Activity, Settings tree, Prompt editor save-gating, Retrieval, Onboarding); HUD pages rendered from fixture StateFrames.
- **Screenshots:** reference screens in `docs/screens/phone/` and `docs/screens/hud/`; existing root `phone-*.png`, `window-*.xml`, `.tmp_glasses_*.png`, `check-*.png` move to `docs/screens/legacy/`.
- **On-device checklist** (scripted adb, phone + glasses): link, capture→memory, ask→answer→TTS, nudge, action confirm (incl. simultaneous phone + HUD response), pause/resume, offline pause on glasses then reconnect (stays paused), offline buffer flush incl. mid-frame link drop, Forget fact vs Forget moment.

### 6.5 Delivery phases (each buildable + demoable)
0. **Spikes** (results recorded in `docs/spikes/`):
   - **CXR glasses → phone JPEG** (Live Fit's binary path is phone → glasses, so this direction is unproven): max per-message payload; throughput for 20–40 KB frames; chunking + reassembly per §1.8; CRC; ack round-trip; interrupted flush (kill link mid-frame, verify resume from chunk 0 and phone dedupe); 20-frame backlog flush time; concurrent PTT — audio latency while a flush is in progress and with flush suspended.
   - **TTS** via glasses BT audio route (+ glasses-side engine availability).
   - **STT** on the target phone: API level, on-device availability, pack install, external-audio byte-count probe (§5.1.1).
1. Module restructure + `:core:model` / `:core:ui` / `:core:hud` (no behaviour change; build + existing tests green).
2. Data layer: Room, legacy import, endpoint registry + role bindings.
3. CXR link replaces RFCOMM; frames end-to-end; glasses Glance / Today / Status.
4. Phone shell: NavHost, PillNav, Home, Activity, banners, Memory/Frame detail.
5. AI + Settings: prompts + schemas + versions + test bed; retrieval + fusion; all Settings screens.
6. Voice + answers: PTT, STT, Answer overlay, TTS.
7. Nudges, actions, data sources, privacy pause, onboarding.
8. Hardening: polish, screenshots, on-device checklist.

---

## 7. Open risks
- **CXR glasses → phone binary transfer** for ~20–40 KB JPEGs — unproven direction; phase 0 spike; chunking protocol already specified (§1.8).
- **STT external-audio support** varies by phone/recognizer — capability gate + byte-count guard (§5.1.1).
- **TTS audio route** to glasses speaker — verified in phase 0 (BT A2DP); without BT audio the answer is HUD-only (§3.6).
- **On-device STT language packs** on the phone — reuse Live Fit `SpeechPacks` flow in onboarding.
- **Guided decoding support** varies by deployment/model — capability flag + fallback (§4.4).
- **Brute-force vector search** scale — fine < ~50k memories; `VectorIndex` interface allows swap.

---

## 8. Requirements checklist
Every plan task cites the R-IDs it satisfies. All must be covered.

**Architecture & protocol**
- R-01 Modules per §1.1 with dependency rules; `:app`/`:glasses-app` replaced by `:phone`/`:glasses`.
- R-02 Live Fit pieces copied/adapted per §1.2 (with their tests).
- R-03 Existing backend logic ported per §1.3; existing tests pass in new modules.
- R-04 RFCOMM/SPP code removed per §1.4.
- R-05 CXR channels per §1.5 (incl. `pc_frame_ack`, `pc_hello` clock + pause records) with protocol version + mismatch handling.
- R-06 `StateFrame` per §1.6, full-frame broadcast ≤ 4 Hz.
- R-07 `ContextHubService` owns link/voice/broadcast; no polling loop; glasses-side capture timing + 20-frame offline buffer; pause authority exception (§1.7).
- R-08 Frame transfer per §1.8: chunking, CRC, stop-and-wait ack, delete-after-ack, interrupted-flush restart, phone dedupe by `frameId`, PTT priority.

**Phone**
- R-10 NavHost + 3-tab PillNav + routes per §2.1; onboarding gate.
- R-11 Banner system per §2.2 with severity ordering.
- R-12 `:core:ui` tokens, type, shapes, spacing, components per §2.3; app icon.
- R-13 Home (Ask-first) per §2.4 incl. suggested questions + For you feed.
- R-14 Ask detail with sources, rating, action state.
- R-15 Activity: status strip, Today/Week/Month, bar chart, tiles, day drill-down, timeline.
- R-16 Memory detail (Trust/Edit/Forget fact/Forget moment per §6.1.1) + Frame viewer.
- R-17 Settings tree per §2.4 incl. Devices, Capture & privacy, AI, Connectors, Memory & system, Diagnostics.
- R-18 Glasses settings: verbosity with HudPreview, scale/position, gesture mapping, re-authorize.
- R-19 Onboarding wizard steps per §2.4.
- R-20 One ViewModel per screen; stateless screen composables.

**Glasses**
- R-30 Units per §3.1; single capture path in `CaptureService`; guardrails preserved.
- R-31 Pages Glance / Today / Status and overlays per §3.2.
- R-32 Rendering rules per §3.3 via `:core:hud`.
- R-33 Verbosity behaviour per §3.4.
- R-34 Gestures + remapping per §3.5.
- R-35 TTS path (phone TTS over BT; HUD-only when BT audio is absent); offline behaviour; no raw errors (§3.6).

**AI / retrieval**
- R-40 Endpoint registry with kinds, capabilities, encrypted keys, Test, `.env` seed (§4.1).
- R-41 Embeddings on Azure/OpenAI-compat/Ollama/custom/llama.cpp endpoints.
- R-42 Role bindings with capability-filtered picker (§4.2).
- R-43 Prompt store, seeds, variables, one-active invariant (§4.3).
- R-44 Versioning toggle on Prompts screen (default on); history view/diff/restore; off = overwrite.
- R-45 Output schema per version; default schemas incl. typed `ProposedAction` in ANSWER **and** NUDGE; Reset to default (§4.4).
- R-46 `StructuredOutput` adapters per kind + fallback; `WireSchemaCompiler` canonical → wire subset (nullable-required, `anyOf`, stripped constraints into descriptions, per-kind limits); null→absent normalisation; full local validation against canonical schema; 1 retry + retry queue.
- R-47 Test bed with frame/text/question inputs, model selector, metrics, five-gate save-gating + required template variables (§4.5).
- R-48 Lexical (FTS4 + BM25), Semantic (cosine, `VectorIndex`), Hybrid (§4.6).
- R-49 Fusion strategies WeightedScore / RRF / CombMNZ selectable with per-strategy config.
- R-50 Retrieval tuning (top-k, recency half-life, min confidence) + Try it + index stats/Rebuild.
- R-51 Embedding space identity (fingerprint), canary staleness check, BUILDING/ACTIVE generations with atomic swap, no mixed-space reads, fallback rules (§4.6).
- R-52 Answer path per §4.7.
- R-53 Role contracts: decoders + semantic checks used in production and test bed; `SchemaCompatibilityChecker` enforcing refinement rules (§4.4.1) and wire-compilability for every bound endpoint (§4.4).

**Flows**
- R-60 Push-to-talk ask from glasses and phone per §5.1.
- R-67 STT capability gate, external-audio byte-count probe + production guard (never answer from phone-mic fallback), unavailable-state UX on HUD/Home/Settings/Onboarding, typed-Ask fallback; phone voice on-device only, never network `SpeechRecognizer` (§5.1.1).
- R-61 NudgeEngine with gates per §5.2.
- R-62 Built-in actions Reminder / Shopping / Calendar per the §5.3 behaviour matrix (enabled × confirm × origin × verbosity), phone-owned expiry, HUD timeout never votes, CAS single execution, idempotent executors, Undo, action_log.
- R-63 `ToolConnector` interface + `McpConnector` stub + "Coming in v2" row.
- R-64 Data sources with on-demand permissions, injected as context only (§5.4).
- R-65 Privacy pause triggers, durations, buffer drop, HUD + phone indication; `PauseRecord` persistence on both sides, glasses-local enforcement + offline quiet hours, union merge on reconnect, Resume clears only named pauses, retained closed `pause_interval`s with effective end (7 d) + 72 h glasses buffer age cap, flush filter over open and closed intervals, fail-closed before clock offset (§5.5).
- R-66 Phone camera manual capture into same pipeline (§5.6).

**Data / quality**
- R-70 Room schema per §6.1 (KSP; SQLCipher when encryption on).
- R-77 Forget fact / Forget moment semantics; frame extraction lock (no re-extraction after Forget fact); transitive descendant invalidation + re-summarise into new ids / delete; ask/nudge redaction over the full closure; pending-proposal expiry (§6.1.1).
- R-71 Legacy import worker per §6.2.
- R-72 `UserFacingError` mapping; no raw errors in any UI (§6.3).
- R-73 Test suites per §6.4.
- R-74 Screenshot references + legacy screenshot relocation.
- R-75 On-device checklist script.
- R-76 Phase 0 spikes (CXR G→P JPEG incl. chunking/ack/interrupt/PTT concurrency; TTS; STT probe) executed and recorded in `docs/spikes/` before phase 3.
